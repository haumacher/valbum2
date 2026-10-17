# Mail, OIDC and passkeys are configured from the environment only

`VALBUM_PUBLIC_URL`, `VALBUM_SMTP_*`, `VALBUM_OIDC_<ID>_*` are read by `ServerEnvironment` — no
command-line flags. OIDC and passkeys are offered only when `VALBUM_PUBLIC_URL` is set. An invalid
value refuses the start. `valbum-server`, `/etc/default/valbum` (mode 640) and `compose.yaml` must
list any new variable.
`VALBUM_CACHE_MEMORY` (megabytes, default an eighth of the heap) bounds the album cache by weight
(`ResourceCache.BYTES_PER_PART` per photo); the inotify watches stay bounded by `MAX_FOLDERS` (#245).
`VALBUM_INDEX_MEMORY` (megabytes, default a sixteenth of the heap) is the heap of the space indexes
(#264), shared equally among the spaces: a quarter (at most 16 MB) is Lucene's buffer of new
documents, the rest H2's page cache, applied with `SET CACHE_SIZE` on the writer — never in the
JDBC URL, see `search-index.md`.

`VALBUM_EMBEDDER_DIR`, `_PORT`, `_PRECISION`, `_URL`, `_KEY`, `_CERT_SHA256` (content search,
#257) are read by `EmbedderSettings` for `ServerEnvironment`.
