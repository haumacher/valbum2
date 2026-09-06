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
/// A background run refuses for exactly the same reasons the foreground one
/// does — switched off, no server, unpaired, no inbox — and it says so in the
/// store instead of on a screen nobody is looking at: [BackgroundRunRecord] is
/// what the settings section shows afterwards.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'camera_roll.dart';
import 'client.dart';
import 'connectivity.dart';
import 'photo_library.dart';
import 'platform.dart';
import 'settings.dart';

/// The identity of the periodic task, on Android its unique name and on iOS
/// the `BGTaskScheduler` identifier registered in `Info.plist`.
///
/// It is a constant of this app, not a generated value: the platform keeps a
/// task registered across app restarts and updates, and a name that changed
/// would leave the old one running forever.
const String backgroundSyncTaskName = "de.haumacher.valbum.cameraRollSync";

/// How often the platform is asked to run the sync.
///
/// Fifteen minutes is Android's minimum for periodic work; anything smaller is
/// silently raised to it. iOS ignores the value and decides for itself.
const Duration backgroundSyncInterval = Duration(minutes: 15);

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
  /// `false`; empty where there is nothing to explain.
  String get unavailableReason;

  /// Registers the periodic task, doing nothing if it is already registered.
  ///
  /// Called whenever the sync is switched on and once at every app start with
  /// a switched-on configuration, so that an updated app registers the task
  /// again — it must therefore be idempotent.
  Future<void> schedule();

  /// Removes the periodic task; the app stops syncing while it is closed.
  Future<void> cancel();
}

/// The [BackgroundScheduler] of a platform that has no background execution.
///
/// The web build and the desktop builds get this: a browser tab that is closed
/// is gone, and a desktop app has no periodic work API this app uses. Saying
/// so plainly is better than a promise nothing keeps.
class UnavailableBackgroundScheduler extends BackgroundScheduler {
  @override
  final String unavailableReason;

  const UnavailableBackgroundScheduler([
    this.unavailableReason = "Background sync is not available on this "
        "platform; the camera roll syncs while the app is open.",
  ]);

  @override
  bool get available => false;

  @override
  Future<void> schedule() async {}

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
  final String unavailableReason;

  /// The number of times [schedule] was called.
  int scheduled = 0;

  /// The number of times [cancel] was called.
  int cancelled = 0;

  /// What every call throws, `null` while the platform is happy.
  Object? problem;

  FakeBackgroundScheduler({
    this.available = true,
    this.unavailableReason = "No background sync in this test.",
    this.problem,
  });

  @override
  Future<void> schedule() async {
    scheduled++;
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

  /// The one line the settings section shows.
  String get line {
    var when = _stamp(at);
    if (!ok) {
      return "Last background sync at $when failed: "
          "${message ?? "unknown reason"}";
    }
    return "Last background sync at $when: $stored uploaded, "
        "$present already present";
  }

  static String _stamp(DateTime when) {
    var local = when.toLocal();
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
  final String? skipped;

  const BackgroundRunResult({this.record, this.skipped});

  /// Whether a sync was attempted at all.
  bool get ran => skipped == null;

  /// Whether a sync ran and succeeded.
  bool get ok => record?.ok ?? false;

  @override
  String toString() => skipped ?? "${record?.line}";
}

/// Runs one camera-roll sync from nothing but the persisted store.
///
/// This is what a background run *is*: the app is not running, so the server
/// URL, the device token, the inbox album and the watermark are read from
/// [store] and a whole engine is built around them for the length of one run.
/// The refusals are the ones of the foreground engine, because it is the same
/// engine — switched off, no server configured, no inbox, unpaired, offline —
/// and each of them is written back as a [BackgroundRunRecord] the settings
/// screen shows the next time somebody opens it.
///
/// Every moving part can be replaced, so that a test never touches the device:
/// [library] instead of the platform's photo library, [transport] instead of a
/// real HTTP client, [connectivity] instead of the device's network,
/// [clock] instead of the wall clock.
Future<BackgroundRunResult> runBackgroundSync({
  SettingsStore store = const PreferencesSettingsStore(),
  PhotoLibrary? library,
  http.Client? transport,
  ConnectivitySource? connectivity,
  DateTime Function()? clock,
}) async {
  var now = clock ?? DateTime.now;

  var config = await store.loadCameraRollConfig();
  if (!config.enabled) {
    // Not a failure and not a report: the user switched the sync off, and the
    // task is on its way out (see [BackgroundScheduler.cancel]).
    return const BackgroundRunResult(
      skipped: "The camera-roll sync is switched off.",
    );
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
      BackgroundRunRecord.failed(now(), "No album server is configured."),
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

  var ownLibrary = library == null ? defaultPhotoLibrary() : null;
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
    connectivity: network,
    clock: now,
  );
  try {
    await sync.load();
    var status = await sync.runOnce();
    return await record(switch (status.phase) {
      CameraRollPhase.idle => BackgroundRunRecord(
          at: status.lastSuccess ?? now(),
          ok: true,
          stored: status.lastStored,
          present: status.lastPresent,
        ),
      _ => BackgroundRunRecord.failed(
          now(),
          status.message ?? "The sync did not run.",
        ),
    });
  } catch (error) {
    return await record(BackgroundRunRecord.failed(now(), "$error"));
  } finally {
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
/// is not paired" is not something the platform can retry better than the next
/// scheduled run does, and telling the platform that the task failed would
/// only pile up back-off on top of our own. `false` is answered when the task
/// itself threw — then something is wrong with this code, not with the sync.
@pragma('vm:entry-point')
void backgroundSyncDispatcher() {
  executeBackgroundTask(() async {
    try {
      await runBackgroundSync();
      return true;
    } catch (error) {
      if (kDebugMode) {
        print("The background sync task failed: $error");
      }
      return false;
    }
  });
}
