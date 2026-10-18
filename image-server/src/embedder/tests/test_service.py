#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The embedder service without the model, see issue #257: the protocol, the refusals, the
Matryoshka truncation and the image preprocessing. Runs anywhere the service's packages are.
"""

import base64
import io
import json
import os
import re
import subprocess
import sys
import threading
import types
import urllib.error
import urllib.request

import numpy as np
import pytest
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import embedder  # noqa: E402


class FakeModel:
    """Answers a vector that says what it was given: the length of the text, or the image's size."""

    def embed_text(self, text):
        v = np.zeros(768, dtype=np.float32)
        v[0] = 3.0
        v[1] = 4.0
        v[300] = len(text) + 1
        return v / np.linalg.norm(v), len(text)

    def embed_image(self, image):
        v = np.zeros(768, dtype=np.float32)
        v[0] = image.width
        v[1] = image.height
        return v / np.linalg.norm(v), 3


def start(key=None, tls=None):
    args = types.SimpleNamespace(precision="fp32", model_dir="/nowhere", threads=0)
    result = embedder.Service(args, embedder.read_manifest(), key)
    result.model = FakeModel()
    result.status = "ready"
    embedder.Handler.service = result
    httpd = embedder.Server(("127.0.0.1", 0), embedder.Handler)
    httpd.tls = tls
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    result.url = "%s://127.0.0.1:%d" % ("https" if tls else "http", httpd.server_address[1])
    result.httpd = httpd
    return result


def stop(service):
    service.httpd.shutdown()
    service.httpd.server_close()


@pytest.fixture()
def service():
    result = start()
    yield result
    stop(result)


def call(url, body=None, key=None, context=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if key is not None:
        headers["Authorization"] = "Bearer " + key
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10, context=context) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as ex:
        return ex.code, json.load(ex)


def jpeg(width, height):
    out = io.BytesIO()
    Image.new("RGB", (width, height), (200, 30, 40)).save(out, "JPEG")
    return "data:image/jpeg;base64," + base64.b64encode(out.getvalue()).decode("ascii")


def test_health_names_the_pinned_model(service):
    status, body = call(service.url + "/health")
    assert status == 200
    assert body["status"] == "ready"
    assert body["model"] == "google/embeddinggemma-2"
    assert body["repository"] == "onnx-community/embeddinggemma-2-ONNX"
    assert len(body["revision"]) == 40
    assert body["precision"] == "fp32"
    assert body["dimension"] == 768
    assert body["preprocessing"] == embedder.PREPROCESSING


def test_texts_are_embedded_as_given_truncated_and_normalised_again(service):
    status, body = call(service.url + "/v1/embeddings", {"input": ["ab", "abcd"], "dimensions": 256})
    assert status == 200
    assert body["object"] == "list"
    assert [d["index"] for d in body["data"]] == [0, 1]
    for d in body["data"]:
        v = np.asarray(d["embedding"])
        assert v.shape == (256,)
        assert abs(np.linalg.norm(v) - 1) < 1e-6
        # The entry beyond 256 is cut off; what remains is 3:4 normalised.
        assert v[0] == pytest.approx(0.6) and v[1] == pytest.approx(0.8)
    assert body["usage"]["prompt_tokens"] == 6


def test_a_single_string_and_base64_vectors(service):
    status, body = call(service.url + "/v1/embeddings", {"input": "x", "encoding_format": "base64"})
    assert status == 200
    v = np.frombuffer(base64.b64decode(body["data"][0]["embedding"]), dtype="<f4")
    assert v.shape == (768,)
    assert abs(np.linalg.norm(v) - 1) < 1e-6


def test_images_come_as_data_uris(service):
    status, body = call(service.url + "/v1/embeddings",
                        {"input": [jpeg(30, 40), jpeg(40, 30)], "modality": "image", "dimensions": 2})
    assert status == 200
    assert body["data"][0]["embedding"] == pytest.approx([0.6, 0.8])
    assert body["data"][1]["embedding"] == pytest.approx([0.8, 0.6])


