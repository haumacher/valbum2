/// Probe of the network constraint of the background task (issue #43),
/// composing the Wi-Fi-only toggle with the other states of the sync: a
/// switched-off sync, a restart of the app on the stored configuration, and a
/// platform without background execution.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

const List<String> inbox = ["Inbox"];

InMemorySettingsStore storeWith(CameraRollConfig config) {
  var store = InMemorySettingsStore("http://server/valbum/", "token-42", "Phone");
  store.cameraRoll = config.toJson();
  return store;
}

CameraRollSync engine(InMemorySettingsStore store, BackgroundScheduler scheduler) =>
    CameraRollSync(
      store: store,
      library: FakePhotoLibrary(),
      clientOf: () => null,
      scheduler: scheduler,
      timerFactory: FakeTimers().create,
    );

void main() {
  test('a toggle while the sync is off is honoured by the later switch-on',
      () async {
    var store = storeWith(const CameraRollConfig(inbox: inbox));
    var scheduler = FakeBackgroundScheduler();
    var sync = engine(store, scheduler);
    addTearDown(sync.dispose);
    await sync.load();

    await sync.setWifiOnly(false);
    expect(scheduler.requests, isEmpty);
    expect(scheduler.cancelled, 0);

    expect(await sync.setEnabled(true), isNull);
    await pumpEventQueue();
    expect(scheduler.requests, [BackgroundNetwork.connected]);
  });

  test('the next app start registers the constraint the switch was left in',
      () async {
    var store = storeWith(const CameraRollConfig(inbox: inbox));
    var first = FakeBackgroundScheduler();
    var sync = engine(store, first);
    await sync.load();
    expect(await sync.setEnabled(true), isNull);
    await pumpEventQueue();
    await sync.setWifiOnly(false);
    await pumpEventQueue();
    expect(first.requests,
        [BackgroundNetwork.unmetered, BackgroundNetwork.connected]);
    sync.dispose();

    // The same store, a new process.
    var second = FakeBackgroundScheduler();
    var restarted = engine(store, second);
    addTearDown(restarted.dispose);
    await restarted.load();
    restarted.start();
    await pumpEventQueue();
    expect(second.requests, [BackgroundNetwork.connected]);
    expect(restarted.backgroundProblem, isNull);
  });

  test('switching the sync off after a toggle cancels and registers nothing',
      () async {
    var store = storeWith(const CameraRollConfig(inbox: inbox));
    var scheduler = FakeBackgroundScheduler();
    var sync = engine(store, scheduler);
    addTearDown(sync.dispose);
    await sync.load();
    expect(await sync.setEnabled(true), isNull);
    await pumpEventQueue();
    await sync.setWifiOnly(false);
    expect(await sync.setEnabled(false), isNull);
    await pumpEventQueue();

    expect(scheduler.requests,
        [BackgroundNetwork.unmetered, BackgroundNetwork.connected]);
    expect(scheduler.cancelled, 1);

    // Toggling back while off adds nothing more.
    await sync.setWifiOnly(true);
    expect(scheduler.scheduled, 2);
    expect(scheduler.cancelled, 1);
  });

  test('a platform without background sync takes the toggle without complaint',
      () async {
    var store = storeWith(const CameraRollConfig(inbox: inbox));
    var scheduler = FakeBackgroundScheduler(available: false);
    var sync = engine(store, scheduler);
    addTearDown(sync.dispose);
    await sync.load();
    expect(await sync.setEnabled(true), isNull);
    await sync.setWifiOnly(false);
    await pumpEventQueue();

    expect(scheduler.requests, isEmpty);
    expect(sync.backgroundProblem, isNull);
    expect(CameraRollConfig.parse(store.cameraRoll).wifiOnly, isFalse);
  });

  test('a toggle that the platform refuses is recovered by the next toggle',
      () async {
    var store = storeWith(const CameraRollConfig(inbox: inbox));
    var scheduler = FakeBackgroundScheduler();
    var sync = engine(store, scheduler);
    addTearDown(sync.dispose);
    await sync.load();
    expect(await sync.setEnabled(true), isNull);
    await pumpEventQueue();

    scheduler.problem = StateError("no WorkManager");
    await sync.setWifiOnly(false);
    expect(sync.backgroundProblem, contains("could not be scheduled"));

    scheduler.problem = null;
    await sync.setWifiOnly(true);
    expect(sync.backgroundProblem, isNull);
    expect(scheduler.lastNetwork, BackgroundNetwork.unmetered);
  });
}
