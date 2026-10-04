# No address reaches a dot-folder

`AuthService.walk` refuses any path segment starting with `.` (404 `PATH_ESCAPED`), so `.valbum`
(users, trash, duplicates, secrets) and `.vacache` are never served directly. The app uses this for
its own pseudo-routes (`/.duplicates/`). NAS/desktop litter (`@eaDir`, `Thumbs.db`, `#recycle`, …)
is filtered by the single predicate `LibraryFiles.isIgnored`.