@pytest.mark.parametrize("body, message", [
    ({}, "'input'"),
    ({"input": []}, "'input'"),
    ({"input": [1]}, "'input'"),
    ({"input": "x", "modality": "audio"}, "'modality'"),
    ({"input": "x", "dimensions": 0}, "'dimensions'"),
    ({"input": "x", "dimensions": 769}, "'dimensions'"),
    ({"input": "x", "dimensions": True}, "'dimensions'"),
    ({"input": "x", "encoding_format": "hex"}, "'encoding_format'"),
    ({"input": ["x"] * 65}, "at most"),
    ({"input": "data:image/jpeg;base64,!!", "modality": "image"}, "base64"),
    ({"input": "data:image/jpeg;base64," + base64.b64encode(b"no image").decode(), "modality": "image"},
     "not an image"),
])
def test_a_bad_request_is_refused_saying_why(service, body, message):
    status, answer = call(service.url + "/v1/embeddings", body)
    assert status == 400
    assert message in answer["error"]["message"]


def test_every_request_served_is_a_line_of_the_log(service, capsys):
    """Issue #305: what kind, how many, the picture sizes and the time; never what was embedded."""
    call(service.url + "/v1/embeddings", {"input": ["a secret text", "abcd"]})
    call(service.url + "/v1/embeddings", {"input": [jpeg(30, 40), jpeg(40, 30)], "modality": "image"})
    call(service.url + "/v1/embeddings", {"input": jpeg(30, 40), "modality": "image"})
    err = capsys.readouterr().err
    assert re.search(r"^valbum-embedder: embedded 2 texts in \d+\.\d+ s$", err, re.M), err
    assert re.search(r"^valbum-embedder: embedded 2 images \(30x40, 40x30\) in \d+\.\d+ s$", err, re.M), err
    assert re.search(r"^valbum-embedder: embedded 1 image \(30x40\) in \d+\.\d+ s$", err, re.M), err
    assert "secret" not in err


def test_a_refused_request_is_a_warning_with_its_reason(service, capsys):
    call(service.url + "/v1/embeddings", {"input": "x", "modality": "audio"})
    err = capsys.readouterr().err
    assert "valbum-embedder: WARNING: refused an embeddings request: 'modality' must be" in err, err


def test_a_wrong_key_is_a_warning_without_the_key(capsys):
    keyed = start(key="the-right-key")
    try:
        status, _ = call(keyed.url + "/v1/embeddings", {"input": "x"}, key="a-wrong-key")
        assert status == 401
    finally:
        stop(keyed)
    err = capsys.readouterr().err
    assert "valbum-embedder: WARNING: refused a request from 127.0.0.1: the key is wrong" in err, err
    assert "a-wrong-key" not in err and "the-right-key" not in err


def test_while_the_model_is_not_there_the_service_says_why(service):
    service.status = "failed"
    service.error = "FileNotFoundError: onnx/model.onnx"
    status, body = call(service.url + "/v1/embeddings", {"input": "x"})
    assert status == 503
    assert body["error"]["type"] == "unavailable"
    assert "onnx/model.onnx" in body["error"]["message"]
    status, health = call(service.url + "/health")
    assert health["status"] == "failed" and "onnx/model.onnx" in health["error"]


def test_unknown_resources_are_not_found(service):
    assert call(service.url + "/v1/models")[0] == 404
    assert call(service.url + "/v1/other", {"input": "x"})[0] == 404


def test_truncation_renormalises():
    v = np.arange(1, 769, dtype=np.float32)
    v /= np.linalg.norm(v)
    t = embedder.truncate(v, 128)
    assert t.shape == (128,)
    assert np.linalg.norm(t) == pytest.approx(1.0)
    assert np.allclose(t, v[:128] / np.linalg.norm(v[:128]))
    with pytest.raises(ValueError):
        embedder.truncate(np.zeros(768, dtype=np.float32), 256)


