/// Adversarial probes of camera-roll sync (issue #30): the cases where a
/// watermark could lose a photo, where a restart could upload one twice, and
/// where the engine has to keep its promise not to run two syncs at once.
library;

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_timers.dart';

const String serverDataUrl = "http://server/valbum/data";
const List<String> inbox = ["Inbox"];
const String knowsNothing = '{"present":[]}';

/// A photo taken [minute] minutes after noon, with its own id.
PhotoItem photo(String name, int minute, {String? id}) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
      id: id,
    );

/// An engine over the given store, so that two engines can share one store —
/// which is what a restarted app is.
CameraRollSync engine({
  required SettingsStore store,
  required PhotoLibrary library,
  required FakeTimers timers,
  required Future<http.Response> Function(http.Request request) upload,
  List<String>? uploads,
  int batchSize = 10,
}) =>
    CameraRollSync(
      store: store,
      library: library,
      clientOf: () => VAlbumClient(
        dataUrl: serverDataUrl,
        httpClient: MockClient((request) async {
          if (request.method == "POST") {
            return http.Response(knowsNothing, 200);
          }
          if (request.method == "PUT") {
            // Recorded only once the answer is there: an upload that the
            // server never accepted is not an upload.
            var response = await upload(request);
            uploads?.add(request.body);
            return response;
          }
          return http.Response("{}", 404);
        }),
      ),
      batchSize: batchSize,
      clock: () => timers.now,
      timerFactory: timers.create,
    );

/// The file names of an upload body.
List<String> namesIn(String body) => [
      for (var match in RegExp('filename="([^"]+)"').allMatches(body))
        match.group(1)!
    ];

