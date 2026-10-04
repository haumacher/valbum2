# Flutter test notes

- Run with `TZ=UTC` (CI does); never assert a literal clock time.
- Browser-only tests (`browser_*_test.dart`) need `flutter test --platform chrome <file>`; the VM run skips them.
- `kIsWeb` is false under the test binding — web-only paths go through injectable seams
  (`isWeb`, `browserMenu`, `downloadSaver`, `mediaPicker`, …).
