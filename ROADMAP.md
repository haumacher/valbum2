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

- ~~Raspberry Pi install recipe for the server~~ — done: a Debian package (`valbum`, arm64/amd64/armhf)
  from an APT repository on GitHub Pages, systemd unit, `/etc/default/valbum`.
- ~~Release automation from tags~~ — done: `valbum-<x.y.z>` builds the packages and a signed Android APK,
  publishes the GitHub Release and rebuilds the APT site.
- Docker image for the server (from the same platform jar).
- Store builds for Android and iOS; desktop bundles.

## Phase 6 — Spaces: the multi-user model reshaped

*Decided 2026-09-15 by the author; issues #82–#85 filed the same day and delivered 2026-09-16
(follow-up: #86, the first admin of a space must have a name). Supersedes the Phase 3 model of
grants, groups, links and guests, which is retired: its endpoints answer 410 for one release.*

Goal: a server hosts one or several **spaces**. A space is a mandator: it has its own users, its
own albums and its own share links, and nothing crosses its boundary — no shared user, no move,
no link, no grant into another space. Within a space every user holds one permission for every
album; who may see what is decided by two independent axes and one flag, not by a tree of grants.

The model:

- **Two server modes.** *Single-space*: the base folder is the one space, the layout of an
  un-migrated library today. *Multi-space*: every folder directly under the base folder that
  carries a `.valbum/space.json` is a space; a space is created on disk by hand, never by the
  server. The space is the first path segment of the app base a person types
  (`https://host/valbum/<space>/`); single-space mode keeps today's addresses.
- **Users belong to exactly one space** and live in the space's own `.valbum/users.json` with
  their devices. One person in two spaces is two accounts; we assume that does not happen.
- **Permission = role × clearance + share flag.** Role ∈ {admin, edit, contribute, view} says what
  a user may do; clearance ∈ {public, non-private, all} says which privacy levels they see; the
  share flag says whether they may create share links. Admin implies edit, full clearance, share
  and the management of the space's users. A user holds the same permission in every album of the
  space. Per-image privacy stays; clearance is what it is compared against.
- **Anonymous access is a property of the space** (`none` or `public images only`), set in
  `space.json`, not a server mode.
- **Bootstrap and invitations.** A space has a nameless admin from the moment it exists, and the
  server prints a single-use seat code for them while they have no device (#89); whoever redeems
  it chooses their own name. Every further user comes in through an invitation issued by an admin,
  carrying the role and clearance the invitee gets. No guest accounts and no pseudo-spaces: a
  guest is a user with view and a low clearance.
- **A share link is not a user.** It is an expiring, restricted view of one album or folder,
  created by a user with the share flag, cut to at most the creator's clearance, optionally
  allowing contribution with attribution to the link. On the web it shows the shared album as the
  top-level entity. Copying a shared album into one's own space is a future extension.
- **Migration is explicit and reported.** An un-migrated library becomes the one space of a
  single-space server; a library migrated per user becomes a multi-space server where each user's
  folder is a space and the user its admin. Grants held into a space by others, groups,
  materialised links, share registries and guest pseudo-spaces cannot be represented and are
  dropped, each named in a start-up report so the admin can re-invite.
- **The endpoints do not change their questions.** Every endpoint keeps asking one method for
  rights and one for clearance; this phase changes what those two methods consult.

## Phase 7 — People: who is in a photo

Design record: issue #123 (2026-09-20). The server detects the faces of every photograph of a space
that opted in (`space.json` `faces: on`, default off), embeds and clusters them per album, and
serves face crops; detection and embeddings are cache in the album's `.vacache`, keyed by the content
hash and the model version, and never leave the server. A confirmed name is a human decision stored
on the `ImagePart` in `index.json` as a normalised box in the raw raster plus a person of the
space-level register (`<space>/.valbum/people.json`); a rejection is a confirmation too. Suggestions
are derived at request time and never auto-confirmed. Naming needs `edit`; faces are answered to
signed-in members only, never to a share link or an anonymous caller. The app's editor is per album
first ("Persons in this album"). Packages in delivery order: #124 (detection, clustering, crops),
#125 (register, confirmed tags, the tagging action), #126 (the face editor), #127 (recognition and
suggestions across the space), #128 (a person linked to a member), #129 (XMP face regions imported).

## Decisions log

- **2026-09-22** — 2.5.0 released (tag `valbum-2.5.0` on 3d5d7df; Debian packages for amd64, arm64 and
  armhf, the signed APK, the APT site), master opened 2.6.0. Phase 7 (People) delivered in one day:
  face detection and clustering per album with crops (#124), the people register and confirmed tags
  (#125), the face editor "Persons in this album" (#126), recognition across the space answered as
  suggestions (#127), a person linked to a member (#128), XMP face regions imported (#129), a decision
  taken back (#138); the inbox tile counts what waits (#137); the whole app speaks the user's language
  (#108, three slices). Recorded deviations from the design record: a face carrying any decision is
  never suggested anybody; the float32 SFace model is bundled and checked in (+34.6 MiB on the .deb).

- **2026-09-21** — Phase 7 (People) opened with #124. Also this day: 2.4.0 released the evening
  before and master opened 2.5.0; a folder of folders has no date (#133) and may be shown by a chosen
  child's picture (#110); a moved-into folder is fetched again (#134); the camera-roll sync falls back
  to the default inbox when its chosen one is renamed away (#132); an inbox is a kind of album with a
  screen of its own, dated order on read, visible to editors and own contributors only, single-image
  delete to the trash (#131, #135, #136); the app speaks the user's language, English the source and
  German the first DeepL translation, the platform locale deciding (#108, two slices of three).

- **2026-09-19** — An invitation is a pending user carrying a code (#89, step two). The author,
  finishing the unification of #89: "When creating an invitation, allow adding a name to have a
  memento to what person you sent this invitation - optional." And, on what an invitation is:
  "Each space can have an unnamed admin user when created. When a seat code is entered for an
  unnamed user, the app can ask the user for a name. This makes inviting a user more easy, because
  the user can name himself." So issuing an invitation **creates the user at once** — nameless,
  deviceless, with the permission the inviter chose — and one ordinary single-use code for them;
  the `/i/<token>/` link *is* that code, and joining is the ordinary pairing that names the user
  and adds their first device. There is no second store, no second redemption path and no second
  kind of token left: **`invitations.json` is retired**, read once at start-up and set aside under
  `.valbum/retired/`, with every live invitation carried over under its own token hash so that a
  link somebody was sent last week keeps working. `?type=invitations` is derived from the users
  and their codes. What follows from "a pending user is a user" is the rest of the design:
  the users list shows the seats still waiting for somebody, withdrawing or expiring an invitation
  removes that seat again while somebody who already joined keeps their account, and removing an
  inviter withdraws what they put in the post. The **recipient memento** is the inviter's own note
  about whom they wrote to; it names the seat until the person chooses a name, stays beside the
  name afterwards — and is never shown to the invitee, because it is a note somebody made to
  themselves.

- **2026-09-19** — The backup code is the missing end, and the sign-in form belongs where the
  refusal is (#91, #92). The author, on the sign-out button: "While it is required, when opening
  an album on a foreign device, it is dangerous, because you normally cannot re-login, because you
  need another device to create a code. So there is still a missing end." And on what closes it:
  "The backup code is fine — but since it must live forever, it should be much longer." So a
  **backup code**: the same record in the same store as every other code (#89), told apart by one
  field, and differing in exactly two values — sixteen characters instead of eight, because it
  is written down and kept, and **no expiry at all**, because the day it is needed is the day
  nobody could foresee. It is exempt from the rule that a code dies with the device that issued
  it, for the plain reason that the device that issued it is precisely the one that will be gone;
  what it rests on instead is that it is one's own, single use, one per user, and withdrawn by
  making a new one. Signing out of one's **last** device now asks first and names the ways back in
  words, and asks even when the device list could not be read, because "probably fine" is no
  answer to a door that locks behind one. And the author, on signing in: "After codes/secrets are
  unified, you should be able to log in simply by entering a code — at least in a browser,
  since the server is already known there." So the browser's "sign-in required" page **is** the
  sign-in form, one widget shared with the server screen; off the web the app opens with one
  screen, one field that takes an address **or** an invitation link, and the scanner beside it;
  and every code that is shown is offered as characters, as a QR code and as the same payload in a
  link one can send.

- **2026-09-19** — The pairing secret and the device code are one mechanism (#89, step one).
  The author's observation: "A pairing secret is more or less a device code that is offered by the
  server for the admin account during startup. The only (?) difference is that the user gives
  himself a name during the first assignment." So there is one thing left: a **code** — a
  single-use secret, ten minutes, that adds one device to one target user. Three issuers, and the
  issuer is all that differs: the server at start-up (the **seat code**, for a space's
  administrator while they have no device), a signed-in device (for its own user, #65), and an
  administrator (the **recovery code**, for anybody who cleared a browser or reinstalled an app).
  Every space has its administrator from the moment its folder does — nameless and without
  devices; the seat is never empty, only unnamed — and redeeming a code for a nameless user
  requires a name, which the app asks for and the person chooses for themselves. That is also what
  makes inviting easy: nobody ever names anybody else. `--pairing-secret` is gone: given, the
  server refuses to start and names `--admin-code <code>`, which fixes the printed code for a demo
  server or for `/etc/default/valbum`; a pairing that still carries `secret` is answered `410`
  naming its replacement, for one release. Losing the last device of a space is recovered by a
  restart, which only somebody with the machine can do — the same trust anchor as before, without
  a standing master key printed into a journal. **This reverses the #86 decision** that a nameless
  administrator can only come from a library written before Phase 6: a nameless administrator
  *without devices* is now the fresh seat of every space, and #86's start-up auto-naming applies
  only to a nameless administrator *with* devices, where nobody would ever be asked. Step two
  (invitations as a pending user carrying a code, with an optional recipient memento) follows.

- **2026-09-16** — Phase 6 delivered (#82–#85). A server hosts one or several spaces, decided at
  start-up by the presence of `space.json`; in multi-space mode the space is the first path segment
  of everything that belongs to it, sessions included, and a root-level session address is refused
  with a sentence saying where such links live. Each space has its own user store, so credentials of
  one space are nothing in another and no permission check knows about spaces. Permission is role ×
  clearance + share flag, stored per user, the same for every path; the rights method no longer looks
  at the path. A share link is its own permission, refused rather than trimmed when it would show
  more than its maker may see, so what it shows is frozen when it is made; removing a user withdraws
  their links. The app reads sessions under a space, offers what the role allows where the server
  answered no rights, lets an admin edit and remove users, and lost the groups and link screens.
  Verified in a real browser on a two-space server: the app under `/valbum/alice/` asks
  `/valbum/alice/data/`, the closed space refuses anonymous callers, the pairing makes the space's
  admin, the album renders. About 250 server tests of the retired mechanisms went with them.

- **2026-09-15 (evening)** — Spaces (Phase 6). The author reshaped the multi-user doctrine: album
  spaces are separated from users and become mandators — disjoint, with their own users, sharing
  nothing and moving nothing across their boundary. A user holds one permission for the whole
  space, expressed on two axes (role: admin, edit, contribute, view; clearance: public,
  non-private, all) plus a share flag, which resolves the tension between "the same permission in
  every album" and per-image privacy. Anonymous access becomes a property of the space. Share
  links stay expiring restricted views without a user. Grants per subtree, groups, links between
  spaces and guest pseudo-spaces of the Phase 3 model are retired with an explicit, reported
  migration. One person in two spaces is assumed not to exist. Also this day: previews decode at
  preview scale and are generated once (#67–#69), video renditions and teasers (#74, #75), the
  device code as a QR code (#66), image-counting upload progress (#70), the heading anchor (#71),
  video dating and playback on Android (#72, #73), sort by date (#76), recording-time adjustment
  (#77), camera selection (#78), the fullscreen description edit (#80).

- **2026-09-13 (night)** — Distribution (Phase 5). The server installs on a Raspberry Pi like any other
  package: `apt install valbum` from an APT repository at <https://haumacher.github.io/valbum2/>, and
  `apt upgrade` is the update path. The package is built inside the Maven build with `jdeb` (pure Java,
  no dpkg tooling on the runner), only when the build is restricted to one JavaCPP platform — the
  unrestricted jar carries 2.3 GB of natives for every platform, the arm64 subset makes an 85 MB package.
  It installs a wrapper, a systemd unit running as the system user `valbum`, and `/etc/default/valbum` as
  the one place to configure (the library folder, port, context path, auth mode); a variable set in the
  environment wins over the file, a missing library folder — a USB disk not yet mounted — is refused and
  retried by systemd rather than served empty. The default library `/var/lib/valbum` is never removed,
  not even on purge: the package obeys the same doctrine as the server, it does not delete photos. The
  release workflow runs on a `valbum-<x.y.z>` tag, sets the pom version from the tag without committing
  it, and treats the GitHub Release assets as the truth: the Pages site is a derived view rebuilt from the
  newest two releases on every run (a Pages site is capped near 1 GB), so nothing is carried between runs
  and `workflow_dispatch` republishes it any time. An APT repository or an APK is never published
  unsigned or debug-signed; a missing secret fails the job with the command that creates the key. The
  Android APK rides the same release, `versionCode` derived from the version so no counter is kept.

- **2026-09-13 (evening)** — Management (#55), app side, and the empty root (#56). The screens are sections
  of the server settings, not screens of their own: my devices, open invitations and (for the admin) the
  users are all about *this device and this person*, which is what the settings screen is. Removing the
  asking device from its own list *is* the sign-out — the server takes the entry, the device forgets its
  token through the same code the sign-out button runs — so there is one way to sign out, not two that
  drift apart. Groups are the exception and got a screen reached from the settings, because a group is
  about other people and outlives the device; the group dialog moved out of the share dialog so both
  reach it. Every section asks only what its caller's role would not have refused (a guest is asked
  nothing about users, invitations or groups) and shows the server's own sentence when it refuses. An
  empty listing says what it is instead of showing a black page: the guest's own root says nothing has
  been shared yet, a library root says it has no albums (and how to make one where the caller may), any
  other folder that it is empty; inside a share link the shared folder reads as a folder.

- **2026-09-14** — Management (#55), server side. Three gaps the screens could not do without. A device has
  an identity: a short opaque id assigned at pairing, given to the devices of an older `users.json` on the
  first load (written back once, the `devices.json` takeover pattern), so that two devices called "probe"
  can be told apart; `?type=devices` lists the caller's own with the asking one marked, `?action=unpair`
  removes one of them — the asking one included, which is "sign out here" done properly — and an id of
  anybody else's device is the same 404 as an unknown one. The admin manages users, not other people's
  devices. The admin's user list carries space, creation instant and device count. A group is renamed in
  one step with every grant naming it, groups first, grants second: a crash in between leaves a grant to a
  name nobody carries, which matches nobody (fail-closed), and renaming back restores the prior state
  exactly. Found by the review probe: a group could be *created* under a user's name though a user may not
  take a group's (#52) and a rename refused it — both directions now refuse with one sentence.

- **2026-09-13 (late, later)** — Camera-roll inbox (#54). The inbox is an album in the own space, and
  choosing one is optional: the first run without a chosen inbox creates `Inbox` at the space root and
  stores the path the server answers — the root's placement rule may have filed it into a year — so the
  server, not the request, says where new photos go. A name already taken is resolved by handling the
  server's conflict, not by asking the listing first (two devices can create the inbox in the same
  moment): an album standing there is adopted, anything else is the server's sentence. The picker offers
  no link tiles — a camera roll dropped into a shared album would pour the device into somebody else's
  library. A guest has no space: the switch and the picker are disabled with the issue's sentence, and a
  run refuses with it too, since a device's config may still say "enabled" from another life; the
  background run asks the server the same question and refuses the same way. Nobody said who is calling
  reads as "not a guest", so anonymous and older servers behave as before.

- **2026-09-13 (late)** — Uploader attribution (#53), app side. One caption per image: "Added by <label>"
  above the comment wherever the image is looked at, shown to everybody but the contributor themself — a
  share-link caller is a `token:` subject whose id the app never learns, so a link always sees the line, as
  does an anonymous caller. The tile properties name the contributor to the editor too. "Take back…" in the
  image view is the move of #47 with the picker confined to the caller's own space, offered exactly where
  the server would allow it: the caller is the contributor, holds `contribute` but not `edit`, is no share
  link and no guest; the viewer leaves the photo with it. No bulk take-back — selection lives in the edit
  mode a contributor does not have — and none from inside a group's alternatives, since a group moves whole
  by its representative.

- **2026-09-13 (night)** — Uploader attribution (#53), server side. Attribution is recorded once, at the
  upload, in the sidecar the upload already writes — `.hashes.json`, not `index.json`, which is built from
  disk and written by the app: each hash entry gains the uploader's subject (`user:<name>`, `token:<id>`,
  `anonymous`) and a label copied at that moment (the user's name, the link's label), so a withdrawn link
  still names who contributed, and an upload already present keeps its first contributor. On the wire
  `ImagePart.contributor`/`contributorLabel` are derived on read like the effective date and cleared before
  any sidecar write, so the app's PUT can neither persist nor lose them; a move carries the hash entry and
  with it the attribution, folder moves rename the sidecar along. A file changed on disk is rehashed but
  keeps its contributor. The rights seam of #49 became precise: a move out of an album needs `edit` on the
  source *or* the caller must be the contributor of every named image — taking one's own contribution back
  is a move into one's own space, never a delete; a share link (no space) and a guest (photo-free root) are
  told why they cannot. Uploads now invalidate the resource cache. The app half landed the same night (see
  above).

- **2026-09-13 (evening)** — Invitations and guests (#52), app side. Share links and invitations are one
  session mechanism with two kinds: the app base `<context>/s/<token>/` or `<context>/i/<token>/` names
  the kind, the token goes to `?type=auth` once, and the answer decides — a share opens the album, an
  invitation opens a welcome that ends in a sign-in, and a token the server knows as neither falls back to
  the ordinary start without a word. Joining is the pairing with the invitation instead of the secret; the
  device token that comes out is stored like any sign-in together with the plain server URL, the invitation
  token is stored nowhere, and "Open your albums" replaces the browser location so the used-up token leaves
  the URL and the history. On the phone the same link is pasted into the server field, which recognises it
  (server plus invitation) and switches the sign-in to it; a pasted share link is told to open in a browser.
  "Invite…" lives in the server settings for admins and members, the URL shown once. The app learns the
  caller's role from one `?type=auth` per client and hides at a guest's own root what the server would
  refuse by role — creating, moving, placement, and (found by the review probe) sharing — while a guest
  contributing through a link keeps what the target's grant gives.

- **2026-09-13 (later)** — Invitations and guests (#52), server side. An invitation is a single-use token
  that creates a user: `?action=invite` (a member, or the admin alone under `--invite admin`) records it
  hash-only in `<basepath>/.valbum/invitations.json` with role, inviter, note and an expiry that is never
  empty (seven days by default), and answers the token once with `<context>/i/<token>/`. Accepting *is*
  pairing: `?action=pair` with the invitation instead of the secret, a free name, and the device token
  comes out exactly as one earned by the secret; the invitation is used up inside the same lock, so a token
  that races itself creates one user. An invitation bearer is anonymous everywhere but `?type=auth`, which
  names inviter and role so the app can say who asked. A guest's root is
  `<basepath>/.valbum/guests/<name>/`, shaped exactly like a member's space root (links sidecar, own share
  registry), which is why materialisation, tiles, moves and declining work for a guest without a line of
  new code; the one thing that folder is not for — a photo — is refused by role on creation, upload and
  move-target, and no grant can target it. Promotion is therefore one rename of that folder to
  `<basepath>/<name>/` — it becomes the space, links and registry already in place; demotion is not
  offered. Names: a free name is neither a user, a group, nor a top-level folder, and — found by the review
  probe — may not start with `~`, the canonical form of another user's space. Token lookup order is
  devices, shares, invitations; a token in none of them is unknown. The app half landed the same evening
  (see above).

- **2026-09-13** — Share links (#51), app side. A link is a session, not a pairing: the web app recognises
  `<context>/s/<token>/` in its own app base (never in the document location, which is the album being
  looked at after the first navigation), talks to `<context>/data` with the token as bearer, and consults
  the device's stored server and token not at all — neither read nor written. The two starts therefore wait
  for different things: an ordinary start waits for the device (its stored settings), a link session waits
  for the server (the `?type=auth` answer confirming the link); a token the server does not know as a link
  falls back to the ordinary start without comment. Inside a link there is no edit mode, no settings screen
  and nothing that grants, moves or unlinks; the upload button follows `writeAllowed`. A 410 shows the
  server's own sentence and nothing else; a 404 inside a link is always "outside the link", with one way
  back to its root. "Share link…" is a dialog beside "Share with…", offered under the same rights rule,
  listing links with inherited ones marked, creating with label, expiry, privacy ceiling, rating floor and
  rights, showing the URL exactly once. Found in the browser check, not in the tests: the router gate had
  waited for the settings in every session, so a real link never left the splash — the tests had handed the
  app already-loaded settings. Nothing is cached on disk in a link session.

- **2026-09-12 (night)** — Share links (#51), server side. A share link is a grant whose subject is a
  token: `?action=share` records the link in `<basepath>/.valbum/shares.json` (hash only, with label,
  expiry, privacy and rating limits) and its grant in one step, so the one rights method answers for links
  too, and the grant API refuses `token:` subjects outright — a link comes and goes only through
  `?action=share` / `?action=unshare`, so the two stores can never disagree. A link caller's `/` is the
  shared folder itself and nothing outside it exists (404), it reads even in a migrated library, writes
  only what `contribute` allows, sees `min(members, maxPrivacy)` and nothing rated below `minRating`
  (the rating joined the privacy filter), and is answered 410 with a spoken reason on every endpoint once
  the link expired or was withdrawn. The web app is served under `<context>/s/<token>/` by the same
  static handler, which never looks at the token. The app half landed the next day (see above).

- **2026-09-12 (later)** — Link entries (#50), server side. A shared album appears in the recipient's own
  tree as a link: a record in the folder's `.links.json` sidecar naming the target in the owner's
  coordinates. The resolver walks a request path segment by segment and follows a link to its target, so
  the URL is the viewer's path while every rights, clearance and privacy question is asked about the
  target — access is checked against the grant on the target, never against the link. Links are
  materialised when a member lists their own root, filed by the root's placement rule, and remembered in
  the space's `.valbum/share-registry.json` (named so because #51 claims `shares.json`); a removed link
  is recorded as declined and does not come back until a management screen (#55) clears it. A real
  folder always wins a name back from a link; the disk is asked first. The owner's library is
  byte-identical before and after anyone links, browses, moves or unlinks. The app half landed the same
  day: a link tile carries a badge and "from <owner>", "Remove from my albums" declines a share after a
  confirmation, and a canonical `~owner/…` URL opened by a signed-in member is rewritten to the member's
  own link when the root listing knows one, so a copied URL lands in the viewer's tree. The album menu
  through a link says "Shared with you" without the owner's name: the owner is named on the tile, since a
  reloaded or bookmarked route has no parent tile to ask.

- **2026-09-12** — Groups and grants (#49), server side. Sharing is one mechanism, the grant
  `{owner, path, subject, rights}` in `.valbum/grants.json`, with named groups in `.valbum/groups.json`;
  `AuthService.rights(caller, path)` is the one method every endpoint asks. Three decisions beyond the
  issue: (1) the admin manages users and grants but holds no rights in anybody else's space — a role
  that could read every album would make the privacy levels of #46 a decoration; (2) whose space a path
  lies in is the deepest space folder containing it, so in a library that was never migrated the
  owner's grant on her base folder never covers the member spaces inside it, while her own direct access
  to them stays as it was until `--migrate-to-user` closes it; (3) every path the server answers is
  spelled in the coordinates of the request — a request through the canonical form `~<owner>/…` is
  answered with `~<owner>/…` — so a recipient's app never navigates into its own space by mistake.
  `download` gates the original bytes; since the app's viewer opens the original, a `view`-only grant
  shows thumbnails only until a preview rendition exists (Phase 4). The app half landed the same day:
  the app offers only what the answered rights allow (no edit mode, upload or folder actions without
  the right, a "Shared by <owner> — you may …" line instead), "Share with…" on albums and folders with
  the users, groups and "everybody" of the server and inline group creation, the server's reason in
  place of a refused original, and the offline cache keyed by the signed-in user. The admin shares
  other users' spaces through the management screens of #55, not from the tile.

- **2026-09-06 (night)** — Phase 3 sharing, first four issues: users and spaces (#45), privacy
  enforced on the server (#46), moving by rename with duplicates set aside in `.valbum/duplicates/`
  rather than removed (#47, overriding the issue's "removes the source" to keep "the server never
  deletes an original"), and album dates with placement rules (#48, server side). Two dates travel on
  the wire — the explicit `date` that is stored and the derived `effectiveDate` that never is — so a
  round trip can never freeze a derived date into a sidecar. Year folders are `YYYY`, month folders
  `YYYY/YYYY-MM`. Listings are ordered newest first from now on. The app half of #48 landed
  2026-09-12: the album properties carry the date (saying when it is derived from the folder name or
  the photos), the folder properties set the rule, "Apply rule" files what is already there, and
  "Create album" follows the `CreateResult` to where the album landed.

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
