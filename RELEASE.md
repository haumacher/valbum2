# Releasing VAlbum2

A release is cut by pushing a tag `valbum-<major>.<minor>.<patch>`. The *Release* workflow
(`.github/workflows/release.yml`) then builds the server's Debian packages for `arm64`,
`amd64` and `armhf`, a signed Android APK, publishes a GitHub Release with all of them and
rebuilds the APT repository on GitHub Pages, <https://haumacher.github.io/valbum2/>.

Nothing is ever published unsigned: a missing secret fails the job with a message saying what
to add. This document is the one-time setup of those secrets and the repository settings, the
release procedure itself, and what to do when something goes wrong.

## What the workflow does

| Job | Runs on | What it does |
|---|---|---|
| `prepare` | tag push or manual run | Parses the tag into the version `x.y.z` and the Android `versionCode` `x*10000 + y*100 + z`. Refuses any other tag form. |
| `web` | tag | `flutter build web --release`, handed to the packaging jobs as a workflow artifact so the app is built once. |
| `deb` (×3) | tag | Sets the pom version from the tag (`mvn versions:set`, **not** committed), builds `valbum_<version>_<arch>.deb` with the web build bundled into the jar. The tests run on the `amd64` leg only. |
| `android` | tag | Writes the keystore from the secrets, `flutter build apk --release`, verifies with `apksigner` that the APK is **not** debug-signed, deletes the signing material again. |
| `release` | tag | Creates the GitHub Release `VAlbum <version>` with generated notes, or replaces the assets of an existing one: the three `.deb`, `valbum-<version>.apk`, `SHA256SUMS`. Runs even when the Android job failed (the release then carries the packages only and the run shows red). |
| `pages` | tag, or manual run without a tag | Downloads the `.deb` assets of the **newest two** releases, builds the signed APT repository with `.github/scripts/build-apt-repo.sh` and deploys it to GitHub Pages. |

The GitHub Release assets are the source of truth. The Pages site is a derived view rebuilt
from scratch on every run, so nothing is carried between runs and the site can be republished
at any time.

## One-time setup

