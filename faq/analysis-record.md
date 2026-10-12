# A request never analyses a file — and a test's servlet does

An album is answered from its `index.json` and from `.vacache/analysis.json`, the record the
background (`AnalysisStep`, first stage of the `FolderPipeline`) keeps of every media file: size,
modification time, and the analysed part or why the file cannot be read (#253). A request lists no
folder's files and opens no photograph; what is not recorded yet is not answered, and a folder never
recorded answers `AlbumInfo.preparing`.

- The record is the background's own file, not `index.json`: an edit that read the album before the
  background wrote would otherwise overwrite what it found for good. A failure is keyed by size and
  mtime and by `AnalysisRecord.ANALYSIS` (raise it when a build reads more formats), and never
  reaches `index.json`; a broken part an edit stores (its trash rating!) is stored without a size in
  pixels, which makes the analysis look at its file again.
- Pure reads hold only once `ImageServlet#startIndexing()` ran (`ResourceCache#setBackground`). A
  servlet without a background — every test that does not start indexing, the offline tools —
  records a folder on the request itself, doing exactly the background's work. A test of the
  production behaviour sets `cache().setBackground(true)` and drives `pipeline().process(folder,
  Stage.RECORD)` (see `TestBackgroundRecord`).
- A test that fingerprints "everything the server wrote" must expect `.vacache/analysis.json`;
  `?action=refresh-cache` deletes it, so the folder is analysed again (a broken file included).
