The stored form of a saved content search of issue #257: an `index.json` of `kind` `SEARCH` whose
query holds a `SearchContent` — the text of what the photos show, never a vector — once, as a
member of the root's `SearchAnd`, next to criteria that narrow it (here a span of time and "no
videos"). The answer is ordered by similarity, so the stored picture is the most similar photo
when the search was created. The query version stays `1`: a new kind of criterion is a new
message, and a build that does not know `SearchContent` reads it as nothing and refuses the
search with a sentence (`PhotoSearch.NEWER`), never answering it without the ranking.

`later.json` stands for a search written by a build after this one: next to the content criterion
a criterion this build does not know (`SearchShot`, a made-up name: `SearchSimilar` stood here until
#257 package 5 made it known), which is refused as such.

`TestContentQuery` reads, writes and reads each of them, and serves `index.json` in its library.

`similar.json` is the stored form of a saved "More like this" (#257 package 5): a `SearchSimilar`
holding a `PhotoRef` — the content hash the server fills in when it stores the search, and the path
relative to the space root as last seen — never a vector, next to a criterion that narrows it.
