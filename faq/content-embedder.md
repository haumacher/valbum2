# The embedder of content search is a child of the server, made ready on first use

The Python service of #257 (`image-server/src/embedder`) travels inside the JAR; `ManagedEmbedder`
unpacks it, makes its venv (`setup-venv`, pinned wheels with hashes) and downloads the model only
when something first asks for a vector, then runs it as a child on `127.0.0.1`. Rejected: a second
systemd unit (nothing equivalent in the container or a `java -jar` run, and it would run for
libraries that never use it) and a venv made in `postinst` (Debian maintainer scripts must not
download, offline installs would fail, every user would pay ~190 MB). `golden.json` is made with
`tests/make_golden.py` in a scratch venv with PyTorch; `tests/test_golden.py` needs the model
(`VALBUM_EMBEDDER_MODEL_DIR`) and is skipped without it. onnxruntime holds the GIL while it loads
the model, so `/health` may not answer for those seconds.
