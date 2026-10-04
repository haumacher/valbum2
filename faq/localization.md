# Localization workflow

Only `valbum_ui/lib/l10n/app_en.arb` is edited by hand. Then:
1. `valbum_ui/android/gradlew -p valbum_ui translateArb` (DeepL; key in `~/.gradle/gradle.properties`)
2. `flutter gen-l10n`

All generated files are committed. Never start a sentence with a placeholder (DeepL capitalises it).
`test/l10n_guard_test.dart` fails on user-facing literals outside the ARB. Engines without a
`BuildContext` return `AppNotice` data, not sentences. Server messages are shown verbatim.
German uses "Sie"; hand corrections in `app_de.arb` survive until the English source changes.
