/// Camera-roll sync (issue #30): new photos on the device flow into an inbox
/// album on the server.
///
/// The engine is [CameraRollSync]. It is a state machine with exactly one run
/// at a time:
///
/// * a **trigger** (the library's change stream, the periodic scan, app
///   start-up, or the user's "Sync now") asks for a run;
/// * a run asks the library for everything taken at or after the *watermark*
///   ([CameraRollConfig.since]), drops the ids it already handled
///   ([CameraRollConfig.done]), and hands the rest to
///   [VAlbumClient.uploadNew] in batches;
/// * the watermark advances **after** a batch was accepted, never before, so
///   an interrupted run resumes where it stopped;
/// * a failure stops the run, keeps the watermark, says why, and schedules a
///   retry with an exponential back-off (30 s, 1 min, 2 min, … 30 min).
///
/// Nothing here decides whether a photo is a duplicate: [VAlbumClient.uploadNew]
/// hashes every file and asks the server, and the server answers `present` for
/// contents it already holds (issue #29). A reinstalled app therefore starts
/// with an empty watermark, re-scans the whole library and transfers nothing.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'background.dart';
import 'client.dart';
import 'photo_library.dart';
import 'settings.dart';

/// What the sync does, and what it is configured with.
///
/// Persisted as one JSON blob through the [SettingsStore], see
/// [CameraRollStorage]. A store written before issue #30 holds nothing under
/// that key; it loads as [CameraRollConfig.disabled], so an app that is
/// updated does not suddenly start uploading.
@immutable
class CameraRollConfig {
  /// Whether the app watches the photo library.
  final bool enabled;

  /// The album on the server new photos are uploaded into, empty while the
  /// user has not chosen one.
  ///
  /// A path of folder names, as everywhere else in this app, see
  /// [VAlbumClient.folderUrl].
  final List<String> inbox;

  /// The taken-at stamp of the newest item that was handled, `null` before the
  /// first run.
  ///
  /// The next run asks the library for everything from here on — inclusively,
  /// so the newest handled item shows up again and is recognised by [done].
  final DateTime? since;

  /// The ids of the items already handled that carry exactly [since] as their
  /// taken-at stamp.
  ///
  /// Two photos taken in the same second must not make the sync choose between
  /// uploading one twice and skipping the other, so the watermark is a stamp
  /// *plus* the ids at that stamp.
  final List<String> done;

  const CameraRollConfig({
    this.enabled = false,
    this.inbox = const [],
    this.since,
    this.done = const [],
  });

  /// The configuration of an app that was never told to sync anything.
  static const CameraRollConfig disabled = CameraRollConfig();

  /// Whether an inbox album was chosen, without which nothing can be uploaded.
  ///
  /// The root of the library is deliberately not an inbox: a camera roll
  /// dropped into the root would fill the listing with loose files.
  bool get hasInbox => inbox.isNotEmpty;

  /// The same configuration with the given values replaced.
  CameraRollConfig copyWith({
    bool? enabled,
    List<String>? inbox,
    DateTime? since,
    List<String>? done,
  }) =>
      CameraRollConfig(
        enabled: enabled ?? this.enabled,
        inbox: inbox ?? this.inbox,
        since: since ?? this.since,
        done: done ?? this.done,
      );

  /// This configuration as the JSON blob the store keeps.
  String toJson() => jsonEncode({
        "enabled": enabled,
        "inbox": inbox,
        if (since != null) "since": since!.toUtc().toIso8601String(),
        "done": done,
      });

