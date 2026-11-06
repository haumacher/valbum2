/// Camera-roll sync while the app is closed (issue #32).
///
/// The foreground engine ([CameraRollSync]) only runs while somebody looks at
/// the app. The platform can do better: Android's `WorkManager` and iOS'
/// `BGTaskScheduler` start the app's Dart code every so often, without a
/// screen. This library is that second entry into the same sync:
///
///  * [BackgroundScheduler] is the platform's side of it — register the
///    periodic task, cancel it, or say why there is none. The implementation
///    is chosen by the conditional import in `platform.dart`, exactly as the
///    photo library is: a browser and a desktop get
///    [UnavailableBackgroundScheduler] and say so, a phone gets the
///    `workmanager` one (see `background_workmanager.dart`).
///  * [backgroundSyncDispatcher] is what the platform starts. It is a
///    top-level function marked as a VM entry point, because the background
///    isolate has no widget tree to find anything in — everything it needs it
///    rebuilds from the persisted [SettingsStore].
///  * [runBackgroundSync] is that rebuilding, as a plain function: store →
///    [ServerSettings] → [VAlbumClient] → [PhotoLibrary] → [CameraRollSync] →
///    one run. A test calls it with fakes; the dispatcher only wraps it.
///
/// On Android there are four works, all of them running [runBackgroundSync]
/// (issue #300), and only one of them uploads: [backgroundUploadTaskName], an
/// *expedited* work with a network constraint — the one kind of background
/// run Android grants the network to when the app is not on the screen
/// (Android 15 cuts it otherwise), see `background_workmanager.dart`. The
/// app on the screen hands its own runs to it, and so do the three others,
/// which upload nothing themselves ([BackgroundScheduler.requestUpload]):
///
///  * [backgroundTriggerTaskName], which the platform starts a few seconds
///    after the photo library changed — a photo was just taken. It cannot be
///    expedited itself: a content trigger is a constraint an expedited work
///    may not have;
///  * [backgroundSyncTaskName], the periodic one — once a day, while the
///    device charges, the catch-up of whatever the others missed;
///  * [backgroundRetryTaskName], a delayed hand-over after a run that could
///    not reach the server, with a wait that doubles with every attempt.
///
/// The runs meet in one [SyncRelay], so that only one of them uploads at a
/// time and the app shows what the one that does is doing. Every run but the
/// trigger's own makes sure a trigger waits for the next photo
/// ([BackgroundScheduler.ensureTrigger]): a trigger that failed takes the one
/// it registered behind itself along, and only a later run can register it
/// again. iOS has the periodic task alone, which uploads itself.
///
/// A background run refuses for exactly the same reasons the foreground one
/// does — switched off, no server, signed out, a guest with no space of their
/// own (issue #54) — and it creates the inbox album the same way a foreground
/// run does, because it is the same run. It says so in the
/// store instead of on a screen nobody is looking at: [BackgroundRunRecord] is
/// what the settings section shows afterwards.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'caller.dart';
import 'camera_roll.dart';
import 'locales.dart';
import 'notices.dart';
import 'client.dart';
import 'connectivity.dart';
import 'diagnostics.dart';
import 'hash_cache.dart';
import 'photo_library.dart';
import 'platform.dart';
import 'settings.dart';
import 'sync_notifier.dart';
import 'sync_relay.dart';

/// The identity of the periodic task, on Android its unique name and on iOS
/// the `BGTaskScheduler` identifier registered in `Info.plist`.
///
/// It is a constant of this app, not a generated value: the platform keeps a
/// task registered across app restarts and updates, and a name that changed
/// would leave the old one running forever.
const String backgroundSyncTaskName = "de.haumacher.valbum.cameraRollSync";

/// The identity of the work the platform starts when the photo library
/// changed (issue #300); Android only.
///
/// One-off by nature — a work with a content trigger runs once — so every run
/// of it registers the next one, see [BackgroundScheduler.rearmTrigger].
const String backgroundTriggerTaskName =
    "de.haumacher.valbum.cameraRollTrigger";

/// The identity of the foreground work the app on the screen hands its runs
/// to (issue #300); Android only, see [SyncHandover].
const String backgroundUploadTaskName = "de.haumacher.valbum.cameraRollUpload";

/// The identity of the work that hands a run to [backgroundUploadTaskName]
/// again after one could not reach the server (issue #300); Android only,
/// see [BackgroundScheduler.scheduleRetry].
///
/// A work of its own rather than WorkManager's retry of the upload: a
/// retried expedited work is registered again as an ordinary one, which
/// Android 15 does not grant the network; a fresh hand-over is expedited
/// again.
const String backgroundRetryTaskName = "de.haumacher.valbum.cameraRollRetry";