@pytest.mark.parametrize("size, target", [
    # Gemma4ImageProcessor's rule, as transformers 5.19 computes it.
    ((600, 900), (624, 960)),
    ((1200, 800), (960, 624)),
    ((1536, 2048), (672, 912)),
    ((300, 1200), (384, 1584)),
    ((10, 4000), (48, 13440)),
    ((4000, 10), (13440, 48)),
    ((60, 80), (672, 912)),
])
def test_the_target_size_follows_the_processor(size, target):
    assert embedder.target_size(*size) == target
    h, w = target
    assert h % 48 == 0 and w % 48 == 0 and (h // 16) * (w // 16) <= embedder.MAX_PATCHES


def test_patches_are_laid_out_as_the_processor_lays_them_out():
    pixels = np.random.default_rng(1).integers(0, 256, size=(624, 960, 3), dtype=np.uint8)
    values, positions, soft = embedder.patches(Image.fromarray(pixels))
    count = 39 * 60
    assert values.shape == (1, embedder.MAX_PATCHES, 768)
    assert soft == count // 9
    # The patch of row 2, column 5: rows, then columns, then channels of its 16 x 16 pixels.
    index = 2 * 60 + 5
    expected = pixels[32:48, 80:96, :].astype(np.float32).reshape(-1) / 255
    assert np.allclose(values[0, index], expected)
    assert positions[0, index].tolist() == [5, 2]
    assert (values[0, count:] == 0).all() and (positions[0, count:] == -1).all()


def test_an_upright_preview_and_an_exif_rotated_image_are_seen_alike():
    image = Image.new("RGB", (40, 20), (0, 0, 0))
    image.putpixel((0, 0), (255, 255, 255))
    exif = Image.Exif()
    exif[0x0112] = 6  # rotate 90 degrees clockwise to show
    out = io.BytesIO()
    image.save(out, "JPEG", exif=exif.tobytes())
    shown = embedder.decode_image(out.getvalue())
    assert shown.size == (20, 40)


def test_the_manifest_pins_every_file():
    manifest = embedder.read_manifest()
    assert len(manifest["revision"]) == 40
    for precision in embedder.PRECISIONS:
        text, vision = embedder.model_files(manifest, precision)
        assert "audio" not in text + vision
        names = [f["name"] for f in manifest["files"][precision]]
        assert text + "_data" in names and vision + "_data" in names
    for files in manifest["files"].values():
        for f in files:
            assert len(f["sha256"]) == 64 and f["size"] > 0
            assert "fp16" not in f["name"]


def test_the_service_tells_its_port_and_exits_with_its_parent(tmp_path):
    # A model directory that holds nothing: the service starts, says where it listens, reports the
    # failure, and ends when stdin closes.
    process = subprocess.Popen(
        [sys.executable, "-I", os.path.join(os.path.dirname(HERE), "embedder.py"), "--model-dir", str(tmp_path),
         "--exit-with-stdin"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        port = json.loads(process.stdout.readline())["listening"]
        assert port > 0
        url = "http://127.0.0.1:%d/health" % port
        for _ in range(100):
            status, health = call(url)
            if health["status"] != "loading":
                break
            threading.Event().wait(0.1)
        assert health["status"] == "failed"
        assert "tokenizer.json" in health["error"] or "model" in health["error"]
        # The server is gone: nobody reads the log, and stdin closes.
        process.stderr.close()
        process.stdin.close()
        assert process.wait(timeout=10) == 0
    finally:
        if process.poll() is None:
            process.kill()


SETUP = os.path.join(os.path.dirname(HERE), "setup-venv")


def run_setup(tmp_path, python):
    env = dict(os.environ, PYTHON=python)
    return subprocess.run(["sh", SETUP, str(tmp_path / "env"), os.path.join(os.path.dirname(HERE), "requirements.txt")],
                          env=env, capture_output=True, text=True, timeout=60)


def test_setup_names_a_missing_python(tmp_path):
    result = run_setup(tmp_path, str(tmp_path / "no-python"))
    assert result.returncode != 0
    assert "Python 3.12 or newer" in result.stderr
    assert not (tmp_path / "env").exists()


def test_setup_refuses_an_old_python(tmp_path):
    fake = tmp_path / "python3"
    fake.write_text("#!/bin/sh\ncase \"$1\" in -V) echo 'Python 3.11.2';; *) exit 1;; esac\n")
    fake.chmod(0o755)
    result = run_setup(tmp_path, str(fake))
    assert result.returncode != 0
    assert "Python 3.11.2 is too old" in result.stderr
    assert not (tmp_path / "env").exists()


def test_the_requirements_pin_every_package_with_hashes():
    with open(os.path.join(os.path.dirname(HERE), "requirements.txt"), encoding="utf-8") as f:
        text = f.read()
    names = [line.split("==")[0] for line in text.splitlines() if "==" in line and not line.startswith("#")]
    assert sorted(names) == sorted(["numpy", "onnxruntime", "flatbuffers", "packaging", "protobuf", "tokenizers",
                                    "pillow"])
    for block in text.split("==")[1:]:
        assert "--hash=sha256:" in block.split("\n\n")[0]
    assert "torch" not in text
