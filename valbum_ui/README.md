# valbum_ui — the VAlbum app

The Flutter client of [VAlbum2](../README.md): one code base for the web, Android, iOS, Linux,
Windows and macOS. It talks to the Java server's JSON API under `/data/` and renders listings,
albums (with the row layout in `lib/album_layout.dart`) and single images.

## Layout

- `lib/main.dart` — the app: listing, album and image views, editing, upload.
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

Widget tests must not touch the network: pass a `MockClient` to `VAlbumApp(client: ...)` and wrap
the pump in `withFakeImageHttp` (see `test/util/`) so image loads are answered locally.
