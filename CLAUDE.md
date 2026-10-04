# CLAUDE.md

VAlbum2 — a self-hosted photo/video album. A Java backend (Jetty) serves a folder tree of photos as a
JSON API and never modifies the originals. The only front end is the Flutter app in `valbum_ui/`
(web, mobile, desktop). Direction and decisions: `ROADMAP.md`. Public build instructions: `CONTRIBUTING.md`.

## Build

- Backend (Maven, Java 21): `mvn clean install` from the repo root. Format: `mvn spotless:apply`.
- Demo server: `mvn exec:java@test-server -pl :image-server` → http://localhost:9090/valbum/
- App (Flutter SDK in `~/flutter`): in `valbum_ui/` — `flutter pub get`, `flutter analyze`, `flutter test`.
- A full check runs both toolchains (`build-verify` skill).

## Rules

- `model.proto` generates the Java model and `valbum_ui/lib/resource.dart` — edit the proto, never the output.
- Strings: edit only `valbum_ui/lib/l10n/app_en.arb`, then translate and `flutter gen-l10n` (see `faq/localization.md`).
- New dependencies must be compatible with AGPL-3.0-or-later.
- Commit directly to `master` and push after each reviewed, gated change (`valbum-workflow` skill).

## Knowledge

Non-obvious facts live in `faq/`, one file each. Do **not** document features in this file —
behaviour belongs in code, tests, issues and `ROADMAP.md`. Add a `faq/` file only for a pitfall or
decision a reader could not derive from the code.