  /// The configuration stored as [text], [disabled] if there is none.
  ///
  /// A blob this version cannot read is treated as absent rather than as a
  /// reason to fail: the user re-chooses the inbox, and nothing is lost but
  /// the watermark — the server answers `present` for what it already holds.
  static CameraRollConfig parse(String? text) {
    if (text == null || text.trim().isEmpty) {
      return disabled;
    }
    try {
      var json = jsonDecode(text);
      if (json is! Map) {
        return disabled;
      }
      var since = json["since"];
      return CameraRollConfig(
        enabled: json["enabled"] == true,
        inbox: [for (var name in (json["inbox"] as List? ?? [])) "$name"],
        since: since is String ? DateTime.tryParse(since) : null,
        done: [for (var id in (json["done"] as List? ?? [])) "$id"],
      );
    } catch (_) {
      return disabled;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is CameraRollConfig &&
      other.enabled == enabled &&
      listEquals(other.inbox, inbox) &&
      other.since == since &&
      listEquals(other.done, done);

  @override
  int get hashCode => Object.hash(enabled, inbox.length, since, done.length);

  @override
  String toString() => toJson();
}

/// Typed access to the camera-roll configuration of a [SettingsStore].
extension CameraRollStorage on SettingsStore {
  /// The stored configuration, [CameraRollConfig.disabled] if there is none.
  Future<CameraRollConfig> loadCameraRollConfig() async =>
      CameraRollConfig.parse(await loadCameraRoll());

  /// Stores the given configuration.
  Future<void> saveCameraRollConfig(CameraRollConfig config) =>
      saveCameraRoll(config.toJson());
}

/// What the sync is doing right now, see [CameraRollStatus].
enum CameraRollPhase {
  /// The user has not switched the sync on.
  disabled,

  /// There is no photo library on this platform, or access was refused.
  unavailable,

  /// Switched on, nothing to do at the moment.
  idle,

  /// A run is transferring items.
  running,

  /// A run failed; the next attempt is scheduled, see
  /// [CameraRollStatus.nextAttempt].
  waiting,

  /// A run failed and nothing is scheduled — the sync was switched off, or the
  /// reason is not one that retrying can fix.
  failed,
}

/// What the sync is doing, and what the last run did.
@immutable
class CameraRollStatus {
  final CameraRollPhase phase;

  /// The number of items transferred so far in the running run.
  final int done;

  /// The number of items the running run set out to transfer.
  final int total;

  /// Why the sync is not running, where there is a reason to show.
  final String? message;

  /// When the next attempt is due, while [phase] is
  /// [CameraRollPhase.waiting].
  final DateTime? nextAttempt;

  /// When the last run finished successfully, `null` if none ever did.
  final DateTime? lastSuccess;

  /// What the last successful run uploaded, and what the server already had.
  final int lastStored;
  final int lastPresent;

  const CameraRollStatus({
    this.phase = CameraRollPhase.disabled,
    this.done = 0,
    this.total = 0,
    this.message,
    this.nextAttempt,
    this.lastSuccess,
    this.lastStored = 0,
    this.lastPresent = 0,
  });

  /// Whether a run is transferring items right now.
  bool get running => phase == CameraRollPhase.running;

  /// The share of the running run that is done, `null` while nothing runs.
  double? get progress =>
      phase == CameraRollPhase.running && total > 0 ? done / total : null;

  /// The same status with the given values replaced.
  ///
  /// The nullable fields are replaced wholesale (a `null` clears them), which
  /// is what every transition here wants; the counts of the last run are
  /// carried over unless they are given.
  CameraRollStatus copyWith({
    CameraRollPhase? phase,
    int? done,
    int? total,
    String? message,
    DateTime? nextAttempt,
    DateTime? lastSuccess,
    int? lastStored,
    int? lastPresent,
  }) =>
      CameraRollStatus(
        phase: phase ?? this.phase,
        done: done ?? this.done,
        total: total ?? this.total,
        message: message,
        nextAttempt: nextAttempt,
        lastSuccess: lastSuccess ?? this.lastSuccess,
        lastStored: lastStored ?? this.lastStored,
        lastPresent: lastPresent ?? this.lastPresent,
      );

  /// The one line the settings screen and the app-bar tooltip show.
  String get line => switch (phase) {
        CameraRollPhase.disabled => "Camera-roll sync is off.",
        CameraRollPhase.unavailable =>
          message ?? "No photo library on this platform",
        CameraRollPhase.running => "Uploading ${done + 1} of $total...",
        CameraRollPhase.waiting =>
          "Failed: ${message ?? "unknown reason"} - retrying at "
              "${nextAttempt == null ? "the next attempt" : _time(nextAttempt!)}",
        CameraRollPhase.failed => "Failed: ${message ?? "unknown reason"}",
        CameraRollPhase.idle => _idleLine,
      };

  String get _idleLine {
    var when = lastSuccess;
    if (when == null) {
      return "Waiting for new photos.";
    }
    var count = lastStored + lastPresent;
    if (count == 0) {
      return "Nothing new, checked at ${_time(when)}.";
    }
    return "Synced $count ${count == 1 ? "photo" : "photos"} at "
        "${_time(when)} ($lastStored uploaded, $lastPresent already there).";
  }

  static String _time(DateTime when) {
    var local = when.toLocal();
    String two(int value) => value.toString().padLeft(2, "0");
    return "${two(local.hour)}:${two(local.minute)}";
  }
}

/// Builds a timer, so that a test does not have to wait for one.
typedef TimerFactory = Timer Function(Duration delay, void Function() callback);

/// The number of bounded scans one run may do before it leaves the rest to the
/// next trigger; a guard against a library that keeps answering with items the
/// server never accepts.
const int maxRounds = 50;

/// The delays a failed run is retried with: 30 s, doubling, capped at 30 min.
const List<Duration> retryDelays = [
  Duration(seconds: 30),
  Duration(minutes: 1),
  Duration(minutes: 2),
  Duration(minutes: 4),
  Duration(minutes: 8),
  Duration(minutes: 16),
  Duration(minutes: 30),
];

/// The engine watching the device's photo library and filling the inbox album.
///
/// Every moving part is injected: the [library] the photos come from, the
/// [clientOf] the upload goes through, the [clock] and the [timerFactory] —
/// so a test drives a whole day of back-off in a millisecond.
class CameraRollSync extends ChangeNotifier {
  /// Where the configuration is persisted.
  final SettingsStore store;

  /// The device's photo library.
  final PhotoLibrary library;

  /// The client to upload with, `null` while no server is configured.
  ///
  /// A function, not a value: the app builds a new client whenever the server
  /// URL or the device token changes, and the sync must use the current one.
  final VAlbumClient? Function() clientOf;

  /// Whether the app currently cannot reach the server.
  ///
  /// A sync while the server is away would only produce failures; it is
  /// refused with that reason instead, see the "refusals speak" rule.
  final bool Function() isOffline;

  /// The number of items handed to the server in one request.
  final int batchSize;

  /// How often the library is scanned while the app runs.
  final Duration interval;

  /// The platform's periodic background execution (issue #32).
  ///
  /// Switching the sync on registers the task, switching it off removes it,
  /// and every app start with a switched-on configuration registers it again
  /// — an app that was updated must not lose it. A platform that has none
  /// says so, see [BackgroundScheduler.available].
  final BackgroundScheduler scheduler;

  /// The current time, injected so that tests are instant.
  final DateTime Function() clock;

  /// Builds the delay timers, injected for the same reason.
  final TimerFactory timerFactory;

  CameraRollConfig _config = CameraRollConfig.disabled;
  CameraRollStatus _status = const CameraRollStatus();
  bool _loaded = false;
  BackgroundRunRecord? _lastBackgroundRun;
  String? _backgroundProblem;

  /// Whether a failed run may arm a retry timer.
  ///
  /// A background run must not: the isolate it runs in is torn down when it
  /// returns, so a timer there would either die unfired or hold the isolate
  /// open. The platform runs the task again anyway, see [runOnce].
  bool _armRetries = true;

  bool _running = false;
  bool _pending = false;
  bool _stopRequested = false;
  int _attempt = 0;

  Timer? _retryTimer;
  Timer? _scanTimer;
  StreamSubscription<void>? _watch;
  bool _disposed = false;

  CameraRollSync({
    required this.store,
    required this.library,
    required this.clientOf,
    bool Function()? isOffline,
    BackgroundScheduler? scheduler,
    this.batchSize = 10,
    this.interval = const Duration(minutes: 15),
    DateTime Function()? clock,
    TimerFactory? timerFactory,
  })  : isOffline = isOffline ?? _never,
        scheduler = scheduler ?? const UnavailableBackgroundScheduler(),
        clock = clock ?? DateTime.now,
        timerFactory = timerFactory ?? _realTimer;

  static bool _never() => false;

  static Timer _realTimer(Duration delay, void Function() callback) =>
      Timer(delay, callback);

  /// What the sync is configured with, see [CameraRollConfig].
  CameraRollConfig get config => _config;

  /// What the sync is doing, see [CameraRollStatus].
  CameraRollStatus get status => _status;

  /// Whether [load] has finished.
  bool get loaded => _loaded;

  /// Reads the stored configuration; called once when the app starts.
  Future<void> load() async {
    _config = await store.loadCameraRollConfig();
    _lastBackgroundRun = await store.loadBackgroundRunRecord();
    _loaded = true;
    _publish(_restingStatus());
  }

  /// What the last background run did, `null` if none ever ran (issue #32).
  ///
  /// Read once by [load]; the run itself happens in another isolate while this
  /// app is closed, so there is nothing to listen to — the next start reads
  /// what it left behind.
  BackgroundRunRecord? get lastBackgroundRun => _lastBackgroundRun;

  /// Why the background task could not be registered or removed, `null` while
  /// the platform did what it was asked.
  ///
  /// A plugin that throws is not swallowed: the settings section shows this.
  String? get backgroundProblem => _backgroundProblem;

  /// Arms the triggers: the library's change stream and the periodic scan.
  ///
  /// Does nothing while the sync is switched off — an app that is not syncing
  /// must not hold a timer, let alone a subscription to the photo library.
  void start() {
    if (_disposed) {
      return;
    }
    if (!_config.enabled) {
      _disarm();
      return;
    }
    _watch ??= library.changes.listen((_) => trigger());
    _armScan();
    // Idempotent, so every start may ask: an app that was updated registers
    // the periodic task again this way (issue #32).
    unawaited(_arrangeBackground(true));
    trigger();
  }

  void _armScan() {
    _scanTimer?.cancel();
    _scanTimer = timerFactory(interval, () {
      _scanTimer = null;
      if (_config.enabled) {
        _armScan();
        trigger();
      }
    });
  }

  void _disarm() {
    _watch?.cancel();
    _watch = null;
    _scanTimer?.cancel();
    _scanTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;
  }

  /// Asks for a run, unless one is already going on.
  ///
  /// A trigger arriving during a run is remembered: the change that caused it
  /// is picked up by one more run when the current one is through, and never
  /// by a second run alongside it.
  void trigger() {
    if (_running) {
      _pending = true;
      return;
    }
    unawaited(_run());
  }

  /// Runs one sync now, forgetting the back-off of previous failures.
  ///
  /// This is the "Sync now" of the settings screen: the user asked, so the
  /// next attempt is not in eight minutes but immediately.
  Future<void> syncNow() async {
    _attempt = 0;
    _retryTimer?.cancel();
    _retryTimer = null;
    _stopRequested = false;
    if (_running) {
      _pending = true;
      return;
    }
    await _run();
  }

  /// Runs exactly one sync and answers what it did, arming nothing.
  ///
  /// This is the entry a background run uses (issue #32): no change stream, no
  /// periodic scan, and no retry timer — the isolate ends when this future
  /// completes, and the platform schedules the next run itself. The state
  /// machine, the watermark and every refusal are the ones of a foreground
  /// run, because it is the same run.
  Future<CameraRollStatus> runOnce() async {
    if (_disposed || _running) {
      return _status;
    }
    if (!_config.enabled) {
      _publish(_restingStatus());
      return _status;
    }
    _running = true;
    _stopRequested = false;
    _armRetries = false;
    try {
      await _runOnce();
    } finally {
      _armRetries = true;
      _running = false;
      _pending = false;
    }
    return _status;
  }

  /// Asks the running sync to stop after the batch it is transferring.
  void stop() {
    _stopRequested = true;
    _pending = false;
  }

  /// Switches the sync on or off.
  ///
  /// Returns the reason it refused, `null` when it did what it was asked:
  /// there is nothing to sync into before an inbox album was chosen, and a
  /// switch that flips back on its own would leave the user guessing.
  Future<String?> setEnabled(bool value) async {
    if (value && !_config.hasInbox) {
      return "Choose an inbox album first - that is where new photos go.";
    }
    if (value && !await library.requestAccess()) {
      return library.accessProblem ?? "The photo library cannot be read.";
    }
    await _store(_config.copyWith(enabled: value));
    if (value) {
      start();
    } else {
      stop();
      _disarm();
      await _arrangeBackground(false);
      _publish(_restingStatus());
    }
    return null;
  }

  /// Registers or removes the platform's periodic background task.
  ///
  /// Never throws: a plugin that refuses is a reason to show, not a reason to
  /// keep the user from switching the sync on — the foreground sync works
  /// either way, see [backgroundProblem].
  Future<void> _arrangeBackground(bool enabled) async {
    if (!scheduler.available) {
      return;
    }
    try {
      if (enabled) {
        await scheduler.schedule();
      } else {
        await scheduler.cancel();
      }
      _backgroundProblem = null;
    } catch (error) {
      _backgroundProblem = enabled
          ? "Background sync could not be scheduled: $error"
          : "Background sync could not be switched off: $error";
    }
    if (!_disposed) {
      notifyListeners();
    }
  }

  /// Chooses the album new photos are uploaded into.
  ///
  /// Changing the inbox does not re-upload anything: the watermark stays, and
  /// the server answers `present` for contents the new album already holds.
  Future<void> chooseInbox(List<String> path) async {
    await _store(_config.copyWith(inbox: [...path]));
    _publish(_restingStatus());
  }

  /// Forgets what was uploaded, so that the next run re-scans the library.
  ///
  /// Nothing is uploaded twice by this: the server is asked for every hash.
  Future<void> forgetProgress() async {
    await _store(CameraRollConfig(
      enabled: _config.enabled,
      inbox: _config.inbox,
    ));
    _publish(_restingStatus());
  }

  Future<void> _store(CameraRollConfig config) async {
    _config = config;
    await store.saveCameraRollConfig(config);
  }

  /// The status of a sync that is not doing anything at the moment.
  CameraRollStatus _restingStatus() => _status.copyWith(
        phase:
            _config.enabled ? CameraRollPhase.idle : CameraRollPhase.disabled,
        done: 0,
        total: 0,
      );

  void _publish(CameraRollStatus status) {
    _status = status;
    if (!_disposed) {
      notifyListeners();
    }
  }

  /// One run, see the library documentation for the state machine.
  Future<void> _run() async {
    if (_disposed || _running) {
      return;
    }
    if (!_config.enabled) {
      _publish(_restingStatus());
      return;
    }
    _running = true;
    _stopRequested = false;
    try {
      await _runOnce();
    } finally {
      _running = false;
    }
    if (_pending && !_stopRequested && _config.enabled) {
      _pending = false;
      await _run();
    } else {
      _pending = false;
    }
  }

  Future<void> _runOnce() async {
    if (!_config.hasInbox) {
      _fail("Choose an inbox album first - that is where new photos go.",
          retry: false);
      return;
    }
    if (isOffline()) {
      _fail("Offline: the album server cannot be reached.");
      return;
    }
    var client = clientOf();
    if (client == null) {
      _fail("No album server is configured.", retry: false);
      return;
    }
    if (!await library.requestAccess()) {
      _publish(_status.copyWith(
        phase: CameraRollPhase.unavailable,
        message: library.accessProblem ?? "The photo library cannot be read.",
      ));
      return;
    }

    var stored = 0;
    var present = 0;
    var transferred = 0;
    // A platform scan is bounded (see [PhotoLibrary.scanLimit]), so a full
    // answer may have left items behind; the watermark has advanced by then,
    // so the next round picks exactly those up. A round that was not full —
    // and every round of a library that answers with everything — ends the
    // run.
    for (var round = 0; round < maxRounds; round++) {
      List<PhotoItem> items;
      try {
        items = await library.itemsSince(_config.since);
      } catch (error) {
        _fail("The photo library could not be read: $error");
        return;
      }

      var alreadyDone = _config.done.toSet();
      var pending = [
        for (var item in items)
          if (!alreadyDone.contains(item.id)) item
      ];
      if (pending.isEmpty) {
        break;
      }
      var truncated =
          library.scanLimit > 0 && items.length >= library.scanLimit;

      var total = transferred + pending.length;
      _publish(_status.copyWith(
        phase: CameraRollPhase.running,
        done: transferred,
        total: total,
      ));

      for (var start = 0; start < pending.length; start += batchSize) {
        if (_stopRequested) {
          _publish(_restingStatus());
          return;
        }
        var batch = pending.sublist(
          start,
          start + batchSize > pending.length
              ? pending.length
              : start + batchSize,
        );
        UploadSummary summary;
        try {
          summary = await client.uploadNew(
            _config.inbox,
            [for (var item in batch) item.upload],
          );
        } on VAlbumException catch (error) {
          _fail(error.message);
          return;
        } catch (error) {
          _fail(
            VAlbumClient.isTransportFailure(error)
                ? "The server cannot be reached "
                    "(${VAlbumClient.transportMessage(error)})."
                : error.toString(),
          );
          return;
        }
        stored += summary.stored;
        present += summary.present;
        transferred += batch.length;
        // Only now: what was accepted is what the watermark may cover.
        await _advance(batch);
        _publish(_status.copyWith(
          phase: CameraRollPhase.running,
          done: transferred,
          total: total,
        ));
      }
      if (!truncated) {
        break;
      }
    }
    _succeed(stored, present);
  }

  /// Records that [batch] was accepted by the server.
  ///
  /// The watermark becomes the newest taken-at stamp handled so far; the ids
  /// kept beside it are exactly those at that stamp — the older ones can never
  /// be offered again, because the next scan starts at the watermark.
  Future<void> _advance(List<PhotoItem> batch) async {
    var mark = _config.since;
    for (var item in batch) {
      if (mark == null || item.takenAt.isAfter(mark)) {
        mark = item.takenAt;
      }
    }
    var kept = <String>{
      // The previously recorded ids belong to the previous watermark; they
      // survive only while that watermark is still the current one.
      if (mark == _config.since) ..._config.done,
      for (var item in batch)
        if (item.takenAt == mark) item.id,
    };
    await _store(_config.copyWith(since: mark, done: kept.toList()));
  }

  void _succeed(int stored, int present) {
    _attempt = 0;
    _retryTimer?.cancel();
    _retryTimer = null;
    _publish(CameraRollStatus(
      phase: CameraRollPhase.idle,
      lastSuccess: clock(),
      lastStored: stored,
      lastPresent: present,
    ));
  }

  /// Ends the run with a reason, and schedules the next attempt.
  void _fail(String message, {bool retry = true}) {
    if (!retry || !_armRetries) {
      _publish(_status.copyWith(
        phase: CameraRollPhase.failed,
        message: message,
        done: 0,
        total: 0,
      ));
      return;
    }
    var delay = retryDelays[
        _attempt < retryDelays.length ? _attempt : retryDelays.length - 1];
    _attempt++;
    _retryTimer?.cancel();
    _retryTimer = timerFactory(delay, () {
      _retryTimer = null;
      trigger();
    });
    _publish(_status.copyWith(
      phase: CameraRollPhase.waiting,
      message: message,
      nextAttempt: clock().add(delay),
      done: 0,
      total: 0,
    ));
  }

  /// The delay the next failure will be retried after, for the tests and the
  /// status line.
  Duration get nextDelay => retryDelays[
      _attempt < retryDelays.length ? _attempt : retryDelays.length - 1];

  @override
  void dispose() {
    _disposed = true;
    _disarm();
    super.dispose();
  }
}
