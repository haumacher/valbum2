# `.hashes.json` is the truth, `hash-index.json` is derived

Per-folder `.hashes.json` sidecars hold content hashes and upload attribution. The per-space
`<space>/.valbum/hash-index.json` is only a lookup derived from them (no database). It is built by a
background executor started in `Main.createServer` — never by a test — and kept current through
`HashCache.flush()` → `HashIndex.sidecarWritten`. Duplicate detection, collections and `?action=check`
depend on it.

Folders copied in while the server runs are hashed without a restart (#235): the space's
`FolderPipeline` (`HashIndex.pipeline()`) watches the whole library itself (`LibraryWatch`, #253;
a request watches nothing) and sweeps folder times for what no watch covers; it waits until a
folder's contents stand still, then runs its steps on the same low-priority thread. The analysis of
#253 runs first (its own stage, see `analysis-record.md`), hashing next: the start-up walk hashes
every folder before the catch-up of #236 (previews, cover, faces, places, then videos) begins,
because the sync waits for a complete index. Every `.hashes.json` read-modify-write runs under one lock per
folder in `HashCache`, so the face pass, an upload and the hash pass never hash twice or lose entries.
