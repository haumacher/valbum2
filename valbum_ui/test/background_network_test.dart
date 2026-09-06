/// Tests of the network constraint the background task is registered with
/// (issue #43).
///
/// The Wi-Fi-only switch is not only a rule the run obeys — it is what the
/// platform holds the wake-up back for. These tests pin down which
/// requirement [CameraRollSync] asks the [BackgroundScheduler] for, and that
/// a toggle of the switch re-registers the task instead of leaving the old
/// constraint behind.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

/// The album new photos go into.
const List<String> inbox = ["Inbox"];

/// A store holding the given camera-roll configuration.
InMemorySettingsStore storeWith(CameraRollConfig config) {
  var store = InMemorySettingsStore("http://server/valbum/", "token-42", "Phone");
  store.cameraRoll = config.toJson();
  return store;
}

/// An engine over [store] that talks to no server and holds no real timer.
CameraRollSync engine(
  InMemorySettingsStore store,
  BackgroundScheduler scheduler, {
  FakePhotoLibrary? library,
}) =>
    CameraRollSync(
      store: store,
      library: library ?? FakePhotoLibrary(),
      clientOf: () => null,
      scheduler: scheduler,
      timerFactory: FakeTimers().create,
    );

/// Pumps the camera-roll section of the settings on its own.
Future<void> pumpSection(WidgetTester tester, CameraRollSync sync) async {
  await tester.pumpWidget(
    MaterialApp(
      home: CameraRollScope(
        sync: sync,
        child: const Scaffold(
          body: SingleChildScrollView(child: CameraRollSection()),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('switching the sync on', () {
    test('asks for an unmetered network while Wi-Fi-only is on', () async {
      var store = storeWith(const CameraRollConfig(inbox: inbox));
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      expect(await sync.setEnabled(true), isNull);
      await pumpEventQueue();

      expect(scheduler.requests, [BackgroundNetwork.unmetered]);
    });

    test('asks for any connection while Wi-Fi-only is off', () async {
      var store = storeWith(
        const CameraRollConfig(wifiOnly: false, inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      expect(await sync.setEnabled(true), isNull);
      await pumpEventQueue();

      expect(scheduler.requests, [BackgroundNetwork.connected]);
    });
  });

  group('toggling the Wi-Fi-only switch', () {
    test('re-registers the task while the sync is on', () async {
      var store = storeWith(
        const CameraRollConfig(enabled: true, inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();
      sync.start();
      await pumpEventQueue();
      expect(scheduler.requests, [BackgroundNetwork.unmetered]);

      await sync.setWifiOnly(false);
      await pumpEventQueue();
      await sync.setWifiOnly(true);
      await pumpEventQueue();

      expect(scheduler.requests, [
        BackgroundNetwork.unmetered,
        BackgroundNetwork.connected,
        BackgroundNetwork.unmetered,
      ]);
      // The task is updated, never taken away and put back: cancelling it
      // would lose the timing the platform is already counting down.
      expect(scheduler.cancelled, 0);
    });

    test('registers nothing while the sync is off', () async {
      var store = storeWith(const CameraRollConfig(inbox: inbox));
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      await sync.setWifiOnly(false);
      await pumpEventQueue();
      await sync.setWifiOnly(true);
      await pumpEventQueue();

      expect(scheduler.requests, isEmpty);
      expect(scheduler.cancelled, 0);
      expect((await store.loadCameraRollConfig()).wifiOnly, isTrue);
    });

    test('says so when the plugin refuses, and stores the setting', () async {
      var store = storeWith(
        const CameraRollConfig(enabled: true, inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler(
        problem: StateError("no WorkManager here"),
      );
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      await sync.setWifiOnly(false);
      await pumpEventQueue();

      expect(
        sync.backgroundProblem,
        contains("Background sync could not be scheduled"),
      );
      expect(sync.backgroundProblem, contains("no WorkManager here"));
      expect(sync.config.wifiOnly, isFalse);
      expect((await store.loadCameraRollConfig()).wifiOnly, isFalse);
    });
  });

  test('an app start carries the stored setting to the platform', () async {
    var store = storeWith(
      const CameraRollConfig(enabled: true, wifiOnly: false, inbox: inbox),
    );
    var scheduler = FakeBackgroundScheduler();
    var sync = engine(store, scheduler);
    addTearDown(sync.dispose);
    await sync.load();

    sync.start();
    await pumpEventQueue();

    expect(scheduler.requests, [BackgroundNetwork.connected]);
  });

  testWidgets('the switch on the settings screen changes the constraint',
      (tester) async {
    var store = storeWith(
      const CameraRollConfig(enabled: true, inbox: inbox),
    );
    var scheduler = FakeBackgroundScheduler();
    var library = FakePhotoLibrary();
    addTearDown(library.dispose);
    var sync = engine(store, scheduler, library: library);
    addTearDown(sync.dispose);
    await sync.load();
    // `pumpEventQueue` must not be used under `testWidgets`: the fake clock
    // only moves when the tester pumps, so the section is pumped instead.
    sync.start();
    await pumpSection(tester, sync);
    expect(scheduler.lastNetwork, BackgroundNetwork.unmetered);

    await tester.tap(find.byKey(cameraRollWifiOnlyKey));
    await tester.pumpAndSettle();

    expect(scheduler.lastNetwork, BackgroundNetwork.connected);

    await tester.tap(find.byKey(cameraRollWifiOnlyKey));
    await tester.pumpAndSettle();

    expect(scheduler.lastNetwork, BackgroundNetwork.unmetered);
  });
}
