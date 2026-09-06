/// Tests of camera-roll sync (issue #30): the engine that watches the device's
/// photo library and fills an inbox album, what it persists, what it says when
/// it cannot run, and the settings section the user drives it from.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fake_timers.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The album new photos go into.
const List<String> inbox = ["2026-03-01 Inbox"];

/// A client answering through the given handler, without the thumbnail
/// wrapper: nothing in a camera-roll sync fetches an image.
VAlbumClient syncClient(
  Future<http.Response> Function(http.Request request) handler,
) =>
    VAlbumClient(dataUrl: serverDataUrl, httpClient: MockClient(handler));

/// The answer to an upload check that knows none of the asked hashes.
const String knowsNothing = '{"present":[]}';

/// The answer to an upload check that already holds every asked content.
String knowsEverything(http.Request request) {
  var asked = [
    for (var entry in (jsonDecode(request.body)["hashes"] as List))
      entry["hash"]
  ];
  return '{"present":[${asked.map((hash) => '{"hash":"$hash",'
      '"name":"known.jpg"}').join(",")}]}';
}

/// A photo taken [minute] minutes after noon.
PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// The engine of a test: fake library, fake timers, in-memory store.
class Harness {
  final FakePhotoLibrary library;
  final InMemorySettingsStore store;
  final FakeTimers timers = FakeTimers();

  /// The bodies of the uploads the server received.
  final List<String> uploads = [];

  /// Every request the client sent.
  final List<http.Request> requests = [];

  /// Whether the app believes it is offline.
  bool offline = false;

  /// What the check endpoint answers; the default knows nothing.
  String Function(http.Request request) check = (_) => knowsNothing;

  /// Answers the upload, or throws to fail it.
  ///
  /// The default accepts everything with an empty body, which the client reads
  /// as "all stored", see [VAlbumClient.uploadResult].
  Future<http.Response> Function(http.Request request) upload =
      (_) async => http.Response("", 200);

  late final CameraRollSync sync;

  Harness({
    List<PhotoItem>? items,
    PhotoLibrary? photoLibrary,
    CameraRollConfig config =
        const CameraRollConfig(enabled: true, inbox: inbox),
    int batchSize = 10,
    bool withServer = true,
  })  : library = photoLibrary is FakePhotoLibrary
            ? photoLibrary
            : FakePhotoLibrary(items: items),
        store = InMemorySettingsStore() {
    store.cameraRoll = config.toJson();
    var client = withServer
        ? syncClient((request) async {
            requests.add(request);
            if (request.method == "POST") {
              return http.Response(check(request), 200);
            }
            if (request.method == "PUT") {
              uploads.add(request.body);
              return upload(request);
            }
            return http.Response(fixture("listing.json"), 200);
          })
        : null;
    sync = CameraRollSync(
      store: store,
      library: photoLibrary ?? library,
      clientOf: () => client,
      isOffline: () => offline,
      batchSize: batchSize,
      clock: () => timers.now,
      timerFactory: timers.create,
    );
  }

  /// Reads the stored configuration and arms the triggers.
  Future<void> start() async {
    await sync.load();
    sync.start();
    await pumpEventQueue();
  }

  /// The names of the files of every upload, in order.
  List<List<String>> get uploadedNames => [
        for (var body in uploads)
          [
            for (var match in RegExp('filename="([^"]+)"').allMatches(body))
              match.group(1)!
          ]
      ];

