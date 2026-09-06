import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

const List<String> inbox = ["2026-03-01 Inbox"];

PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

class Harness {
  final FakePhotoLibrary library;
  final FakeConnectivity network;
  final InMemorySettingsStore store = InMemorySettingsStore();
  final FakeTimers timers = FakeTimers();
  final List<String> uploads = [];
  late final CameraRollSync sync;

  Harness(List<PhotoItem> items, NetworkKind kind)
      : library = FakePhotoLibrary(items: items),
        network = FakeConnectivity(kind) {
    store.cameraRoll =
        const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        uploads.add(request.body);
        return http.Response("", 200);
      }),
    );
    sync = CameraRollSync(
      store: store,
      library: library,
      clientOf: () => client,
      connectivity: network,
      clock: () => timers.now,
      timerFactory: timers.create,
    );
  }

  void dispose() {
    sync.dispose();
    library.dispose();
    network.dispose();
  }
}

void main() {
  test('lifting the limit on mobile data syncs at once, and the progress '
      'survives putting it back', () async {
    var harness = Harness([photo("a.jpg", 1)], NetworkKind.mobile);
    addTearDown(harness.dispose);
    await harness.sync.load();

    await harness.sync.syncNow();
    expect(harness.uploads, isEmpty);

    // Off: the run the switch triggers uploads the photo.
    await harness.sync.setWifiOnly(false);
    await pumpEventQueue();
    expect(harness.uploads, hasLength(1));
    var config = await harness.store.loadCameraRollConfig();
    expect(config.wifiOnly, isFalse);
    expect(config.inbox, inbox, reason: "the other settings are untouched");
    expect(config.since, isNotNull, reason: "the progress is recorded");

    // On again, still on mobile data: a new photo waits, the old progress
    // stays, and the refusal names Wi-Fi.
    await harness.sync.setWifiOnly(true);
    harness.library.add(photo("b.jpg", 2));
    await harness.sync.syncNow();
    expect(harness.uploads, hasLength(1));
    expect(harness.sync.status.message, contains("Wi-Fi"));
    config = await harness.store.loadCameraRollConfig();
    expect(config.wifiOnly, isTrue);
    expect(config.since, isNotNull);

    // Ethernet counts as unmetered.
    harness.network.moveTo(NetworkKind.ethernet);
    await harness.sync.syncNow();
    expect(harness.uploads, hasLength(2));
  });

  testWidgets('the switch on the settings screen lifts the limit',
      (tester) async {
    var harness = Harness([photo("a.jpg", 1)], NetworkKind.mobile);
    addTearDown(harness.dispose);
    await harness.sync.load();
    await tester.pumpWidget(
      MaterialApp(
        home: CameraRollScope(
          sync: harness.sync,
          child: const Scaffold(
            body: SingleChildScrollView(child: CameraRollSection()),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    var wifiSwitch = find.byKey(cameraRollWifiOnlyKey);
    expect(tester.widget<SwitchListTile>(wifiSwitch).value, isTrue,
        reason: "on by default");
    await tester.tap(wifiSwitch);
    await tester.pumpAndSettle();

    expect(tester.widget<SwitchListTile>(wifiSwitch).value, isFalse);
    expect(harness.uploads, hasLength(1));
    expect((await harness.store.loadCameraRollConfig()).wifiOnly, isFalse);
  });
}
