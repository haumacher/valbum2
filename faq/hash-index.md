# `.hashes.json` is the truth, `hash-index.json` is derived

Per-folder `.hashes.json` sidecars hold content hashes and upload attribution. The per-space
`<space>/.valbum/hash-index.json` is only a lookup derived from them (no database). It is built by a
background executor started in `Main.createServer` — never by a test — and kept current through
`HashCache.flush()` → `HashIndex.sidecarWritten`. Duplicate detection, collections and `?action=check`
depend on it.

Folders copied in while the server runs are hashed without a restart (#235): the `ResourceCache`
tells the space's `FolderPipeline` (`HashIndex.pipeline()`) about every album it loads and every
change its watches report; the pipeline waits until the folder's contents stand still, then runs its
steps on the same low-priority thread. Hashing is the first step; further per-folder steps (#236) are
added with `pipeline().addStep(...)`. Every `.hashes.json` read-modify-write runs under one lock per
folder in `HashCache`, so the face pass, an upload and the hash pass never hash twice or lose entries.
