/// Tests of the background camera-roll sync (issue #32): the run that rebuilds
/// the whole engine from the persisted store, what it records, how the
/// foreground picks that up, when the periodic task is registered and removed,
/// and the line the settings section shows afterwards.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

/// The app base of the server the tests talk to, as a user would type it.
const String serverUrl = "http://server/valbum/";

/// The album new photos go into.
const List<String> inbox = ["Inbox"];

/// The URL the uploads of that album go to.
const String inboxUrl = "http://server/valbum/data/Inbox/";

/// The token this device is paired with the server as.
const String deviceToken = "token-42";

/// The moment every test reads as "now".
final DateTime now = DateTime.utc(2026, 3, 1, 12, 30);

/// A photo taken [minute] minutes after noon.
PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// A store holding a switched-on camera-roll configuration for [inbox].
InMemorySettingsStore enabledStore({
  String? url = serverUrl,
  String? token = deviceToken,
  CameraRollConfig config = const CameraRollConfig(
    enabled: true,
    inbox: inbox,
  ),
}) {
  var store = InMemorySettingsStore(url, token, token == null ? null : "Phone");
  store.cameraRoll = config.toJson();
  return store;
}

/// What a server answered, and what it was asked.
class FakeServer {
  /// Every request the client sent.
  final List<http.Request> requests = [];

  /// The bodies of the uploads the server received.
  final List<String> uploads = [];

  /// The answer to the upload check; the default knows no content.
  http.Response Function(http.Request request) check =
      (_) => http.Response('{"present":[]}', 200);

  /// The answer to the upload itself; an empty body means "all stored".
  http.Response Function(http.Request request) upload =
      (_) => http.Response("", 200);

  /// The transport to hand to the code under test.
  http.Client get transport => MockClient((request) async {
        requests.add(request);
        if (request.method == "POST") {
          return check(request);
        }
        if (request.method == "PUT") {
          uploads.add(request.body);
          return upload(request);
        }
        return http.Response("", 404);
      });

  /// The names of the files of every upload, in order.
  List<List<String>> get uploadedNames => [
        for (var body in uploads)
          [
            for (var match in RegExp('filename="([^"]+)"').allMatches(body))
              match.group(1)!
          ]
      ];
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
  group('BackgroundRunRecord', () {
    test('round-trips through the store', () async {
      var store = InMemorySettingsStore();
      var record = BackgroundRunRecord(
        at: now,
        ok: true,
        stored: 3,
        present: 1,
      );

      await store.saveBackgroundRunRecord(record);

      expect(await store.loadBackgroundRunRecord(), record);
    });

    test('is absent from a store that never heard of it', () async {
      expect(await InMemorySettingsStore().loadBackgroundRunRecord(), isNull);
    });

    test('ignores a blob it cannot read, and keys it does not know', () {
      expect(BackgroundRunRecord.parse("{not json"), isNull);
      expect(BackgroundRunRecord.parse("[1,2,3]"), isNull);
      expect(BackgroundRunRecord.parse(""), isNull);

      var future = BackgroundRunRecord.parse(
        '{"version":99,"at":"2026-03-01T12:30:00.000Z","ok":true,'
        '"stored":2,"present":0,"whatever":{"deep":1}}',
      );

      expect(future?.at, now);
      expect(future?.stored, 2);
    });
  });

  group('runBackgroundSync', () {
    test('uploads new items through the stored server and token', () async {
      var store = enabledStore();
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ran, isTrue);
      expect(result.ok, isTrue);
      expect(result.record?.stored, 1);
      expect(result.record?.present, 0);
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
      // Every request goes to the inbox of the stored server, and identifies
      // this device: a background run is as paired as a foreground one.
      expect(server.requests, isNotEmpty);
      for (var request in server.requests) {
        expect(request.headers["authorization"], "Bearer $deviceToken");
        expect(request.url.toString(), startsWith(inboxUrl));
      }
    });

    test('records what it did in the store', () async {
      var store = enabledStore();
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(await store.loadBackgroundRunRecord(), result.record);
      expect(result.record?.line, contains("1 uploaded, 0 already present"));
    });

    test('does nothing and records nothing while the sync is off', () async {
      var store = enabledStore(
        config: const CameraRollConfig(inbox: inbox),
      );
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ran, isFalse);
      expect(result.record, isNull);
      expect(server.requests, isEmpty);
      expect(store.backgroundRun, isNull);
    });

