# valbum_ui — the VAlbum app

The Flutter client of [VAlbum2](../README.md): one code base for the web, Android, iOS, Linux,
Windows and macOS. It talks to the Java server's JSON API under `/data/` and renders listings,
albums (with the row layout in `lib/album_layout.dart`) and single images.

## Layout

- `lib/main.dart` — `main()` only; it re-exports the libraries below, so
  `package:valbum_ui/main.dart` remains the one import a test needs.
- `lib/app.dart` — the application shell: `VAlbumApp`, the `VAlbumScope`
  wiring and the router (`VAlbumRouterDelegate`,
  `VAlbumRouteInformationParser` and `VAlbumNavigator`, the navigation API
  every view uses instead of the `Navigator`), `VAlbumView`/`VAlbumState`
  (loads the listing or album a route lives in and shows the view the route
  names), the per-album scroll memory, image upload, and the `menu`/`menuItem`
  helpers.
- `lib/routes.dart` — the URL grammar: the `VAlbumRoute` kinds (listing or
  album, image, alternatives, group member), `parseRoute`/`routeToUri` and the
  app base (`appBasePath`). Pure, no widgets.
- `lib/listing_view.dart` — `ListingView` (the folder tiles) plus the
  `CreateAlbumDialog` and `CreateFolderDialog`; a long press on a tile opens its menu with "Move to…".
- `lib/move_view.dart` — the move of issue #47: `FolderPicker` (browses the caller's own tree
  from the root through the injected client, chooses a folder or an album as the target, creates
  nothing) and `moveWithPicker`, which posts the move, reloads and reports every outcome — a
  snack bar when all moved, a dialog naming each refusal in the server's words.
- `lib/album_view.dart` — the album: `AlbumContent`/`AlbumContentState` (edit
  mode and the save round-trip), `ContentWidgetBuilder` and
  `ImageWidgetBuilder` (turning the layout into widgets), `ThumbnailEditor`
  and the `AlbumPropertiesDialog`; the rating filter (`RatingFilterBar`, the
  `+`/`-` keys) and the per-tile overlay toolbars (rotate, flip, rating,
  privacy level, comment, heading), the privacy marker on restricted tiles and the "view as"
  preview switch of the edit mode (a read-only look at the album as members or the public see it).
  "Move to…" in the edit app bar moves the selection to another album (`lib/move_view.dart`).
- `lib/image_view.dart` — `ImageView`, the full-screen single image viewer
  (zoom, pan, swipe and keyboard navigation).
- `lib/group_view.dart` — the "alternatives" view of an `ImageGroup`
  (`GroupView`, reached by the "down" chevron of the image viewer) and
  `GroupDetailView`, the viewer navigating within a group.
- `lib/album_edit.dart` — widget-free editing logic: the orientation algebra
  (`PlaneTransform`, `OrientationOps`), the rating filter, the privacy levels, the selection
  arithmetic behind the tile editor and the grouping operations
  (`groupSelection`, `ungroup`).
- `lib/image_transform.dart` — the widget-free helpers of the viewer:
  `ImageTransform` (fit, wheel zoom around a point, click to 1:1, the
  re-centering on zoom-out) and the rating-filter-aware navigation
  (`nextVisible`, `previousVisible`, `firstVisible`, `lastVisible`).
- `lib/album_model.dart` — widget-free model helpers: `AlbumInitializer`
  (rebuilds the transient `previous`/`next`/`home`/`end` links, the `owner` of
  every part and the `group` of every image inside an `ImageGroup`; the images
  of a group are linked among themselves, which is the order the alternatives
  view navigates in) and `thumbnailName`.
- `lib/client.dart` — `VAlbumClient`, the one place that builds URLs and talks HTTP; injected via
  `VAlbumScope` so tests can pass a `MockClient`. `loadPreview` is the one load that bypasses the
  offline cache: a "view as" preview is never the copy the app browses offline. `move` posts the
  `?action=move` write and returns the server's outcomes.
- `lib/settings.dart` — the server settings: URL, the sign-in (sign-in code, device
  name) and who this device is signed in as (user, role, device, space); the settings store.
