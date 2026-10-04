# The server never modifies an original

All edits live in sidecars (`index.json`, `.hashes.json`, `.vacache/`). The only file operations on
originals are same-filesystem renames: library migrations, moves (duplicates set aside in
`<space>/.valbum/duplicates/`), and deleting an album into `<space>/.valbum/trash/`.

The one exception, decided by the author on 2026-09-23 (#152): an administrator's **purge** of
photographs rated −2 unlinks them. The trash *folder* deliberately stays for album/folder deletes
and contributors' deletes (#159) — those keep their safety net on disk.

`TestImageServletDelete.testNothingButWhatTheServerWroteIsEverDeleted` pins what a delete may remove.
