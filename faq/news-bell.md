# The bell's events come from `.hashes.json`, its read marks are temporary

The bell of #268 (`Arrivals`) lists what others made arrive: an upload, or a move out of the inbox
into an album. Both are recorded in the attribution of `.hashes.json` — `uploaded` (time of the
upload), `movedBy`/`movedByLabel`/`moved` (who took it out of the inbox, and when) — and read into
the table `arrivals` of the space index with the hashes (`HashIndex`), so a rebuild finds them
again and every sidecar write is in the bell at once. A file recorded before #268 has no time and
is in no bell; a move between albums records nothing new (the photograph keeps its event).

- **Grouping and aging:** one entry per actor, album and burst — a new entry after 2 h of quiet
  (`Arrivals.BURST_GAP`); only the last 90 days, at most 200 entries, the unread first.
- **Read marks** (`arrival_marks`: member, album, actor, span of the entry) live in the index only,
  so phone and browser agree. They age out after 90 days and **go with a rebuild**: whatever
  arrived before the index was created (`SpaceIndex.created()`) counts as read, so a schema bump
  never rings for the whole library. A photograph arriving later in a read burst is unread again.
- An entry is keyed by album, actor and its first time; moving its first photograph away, or
  renaming the album, makes it a new entry.
