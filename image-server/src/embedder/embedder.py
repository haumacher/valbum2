#!/usr/bin/env python3
#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The embedder service of VAlbum, see issue #257.

A small HTTP service that turns text and pictures into vectors of EmbeddingGemma 2
(google/embeddinggemma-2, Apache-2.0), one shared space for both, so that a text query can be
compared with a photograph directly. It runs the ONNX export onnx-community/embeddinggemma-2-ONNX
with onnxruntime; only the text model and the vision encoder are loaded, never the audio encoder.

It runs in one of two ways:

- Started by the album server (de.haumacher.imageServer.content.ManagedEmbedder) as its child on
  127.0.0.1, when a space first asks for content search. The server downloads the model; the
  service never touches the network.
- On its own, on any machine - the package valbum-embedder, or its container image - and called by
  album servers over the network (VALBUM_EMBEDDER_URL on their side). Then it listens on all
  interfaces, fetches the model itself (--fetch, the files of model.json checked against their
  SHA-256) and asks every request for its key (VALBUM_EMBEDDER_KEY, "Authorization: Bearer
  <key>"); TLS is optional (--tls-cert, --tls-key).

Either way it never sees a photograph's name, only the bytes of a preview.

The protocol is the OpenAI embeddings call, with images as Infinity sends them:

    POST /v1/embeddings
    {"input": ["task: search result | query: flowers"], "dimensions": 256}
    {"input": ["data:image/jpeg;base64,..."], "modality": "image", "dimensions": 256}

    -> {"object": "list", "model": "google/embeddinggemma-2",
        "data": [{"object": "embedding", "index": 0, "embedding": [0.012, ...]}],
        "usage": {"prompt_tokens": 10, "total_tokens": 10}}

    GET /health
    -> {"status": "ready", "model": ..., "revision": ..., "precision": "fp32", "dimension": 768,
        "preprocessing": 1}

The status is "downloading" (with --fetch, a "detail" says how far), "loading", "ready" or
"failed" (with an "error"). Every embeddings answer names the revision, precision and
preprocessing it was computed with, so that a client never takes a vector of another model for
one of the model it asked before.

With a key configured, every request without "Authorization: Bearer <key>", the health check
included, is answered 401 with the reason.

Texts are embedded as given: the task prefixes of the model card ("task: search result | query: ",
"title: none | text: ") are the client's business, so that what is embedded is visible where it is
decided. Every vector is L2-normalised; a requested dimension below 768 truncates the vector and
normalises it again (Matryoshka). Never fp16: the model card warns of NaN.

Run "embedder.py --help" for the options, "embedder.py bench --help" for the benchmark.
"""

import argparse
import base64
import binascii
import hashlib
import hmac
import io
import ipaddress
import json
import math
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The heavy imports (numpy, onnxruntime, tokenizers, PIL) are deferred to the first use, so that
# "--help" and a failing start answer quickly and say what is missing.

HERE = os.path.dirname(os.path.abspath(__file__))

# The version of what is made of the model before and around it: the image preprocessing below
# and the task prefixes the client adds. Named in every answer; the album server refuses a service
# whose version is not its own (ContentModel.PREPROCESSING, which a test holds equal to this).
PREPROCESSING = 1

# What the vision encoder expects, from processor_config.json of the pinned revision
# (image_processor: Gemma4ImageProcessor). Changing any of these changes every vector:
# PREPROCESSING, and the server's ContentModel.PREPROCESSING with it, must change with them.
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


# --- The model's files, fetched by the service of its own (--fetch) --------------------------------
#
# The same rules as the album server's ModelStore, and the same files on disk, so that a folder
# prepared by either is taken by the other: the folder <repository name>@<revision[:8]> below the
# model root, each file under its name in the repository, downloaded beside its place (.part),
# resumed with a range request, checked against the SHA-256 of model.json and only then moved into
# place; a record (<name>.download, Java properties) remembers the check, so that a restart does
# not read two gigabytes again. A file placed by hand that is not the pinned one is never touched:
# it is the administrator's, and the service says so and waits.

HUGGING_FACE = "https://huggingface.co/"
RECORD_SUFFIX = ".download"
PART_SUFFIX = ".part"

# How long a download may receive nothing before it is given up (and resumed at the next attempt).
STALL_SECONDS = 120

# The pause after a failed download, doubled with each further one up to the longest.
FIRST_RETRY = 60
MAX_RETRY = 3600


class FetchError(Exception):
    """A file of the model cannot be had now; `permanent` when only the administrator can help."""

    def __init__(self, message, permanent=False):
        super().__init__(message)
        self.permanent = permanent


def model_folder(manifest):
    """The folder of the model below the model root: embeddinggemma-2-ONNX@daa72c51."""
    return manifest["repository"].rsplit("/", 1)[-1] + "@" + manifest["revision"][:8]


def megabytes(count):
    return "%d MB" % ((count + 512 * 1024) // (1024 * 1024))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _escape(value):
    out = []
    for c in value:
        if c in "\\:=#!":
            out.append("\\" + c)
        elif c == "\n":
            out.append("\\n")
        else:
            out.append(c)
    return "".join(out)


def read_record(path):
    """The properties of a record, None where there is none or it cannot be read."""
    try:
        with open(path, encoding="latin-1") as f:
            lines = f.read().splitlines()
    except OSError:
        return None
    result = {}
    for line in lines:
        line = line.strip()
        if not line or line[0] in "#!":
            continue
        key, value = [], []
        target = key
        escaped = False
        for c in line:
            if escaped:
                target.append({"n": "\n", "t": "\t"}.get(c, c))
                escaped = False
            elif c == "\\":
                escaped = True
            elif target is key and c in "=:":
                target = value
            else:
                target.append(c)
        result["".join(key).strip()] = "".join(value).strip()
    return result


def modified_millis(path):
    return os.stat(path).st_mtime_ns // 1_000_000


class Fetcher:
    """The files of one precision of the model in one folder, and the download that fetches them."""

    def __init__(self, directory, manifest, precision, base_url=HUGGING_FACE):
        self.directory = directory
        self.manifest = manifest
        self.files = manifest["files"]["common"] + manifest["files"][precision]
        self.base_url = base_url if base_url.endswith("/") else base_url + "/"
        self.total = sum(f["size"] for f in self.files)
        self.received = 0
        self.current = None

    def progress(self):
        if self.current is None:
            return "checking the model files in " + self.directory
        return "%s of %s into %s, now %s" % (megabytes(self.received), megabytes(self.total), self.directory,
                                              self.current)

    def _target(self, entry):
        return os.path.join(self.directory, *entry["name"].split("/"))

    def checked(self, entry):
        """Whether the file is there and its record says it was checked as it is now."""
        target = self._target(entry)
        record = read_record(target + RECORD_SUFFIX)
        if record is None:
            return False
        try:
            return (record.get("sha256") == entry["sha256"] and os.path.getsize(target) == entry["size"]
                    and record.get("modified") == str(modified_millis(target)))
        except OSError:
            return False

    def ready(self):
        return all(self.checked(entry) for entry in self.files)

    def ensure(self):
        """Makes every file ready; raises FetchError saying what is missing."""
        for entry in self.files:
            self._ensure(entry)
        self.current = None

    def _write_record(self, entry, source):
        target = self._target(entry)
        lines = [
            "#Checked by VAlbum against the pinned SHA-256 (issue #257); without this file it is checked again",
            "sha256=" + entry["sha256"],
            "size=" + str(os.path.getsize(target)),
            "modified=" + str(modified_millis(target)),
            "source=" + _escape(source),
            "checked=" + _escape(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        ]
        tmp = target + RECORD_SUFFIX + ".tmp"
        with open(tmp, "w", encoding="latin-1") as f:
            f.write("\n".join(lines) + "\n")
        os.replace(tmp, target + RECORD_SUFFIX)

    def _ensure(self, entry):
        if self.checked(entry):
            return
        target = self._target(entry)
        if os.path.isfile(target):
            record = read_record(target + RECORD_SUFFIX)
            digest = sha256_of(target)
            if digest == entry["sha256"] and os.path.getsize(target) == entry["size"]:
                self._write_record(entry, "placed by hand" if record is None else record.get("source", "downloaded"))
                return
            if record is None:
                raise FetchError("%s was placed by hand but is not the file of the pinned model (SHA-256 %s, "
                                 "expected %s); replace or remove it" % (target, digest, entry["sha256"]),
                                 permanent=True)
            # Downloaded once and changed since: fetched again.
            os.remove(target)
            os.remove(target + RECORD_SUFFIX)
        self._download(entry)

    def _download(self, entry):
        target = self._target(entry)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        part = target + PART_SUFFIX
        url = "%s%s/resolve/%s/%s" % (self.base_url, self.manifest["repository"], self.manifest["revision"],
                                      urllib.parse.quote(entry["name"]))
        self.current = entry["name"]
        have = os.path.getsize(part) if os.path.isfile(part) else 0
        if have > entry["size"]:
            os.remove(part)
            have = 0
        if have < entry["size"]:
            headers = {"User-Agent": "VAlbum (https://github.com/haumacher/valbum2)"}
            if have > 0:
                headers["Range"] = "bytes=%d-" % have
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers),
                                            timeout=STALL_SECONDS) as response:
                    append = response.status == 206 and have > 0
                    if response.status not in (200, 206):
                        raise FetchError("HTTP %d for %s" % (response.status, url))
                    log("downloading %s (%s)%s" % (entry["name"], megabytes(entry["size"]),
                                                  ", resumed at " + megabytes(have) if append else ""))
                    with open(part, "ab" if append else "wb") as out:
                        while True:
                            chunk = response.read(1 << 16)
                            if not chunk:
                                break
                            out.write(chunk)
                            self.received += len(chunk)
            except urllib.error.HTTPError as ex:
                raise FetchError("HTTP %d for %s" % (ex.code, url))
            except (urllib.error.URLError, OSError) as ex:
                reason = getattr(ex, "reason", ex)
                raise FetchError("the download of %s from %s failed: %s; it is resumed at the next attempt"
                                 % (entry["name"], url, reason))
        size = os.path.getsize(part)
        if size != entry["size"]:
            if size > entry["size"]:
                os.remove(part)
            raise FetchError("the download of %s stopped at %d of %d bytes" % (entry["name"], size, entry["size"]))
        digest = sha256_of(part)
        if digest != entry["sha256"]:
            os.remove(part)
            raise FetchError("the download of %s is not the file of the pinned model (SHA-256 %s, expected %s)"
                             % (entry["name"], digest, entry["sha256"]))
        os.replace(part, target)
        self._write_record(entry, url)
        log("downloaded and checked " + entry["name"])


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

    def __init__(self, args, manifest, key=None):
        self.args = args
        self.manifest = manifest
        self.model = None
        self.status = "loading"
        self.error = None
        self.detail = None
        # The key every request must carry, None for none; kept as its hash, compared in constant time.
        self.key = hashlib.sha256(key.encode("utf-8")).digest() if key else None
        self.lock = threading.Lock()
        self.last_use = time.monotonic()
        self.fetcher = None
        self._refusals = {}
        self._refusal_lock = threading.Lock()

    def authorise(self, header):
        """None where a request with the given Authorization header may be served, else the reason."""
        if self.key is None:
            return None
        scheme, _, token = (header or "").strip().partition(" ")
        token = token.strip()
        if scheme.lower() != "bearer" or not token:
            return ("this embedder service needs a key: the request must carry 'Authorization: Bearer <key>' "
                    "(VALBUM_EMBEDDER_KEY on the album server, the same as on the service)")
        if not hmac.compare_digest(hashlib.sha256(token.encode("utf-8")).digest(), self.key):
            return ("the key is wrong: VALBUM_EMBEDDER_KEY on the album server is not the key of this embedder "
                    "service")
        return None

    def note_refusal(self, client, reason):
        """Logs a refused client, at most once a minute each, so that the administrator sees who knocks."""
        now = time.monotonic()
        with self._refusal_lock:
            last = self._refusals.get(client)
            if last is not None and now - last < 60:
                return
            self._refusals[client] = now
        log("refused a request from %s: %s" % (client, reason.split(":")[0]))

    def fetch(self):
        """Fetches the model's files (--fetch); answers whether they are ready, trying again after failures."""
        self.fetcher = Fetcher(self.args.model_dir, self.manifest, self.args.precision, self.args.model_url)
        delay = FIRST_RETRY
        while True:
            self.status = "downloading"
            self.detail = self.fetcher.progress()
            try:
                if not self.fetcher.ready():
                    log("fetching the model (%s) into %s" % (megabytes(self.fetcher.total), self.args.model_dir))
                    self.fetcher.ensure()
                self.detail = None
                return True
            except FetchError as ex:
                if ex.permanent:
                    self.error = str(ex)
                    self.status = "failed"
                    self.detail = None
                    log("cannot fetch the model: " + self.error)
                    return False
                self.detail = "%s; next attempt in %d s" % (ex, delay)
                log(self.detail)
            except OSError as ex:
                # The folder is not writable, the disk is full: said, and tried again.
                self.detail = "cannot write the model into %s: %s; next attempt in %d s" % (
                    self.args.model_dir, ex, delay)
                log(self.detail)
            time.sleep(delay)
            delay = min(delay * 2, MAX_RETRY)

    def load(self):
        if getattr(self.args, "fetch", False) and not self.fetch():
            return
        self.status = "loading"
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
            "preprocessing": PREPROCESSING,
        }
        if self.error:
            result["error"] = self.error
        if self.status == "downloading" and self.fetcher is not None:
            result["detail"] = self.detail or self.fetcher.progress()
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
            reason = self.error or (self.health().get("detail") if self.status == "downloading" else None)
            return 503, error("the model is " + self.status + (": " + reason if reason else ""), "unavailable")
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
            "revision": self.manifest["revision"],
            "precision": self.args.precision,
            "preprocessing": PREPROCESSING,
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

    # A connection that sends nothing for this long is closed: the service may face the network.
    timeout = 300

    def log_message(self, format, *args):  # noqa: A002 - the name is http.server's
        # Quiet: every request would be a line in the server's log.
        pass

    def _answer(self, status, body, headers=()):
        payload = json.dumps(body).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            for name, value in headers:
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            # The client gave up waiting (its time limit); nothing to tell anybody.
            self.close_connection = True

    def _authorised(self):
        """Whether the request carries the key; answers 401 with the reason where it does not."""
        reason = self.service.authorise(self.headers.get("Authorization"))
        if reason is None:
            return True
        self.service.note_refusal(self.client_address[0], reason)
        # The body of a refused request is not read: the connection cannot be used again.
        self.close_connection = True
        self._answer(401, error(reason, "unauthorized"),
                     [("WWW-Authenticate", 'Bearer realm="valbum-embedder"'), ("Connection", "close")])
        return False

    def do_GET(self):  # noqa: N802 - the name is http.server's
        if not self._authorised():
            return
        if self.path == "/health":
            self._answer(200, self.service.health())
        else:
            self._answer(404, error("no such resource: " + self.path))

    def do_POST(self):  # noqa: N802 - the name is http.server's
        if not self._authorised():
            return
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