void main() {
  test('a failure between two photos of the same second loses neither',
      () async {
    // Both photos carry the same taken-at stamp. If the watermark were only a
    // stamp, accepting the first and failing on the second would either skip
    // the second forever or offer the first again.
    var library = FakePhotoLibrary(items: [
      photo("a.jpg", 5, id: "a"),
      photo("b.jpg", 5, id: "b"),
    ]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var uploads = <String>[];
    var attempt = 0;
    var sync = engine(
      store: store,
      library: library,
      timers: timers,
      uploads: uploads,
      batchSize: 1,
      upload: (_) async {
        if (++attempt == 2) {
          throw http.ClientException("connection reset");
        }
        return http.Response("", 200);
      },
    );
    addTearDown(sync.dispose);
    await sync.load();

    await sync.syncNow();
    expect(uploads.map(namesIn), [
      ["a.jpg"],
    ]);
    var afterFailure = await store.loadCameraRollConfig();
    expect(afterFailure.since, DateTime.utc(2026, 3, 1, 12, 5));
    expect(afterFailure.done, ["a"]);

    await sync.syncNow();

    expect(
        uploads.map(namesIn),
        [
          ["a.jpg"],
          ["b.jpg"],
        ],
        reason: "The second photo of that second is still owed.");
  });

  test('a restarted app continues at the watermark it left', () async {
    var library = FakePhotoLibrary(items: [photo("a.jpg", 1, id: "a")]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var uploads = <String>[];
    Future<http.Response> accept(http.Request request) async =>
        http.Response("", 200);

    var first = engine(
      store: store,
      library: library,
      timers: timers,
      uploads: uploads,
      upload: accept,
    );
    await first.load();
    await first.syncNow();
    first.dispose();

    // A new engine over the same store: the app was restarted.
    var second = engine(
      store: store,
      library: library,
      timers: timers,
      uploads: uploads,
      upload: accept,
    );
    addTearDown(second.dispose);
    await second.load();
    await second.syncNow();

    expect(uploads, hasLength(1), reason: "Nothing was offered twice.");
    expect(second.config.since, DateTime.utc(2026, 3, 1, 12, 1));
  });

  test('contents that cannot be read fail the run and keep the watermark',
      () async {
    var library = FakePhotoLibrary(items: [
      PhotoItem(
        id: "cloud",
        name: "cloud.jpg",
        takenAt: DateTime.utc(2026, 3, 1, 12, 7),
        length: 3,
        openRead: () => Stream<List<int>>.error(
          StateError("not on this device yet"),
        ),
      ),
    ]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var sync = engine(
      store: store,
      library: library,
      timers: timers,
      upload: (_) async => http.Response("", 200),
    );
    addTearDown(sync.dispose);
    await sync.load();

    await sync.syncNow();

    expect(sync.status.phase, CameraRollPhase.waiting);
    expect(sync.status.message, contains("not on this device yet"));
    expect(sync.config.since, isNull,
        reason: "Nothing was accepted, so nothing may be marked as done.");
    expect(timers.pending, [const Duration(seconds: 30)]);
  });

  test('changing the inbox does not offer the whole library again', () async {
    var library = FakePhotoLibrary(items: [photo("a.jpg", 1, id: "a")]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var uploads = <String>[];
    var sync = engine(
      store: store,
      library: library,
      timers: timers,
      uploads: uploads,
      upload: (_) async => http.Response("", 200),
    );
    addTearDown(sync.dispose);
    await sync.load();
    await sync.syncNow();

    await sync.chooseInbox(const ["Another"]);
    await sync.syncNow();

    expect(uploads, hasLength(1));
    expect(sync.config.since, DateTime.utc(2026, 3, 1, 12, 1));
    expect(sync.config.inbox, ["Another"]);
  });

  test('"forget what was uploaded" re-scans but the server refuses duplicates',
      () async {
    var library = FakePhotoLibrary(items: [photo("a.jpg", 1, id: "a")]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var uploads = <String>[];
    var sync = engine(
      store: store,
      library: library,
      timers: timers,
      uploads: uploads,
      upload: (_) async => http.Response(
        '{"files":[{"name":"a.jpg","storedAs":"a.jpg","hash":"h",'
        '"status":"present"}]}',
        200,
      ),
    );
    addTearDown(sync.dispose);
    await sync.load();
    await sync.syncNow();

    await sync.forgetProgress();
    expect(sync.config.since, isNull);
    await sync.syncNow();

    expect(uploads, hasLength(2), reason: "The library was scanned again.");
    expect(sync.status.lastStored, 0);
    expect(sync.status.lastPresent, 1,
        reason: "The server recognised the content it already holds.");
  });

  test('an unknown key in the stored blob does not lose the rest', () {
    var config = CameraRollConfig.parse(
      '{"enabled":true,"inbox":["a","b"],"since":"2026-03-01T12:05:00.000Z",'
      '"done":["x"],"somethingNew":42}',
    );

    expect(config.enabled, isTrue);
    expect(config.inbox, ["a", "b"]);
    expect(config.since, DateTime.utc(2026, 3, 1, 12, 5));
    expect(config.done, ["x"]);
  });

  test('a burst of triggers produces one run, then one more', () async {
    var gate = Completer<void>();
    var library = FakePhotoLibrary(items: [photo("a.jpg", 1, id: "a")]);
    addTearDown(library.dispose);
    var store = InMemorySettingsStore()
      ..cameraRoll =
          const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var timers = FakeTimers();
    var sync = engine(
      store: store,
      library: library,
      timers: timers,
      upload: (_) async {
        await gate.future;
        return http.Response("", 200);
      },
    );
    addTearDown(sync.dispose);
    await sync.load();

    sync.trigger();
    await pumpEventQueue();
    for (var i = 0; i < 5; i++) {
      sync.trigger();
    }
    await pumpEventQueue();
    expect(library.scans, hasLength(1));

    gate.complete();
    await pumpEventQueue();

    expect(library.scans, hasLength(2),
        reason: "Five triggers during one run add up to exactly one more.");
    expect(sync.status.running, isFalse);
  });
}
