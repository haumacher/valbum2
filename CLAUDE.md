# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

VAlbum2 — a self-hosted photo/video album. A Java backend (Jetty) serves albums read from a folder tree; it never modifies the original photos (all edits are stored in sidecar files). Two front-ends exist:

- **`valbum_ui/`** — Flutter app. **This is the active development focus.**
- **`image-server-client/`** — legacy GWT web UI (compiled to JS, served by the backend).

## Two separate toolchains

This repo mixes **Maven** (Java/GWT, multi-module) and **Flutter/Dart** (`valbum_ui/`), with independent dependency management. A full build means running both.

### Maven (backend + GWT UI)

- Build everything: `mvn clean install` (run from repo root)
  - **Known quirk:** the build fails at the `image-server-client` GWT compile with `Working directory ".../image-server-client/target" does not exist!` after a `clean`. Workaround: `mkdir -p image-server-client/target` after `clean` and before the GWT compile (or build without `clean`).
- Run the demo server: `mvn exec:java@test-server -pl :image-server` → http://localhost:9090/valbum/ (port 9090, non-standard)
- Run the packaged jar: `java -jar image-server/target/image-server-jar-with-dependencies.jar --basepath /path/to/photos [--port 8080] [--contextpath valbum]`
- Test fixtures live at `image-server/src/test/fixtures/test-album`.
- Java lint/format (Spotless, lightweight — tidies imports/whitespace, preserves tab indentation; **not** bound to a build phase): `mvn spotless:check` / `mvn spotless:apply`.

### Flutter (`valbum_ui/`)

- The `dart` CLI is on PATH; the `flutter` CLI may not be. Prefer `dart analyze` / `dart test` / `dart format` when `flutter` is unavailable.
- The backend URL is hardcoded in `valbum_ui/lib/main.dart` (defaults to `http://localhost:9090/valbum/data`).

## Gotchas

- **Model classes are generated.** `image-server-shared/src/main/java/.../shared/model/model.proto` is the source of truth; the msgbuf-generator Maven plugin regenerates the model on every build. Edit the `.proto`, never the generated output. This generates **both** the Java model classes **and** the Dart file `valbum_ui/lib/resource.dart` (see the `option DartLib=...` line in `model.proto`) — `resource.dart` is generated, so don't hand-edit or reformat it.
- **Java 1.8 source/target** even though the runtime is newer — required by GWT 2.9.0. Don't bump the compiler `source`/`target` without checking GWT compatibility.

## Conventions

- Work on a feature branch and open a PR to `master`; don't commit directly to `master`.
