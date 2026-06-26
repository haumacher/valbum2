# Contributing to VAlbum2

Thanks for your interest in contributing to VAlbum2! This guide explains how to
build and test the project locally.

VAlbum2 is a Maven multi-module project (a Java server plus a
[GWT](https://www.gwtproject.org/) web client). The modules are:
`image-server-shared`, `image-server-client`, `image-server`, `util-gwt`,
`util-servlet`, and `util-xml`.

## Prerequisites

- **JDK 11** — a [Java 11 JDK](https://adoptium.net/temurin/releases/?version=11)
  is recommended (the project compiles to Java 8 bytecode, so JDK 8 or newer
  works, but the runtime documented in the `README.md` is Java 11).
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

Then open http://localhost:9090/valbum/ in your browser. See the `README.md` for
more details on running and deploying VAlbum.

## Submitting changes

1. Create a feature branch off `master`.
2. Make your change and ensure the project still builds (`mvn clean install`).
3. Open a pull request describing what you changed and why.
