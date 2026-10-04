# Derived fields are cleared before every sidecar write

Many model fields are computed on read and must never be stored: `effectiveDate`, `FolderInfo.kind`,
`AlbumKind.INBOX`, `ImagePart.faces`, `facesPending`, contributor attribution, cover crops, a
listing's `folders`. `AlbumDate.clearDerived` is the one place that strips them; a new derived
field must be cleared there too. Derived per-caller data is added to a *copy*, never to the cached album.

`AlbumKind.COLLECTION` is the exception: stored once at creation.
