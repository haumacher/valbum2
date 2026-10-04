# Java/Dart twins are pinned by shared fixture tables

Some logic exists in both toolchains and must agree. Each pair is pinned by one JSON table under
`image-server/src/test/fixtures/` that both test suites read:

- `name-dates.json` — `ImageData.nameDate` ↔ `lib/name_date.dart`
- `folder-names.json` — `FolderNames.albumFolderName` ↔ `album_date.dart`
- `crop-orientations.json` — `Crops` ↔ `lib/crop.dart`
- `filtered-headings.json` — `FilteredHeadings` ↔ `lib/album_labels.dart`

Change both sides and the table together.
