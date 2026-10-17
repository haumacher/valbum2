# The index of a space is added to in place; a rebuild only for a change of what is there

A rebuild of `.valbum/index/` (`SpaceIndex.SCHEMA` bumped) loses what no sidecar holds — the news
bell's read marks — and leaves the search incomplete until the background has read the library
again. So a build that only *adds* (a table, a column, an index) appends a step to
`SpaceIndex.MIGRATIONS` instead; the meta row `migration` counts the steps an index has had, and a
created index runs them all.

- **A step is DDL with `IF NOT EXISTS`, nothing else.** H2 commits every DDL statement on its own,
  so a process killed inside a step runs the whole step again. A released step is never edited or
  removed; a bump of `SCHEMA` starts the list afresh.
- **The fill is the background's ordinary work, not part of the step.** A new table starts empty,
  and its owner must read a missing row as "not read yet" — the face prototypes (#264 slice 3):
  `FaceIndex#prototypeStep` trusts no pipeline record and compares a stamp per album
  (`Recognition#stampOf`: `index.json`, `faces.json`, `.hashes.json`, the model), so after an
  upgrade the start-up walk fills every album even though its faces are long done, and a fill
  killed half-way resumes with the albums it did not commit.
- **An index of a later build is rebuilt** (`migration` above what this build knows): this build
  would not keep the later tables current, and the later build would trust them on its return. An
  *older* build's absence is harmless only because the stamps put stale rows right.
- **Lucene stays stamped with `SCHEMA`**, not with the migration: a step never empties it.
- **Only the background's own read records a real stamp** (taken *before* reading). A write from a
  request (a listing, `tag-faces`) records `0`, "unverified", so the next walk reads that album
  itself.