/// Whether the platform runs [task] as a foreground work, with a notification
/// of its own, see `background_workmanager.dart`.
///
/// Only the upload is one: the other works hand their run over to it in a
/// moment, see [BackgroundScheduler.requestUpload].
bool foregroundSyncTask(String task) => task == backgroundUploadTaskName;

/// The id of the notification of a run of [task].
///
/// One per work, so that two works the platform has in the foreground at the
/// same time do not take each other's notification away.
int syncNotificationIdOf(String task) => switch (task) {
      backgroundUploadTaskName => 3001,
      backgroundTriggerTaskName => 3002,
      _ => 3003,
    };

/// How long a background run waits for another run of the process to end
/// before it leaves the photos to that one, see [SyncRelay.claim].
///
/// The run the app handed over waits long: the app is waiting for it. The
/// others give way soon — the run that holds the claim scans again before it
/// ends, see [CameraRollSync.runOnce].
Duration claimPatienceOf(String task) => task == backgroundUploadTaskName
    ? const Duration(minutes: 10)
    : const Duration(seconds: 30);

/// The scans a background run makes at most, see [CameraRollSync.runOnce].
const int backgroundPasses = 3;

/// How often iOS is asked to run the sync.
///
/// iOS ignores the value and decides for itself; Android has
/// [androidCatchUpInterval] instead.
const Duration backgroundSyncInterval = Duration(minutes: 15);

/// How often Android runs the periodic catch-up (issue #300).
///
/// A new photo starts its own run ([backgroundTriggerTaskName]); what the
/// periodic work still finds is what a trigger missed, and waking the device
/// every quarter of an hour of a night for that is not worth it. Once a day,
/// while the device charges.
const Duration androidCatchUpInterval = Duration(days: 1);

/// The wait of [backgroundRetryTaskName] after the [attempt]th run in a row
/// that could not reach the server: half a minute, doubled with every
/// attempt, at most five hours (where WorkManager caps its own back-off).
Duration backgroundRetryDelay(int attempt) {
  const first = Duration(seconds: 30);
  const most = Duration(hours: 5);
  var factor = 1 << (attempt < 1 ? 0 : (attempt > 10 ? 10 : attempt - 1));
  var delay = first * factor;
  return delay > most ? most : delay;
}

/// What network the platform must see before it starts a background run.
///
/// The Wi-Fi-only switch of the sync ([CameraRollConfig.wifiOnly]) is not only
/// a rule the run itself obeys — it is a constraint the platform can hold the
/// wake-up back for, which saves starting the app just to refuse (issue #43).
enum BackgroundNetwork {
  /// Any network will do; a metered one included.
  connected,

  /// Only an unmetered network — what the Wi-Fi-only switch asks for.
  unmetered;

  /// The requirement of a sync configured with [wifiOnly].
  static BackgroundNetwork of(bool wifiOnly) =>
      wifiOnly ? unmetered : connected;
}

/// The platform's periodic background execution, behind an interface.
///
/// Nothing here runs a sync — this only arranges *that* one is run while the
/// app is closed, see [runBackgroundSync].
abstract class BackgroundScheduler {
  const BackgroundScheduler();

  /// Whether this platform can run the sync while the app is closed.
  ///
  /// `false` makes the settings section say so, rather than promising a
  /// background sync that would never happen.
  bool get available;

  /// Why there is no background sync, shown to the user while [available] is
  /// `false`; `null` where there is nothing to explain.
  ///
  /// The reason as data, never as words: the section showing it turns it into
  /// a sentence in the language of the device, see [noticeText] (issue #108).
  AppNotice? get unavailableReason;

  /// Registers the periodic task, updating the one that is there.
  ///
  /// Called whenever the sync is switched on, whenever the Wi-Fi-only switch
  /// is toggled while it is on, and once at every app start with a
  /// switched-on configuration, so that an updated app registers the task
  /// again — it must therefore be idempotent, and re-registering with another
  /// [network] must replace the constraint instead of adding a second task.
  ///
  /// [network] is what the caller wants of the platform, not what it reads
  /// from a configuration: the scheduler knows nothing about the sync, and a
  /// test can therefore state the requirement it expects.
  ///
  /// [changed] says that the requirement was just changed — the Wi-Fi-only
  /// switch was toggled — rather than registered again as it was: a work
  /// that waits for a change of the photo library is then replaced, which a
  /// start of the app leaves alone (issue #300).
  Future<void> schedule(BackgroundNetwork network, {bool changed = false});

  /// Removes the periodic task; the app stops syncing while it is closed.
  ///
  /// On Android also the work started by a change of the photo library and
  /// the run the app handed over (issue #300).
  Future<void> cancel();

  /// Registers the next run on a change of the photo library, after the one
  /// that is running (issue #300).
  ///
  /// Called by that run itself, from its background isolate: a work with a
  /// content trigger runs once. A platform without one does nothing.
  Future<void> rearmTrigger(BackgroundNetwork network) async {}

