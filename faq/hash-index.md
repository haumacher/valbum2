# `.hashes.json` is the truth, the hash index in `.valbum/index/` is derived

Per-folder `.hashes.json` sidecars hold content hashes and upload attribution; they are the truth.
The hash index is a lookup derived from them, kept in the space's H2 database
`<space>/.valbum/index/` (`SpaceIndex`, #264): a table of folders with the stamp of the sidecar each
was read from, and the hashes per folder as `BINARY(32)`. It is rebuildable and **no part of a
backup**: a database that is missing, unreadable (garbage, another H2 format) or of another schema
(`SpaceIndex.SCHEMA`) is deleted and filled again by the background from the sidecars; one that
cannot be created at all (read-only library, held by another process) lives in memory. A change
of what is there is a bump of `SCHEMA` (a rebuild); a build that only adds tables does so in place,
see `index-migration.md`. The `hash-index.json` of earlier builds is litter,
deleted after the first complete pass.

The background pass runs in the executor started in `Main.createServer` — never by a test — and a
folder whose sidecar stamp matches is not opened. The server's own edits write through:
`HashCache.flush()` → `HashIndex.sidecarWritten` (upload, move, delete, replace-originals, the lazy
fill of a check) and `treeChanged` after a move, delete, purge or rename of folders. Requests only
read; `pathOf` answers only a file that is there, and the background drops vanished files from the
sidecar and the index. Duplicate detection, collections and `?action=check` depend on it.

Folders copied in while the server runs are hashed without a restart (#235): the space's
`FolderPipeline` (`HashIndex.pipeline()`) watches the whole library itself (`LibraryWatch`, #253;
a request watches nothing) and sweeps folder times for what no watch covers; it waits until a
folder's contents stand still, then runs its steps on the same low-priority thread. The analysis of
#253 runs first (its own stage, see `analysis-record.md`), hashing next: the start-up walk hashes
every folder before the catch-up of #236 (previews, cover, faces, places, then videos) begins,
because the sync waits for a complete index. Every `.hashes.json` read-modify-write runs under one lock per
folder in `HashCache`, so the face pass, an upload and the hash pass never hash twice or lose entries.

The `SpaceIndex` is opened once by the `ImageServlet` and shared with the search (`search-index.md`),
closed by its `destroy()`; a `HashIndex(Path)` of a tool opens its own.

H2 is opened with the `async:` file system: a plain `FileChannel` is closed by `Thread.interrupt()`,
and the pipeline thread is interrupted at every shutdown.
