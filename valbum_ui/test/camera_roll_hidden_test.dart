/// The in-app camera-roll sync on Android starts no run while the app is not
/// on the screen (issues #300, #317): Android cuts its network there, and a
/// run holding the claim of the process would keep the expedited upload work
/// from the photos. The run asked for meanwhile is made on resume.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'background_test.dart' show FakeServer, enabledStore, now, photo;
import 'camera_roll_foreground_test.dart' show clientOf;
import 'util/fake_timers.dart';
import 'util/lifecycle.dart';

/// A sync and the runs it started, by the phases it published.
(CameraRollSync, List<CameraRollPhase>) countingSync() {
  var server = FakeServer();
  var sync = CameraRollSync(
    store: enabledStore(
        config: const CameraRollConfig(enabled: true, wifiOnly: false)),
    library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
    callerOf: () async => const CallerInfo(role: roleMember, inbox: "Inbox"),
    clientOf: () => clientOf(server),
    scheduler: FakeBackgroundScheduler(),
    timerFactory: FakeTimers().create,
    clock: () => now,
    connectivity: FakeConnectivity(),
  );
  var phases = <CameraRollPhase>[];
  sync.addListener(() => phases.add(sync.status.phase));
  return (sync, phases);
}

void main() {
  testWidgets('Android: a run asked for while hidden is made on resume',
      (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    var (sync, phases) = countingSync();
    addTearDown(sync.dispose);
    resume(tester);
    await tester.runAsync(() async {
      await sync.load();
      toBackground(tester);
      sync.start();
      sync.trigger();
      await pumpEventQueue();
    });
    expect(phases, isNot(contains(CameraRollPhase.running)),
        reason: "Hidden on Android: no run.");

    await tester.runAsync(() async {
      toForeground(tester);
      for (var i = 0; i < 50 && !phases.contains(CameraRollPhase.running); i++) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
    });
    expect(phases, contains(CameraRollPhase.running),
        reason: "Resumed: the run asked for.");
    debugDefaultTargetPlatformOverride = null;
  });

  testWidgets('a desktop runs while minimized: it has no background work',
      (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.linux;
    var (sync, phases) = countingSync();
    addTearDown(sync.dispose);
    resume(tester);
    await tester.runAsync(() async {
      await sync.load();
      toBackground(tester);
      sync.start();
      for (var i = 0; i < 50 && !phases.contains(CameraRollPhase.running); i++) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
    });
    expect(phases, contains(CameraRollPhase.running));
    debugDefaultTargetPlatformOverride = null;
  });
}