Everything below is done once per repository (or once per fork). All secrets go under
**Settings → Secrets and variables → Actions → Repository secrets**, or from a terminal with
the [GitHub CLI](https://cli.github.com/) as shown. `gh secret set` needs a token with the
`repo` scope; a fine-grained personal access token also needs *Secrets: read and write*.

### 1. The APT signing key

`apt` refuses an unsigned repository, and anyone could serve a package in place of an unsigned
one. The `Release` file of the repository is signed with a GPG key whose private half is a
repository secret and whose public half is published on the site as `valbum.gpg` (users import
it with the `curl ... | tee /usr/share/keyrings/valbum.gpg` line from the README).

Create the key on a machine you trust. A key without a passphrase is the simplest for CI
(the secret store is the protection); if you give it one, add it as a second secret.

```
gpg --quick-generate-key "VAlbum APT <you@example.com>" ed25519 sign never
gpg --list-secret-keys --keyid-format long          # note the key id / fingerprint
gpg --armor --export-secret-keys <key-id> > apt-signing-key.asc
```

`sign` creates a signing-only key, `never` a key that does not expire (an expired key would
make every installed machine refuse updates). Add the secrets:

```
gh secret set APT_SIGNING_KEY < apt-signing-key.asc
gh secret set APT_SIGNING_PASSPHRASE                # only if the key has one; type it at the prompt
shred -u apt-signing-key.asc
```

| Secret | Value |
|---|---|
| `APT_SIGNING_KEY` | The ASCII-armored private key, complete with the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `END` lines |
| `APT_SIGNING_PASSPHRASE` | Its passphrase; leave the secret unset if the key has none |

Keep a backup of the private key (`gpg --export-secret-keys`) somewhere safe. If it is lost,
a new key can be generated and set, but every machine that installed the repository has to
import the new `valbum.gpg` before `apt update` works again.

### 2. The Android upload keystore

Android installs an update only if it is signed with the same key as the installed copy. The
release APK is therefore signed with one keystore for the life of the app, and the workflow
refuses to publish an APK signed with the debug key.

**Losing this keystore means no future APK can ever update an installed VAlbum.** Users would
have to uninstall and reinstall. Keep it, and its passwords, in a backup you will still find
in ten years.

```
keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
    -validity 10000 -alias upload
```

`keytool` asks for the keystore password, the key password (answer with the same one unless
you want two) and the certificate's name fields; fill them in as you like. Then:

```
gh secret set ANDROID_KEYSTORE_BASE64 --body "$(base64 -w0 upload-keystore.jks)"
gh secret set ANDROID_KEYSTORE_PASSWORD                 # type the keystore password at the prompt
gh secret set ANDROID_KEY_ALIAS --body upload
gh secret set ANDROID_KEY_PASSWORD                      # type the key password at the prompt
```

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64-encoded on **one line** (`base64 -w0`; on macOS `base64 -i upload-keystore.jks \| tr -d '\n'`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The alias given to `keytool`, `upload` above |
| `ANDROID_KEY_PASSWORD` | The key password |

Move `upload-keystore.jks` out of the working copy into your backup. `*.jks` and
`android/key.properties` are gitignored so an accident does not commit them, but do not rely
on it.

To sign a release build locally with the same key, put the keystore at
`valbum_ui/android/app/upload-keystore.jks` and write `valbum_ui/android/key.properties`:

```
storeFile=upload-keystore.jks
storePassword=<keystore password>
keyAlias=upload
keyPassword=<key password>
```

Without that file `flutter build apk --release` falls back to the debug key, which is fine
for `flutter run --release` and never leaves your machine.

### 3. GitHub Pages

Under **Settings → Pages**, set *Build and deployment → Source* to **GitHub Actions**. This
creates the `github-pages` environment the `pages` job deploys to. The repository is
configured this way already; a fork has to repeat it.

**Allow tags to deploy.** GitHub creates the `github-pages` environment with a deployment
policy that admits only the default branch. A release runs from the tag, not from `master`,
and the Pages deployment would be refused with

```
Branch "valbum-1.1.0" is not allowed to deploy to github-pages due to environment protection rules.
```

Add a tag pattern to the policy once, under **Settings → Environments → github-pages →
Deployment branches and tags → Add deployment branch or tag rule**, ref type *Tag*, pattern
`valbum-*`. From the terminal:

```
gh api --method POST repos/haumacher/valbum2/environments/github-pages/deployment-branch-policies \
    -f name='valbum-*' -f type=tag
```

Keep the `master` rule: a manual run that only republishes the site runs from `master`.

### 4. Workflow permissions

The workflow declares what it needs per job (`contents: write` for the release, `pages: write`
and `id-token: write` for the deployment); those declarations override the repository's
default workflow permissions, so nothing has to be changed under
*Settings → Actions → General*. Actions must be enabled for the repository, which they are
by default.

### Checking the setup

The Release workflow can be run without a tag: **Actions → Release → Run workflow**, leave the
tag empty. This runs only the `pages` job, which checks `APT_SIGNING_KEY` and the deployment
policy but needs at least one published release to have anything to serve, so on a fresh
repository it fails at "No published release carries a package". That is the expected outcome
before the first release; the secret and the environment were checked by then.

## Cutting a release

1. **Make sure `master` is green.** The CI workflow must have passed on the commit you tag;
   the release workflow does not re-run the Flutter tests and runs the Java tests on one leg only.
2. **Pick the version.** The pom on `master` carries the *next* version as a snapshot,
   `1.1.0-SNAPSHOT` means the next release is `1.1.0`. The tag must be exactly
   `valbum-<major>.<minor>.<patch>`; `valbum-1.1` or `v1.1.0` fail in the first job. The
   Android `versionCode` is `major*10000 + minor*100 + patch`, so `minor` and `patch` stay below
   100 and versions must grow: a `1.1.0` after a `1.2.0` could not be installed over it.
3. **Tag and push.**

   ```
   git checkout master && git pull
   git tag -a valbum-1.1.0 -m "VAlbum 1.1.0"
   git push origin valbum-1.1.0
   ```

4. **Watch the run** under *Actions → Release* (or `gh run watch`). It takes a while: the
   `arm64` and `armhf` legs download JavaCPP natives, the Flutter jobs set up the SDK.
5. **Check the result.**
   - The release page <https://github.com/haumacher/valbum2/releases> shows `VAlbum 1.1.0`
     with `valbum_1.1.0_arm64.deb`, `_amd64.deb`, `_armhf.deb`, `valbum-1.1.0.apk` and
     `SHA256SUMS`. Edit the generated notes if they need a human sentence.
   - <https://haumacher.github.io/valbum2/> lists the new version, and on a machine with the
     repository configured `sudo apt update && apt policy valbum` offers it.
   - `apksigner verify --print-certs valbum-1.1.0.apk` (from the Android build tools) shows
     your certificate, not `CN=Android Debug`.
6. **Open the next version.** Bump the pom by hand and commit it to `master`:

   ```
   mvn -B versions:set -DnewVersion=1.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "Open 1.2.0" && git push
   ```

   The workflow never commits: the pom version is set from the tag inside the build only.
   `valbum_ui/pubspec.yaml`'s `version:` is overridden by `--build-name`/`--build-number` in
   the same way and need not be touched.

## Re-running and republishing

- **A release run failed half-way** (a flaky download, a missing secret you have since added):
  fix the cause and rerun the failed jobs from the run's page, or run the workflow manually
  with the tag in the input. An existing release keeps its notes; its assets are replaced.
- **Only the site is broken or stale:** run the workflow manually with an **empty** tag. Nothing
  is built; the site is rebuilt from the `.deb` assets of the newest two published releases.
- **Republish an older release:** run the workflow manually with its tag. Only tags at or after
  the commit that introduced the Debian packaging can be built this way; the older tags
  `valbum-0.9.0` and `valbum-1.0.0` predate it and have no release.
- **A release was wrong:** do not move the tag. Fix `master`, cut the next patch version. If
  the wrong release must disappear, delete the GitHub Release (and the tag) by hand, then
  republish the site with an empty-tag run so the repository stops offering it.

## Building the release artifacts locally

Everything the workflow does can be done on a developer machine, which is the quickest way to
debug a packaging problem.

```
# The web app, bundled into the jar by the Maven build if valbum_ui/build/web exists
( cd valbum_ui && flutter pub get && flutter build web --release )

# One Debian package; the platform selects the architecture (linux-x86_64 → amd64,
# linux-arm64 → arm64, linux-armhf → armhf). Without -Djavacpp.platform no .deb is built:
# the unrestricted jar carries natives for every platform and is far too big.
mvn -B versions:set -DnewVersion=1.1.0 -DgenerateBackupPoms=false
mvn -B -Djavacpp.platform=linux-arm64 -DskipTests clean install
dpkg-deb --info image-server/target/valbum_1.1.0_arm64.deb
git checkout pom.xml */pom.xml            # undo the version change

# The APT repository from a directory of .deb files, signed with a key in your GNUPGHOME
sudo apt-get install dpkg-dev apt-utils gnupg
.github/scripts/build-apt-repo.sh image-server/target site <key-id>
python3 -m http.server -d site 8000       # then point a test machine at http://<host>:8000

# The signed APK, with android/key.properties in place as described above
( cd valbum_ui && flutter build apk --release --build-name=1.1.0 --build-number=10100 )
```

## When something fails

| Symptom | Cause and remedy |
|---|---|
| `Tag 'x' is not of the form valbum-<major>.<minor>.<patch>` | Delete the tag (`git push --delete origin x`) and push a correctly named one. |
| `Missing repository secret(s): ANDROID_...` | Set the secrets from section 2. The release is published without the APK; rerun the failed jobs afterwards to attach it. |
| `Missing repository secret APT_SIGNING_KEY` | Set it from section 1. The release exists; run the workflow with an empty tag to publish the site. |
| `APT_SIGNING_KEY contains no secret key` | The secret holds the public key. Export with `gpg --armor --export-secret-keys`, not `--export`. |
| `Branch "valbum-x.y.z" is not allowed to deploy to github-pages` | Add the `valbum-*` tag rule from section 3, then rerun the `pages` job. |
| `The APK is signed with the debug key` | `key.properties` was not picked up: an alias or password secret is wrong or empty. Check the four secrets. |
| `apksigner was not found` | The Flutter action's Android SDK lacks build tools; usually a transient image problem, rerun. |
| `Expected ...deb, but the build produced:` | The version in the pom and the tag disagree, or the `deb.arch` mapping for the platform is missing; build locally as above. |
| `apt update` on a client: `NO_PUBKEY` or `The following signatures couldn't be verified` | The signing key changed. Re-import `valbum.gpg` from the site on the client. |
| The site lists a version but `apt` does not offer it | The client's architecture has no package in that version, or `apt update` was not run. `apt policy valbum` shows what is seen. |
