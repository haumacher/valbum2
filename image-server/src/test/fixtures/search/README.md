The stored form of a saved search of issue #227: the `index.json` of an album whose `kind` is
`SEARCH`. It holds the saved search's own statements — title, subtitle, star (and date and album
picture where its author gave them) — and its `query`, and nothing else: no part and no reference.
The photos are found whenever the saved search is opened.

The `query` is a versioned tree (`version` 1): `SearchAnd`, `SearchOr` and `SearchNot` over the
leaves `SearchPerson` (a confirmed face, by person id), `SearchDate` (milliseconds, `from` inclusive,
`to` exclusive, 0 for open), `SearchPlace` (a GeoNames id at any level), `SearchLabel`,
`SearchRating` (the lowest rating), `SearchMedia` (`video` true or false), `SearchText` (comment,
album title or subtitle, ignoring case), `SearchCamera` and `SearchFolder` (a path below the space
root). This file uses every kind once; the meaning of each is frozen.

`TestSavedSearch` places it as `Trips/Fixture` in its library: it looks below `Trips` and finds
`2021 Lake/anne_philipp.jpg` — Anne and Philipp, 2021, labelled `Holiday`, a Canon — and nothing else.
A build that reads a criterion it does not know refuses the whole search with a sentence, see
`TestSavedSearch#testAnUnknownCriterionIsRefusedVisibly`.
