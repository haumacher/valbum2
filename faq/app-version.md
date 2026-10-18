# The app's version comes from the build

The About dialog shows `String.fromEnvironment('VALBUM_VERSION')`, passed by the release workflow
(`--dart-define=VALBUM_VERSION=…`). Without it no version is shown — `pubspec.yaml`'s `1.0.0` is a
placeholder and never displayed there.

The diagnostics header and the status page show `appVersion` (`diagnostics.dart`): the Flutter
tool's `FLUTTER_BUILD_NAME`/`FLUTTER_BUILD_NUMBER` defines (`--build-name`/`--build-number` of the
APK release), or `VALBUM_VERSION` alone where the tool filled the build name from `pubspec.yaml`
(the web release). Only a build defining nothing (a test) shows the pubspec's `1.0.0+1`. Never
hand-keep a version constant: the 2.15.1 APK said "App: 1.0.0+1".
