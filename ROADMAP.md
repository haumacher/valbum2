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
   page width — is what makes an album look like an album. `valbum_ui/lib/album_layout.dart` is now
   the **canonical** implementation; the Java `AlbumLayout` has been retired. The golden fixtures in
   `valbum_ui/test/fixtures/layout/`, generated from the Java implementation before it was deleted,
   are the **contract**: `album_layout_golden_test.dart` replays them, and where an implementation
   and a fixture disagree, the implementation is wrong.

Consequences we accept:

- **The GWT client and everything that exists only for it is deleted**: `image-server-client`,
  `util-gwt`, the HTML index page, the Bulma/Font Awesome webjars, and `util-xml` if nothing else
  needs it. This is the cleanup, and it comes first.
- **Java is no longer pinned to 1.8.** GWT 2.9 was the only reason. After the cleanup the server
  moves to a current LTS (21), and the msgbuf output must follow.
- **The Flutter app must reach GWT parity before any new feature ships.** Nothing the album
  author can do today may become impossible tomorrow.

## Phase 0 — Cleanup: server becomes an API, the repository becomes one product

*Issues #9 (GWT removal, gates the rest), #10 (static hosting), #11 (Java 21), #12 (layout goldens),
#13 (docs), #14 (CI), #15 (Flutter tests).*

Goal: `mvn install` builds a slim server jar with no GWT compile step; `flutter build web` output is
served by that jar at `/`; the tree contains only what the product needs.

