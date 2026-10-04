# The app's version comes from a dart-define

The About dialog shows `String.fromEnvironment('VALBUM_VERSION')`, passed by the release workflow
(`--dart-define=VALBUM_VERSION=…`). Without it no version is shown — `pubspec.yaml`'s `1.0.0` is a
placeholder and never displayed.
