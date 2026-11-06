/// A periodic question to the server that is asked only while the app can be
/// seen (issue #317).
///
/// With the app in the background Android blocks its network: every tick of
/// a timer that goes on there fails, fills the diagnostics log and wakes the
/// device for nothing. A [ForegroundTimer] therefore stops when the app is
/// hidden or paused, and on resume asks once at once and starts its interval
/// afresh.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';

/// Whether the app can be seen in [state]: resumed or inactive (a dialog of
/// the system over it). Unknown (`null`) counts as visible — a platform that
/// has not said yet, or a test.
bool isVisibleState(AppLifecycleState? state) =>
    state == null ||
    state == AppLifecycleState.resumed ||
    state == AppLifecycleState.inactive;

/// Whether the app can be seen now, see [isVisibleState].
bool get appVisible => isVisibleState(WidgetsBinding.instance.lifecycleState);

/// Calls [onTick] every interval while the app can be seen, and once whenever
/// the app is resumed, while it is [start]ed; see the library comment.
class ForegroundTimer {
  /// What is done every interval, and on resume.
  final void Function() onTick;

  ForegroundTimer(this.onTick);

  Duration? _interval;

  Timer? _timer;

  AppLifecycleListener? _lifecycle;

  /// Whether the timer is started, whether or not the app can be seen.
  bool get started => _interval != null;

  /// Whether a tick is due: started, and the app can be seen.
  bool get ticking => _timer != null;

  /// Starts ticking every [interval] from now, or restarts with it.
  ///
  /// Ticks nothing at once; while the app cannot be seen, the first tick is
  /// the one of the next resume.
  void start(Duration interval) {
    _interval = interval;
    _lifecycle ??= AppLifecycleListener(
      onResume: _resumed,
      onStateChange: (state) {
        if (!isVisibleState(state)) {
          _cancel();
        }
      },
    );
    _arm();
  }

  /// Stops ticking, until the next [start].
  void stop() {
    _interval = null;
    _cancel();
  }

  void _arm() {
    _cancel();
    var interval = _interval;
    if (interval != null && appVisible) {
      _timer = Timer.periodic(interval, (_) => onTick());
    }
  }

  void _cancel() {
    _timer?.cancel();
    _timer = null;
  }

  void _resumed() {
    if (!started) {
      return;
    }
    _arm();
    onTick();
  }

  /// Stops ticking for good.
  void dispose() {
    stop();
    _lifecycle?.dispose();
    _lifecycle = null;
  }
}
