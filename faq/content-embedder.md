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
without the switch sends, so an older app's save never turns the search off. The vector index
(#264 slice 4, `faq/search-index.md`) is fed from these files.

**The query (2026-10-17, #257 package 4):** a `SearchContent` ranks, so it stands only as the root
or once in the root's AND; the other members are evaluated by H2 into the filter *inside* the
vector query (no intersection afterwards, so `k` stays `k`), and OR/NOT around it is refused. The
weights (0.7 picture, 0.3 text), the cut (0.05) and the page (100, at most 1000) are uncalibrated
guesses, all in `ContentSearch`. "More" asks again for a larger `count` — HNSW has no offset, a
larger `k` is the same walk. The query vector is kept per model and text (64 of them), so a search
opened again answers while the embedder is down; a stale one cannot outlive a model change, the
index's stamp is compared on every query. The answer's order travels as `ImagePart.score`, which
the app sorts by when it ungroups a result.

**More like this (2026-10-18, #257 package 5):** a `SearchSimilar { PhotoRef ref }` ranks where a
`SearchContent` may stand; a query ranks once, so a text and a photo together are refused
(`ContentSearch.TEXT_AND_PHOTO`) — the app sends the field's text as a `SearchText` while a photo
is the query. The vector is the photo's `v_img` from the index (weights 1/0, no embedder call, so it
answers while the embedder is down), the photo itself left out inside the vector query, cut at
`SIMILAR_THRESHOLD` 0.3 (picture-to-picture cosines run higher than text-to-picture; uncalibrated).
A reference the caller may not see and one that leads nowhere are answered alike: `200`, no parts,
`AlbumInfo.searchProblem = PHOTO_NOT_FOUND`, the reference emptied in the answered query (a link
or anonymous visitor never gets the path at all); no vector yet is `PHOTO_NOT_DESCRIBED`. A saved
search stores the path and the server fills in the content hash (`PhotoRef` semantics: follows a
move); an empty reference written back by a member who was answered it empty keeps the stored one.
