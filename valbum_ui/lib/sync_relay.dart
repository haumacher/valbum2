/// One camera-roll run at a time in the whole app process, and what it is
/// doing told to the app on the screen (issue #300).
///
/// On Android a run is carried out in more than one place: the app on the
/// screen hands its runs to a foreground work so that switching the screen off
/// does not stop them, a new photo starts a background work, and the periodic
/// work keeps running as a fallback. Each of them runs in an engine of its own
/// — a Dart isolate that shares no memory with the others — but all of them in
/// the one process of the app. [SyncRelay] is where they meet:
///
///  * [SyncRelay.claim] makes a run the one run of the process; a second run
///    waits for the first one to end instead of uploading the same photos
///    beside it;
///  * [RunClaim.publish] tells whoever watches what the run is doing, and
///    [SyncRelay.watch] is the app on the screen listening — it shows the
///    progress of a run it does not carry out itself;
///  * [SyncRelay.requestStop] is the app's Stop button reaching that run.
///
/// Everything crossing it is plain values (a status as `CameraRollStatus.
/// toWire` writes it): isolates of different engines exchange nothing else.
///
/// The implementation of a phone is `IsolateSyncRelay` (`sync_relay_io.dart`,
/// reached through `platform.dart`); [MemorySyncRelay] is the one of the tests,
/// shared by the engines a test builds.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

/// What a run says to whoever watches, see [RunClaim.publish].
@immutable
class RelayMessage {
  /// Which run speaks: the background task it runs as, or [appRunTask].
  final String task;

  /// What the run is doing, as `CameraRollStatus.toWire` writes it.
  final Map<String, Object?> status;

  /// Whether the run has ended; [status] is then its last word.
  final bool finished;

  /// Whether the run that ended failed for a reason a retry may fix.
  final bool retryable;

  const RelayMessage({
    required this.task,
    required this.status,
    this.finished = false,
    this.retryable = false,
  });

  /// This message as the plain values that cross between isolates.
  Map<String, Object?> toWire() => {
        "task": task,
        "status": status,
        "finished": finished,
        "retryable": retryable,
      };

  /// The message [wire] holds, `null` for anything else.
  static RelayMessage? fromWire(Object? wire) {
    if (wire is! Map) {
      return null;
    }
    var status = wire["status"];
    return RelayMessage(
      task: "${wire["task"] ?? ""}",
      status: status is Map
          ? {for (var entry in status.entries) "${entry.key}": entry.value}
          : const {},
      finished: wire["finished"] == true,
      retryable: wire["retryable"] == true,
    );
  }

  @override
  String toString() => "$toWire()";
}

/// The task name of a run the app on the screen carries out itself.
const String appRunTask = "app";

/// The one run of the process, while it holds the claim, see
/// [SyncRelay.claim].
abstract class RunClaim {
  /// Tells whoever watches what the run is doing; [finished] with its last
  /// word.
  void publish(
    Map<String, Object?> status, {
    bool finished = false,
    bool retryable = false,
  });

  /// One event per request to stop, see [SyncRelay.requestStop].
  Stream<void> get stopRequests;

  /// Gives the claim up; the next run waiting for it goes ahead.
  Future<void> release();
}

/// The meeting point of the runs of one process, see the library comment.
abstract class SyncRelay {
  /// Makes the run [task] the one run of the process.
  ///
  /// Waits up to [patience] for a run that holds the claim to end, and answers
  /// `null` when it did not end in time: the run then leaves the photos to
  /// the one that is going on.
  Future<RunClaim?> claim({required String task, required Duration patience});

  /// What the runs of the process say, see [RunClaim.publish].
  ///
  /// The app on the screen listens; one listener per process.
  Stream<RelayMessage> watch();

  /// Asks the run that holds the claim to stop after the batches it is
  /// transferring; `false` when no run holds it.
  Future<bool> requestStop();
}

/// A [SyncRelay] inside one isolate, used by tests: the engines a test builds
/// share one, exactly as the engines of a phone share their process.
class MemorySyncRelay extends SyncRelay {
  _MemoryClaim? _holder;
  final List<(String, Completer<_MemoryClaim>)> _waiting = [];
  final StreamController<RelayMessage> _watchers =
      StreamController<RelayMessage>.broadcast();

  /// Everything that was published, in order.
  final List<RelayMessage> published = [];

  /// The tasks that asked for the claim, in order.
  final List<String> claims = [];

  /// The tasks that gave up waiting for it.
  final List<String> missed = [];

  /// The number of stop requests that reached a run.
  int stops = 0;

  /// Whether a run waits for the claim at all; `false` stands for a holder
  /// that outlasts every patience.
  bool waits;

  MemorySyncRelay({this.waits = true});

  /// Whether a run holds the claim.
  bool get busy => _holder != null;

  /// The task holding the claim, `null` while none does.
  String? get holder => _holder?.task;

  @override
  Future<RunClaim?> claim({
    required String task,
    required Duration patience,
  }) async {
    claims.add(task);
    if (_holder == null) {
      return _holder = _MemoryClaim(this, task);
    }
    // A fake clock of a test does not move this one: waiting ends when the
    // holder releases, or never — a test that wants a give-up says so with a
    // zero patience.
    if (patience == Duration.zero || !waits) {
      missed.add(task);
      return null;
    }
    var turn = Completer<_MemoryClaim>();
    _waiting.add((task, turn));
    return turn.future;
  }

  @override
  Stream<RelayMessage> watch() => _watchers.stream;

  @override
  Future<bool> requestStop() async {
    var holder = _holder;
    if (holder == null) {
      return false;
    }
    stops++;
    holder._stops.add(null);
    return true;
  }

  void _publish(RelayMessage message) {
    published.add(message);
    _watchers.add(message);
  }

  void _release(_MemoryClaim claim) {
    if (_holder != claim) {
      return;
    }
    _holder = null;
    if (_waiting.isNotEmpty) {
      // Handed over at once, so that nobody slips in between.
      var (task, turn) = _waiting.removeAt(0);
      turn.complete(_holder = _MemoryClaim(this, task));
    }
  }
}

class _MemoryClaim extends RunClaim {
  final MemorySyncRelay _relay;
  final String task;
  final StreamController<void> _stops = StreamController<void>.broadcast();

  _MemoryClaim(this._relay, this.task);

  @override
  void publish(
    Map<String, Object?> status, {
    bool finished = false,
    bool retryable = false,
  }) =>
      _relay._publish(RelayMessage(
        task: task,
        status: status,
        finished: finished,
        retryable: retryable,
      ));

  @override
  Stream<void> get stopRequests => _stops.stream;

  @override
  Future<void> release() async {
    _relay._release(this);
    await _stops.close();
  }
}
