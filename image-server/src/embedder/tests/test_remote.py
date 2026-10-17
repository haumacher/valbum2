#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The embedder service on a machine of its own, see issue #257: the key every request needs, the
warning of a service open to the network, TLS, and the model it fetches itself (--fetch).
Without the model, like test_service.py.
"""

import hashlib
import http.server
import json
import os
import shutil
import ssl
import subprocess
import sys
import threading
import time
import types
import urllib.error
import urllib.request

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import embedder  # noqa: E402
from test_service import call, start, stop  # noqa: E402

SCRIPT = os.path.join(os.path.dirname(HERE), "embedder.py")


# --- The key ----------------------------------------------------------------------------------------

def raw_call(url, headers):
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, dict(response.headers), json.load(response)
    except urllib.error.HTTPError as ex:
        return ex.code, dict(ex.headers), json.load(ex)


@pytest.fixture()
def keyed():
    result = start(key="s3cret-key")
    yield result
    stop(result)


def test_without_the_key_every_request_is_refused_saying_why(keyed):
    status, body = call(keyed.url + "/v1/embeddings", {"input": "x"})
    assert status == 401
    assert body["error"]["type"] == "unauthorized"
    assert "needs a key" in body["error"]["message"]
    assert "Authorization: Bearer" in body["error"]["message"]
    assert "VALBUM_EMBEDDER_KEY" in body["error"]["message"]
    # The health check too: it names the model, and a service open to everybody is not wanted.
    status, headers, body = raw_call(keyed.url + "/health", {})
    assert status == 401
    assert headers["WWW-Authenticate"].startswith("Bearer")
    assert "needs a key" in body["error"]["message"]


def test_a_wrong_key_is_refused(keyed):
    status, body = call(keyed.url + "/v1/embeddings", {"input": "x"}, key="guessed")
    assert status == 401
    assert "key is wrong" in body["error"]["message"]
    status, _, body = raw_call(keyed.url + "/health", {"Authorization": "Basic czNjcmV0LWtleQ=="})
    assert status == 401
    assert "needs a key" in body["error"]["message"]


def test_the_right_key_is_served(keyed):
    status, body = call(keyed.url + "/health", key="s3cret-key")
    assert status == 200 and body["status"] == "ready"
    status, body = call(keyed.url + "/v1/embeddings", {"input": "x", "dimensions": 2}, key="s3cret-key")
    assert status == 200
    assert body["data"][0]["embedding"] == pytest.approx([0.6, 0.8])
    status, _, _ = raw_call(keyed.url + "/health", {"Authorization": "bearer s3cret-key"})
    assert status == 200


def test_the_key_is_compared_in_constant_time():
    service = embedder.Service(types.SimpleNamespace(precision="fp32"), embedder.read_manifest(), "abc")
    # Kept as its hash: what is compared has the same length whatever the client sends.
    assert service.key == hashlib.sha256(b"abc").digest()
    assert service.authorise("Bearer abc") is None
    assert "wrong" in service.authorise("Bearer abd")
    assert "wrong" in service.authorise("Bearer " + "a" * 1000)


def test_without_a_key_everybody_is_served(service):
    status, body = call(service.url + "/v1/embeddings", {"input": "x"})
    assert status == 200


def test_every_vector_names_the_model_it_is_of(service):
    status, body = call(service.url + "/v1/embeddings", {"input": "x"})
    assert status == 200
    assert len(body["revision"]) == 40
    assert body["precision"] == "fp32"
    assert body["preprocessing"] == embedder.PREPROCESSING


@pytest.fixture()
def service():
    result = start()
    yield result
    stop(result)


def run_until(args, env, wanted, seconds=20):
    """Starts the service as a process, returns the lines of its log up to the one containing `wanted`."""
    process = subprocess.Popen([sys.executable, "-I", SCRIPT] + args + ["--exit-with-stdin"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               env=env)
    lines = []
    try:
        port = json.loads(process.stdout.readline())["listening"]
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            line = process.stderr.readline()
            if not line:
                break
            lines.append(line.strip())
            if wanted in line:
                break
        return port, lines
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()


def env_without_key(**more):
    env = {k: v for k, v in os.environ.items() if k != "VALBUM_EMBEDDER_KEY"}
    env.update(more)
    return env


def test_a_service_open_to_the_network_without_a_key_starts_and_warns(tmp_path):
    _, lines = run_until(["--model-dir", str(tmp_path), "--host", "0.0.0.0", "--port", "0"], env_without_key(),
                         "cannot load")
    text = "\n".join(lines)
    assert "WARNING: no key is set (VALBUM_EMBEDDER_KEY)" in text
    assert "unencrypted" in text


def test_a_service_with_a_key_says_so_and_does_not_warn(tmp_path):
    _, lines = run_until(["--model-dir", str(tmp_path), "--host", "0.0.0.0", "--port", "0"],
                         env_without_key(VALBUM_EMBEDDER_KEY="unique-key-4711"), "cannot load")
    text = "\n".join(lines)
    assert "every request needs the key" in text
    assert "WARNING" not in text
    assert "unique-key-4711" not in text, "The key is never logged."


def test_the_child_of_the_album_server_on_loopback_does_not_warn(tmp_path):
    _, lines = run_until(["--model-dir", str(tmp_path)], env_without_key(), "cannot load")
    text = "\n".join(lines)
    assert "WARNING" not in text and "unencrypted" not in text


# --- TLS ----------------------------------------------------------------------------------------------

@pytest.fixture(scope="module")
def certificate(tmp_path_factory):
    if shutil.which("openssl") is None:
        pytest.skip("no openssl to make a certificate with")
    folder = tmp_path_factory.mktemp("tls")
    cert, key = str(folder / "cert.pem"), str(folder / "key.pem")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert,
                    "-days", "2", "-subj", "/CN=embedder", "-addext", "subjectAltName=IP:127.0.0.1"],
                   check=True, capture_output=True)
    return cert, key


def test_tls_serves_https_with_the_key(certificate):
    cert, key = certificate
    service = start(key="s3cret-key", tls=embedder.tls_context(cert, key))
    try:
        assert service.url.startswith("https://")
        client = ssl.create_default_context(cafile=cert)
        status, body = call(service.url + "/health", key="s3cret-key", context=client)
        assert status == 200 and body["status"] == "ready"
        status, body = call(service.url + "/health", context=client)
        assert status == 401
        # A client that speaks plain HTTP gets nothing, and the service goes on.
        with pytest.raises((urllib.error.URLError, ConnectionError, OSError)):
            urllib.request.urlopen(service.url.replace("https", "http") + "/health", timeout=5).read()
        status, _ = call(service.url + "/health", key="s3cret-key", context=client)
        assert status == 200
    finally:
        stop(service)


def test_tls_needs_certificate_and_key(tmp_path):
    result = subprocess.run([sys.executable, "-I", SCRIPT, "--model-dir", str(tmp_path), "--tls-cert", "x.pem"],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode != 0
    assert "both --tls-cert and --tls-key" in result.stderr


def test_an_unusable_certificate_is_refused_naming_it(tmp_path):
    (tmp_path / "c.pem").write_text("no certificate")
    with pytest.raises(SystemExit) as ex:
        embedder.tls_context(str(tmp_path / "c.pem"), str(tmp_path / "c.pem"))
    assert "c.pem" in str(ex.value)


# --- The model, fetched by the service itself -----------------------------------------------------

FILES = {"tokenizer.json": b"{\"tokens\": 1}" * 1000, "onnx/model.onnx": b"graph" * 300,
         "onnx/model.onnx_data": bytes(range(256)) * 400}


def fake_manifest():
    def entry(name):
        return {"name": name, "size": len(FILES[name]), "sha256": hashlib.sha256(FILES[name]).hexdigest()}
    return {"model": "google/embeddinggemma-2", "repository": "onnx-community/embeddinggemma-2-ONNX",
            "revision": "daa72c51243991dfcaf9f9137d2c573d8f7790c0", "dimension": 768,
            "files": {"common": [entry("tokenizer.json")], "fp32": [entry("onnx/model.onnx"),
                                                                     entry("onnx/model.onnx_data")]}}


class Hub(http.server.BaseHTTPRequestHandler):
    """Serves FILES below /<repository>/resolve/<revision>/, with range requests, and records them."""

    requests = []

    def log_message(self, format, *args):  # noqa: A002
        pass

    def do_GET(self):  # noqa: N802
        manifest = fake_manifest()
        prefix = "/%s/resolve/%s/" % (manifest["repository"], manifest["revision"])
        name = self.path[len(prefix):] if self.path.startswith(prefix) else None
        Hub.requests.append((self.path, self.headers.get("Range")))
        if name not in FILES:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = FILES[name]
        first = 0
        if self.headers.get("Range", "").startswith("bytes="):
            first = int(self.headers["Range"][6:].split("-")[0])
            self.send_response(206)
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(data) - first))
        self.end_headers()
        self.wfile.write(data[first:])


@pytest.fixture()
def hub():
    Hub.requests = []
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Hub)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield "http://127.0.0.1:%d/" % httpd.server_address[1]
    httpd.shutdown()
    httpd.server_close()


def test_the_model_is_fetched_checked_and_recorded(tmp_path, hub):
    manifest = fake_manifest()
    folder = tmp_path / embedder.model_folder(manifest)
    assert folder.name == "embeddinggemma-2-ONNX@daa72c51", "The album server's ModelStore folder."
    fetcher = embedder.Fetcher(str(folder), manifest, "fp32", hub)
    assert not fetcher.ready()
    fetcher.ensure()
    assert fetcher.ready()
    for name, data in FILES.items():
        assert (folder / name).read_bytes() == data
        record = embedder.read_record(str(folder / name) + ".download")
        assert record["sha256"] == hashlib.sha256(data).hexdigest()
        assert record["size"] == str(len(data))
        assert record["source"].startswith(hub)
    # Ready: the next start reads the records, not the files, and downloads nothing.
    count = len(Hub.requests)
    embedder.Fetcher(str(folder), manifest, "fp32", hub).ensure()
    assert len(Hub.requests) == count


def test_an_interrupted_download_is_resumed(tmp_path, hub):
    manifest = fake_manifest()
    folder = tmp_path / "m"
    (folder / "onnx").mkdir(parents=True)
    (folder / "onnx" / "model.onnx_data.part").write_bytes(FILES["onnx/model.onnx_data"][:5000])
    embedder.Fetcher(str(folder), manifest, "fp32", hub).ensure()
    assert (folder / "onnx" / "model.onnx_data").read_bytes() == FILES["onnx/model.onnx_data"]
    assert ("/onnx-community/embeddinggemma-2-ONNX/resolve/daa72c51243991dfcaf9f9137d2c573d8f7790c0/"
            "onnx/model.onnx_data", "bytes=5000-") in Hub.requests


def test_a_file_placed_by_hand_is_checked_and_taken(tmp_path, hub):
    manifest = fake_manifest()
    folder = tmp_path / "m"
    folder.mkdir()
    (folder / "tokenizer.json").write_bytes(FILES["tokenizer.json"])
    embedder.Fetcher(str(folder), manifest, "fp32", hub).ensure()
    assert embedder.read_record(str(folder / "tokenizer.json.download"))["source"] == "placed by hand"
    assert not [path for path, _ in Hub.requests if path.endswith("tokenizer.json")]


def test_a_wrong_file_placed_by_hand_is_never_touched(tmp_path, hub):
    manifest = fake_manifest()
    folder = tmp_path / "m"
    folder.mkdir()
    (folder / "tokenizer.json").write_bytes(b"mine")
    with pytest.raises(embedder.FetchError) as ex:
        embedder.Fetcher(str(folder), manifest, "fp32", hub).ensure()
    assert ex.value.permanent
    assert "placed by hand" in str(ex.value) and "replace or remove it" in str(ex.value)
    assert (folder / "tokenizer.json").read_bytes() == b"mine"


def test_a_corrupt_download_is_thrown_away(tmp_path, hub):
    manifest = fake_manifest()
    manifest["files"]["common"][0]["sha256"] = "0" * 64
    folder = tmp_path / "m"
    with pytest.raises(embedder.FetchError) as ex:
        embedder.Fetcher(str(folder), manifest, "fp32", hub).ensure()
    assert not ex.value.permanent
    assert "not the file of the pinned model" in str(ex.value)
    assert not (folder / "tokenizer.json").exists() and not (folder / "tokenizer.json.part").exists()


def test_a_record_written_by_the_album_server_is_read(tmp_path):
    # java.util.Properties.store escapes ':' and '='.
    path = tmp_path / "model.onnx.download"
    path.write_text("#Checked by VAlbum\n#Sat Oct 17 14:08:47 UTC 2026\n"
                    "checked=2026-10-17T14\\:08\\:47Z\nmodified=1760710127000\nsha256=ab\n"
                    "size=12\nsource=https\\://huggingface.co/x/resolve/r/onnx/model.onnx\n", encoding="latin-1")
    assert embedder.read_record(str(path)) == {
        "checked": "2026-10-17T14:08:47Z", "modified": "1760710127000", "sha256": "ab", "size": "12",
        "source": "https://huggingface.co/x/resolve/r/onnx/model.onnx"}


def test_while_the_download_fails_the_service_says_why_and_tries_again(tmp_path, monkeypatch):
    monkeypatch.setattr(embedder, "FIRST_RETRY", 3600)
    args = types.SimpleNamespace(precision="fp32", model_dir=str(tmp_path / "m"), threads=0, fetch=True,
                                 model_url="http://127.0.0.1:9/")
    service = embedder.Service(args, fake_manifest())
    threading.Thread(target=service.load, daemon=True).start()
    for _ in range(100):
        health = service.health()
        if "next attempt" in health.get("detail", ""):
            break
        time.sleep(0.05)
    assert health["status"] == "downloading"
    assert "tokenizer.json" in health["detail"] and "next attempt in 3600 s" in health["detail"]
    status, body = service.embed({"input": "x"})
    assert status == 503 and "downloading" in body["error"]["message"]


def test_a_fetched_model_is_then_loaded(tmp_path, hub):
    args = types.SimpleNamespace(precision="fp32", model_dir=str(tmp_path / "m"), threads=0, fetch=True,
                                 model_url=hub)
    service = embedder.Service(args, fake_manifest())
    service.load()
    # The files are fakes: onnxruntime refuses them - after the download, which is what is tested here.
    assert service.status == "failed"
    assert (tmp_path / "m" / "onnx" / "model.onnx_data").is_file()
    assert "detail" not in service.health()
