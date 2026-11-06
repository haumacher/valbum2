# Android camera-roll sync in the background

Issue #300. Four WorkManager works run `runBackgroundSync`; only the **upload** uploads, and it is
an *expedited* one-off with a network constraint. The trigger (MediaStore content trigger, re-armed
by its own run with `append`), the daily periodic catch-up (charging) and the delayed retry only
hand the run to it. The app on the screen hands its runs to the same work. Pitfalls the code does
not show (verified against work-runtime 2.11.2 bytecode and workmanager_android 0.10.8):

- **Android 15 cuts the network of a background app** ("Background network access restrictions",
  all apps): a request outside a valid process lifecycle fails, typically `Failed host lookup`,
  errno 7, while the device says it is connected. Seen on the device 2 s after a foreground upload
  succeeded. What gets the network: an **expedited job** ("bypasses Doze, app standby and battery
  saver network restrictions if the job has a required network", `JobInfo.Builder.setExpedited`;
  AOSP gives the network bypass flags only to expedited and user-initiated jobs) or a foreground
  service, which Android 12+ refuses to start from the background. An ordinary job is not
  enough. Before uploading, a background run looks up the server's name: if that fails while the
  device is connected, the run records "Android blocked the network for the background run" and
  is not retried.
- **A retried or stopped expedited work comes back as an ordinary job.** `SystemJobInfoConverter`
  sets `setExpedited` only for `runAttemptCount == 0` with no delay, and every start raises the
  count. So a server outage is not answered with WorkManager's retry. Instead the retry work, an
  ordinary delayed one-off whose wait doubles from 30 s to at most 5 h (the count is kept in the
  run record), hands over afresh, and that hand-over is expedited again. Expedited works may have
  no constraint but network/storage and no delay.
- **GreedyScheduler runs works in the living process**, without a JobScheduler job (`Starting
  work for …` in logcat). It skips only device-idle and content-trigger works, so an expedited
  work can start there too. If its job then starts, `Processor` joins it to the running work and
  holds the job until the work ends. That is the case Android 15 starves, and why the upload goes
  through an expedited job.
- **Retry vs failure in an `append` chain.** Dart `true` → success, `false` → `Result.retry()`,
  an exception → `Result.failure()`. A retry leaves an appended successor BLOCKED. A failure fails
  every dependent (`iterativelyFailWorkAndDependents`). A retried content-trigger work keeps its
  content trigger, so it waits for the *next* photo rather than its back-off. The plugin passes
  `append` on as `APPEND_OR_REPLACE`, which replaces a failed chain. Every run but the trigger's
  own registers the trigger with `keep`, which WorkManager decides over the whole unique chain.
  That re-registration is what heals a chain a failure took away.
- **Never decide by `Workmanager.getWorkInfo` for a chained name.** The plugin answers the *first*
  work of the unique name, which for an `append` chain finished long ago. That is why the upload is
  `keep` and never `append`: the app follows it by its state.
- **A fired content trigger can be swallowed at process start.** When WorkManager reconciles at a
  cold start (`WM-ForceStopRunnable: Found unfinished work`), it reschedules the trigger under a new
  job id with `CONTENT_TRIGGER` unsatisfied again, and no Dart runs. The photo waits for the next
  photo, the app or the daily catch-up. Dart cannot notice this; noticing it would take native
  code. Every run scans the whole library, never a delta.
- **`update` moves an existing periodic work to a new period.** `WorkerUpdater.updateWorkImpl`
  copies the new spec (interval, constraints) over the old one, keeping its id, state,
  `lastEnqueueTime` and period count. An install registered every 15 min moves to once a day at the
  next app start.
- **Every engine has its own `SharedPreferences` cache.** The works run in engines of their own,
  in the app's process; a value one wrote is not seen by another until `SettingsStore.reload()`.
  With a `SyncRelay` the engine reloads before every write of the camera-roll configuration.
- **One run at a time is the `IsolateNameServer`**, process-wide across engines
  (`sync_relay_io.dart`); a claim left by a torn-down engine is detected by a ping and removed.
- **`dataSync` is opt-in** in the plugin (`workmanager.enableDataSyncForegroundService=true` in
  `android/gradle.properties`); the Play Console asks for a declaration of that type. Android 15
  limits `dataSync` to six hours a day. An expedited job alone is sure of about one minute; the
  upload asks for the `dataSync` foreground work to go on longer, which works from the app on the
  screen. From the background, a long upload may be stopped and continues with the next run.
