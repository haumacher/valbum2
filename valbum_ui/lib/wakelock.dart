/// Keeping the device awake while an upload runs (issue #63).
///
/// An upload of a hundred photos takes minutes, and a phone whose screen locks
/// in the middle of it puts the app into the background, where Android's Doze
/// suspends the network and closes the socket: the transfer dies, and the next
/// unlock shows a failure the user did nothing to deserve. The upload therefore
/// holds the screen awake for as long as its dialog is up — and lets go of it
/// on every way out, success, failure and cancellation alike.
///
/// Behind an interface for the same reason as [PhotoLibrary]: a widget test
/// must not reach a platform channel, and what the upload did with the wakelock
/// is something a test can then assert, see [RecordingWakelock].
library;

import 'package:flutter/widgets.dart';

/// Something that can keep the device's screen awake.
abstract class Wakelock {
  const Wakelock();

  /// Asks the platform to keep the screen on, or to let it lock again.
  ///
  /// Never throws: a platform that refuses the lock is not a reason to refuse
  /// the upload — the upload is simply as fragile as it was before.
  Future<void> keepAwake(bool awake);
}

/// The [Wakelock] of a platform that has none, and of every test.
class NoWakelock extends Wakelock {
  const NoWakelock();

  @override
  Future<void> keepAwake(bool awake) async {}
}

/// A [Wakelock] that only remembers what it was asked, for tests.
///
/// Lives in `lib/` next to [FakePhotoLibrary], for the same reason.
class RecordingWakelock extends Wakelock {
  /// Every request, in order: `true` for "keep awake", `false` for "let go".
  final List<bool> requests = [];

  /// Whether the screen is being held awake right now.
  bool get awake => requests.isNotEmpty && requests.last;

  @override
  Future<void> keepAwake(bool awake) async => requests.add(awake);
}

/// Makes the [Wakelock] of the app available to the widget tree.
class WakelockScope extends InheritedWidget {
  /// What holds the screen awake, see [Wakelock].
  final Wakelock wakelock;

  const WakelockScope({
    super.key,
    required this.wakelock,
    required super.child,
  });

  /// The wakelock of the enclosing app, a [NoWakelock] outside one (a view
  /// pumped on its own in a test).
  static Wakelock of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<WakelockScope>()?.wakelock ??
      const NoWakelock();

  @override
  bool updateShouldNotify(WakelockScope oldWidget) =>
      wakelock != oldWidget.wakelock;
}
