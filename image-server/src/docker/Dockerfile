# syntax=docker/dockerfile:1.7
#
# The VAlbum server as a container image (issue #174), one image per architecture published
# under one multi-arch tag.
#
# The image is built from the platform JARs the Debian packages are built from, never from the
# all-platform JAR (which carries the natives of every OS and CPU). Build context and inputs:
#
#   - the build context is image-server/src, of which Dockerfile.dockerignore admits only this
#     directory's scripts and deb/valbum-server - the same start script the Debian package runs,
#     so one place turns the VALBUM_* variables into server arguments;
#   - the named context 'jars' is a directory holding one platform JAR per architecture, named
#     after Docker's TARGETARCH: amd64.jar (built with -Djavacpp.platform=linux-x86_64) and
#     arm64.jar (-Djavacpp.platform=linux-arm64). Each is the image-server-<platform>-jar-with-
#     dependencies.jar of the Maven build, with the Flutter web build bundled into it.
#
#   docker buildx build --platform linux/amd64,linux/arm64 \
#       --build-context jars=<dir with amd64.jar and arm64.jar> \
#       -f image-server/src/docker/Dockerfile \
#       -t ghcr.io/haumacher/valbum:<version> image-server/src
#
# The runtime contract (the /photos mount, PUID/PGID, the VALBUM_* variables) is described in
# the README, section "Docker (Synology NAS)", and in docker-entrypoint.

ARG BASE=eclipse-temurin:21-jre-noble

# --- Split the fat JAR into its two layers -------------------------------------------------------
#
# Runs on the build machine's own platform: it only reshuffles files, so an arm64 image costs no
# emulation here. See split-jar for what goes where and why the result is reproducible.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS split
RUN apt-get update \
	&& apt-get install -y --no-install-recommends zip unzip \
	&& rm -rf /var/lib/apt/lists/*
ARG TARGETARCH
COPY --from=jars ${TARGETARCH}.jar /work/fat.jar
COPY --chmod=755 docker/split-jar /usr/local/bin/split-jar
RUN split-jar /work/fat.jar /out
# The natives the 'check' target below resolves; never part of the published image.
RUN mkdir /natives && unzip -q /work/fat.jar 'org/bytedeco/*/linux-*/*' -d /natives

# --- The runtime image ------------------------------------------------------------------------------
FROM ${BASE} AS runtime

# The system libraries the bundled natives link and no JavaCPP artifact ships:
#
#   - for the FFmpeg program of the video renditions exactly the 'Recommends' of the Debian
#     package (image-server/src/deb/control/control), which TestDebianPackageLibraries derives
#     from the artifacts' ELF headers and TestDockerImageLibraries holds this line to;
#   - for the face index GTK 2: FaceDetection loads OpenCV through the JavaCPP preset
#     'opencv_java', which inherits nearly every OpenCV preset and so loads the JNI library of
#     'opencv_highgui', which links GTK 2, gdk-pixbuf, cairo and glib. Without them the detector
#     does not load at all and a space with 'faces: on' is served without faces. It costs the
#     image about 115 MB, and it goes again once the face index loads only what it uses.
#
# check-libraries (the 'check' target below) proves the closure inside the built image.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends \
		libxcb1 libxcb-shm0 libxcb-shape0 libxcb-xfixes0 libasound2t64 \
		libgtk2.0-0t64 \
	&& rm -rf /var/lib/apt/lists/*

COPY --chmod=755 deb/valbum-server /usr/bin/valbum-server
COPY --chmod=755 docker/docker-entrypoint /usr/local/bin/docker-entrypoint
COPY --chmod=755 docker/healthcheck /usr/local/bin/healthcheck
COPY --chmod=755 docker/check-libraries /usr/local/bin/check-libraries

# Layer 1: the dependencies with their natives and the face models - large, and unchanged between
# releases that do not touch a dependency, so an update does not pull them again.
COPY --from=split /out/lib/ /opt/valbum/lib/
# Layer 2: VAlbum's own classes and the web app - small, new with every release.
COPY --from=split /out/valbum.jar /opt/valbum/valbum.jar

ENV VALBUM_JAR=/opt/valbum/valbum.jar \
	VALBUM_BASEPATH=/photos \
	VALBUM_PORT=8080 \
	VALBUM_CONTEXTPATH=valbum \
	VALBUM_AUTH=writes \
	VALBUM_SPACES=auto \
	VALBUM_OPTS= \
	JAVA_OPTS= \
	VALBUM_HEAP_PERCENT=50

RUN mkdir -p /photos

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 CMD ["/usr/local/bin/healthcheck"]

ENTRYPOINT ["/usr/local/bin/docker-entrypoint"]

# --- The library check (not published) -------------------------------------------------------------
#
# 'docker buildx build --target check ...' resolves every library the bundled FFmpeg and the face
# index link, inside this very image and for its architecture (arm64 under QEMU): the image's
# counterpart of TestDebianPackageLibraries. Fails the build naming what is missing.
FROM runtime AS check
COPY --from=split /natives /natives
RUN check-libraries /natives

# --- The published image: the runtime stage, and the default target ------------------------------
FROM runtime
