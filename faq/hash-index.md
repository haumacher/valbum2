# `.hashes.json` is the truth, `hash-index.json` is derived

Per-folder `.hashes.json` sidecars hold content hashes and upload attribution. The per-space
`<space>/.valbum/hash-index.json` is only a lookup derived from them (no database). It is built by a
background executor started in `Main.createServer` — never by a test — and kept current through
`HashCache.flush()` → `HashIndex.sidecarWritten`. Duplicate detection, collections and `?action=check`
depend on it.
