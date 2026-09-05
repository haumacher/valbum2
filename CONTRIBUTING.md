# Contributing to VAlbum2

Thanks for your interest in contributing to VAlbum2! This guide explains how to
build and test the project locally.

VAlbum2 is a Maven multi-module Java server (`image-server`,
`image-server-shared`, `util-servlet`) plus a Flutter app in `valbum_ui/`
(web, mobile, desktop). This guide covers the server; see `valbum_ui/README.md`
and `CLAUDE.md` for the Flutter toolchain.

## Prerequisites

- **JDK 21** — a [Java 21 JDK](https://adoptium.net/temurin/releases/?version=21).
  The modules compile with `--release 21`; the build enforces a JDK 21 or newer.
- **Apache Maven 3.6.0 or newer** — see [maven.apache.org](https://maven.apache.org/).
  The build enforces this minimum version.
- **Git** — to clone the repository.

## Building

From the repository root, build all modules and install the artifacts into your
local Maven repository:

```
mvn clean install
```

To build without running the tests:

```
mvn -DskipTests package
```

## Running the tests

From the repository root:

```
mvn test
```

## Trying it out

After a successful build you can start a demo server with a sample album:

```
mvn exec:java@test-server -pl :image-server
```

Then open http://localhost:9090/valbum/data/?type=json in your browser to see the
JSON API. The web front end is the Flutter app in `valbum_ui/`; its web build
(`flutter build web`) is bundled into the server JAR when it exists, and can be
served from disk during development:

```
java -jar image-server/target/image-server-jar-with-dependencies.jar \
    --basepath image-server/src/test/fixtures/test-album \
    --webroot valbum_ui/build/web
```

See the `README.md` for more details on running and deploying VAlbum.

## Submitting changes

1. Create a feature branch off `master`.
2. Make your change and ensure the project still builds (`mvn clean install`).
3. Open a pull request describing what you changed and why.
