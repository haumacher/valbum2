The stored form of a collection of issue #221: the `index.json` of an album whose `kind` is
`COLLECTION`. A part is a reference — its `name` (unique in the collection), a `ref` with the
photograph's content `hash` (SHA-256) and the `path` relative to the space root it was last seen at,
and the collection's own `labels`. Headings and the album picture are the collection's own. The
server writes the remaining fields of an `ImagePart` with their defaults; they are never read: the
photograph's turn, crop, rating, privacy, description and time are its own album's.

`TestCollections` puts `P3031375.JPG` and `IMG_0415.JPG` of `test-album` into the albums
`Schlosspark` and `Blumen` of a space: the first hint is stale (`Old place/`), so it resolves through
the hash index, the second through its hint, and `gone.jpg` names contents the space does not hold —
a missing part.