- ✅ Remove `image-server-client`, `util-gwt` and the GWT plugin configuration; remove `serveIndex`,
  `Page.java`, the webjar dependencies and `util-xml` (if it is then unused). (#9)
- ✅ Serve the Flutter web build from the server root; the API stays at `/data`. The Flutter app
  derives the server URL from its own origin on the web (settings screen elsewhere: Phase 2). (#10)
- ✅ Raise the Java source/target to 21. (#11)
- ✅ Generate golden layout fixtures from the Java `AlbumLayout` (a set of image dimension lists at
  several page widths with the resulting row structure), check them in, make `album_layout.dart`
  pass them, then remove the Java layout package. *(Done: 88 fixtures in
  `valbum_ui/test/fixtures/layout/`; the Java layout package is gone.)*
- ✅ Rewrite `README.md` for the new shape (server + app), keep `CONTRIBUTING.md` honest, update
  `CLAUDE.md` and the `build-verify` skill, add a `.gitignore`, drop the Eclipse `.project`/`.settings`.
- ✅ Add continuous integration (GitHub Actions) running both toolchains' gates. (#14)
- ✅ Replace the Flutter starter template test with real widget tests using an injected HTTP client. (#15)

## Phase 1 — Feature parity: everything the GWT app can do, the Flutter app can do

*Issues #16 (sidecar save), #17 (rating filter), #18 (tile editing), #19 (groups), #20 (headings),
#21 (image view), #22 (video), #23 (listing tiles), #24 (deep links). Phases 2–4 are filed when
Phase 1 is done.*

The GWT client's capabilities, which defined "done" for this phase — all rows landed on 2026-09-05:

| Area | GWT capability | Flutter today |
|---|---|---|
| Listing | Folder tiles with index picture (scale/translate crop), title, subtitle; up/home navigation | ✅ #23 |
| Album | Title/subtitle, layout rows, headings between rows, min-rating filter (`+`/`-`), resize re-layout | ✅ #17 (filter), headings render |
| Editing | Edit mode with save to `index.json`; album title/subtitle editor | ✅ #16 |
| Image tile | Rotate left/right, flip; rating -2..2; comment editor; create heading; select (click, ctrl, shift-range); group selected images with a representative | ✅ #18, #19 |
| Heading | Edit text; delete | ✅ #18, #20 |
| Image view | Fit to screen; wheel zoom, drag pan, click for 1:1; prev/next; keyboard (arrows, Home, End); swipe gestures; comment display; navigation honours the rating filter | ✅ #21 |
| Groups | Group shown by its representative; "alternatives" detail view listing all members; navigation within a group | ✅ #19 |
| Video | Inline HTML5 playback (mp4, mov) | ✅ #22 (server range requests: #26) |
| URLs | Deep links per album/image/group via `#` paths; back/forward; scroll position memory | ✅ #24 |

Parity is delivered as one package per table row, each with widget tests. The rating filter, the
group model and the sidecar round-trip are shared mechanisms, so they land first.

## Phase 2 — The mobile promise: photos flow into the library

*Issues #27 (server settings screen, first), #28 (authentication), #29 (idempotent uploads),
#30 (camera-roll sync, needs #27–#29), #31 (offline cache). Filed 2026-09-05 when Phase 2 started.*

- ✅ Server settings screen (URL, credentials) replacing the hardcoded host; connection test. (#27; credentials with #28)
- ✅ Camera-roll sync: the app watches the device's photo library and uploads new items to a chosen
  inbox album, in the background where the platform allows, with progress and retry. Uploads are
  idempotent (content hash), so a retried upload never duplicates. (#30; server side #29: the
  server hashes every upload, answers `present` for known content, caches hashes in a per-folder
  `.hashes.json` sidecar, offers a pre-check, and never replaces an existing original) *(Done in
  the foreground: the sync runs at app start, on every library change, every 15 minutes while the
  app is open and on demand, with back-off retries and a status section in the settings. Background
  execution while the app is closed landed with #32: a `workmanager` periodic task, Android's
  fifteen-minute minimum with the constraints "network connected" and "battery not low", iOS via
  `BGAppRefresh`; the background isolate rebuilds settings, client and engine from the persisted
  store, runs one sync with the same refusals and records what it did for the settings section.)*
- ✅ Authentication: a per-device token issued by the server; the API refuses anonymous writes.
  Read access stays open for a home network and is switchable. (#28) *(Done: `--auth
  off|writes|all`, default `writes`; a device is paired with `POST <data>/?action=pair` against the
  `--pairing-secret`, the token hash is kept in `<basepath>/.valbum/devices.json` (since #45: `users.json`); a refusal is a
  401 with an `ErrorInfo` body the app shows.)*
- ✅ Offline: the app caches listings and thumbnails so the library browses without the server. (#31)
  *(Done: network first, cache only when the server cannot be reached; a bounded LRU file cache off
  the web; an offline banner with the copy's stamp; one visible refusal for changes while offline.)*

## Phase 3 — Users, groups and sharing: one library for a family

*Issues #45 (users and sign-in, gates the rest), #46 (privacy levels), #47 (move images and albums),
#48 (album date and placement rules), #49 (groups and grants), #50 (link entries), #51 (share
links), #52 (invitations and guests), #53 (uploader attribution), #54 (per-user inbox), #55
(management screens). Filed 2026-09-06.*

Goal: a family shares one server. Each member takes their own photos, files them in private albums
or adds them to the shared album of a joint event, and sees everything they may see in one tree
arranged the way they like. Guests without albums of their own contribute photos to an event they
were part of. A share link opens an album to anyone, within the limits the author sets.

The model, decided 2026-09-06 (issue bodies carry the design notes):

- **A user is the principal; devices belong to users.** Signing in on a device issues the device
  token exactly as pairing does today. The first user is the admin; everyone else comes in through
  an invitation. There is no open self-registration — a hosting platform with open sign-up is a
  different product and stays one flag away.
- **Exactly one space per user.** Every member owns exactly one top-level folder under the base
  folder, and that folder is their root. Nothing else exists from their point of view. Turning an
  existing single-user library into a space is one explicit, rename-only migration, never a
  silent start-up step.
- **Sharing is one mechanism: the grant.** A grant says who (a user, a named group, a share-link
  token, or the anonymous caller) may do what (view, download, contribute, edit) on which subtree.
  Rights are enforced on every endpoint; a path is not a permission.
- **A shared album appears in the recipient's own tree** as a link entry pointing at the owner's
  folder, placed where the recipient wants it. Access is checked against the grant on the target,
  never against the link. While browsing, the URL is the viewer's path; share links use the
  owner's path; the server resolves both.
- **Albums get a date and folders get a placement rule.** The date comes from the sidecar, else
  the folder name, else the earliest image. A folder rule ("by year") places whatever lands in it —
  a created album, a shared album's link, a moved album — into the year folder, so nobody files a
  shared album by hand. The rule places, it does not police.
- **The privacy level becomes effective.** Public, members, private — enforced on listings and on
  the image endpoints, editable on the tile, and the limit a share link is cut to.
- **Moving is a rename.** Images and albums move between folders by renaming files; pixel content
  is never touched. The inbox of the camera-roll sync is an album in the own space, emptied by
  moving; guests have no space and no sync.

## Phase 4 — The server grows up

- Sync API: "changes since" per folder using modification stamps so the app refreshes cheaply.
- Preview pipeline: multiple thumbnail sizes, WebP, video poster frames and a streamable preview
  rendition; previews generated ahead of time, never on the request path for large albums.
- Metadata: EXIF date/GPS extraction into the protocol; a date timeline and a map view in the app.
- Multi-library: several base folders served by one server.

## Phase 5 — Distribution

- Docker image and a Raspberry Pi install recipe for the server.
- Store builds for Android and iOS; desktop bundles.
- Release automation from tags.

## Decisions log

- **2026-09-06 (night)** — Phase 3 sharing, first four issues: users and spaces (#45), privacy
  enforced on the server (#46), moving by rename with duplicates set aside in `.valbum/duplicates/`
  rather than removed (#47, overriding the issue's "removes the source" to keep "the server never
  deletes an original"), and album dates with placement rules (#48, server side). Two dates travel on
  the wire — the explicit `date` that is stored and the derived `effectiveDate` that never is — so a
  round trip can never freeze a derived date into a sidecar. Year folders are `YYYY`, month folders
  `YYYY/YYYY-MM`. Listings are ordered newest first from now on. The app half of #48 (date picker,
  folder rule editor, listing sort) is open.

- **2026-09-06 (evening)** — Phase 3 is redefined as *users, groups and sharing* (issues #45–#55);
  the former Phase 3 (sync API, previews, metadata) becomes Phase 4 and distribution Phase 5. The
  brainstorm settled three alternatives: one space per user with sharing to named groups beats
  several spaces per user (a member wants one tree they arrange themselves, not spaces to
  remember); shared albums are link entries in the recipient's tree placed by a date-based folder
  rule, not links to the owner's year folders (those would leak private albums or never merge
  with the recipient's own years); photos move between albums by file rename, not by albums that
  reference images elsewhere (which would break "the folder tree is the truth"). Invitation-only,
  no open registration.

- **2026-09-06 (later still)** — A double-height row section reads **row-wise**: its upper row shows
  a prefix of the section's images in stored order, its lower row the remaining suffix, and the
  split point is the one that balances the two row heights best (a tie gives the upper row the
  extra image). Before, every image went to the currently narrower row, so the section zig-zagged
  against the stored order and a drop right of an image landed *below* it (#44). This reverses the
  "the Java-generated goldens are the contract" rule for the 16 fixtures that contain a `DoubleRow`
  and disagreed: they were regenerated from Dart by `test/tool/regenerate_layout_goldens.dart` and
  Dart is their reference from now on; every fixture without a `DoubleRow` is untouched, see
  `valbum_ui/test/fixtures/layout/README.md`. What is *not* fixed by this: the row computation still
  buffers a run of landscape images across a portrait image, so a portrait can change places with
  the landscapes around it — inside a section the order is now exact, between a portrait and its
  neighbours it is not, and the reorder keeps working off the displayed order for that reason.

- **2026-09-06 (later)** — Background camera-roll sync (#32) rests on one seam: `runBackgroundSync`
  is a plain function that rebuilds settings → client → photo library → `CameraRollSync` from
  nothing but the persisted `SettingsStore`, and `CameraRollSync.runOnce` runs exactly one sync
  without arming a change stream, a periodic scan or a retry timer — a background isolate is torn
  down when its run returns, so nothing may outlive it. The platform side is a `BackgroundScheduler`
  interface chosen by the same conditional import that picks the photo library, so the web and the
  desktops answer "not available on this platform" instead of promising a sync nothing keeps. A
  refused background run is not a failed task: the platform re-runs the periodic work anyway, and
  the reason is written into a second store blob the settings section shows.

- **2026-09-06** — Phase 2 complete (issues #27–#31): a device names its server, pairs with it,
  browses from a cached copy when it is away, and its camera roll flows into an inbox album with
  the server de-duplicating by content hash. The Android build was repaired the same day (#33, the
  checked-in Gradle wrapper could not run on JDK 21) and the background execution of the sync
  followed (#32), so nothing of Phase 2 is left open. Phase 3 is next; its issues are filed
  when work on it starts.

- **2026-09-05 (evening)** — Phase 0 and Phase 1 complete (issues #9–#24). The Flutter app does
  everything the GWT client did, verified live on the bundled jar: deep links, keyboard navigation
  and the row layout under a context path. Server follow-ups from the parity work: #25 (root album
  save), #26 (video content type and range requests). Phase 2 is next; its issues are filed when
  work on it starts.

- **2026-09-05** — GWT and HTML front ends are retired; Flutter is the only client. The album
  layout algorithm is preserved in Dart with Java-generated golden fixtures. Java moves off 1.8.
  Cleanup (Phase 0) precedes parity (Phase 1) precedes any new feature.