  void dispose() {
    sync.dispose();
    library.dispose();
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
  group('CameraRollConfig', () {
    test('round-trips through the store', () async {
      var store = InMemorySettingsStore();
      var config = CameraRollConfig(
        enabled: true,
        inbox: const ["holidays", "2026"],
        since: DateTime.utc(2026, 3, 1, 12, 30),
        done: const ["a", "b"],
      );

      await store.saveCameraRollConfig(config);

      expect(await store.loadCameraRollConfig(), config);
    });

    test('loads as disabled from a store that never heard of it', () async {
      var store = InMemorySettingsStore();

      var config = await store.loadCameraRollConfig();

      expect(config.enabled, isFalse);
      expect(config.inbox, isEmpty);
      expect(config.since, isNull);
      expect(config, CameraRollConfig.disabled);
    });

    test('loads as disabled from a blob it cannot read', () {
      expect(CameraRollConfig.parse("{not json"), CameraRollConfig.disabled);
      expect(CameraRollConfig.parse("[1,2,3]"), CameraRollConfig.disabled);
      expect(CameraRollConfig.parse(""), CameraRollConfig.disabled);
    });
  });

  group('a run', () {
    test('uploads the new items in batches and reports the counts', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2), photo("c.jpg", 3)],
        batchSize: 2,
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, [
        ["a.jpg", "b.jpg"],
        ["c.jpg"],
      ]);
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.lastStored, 3);
      expect(harness.sync.status.lastPresent, 0);
      expect(harness.sync.status.line, contains("Synced 3 photos"));
    });

    test('uploads into the chosen inbox album', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(
        harness.requests.map((r) => r.url.toString()),
        [
          "$serverDataUrl/2026-03-01%20Inbox/?action=check",
          "$serverDataUrl/2026-03-01%20Inbox/",
        ],
      );
    });

    test('advances the watermark and remembers the ids at it', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 5), photo("c.jpg", 5)],
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      var stored = await harness.store.loadCameraRollConfig();
      expect(stored.since, DateTime.utc(2026, 3, 1, 12, 5));
      expect(stored.done..sort(), ["b.jpg", "c.jpg"]);
    });

    test('uploads nothing at all on a second run without new items', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await harness.sync.syncNow();
      harness.uploads.clear();
      harness.requests.clear();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.requests, isEmpty, reason: "Nothing to even ask about.");
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.line, contains("Nothing new"));
    });

    test('picks up an item added after the previous run', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await harness.sync.syncNow();

      harness.library.items.add(photo("b.jpg", 9));
      await harness.sync.syncNow();

      expect(harness.uploadedNames, [
        ["a.jpg"],
        ["b.jpg"],
      ]);
    });
  });

  group('idempotency', () {
    test('transfers nothing when the server holds every content', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2)],
      );
      addTearDown(harness.dispose);
      harness.check = knowsEverything;
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty,
          reason: "The check answered 'present' for every hash.");
      expect(harness.sync.status.lastStored, 0);
      expect(harness.sync.status.lastPresent, 2);
      expect(harness.sync.status.line, contains("Synced 2 photos"));
    });

    test('a reinstalled app converges without re-uploading', () async {
      // No watermark at all, as after a reinstall: the whole library is
      // scanned, and the server answers that it has everything.
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2), photo("c.jpg", 3)],
        config: const CameraRollConfig(enabled: true, inbox: inbox),
        batchSize: 2,
      );
      addTearDown(harness.dispose);
      harness.check = knowsEverything;
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect((await harness.store.loadCameraRollConfig()).since,
          DateTime.utc(2026, 3, 1, 12, 3));
    });
  });

  group('failures', () {
    test('keeps the watermark at the last good batch and retries', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2), photo("c.jpg", 3)],
        batchSize: 1,
      );
      addTearDown(harness.dispose);
      var attempt = 0;
      harness.upload = (_) async {
        if (++attempt == 2) {
          throw http.ClientException("connection reset");
        }
        return http.Response("", 200);
      };
      await harness.sync.load();

      await harness.sync.syncNow();

      var stored = await harness.store.loadCameraRollConfig();
      expect(stored.since, DateTime.utc(2026, 3, 1, 12, 1),
          reason: "Only the first batch was accepted.");
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(harness.sync.status.message, contains("connection reset"));
      expect(harness.sync.status.nextAttempt,
          harness.timers.now.add(const Duration(seconds: 30)));
      expect(harness.sync.status.line, contains("retrying at"));
      expect(harness.timers.pending, contains(const Duration(seconds: 30)));
    });

    test('backs off further with every failed attempt', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async => throw http.ClientException("no route");
      await harness.sync.load();

      await harness.sync.syncNow();
      expect(harness.timers.pending, [const Duration(seconds: 30)]);

      // The scheduled retry fires and fails again.
      expect(harness.timers.fire(const Duration(seconds: 30)), 1);
      await pumpEventQueue();
      expect(harness.timers.pending, [const Duration(minutes: 1)]);

      expect(harness.timers.fire(const Duration(minutes: 1)), 1);
      await pumpEventQueue();
      expect(harness.timers.pending, [const Duration(minutes: 2)]);
    });

    test('caps the back-off at half an hour', () {
      expect(retryDelays.first, const Duration(seconds: 30));
      expect(retryDelays.last, const Duration(minutes: 30));
      for (var index = 1; index < retryDelays.length - 1; index++) {
        expect(retryDelays[index], retryDelays[index - 1] * 2);
      }
    });

    test('"Sync now" runs at once and forgets the back-off', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      var fail = true;
      harness.upload = (_) async {
        if (fail) {
          throw http.ClientException("no route");
        }
        return http.Response("", 200);
      };
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.timers.fire(const Duration(seconds: 30)), 1);
      await pumpEventQueue();
      expect(harness.sync.nextDelay, const Duration(minutes: 2),
          reason: "Two attempts have failed.");

      await harness.sync.syncNow();
      expect(harness.timers.pending, [const Duration(seconds: 30)],
          reason: "The user asked, so the back-off starts over.");

      fail = false;
      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.message, isNull);
      expect(harness.timers.pending, isEmpty);
      expect(harness.sync.nextDelay, const Duration(seconds: 30));
    });

    test('surfaces the reason a server refuses the upload', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async => http.Response(
            '["ErrorInfo",{"message":"Pair this device."}]',
            401,
          );
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(harness.sync.status.message, "Pair this device.");
      expect(harness.sync.status.line, contains("Pair this device."));
    });

    test('refuses to run while the app is offline', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.offline = true;
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(harness.sync.status.message, contains("Offline"));
    });

    test('refuses to run without an inbox album', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        config: const CameraRollConfig(enabled: true),
      );
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.failed);
      expect(harness.sync.status.message, contains("inbox album"));
      expect(harness.timers.pending, isEmpty,
          reason: "Retrying cannot fix a missing inbox.");
    });

    test('refuses to run without a server', () async {
      var harness = Harness(items: [photo("a.jpg", 1)], withServer: false);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.failed);
      expect(harness.sync.status.message, contains("server"));
    });

    test('says what the platform said when access is denied', () async {
      var library = FakePhotoLibrary(
        items: [photo("a.jpg", 1)],
        granted: false,
        accessProblem: "Allow photo access for VAlbum.",
      );
      var harness = Harness(photoLibrary: library);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.unavailable);
      expect(harness.sync.status.line, "Allow photo access for VAlbum.");
    });
  });

  group('the triggers', () {
    test('a change of the library starts a run', () async {
      var harness = Harness(items: []);
      addTearDown(harness.dispose);
      await harness.start();
      var scans = harness.library.scans.length;

      harness.library.add(photo("a.jpg", 1));
      await pumpEventQueue();

      expect(harness.library.scans.length, greaterThan(scans));
      expect(harness.uploadedNames, [
        ["a.jpg"],
      ]);
    });

    test('the periodic scan starts a run', () async {
      var harness = Harness(items: []);
      addTearDown(harness.dispose);
      await harness.start();
      harness.library.items.add(photo("a.jpg", 1));

      expect(harness.timers.fire(const Duration(minutes: 15)), 1);
      await pumpEventQueue();

      expect(harness.uploadedNames, [
        ["a.jpg"],
      ]);
      expect(harness.timers.pending, contains(const Duration(minutes: 15)),
          reason: "The next scan is armed again.");
    });

    test('a disabled sync arms no timer and watches nothing', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        config: CameraRollConfig.disabled,
      );
      addTearDown(harness.dispose);

      await harness.start();
      harness.library.announceChange();
      await pumpEventQueue();

      expect(harness.timers.pending, isEmpty);
      expect(harness.uploads, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.disabled);
    });

    test('runs never overlap; a change during a run is picked up after',
        () async {
      var gate = Completer<void>();
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async {
        await gate.future;
        return http.Response("", 200);
      };
      // `start` arms the change stream and triggers the first run.
      await harness.start();
      expect(harness.sync.status.running, isTrue);
      var scans = harness.library.scans.length;

      // The device announces a new photo while the first one is in flight.
      harness.library.add(photo("b.jpg", 2));
      await pumpEventQueue();
      expect(harness.library.scans.length, scans,
          reason: "No second run alongside the running one.");
      expect(harness.uploads, hasLength(1));

      gate.complete();
      await pumpEventQueue();

      expect(
          harness.uploadedNames,
          [
            ["a.jpg"],
            ["b.jpg"],
          ],
          reason: "The change is picked up when the run is through.");
    });

    test('"Stop" ends a run after the batch it is transferring', () async {
      var gate = Completer<void>();
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2)],
        batchSize: 1,
      );
      addTearDown(harness.dispose);
      harness.upload = (_) async {
        await gate.future;
        return http.Response("", 200);
      };
      await harness.sync.load();

      unawaited(harness.sync.syncNow());
      await pumpEventQueue();
      harness.sync.stop();
      gate.complete();
      await pumpEventQueue();

      expect(harness.uploadedNames, [
        ["a.jpg"],
      ]);
      expect(harness.sync.status.running, isFalse);
      expect((await harness.store.loadCameraRollConfig()).since,
          DateTime.utc(2026, 3, 1, 12, 1));
    });
  });

  group('switching the sync on', () {
    test('is refused while no inbox album is chosen', () async {
      var harness = Harness(config: CameraRollConfig.disabled);
      addTearDown(harness.dispose);
      await harness.sync.load();

      var problem = await harness.sync.setEnabled(true);

      expect(problem, contains("inbox album"));
      expect(harness.sync.config.enabled, isFalse);
    });

    test('is refused when the platform has no photo library', () async {
      var harness = Harness(
        photoLibrary: const UnavailablePhotoLibrary("No camera here."),
        config: const CameraRollConfig(inbox: inbox),
      );
      addTearDown(harness.sync.dispose);
      await harness.sync.load();

      expect(await harness.sync.setEnabled(true), "No camera here.");
      expect(harness.sync.config.enabled, isFalse);
    });

    test('stores the choice and starts watching', () async {
      var harness = Harness(config: CameraRollConfig.disabled);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await harness.sync.chooseInbox(inbox);
      expect(await harness.sync.setEnabled(true), isNull);
      await pumpEventQueue();

      expect((await harness.store.loadCameraRollConfig()).enabled, isTrue);
      expect((await harness.store.loadCameraRollConfig()).inbox, inbox);
      expect(harness.timers.pending, contains(const Duration(minutes: 15)));

      await harness.sync.setEnabled(false);
      expect(harness.timers.pending, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.disabled);
    });
  });

  group('the status line', () {
    test('says what every phase means', () {
      expect(const CameraRollStatus().line, contains("off"));
      expect(
        const CameraRollStatus(
          phase: CameraRollPhase.unavailable,
          message: "No photo library on this platform",
        ).line,
        "No photo library on this platform",
      );
      expect(
        const CameraRollStatus(
          phase: CameraRollPhase.running,
          done: 2,
          total: 8,
        ).line,
        "Uploading 3 of 8...",
      );
      expect(
        CameraRollStatus(
          phase: CameraRollPhase.waiting,
          message: "no route",
          nextAttempt: DateTime.utc(2026, 3, 1, 12, 5).toLocal(),
        ).line,
        startsWith("Failed: no route - retrying at "),
      );
      expect(
        const CameraRollStatus(
          phase: CameraRollPhase.failed,
          message: "no server",
        ).line,
        "Failed: no server",
      );
      expect(
        const CameraRollStatus(phase: CameraRollPhase.idle).line,
        "Waiting for new photos.",
      );
      expect(
        CameraRollStatus(
          phase: CameraRollPhase.idle,
          lastSuccess: DateTime.utc(2026, 3, 1, 12),
          lastStored: 1,
        ).line,
        contains("Synced 1 photo at"),
      );
    });

    test('reports the progress of a run', () {
      expect(
        const CameraRollStatus(
          phase: CameraRollPhase.running,
          done: 2,
          total: 8,
        ).progress,
        0.25,
      );
      expect(const CameraRollStatus().progress, isNull);
    });
  });

  group('the settings section', () {
    testWidgets('refuses to switch on without an inbox album',
        (WidgetTester tester) async {
      var harness = Harness(config: CameraRollConfig.disabled);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSwitchKey));
      await tester.pumpAndSettle();

      expect(
          find.textContaining("Choose an inbox album first"), findsOneWidget);
      expect(harness.sync.config.enabled, isFalse);
    });

    testWidgets('says that a platform has no photo library',
        (WidgetTester tester) async {
      var harness = Harness(
        photoLibrary: const UnavailablePhotoLibrary(
          "No photo library on this platform",
        ),
        config: CameraRollConfig.disabled,
      );
      addTearDown(harness.sync.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      expect(find.text("No photo library on this platform"), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(cameraRollSwitchKey))
            .onChanged,
        isNull,
      );
      expect(
        tester.widget<FilledButton>(find.byKey(cameraRollSyncNowKey)).onPressed,
        isNull,
      );
    });

    testWidgets('chooses an inbox album through the picker',
        (WidgetTester tester) async {
      var harness = Harness(config: CameraRollConfig.disabled);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      expect(find.text("No album chosen yet"), findsOneWidget);

      await tester.tap(find.byKey(cameraRollChooseKey));
      await tester.pumpAndSettle();

      // The mocked listing tree: the root lists two folders.
      expect(find.text("Schlosspark Karlsruhe"), findsOneWidget);
      await tester.tap(find.text("2003-06-21 Ausflug"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Use this album"));
      await tester.pumpAndSettle();

      expect(harness.sync.config.inbox, ["2003-06-21 Ausflug"]);
      expect(find.text("2003-06-21 Ausflug"), findsOneWidget);
      expect((await harness.store.loadCameraRollConfig()).inbox,
          ["2003-06-21 Ausflug"]);
    });

    testWidgets('shows the progress of a run and stops it',
        (WidgetTester tester) async {
      var gate = Completer<void>();
      var harness = Harness(
        items: [photo("a.jpg", 1), photo("b.jpg", 2)],
        batchSize: 1,
      );
      addTearDown(harness.dispose);
      harness.upload = (_) async {
        await gate.future;
        return http.Response("", 200);
      };
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSyncNowKey));
      await tester.pump();
      await tester.pump();

      expect(find.text("Uploading 1 of 2..."), findsOneWidget);
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      expect(find.byKey(cameraRollStopKey), findsOneWidget);

      await tester.tap(find.byKey(cameraRollStopKey));
      gate.complete();
      await tester.pumpAndSettle();

      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(harness.uploads, hasLength(1));
    });

    testWidgets('"Sync now" runs a sync and reports what it did',
        (WidgetTester tester) async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSyncNowKey));
      await tester.pumpAndSettle();

      expect(harness.uploadedNames, [
        ["a.jpg"],
      ]);
      expect(find.textContaining("Synced 1 photo at"), findsOneWidget);
    });

    testWidgets('says why a run failed and when it is retried',
        (WidgetTester tester) async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async => http.Response(
            '["ErrorInfo",{"message":"Pair this device."}]',
            401,
          );
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSyncNowKey));
      await tester.pumpAndSettle();

      expect(
        find.textContaining("Failed: Pair this device. - retrying at"),
        findsOneWidget,
      );
    });
  });

  group('the settings screen of the app', () {
    testWidgets('carries the camera-roll section', (WidgetTester tester) async {
      var library = FakePhotoLibrary();
      addTearDown(library.dispose);
      var client = clientReturning(fixture("listing.json"));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, photoLibrary: library),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        await tester.tap(find.text("Server..."));
        await tester.pumpAndSettle();
        // The section sits below the pairing section of a long screen.
        await tester.scrollUntilVisible(
          find.byKey(cameraRollSwitchKey),
          300,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.pumpAndSettle();
      });

      expect(find.text("Camera roll"), findsOneWidget);
      expect(find.byKey(cameraRollSwitchKey), findsOneWidget);
      expect(find.text("No album chosen yet"), findsOneWidget);
    });
  });

  group('the app-bar indicator', () {
    testWidgets('appears while a sync runs and opens the settings',
        (WidgetTester tester) async {
      var gate = Completer<void>();
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async {
        await gate.future;
        return http.Response("", 200);
      };
      await harness.sync.load();

      await tester.pumpWidget(
        MaterialApp(
          home: CameraRollScope(
            sync: harness.sync,
            child: Scaffold(
              appBar: AppBar(actions: const [CameraRollIndicator()]),
              body: const SizedBox.shrink(),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(cameraRollIndicatorKey), findsNothing);

      unawaited(harness.sync.syncNow());
      await tester.pump();
      await tester.pump();

      expect(find.byKey(cameraRollIndicatorKey), findsOneWidget);
      expect(
        tester.widget<IconButton>(find.byKey(cameraRollIndicatorKey)).tooltip,
        "Uploading 1 of 1...",
      );

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(cameraRollIndicatorKey), findsNothing);
    });

    testWidgets('the listing shows it while a sync runs',
        (WidgetTester tester) async {
      var gate = Completer<void>();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);
      var store = InMemorySettingsStore()
        ..cameraRoll =
            const CameraRollConfig(enabled: true, inbox: inbox).toJson();
      var client = VAlbumClient(
        dataUrl: serverDataUrl,
        httpClient: MockClient(servingThumbnails((request) async {
          if (request.method == "POST") {
            return http.Response(knowsNothing, 200);
          }
          if (request.method == "PUT") {
            await gate.future;
            return http.Response("", 200);
          }
          return http.Response(fixture("listing.json"), 200);
        })),
      );
      var settings = ServerSettings(
        store: store,
        platformDefault: () => serverDataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          settings: settings,
          photoLibrary: library,
        ));
        await tester.pumpAndSettle();

        expect(find.byKey(cameraRollIndicatorKey), findsOneWidget,
            reason: "The sync starts with the app.");

        gate.complete();
        await tester.pumpAndSettle();
      });

      expect(find.byKey(cameraRollIndicatorKey), findsNothing);
    });
  });
}