- `lib/device_code_payload.dart` — what the QR code of a device code carries and the only place
  scanned text is interpreted: `valbum-device://pair?server=…&code=…`, deliberately no URL the
  server serves, so a forwarded picture is worth exactly as much as a forwarded code (issue #66).
  Pure, no widgets.
- `lib/device_code_scanner.dart` — the camera behind an interface (`DeviceCodeScanner`,
  `NoDeviceCodeScanner`, `FakeDeviceCodeScanner`, `DeviceCodeScannerScope`), with the
  `mobile_scanner`-backed implementation in `lib/device_code_scanner_plugin.dart`, reached only
  through the conditional import of `lib/platform.dart` — the web build never links it.
- `lib/video_view.dart` — inline video playback: `VideoView` plays the server's playback rendition
  (`?type=video`, issues #74/#75), waits out a pending one with "The video is being prepared…" and
  a "Play the original" button, falls back to the original where there will be none, and says a
  failure in one plain sentence with the raw text in the diagnostics log (#73); `VideoTeaser` plays
  the three-second teaser (`?type=teaser`) while the pointer rests on a video tile, on pointer
  platforms only. The controller is injected (`VideoControllerFactory`), so tests reach no plugin.
- `lib/upload_progress.dart` — the dialog an upload runs behind (issue #70): one measurement,
  images (`12 von 48 Bildern`, wrapping, never truncated), the percentage in a determinate wheel,
  and a Cancel that asks the upload to stop. It never closes itself — the code that opened it
  closes it once the server has answered (issue #59). What it shows is `UploadProgress`, the one
  value `VAlbumClient.uploadNew` reports.
- `lib/urls.dart` — derives the server URL from the page origin on the web
  (from the app base, not from the location: the location is the view, see
  `lib/routes.dart`).
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

## The words the app says (issue #108)

Every user-facing string lives in an ARB file below `lib/l10n/` and is read
through `AppLocalizations`; the platform locale decides which language is
shown, and there is no switch in the app. A device asking for a language the
app does not carry reads **English**, the language the strings are written in
— `resolveAppLocale` in `lib/locales.dart`, installed as the
`localeListResolutionCallback` of both `MaterialApp`s, because Flutter's own
resolution would answer whatever `gen-l10n` listed first, which is an accident
of the alphabet. English is the source and
**`lib/l10n/app_en.arb` is the only file edited by hand** — the same doctrine
as `lib/resource.dart`:

| file | written by |
| --- | --- |
| `lib/l10n/app_en.arb` | **you** |
| `lib/l10n/app_de.arb` (and every further language) | `gradle translateArb` |
| `lib/l10n/app_localizations*.dart` | `flutter gen-l10n` |

Adding a string:

1. put the key, the text and a `description` into `lib/l10n/app_en.arb`
   (a count or a name is an ICU placeholder, and a count is an ICU plural);
2. `gradle -p . translateArb` from this directory — the Gradle plugin
   `de.haumacher.auto-translate-arb` (see `build.gradle`) translates only what
   changed through DeepL, keeping the placeholders and the plurals, and reads
   its key from `deepl.apiKey` in `~/.gradle/gradle.properties` or from
   `DEEPL_API_KEY`;
3. `flutter gen-l10n` to regenerate the Dart;
4. read it as `AppLocalizations.of(context)!.myKey`.

The generated Dart **is** checked in, so a clone builds and CI runs without a
DeepL key and without a `gen-l10n` step of their own.

What the *server* says is shown as it arrives, and so is a transport failure:
the protocol carries an `ErrorInfo.code` beside its message, and mapping those
to localized texts is a follow-up (see `refusalMessage` in `manage_view.dart`).
What the app itself authors is never thrown to be read — an exception message
is for whoever catches it, and the catch answers a localized sentence of its
own, see `serverUrlError` in `lib/urls.dart`.

`test/l10n_guard_test.dart` holds the line: every language carries the keys and
the placeholders of `app_en.arb`, the fallback language is English, the files
listed in its `convertedFiles` carry no user-facing literal any more, and none
of them throws a sentence that is not named in `allowedThrownLiterals` with the
reason nothing shows it. A slice that converts a further screen adds its files
to that list. `test/util/l10n.dart` is what a widget test pumps with —
`localizedApp(widget, locale: …)` and `l10nOf(locale)` — and it resolves
locales by the app's own rule. `test/l10n_probe_test.dart` is the review probe,
kept as a test.

Converted so far: slice 1 the server settings screen and what it is made of
(`settings.dart`, `sign_in_form.dart`, `first_screen.dart`, `manage_view.dart`,
`caller.dart`, `urls.dart`, `device_code_scanner.dart`); slice 2 the share link
and the share session (`share_view.dart`, `share_session.dart`), the
camera-roll section with its inbox picker (`camera_roll_view.dart`), the
alternatives view (`group_view.dart`), the video player (`video_view.dart`),
the in-app photo picker (`photo_picker_view.dart`), the upload dialog
(`upload_progress.dart`) and the code scanner of a phone
(`device_code_scanner_plugin.dart`). `test/l10n_german_test.dart` carries one
German assertion block per converted screen.

Still English, and why: `invitation.dart` is converted but for
`invitationNoticeText`, whose sentences `app.dart` composes without an
`AppLocalizations` at hand; `attribution.dart` ("Added by …") is asked by
`image_properties.dart` through `attributionShown(image)`, which has no
context either; `cache_refresh.dart` throws its transport sentence where
`client.dart` throws its own. All three are one call-site change away and
belong to the slice that converts the album.

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

## The URL is the view

The app uses real paths (no `#`): `/<album>/` is a listing or an album,
`/<album>/<image>` the single image viewer, `/<album>/<image>/alternatives/`
the group's alternatives view and `/<album>/<image>/alternatives/<member>` one
of its images in detail mode — the URLs the retired GWT client kept in its
hash. Every navigation is a route change, so the browser's back and forward
buttons work and a view can be bookmarked; the scroll offset of an album is
remembered while an image of it is being viewed. Route paths are relative to
the `<base href>` the web build writes into `index.html`, which is the
server's context path (`/valbum/` in the demo). See `lib/routes.dart`.
