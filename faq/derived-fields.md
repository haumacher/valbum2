# Derived fields are cleared before every sidecar write

Many model fields are computed on read and must never be stored: `effectiveDate`, `FolderInfo.kind`,
`AlbumKind.INBOX`, `ImagePart.faces`, `facesPending`, `ImagePart.places`, contributor attribution,
cover crops, a listing's `folders`. `AlbumDate.clearDerived` is the one place that strips them; a
new derived field must be cleared there too. Derived per-caller data is added to a *copy*, never to
the cached album: e.g. the place names in the caller's language (`Accept-Language`) go into copied
photos in the answer's own shell (`PhotoPlaces.localize`).

`AlbumKind.COLLECTION` and `AlbumKind.SEARCH` are the exceptions: stored once at creation. A saved
search stores its `query` (and title, date, star, picture) and never a part: its parts are found
live and answered to the caller (`ImageServlet#searchAnswer`).