  /// Registers the run on a change of the photo library where none waits,
  /// leaving one that waits or runs as it is (issue #300).
  ///
  /// Called by every background run that is not the trigger's own, from its
  /// background isolate, so that a trigger lost to a failed run is back with
  /// the next run of any work. A platform without one does nothing.
  Future<void> ensureTrigger(BackgroundNetwork network) async {}

  /// Hands a run to the upload work ([backgroundUploadTaskName]), the one
  /// background run that is granted the network (issue #300); `false` where
  /// this platform has none, and the caller uploads itself.
  ///
  /// An upload that waits or runs already is the one asked for. Called from
  /// a background isolate.
  Future<bool> requestUpload(BackgroundNetwork network) async => false;

  /// Hands a run to the upload work again after [delay], because the last
  /// one could not reach the server, see [backgroundRetryTaskName] (issue
  /// #300).
  ///
  /// A retry that waits already is the one asked for. A platform without one
  /// does nothing: its next periodic run is the retry.
  Future<void> scheduleRetry(BackgroundNetwork network,
      {required Duration delay}) async {}
}

/// Where the app on the screen hands its runs to, so that switching the
/// screen off does not stop them (issue #300).
///
/// On Android a foreground work ([backgroundUploadTaskName]): the platform
/// does not freeze an app that runs one, and keeps the device awake for it.
/// The run reports through the [SyncRelay], and [CameraRollSync] shows what it
/// reports.
abstract class SyncHandover {
  const SyncHandover();

  /// The task name the handed-over run carries over the relay.
  String get task;

  /// Asks the platform to carry out a run now; `false` where it refused, and
  /// the app carries it out itself.
  ///
  /// A run that is asked for while one is waiting or going on is that one.
  Future<bool> request();

  /// Whether the run asked for is still to come or going on.
  Future<bool> pending();

  /// Drops the run asked for.
  Future<void> cancel();
}

/// A [SyncHandover] that records what it was asked, used by tests.
///
/// [onRequest] is the platform: a test lets it start the run it stands for —
/// [runBackgroundSync] with the same relay — or nothing at all.
class FakeSyncHandover extends SyncHandover {
  @override
  final String task;

  /// Whether [request] is accepted.
  bool accepts;

  /// What [pending] answers: from an accepted [request] until the run
  /// [onRequest] started is over.
  bool isPending = false;

  /// The number of [request] and [cancel] calls.
  int requests = 0;
  int cancelled = 0;

  /// What the platform does when it accepts a request: the run it starts;
  /// `null` for a work that never runs.
  Future<void> Function()? onRequest;

  FakeSyncHandover({
    this.task = backgroundUploadTaskName,
    this.accepts = true,
    this.onRequest,
  });

  @override
  Future<bool> request() async {
    requests++;
    if (!accepts) {
      return false;
    }
    isPending = true;
    var run = onRequest?.call();
    if (run != null) {
      unawaited(run.whenComplete(() => isPending = false));
    }
    return true;
  }

  @override
  Future<bool> pending() async => isPending;

  @override
  Future<void> cancel() async {
    cancelled++;
    isPending = false;
  }
}

/// The [BackgroundScheduler] of a platform that has no background execution.
///
/// The web build and the desktop builds get this: a browser tab that is closed
/// is gone, and a desktop app has no periodic work API this app uses. Saying
/// so plainly is better than a promise nothing keeps.
class UnavailableBackgroundScheduler extends BackgroundScheduler {
  @override
  final AppNotice? unavailableReason;

  const UnavailableBackgroundScheduler([
    this.unavailableReason = const NoBackgroundSyncHere(),
  ]);

  @override
  bool get available => false;

  @override
  Future<void> schedule(BackgroundNetwork network,
      {bool changed = false}) async {}

  @override
  Future<void> cancel() async {}
}

/// A [BackgroundScheduler] that only records what it was asked, used by tests.
///
/// Lives in `lib/` for the same reason [FakePhotoLibrary] does: an embedder
/// driving this app's engine itself needs it as much as a test does.
class FakeBackgroundScheduler extends BackgroundScheduler {
  @override
  final bool available;

  @override
  final AppNotice? unavailableReason;

  /// The network requirement of every [schedule] call, in order.
  final List<BackgroundNetwork> requests = [];

  /// The number of times [schedule] was called.
  int get scheduled => requests.length;

  /// What the last [schedule] call asked for, `null` before the first one.
  BackgroundNetwork? get lastNetwork => requests.isEmpty ? null : requests.last;

  /// The number of times [cancel] was called.
  int cancelled = 0;

  /// Whether each [schedule] call said the requirement changed, in order.
  final List<bool> changes = [];

  /// The network requirement of every [rearmTrigger] call, in order.
  final List<BackgroundNetwork> rearms = [];

