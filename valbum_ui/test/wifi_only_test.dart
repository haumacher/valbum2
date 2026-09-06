/// Tests of the Wi-Fi-only camera-roll sync (issue #36): the setting itself,
/// the refusal of a run on a metered network, the run that goes ahead on an
/// unmetered one, the switch on the settings screen, and the background run
/// that obeys the same rule.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

/// The server the tests talk to, as a user would type it.
const String serverUrl = "http://server/valbum/";

/// The data URL derived from it.
const String serverDataUrl = "http://server/valbum/data";

/// The album new photos go into.
const List<String> inbox = ["2026-03-01 Inbox"];

/// A photo taken [minute] minutes after noon.
PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// The engine of a test: fake library, fake network, fake timers, in-memory
/// store.
class Harness {
  final FakePhotoLibrary library;
  final FakeConnectivity network;
  final InMemorySettingsStore store;
  final FakeTimers timers = FakeTimers();

  /// The bodies of the uploads the server received.
  final List<String> uploads = [];

  /// Every request the client sent.
  final List<http.Request> requests = [];

  late final CameraRollSync sync;

  Harness({
    List<PhotoItem>? items,
    NetworkKind network = NetworkKind.mobile,
    CameraRollConfig config =
        const CameraRollConfig(enabled: true, inbox: inbox),
  })  : library = FakePhotoLibrary(items: items),
        network = FakeConnectivity(network),
        store = InMemorySettingsStore() {
    store.cameraRoll = config.toJson();
    var client = VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: MockClient((request) async {
        requests.add(request);
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
      connectivity: this.network,
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
  group('the setting', () {
    test('is on where the store never heard of it', () async {
      var store = InMemorySettingsStore();
      // Exactly the blob issue #30 wrote: no word about Wi-Fi.
      store.cameraRoll = '{"enabled":true,"inbox":["Inbox"],"done":[]}';

      var config = await store.loadCameraRollConfig();

      expect(config.wifiOnly, isTrue);
      expect(config.enabled, isTrue, reason: "the old meaning is unchanged");
      expect(config.inbox, ["Inbox"]);
    });

    test('is on in a fresh configuration', () {
      expect(const CameraRollConfig().wifiOnly, isTrue);
      expect(CameraRollConfig.disabled.wifiOnly, isTrue);
    });

    test('round-trips through the store, in both positions', () async {
      var store = InMemorySettingsStore();

      await store.saveCameraRollConfig(
        const CameraRollConfig(enabled: true, wifiOnly: false, inbox: inbox),
      );
      expect((await store.loadCameraRollConfig()).wifiOnly, isFalse);

      await store.saveCameraRollConfig(
        const CameraRollConfig(enabled: true, inbox: inbox),
      );
      expect((await store.loadCameraRollConfig()).wifiOnly, isTrue);
    });

    test('survives forgetting the progress', () async {
      var harness = Harness(
        config: const CameraRollConfig(
          enabled: true,
          wifiOnly: false,
          inbox: inbox,
        ),
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.forgetProgress();

      expect((await harness.store.loadCameraRollConfig()).wifiOnly, isFalse);
    });
  });

  group('a run on a metered network', () {
    test('uploads nothing and says why', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.requests, isEmpty);
      expect(harness.sync.status.message, contains("Wi-Fi"));
      expect(harness.sync.status.line, contains("Wi-Fi"));
      // Nothing was handled, so nothing was recorded as handled.
      expect((await harness.store.loadCameraRollConfig()).since, isNull);
    });

    test('is refused on a network the platform cannot name', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.other,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.sync.status.message, contains("Wi-Fi"));
    });

    test('says it has no network at all where there is none', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.none,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.sync.status.message, contains("No network"));
    });

