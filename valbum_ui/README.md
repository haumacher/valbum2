# valbum_ui — the VAlbum app

The Flutter client of [VAlbum2](../README.md): one code base for the web, Android, iOS, Linux,
Windows and macOS. It talks to the Java server's JSON API under `/data/` and renders listings,
albums (with the row layout in `lib/album_layout.dart`) and single images.

## Layout

- `lib/main.dart` — `main()` only; it re-exports the libraries below, so
  `package:valbum_ui/main.dart` remains the one import a test needs.
- `lib/app.dart` — the application shell: `VAlbumApp` and the `VAlbumScope`
  wiring, `VAlbumView`/`VAlbumState` (loads the resource at a path and
  dispatches to the view for its type), image upload, and the `menu`/`menuItem`
  helpers.
- `lib/listing_view.dart` — `ListingView` (the folder tiles) plus the
  `CreateAlbumDialog` and `CreateFolderDialog`.
- `lib/album_view.dart` — the album: `AlbumContent`/`AlbumContentState` (edit
  mode and the save round-trip), `ContentWidgetBuilder` and
  `ImageWidgetBuilder` (turning the layout into widgets), `ThumbnailEditor`
  and the `AlbumPropertiesDialog`.
- `lib/image_view.dart` — `ImageView`, the full-screen single image viewer
  (zoom, pan, swipe and keyboard navigation).
- `lib/image_transform.dart` — the widget-free helpers of the viewer:
  `ImageTransform` (fit, wheel zoom around a point, click to 1:1, the
  re-centering on zoom-out) and the rating-filter-aware navigation
  (`nextVisible`, `previousVisible`, `firstVisible`, `lastVisible`).
- `lib/album_model.dart` — widget-free model helpers: `AlbumInitializer`
  (rebuilds the transient `previous`/`next` links) and `thumbnailName`.
- `lib/client.dart` — `VAlbumClient`, the one place that builds URLs and talks HTTP; injected via
  `VAlbumScope` so tests can pass a `MockClient`.
- `lib/urls.dart` — derives the server URL from the page origin on the web.
- `lib/album_layout.dart` — the album row layout algorithm. It is pinned by the golden fixtures in
  `test/fixtures/layout/` (see the README there); where the two disagree, the implementation is wrong.
- `lib/resource.dart` — **generated** from `image-server-shared/.../model/model.proto` by the Maven
  build. Never edit or reformat it; change the `.proto` and rebuild.

## Commands

```
flutter pub get
flutter analyze            # the bar is zero errors
flutter test
flutter run -d chrome      # against the demo server on http://localhost:9090/valbum/data
flutter build web          # output is bundled into the server jar by `mvn install`
```

## Editing an album

Edit mode is entered by long-pressing an image tile. Its app bar carries the
album properties editor (title and subtitle) and the save action. Saving PUTs
the whole `AlbumInfo` as JSON to the album's own URL — `<dataUrl>/<path>/`,
i.e. the JSON URL without its `?type=json` query (`VAlbumClient.saveAlbum`) —
where the server stores it as the `index.json` sidecar, keeping the previous
one as a backup. Only the persistent model fields are on the wire: the
generated writer omits `path`, `imageByName`, `minRating`, `owner`, `group`
and the `previous`/`next`/`home`/`end` links. After a successful write the
album is re-fetched so those transient links are rebuilt; a refused write
keeps the edit mode open and reports the HTTP status in a snack bar.

Widget tests must not touch the network: pass a `MockClient` to `VAlbumApp(client: ...)` and wrap
the pump in `withFakeImageHttp` (see `test/util/`) so image loads are answered locally.
