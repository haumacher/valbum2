# The search answers from the index, and H2's cache is no hard bound

The search of #227 asks `SearchIndex` (#264): rows per photograph in the space's H2 database
(date, rating, privacy, camera, kind, persons, labels, places), and its words in a Lucene index
beside it, `.valbum/index/lucene/`, keyed by the row's id. A query answers what the index holds —
no sidecar is read and no stamp compared on the request. The album of every match still filters it
(`PrivacyFilter`), so a stale row shows a photo late, never one it must not.

- **Write-through is a mark plus a drain.** `storeSidecar` and the analysis record call
  `SearchIndex.sidecarWritten(folder)`, which only marks the folder: they run under folder locks
  that the cache lookup of a re-read could take again. The mark is drained by
  `SearchIndex#writeThrough()` in the `finally` of `doPut`/`doPost` and before every search; a
  move, delete, purge or rename of folders calls `treeChanged` directly. A new writer of a
  sidecar needs no more than `storeSidecar`.
- **Outside changes wait for the background**, the `search` step of the `FolderPipeline` (stamps of
  the folder, `index.json`, `.vacache/analysis.json`, and the gazetteer epoch for photos waiting
  for places). A servlet without a background (tests, tools) does that work on the request
  (`setSyncOnRequest(false)` holds a test to the write-through).
- **Lucene belongs to one H2 database.** It is stamped with H2's random `generation`; another one
  empties it. Lucene commits at most every 2 s: each text write carries a sequence number
  (`albums.text_seq`), and on open the albums beyond Lucene's committed number are read again
  (`SpaceIndex#textSince`). The analysers are built from their classes: the server JAR unpacks every
  dependency, and Lucene's SPI service files of core and analysis-common overwrite each other.
- **H2 pitfalls.** `CACHE_SIZE` in the JDBC URL is applied again by every connection the pool opens
  later, undoing `SET CACHE_SIZE` — it is set once on the writer. H2's page cache evicts a page by
  holding it weakly and takes it back on the next read without evicting another, so reads alone
  grow it past its size; `SpaceIndex#trimCache()` empties it after a search when it holds more than
  twice its size. An array parameter holds at most 65 536 values.
- **A read is one snapshot.** `SpaceIndex#read` runs in a `REPEATABLE_READ` transaction that is
  rolled back. In autocommit, a statement that finds a row through a secondary index while a
  writer deletes it fails now and then with 90143 ("Row not found in primary index"), and the
  search answered nothing (`TestSearchIndex#testSearchesDuringARebuildAreConsistent` under load).
- **Rows by id are joined, not listed.** `id IN (?, …)` re-checks the whole list for every row it
  finds (seconds for 100 000 ids); `SearchIndex` joins `TABLE(x BIGINT = ?)` with the ids as an
  array, or walks the id range where the ids are dense (#276).
- **The options of the search view never go through the albums.** `?type=search-options` at the
  root once asked every album of the library for its photos (the album filter of `searchMatches`):
  2 000 albums read into a cold cache, seconds, and the app's choosers stood empty meanwhile. They
  are read off the index (`ImageServlet#optionRows`, `SearchIndex#facets`, the table `places`);
  a folder changed behind the index is read into it first (`SearchIndex#refresh`), and only an
  inbox shown to a contributor as their own photos reads its album (the contributor is no column).
- **The vectors are a third store, filtered by H2's ids.** `VectorIndex` (#264 slice 4) is its own
  Lucene folder, `.valbum/index/vectors/` — a whole-document update would otherwise tie every
  description edit to the album's vectors, and a model change must drop vectors without the words.
  A document per photograph keyed by its `photos.id`, plus a marker per album (the stamp it was fed
  with, committed with its vectors, so a kill loses both). Who may see a photograph is *not* in it:
  the scope `BitSet` of the search is the filter inside `KnnFloatVectorQuery`, read off a doc value
  per document. `LongPoint.newSetQuery` of the ids was tried and costs every id in every segment
  (600 ms for 100 000 allowed in 81 segments, against 40 ms). An index of the build before gains
  the folder without a migration step; the start-up walk fills it (`ContentVectors`).
