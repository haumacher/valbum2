#!/usr/bin/env bash
#
# Build a signed APT repository from a directory of .deb files.
#
# The repository is a *derived view*: everything it contains comes from the .deb
# files handed in, so it can be rebuilt from scratch at any time and needs no
# state carried over from an earlier run.
#
# Usage:
#   build-apt-repo.sh <deb-dir> <out-dir> <key-id>
#
#   <deb-dir>  directory that is searched recursively for *.deb
#   <out-dir>  directory the site is written to (created, must be empty or absent)
#   <key-id>   id (or fingerprint, or uid) of the secret key in the current
#              GNUPGHOME used to sign `Release`
#
# Environment:
#   SITE_URL   base URL the generated index.html tells users to configure
#              (default: https://haumacher.github.io/valbum2)
#   GPG_PASSPHRASE  passphrase of the signing key, if it has one
#
set -euo pipefail

usage() {
	echo "usage: $0 <deb-dir> <out-dir> <key-id>" >&2
	exit 2
}

[ $# -eq 3 ] || usage

DEB_DIR="$1"
OUT_DIR="$2"
KEY_ID="$3"

SITE_URL="${SITE_URL:-https://haumacher.github.io/valbum2}"
SUITE="stable"
COMPONENT="main"

for tool in dpkg-scanpackages apt-ftparchive dpkg-deb gpg gzip; do
	command -v "$tool" >/dev/null 2>&1 || {
		echo "error: '$tool' is not installed." >&2
		echo "       On Ubuntu/Debian: sudo apt-get install dpkg-dev apt-utils gnupg" >&2
		exit 1
	}
done

[ -d "$DEB_DIR" ] || { echo "error: no such directory: $DEB_DIR" >&2; exit 1; }

mapfile -t DEBS < <(find "$DEB_DIR" -name '*.deb' -type f | sort)
if [ "${#DEBS[@]}" -eq 0 ]; then
	echo "error: no .deb files found below $DEB_DIR" >&2
	echo "       The APT repository is built from the GitHub Release assets;" >&2
	echo "       without any package there is nothing to publish." >&2
	exit 1
fi

if ! gpg --list-secret-keys "$KEY_ID" >/dev/null 2>&1; then
	echo "error: no secret key '$KEY_ID' in GNUPGHOME=${GNUPGHOME:-$HOME/.gnupg}." >&2
	echo "       An unsigned APT repository is never published." >&2
	echo "       Create a key with:" >&2
	echo "         gpg --quick-generate-key \"VAlbum APT <you@example.com>\" ed25519 sign never" >&2
	echo "         gpg --armor --export-secret-keys <key-id>" >&2
	exit 1
fi

GPG_ARGS=(--batch --yes --pinentry-mode loopback --local-user "$KEY_ID")
if [ -n "${GPG_PASSPHRASE:-}" ]; then
	GPG_ARGS+=(--passphrase "$GPG_PASSPHRASE")
fi

POOL="$OUT_DIR/pool/$COMPONENT/v/valbum"
if [ -e "$OUT_DIR" ] && [ -n "$(ls -A "$OUT_DIR")" ]; then
	echo "error: the output directory $OUT_DIR exists and is not empty." >&2
	echo "       The site is built from scratch; give a fresh directory." >&2
	exit 1
fi
mkdir -p "$POOL"

echo "Collecting ${#DEBS[@]} package(s) into $POOL"
for deb in "${DEBS[@]}"; do
	cp -f "$deb" "$POOL/$(basename "$deb")"
done

# The architectures actually present; the repository never advertises one it
# has no package for.
mapfile -t ARCHS < <(
	for deb in "$POOL"/*.deb; do
		dpkg-deb --field "$deb" Architecture
	done | sort -u
)
echo "Architectures: ${ARCHS[*]}"

for arch in "${ARCHS[@]}"; do
	dist_dir="$OUT_DIR/dists/$SUITE/$COMPONENT/binary-$arch"
	mkdir -p "$dist_dir"
	# dpkg-scanpackages wants paths relative to the repository root, so it runs
	# there; `--arch` keeps the architecture's packages and the `all` ones.
	(
		cd "$OUT_DIR"
		dpkg-scanpackages --arch "$arch" --multiversion "pool/$COMPONENT/v/valbum" /dev/null
	) > "$dist_dir/Packages" 2>/dev/null
	gzip -9nc "$dist_dir/Packages" > "$dist_dir/Packages.gz"
	echo "  binary-$arch: $(grep -c '^Package: ' "$dist_dir/Packages") package(s)"
done

echo "Writing dists/$SUITE/Release"
(
	cd "$OUT_DIR"
	apt-ftparchive \
		-o "APT::FTPArchive::Release::Origin=VAlbum" \
		-o "APT::FTPArchive::Release::Label=VAlbum" \
		-o "APT::FTPArchive::Release::Suite=$SUITE" \
		-o "APT::FTPArchive::Release::Codename=$SUITE" \
		-o "APT::FTPArchive::Release::Components=$COMPONENT" \
		-o "APT::FTPArchive::Release::Architectures=${ARCHS[*]}" \
		-o "APT::FTPArchive::Release::Description=VAlbum — self-hosted photo and video album" \
		release "dists/$SUITE" > "dists/$SUITE/Release.tmp"
	mv "dists/$SUITE/Release.tmp" "dists/$SUITE/Release"
)

echo "Signing dists/$SUITE/Release with $KEY_ID"
gpg "${GPG_ARGS[@]}" --armor --detach-sign \
	--output "$OUT_DIR/dists/$SUITE/Release.gpg" "$OUT_DIR/dists/$SUITE/Release"
gpg "${GPG_ARGS[@]}" --clearsign \
	--output "$OUT_DIR/dists/$SUITE/InRelease" "$OUT_DIR/dists/$SUITE/Release"

# The public key, dearmored for /usr/share/keyrings/ and armored for anyone who
# would rather look at it first.
gpg --batch --yes --export "$KEY_ID" > "$OUT_DIR/valbum.gpg"
gpg --batch --yes --armor --export "$KEY_ID" > "$OUT_DIR/valbum.asc"
[ -s "$OUT_DIR/valbum.gpg" ] || { echo "error: exporting the public key produced nothing" >&2; exit 1; }

# GitHub Pages would otherwise hide directories starting with an underscore and
# run the content through Jekyll.
touch "$OUT_DIR/.nojekyll"

echo "Writing index.html"
versions_html=$(
	for deb in "$POOL"/*.deb; do
		printf '%s\t%s\t%s\n' \
			"$(dpkg-deb --field "$deb" Version)" \
			"$(dpkg-deb --field "$deb" Architecture)" \
			"$(basename "$deb")"
	done | sort -rV | while IFS=$'\t' read -r version arch file; do
		printf '<tr><td>%s</td><td>%s</td><td><a href="pool/%s/v/valbum/%s">%s</a></td></tr>\n' \
			"$version" "$arch" "$COMPONENT" "$file" "$file"
	done
)

cat > "$OUT_DIR/index.html" <<HTML
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VAlbum APT repository</title>
<style>
body { font-family: system-ui, sans-serif; line-height: 1.5; margin: 0 auto; max-width: 46rem; padding: 2rem 1rem; color: #222; background: #fff; }
h1 { margin-bottom: 0.2rem; }
p.lead { color: #555; margin-top: 0; }
pre { background: #f4f4f4; padding: 0.8rem; overflow-x: auto; border-radius: 4px; }
code { font-family: ui-monospace, monospace; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 0.3rem 0.6rem; border-bottom: 1px solid #ddd; }
footer { margin-top: 3rem; color: #666; font-size: 0.9rem; }
</style>
</head>
<body>
<h1>VAlbum</h1>
<p class="lead">A self-hosted photo and video album. This page is the APT repository for the server package.</p>

<h2>Install on a Raspberry Pi or another Debian/Ubuntu machine</h2>
<pre><code>sudo install -d /usr/share/keyrings
curl -fsSL $SITE_URL/valbum.gpg | sudo tee /usr/share/keyrings/valbum.gpg &gt;/dev/null
echo "deb [signed-by=/usr/share/keyrings/valbum.gpg] $SITE_URL $SUITE $COMPONENT" | sudo tee /etc/apt/sources.list.d/valbum.list
sudo apt update &amp;&amp; sudo apt install valbum</code></pre>
<p>APT picks the right architecture itself (arm64, amd64 and armhf are published).
The package needs a Java 21 runtime, which Debian 13 (trixie), Raspberry Pi OS
trixie and Ubuntu 24.04 have; Debian 12 (bookworm) ships only Java 17.</p>

<h2>After installing</h2>
<p>The server runs as the systemd service <code>valbum</code> and is configured in
<code>/etc/default/valbum</code>. Point it at your photo disk and restart:</p>
<pre><code>sudo nano /etc/default/valbum      # VALBUM_BASEPATH=/mnt/photos, VALBUM_PORT=8080, ...
sudo systemctl restart valbum</code></pre>
<p>At its first start the server prints a pairing secret to the journal:</p>
<pre><code>journalctl -u valbum | grep -i pairing</code></pre>
<p>Then open <code>http://&lt;your-pi&gt;:8080/</code> in a browser, or point the app's server
setting at it and sign in with that secret.</p>

<h2>Updating</h2>
<pre><code>sudo apt update &amp;&amp; sudo apt upgrade</code></pre>
<p>Your library at <code>VALBUM_BASEPATH</code> (default <code>/var/lib/valbum</code>) is never
touched by the package, not even when it is purged.</p>

<h2>Published packages</h2>
<table>
<tr><th>Version</th><th>Architecture</th><th>File</th></tr>
$versions_html
</table>
<p>The Android app (APK) and all released packages are on the
<a href="https://github.com/haumacher/valbum2/releases">GitHub Releases page</a>.</p>

<footer>
Signing key: <a href="valbum.gpg">valbum.gpg</a> (binary keyring) &middot;
<a href="valbum.asc">valbum.asc</a> (armored) &middot;
<a href="https://github.com/haumacher/valbum2">source on GitHub</a>
</footer>
</body>
</html>
HTML

echo "Done: $OUT_DIR"
