#!/usr/bin/env python3
#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The embedder service of VAlbum, see issue #257.

A small HTTP service on 127.0.0.1 that turns text and pictures into vectors of EmbeddingGemma 2
(google/embeddinggemma-2, Apache-2.0), one shared space for both, so that a text query can be
compared with a photograph directly. It runs the ONNX export onnx-community/embeddinggemma-2-ONNX
with onnxruntime; only the text model and the vision encoder are loaded, never the audio encoder.

The server (de.haumacher.imageServer.content) downloads the model, starts this service when a
space asks for content search and talks to it; the service itself never touches the network and
never sees a photograph's name, only the bytes of a preview.

The protocol is the OpenAI embeddings call, with images as Infinity sends them:

    POST /v1/embeddings
    {"input": ["task: search result | query: flowers"], "dimensions": 256}
    {"input": ["data:image/jpeg;base64,..."], "modality": "image", "dimensions": 256}

    -> {"object": "list", "model": "google/embeddinggemma-2",
        "data": [{"object": "embedding", "index": 0, "embedding": [0.012, ...]}],
        "usage": {"prompt_tokens": 10, "total_tokens": 10}}

    GET /health
    -> {"status": "ready", "model": ..., "revision": ..., "precision": "fp32", "dimension": 768}

Texts are embedded as given: the task prefixes of the model card ("task: search result | query: ",
"title: none | text: ") are the client's business, so that what is embedded is visible where it is
decided. Every vector is L2-normalised; a requested dimension below 768 truncates the vector and
normalises it again (Matryoshka). Never fp16: the model card warns of NaN.

