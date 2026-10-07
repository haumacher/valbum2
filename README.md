# VAlbum2 — Virtual Photo Album

*The friendly home for all your digital memories.*

VAlbum keeps your photos and videos on hardware you own — a Raspberry Pi, a NAS, any Linux
machine — and shows them on every device, without a cloud provider. A small server reads your
album folders; one app shows them in the browser, on Android and on the desktop.

*Eine deutsche Zusammenfassung steht am Ende dieser Seite.*

## What VAlbum is

- **Albums from your folders.** Every folder with photos and videos is an album. Titles, captions,
  ratings, headings and order are added in the app.
- **Camera-roll sync.** The Android app uploads new photos into an *inbox* album, where you sort
  them into albums by day.
- **Share links.** Hand out a link to one album or folder, with an expiry and the photos it may show.
- **Invitations.** Family and friends join with a link and get the permission you chose.
- **Privacy levels.** Each photo is public, for members, or private.
- **Faces** (off unless you switch them on). The server finds faces, groups them and recognises the
  people you named.
- **Spaces.** One server can host several separate libraries, each with its own users.
- **One app** for the web, Android and the desktop.

Where the project is heading is in [ROADMAP.md](ROADMAP.md).

## How it works

- **Your folders are your albums.** One folder holds everything; nest albums as you like. Dates
  come from the album, its folder name (`2026-09-06 Trip`) or its earliest photo; newest first.
- **Photos are never modified.** What you change in the app is written to small files beside
  your photos (`index.json`, `.hashes.json`) and to caches (`.vacache`).
- **Nothing is deleted, with two exceptions:**
  - an administrator **purges** the photos rated "Trash" on an album's trash page;
  - deleting an album that holds no picture removes the empty folder.
- **Everything else is a rename.** A deleted album goes to `.valbum/trash/`, a duplicate a move
  finds to `.valbum/duplicates/`, a replaced upload to `.valbum/replaced/` — all
  inside the library, where you can take them back by hand.
- **Albums that look like albums.** Rows fill the page width, landscape and portrait shots mixed.

## Installing

Pick one: a NAS with Docker, a Raspberry Pi or other Debian/Ubuntu machine, and on the phone the
Android app.

### Docker (Synology NAS)

Every release is a container image for `amd64` and `arm64`: **`hauix/valbum`** on Docker Hub,
identical `ghcr.io/haumacher/valbum`, tags `:<version>` and `:latest`.

Synology's *Container Manager* runs on every x86-64 model and, since DSM 7.2, on the ARM models
DS124, DS223, DS223j and DS423. Older ARM models (DS218, DS220j, …) cannot run it. Synology's
`@eaDir` and `#recycle` folders are never shown as albums.

What the container needs ([`compose.yaml`](compose.yaml) has it all):

- **The photo folder, read-write, at `/photos`.** VAlbum writes its files beside the photos. Without
  a mount the container refuses to start, so nothing is lost with the next update.
- **`PUID` and `PGID`**: the user and group the server runs as and who owns every file it writes.
  Take the owner of the photo share; `id <user>` in an SSH session shows them, on Synology typically
  `1026` and `100`. That user needs *Read/Write* on the share. Without them the owner of `/photos` is
  taken; a folder owned by root is refused. `UMASK=002` makes the files group-writable.
  (Or run the container as that user with `user: "1026:100"`.)
- **Port 8080**, the album at `http://<nas>:8080/valbum/`.
- **Memory.** The heap is `VALBUM_HEAP_PERCENT` (50 %) of what the container may use. On a
  **DS223j** (1 GB) set `VALBUM_OPTS: "--preview-threads 1"` and leave faces off; they need the
  most memory and time.
- A health check asks the app every 30 seconds; Container Manager then shows *healthy*.

#### Setting it up in Container Manager

1. **Package Center**: install *Container Manager*.
2. **File Station**: create a folder for the project, e.g. `docker/valbum`. Note where the photos
   are, e.g. `/volume1/photo`.
