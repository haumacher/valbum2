# Maven 3 only: `./mvnw`

JavaCPP narrows its natives to one platform through `-Djavacpp.platform=…`, which activates
profiles in the `*-platform` dependency POMs. Under Maven 4 that no longer happens: the "platform JAR"
silently carries the natives of every OS and CPU (about 1 GB instead of 150 MB), and the container
check fails on the foreign libraries. GitHub's runners switched to Maven 4 in October 2026 (the log
shows "Loaded … auto-discovered prefixes"). The workflows therefore build with the Maven Wrapper
pinned to 3.9.16, and the root POM's enforcer refuses Maven 4 with this file's name.