  /// The network requirement of every [ensureTrigger] call, in order.
  final List<BackgroundNetwork> ensures = [];

  /// The network requirement of every [scheduleRetry] call, in order.
  final List<BackgroundNetwork> retries = [];

  /// The delay of every [scheduleRetry] call, in order.
  final List<Duration> retryDelays = [];

  /// The network requirement of every [requestUpload] call, in order.
  final List<BackgroundNetwork> uploads = [];

  /// Whether [requestUpload] is accepted; `false` stands for a platform
  /// without an upload work, where the run uploads itself.
  bool acceptsUploads;

  /// What every call throws, `null` while the platform is happy.
  Object? problem;

  FakeBackgroundScheduler({
    this.available = true,
    this.unavailableReason = const NoBackgroundSyncInTest(),
    this.problem,
    this.acceptsUploads = false,
  });

  @override
  Future<void> schedule(BackgroundNetwork network,
      {bool changed = false}) async {
    requests.add(network);
    changes.add(changed);
    var failure = problem;
    if (failure != null) {
      throw failure;
    }
  }

  @override
  Future<void> cancel() async {
    cancelled++;
    var failure = problem;
    if (failure != null) {
      throw failure;
    }
  }

  @override
  Future<void> rearmTrigger(BackgroundNetwork network) async {
    rearms.add(network);
  }

  @override
  Future<void> ensureTrigger(BackgroundNetwork network) async {
    ensures.add(network);
  }

  @override
  Future<bool> requestUpload(BackgroundNetwork network) async {
    uploads.add(network);
    return acceptsUploads;
  }

  @override
  Future<void> scheduleRetry(BackgroundNetwork network,
      {required Duration delay}) async {
    retries.add(network);
    retryDelays.add(delay);
  }
}

/// What one background run did, as the settings screen shows it afterwards.
///
/// Persisted as one JSON blob under its own key, see [BackgroundRunStorage].
/// The shape carries a [version] and every reader ignores what it does not
/// know, exactly as [CameraRollConfig] does: a report is never a reason to
/// fail, and a blob this version cannot read is treated as absent.
@immutable
class BackgroundRunRecord {
  /// The version of this shape, so that a later one is recognisable.
  static const int currentVersion = 1;

  /// When the run finished.
  final DateTime at;

  /// Whether the run did what it set out to do.
  final bool ok;

  /// How many items were uploaded, and how many the server already held.
  final int stored;
  final int present;

  /// Why the run failed, `null` while [ok].
  final String? message;

  /// How many runs in a row, this one included, could not reach the server
  /// (issue #300): what the wait of the next retry doubles with, see
  /// [backgroundRetryDelay]. `0` for every other end.
  final int retries;

  const BackgroundRunRecord({
    required this.at,
    required this.ok,
    this.stored = 0,
    this.present = 0,
    this.message,
    this.retries = 0,
  });

  /// A record of a run that failed with the given reason.
  factory BackgroundRunRecord.failed(DateTime at, String message) =>
      BackgroundRunRecord(at: at, ok: false, message: message);

  /// This record as the JSON blob the store keeps.
  String toJson() => jsonEncode({
        "version": currentVersion,
        "at": at.toUtc().toIso8601String(),
        "ok": ok,
        "stored": stored,
        "present": present,
        if (message != null) "message": message,
        if (retries > 0) "retries": retries,
      });

  /// The record stored as [text], `null` if there is none or it cannot be
  /// read.
  static BackgroundRunRecord? parse(String? text) {
    if (text == null || text.trim().isEmpty) {
      return null;
    }
    try {
      var json = jsonDecode(text);
      if (json is! Map) {
        return null;
      }
      var at = DateTime.tryParse("${json["at"]}");
      if (at == null) {
        return null;
      }
      var message = json["message"];
      return BackgroundRunRecord(
        at: at,
        ok: json["ok"] == true,
        stored: json["stored"] is int ? json["stored"] as int : 0,
        present: json["present"] is int ? json["present"] as int : 0,
        message: message is String ? message : null,
        retries: json["retries"] is int ? json["retries"] as int : 0,
      );
    } catch (_) {
      // Silent on purpose: a report that cannot be read is no report — the
      // section shows none, and the next run writes a new one.
      return null;
    }
  }

  /// The time of day this run happened, as the settings section shows it.
  String get stamp {
    var local = at.toLocal();
    String two(int value) => value.toString().padLeft(2, "0");
    return "${two(local.hour)}:${two(local.minute)}";
  }

  @override
  bool operator ==(Object other) =>
      other is BackgroundRunRecord &&
      other.at == at &&
      other.ok == ok &&
      other.stored == stored &&
      other.present == present &&
      other.message == message &&
      other.retries == retries;

  @override
  int get hashCode => Object.hash(at, ok, stored, present, message, retries);

