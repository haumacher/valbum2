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
  albums with titles and dates derived from folder names and image metadata.
- **Originals are never touched.** Everything you change in VAlbum — titles, captions, ratings,
  rotation, grouping near-duplicate shots, section headings — is stored in an `index.json` sidecar
  file next to your photos. No file of yours is ever modified, moved or deleted.
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
| `--pairing-secret <secret>` | The secret a device presents to be paired; a random one is printed at start-up if none is given | generated |
| `--migrate-to-user <name>` | One-time: move the albums at the base folder into a folder `<name>` and make it the library owner's space (see below); the server does not start afterwards | none |

### Signing in a device

With `--auth writes` (the default) the server serves every read but refuses an anonymous change or
upload with `401` and a message the app shows. To let a device change something, open the app's
server settings and sign in: enter a user name, the pairing secret the server printed at start-up
("Pairing secret: ...") and a device name, then press "Sign in". The server issues a token for
this device, the app stores it beside the server URL and sends it on every request from then on;
the settings show who the device is signed in as (user, role, device, space). "Sign out" forgets
the token. `--auth all` refuses anonymous reads as well; `--auth off` is the old behaviour, open to
everyone who can reach the server.

### Users and spaces

A paired device belongs to a user. The pairing secret signs in the **library owner** (the admin);
the first sign-in that gives a user name names the owner, later sign-ins with the secret use that
name or none. Users, their role and their devices are kept in `<basepath>/.valbum/users.json`,
which holds a hash of every issued token, never the token itself; a `devices.json` written by an
older server is taken over on first start and kept as `devices.json.migrated`. Nothing else is ever
written outside the `index.json` sidecars.

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

### Privacy levels

Every image has a privacy level, set on its tile in the app: **public** (0), **members** (1) or
**private** (2). The server enforces it on the way out: a listing omits what the caller may not
see, and the image, thumbnail and preview endpoints refuse such an image with a message (401 for an
anonymous caller, 403 for a signed-in one). The owner of a space sees everything in it; an
anonymous caller in `--auth writes` mode sees only public images, so a single-user library on a home
network keeps working minus its restricted photos; `--auth off` shows everything to everyone. A
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

On the web the app talks to the server it was loaded from. Other platforms currently use
`http://localhost:9090/valbum/data` (the demo server); a settings screen is planned.

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
danach http://localhost:8080/ im Browser öffnen. Optionen: `--port`, `--contextpath`, `--webroot`, `--auth`, `--pairing-secret`.

Standardmäßig lehnt der Server anonyme Änderungen ab (`--auth writes`). Beim Start gibt er ein
Kopplungsgeheimnis aus; damit koppelst Du in den Server-Einstellungen der App dieses Gerät, das
danach ein eigenes Token mitschickt.
