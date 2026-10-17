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
/// On Android there are three works, all of them running the same
/// [runBackgroundSync] (issue #300): the periodic one
/// ([backgroundSyncTaskName]), one the platform starts a few seconds after the
/// photo library changed ([backgroundTriggerTaskName]) — a photo that was just
/// taken is uploaded with the app closed — and the one the app on the screen
/// hands its own runs to ([backgroundUploadTaskName]), which runs as a
/// foreground work and is therefore not frozen when the screen goes off. The
/// runs of the three meet in one [SyncRelay], so that only one of them uploads
/// at a time and the app shows what the one that does is doing.
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

/// Whether the platform runs [task] as a foreground work, with a notification
/// of its own, see `background_workmanager.dart`.
///
/// The periodic work is not one: it would show a notification every quarter
/// of an hour only to find nothing new.
bool foregroundSyncTask(String task) =>
    task == backgroundUploadTaskName || task == backgroundTriggerTaskName;

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

/// How often the platform is asked to run the sync.
///
/// Fifteen minutes is Android's minimum for periodic work; anything smaller is
/// silently raised to it. iOS ignores the value and decides for itself.
const Duration backgroundSyncInterval = Duration(minutes: 15);

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

  /// What every call throws, `null` while the platform is happy.
  Object? problem;

  FakeBackgroundScheduler({
    this.available = true,
    this.unavailableReason = const NoBackgroundSyncInTest(),
    this.problem,
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

  const BackgroundRunRecord({
    required this.at,
    required this.ok,
    this.stored = 0,
    this.present = 0,
    this.message,
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
      );
    } catch (_) {
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
      other.message == message;

  @override
  int get hashCode => Object.hash(at, ok, stored, present, message);

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

  const BackgroundRunResult({this.record, this.skipped});

  /// Whether a sync was attempted at all.
  bool get ran => skipped == null;

  /// Whether a sync ran and succeeded.
  bool get ok => record?.ok ?? false;

  @override
  String toString() => skipped ?? "$record";
}

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
/// [backgroundTriggerTaskName] registers the next one first, with [scheduler];
/// with a [relay] the run waits its turn behind another run of the process
/// and reports to the app on the screen; [notifier] shows its progress while
/// it uploads.
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
}) async {
  var now = clock ?? DateTime.now;

  var config = await store.loadCameraRollConfig();
  if (!config.enabled) {
    // Not a failure and not a report: the user switched the sync off, and the
    // task is on its way out (see [BackgroundScheduler.cancel]).
    return BackgroundRunResult(
      skipped: platformMessages.backgroundSyncOff,
    );
  }

  if (task == backgroundTriggerTaskName && scheduler != null) {
    // First, not last: a run the platform cuts off must not take the next
    // trigger with it. Registered behind this one, it observes the library
    // from the moment this run is over; what changes meanwhile, the passes of
    // this run pick up.
    try {
      await scheduler.rearmTrigger(BackgroundNetwork.of(config.wifiOnly));
    } catch (error) {
      if (kDebugMode) {
        print("background sync task: no next trigger !! $error");
      }
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
  var ownNetwork = connectivity == null ? defaultConnectivity() : null;
  var network = connectivity ?? ownNetwork!;

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
    hashCache: hashCache ?? defaultHashCache(),
    relay: relay,
    runTask: task,
    claimPatience: claimPatienceOf(task),
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
    return await record(switch (status.phase) {
      CameraRollPhase.idle => BackgroundRunRecord(
          at: status.lastSuccess ?? now(),
          ok: true,
          stored: status.lastStored,
          present: status.lastPresent,
        ),
      _ => BackgroundRunRecord.failed(now(), reasonOfStatus(status)),
    });
  } catch (error) {
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
/// A refused run is *not* a failed task: "the server is away" or "this device
/// is not signed in" is not something the platform can retry better than the next
/// scheduled run does, and telling the platform that the task failed would
/// only pile up back-off on top of our own. `false` is answered when the task
/// itself threw — then something is wrong with this code, not with the sync.
@pragma('vm:entry-point')
void backgroundSyncDispatcher() {
  executeBackgroundTask((task) async {
    try {
      await runBackgroundSync(
        task: task,
        relay: defaultSyncRelay(),
        notifier: defaultSyncNotifier(),
        scheduler: defaultBackgroundScheduler(),
      );
      return true;
    } catch (error) {
      if (kDebugMode) {
        print("background sync task !! $error");
      }
      return false;
    }
  });
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