  @override
  String toString() => toJson();
}

/// Typed access to the background-run report of a [SettingsStore].
extension BackgroundRunStorage on SettingsStore {
  /// What the last background run did, `null` if none ever ran.
  Future<BackgroundRunRecord?> loadBackgroundRunRecord() async =>
      BackgroundRunRecord.parse(await loadBackgroundRun());

  /// Records what a background run did.
  Future<void> saveBackgroundRunRecord(BackgroundRunRecord record) =>
      saveBackgroundRun(record.toJson());
}

/// What [runBackgroundSync] did.
@immutable
class BackgroundRunResult {
  /// What was recorded in the store, `null` when nothing was.
  ///
  /// A run that was not due — the sync is switched off — records nothing: the
  /// report of the last real run must not be overwritten by "we did not look".
  final BackgroundRunRecord? record;

  /// Why nothing ran, `null` when a run was attempted.
  ///
  /// A plain sentence, because it is written into the persisted report the
  /// settings screen reads back: a background run has no widget tree, so it
  /// words its own reasons with [platformMessages].
  final String? skipped;

  /// Whether the run could not reach the server for a reason that passes on
  /// its own — no network, the server away, a timeout — see
  /// [transientNotice] (issue #300), so that its retry was asked for.
  ///
  /// A refusal no retry cures (switched off, no server, not signed in, no
  /// access to the photos, Wi-Fi-only on a mobile connection, which the
  /// constraint of the work covers already) is not, nor is a network Android
  /// blocked ([blocked]): a retry would be blocked alike.
  final bool transient;

  /// Whether Android refused this run the network: the device is connected,
  /// and the name of the server cannot be looked up (issue #300).
  final bool blocked;

  /// Whether the run was handed to the upload work instead of uploading
  /// itself, see [BackgroundScheduler.requestUpload].
  final bool handedOver;

  const BackgroundRunResult({
    this.record,
    this.skipped,
    this.transient = false,
    this.blocked = false,
    this.handedOver = false,
  });

  /// Whether a sync was attempted at all.
  bool get ran => skipped == null;

  /// Whether a sync ran and succeeded.
  bool get ok => record?.ok ?? false;

  @override
  String toString() => skipped ?? "$record";
}

/// Whether [notice], the reason a run ended with, passes on its own, so that
/// the platform retries the run with a back-off (issue #300).
///
/// Only the reasons of a server that was not reached: the server's own
/// answer (a sentence, not a notice) is a decision the next periodic run
/// asks again, and every other notice is a refusal a retry does not cure.
bool transientNotice(AppNotice? notice) =>
    notice is ServerOffline ||
    notice is ServerUnreachable ||
    notice is NoNetworkForSync;

/// Looks the name [host] up, as a run does before it uploads; the error of
/// the lookup, `null` where the name resolves. See [runBackgroundSync].
typedef HostLookup = Future<Object?> Function(String host);

/// Runs one camera-roll sync from nothing but the persisted store.
///
/// This is what a background run *is*: the app is not running, so the server
/// URL, the device token, the inbox album and the watermark are read from
/// [store] and a whole engine is built around them for the length of one run.
/// The refusals are the ones of the foreground engine, because it is the same
/// engine — switched off, no server configured, no inbox, signed out, offline —
/// and each of them is written back as a [BackgroundRunRecord] the settings
/// screen shows the next time somebody opens it.
///
/// Every moving part can be replaced, so that a test never touches the device:
/// [library] instead of the platform's photo library, [transport] instead of a
/// real HTTP client, [connectivity] instead of the device's network,
/// [clock] instead of the wall clock, [hashCache] instead of the file of
/// remembered hashes.
///
/// [task] is the work the platform runs (issue #300): the run of
/// [backgroundTriggerTaskName] registers the next one first, with [scheduler],
/// and every other run makes sure one waits
/// ([BackgroundScheduler.ensureTrigger]); every run but the upload's own hands
/// the upload to that work where [scheduler] has one
/// ([BackgroundScheduler.requestUpload]) and is done; a run that could not
/// reach the server registers the retry ([BackgroundScheduler.scheduleRetry]);
/// with a [relay] the run waits its turn behind another run of the process
/// and reports to the app on the screen; [notifier] shows its progress while
/// it uploads.
///
/// Before it asks the server anything, a run looks the name of the server up
/// with [lookup] — by default the platform's, against the real transport only
/// — and where that fails while [connectivity] says the device is connected,
/// it records that Android blocked the network for it, rather than "the
/// server cannot be reached" (issue #300). It is not retried: a retry would
/// be blocked alike.
///
/// Every failure is written into [log]: the platform's run hands it a log
/// over the store every engine of the app shares (see
/// [backgroundSyncDispatcher] and [defaultDiagnosticsStore]), so that the app
/// on the screen shows what went wrong here; without one the log is in
/// memory, and a test touches no file. The run waits for the log to be
/// written before it answers: the isolate is torn down after.
Future<BackgroundRunResult> runBackgroundSync({
  SettingsStore store = const PreferencesSettingsStore(),
  PhotoLibrary? library,
  http.Client? transport,
  ConnectivitySource? connectivity,
  DateTime Function()? clock,
  HashCache? hashCache,
  String task = backgroundSyncTaskName,
  SyncRelay? relay,
  SyncNotifier notifier = const NoSyncNotifier(),
  BackgroundScheduler? scheduler,
  DiagnosticsLog? log,
  HostLookup? lookup,
}) async {
  var now = clock ?? DateTime.now;
  var diagnostics = log ?? DiagnosticsLog(now: now);
  try {
    return await _runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      connectivity: connectivity,
      now: now,
      hashCache: hashCache,
      task: task,
      relay: relay,
      notifier: notifier,
      scheduler: scheduler,
      log: diagnostics,
      lookup: lookup ?? (transport == null ? lookUpHost : null),
    );
  } finally {
    await diagnostics.flush();
  }
}

