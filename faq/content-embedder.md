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

**On another machine (2026-10-17):** the package `valbum-embedder` (and its image) runs the same
`embedder.py` for album servers that set `VALBUM_EMBEDDER_URL`; the server pushes previews to it,
no worker pulls jobs (the author's decision). It carries no Java: the model is fetched by the
service itself (`--fetch`, `Fetcher` in `embedder.py`, stdlib only) by the same rules and the same
`.download` records as `ModelStore`, so a folder prepared by either is taken by the other — a JRE
for a 100-line download would have cost a fresh machine ~200 MB. Its configuration is made by
`postinst` with a fresh key and is deliberately **not** a conffile (policy forbids maintainer
scripts to edit one); an empty key is the administrator's choice and stays empty. TLS is optional,
never enforced. The service names its `PREPROCESSING` with every vector; the client refuses another
than `ContentModel.PREPROCESSING` and any vector whose model differs from the stamp it reported.

**The vectors (2026-10-17, #257 package 2):** `space.json` `content` switches a space's
`ContentIndex` on; its step is a *unit* step (one photo per unit, queued behind the photo work of
every album like the videos), because an embedder taking 90 s per photo must never hold up a
preview, and a unit put aside for an upload keeps its vector computing on its own thread and takes
it up again. Each album's `.vacache/content.bin` (binary, ~2.2 KB per photo; JSON with base64 would
be ~2.9 KB) keys `v_img` by content hash and poster moment and `v_txt` by the SHA-256 of its text
(`ContentText`, whose `VERSION` is in the text stamp), so a description edit re-embeds the text
alone and a stamp change only one kind. The cache, not `pipeline.json`, is the truth: the step
trusts no record. `SpaceSettings.content` is an enum whose first constant `KEEP` is what an app
without the switch sends, so an older app's save never turns the search off. The H2/Lucene index
(#264 slice 4) reads these files; nothing fills it yet.
