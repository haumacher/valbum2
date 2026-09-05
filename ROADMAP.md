# VAlbum2 — Direction and roadmap

*Adopted 2026-09-05. This document records where the project is going and why. The GitHub issue
tracker holds the concrete work queue; each roadmap item below names the issues that implement it.
Reversals of a decision recorded here are made by editing this file in the same commit.*

## Where we came from

VAlbum started as a Java server rendering HTML pages, became a Java server hosting a GWT single-page
app, and most recently gained a Flutter app that talks to the same JSON protocol. Three front-end
generations now coexist in one repository. The GWT client is feature-complete but web-only; the
Flutter app runs on web, Android, iOS and desktop but only covers browsing, album creation and
upload — its edit mode is a stub.

## Where we are going

**VAlbum is a photo library that lives on your own hardware and is used through one app on every
device you own.** The server owns the files; the app owns the experience.

1. **One server, API only.** The Java server scans the album folder tree, serves the protocol
   (JSON), originals, thumbnails and previews, accepts uploads, and persists edits in sidecar files.
   It renders no HTML and ships no JavaScript of its own. It hosts the Flutter web build as static
   files at `/`, so a browser is still a first-class client.
2. **One client, everywhere.** The Flutter app is the only front end: web, Android, iOS, Linux,
   Windows, macOS. On mobile it becomes the reason the project exists — photos taken on the phone
   flow into the library without cables or cloud services.
3. **One protocol.** `model.proto` remains the single source of truth; the msgbuf generator emits
   the Java and the Dart model. The JSON on the wire and the `index.json` sidecar format are the
   contract; old albums must always load.
4. **The album layout is the heart.** The row layout algorithm — landscape images in rows, portrait
   images paired with stacked "double landscape" rows, panoramas full-width, all scaled to fill the
   page width — is what makes an album look like an album. It exists in Java (`AlbumLayout`) and in
   a Dart port (`album_layout.dart`). The Dart implementation becomes canonical; the Java one is
   retired only after golden fixtures generated from it prove the port equivalent.

Consequences we accept:

- **The GWT client and everything that exists only for it is deleted**: `image-server-client`,
  `util-gwt`, the HTML index page, the Bulma/Font Awesome webjars, and `util-xml` if nothing else
  needs it. This is the cleanup, and it comes first.
- **Java is no longer pinned to 1.8.** GWT 2.9 was the only reason. After the cleanup the server
  moves to a current LTS (21), and the msgbuf output must follow.
- **The Flutter app must reach GWT parity before any new feature ships.** Nothing the album
  author can do today may become impossible tomorrow.

## Phase 0 — Cleanup: server becomes an API, the repository becomes one product

Goal: `mvn install` builds a slim server jar with no GWT compile step; `flutter build web` output is
served by that jar at `/`; the tree contains only what the product needs.

- Remove `image-server-client`, `util-gwt` and the GWT plugin configuration; remove `serveIndex`,
  `Page.java`, the webjar dependencies and `util-xml` (if it is then unused).
- Serve the Flutter web build from the server root; the API stays at `/data`. The Flutter app
  derives the server URL from its own origin on the web and from a settings screen elsewhere.
- Raise the Java source/target to 21 and modernize what the compiler now allows.
- Generate golden layout fixtures from the Java `AlbumLayout` (a set of image dimension lists at
  several page widths with the resulting row structure), check them in, make `album_layout.dart`
  pass them, then remove the Java layout package.
- Rewrite `README.md` for the new shape (server + app), keep `CONTRIBUTING.md` honest, update
  `CLAUDE.md` and the `build-verify` skill, add a `.gitignore`, drop the Eclipse `.project`/`.settings`.
- Add continuous integration (GitHub Actions) running both toolchains' gates.
- Replace the Flutter starter template test with real widget tests using an injected HTTP client.

## Phase 1 — Feature parity: everything the GWT app can do, the Flutter app can do

The GWT client's capabilities, which define "done" for this phase:

| Area | GWT capability | Flutter today |
|---|---|---|
| Listing | Folder tiles with index picture (scale/translate crop), title, subtitle; up/home navigation | Tiles without crop transform |
| Album | Title/subtitle, layout rows, headings between rows, min-rating filter (`+`/`-`), resize re-layout | Layout and headings; no filter |
| Editing | Edit mode with save to `index.json`; album title/subtitle editor | Edit mode is a stub; save does not persist |
| Image tile | Rotate left/right, flip; rating -2..2; comment editor; create heading; select (click, ctrl, shift-range); group selected images with a representative | Selection only; group is a no-op |
| Heading | Edit text; delete | None |
| Image view | Fit to screen; wheel zoom, drag pan, click for 1:1; prev/next; keyboard (arrows, Home, End); swipe gestures; comment display; navigation honours the rating filter | InteractiveViewer zoom; tap to open |
| Groups | Group shown by its representative; "alternatives" detail view listing all members; navigation within a group | Representative shown; no detail view |
| Video | Inline HTML5 playback (mp4, mov) | Thumbnail only |
| URLs | Deep links per album/image/group via `#` paths; back/forward; scroll position memory | None on web |

Parity is delivered as one package per table row, each with widget tests. The rating filter, the
group model and the sidecar round-trip are shared mechanisms, so they land first.

## Phase 2 — The mobile promise: photos flow into the library

- Server settings screen (URL, credentials) replacing the hardcoded host; connection test.
- Camera-roll sync: the app watches the device's photo library and uploads new items to a chosen
  inbox album, in the background where the platform allows, with progress and retry. Uploads are
  idempotent (content hash), so a retried upload never duplicates.
- Authentication: at minimum a per-device token issued by the server; the API refuses anonymous
  writes. Read access can stay open for a home network but must be switchable.
- Offline: the app caches listings and thumbnails so the library browses without the server.

## Phase 3 — The server grows up

- Sync API: "changes since" per folder using modification stamps so the app refreshes cheaply.
- Preview pipeline: multiple thumbnail sizes, WebP, video poster frames and a streamable preview
  rendition; previews generated ahead of time, never on the request path for large albums.
- Metadata: EXIF date/GPS extraction into the protocol; a date timeline and a map view in the app.
- Sharing: per-album share links with an expiry, respecting the image privacy level.
- Multi-library and multi-user, once authentication exists.

## Phase 4 — Distribution

- Docker image and a Raspberry Pi install recipe for the server.
- Store builds for Android and iOS; desktop bundles.
- Release automation from tags.

## Decisions log

- **2026-09-05** — GWT and HTML front ends are retired; Flutter is the only client. The album
  layout algorithm is preserved in Dart with Java-generated golden fixtures. Java moves off 1.8.
  Cleanup (Phase 0) precedes parity (Phase 1) precedes any new feature.
