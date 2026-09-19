# VAlbum2 — Virtual Photo Album

*The friendly home for all your digital memories.*

VAlbum lets you keep your photos and videos on hardware you own and browse them from any device,
without handing them to a cloud provider. A small Java server reads your album folders and serves
them; one Flutter app shows them in the browser, on your phone, or on the desktop. Put the server on
a [Raspberry Pi](https://www.raspberrypi.org/) behind your internet connection and you have your own
photo cloud for a one-off investment.

*Eine deutsche Zusammenfassung steht am Ende dieser Seite.*

## How it works

- **Your folders are your albums.** One folder holds all albums; each album is a folder with photos
  and videos; nest them any way you like. The server reads that tree and presents it as listings and
  albums with titles and dates derived from folder names and image metadata, newest first.
- **Originals are never touched.** Everything you change in VAlbum — titles, captions, ratings,
  privacy levels, rotation, grouping near-duplicate shots, section headings — is stored in an `index.json` sidecar
  file next to your photos. No file of yours is ever modified or deleted; the only thing the server
  does to a photo is rename it when you move it to another album, when a placement rule files an album
  into its year folder, or when you migrate the library into your space; and a photo that a move finds already present at its target is set aside in
  `.valbum/duplicates/`, never removed.
- **One server, one app.** The server (`image-server/`) is a JSON API plus static hosting for the web
  build of the app; the app (`valbum_ui/`) is written in Flutter and runs on the web, Android, iOS,
  Linux, Windows and macOS.
- **Albums that look like albums.** The row layout stitches landscape shots into rows and pairs
  portraits with stacked landscapes so every row fills the page width — the heart of VAlbum since
  its first version.

Where the project is heading is written down in [ROADMAP.md](ROADMAP.md).

## Building

You need Git, a JDK 21 ([Temurin](https://adoptium.net/temurin/releases/?version=21)),
[Apache Maven](https://maven.apache.org/) 3.6 or newer, and — for the app —
the [Flutter SDK](https://docs.flutter.dev/get-started/install) (stable channel).

Build the app for the web first, so the server can bundle it:

```
cd valbum_ui
flutter pub get
flutter build web
cd ..
```

Then build the server from the repository root. If `valbum_ui/build/web` exists it is packed into
the jar; if not, you get an API-only server.

```
mvn clean install
```

The result is `image-server/target/image-server-jar-with-dependencies.jar`, which contains everything
needed to run.

## Running

```
java -jar image-server-jar-with-dependencies.jar --basepath /path/to/your/photos
```

Options:

| Option | Meaning | Default |
|---|---|---|
| `--basepath <dir>` | The folder containing your albums | current directory |
| `--port <n>` | HTTP port | `8080` |
| `--contextpath <name>` | First path segment of the URL, e.g. `photos` → `http://host:8080/photos/` | none |
| `--webroot <dir>` | Serve the web app from a directory instead of the bundled copy (development) | bundled |
| `--auth off\|writes\|all` | What requires a paired device: nothing, changes and uploads, or every request | `writes` |
| `--admin-code <code>` | The sign-in code the server prints for the administrator of a space nobody signed into yet, instead of a random one; eight characters of `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, a dash between the groups allowed | a fresh one at every start |
| `--spaces auto\|single\|multi` | Whether this server hosts one space or several (issue #82); `auto` decides from the folder tree: multi as soon as one folder below the base folder carries `.valbum/space.json` | `auto` |
| `--migrate-to-spaces` | One-time: turn a library migrated per user into a multi-space server, every user folder a space with that user as its admin, and report what could not be carried; the server does not start afterwards | none |
| `--preview-threads <n>` | How many thumbnails are generated at the same time; serving an already cached thumbnail is never throttled (the system property `valbum.previewThreads` does the same) | number of processors |
| `--migrate-to-user <name>` | One-time: move the albums at the base folder into a folder `<name>` and make it the library owner's space (see below); the server does not start afterwards | none |

### Signing in a device

With `--auth writes` (the default) the server serves every read but refuses an anonymous change or
upload with `401` and a message the app shows. There is one way in, and it is always the same
thing: a **code**. A code works once, lives ten minutes and signs one device in as one user.

At start-up the server prints a code for the administrator of every space that has no signed-in
device yet:

```
This library: sign the administrator in with the code ABCD-EFGH (valid 10 minutes, once; restart the server for a new one).
```

Open the app's server settings, enter that code and a device name and press "Sign in". The
administrator of a fresh space has no name yet, so the app asks for one and signs in again with
it: that name is what the users list shows, what an uploaded photo is attributed to and what a
permission change addresses. The server issues a token for this device, the app stores it beside
the server URL and sends it on every request from then on; the settings show who the device is
signed in as (user, role, device, space). "Sign out" forgets the token.

Once somebody signed in, nothing is printed any more — the printed code is a bootstrap, never a
standing master key. A further device comes from a code of a device you already hold (below), and
somebody who lost every device they had gets a **recovery code** from an administrator. If the
administrator themselves has no device left, restart the server: a fresh code is printed, which
only somebody with the machine can do.

`--auth all` refuses anonymous reads as well; `--auth off` is the old behaviour, open to everyone
who can reach the server.

### Adding a further device of your own

A user who wants a second device must not use an invitation (that would create another user). On
a device you are already signed in on, open "My
devices" in the server settings and press "Add a device…": the server issues a short code
(`XXXX-XXXX`) that lives ten minutes and works once. Type it into the "Device code" field of the
sign-in section on the new device, together with a device name, and press "Sign in": the new
device is signed in as you and appears in your device list at once, where it can be signed out
again. The code is deliberately not a link — it is never sent anywhere and cannot be forwarded —
and it dies with the device that issued it: signing that device out withdraws every code it handed
out that was not used yet. Never give a device code to anybody else; it signs them in as you.

If you lost every device you had — a cleared browser, an app reinstalled — an administrator makes
the same code for you: in the users list, "Recovery code" beside your name. It is the same
single-use code with the same ten minutes; it signs a device in as *you*, and it dies with the
administrator's device that made it.

### Users and spaces

A paired device belongs to a user. A space has its administrator (the **library owner**) from the
moment it exists — nameless and without devices until the seat code is redeemed, which is what
gives them their name. Users, their role and their devices are kept in `<basepath>/.valbum/users.json`,
which holds a hash of every issued token, never the token itself; a `devices.json` written by an
older server is taken over on first start and kept as `devices.json.migrated`. Besides the
`index.json` sidecars and the per-folder `.hashes.json` of the upload, `.valbum/` is the only place
the server writes.

Every user owns one top-level folder under the base folder, their *space*, and sees the library
rooted there. The owner's space is the base folder itself until you migrate the library once,
explicitly, with the server stopped:

```
java -jar image-server/target/image-server-jar-with-dependencies.jar --basepath /path/to/photos --migrate-to-user <name>
```

This moves every entry of the base folder except `.valbum` and `.upload` into `/path/to/photos/<name>/`
by a plain rename (sidecars and preview caches ride along), records the folder as the owner's space
and exits. It is refused, with nothing moved, if the owner already has a space, the target folder
is not empty, or the name is not a valid folder name. Once the library is migrated, anonymous
callers are refused in every mode but `off`, because the base folder then holds only user spaces.

### Moving images and albums

In edit mode, a selection of images can be moved to another album, and an album or folder to
another folder: `POST <data>/<source folder>/?action=move` with the target folder and the names to
move. A move is a rename on the same file system — the pixels are never touched — and the image's
rating, privacy level, comment and orientation move with it into the target album's `index.json`,
as does its entry in `.hashes.json`, so the upload de-duplication keeps working. Moving a group's
representative moves the whole group. A name already taken at the target is resolved as an upload
would (the moved file gets a free name); a photo the target already holds with identical content is
set aside in `.valbum/duplicates/`, never deleted. Every name gets an outcome: its new name (a path
below the target when the target files by year, see below), or the reason it was not moved. Nothing
is ever overwritten.

### Album dates and placement rules

An album has a date: the one its author sets in the album properties (stored in `index.json`), else
the leading date of its folder name (`2026-09-06 Name`, `2026-09 Name`, `2026 Name`), else the
earliest photo date. The server reports this *effective date* on every album and listing tile and
never stores a derived one; listings are ordered newest first, undated folders after them by name.
A folder can carry a placement rule — by year or by year and month — set in its properties. The rule
places whatever lands in the folder: an album created there is filed into `YYYY/` (or
`YYYY/YYYY-MM/`), an album moved there likewise, and `POST <folder>/?action=place` files what is
already there, once, explicitly. Year folders are ordinary folders. The rule places, it does not
police: what you drag elsewhere by hand stays there.

### Who may do what

Everybody in a space holds **one permission for the whole space** — there are no per-album
permissions:

| | may look and download | may add photos | may change albums | may manage the space |
|---|---|---|---|---|
| `admin` | yes | yes | yes | yes |
| `edit` | yes | yes | yes | no |
| `contribute` | yes | yes | no | no |
| `view` | yes | no | no | no |

Beside the role, every user has a **clearance** — `public`, `nonPrivate` or `all` — which says how
far up the privacy levels below they may look, and a **share flag** which says whether they may
hand out share links. An administrator holds everything: full clearance, the share flag, and the
users of the space.

Only an administrator invites people into a space (`?action=invite`, the invitation carries the
role, clearance and share flag the invitee gets), changes what somebody may do
(`?action=set-permission`) or removes a user with their devices (`?action=remove-user`). The last
administrator of a space can be neither demoted nor removed.

A library written by an older build calls its users `member` and `guest`; they are read as `edit`
(clearance `all`, may share) and `view` (clearance `nonPrivate`), and the stored file is not
rewritten. The sharing of that build — grants, groups, albums linked into somebody else's tree and
guest accounts — is gone; its endpoints answer `410 Gone` with a message naming what does the job
now, for one release.

### Share links

Somebody with the share flag hands out a link to one album or folder: press "Share" on it, give the
link a label, say how long it lives, how far up the privacy levels it shows, which ratings it
includes, and whether whoever opens it may also add photos. The server answers a URL of the form
`<host>/<context>/s/<token>/` (under the space on a multi-space server) and shows the token exactly
once — it is the link, so treat it like a password.

A link is its own permission: it shows what it was cut to at the moment it was made, and that does
not change afterwards, whatever happens to the permission of the person who made it. A link can
never show more than its maker may see (the server refuses such a link rather than trimming it
silently), never shows a private image, and never allows changing what is there. Whoever opens it
sees that album as the whole site; there is nothing above it. Contributions through a link are
recorded as coming from the link, not from a person.

You see and withdraw the links you handed out yourself; an administrator sees and withdraws every
link of the space. Removing a user withdraws every link they handed out, and the answer says how
many. An expired or withdrawn link answers `410 Gone` with a sentence saying which of the two it
is.

### Privacy levels

Every image has a privacy level, set on its tile in the app: **public** (0), **members** (1) or
**private** (2). The server enforces it on the way out: a listing omits what the caller may not
see, and the image, thumbnail and preview endpoints refuse such an image with a message (401 for an
anonymous caller, 403 for a signed-in one). How far up the scale somebody may look is their
**clearance** (see below); an administrator sees everything in their space; an anonymous caller in
`--auth writes` mode sees only public images, so a single-user library on a home network keeps
working minus its restricted photos; `--auth off` shows everything to everyone. A
group whose representative is hidden is shown by its best visible member, and an album whose cover
is hidden gets its first visible image as cover. `?viewAs=public` or `?viewAs=members` on a request
lowers the caller's own clearance for that request (it can never raise it) — the app's "view as"
switch uses it. Nothing is written: the sidecars keep every image at its level.

Open `http://localhost:8080/` (or your context path) in a browser. The JSON API is available under
`/data/`, for example `http://localhost:8080/data/?type=json`.

### Demo server

After `mvn install`, a demo server with a small sample album starts with:

```
mvn exec:java@test-server -pl :image-server
```

It listens on http://localhost:9090/valbum/ and serves the bundled web app if you built it.

### Running the app during development

```
cd valbum_ui
flutter run -d chrome        # or -d linux, an Android device, ...
```

On the web the app talks to the server it was loaded from. Other platforms take the server
from the app's settings screen (the default is the demo server, `http://localhost:9090/valbum/data`).

## Install on a Raspberry Pi or another Debian/Ubuntu machine

Released versions are published as Debian packages from an APT repository, so the
server installs and updates like any other package:

```
sudo install -d /usr/share/keyrings
curl -fsSL https://haumacher.github.io/valbum2/valbum.gpg | sudo tee /usr/share/keyrings/valbum.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/valbum.gpg] https://haumacher.github.io/valbum2 stable main" | sudo tee /etc/apt/sources.list.d/valbum.list
sudo apt update && sudo apt install valbum
```

Packages are published for `arm64`, `amd64` and `armhf`; APT picks the right one.
The server needs a **Java 21 runtime**, which Debian 13 (trixie), Raspberry Pi OS
trixie and Ubuntu 24.04 have; Debian 12 (bookworm) ships only Java 17 and is not
enough.

The package *recommends* the X11/xcb and ALSA libraries the bundled FFmpeg links
for video renditions (the streamable MP4 and the teaser made beside a video).
`apt install` pulls recommendations in by default; installing with
`--no-install-recommends` leaves them out, and the server then says
`Video renditions: NOT available - ...` in its journal while albums, photos,
thumbnails and video poster frames keep working. They can be added at any time:

```
sudo apt install libxcb1 libxcb-shm0 libxcb-shape0 libxcb-xfixes0 libasound2t64
```

(`libasound2` instead of `libasound2t64` before Debian trixie and Ubuntu 24.04.)

The package installs the jar as `/usr/share/valbum/valbum.jar` with the wrapper
`/usr/bin/valbum-server`, and enables and starts the systemd service `valbum`.

### Configuration

Everything is set in `/etc/default/valbum`:

| Variable | Meaning | Default |
|---|---|---|
| `VALBUM_BASEPATH` | The folder containing your albums | `/var/lib/valbum` |
| `VALBUM_PORT` | HTTP port | `8080` |
| `VALBUM_CONTEXTPATH` | First path segment of the URL | none |
| `VALBUM_AUTH` | `off`, `writes` or `all` | `writes` |
| `VALBUM_OPTS` | Further server options | none |
| `JAVA_OPTS` / `JAVA_HOME` | JVM options and the JVM to use | system default |

Point it at the disk holding your photos and restart:

```
sudo nano /etc/default/valbum       # VALBUM_BASEPATH=/mnt/photos
sudo systemctl restart valbum
```

The library folder is never deleted by the package, not even when it is purged.

### Signing in the first device

At every start, while nobody is signed in, the server prints a sign-in code for the
administrator to the journal:

```
journalctl -u valbum | grep "with the code"
```

Then open `http://<your-pi>:8080/` in a browser — or point the app's server setting
at that address — and sign in with that code within ten minutes; the app asks for the
name the library owner should be known by. Missed the ten minutes? `sudo systemctl
restart valbum` prints a new one. A fixed code instead of a fresh one at every start:
`VALBUM_OPTS="--admin-code ABCD-EFGH"` in `/etc/default/valbum`.

To give the owner a space of their own (see *Users and spaces* above), stop the
service and migrate once:

```
sudo systemctl stop valbum
sudo -u valbum valbum-server --migrate-to-user <name>
sudo systemctl start valbum
```

### Behind a reverse proxy

To reach the server from the internet under a name of your own with HTTPS, put a reverse proxy
in front of it and forward `https://home.example.org/valbum/` to `http://<pi>:8080/valbum/`
(the context path has to be the same on both sides, see `VALBUM_CONTEXTPATH`). The server never
spells an absolute URL to itself: its redirects carry only the path, so no rewriting of
`Location` headers is needed, and when the proxy sends the `X-Forwarded-Proto` and
`X-Forwarded-Host` headers (or `Forwarded`), requests report the public surface. Two things the
proxy must allow: request bodies large enough for an upload of many photos at once, and enough
time for it.

nginx:

```
location /valbum/ {
    proxy_pass         http://192.168.178.20:8080/valbum/;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_set_header   X-Forwarded-Host  $host;
    proxy_set_header   X-Forwarded-For   $remote_addr;
    client_max_body_size 0;          # uploads: no limit (or e.g. 2g)
    proxy_read_timeout   600s;
    proxy_request_buffering off;     # stream the upload through instead of spooling it
}
```

Apache httpd (`mod_proxy_http`):

```
ProxyPreserveHost On
ProxyPass        /valbum/ http://192.168.178.20:8080/valbum/
RequestHeader set X-Forwarded-Proto "https"
LimitRequestBody 0
ProxyTimeout     600
```

### Updating

```
sudo apt update && sudo apt upgrade
```

### The Android app

Every release also carries a signed APK, `valbum-<version>.apk`, on the
[Releases page](https://github.com/haumacher/valbum2/releases). It is not in Google
Play, so Android asks you to allow installing apps from the browser or file manager
you download it with. Point it at your server in its settings and sign in with a code —
the one the server printed at start-up, one from a device you already hold, or a
recovery code from your administrator.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup and how changes are made.

---

## Zusammenfassung auf Deutsch

Mit VAlbum verwaltest Du Deine digitalen Photos und Videos ohne Cloud-Dienstleister. Ein kleiner
Java-Server liest Deine Album-Ordner und stellt sie bereit; eine Flutter-App zeigt sie im Browser,
auf dem Handy oder auf dem Desktop. Deine Photos organisierst Du wie bisher: ein Ordner mit allen
Alben, jedes Album ein Ordner mit Photos und Videos. VAlbum fasst Deine Dateien nie an — alle
Änderungen (Titel, Beschriftungen, Bewertungen, Drehungen, Gruppierungen) landen in einer
`index.json`-Datei neben Deinen Photos.

Bauen: `flutter build web` in `valbum_ui/`, dann `mvn clean install` im Hauptverzeichnis (JDK 21 und
Maven nötig). Starten: `java -jar image-server-jar-with-dependencies.jar --basepath /pfad/zu/den/photos`,
danach http://localhost:8080/ im Browser öffnen. Optionen: `--port`, `--contextpath`, `--webroot`, `--auth`, `--admin-code`, `--preview-threads`.

Standardmäßig lehnt der Server anonyme Änderungen ab (`--auth writes`). Angemeldet wird immer mit
einem **Code**: Er gilt zehn Minuten, funktioniert einmal und meldet ein Gerät als einen Benutzer
an. Solange sich in einem Raum noch niemand angemeldet hat, gibt der Server beim Start einen Code
für dessen Administrator aus; Du gibst ihn in den Server-Einstellungen der App ein und wählst
dabei den Namen, unter dem Du in diesem Raum bekannt sein willst. Danach wird nichts mehr
ausgegeben — ein weiteres eigenes Gerät meldest Du mit einem Code von einem Gerät an, das Du schon
hast, und wer alle Geräte verloren hat, bekommt vom Administrator einen Wiederherstellungs-Code
(oder, wenn es den Administrator selbst trifft, hilft ein Neustart des Servers).

Auf einem Raspberry Pi (oder einem anderen Debian/Ubuntu-Rechner) installierst Du den
Server als Paket aus dem APT-Repository:

```
sudo install -d /usr/share/keyrings
curl -fsSL https://haumacher.github.io/valbum2/valbum.gpg | sudo tee /usr/share/keyrings/valbum.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/valbum.gpg] https://haumacher.github.io/valbum2 stable main" | sudo tee /etc/apt/sources.list.d/valbum.list
sudo apt update && sudo apt install valbum
```

Es gibt Pakete für `arm64`, `amd64` und `armhf`; eine Java-21-Laufzeitumgebung wird
benötigt (Debian 13 bzw. Raspberry Pi OS trixie, Ubuntu 24.04 — Debian 12 reicht nicht).
Das Paket empfiehlt (`Recommends`) die X11/xcb- und ALSA-Bibliotheken, die das
mitgelieferte FFmpeg für Video-Konvertierungen braucht; `apt install` installiert sie
standardmäßig mit. Bei `--no-install-recommends` fehlen sie, im Journal steht dann
`Video renditions: NOT available - ...`, alles andere (Alben, Fotos, Vorschaubilder,
Standbilder von Videos) funktioniert weiter. Nachrüsten mit
`sudo apt install libxcb1 libxcb-shm0 libxcb-shape0 libxcb-xfixes0 libasound2t64`
(vor trixie bzw. 24.04 heißt das Paket `libasound2`).
Eingestellt wird alles in `/etc/default/valbum` (vor allem `VALBUM_BASEPATH`, danach
`sudo systemctl restart valbum`); der Anmelde-Code für den Administrator steht bei
jedem Start im Journal (`journalctl -u valbum`), solange sich noch niemand angemeldet
hat. Aktualisiert wird mit `sudo apt upgrade`. Die
Android-App liegt als signierte APK-Datei bei jedem Release auf der
[Releases-Seite](https://github.com/haumacher/valbum2/releases).