# How long a client may take for the TLS handshake.
HANDSHAKE_SECONDS = 30


class Server(ThreadingHTTPServer):
    """The HTTP server, with TLS where a certificate is given; the handshake runs in the request's thread."""

    daemon_threads = True
    tls = None

    def finish_request(self, request, client_address):
        if self.tls is None:
            super().finish_request(request, client_address)
            return
        request.settimeout(HANDSHAKE_SECONDS)
        try:
            secure = self.tls.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            # A client that speaks no TLS, or gives up: nothing to answer.
            return
        try:
            super().finish_request(secure, client_address)
        finally:
            secure.close()


def tls_context(cert, key):
    """The TLS context of the given certificate (PEM, the chain) and private key; TLS 1.2 or newer."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        context.load_cert_chain(cert, key)
    except (OSError, ssl.SSLError) as ex:
        raise SystemExit("valbum-embedder: cannot use the certificate %s with the key %s: %s" % (cert, key, ex))
    return context


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


def is_loopback(host):
    if host in ("localhost", ""):
        return host == "localhost"
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def serve(args):
    manifest = read_manifest(args.manifest)
    if args.precision not in PRECISIONS:
        raise SystemExit("valbum-embedder: unknown precision " + args.precision)
    if args.model_dir is None:
        if args.model_root is None:
            raise SystemExit("valbum-embedder: --model-dir or --model-root is needed")
        args.model_dir = os.path.join(args.model_root, model_folder(manifest))
    if bool(args.tls_cert) != bool(args.tls_key):
        raise SystemExit("valbum-embedder: TLS needs both --tls-cert and --tls-key")
    # The key comes from the environment, never from the command line, which every user of the
    # machine can read.
    key = os.environ.get("VALBUM_EMBEDDER_KEY", "").strip() or None
    if args.nice:
        os.nice(args.nice)
    service = Service(args, manifest, key)
    Handler.service = service
    httpd = Server((args.host, args.port), Handler)
    if args.tls_cert:
        httpd.tls = tls_context(args.tls_cert, args.tls_key)
    # The one line on stdout: the server that started this service reads the port from it.
    print(json.dumps({"listening": httpd.server_address[1]}), flush=True)
    scheme = "https" if httpd.tls else "http"
    log("listening on %s://%s:%d" % (scheme, args.host, httpd.server_address[1]))
    if not is_loopback(args.host):
        if key is None:
            log("WARNING: no key is set (VALBUM_EMBEDDER_KEY) - anybody who can reach this machine on port %d "
                "can use the service. Set a key here and the same on the album server." % httpd.server_address[1])
        else:
            log("every request needs the key (VALBUM_EMBEDDER_KEY)")
        if httpd.tls is None:
            log("no TLS: the previews of the photos, the search texts and the key travel unencrypted; "
                "recommended: --tls-cert and --tls-key (VALBUM_EMBEDDER_TLS_CERT, _TLS_KEY), or a reverse proxy")
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
    model = parser.add_mutually_exclusive_group(required=True)
    model.add_argument("--model-dir", help="the directory of the model files of model.json")
    model.add_argument("--model-root", help="the directory the model's own folder (<repository>@<revision>) is "
                       "made in, see --fetch")
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
    parser.add_argument("--fetch", action="store_true", help="download the model's missing files at start, each "
                        "checked against model.json (the service of its own; the album server fetches for its child)")
    parser.add_argument("--model-url", default=HUGGING_FACE, help="where --fetch downloads from, " + HUGGING_FACE
                        + " by default")
    parser.add_argument("--tls-cert", default=None, help="serve HTTPS with this certificate (PEM, with its chain)")
    parser.add_argument("--tls-key", default=None, help="the private key of --tls-cert (PEM)")
    args = parser.parse_args(argv)
    if args.command == "bench":
        if args.model_dir is None:
            args.model_dir = os.path.join(args.model_root, model_folder(read_manifest(args.manifest)))
        bench(args)
    else:
        serve(args)


if __name__ == "__main__":
    main()