/// [runBackgroundSync], writing into [log].
Future<BackgroundRunResult> _runBackgroundSync({
  required SettingsStore store,
  required PhotoLibrary? library,
  required http.Client? transport,
  required ConnectivitySource? connectivity,
  required DateTime Function() now,
  required HashCache? hashCache,
  required String task,
  required SyncRelay? relay,
  required SyncNotifier notifier,
  required BackgroundScheduler? scheduler,
  required DiagnosticsLog log,
  required HostLookup? lookup,
}) async {
  var config = await store.loadCameraRollConfig(log: log);
  if (!config.enabled) {
    // Not a failure and not a report: the user switched the sync off, and the
    // task is on its way out (see [BackgroundScheduler.cancel]).
    return BackgroundRunResult(
      skipped: platformMessages.backgroundSyncOff,
    );
  }

  var requirement = BackgroundNetwork.of(config.wifiOnly);
  if (task == backgroundTriggerTaskName && scheduler != null) {
    // First, not last: a run the platform cuts off must not take the next
    // trigger with it. Registered behind this one, it observes the library
    // from the moment this run is over; what changes meanwhile, the passes of
    // the upload pick up — a photo after its last pass waits for the next
    // trigger, the app or the daily catch-up.
    try {
      await scheduler.rearmTrigger(requirement);
    } catch (error) {
      if (kDebugMode) {
        print("background sync task: no next trigger !! $error");
      }
      // The run goes on; the next photo starts no run until the app is
      // opened or another background run registers the trigger again, see
      // [BackgroundScheduler.ensureTrigger].
      log.record(
        "Could not register the next background trigger: a new photo starts "
        "no camera-roll run until the app or another background run "
        "registers it",
        facts: [platformErrorFact(error), "Run: $task"],
        cause: failureCause(
          attempt: "background trigger",
          platformError: errorCause(error),
        ),
        window: syncFailureMergeWindow,
      );
    }
  } else if (scheduler != null) {
    // A trigger run that failed took the trigger it registered behind itself
    // along; `keep` registers one where none waits, and leaves the chain
    // that waits or runs as it is — decided by the platform over every work
    // of the name, which asking the plugin for its state is not.
    try {
      await scheduler.ensureTrigger(requirement);
    } catch (error) {
      log.record(
        "Could not make sure a new photo starts a background run",
        facts: [platformErrorFact(error), "Run: $task"],
        cause: failureCause(
          attempt: "background trigger",
          platformError: errorCause(error),
        ),
        window: syncFailureMergeWindow,
      );
    }
  }

  if (task != backgroundUploadTaskName && scheduler != null) {
    // Android: the upload is the work the platform grants the network to;
    // this run only hands it over. Where that fails, the run uploads itself,
    // which is better than nothing.
    try {
      if (await scheduler.requestUpload(requirement)) {
        return BackgroundRunResult(
          skipped: platformMessages.backgroundSyncHandedOver,
          handedOver: true,
        );
      }
    } catch (error) {
      log.record(
        "Could not hand the background run to the upload work: it uploads "
        "itself",
        facts: [platformErrorFact(error), "Run: $task"],
        cause: failureCause(
          attempt: "background upload",
          platformError: errorCause(error),
        ),
        window: syncFailureMergeWindow,
      );
    }
  }

  Future<BackgroundRunResult> record(BackgroundRunRecord entry) async {
    await store.saveBackgroundRunRecord(entry);
    return BackgroundRunResult(record: entry);
  }

  var settings = ServerSettings(store: store);
  await settings.load();
  var dataUrl = settings.dataUrl;
  if (dataUrl == null) {
    logSyncFailure(
      log,
      notice: const NoServerConfigured(),
      task: task,
      outcome: "not retried until a server is set up in the app",
    );
    return record(
      BackgroundRunRecord.failed(
        now(),
        noticeText(const NoServerConfigured(), platformMessages),
      ),
    );
  }

  // No offline cache and no offline state: a background run reads nothing it
  // could show, and a cached answer would only hide that the server is away.
  var ownTransport = transport == null ? http.Client() : null;
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: settings.token,
    httpClient: transport ?? ownTransport!,
    // Every request that fails is written, as in the app.
    log: log,
  );

  // A background run has no Activity to ask for a permission with (issue
  // #169): it reads what the app on the screen was granted, and declines
  // where that is not enough rather than failing or uploading redacted
  // copies.
  var ownLibrary =
      library == null ? defaultPhotoLibrary(background: true) : null;
  var photos = library ?? ownLibrary!;

  // The Wi-Fi-only setting holds for a background run exactly as it does for
  // a foreground one (issue #36): the platform's "a network is connected"
  // constraint says nothing about *which* network, so the run asks.
  var ownNetwork = connectivity == null ? defaultConnectivity(log: log) : null;
  var network = connectivity ?? ownNetwork!;

  // Android 15 cuts the network of an app that is not on the screen unless
  // the run is one it grants it to (issue #300); the device then says it is
  // connected and every name fails to resolve. Asked before the server is,
  // so that the report names that cause and not "the server is away".
  var host = Uri.tryParse(dataUrl)?.host ?? "";
  if (lookup != null && host.isNotEmpty) {
    var kind = await network.current();
    var problem = kind == NetworkKind.none ? null : await lookup(host);
    if (problem != null) {
      var said = platformMessages.backgroundNetworkBlocked;
      log.record(
        "$said: the device is connected (${kind.name}), and the name of the "
        "server could not be looked up",
        facts: [platformErrorFact(problem), "Host: $host", "Run: $task"],
        cause: failureCause(
          attempt: "background network",
          platformError: errorCause(problem),
        ),
        window: syncFailureMergeWindow,
      );
      ownNetwork?.dispose();
      ownLibrary?.dispose();
      ownTransport?.close();
      var entry = BackgroundRunRecord.failed(now(), said);
      await store.saveBackgroundRunRecord(entry);
      return BackgroundRunResult(record: entry, blocked: true);
    }
  }

  var sync = CameraRollSync(
    store: store,
    library: photos,
    clientOf: () => client,
    // The app asks who it is once per client and hands the answer to the
    // sync; a background isolate has no app around it, so the run asks the
    // server itself — one small question before a transfer of photos, and the
    // only way a demoted device learns that it has no space any more, see
    // [CameraRollSync.callerOf] (issue #54) — and where the inbox is (issue
    // #226). A server that cannot be asked is the run's reason, told as the
    // transport told it.
    callerOf: () async => CallerInfo.of(await client.authInfo()),
    connectivity: network,
    clock: now,
    // The same file the app on the screen keeps (issue #299): a photo the
    // app hashed is not hashed again in the background, nor the reverse.
    hashCache: hashCache ?? defaultHashCache(log: log),
    relay: relay,
    runTask: task,
    claimPatience: claimPatienceOf(task),
    log: log,
  );

  // The notification says how many photos are still to go (issue #300); a
  // run that uploads nothing leaves the one the platform shows for a
  // foreground work as it is.
  var notificationId = syncNotificationIdOf(task);
  var messages = platformMessages;
  var channelName = messages.syncNotificationChannel;
  SyncNotice? shown;
  void notify() {
    var notice = syncNoticeOf(sync.status, messages);
    if (notice != null && notice != shown) {
      shown = notice;
      unawaited(
          notifier.show(notificationId, notice, channelName: channelName));
    }
  }

  sync.addListener(notify);
  try {
    await sync.load();
    // More than one pass only where a change of the library starts the run
    // (Android): iOS keeps its one scan in the little time it grants.
    var status =
        await sync.runOnce(passes: relay == null ? 1 : backgroundPasses);
    if (sync.claimMissed) {
      // Not a report either: the run that held the claim reports its own.
      return BackgroundRunResult(skipped: messages.backgroundSyncBusy);
    }
    var transient =
        status.phase != CameraRollPhase.idle && transientNotice(status.notice);
    // How many runs in a row could not reach the server: the wait of the
    // retry doubles with it. Read afresh, another engine wrote the last one.
    var retries = 0;
    if (transient) {
      await store.reload();
      retries = ((await store.loadBackgroundRunRecord())?.retries ?? 0) + 1;
    }
    if (transient && scheduler != null) {
      // Not WorkManager's retry of this work: that one comes back as an
      // ordinary work, which is not granted the network; a fresh hand-over
      // is expedited again, see [backgroundRetryTaskName].
      try {
        await scheduler.scheduleRetry(requirement,
            delay: backgroundRetryDelay(retries));
      } catch (error) {
        log.record(
          "Could not register the retry of a background run: the photos wait "
          "for the next run",
          facts: [platformErrorFact(error), "Run: $task"],
          cause: failureCause(
            attempt: "background retry",
            platformError: errorCause(error),
          ),
          window: syncFailureMergeWindow,
        );
      }
    }
    var entry = switch (status.phase) {
      CameraRollPhase.idle => BackgroundRunRecord(
          at: status.lastSuccess ?? now(),
          ok: true,
          stored: status.lastStored,
          present: status.lastPresent,
        ),
      _ => BackgroundRunRecord(
          at: now(),
          ok: false,
          message: reasonOfStatus(status),
          retries: retries,
        ),
    };
    await store.saveBackgroundRunRecord(entry);
    return BackgroundRunResult(record: entry, transient: transient);
  } catch (error, stack) {
    // Told as the report the section shows tells it.
    logSyncFailure(
      log,
      message: platformErrorText(error),
      error: error,
      task: task,
      outcome: "retried by the next background run",
      more: stackFacts(stack),
    );
    return await record(
        BackgroundRunRecord.failed(now(), platformErrorText(error)));
  } finally {
    sync.removeListener(notify);
    if (shown != null) {
      // A foreground work's notification goes with the work; one posted for
      // a run that was none is taken away here.
      await notifier.clear(notificationId);
    }
    sync.dispose();
    ownLibrary?.dispose();
    ownNetwork?.dispose();
    ownTransport?.close();
  }
}

