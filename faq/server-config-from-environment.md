# Mail, OIDC and passkeys are configured from the environment only

`VALBUM_PUBLIC_URL`, `VALBUM_SMTP_*`, `VALBUM_OIDC_<ID>_*` are read by `ServerEnvironment` — no
command-line flags. OIDC and passkeys are offered only when `VALBUM_PUBLIC_URL` is set. An invalid
value refuses the start. `valbum-server`, `/etc/default/valbum` (mode 640) and `compose.yaml` must
list any new variable.
