/// A [Timer] factory a test drives by hand, see [CameraRollSync.timerFactory].
///
/// The camera-roll sync waits — 30 seconds before the first retry, 15 minutes
/// between two scans. A test must not: it schedules through this factory and
/// fires what it wants to see happen.
library;

import 'dart:async';

/// Every timer the code under test asked for.
class FakeTimers {
  final List<FakeTimer> scheduled = [];

  /// The time the engine reads through its clock.
  DateTime now = DateTime.utc(2026, 3, 1, 12);

  /// The factory to hand to the code under test.
  Timer create(Duration delay, void Function() callback) {
    var timer = FakeTimer(delay, callback);
    scheduled.add(timer);
    return timer;
  }

  /// The delays of the timers still waiting.
  List<Duration> get pending => [
        for (var timer in scheduled)
          if (timer.isActive) timer.delay
      ];

  /// Fires every waiting timer with the given delay.
  ///
  /// Returns how many fired, so a test can assert that there was one.
  int fire(Duration delay) {
    var due = [
      for (var timer in scheduled)
        if (timer.isActive && timer.delay == delay) timer
    ];
    for (var timer in due) {
      timer.fire();
    }
    return due.length;
  }

  /// Fires every waiting timer.
  int fireAll() {
    var due = [
      for (var timer in scheduled)
        if (timer.isActive) timer
    ];
    for (var timer in due) {
      timer.fire();
    }
    return due.length;
  }
}

/// One scheduled callback, fired by the test rather than by the clock.
class FakeTimer implements Timer {
  final Duration delay;
  final void Function() callback;
  bool _active = true;

  FakeTimer(this.delay, this.callback);

  @override
  void cancel() => _active = false;

  @override
  bool get isActive => _active;

  @override
  int get tick => 0;

  /// Runs the callback, once.
  void fire() {
    if (!_active) {
      return;
    }
    _active = false;
    callback();
  }
}
