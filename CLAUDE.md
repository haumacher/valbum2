# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

VAlbum2 — a self-hosted photo/video album. A Java backend (Jetty) serves albums read from a folder tree as a JSON API; it never modifies the original photos (all edits are stored in sidecar files). The only front end is the Flutter app in **`valbum_ui/`** (web, mobile, desktop). The former GWT web client was removed in ROADMAP Phase 0.

Direction, phases and decisions are recorded in `ROADMAP.md`.

## Two separate toolchains

This repo mixes **Maven** (Java backend, multi-module: `image-server`, `image-server-shared`, `util-servlet`) and **Flutter/Dart** (`valbum_ui/`), with independent dependency management. A full build means running both.

### Maven (backend)

- Build everything: `mvn clean install` (run from repo root)
- Run the demo server: `mvn exec:java@test-server -pl :image-server` → http://localhost:9090/valbum/ (port 9090, non-standard)
- Run the packaged jar: `java -jar image-server/target/image-server-jar-with-dependencies.jar --basepath /path/to/photos [--port 8080] [--contextpath valbum] [--webroot /path/to/flutter/build/web] [--auth off|writes|all] [--pairing-secret <secret>]`
- Authentication (#28): `--auth` defaults to `writes` — anonymous reads are served, anonymous PUT/POST are refused with 401 and an `ErrorInfo` body. A device pairs with `POST <data>/?action=pair` against the pairing secret (printed at start-up, `demo` for the demo server) and then sends `Authorization: Bearer <token>`; `GET <data>/?type=auth` reports the caller's state. The token hashes live in `<basepath>/.valbum/devices.json`.
- Static web content: the server serves `META-INF/resources` of the class path (the Flutter web build, icons included, if `valbum_ui/build/web` existed at `package` time) at the context root; `--webroot <dir>` serves a directory from disk with precedence over the class path. Unknown paths without a file extension fall back to `index.html` (client-side deep links).
- Test fixtures live at `image-server/src/test/fixtures/test-album`.
- Java lint/format (Spotless, lightweight — tidies imports/whitespace, preserves tab indentation; **not** bound to a build phase): `mvn spotless:check` / `mvn spotless:apply`.

### Flutter (`valbum_ui/`)

- Use the `flutter` CLI: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run -d chrome`. The Flutter SDK lives in `~/flutter`, the Android SDK in `~/Android/Sdk` (both on PATH via `~/.bashrc`).
- Server URL: on the web derived from the page origin (`lib/urls.dart`, `deriveDataUrl`); on other platforms the default `http://localhost:9090/valbum/data` until the settings screen (ROADMAP Phase 2). All HTTP goes through `lib/client.dart` (`VAlbumClient`), injected via `VAlbumScope` — tests pass a `MockClient`.

## Gotchas

- **Model classes are generated.** `image-server-shared/src/main/java/.../shared/model/model.proto` is the source of truth; the msgbuf-generator Maven plugin regenerates the model on every build. Edit the `.proto`, never the generated output. This generates **both** the Java model classes **and** the Dart file `valbum_ui/lib/resource.dart` (see the `option DartLib=...` line in `model.proto`) — `resource.dart` is generated, so don't hand-edit or reformat it.
- **Java 21** (`maven.compiler.release` in the root pom). The former 1.8 setting was a GWT constraint and is gone; a JDK 21 is required to build (the Maven enforcer checks it).

## Conventions

- Commit directly to `master` and push after every reviewed, gated change (see the `valbum-workflow` skill). Feature branches and PRs are for external contributors, not for the working session.