3. **Container Manager → Project → Create**: name `valbum`, path the folder from step 2, source
   *Create docker-compose.yaml*, and paste:

   ```yaml
   services:
     valbum:
       image: hauix/valbum:latest
       container_name: valbum
       restart: unless-stopped
       ports:
         - "8080:8080"
       volumes:
         - /volume1/photo:/photos
       environment:
         PUID: "1026"
         PGID: "100"
         TZ: Europe/Berlin
         VALBUM_AUTH: writes
         VALBUM_OPTS: ""
   ```

   Adjust the volume, `PUID`/`PGID`, and the left `8080` if that port is taken. Skip the web-portal
   page and finish; the image is pulled and started.
   The image runs in UTC; set `TZ` or the space's `timeZone`.
4. **Sign in**: see [Signing in the first device](#signing-in-the-first-device).

### Raspberry Pi, Debian and Ubuntu

Released versions come from an APT repository:

```
sudo install -d /usr/share/keyrings
curl -fsSL https://haumacher.github.io/valbum2/valbum.gpg | sudo tee /usr/share/keyrings/valbum.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/valbum.gpg] https://haumacher.github.io/valbum2 stable main" | sudo tee /etc/apt/sources.list.d/valbum.list
sudo apt update && sudo apt install valbum
```

- Packages for `arm64`, `amd64` and `armhf`; APT picks the right one.
- It needs **Java 21**: Debian 13 (trixie), Raspberry Pi OS trixie, Ubuntu 24.04. Debian 12 is not
  enough.
- A plain `apt install` also brings the libraries videos need. With `--no-install-recommends`
  videos get no streamable copy (`Video renditions: NOT available` in the log); photos, thumbnails
  and video poster frames still work. Add them later with
  `sudo apt install libxcb1 libxcb-shm0 libxcb-shape0 libxcb-xfixes0 libasound2t64`
  (`libasound2` before trixie and Ubuntu 24.04).
- The service `valbum` starts at once and serves `http://<machine>:8080/`, the library in
  `/var/lib/valbum`. Point it at your photos under [Configuration](#configuration).

### The Android app

Every release carries a signed `valbum-<version>.apk` on the
[Releases page](https://github.com/haumacher/valbum2/releases). It is not in Google Play yet, so
Android asks you to allow installing from your browser. The first screen asks where your album is:
type the server address or paste an invitation link, then sign in with a code.

From 2.9 on the app has a new id, so a 2.8 or older app does not update to it. In the old app,
create a sign-in code (*Server… → My devices → Add a device…*, valid 10 minutes) or a backup code,
install 2.9 and sign in with it. The old app can go before or after.

### Configuration

The `.deb` reads `/etc/default/valbum`; Docker takes the same variables from `environment:`.

| Variable | Meaning | `.deb` | Docker |
|---|---|---|---|
| `VALBUM_BASEPATH` | The library folder | `/var/lib/valbum` | `/photos` (mount it) |
| `VALBUM_PORT` | HTTP port | `8080` | `8080` |
| `VALBUM_CONTEXTPATH` | First path segment of the URL, empty for the root | empty | `valbum` |
| `VALBUM_AUTH` | Sign-in needed for: `off` nothing, `writes` changes and uploads, `all` everything | `writes` | `writes` |
| `VALBUM_SPACES` | `auto`, `single` or `multi`, see [Users and spaces](#users-and-spaces) | `auto` | `auto` |
| `VALBUM_OPTS` | Further [server options](#server-options), e.g. `--preview-threads 1` | | |
| `JAVA_OPTS` | JVM options, e.g. `-Xmx512m` | | |
| `JAVA_HOME` | The Java to use | from `PATH` | |
| `PUID` / `PGID` | User and group the server runs as | | owner of `/photos` |
| `TZ` | Time zone for photos without one, where the space names no `timeZone` | system | UTC |
| `VALBUM_HEAP_PERCENT` | Heap as a share of the memory; an `-Xmx` wins | | `50` |
| `VALBUM_PUBLIC_URL` | The album's address from outside, e.g. `https://home.example.org/valbum` | from each request | from each request |
| `VALBUM_SMTP_*` | The mail account for e-mail codes, see [Mail](#mail) | none | none |
| `VALBUM_OIDC_*` | "Continue with Google", see [Sign-in with Google](#sign-in-with-google) | none | none |

On the `.deb`, point it at your photos and restart:

```
sudo nano /etc/default/valbum       # VALBUM_BASEPATH=/mnt/photos
sudo systemctl restart valbum
```

The user `valbum` must be able to read and write there. Removing the package never deletes the
library.

### Signing in the first device

Every space has an administrator from the start, without a name or a device. At every start the
server prints a sign-in code for them into its log, until they have signed in:

```
This library: sign the administrator in with the code ABCD-EFGH (valid 10 minutes, once; restart the server for a new one).
```

| | Where the code is | New code |
|---|---|---|
| `.deb` | `journalctl -u valbum \| grep "with the code"` | `sudo systemctl restart valbum` |
| Docker | Container Manager → Container → `valbum` → Details → Log, or `docker compose logs valbum` | restart the container |

Open the album in a browser (or the app), enter the code and a device name, and choose the name you
will be known by. `--admin-code ABCD-EFGH` in `VALBUM_OPTS` fixes the code instead of a fresh one.

### Behind a reverse proxy

For HTTPS under your own name, forward `https://home.example.org/valbum/` to
`http://127.0.0.1:8080/valbum/` (or the server's address, when the proxy runs elsewhere). The context
path must be the same on both sides: Docker has `valbum`; on the `.deb` set
`VALBUM_CONTEXTPATH=valbum`. No `Location` rewriting is needed; send `X-Forwarded-Proto` and
`X-Forwarded-Host` (or `Forwarded`). Allow large uploads and enough time for them.

nginx, inside the `server { listen 443 ssl; … }` block:

```
location /valbum/ {
    proxy_pass         http://127.0.0.1:8080/valbum/;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_set_header   X-Forwarded-Host  $host;
    proxy_set_header   X-Forwarded-For   $remote_addr;
    client_max_body_size 0;          # uploads: no limit (or e.g. 2g)
    proxy_read_timeout   600s;
    proxy_request_buffering off;     # stream the upload through instead of spooling it
}
```

Apache needs three modules; without `headers` it refuses the configuration with
*Invalid command 'RequestHeader'*:

```
sudo a2enmod proxy proxy_http headers
```

Then, inside the `<VirtualHost *:443>` block (`mod_proxy` sends `X-Forwarded-Host` by itself):

```
ProxyPreserveHost On
ProxyPass        /valbum/ http://127.0.0.1:8080/valbum/
RequestHeader set X-Forwarded-Proto "https"
LimitRequestBody 0
ProxyTimeout     600
```

and `sudo apachectl configtest && sudo systemctl reload apache2`.

### Mail

The server sends one kind of mail: the 6-digit code with which a visitor of a *personal* share link
confirms an e-mail address. Without a mail account no code is offered, and a personal link "for
anyone with the link" cannot be opened. Use your mail provider's server and account — mail sent
straight from a home connection lands in spam:

```
VALBUM_SMTP_HOST=smtp.example.org
VALBUM_SMTP_USER=album@example.org
VALBUM_SMTP_PASSWORD='secret'
# VALBUM_SMTP_TLS=starttls       # starttls (port 587, default) or tls (port 465)
# VALBUM_SMTP_PORT=587           # only for another port
# VALBUM_SMTP_FROM=…             # only where the user name is no address
```

In `/etc/default/valbum` (readable by root and the group `valbum` only) and `sudo systemctl restart
valbum`; in Docker under `environment:`. The server refuses to start with a setting it cannot use,
naming it, and prints the mail server (never the password) at start-up.

### Sign-in with Google

A visitor of a personal share link may confirm their address with Google instead of a mailed code.

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials) create an OAuth
   client of the type *Web application* with the authorized redirect URI
   `<VALBUM_PUBLIC_URL>/oidc/callback` (the server prints it at start-up).
2. Publish the consent screen (*Audience* → *Publish app*): in "Testing" at most 100 users get in.
   The scopes asked for — `openid`, `email`, `profile` — need no review.
3. Set a fixed public https address and the client:

```
VALBUM_PUBLIC_URL=https://home.example.org/valbum
VALBUM_OIDC_GOOGLE_CLIENT_ID=123456789-abc.apps.googleusercontent.com
VALBUM_OIDC_GOOGLE_CLIENT_SECRET='GOCSPX-…'
```

Without `VALBUM_PUBLIC_URL` nothing is offered, and the log says so. Another OpenID Connect provider
is more variables of its own name: `VALBUM_OIDC_<NAME>_CLIENT_ID`, `_CLIENT_SECRET`,
`_DISCOVERY_URL` (its `…/.well-known/openid-configuration`) and `_LABEL` (the button).

### Passkeys and authenticator apps

A visitor of a personal link may set up a passkey ("Recognise me on my other devices") or an
authenticator app (Google Authenticator, the iOS Passwords app, any TOTP app) under "Sign-in
options…" in the link's menu, and then sign in on another device with it. Neither is the default.
Passkeys need `VALBUM_PUBLIC_URL`: its host is the one a passkey belongs to, so changing the host
later invalidates them (an `http` address works for `localhost` only). An authenticator app needs no
setting; its secrets are stored readably in `<space>/.valbum/contacts.json` — the server computes
the codes from them — so keep that file, and backups of it, as private as `/etc/default/valbum`.

### Updating

- `.deb`: `sudo apt update && sudo apt upgrade`
- Docker: *Container Manager → Project → `valbum` → Action → Build*, or
  `docker compose pull && docker compose up -d` in the project folder.

The library stays where it is.

## Using VAlbum

Most of what follows is under **Server settings…** in the app's menu.

### Devices and the backup code

- **Signing in** is always a **code**: it works once, for ten minutes, and signs one device in as
  one user.
- **Another device of yours**: *My devices → Add a device…* shows a code (also as a QR code). Enter
  it on the new device. Never give it to anybody else — it signs them in as you.
- **Lost every device?** An administrator makes a *Recovery code* beside your name in *Users*. If the
  administrator lost theirs, restarting the server prints a fresh code.
- **Backup code**: *My devices → Create backup code…* gives a 16-character code that never expires
  and works once — your way back after signing out of your last device. Write it down. A new one
  replaces the old one.

### Users and spaces

A **space** is a library of its own: albums, users, share links and sign-in. Nothing crosses from
one space into another.

- **One space** is the default: the library folder itself.
- **Several spaces**: each is a folder below the library folder, created with
  [`valbum-admin create-space`](#administration), reached at `<context>/<space>/`. In that mode the
  albums directly in the library folder are not served; move them into a space first with
  `valbum-admin move-into-space`.
- A space's settings live in `<space>/.valbum/space.json`: `name`, `anonymous` (`public` lets
  visitors see the public photos, `none` shows them nothing), `faces` (`on`/`off`) and `mapUrl` (the
  map a photo's position opens, `{lat}`/`{lon}` in it).
- `timeZone` in `space.json` (e.g. `Europe/Berlin`) is where a photo that states no time zone and no GPS time was taken; without it the server's zone.
- **Users** belong to one space. Everybody but its first administrator **joins by invitation**:
  *Invite…* in *Users*, choose what they may do, send the link. Whoever opens it picks their name
  and signs in. Until then the seat shows as pending and can be withdrawn.

### Who may do what

Every user holds one permission for the whole space — a role, a clearance and the share flag:

| Role | look and download | add photos | change albums | manage users |
|---|---|---|---|---|
| `admin` | yes | yes | yes | yes |
| `edit` | yes | yes | yes | no |
| `contribute` | yes | yes | no | no |
| `view` | yes | no | no | no |

- **Clearance** (`public`, `nonPrivate`, `all`): how far up the privacy levels they may look.
- **Share flag**: whether they may hand out share links.
- The administrator changes these in *Users*. The last administrator can be neither demoted nor
  removed.

### Privacy levels

Each photo is **Public**, **Members** or **Private**, set on its tile. A caller sees only what their
clearance allows; a visitor who is not signed in sees public photos (with `--auth writes` or
`anonymous: public`). *View as* in the album menu shows the album as members or the public see it.

### Share links

*Share link…* on an album or folder: a label, an expiry, the privacy level and ratings it shows,
and whether it may add photos. The link is shown once — treat it like a password.

- It shows what it was made with and never changes afterwards.
- It never shows a private photo, never allows editing, and never shows more than its maker may see.
- Whoever opens it sees that album and nothing above it.
- You see and withdraw your own links; the administrator sees every link of the space.

### Albums

- **Inbox**: *Make this an inbox* in the album properties. Photos are shown by day; tap a day to
  select it and move it into an album. The camera-roll sync creates one named `Inbox`.
- **Moving**: *Move to…* for a selection, *Move album to…* for the album. Nothing is overwritten; a
  photo the target already holds is set aside.
- **Reordering**: in the edit mode, drag a tile by its handle (top right).
- **Dates and filing**: an album's date comes from its properties, its folder name or its earliest
  photo. A folder may file new albums by year or by year and month (*Filing rule*).
- **Trash**: rate a photo *Trash* to hide it. *Show trash* in the album menu lists these photos:
  *Restore* brings one back, an administrator's *Purge…* deletes them from disk.
- **Deleting an album**: *Delete album…* moves it into the space's trash folder (an empty one is
  removed).
- **Downloading**: *Download original* in the photo viewer; a selection in the edit mode downloads
  as one zip (a phone saves the photos one by one).

### Faces

Off unless the space's `space.json` says `"faces": "on"` (`valbum-admin create-space … --faces on`
for a new space). Processing your family's faces is your decision, not a default.

- The server looks at each photo once, in the background, and groups the faces per album.
- *Persons in this album* in the album menu names the groups; *Edit persons* in the viewer names a
  single face or marks one the server missed. A named person is then suggested on other photos.
- Faces are shown to signed-in members only, never through a share link or to the public.
- What describes a face for recognition never leaves the server.

## Administration

`valbum-admin` runs the server's one-time jobs. It stops the server, runs the job as the server's
user with its configuration, and starts the server again (`--no-restart` leaves it stopped).

```
sudo valbum-admin help
sudo valbum-admin help create-space
sudo valbum-admin create-space family --name "The Family"
sudo valbum-admin move-into-space family
sudo valbum-admin replace-originals /path/to/originals --dry-run
```

In Docker, the same inside the running container:

```
docker compose exec valbum valbum-admin create-space family --name "The Family"
```

| Command | What it does |
|---|---|
| `create-space <folder>` | Makes a folder below the library folder a space (`--name`, `--anonymous none\|public`, `--faces on\|off`, `--time-zone <zone>`). The new space's sign-in code appears in the log when the server starts again. |
| `move-into-space <folder>` | Moves a one-space library, with its users, devices, links, invitations and people, into a space of its own, so further spaces fit beside it. Only renames. The old addresses keep working until you delete `.valbum/moved.json`. |
| `replace-originals <folder>` | Puts downloaded originals in the place of the copies older phone apps uploaded without their position; each copy is kept in `.valbum/replaced/`. `--dry-run` only reports. |
| `migrate-to-user`, `migrate-to-spaces` | For libraries from before spaces existed. |

### Importing an existing library

Copy the folders into a space, even while the server runs; no rescan or restart is needed. A few
seconds after a folder stops changing, the server knows its photos (duplicates, the app's sync,
collections).

- Run `chown -R valbum:valbum <library>` afterwards: the server writes small files (`index.json`,
  `.hashes.json`, `.vacache/`) next to the photos. A folder it cannot write is named once in the log.
- Prefer `rsync -a` or `cp -a`; they keep the file dates that photos without EXIF are sorted by.
- Thumbnails are made when first viewed; with faces on, detection runs in the background for a while.
- If the log says folders cannot be watched, raise `fs.inotify.max_user_watches`.

### Server options

Set in `VALBUM_OPTS` (or given to `java -jar`, see [For developers](#for-developers)).

| Option | Meaning | Default |
|---|---|---|
| `--basepath <dir>` | The library folder | current directory |
| `--port <n>` | HTTP port | `8080` |
| `--contextpath <name>` | First path segment of the URL | none |
| `--auth off\|writes\|all` | What needs a signed-in device: nothing, changes and uploads, everything | `writes` |
| `--spaces auto\|single\|multi` | One space or several; `auto` follows the folders | `auto` |
| `--admin-code <code>` | A fixed sign-in code for an administrator without a device (8 characters of `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, dashes allowed) | a fresh one per start |
| `--preview-threads <n>` | Thumbnails made at the same time | number of processors |
| `--webroot <dir>` | Serve the web app from a directory instead of the bundled one | bundled |

Each `valbum-admin` command is also a flag (`create-space` is `--create-space`, its `--name` is
`--space-name`); the server then runs the job and does not start.

## For developers

### Building

You need Git, a JDK 21 ([Temurin](https://adoptium.net/temurin/releases/?version=21)),
[Maven](https://maven.apache.org/) 3.6+ and the [Flutter SDK](https://docs.flutter.dev/get-started/install)
(stable). Build the web app first, so the server bundles it:

```
cd valbum_ui && flutter pub get && flutter build web && cd ..
mvn clean install
```

The result is `image-server/target/image-server-jar-with-dependencies.jar`. Without
`valbum_ui/build/web` the jar serves the API only.

### Running from source

```
java -jar image-server/target/image-server-jar-with-dependencies.jar --basepath /path/to/photos
```

Then open `http://localhost:8080/`; the JSON API is under `/data/`. Every
[server option](#server-options) is a flag here, the one-time jobs too (`--create-space family
--space-name "The Family"`); stop a running server first.

### Demo server

```
mvn exec:java@test-server -pl :image-server
```

A sample album at http://localhost:9090/valbum/, administrator code `ABCD-EFGH`.

### The app during development

```
cd valbum_ui
flutter run -d chrome        # or -d linux, an Android device, ...
```

On the web the app talks to the server it was loaded from; elsewhere the default is the demo server,
`http://localhost:9090/valbum/data`.

### Releasing and contributing

Releases are cut by a tag, see [RELEASE.md](RELEASE.md). How to build, test and send changes is in
[CONTRIBUTING.md](CONTRIBUTING.md).

---

## Zusammenfassung auf Deutsch

VAlbum hält Deine Photos und Videos auf eigener Hardware — Raspberry Pi, NAS oder ein beliebiger
Linux-Rechner — und zeigt sie auf allen Geräten, ohne Cloud-Anbieter. Jeder Ordner mit Photos ist ein
Album. Die Android-App lädt neue Photos in ein Eingangs-Album; Freigabe-Links, Einladungen,
Sichtbarkeitsstufen, Gesichtserkennung (nur auf Wunsch) und mehrere getrennte Bibliotheken
("Spaces") auf einem Server gehören dazu.

Deine Photos werden nie verändert. Titel, Bewertungen und Reihenfolge stehen in kleinen Dateien
neben den Photos. Gelöscht wird nur, wenn ein Administrator die als "Trash" bewerteten Photos eines
Albums endgültig entfernt, oder wenn ein Album ohne Bilder gelöscht wird. Alles andere wird nur
umbenannt, nach `.valbum/trash/`, `duplicates/` oder `replaced/`.

**Installieren.** Auf einer Synology-NAS mit Container Manager das Image `hauix/valbum` mit der
`compose.yaml` oben einrichten (Photo-Ordner nach `/photos`, `PUID`/`PGID` auf dessen Besitzer);
das Album liegt dann unter `http://<nas>:8080/valbum/`. Auf einem Raspberry Pi oder unter
Debian/Ubuntu (Java 21 nötig, also Debian 13 oder Ubuntu 24.04) kommt es als Paket:

```
sudo install -d /usr/share/keyrings
curl -fsSL https://haumacher.github.io/valbum2/valbum.gpg | sudo tee /usr/share/keyrings/valbum.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/valbum.gpg] https://haumacher.github.io/valbum2 stable main" | sudo tee /etc/apt/sources.list.d/valbum.list
sudo apt update && sudo apt install valbum
```

Den Photo-Ordner stellst Du in `/etc/default/valbum` ein (`VALBUM_BASEPATH`, danach
`sudo systemctl restart valbum`); das Album liegt unter `http://<rechner>:8080/`. Wer mit
`--no-install-recommends` installiert, bekommt keine Video-Umwandlungen; nachrüsten mit
`sudo apt install libxcb1 libxcb-shm0 libxcb-shape0 libxcb-xfixes0 libasound2t64`
(vor trixie bzw. 24.04 `libasound2`). Die Android-App gibt es als APK auf der
[Releases-Seite](https://github.com/haumacher/valbum2/releases).

**E-Mail.** Für persönliche Freigabe-Links verschickt der Server einen 6-stelligen Code, mit dem
Besucher ihre E-Mail-Adresse bestätigen — sonst nichts. Dafür trägst Du das Postausgangs-Konto
Deines Mail-Anbieters in `/etc/default/valbum` bzw. `environment:` ein (`VALBUM_SMTP_HOST`,
`VALBUM_SMTP_USER`, `VALBUM_SMTP_PASSWORD`, siehe [Mail](#mail)); ohne Konto gibt es keinen Code.

**Mit Google anmelden.** Statt des Codes können Besucher ihre Adresse mit Google bestätigen. Dazu
legst Du in der Google Cloud Console einen OAuth-Client vom Typ "Webanwendung" an, mit der
Weiterleitungs-URI `<VALBUM_PUBLIC_URL>/oidc/callback`, und veröffentlichst den Zustimmungs-Bildschirm
(im Modus "Testen" kommen höchstens 100 Nutzer hinein; die Bereiche `openid email profile` brauchen
keine Prüfung). Dann `VALBUM_OIDC_GOOGLE_CLIENT_ID` und `VALBUM_OIDC_GOOGLE_CLIENT_SECRET` eintragen.
Nötig ist eine feste öffentliche https-Adresse in `VALBUM_PUBLIC_URL`; ohne sie wird nichts
angeboten (siehe [Sign-in with Google](#sign-in-with-google)).

**Passkey und Authenticator-App.** Besucher eines persönlichen Links können unter
"Anmeldeoptionen…" einen Passkey oder eine Authenticator-App einrichten und sich damit auf einem
anderen Gerät anmelden. Passkeys gibt es nur mit `VALBUM_PUBLIC_URL` (sie gehören zu deren Host);
für die App ist nichts einzustellen. Ihre Geheimnisse liegen lesbar in
`<bereich>/.valbum/contacts.json` — diese Datei (und ihre Sicherungen) so vertraulich behandeln wie
`/etc/default/valbum`.

**Anmelden.** Beim Start schreibt der Server einen Anmelde-Code für den Administrator ins Log
(`journalctl -u valbum` bzw. das Container-Log), solange der noch kein Gerät hat. Der Code gilt zehn
Minuten und einmal; ein Neustart erzeugt einen neuen. Weitere Geräte meldest Du mit einem Code aus
"My devices" an, weitere Personen kommen per Einladung dazu.

**Verwalten.** Einmalige Aufgaben erledigt `valbum-admin`, z.B.
`sudo valbum-admin create-space familie --name "Die Familie"` (im Container:
`docker compose exec valbum valbum-admin …`); `sudo valbum-admin help` zeigt alle Befehle.
Aktualisiert wird mit `sudo apt upgrade` bzw. `docker compose pull && docker compose up -d`.

**Lizenz.** VAlbum ist freie Software unter der
[GNU Affero General Public License, Version 3 oder höher](LICENSE) (AGPL-3.0-or-later).