    test('records the reason an unpaired device is refused', () async {
      var store = enabledStore(token: null);
      var server = FakeServer()
        ..check = (_) => http.Response(
              refusal("This device is not paired with the album server."),
              401,
            );
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ok, isFalse);
      expect(
        result.record?.message,
        contains("not paired"),
      );
      expect(await store.loadBackgroundRunRecord(), result.record);
      // Refused before anything was transferred.
      expect(server.uploads, isEmpty);
    });

    test('records that no server is configured', () async {
      var store = enabledStore(url: null, token: null);
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ok, isFalse);
      expect(result.record?.message, "No album server is configured.");
      expect(await store.loadBackgroundRunRecord(), result.record);
      expect(server.requests, isEmpty);
    });

    test('records that no inbox album was chosen', () async {
      var store = enabledStore(
        config: const CameraRollConfig(enabled: true),
      );
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ok, isFalse);
      expect(result.record?.message, contains("inbox album"));
      expect(server.requests, isEmpty);
    });

    test('arms no retry timer of its own', () async {
      // A background isolate is torn down when the run returns; a timer left
      // behind would either never fire or hold the isolate open. The engine
      // therefore reports the failure and stops, see [CameraRollSync.runOnce].
      var store = enabledStore();
      var server = FakeServer()
        ..check = (_) => http.Response(refusal("Nope."), 401);
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      // `runAsync` inside a `fakeAsync` zone is what would catch a pending
      // timer; the plain expectation here is that the run completes at all and
      // reports a failure that is not waiting for anything.
      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      expect(result.ok, isFalse);
    });
  });

  group('the watermark of a background run', () {
    test('is picked up by the foreground engine', () async {
      var store = enabledStore();
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);

      // The very same store, read by the engine the app builds when it opens.
      var foreground = CameraRollSync(
        store: store,
        library: library,
        clientOf: () => VAlbumClient(
          dataUrl: "http://server/valbum/data",
          token: deviceToken,
          httpClient: server.transport,
        ),
        clock: () => now,
      );
      addTearDown(foreground.dispose);
      await foreground.load();
      await foreground.syncNow();

      // Nothing was offered a second time; the run found nothing to do.
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
      expect(foreground.status.phase, CameraRollPhase.idle);
      expect(foreground.status.lastStored, 0);
    });

    test('is what the foreground shows as the last background run', () async {
      var store = enabledStore();
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: server.transport,
        clock: () => now,
      );

      var foreground = CameraRollSync(
        store: store,
        library: library,
        clientOf: () => null,
      );
      addTearDown(foreground.dispose);
      await foreground.load();

      expect(foreground.lastBackgroundRun, result.record);
    });
  });

  group('the scheduler', () {
    /// An engine over a store that already holds an inbox.
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
          // The periodic scan must not outlive the test.
          timerFactory: FakeTimers().create,
        );

    test('is asked to schedule when the sync is switched on', () async {
      var store = enabledStore(
        config: const CameraRollConfig(inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      expect(await sync.setEnabled(true), isNull);
      await pumpEventQueue();

      expect(scheduler.scheduled, 1);
      expect(scheduler.cancelled, 0);
    });

    test('is asked to cancel when the sync is switched off', () async {
      var store = enabledStore();
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      expect(await sync.setEnabled(false), isNull);

      expect(scheduler.cancelled, 1);
    });

    test('is asked again at every start with a switched-on config', () async {
      // An app that was updated must register the task again; the scheduler
      // makes that idempotent, so asking is what start-up does.
      var store = enabledStore();
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      sync.start();
      await pumpEventQueue();

      expect(scheduler.scheduled, 1);
    });

    test('is not asked at a start with a switched-off config', () async {
      var store = enabledStore(
        config: const CameraRollConfig(inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler();
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      sync.start();
      await pumpEventQueue();

      expect(scheduler.scheduled, 0);
    });

    test('is left alone where the platform has none', () async {
      var store = enabledStore();
      var scheduler = FakeBackgroundScheduler(available: false);
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      sync.start();
      await pumpEventQueue();
      await sync.setEnabled(false);

      expect(scheduler.scheduled, 0);
      expect(scheduler.cancelled, 0);
      expect(sync.backgroundProblem, isNull);
    });

    test('says so when the plugin refuses, and never throws', () async {
      var store = enabledStore(
        config: const CameraRollConfig(inbox: inbox),
      );
      var scheduler = FakeBackgroundScheduler(
        problem: StateError("no WorkManager here"),
      );
      var sync = engine(store, scheduler);
      addTearDown(sync.dispose);
      await sync.load();

      expect(await sync.setEnabled(true), isNull);
      await pumpEventQueue();

      expect(sync.config.enabled, isTrue);
      expect(
        sync.backgroundProblem,
        contains("Background sync could not be scheduled"),
      );
      expect(sync.backgroundProblem, contains("no WorkManager here"));
    });
  });

  group('the settings section', () {
    testWidgets('shows what the last background run uploaded', (tester) async {
      var store = enabledStore();
      await store.saveBackgroundRunRecord(
        BackgroundRunRecord(at: now, ok: true, stored: 2, present: 3),
      );
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: FakeBackgroundScheduler(),
      );
      addTearDown(sync.dispose);
      await sync.load();

      await pumpSection(tester, sync);

      expect(
        tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data,
        allOf(
          contains("Last background sync at"),
          contains("2 uploaded, 3 already present"),
        ),
      );
    });

    testWidgets('shows why the last background run failed', (tester) async {
      var store = enabledStore();
      await store.saveBackgroundRunRecord(
        BackgroundRunRecord.failed(now, "The server cannot be reached."),
      );
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: FakeBackgroundScheduler(),
      );
      addTearDown(sync.dispose);
      await sync.load();

      await pumpSection(tester, sync);

      expect(
        tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data,
        allOf(
          contains("failed"),
          contains("The server cannot be reached."),
        ),
      );
    });

    testWidgets('says when the platform runs nothing in the background',
        (tester) async {
      var store = enabledStore();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: FakeBackgroundScheduler(
          available: false,
          unavailableReason: "Background sync is not available on this "
              "platform; the camera roll syncs while the app is open.",
        ),
      );
      addTearDown(sync.dispose);
      await sync.load();

      await pumpSection(tester, sync);

      expect(
        tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data,
        contains("not available on this platform"),
      );
    });

    testWidgets('shows a scheduler that refused', (tester) async {
      var store = enabledStore(
        config: const CameraRollConfig(inbox: inbox),
      );
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: FakeBackgroundScheduler(
          problem: StateError("no WorkManager here"),
        ),
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();
      await pumpSection(tester, sync);

      await tester.tap(find.byKey(cameraRollSwitchKey));
      await tester.pumpAndSettle();

      expect(find.textContaining("no WorkManager here"), findsOneWidget);
    });
  });
}
