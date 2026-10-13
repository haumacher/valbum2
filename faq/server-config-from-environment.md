# Mail, OIDC and passkeys are configured from the environment only

`VALBUM_PUBLIC_URL`, `VALBUM_SMTP_*`, `VALBUM_OIDC_<ID>_*` are read by `ServerEnvironment` — no
command-line flags. OIDC and passkeys are offered only when `VALBUM_PUBLIC_URL` is set. An invalid
value refuses the start. `valbum-server`, `/etc/default/valbum` (mode 640) and `compose.yaml` must
list any new variable.
`VALBUM_CACHE_MEMORY` (megabytes, default an eighth of the heap) bounds the album cache by weight
(`ResourceCache.BYTES_PER_PART` per photo); the inotify watches stay bounded by `MAX_FOLDERS` (#245).
`VALBUM_INDEX_MEMORY` (megabytes, default a sixteenth of the heap) is H2's page cache of the space
indexes (#264), shared equally among the spaces and applied with `SET CACHE_SIZE` after they opened.