Run "embedder.py --help" for the options, "embedder.py bench --help" for the benchmark.
"""

import argparse
import base64
import binascii
import io
import json
import math
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The heavy imports (numpy, onnxruntime, tokenizers, PIL) are deferred to the first use, so that
# "--help" and a failing start answer quickly and say what is missing.

HERE = os.path.dirname(os.path.abspath(__file__))

# What the vision encoder expects, from processor_config.json of the pinned revision
# (image_processor: Gemma4ImageProcessor). Changing any of these changes every vector: the
# server's stamp (ContentModel.PREPROCESSING) must change with them.
PATCH_SIZE = 16
POOLING_KERNEL = 3
MAX_SOFT_TOKENS = 280
MAX_PATCHES = MAX_SOFT_TOKENS * POOLING_KERNEL ** 2

# Special tokens of config.json / tokenizer_config.json of the pinned revision.
IMAGE_TOKEN = "<|image|>"
BOI_TOKEN = "<|image>"
EOI_TOKEN = "<image|>"

# The hidden size of the text model, the width of the soft tokens the encoders hand it.
TEXT_HIDDEN = 512

# Bounds of a request; nothing VAlbum sends comes near them.
MAX_BODY = 64 * 1024 * 1024
MAX_ITEMS = 64
MAX_TOKENS = 8192
MAX_PIXELS = 64 * 1024 * 1024

PRECISIONS = ("fp32", "int8")


def read_manifest(path=None):
    """The pinned model: repository, revision and the files of each precision, see model.json."""
    with open(path or os.path.join(HERE, "model.json"), encoding="utf-8") as f:
        return json.load(f)


def model_files(manifest, precision):
    """The names of the ONNX files of the given precision: (text model, vision encoder)."""
    names = [f["name"] for f in manifest["files"][precision] if f["name"].endswith(".onnx")]
    text = [n for n in names if "vision" not in n]
    vision = [n for n in names if "vision" in n]
    if len(text) != 1 or len(vision) != 1:
        raise ValueError("model.json names no text model and vision encoder for " + precision)
    return text[0], vision[0]


def truncate(vector, dimension):
    """The first `dimension` entries of an L2-normalised vector, normalised again (Matryoshka)."""
    import numpy as np

    v = np.asarray(vector, dtype=np.float32)[:dimension]
    norm = float(np.linalg.norm(v))
    if not math.isfinite(norm) or norm == 0.0:
        raise ValueError("the model produced no usable vector (norm %r)" % norm)
    return v / norm


def target_size(height, width):
    """The size an image is resized to: Gemma4ImageProcessor's aspect-ratio preserving rule.

    The largest size with at most MAX_PATCHES patches whose sides are multiples of
    PATCH_SIZE * POOLING_KERNEL, so that the pooled soft tokens tile it exactly.
    """
    target_px = MAX_PATCHES * PATCH_SIZE ** 2
    factor = math.sqrt(target_px / (height * width))
    side = POOLING_KERNEL * PATCH_SIZE
    th = int(math.floor(factor * height / side)) * side
    tw = int(math.floor(factor * width / side)) * side
    if th == 0 and tw == 0:
        raise ValueError("the image is too small: %dx%d" % (width, height))
    max_side = (MAX_PATCHES // POOLING_KERNEL ** 2) * side
    if th == 0:
        th = side
        tw = min(int(math.floor(width / height)) * side, max_side)
    elif tw == 0:
        tw = side
        th = min(int(math.floor(height / width)) * side, max_side)
    return th, tw


def decode_image(data):
    """The pixels of an encoded image (JPEG, PNG, WebP, ...) as an RGB PIL image, upright.

    A preview of VAlbum is upright already; an EXIF orientation is applied anyway, so that an
    image of any other source is seen the way it is shown.
    """
    from PIL import Image, ImageOps

    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        image = Image.open(io.BytesIO(data))
        image = ImageOps.exif_transpose(image)
        return image.convert("RGB")
    except Image.DecompressionBombError as ex:
        raise ValueError("the image is too large: %s" % ex)
    except (OSError, SyntaxError) as ex:
        raise ValueError("not an image this service can read: %s" % ex)


def patches(image):
    """The vision encoder's input of an RGB image: (pixel_values, position_ids, soft tokens).

    Resized with a bicubic filter to target_size(), scaled to [0, 1], cut into PATCH_SIZE squares
    in row-major order (each flattened as rows, columns, channels), padded with zeros to
    MAX_PATCHES; a patch's position is (column, row), -1 for padding.
    """
    import numpy as np
    from PIL import Image

    th, tw = target_size(image.height, image.width)
    if (image.height, image.width) != (th, tw):
        image = image.resize((tw, th), Image.Resampling.BICUBIC)
    pixels = np.asarray(image, dtype=np.float32) * np.float32(1.0 / 255.0)
    ph, pw = th // PATCH_SIZE, tw // PATCH_SIZE
    count = ph * pw
    grid = pixels.reshape(ph, PATCH_SIZE, pw, PATCH_SIZE, 3).transpose(0, 2, 1, 3, 4)
    values = np.zeros((1, MAX_PATCHES, PATCH_SIZE * PATCH_SIZE * 3), dtype=np.float32)
    values[0, :count] = grid.reshape(count, -1)
    positions = np.full((1, MAX_PATCHES, 2), -1, dtype=np.int64)
    rows, cols = np.meshgrid(np.arange(ph), np.arange(pw), indexing="ij")
    positions[0, :count, 0] = cols.reshape(-1)
    positions[0, :count, 1] = rows.reshape(-1)
    return values, positions, count // POOLING_KERNEL ** 2


class Model:
    """The text model and the vision encoder of one precision, run with onnxruntime."""

    def __init__(self, directory, precision, threads, manifest):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self._np = np
        text_file, vision_file = model_files(manifest, precision)
        missing = [f["name"] for f in manifest["files"]["common"] + manifest["files"][precision]
                   if not os.path.isfile(os.path.join(directory, f["name"]))]
        if missing:
            raise FileNotFoundError("missing in %s: %s" % (directory, ", ".join(missing)))
        options = ort.SessionOptions()
        if threads > 0:
            options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        # No busy waiting between the steps of an inference: the service runs beside the server at a
        # lower priority, and spinning threads only take the cores the server needs.
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        providers = ["CPUExecutionProvider"]
        self.tokenizer = Tokenizer.from_file(os.path.join(directory, "tokenizer.json"))
        self.text = ort.InferenceSession(os.path.join(directory, text_file), options, providers=providers)
        self.vision = ort.InferenceSession(os.path.join(directory, vision_file), options, providers=providers)
        self._image_token = self._token(IMAGE_TOKEN)
        self._boi = self._token(BOI_TOKEN)
        self._eoi = self._token(EOI_TOKEN)
        self._vision_inputs = [i.name for i in self.vision.get_inputs()]
        self.dimension = self.text.get_outputs()[1].shape[-1]

    def _token(self, text):
        token = self.tokenizer.token_to_id(text)
        if token is None:
            raise ValueError("the tokenizer knows no " + text)
        return token

    def _run(self, ids, image_features=None):
        np = self._np
        ids = np.asarray([ids], dtype=np.int64)
        empty = np.zeros((0, TEXT_HIDDEN), dtype=np.float32)
        out = self.text.run(["sentence_embedding"], {
            "input_ids": ids,
            "attention_mask": np.ones_like(ids),
            "image_features": empty if image_features is None else image_features,
            "video_features": empty,
            "audio_features": empty,
        })
        return out[0][0]

    def embed_text(self, text):
        """The vector of a text, and its number of tokens (begin and end of text included)."""
        ids = self.tokenizer.encode(text).ids
        if len(ids) > MAX_TOKENS:
            raise ValueError("the text has %d tokens, more than %d" % (len(ids), MAX_TOKENS))
        if self._image_token in ids:
            raise ValueError("a text must not name an image placeholder")
        return self._run(ids), len(ids)

    def embed_image(self, image):
        """The vector of an RGB image, and its number of tokens."""
        values, positions, soft = patches(image)
        features = self.vision.run(None, dict(zip(self._vision_inputs, (values, positions))))[0]
        features = features.reshape(-1, TEXT_HIDDEN)
        if features.shape[0] != soft:
            raise ValueError("the vision encoder gave %d soft tokens, expected %d" % (features.shape[0], soft))
        # As the processor spells "<|image|>" alone: begin of text, begin of image, a placeholder per
        # soft token, end of image, end of text.
        bos = self.tokenizer.encode("").ids
        ids = bos[:1] + [self._boi] + [self._image_token] * soft + [self._eoi] + bos[1:]
        return self._run(ids, features), len(ids)


class Service:
    """The state the HTTP handler answers from: the model once it is loaded, or why it is not."""

    def __init__(self, args, manifest):
        self.args = args
        self.manifest = manifest
        self.model = None
        self.status = "loading"
        self.error = None
        self.lock = threading.Lock()
        self.last_use = time.monotonic()

    def load(self):
        start = time.monotonic()
        try:
            model = Model(self.args.model_dir, self.args.precision, self.args.threads, self.manifest)
        except Exception as ex:  # noqa: BLE001 - every failure is a reason to report, not to die of
            self.error = "%s: %s" % (type(ex).__name__, ex)
            self.status = "failed"
            log("cannot load the model: " + self.error)
            return
        self.model = model
        self.status = "ready"
        log("model loaded in %.1f s (%s, %s)" % (time.monotonic() - start, self.args.precision, self.args.model_dir))

    def health(self):
        result = {
            "status": self.status,
            "model": self.manifest["model"],
            "repository": self.manifest["repository"],
            "revision": self.manifest["revision"],
            "precision": self.args.precision,
            "dimension": self.manifest["dimension"],
        }
        if self.error:
            result["error"] = self.error
        return result

    def embed(self, request):
        """The answer to an embeddings request, as (status, body)."""
        if not isinstance(request, dict):
            return 400, error("the request is no JSON object")
        inputs = request.get("input")
        if isinstance(inputs, str):
            inputs = [inputs]
        if not isinstance(inputs, list) or not inputs or not all(isinstance(i, str) for i in inputs):
            return 400, error("'input' must be a string or a non-empty list of strings")
        if len(inputs) > MAX_ITEMS:
            return 400, error("at most %d inputs per request" % MAX_ITEMS)
        modality = request.get("modality", "text")
        if modality not in ("text", "image"):
            return 400, error("'modality' must be 'text' or 'image'")
        full = self.manifest["dimension"]
        dimension = request.get("dimensions", full)
        if not isinstance(dimension, int) or isinstance(dimension, bool) or not 1 <= dimension <= full:
            return 400, error("'dimensions' must be a number from 1 to %d" % full)
        encoding = request.get("encoding_format", "float")
        if encoding not in ("float", "base64"):
            return 400, error("'encoding_format' must be 'float' or 'base64'")
        if self.status != "ready":
            return 503, error("the model is " + self.status + (": " + self.error if self.error else ""), "unavailable")
        data = []
        tokens = 0
        try:
            images = [decode_image(data_uri(i)) for i in inputs] if modality == "image" else None
            with self.lock:
                for index, item in enumerate(inputs):
                    if images is None:
                        vector, count = self.model.embed_text(item)
                    else:
                        vector, count = self.model.embed_image(images[index])
                    tokens += count
                    vector = truncate(vector, dimension)
                    if encoding == "base64":
                        value = base64.b64encode(vector.astype("<f4").tobytes()).decode("ascii")
                    else:
                        value = [float(x) for x in vector]
                    data.append({"object": "embedding", "index": index, "embedding": value})
                self.last_use = time.monotonic()
        except ValueError as ex:
            return 400, error(str(ex))
        return 200, {
            "object": "list",
            "model": self.manifest["model"],
            "data": data,
            "usage": {"prompt_tokens": tokens, "total_tokens": tokens},
        }


def data_uri(text):
    """The bytes of a data URI ("data:image/jpeg;base64,...") or of plain base64."""
    if text.startswith("data:"):
        head, sep, body = text.partition(",")
        if not sep or not head.endswith(";base64"):
            raise ValueError("an image must be a base64 data URI")
        text = body
    try:
        return base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("an image must be a base64 data URI")


def error(message, kind="invalid_request_error"):
    return {"error": {"message": message, "type": kind}}


def log(message):
    try:
        print("valbum-embedder: " + message, file=sys.stderr, flush=True)
    except (OSError, ValueError):
        # Nobody reads the log any more: the server that started this service is gone.
        pass


class Handler(BaseHTTPRequestHandler):
    server_version = "valbum-embedder"
    protocol_version = "HTTP/1.1"
    service = None

    def log_message(self, format, *args):  # noqa: A002 - the name is http.server's
        # Quiet: every request would be a line in the server's log.
        pass

    def _answer(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            # The client gave up waiting (its time limit); nothing to tell anybody.
            self.close_connection = True

    def do_GET(self):  # noqa: N802 - the name is http.server's
        if self.path == "/health":
            self._answer(200, self.service.health())
        else:
            self._answer(404, error("no such resource: " + self.path))

    def do_POST(self):  # noqa: N802 - the name is http.server's
        if self.path != "/v1/embeddings":
            self._answer(404, error("no such resource: " + self.path))
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY:
            self.close_connection = True
            self._answer(413 if length > MAX_BODY else 411, error("a body of at most %d bytes is needed" % MAX_BODY))
            return
        try:
            request = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeDecodeError):
            self._answer(400, error("the body is no JSON"))
            return
        status, body = self.service.embed(request)
        self._answer(status, body)


def watch_stdin():
    """Exits when stdin closes: the server that started this service is gone."""
    try:
        while sys.stdin.buffer.read(4096):
            pass
    except (OSError, ValueError):
        pass
    log("stdin closed, exiting")
    os._exit(0)


def watch_idle(service, seconds):
    """Exits after `seconds` without an embedding request, to give the memory back."""
    while True:
        time.sleep(min(seconds, 30))
        if time.monotonic() - service.last_use > seconds:
            log("idle for %d s, exiting" % seconds)
            os._exit(0)


def serve(args):
    manifest = read_manifest(args.manifest)
    if args.precision not in PRECISIONS:
        raise SystemExit("valbum-embedder: unknown precision " + args.precision)
    if args.nice:
        os.nice(args.nice)
    service = Service(args, manifest)
    Handler.service = service
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    # The one line on stdout: the server that started this service reads the port from it.
    print(json.dumps({"listening": httpd.server_address[1]}), flush=True)
    log("listening on %s:%d" % (args.host, httpd.server_address[1]))
    threading.Thread(target=service.load, name="load", daemon=True).start()
    if args.exit_with_stdin:
        threading.Thread(target=watch_stdin, name="stdin", daemon=True).start()
    if args.idle_exit > 0:
        threading.Thread(target=watch_idle, args=(service, args.idle_exit), name="idle", daemon=True).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


def bench(args):
    """Times the model on this machine, see issue #257: one line per measurement."""
    import resource

    manifest = read_manifest(args.manifest)
    start = time.monotonic()
    model = Model(args.model_dir, args.precision, args.threads, manifest)
    print("load %.1f s, precision %s, threads %s" % (time.monotonic() - start, args.precision, args.threads or "all"))
    queries = ["task: search result | query: " + q for q in
               ("flowers", "mountains in the evening", "Blumen im Regen", "a rainy street at night", "children playing")]
    model.embed_text(queries[0])
    start = time.monotonic()
    for _ in range(args.rounds):
        for q in queries:
            model.embed_text(q)
    count = args.rounds * len(queries)
    print("text query: %.0f ms each (%d queries)" % ((time.monotonic() - start) * 1000 / count, count))
    images = []
    for path in args.images:
        with open(path, "rb") as f:
            images.append(decode_image(f.read()))
    if images:
        model.embed_image(images[0])
        start = time.monotonic()
        for image in images:
            model.embed_image(image)
        print("image: %.0f ms each (%d images)" % ((time.monotonic() - start) * 1000 / len(images), len(images)))
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    print("peak RSS: %d MB" % (rss // 1024))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="embedder.py", description="The embedder service of VAlbum (issue #257).")
    parser.add_argument("--model-dir", required=True, help="the directory of the model files of model.json")
    parser.add_argument("--precision", default="fp32", choices=PRECISIONS, help="fp32 (default) or int8")
    parser.add_argument("--threads", type=int, default=0, help="threads of one inference, 0 for all cores")
    parser.add_argument("--manifest", default=None, help="the model manifest, model.json beside this file by default")
    sub = parser.add_subparsers(dest="command")
    b = sub.add_parser("bench", help="time text queries and the given images, print the peak memory")
    b.add_argument("images", nargs="*", help="JPEG or PNG files to embed")
    b.add_argument("--rounds", type=int, default=4, help="how often the five sample queries are embedded")
    parser.add_argument("--host", default="127.0.0.1", help="the address to listen on, 127.0.0.1 by default")
    parser.add_argument("--port", type=int, default=0, help="the port, 0 (default) for any free one")
    parser.add_argument("--nice", type=int, default=0, help="lower the priority by this much")
    parser.add_argument("--idle-exit", type=int, default=0, help="exit after this many seconds without a request")
    parser.add_argument("--exit-with-stdin", action="store_true", help="exit when stdin closes")
    args = parser.parse_args(argv)
    if args.command == "bench":
        bench(args)
    else:
        serve(args)


if __name__ == "__main__":
    main()