/// The entry point the platform starts this app at for a background run.
///
/// Marked as a VM entry point because the platform reaches it by name in a
/// fresh isolate: nothing in the app calls it, so tree shaking would otherwise
/// drop it.
///
/// The plugin maps the answer onto WorkManager: `true` is "success", `false`
/// is "retry" with the back-off of the work, and an exception is "failure",
/// which fails the works chained behind this one as well (issue #300). Every
/// run that ends is "success" — a refused one too: "this device is not
/// signed in" is not something the platform can retry better than the next
/// run does, and a server that could not be reached is retried by a fresh
/// hand-over ([BackgroundScheduler.scheduleRetry]), because WorkManager's own
/// retry of an expedited work is an ordinary work without the network.
/// `false` is answered only when the task itself threw — then something is
/// wrong with this code, not with the sync — so that the chain of triggers
/// survives it: a failed trigger would take its successor along.
@pragma('vm:entry-point')
void backgroundSyncDispatcher() {
  executeBackgroundTask((task) async {
    // One log for the whole task, over the store the app reads (issue #300).
    var log = DiagnosticsLog(store: defaultDiagnosticsStore());
    try {
      await runBackgroundSync(
        task: task,
        relay: defaultSyncRelay(),
        notifier: defaultSyncNotifier(log: log),
        scheduler: defaultBackgroundScheduler(),
        log: log,
      );
      return true;
    } catch (error, stack) {
      if (kDebugMode) {
        print("background sync task !! $error");
      }
      logBackgroundTaskFailure(log, task, error, stack);
      await log.flush();
      return false;
    }
  });
}

