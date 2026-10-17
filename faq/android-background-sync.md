# Android camera-roll sync in the background

Issue #300. Three WorkManager works run the same `runBackgroundSync`: the periodic one, a one-off
with a `MediaStore` content trigger (re-registered by its own run, policy `append`), and the
foreground work the app hands its own runs to. Pitfalls the code does not show:

- **Never decide by `Workmanager.getWorkInfo` for a chained name.** The plugin answers the *first*
  work of the unique name, which for an `append` chain is one that finished long ago. The trigger
  is registered with `keep` (app start) or `replace` (Wi-Fi-only toggled) instead.
- **Every engine has its own `SharedPreferences` cache.** The works run in engines of their own,
  in the app's process; a value one wrote is not seen by another until `SettingsStore.reload()`.
  With a `SyncRelay` the engine reloads before every write of the camera-roll configuration.
- **One run at a time is the `IsolateNameServer`**, process-wide across engines
  (`sync_relay_io.dart`); a claim left by a torn-down engine is detected by a ping and removed.
- **Android 12+ may refuse a foreground work started from the background** (a new photo, app
  closed). The plugin then runs it as an ordinary work (minutes, no notification). A run the app
  starts while it is on the screen always gets its foreground work.
- **`dataSync` is opt-in** in the plugin (`workmanager.enableDataSyncForegroundService=true` in
  `android/gradle.properties`); the Play Console asks for a declaration of that type. Android 15
  limits `dataSync` to six hours a day.
