---
name: build-verify
description: Build and verify the whole VAlbum2 project — runs the Maven build for the Java/GWT modules and analyzes/tests the Flutter app. Use after making changes to confirm the project still builds and checks pass.
---

Verify the full project across both toolchains. Run the steps in order and report results; if a step fails, surface the failing output and stop.

## 1. Java / GWT modules (Maven)

From the repo root:

```
mvn clean install
```

This also regenerates the msgbuf model classes from `image-server-shared/.../model/model.proto`.

## 2. Flutter app (`valbum_ui/`)

```
cd valbum_ui
flutter pub get
flutter analyze
flutter test
```

## 3. Report

Summarize: did the Maven build succeed, did analysis pass, did tests pass. Quote any errors verbatim.