/// Writes into [log] that the background [task] itself threw [error] — not a
/// refusal of the run, but something wrong with this code — with the top of
/// its [stack].
void logBackgroundTaskFailure(
  DiagnosticsLog log,
  String task,
  Object error,
  StackTrace stack,
) {
  log.record(
    "The background task '$task' failed: ${platformErrorText(error)}",
    facts: [platformErrorFact(error), ...stackFacts(stack)],
    cause: failureCause(
      attempt: "background task $task",
      platformError: errorCause(error),
    ),
    window: syncFailureMergeWindow,
  );
}

/// The top of [stack] as lines of an entry: where an unexpected failure was
/// thrown is what a bug report needs of it.
List<String> stackFacts(StackTrace stack, {int frames = 6}) {
  var lines = [
    for (var line in "$stack".split("\n"))
      if (line.trim().isNotEmpty) line.trim()
  ];
  return [
    for (var line in lines.take(frames)) "Stack: $line",
    if (lines.length > frames) "Stack: … ${lines.length - frames} more",
  ];
}

/// Why a background run did not finish, in words the persisted report keeps.
///
/// The server's own sentence where the server spoke, the app's own reason
/// worded with [platformMessages] otherwise — a background run has no widget
/// tree to read a translation from, see [AppNotice] and issue #108.
String reasonOfStatus(CameraRollStatus status) {
  var said = status.message;
  if (said != null) {
    return said;
  }
  var notice = status.notice;
  return notice == null
      ? platformMessages.backgroundSyncDidNotRun
      : noticeText(notice, platformMessages);
}