    testWidgets('shows the reason where the other refusals are shown',
        (tester) async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSyncNowKey));
      await tester.pumpAndSettle();

      expect(find.textContaining("Wi-Fi", findRichText: true), findsWidgets);
      expect(harness.uploads, isEmpty);
    });

    test('runs again by itself when Wi-Fi comes back', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      harness.sync.start();
      await pumpEventQueue();
      expect(harness.uploads, isEmpty);

      harness.network.moveTo(NetworkKind.wifi);
      await pumpEventQueue();

      expect(harness.uploads, hasLength(1));
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });
  });

  group('a run on an unmetered network', () {
    test('uploads over Wi-Fi', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.wifi,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, hasLength(1));
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });

    test('uploads over Ethernet', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.ethernet,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, hasLength(1));
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });

    test('runs where the platform cannot say what it is on', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.unknown,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, hasLength(1));
    });
  });

  group('with the setting off', () {
    test('a run uploads over a mobile connection', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        config: const CameraRollConfig(
          enabled: true,
          wifiOnly: false,
          inbox: inbox,
        ),
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, hasLength(1));
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.network.asked, 0, reason: "nothing to ask about");
    });

    test('switching it off starts the refused run', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.uploads, isEmpty);

      await harness.sync.setWifiOnly(false);
      await pumpEventQueue();

      expect(harness.uploads, hasLength(1));
    });
  });

  group('the switch on the settings screen', () {
    testWidgets('is on by default and persists what it is toggled to',
        (tester) async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        network: NetworkKind.wifi,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      var switchFinder = find.descendant(
        of: find.byKey(cameraRollWifiOnlyKey),
        matching: find.byType(Switch),
      );
      expect(tester.widget<Switch>(switchFinder).value, isTrue);

      await tester.tap(find.byKey(cameraRollWifiOnlyKey));
      await tester.pumpAndSettle();

      expect(tester.widget<Switch>(switchFinder).value, isFalse);
      expect((await harness.store.loadCameraRollConfig()).wifiOnly, isFalse);

      await tester.tap(find.byKey(cameraRollWifiOnlyKey));
      await tester.pumpAndSettle();

      expect(tester.widget<Switch>(switchFinder).value, isTrue);
      expect((await harness.store.loadCameraRollConfig()).wifiOnly, isTrue);
    });
  });

  group('a background run', () {
    /// A store holding a switched-on configuration for [inbox].
    InMemorySettingsStore storeWith({bool wifiOnly = true}) {
      var store = InMemorySettingsStore(serverUrl, "token-42", "Phone");
      store.cameraRoll = CameraRollConfig(
        enabled: true,
        wifiOnly: wifiOnly,
        inbox: inbox,
      ).toJson();
      return store;
    }

    /// A transport recording every request, answering as an empty server.
    http.Client transportInto(List<http.Request> requests) =>
        MockClient((request) async {
          requests.add(request);
          if (request.method == "POST") {
            return http.Response('{"present":[]}', 200);
          }
          return http.Response("", 200);
        });

    test('is refused on a mobile connection, and says so', () async {
      var store = storeWith();
      var requests = <http.Request>[];
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);
      var network = FakeConnectivity(NetworkKind.mobile);
      addTearDown(network.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: transportInto(requests),
        connectivity: network,
        clock: () => DateTime.utc(2026, 3, 1, 13),
      );

      expect(requests, isEmpty);
      expect(result.ran, isTrue);
      expect(result.ok, isFalse);
      expect(result.record?.message, contains("Wi-Fi"));
      // The settings screen shows exactly this the next time it is opened.
      expect((await store.loadBackgroundRunRecord())?.message, contains("Wi-Fi"));
      expect((await store.loadCameraRollConfig()).since, isNull);
    });

    test('uploads over Wi-Fi', () async {
      var store = storeWith();
      var requests = <http.Request>[];
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);
      var network = FakeConnectivity(NetworkKind.wifi);
      addTearDown(network.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: transportInto(requests),
        connectivity: network,
        clock: () => DateTime.utc(2026, 3, 1, 13),
      );

      expect(result.ok, isTrue);
      expect(result.record?.stored, 1);
      expect(requests, isNotEmpty);
    });

    test('uploads over a mobile connection with the setting off', () async {
      var store = storeWith(wifiOnly: false);
      var requests = <http.Request>[];
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);
      var network = FakeConnectivity(NetworkKind.mobile);
      addTearDown(network.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: transportInto(requests),
        connectivity: network,
        clock: () => DateTime.utc(2026, 3, 1, 13),
      );

      expect(result.ok, isTrue);
      expect(result.record?.stored, 1);
    });
  });
}
