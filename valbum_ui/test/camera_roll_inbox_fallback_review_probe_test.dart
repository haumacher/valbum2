/// Review probes of the inbox fallback (issue #132), composed with what the
/// delivery did not look at: a fallback the server refuses, and the store read
/// back by a fresh engine after a fallback worked.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';

const String serverDataUrl = "http://server/valbum/data";
const List<String> storedInbox = ["2026-01-01 My Inbox"];

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

class Server {
  final List<String> requests = [];
  final List<String> uploadPaths = [];
  final List<String> creations = [];
  Set<String> folders = {"Inbox"};
  http.Response Function(String path) onCreate =
      (path) => http.Response('{"path":"Inbox"}', 200);

  static String folderOf(http.Request request) {
    var path = Uri.decodeComponent(request.url.path);
    const prefix = "/valbum/data/";
    var rest = path.startsWith(prefix) ? path.substring(prefix.length) : path;
    return rest.endsWith("/") ? rest.substring(0, rest.length - 1) : rest;
  }

  http.Client get transport => MockClient((request) async {
        var folder = folderOf(request);
        requests.add("$folder?${request.url.query}");
        var creating = request.method == "PUT" &&
            (request.headers["content-type"]?.startsWith("application/json") ??
                false);
        if (creating) {
          creations.add(folder);
          return onCreate(folder);
        }
        if (!folders.contains(folder)) {
          return http.Response(refusal("There is no album '$folder'."), 404);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          uploadPaths.add(folder);
          return http.Response("", 200);
        }
        return http.Response(fixture("album.json"), 200);
      });
}

CameraRollSync engine(Server server, InMemorySettingsStore store,
    FakePhotoLibrary library) {
  var sync = CameraRollSync(
    store: store,
    library: library,
    clientOf: () => VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: server.transport,
    ),
    callerOf: () async => const CallerInfo(role: roleMember, space: "carol"),
  );
  addTearDown(() {
    sync.dispose();
    library.dispose();
  });
  return sync;
}

void main() {
  test('a refused fallback keeps the stored inbox and says the server\'s reason',
      () async {
    var server = Server();
    server.onCreate =
        (_) => http.Response(refusal("You may not contribute here."), 403);
    var store = InMemorySettingsStore();
    store.cameraRoll =
        const CameraRollConfig(enabled: true, inbox: storedInbox).toJson();
    var library = FakePhotoLibrary(items: [
      fakePhoto("a.jpg", "a".codeUnits, takenAt: DateTime.utc(2026, 3, 1, 12)),
    ]);
    var sync = engine(server, store, library);
    await sync.load();

    await sync.syncNow();

    expect(server.creations, ["Inbox"]);
    expect(server.uploadPaths, isEmpty);
    // The choice is not thrown away for a refusal.
    expect(sync.config.inbox, storedInbox);
    expect((await store.loadCameraRollConfig()).inbox, storedInbox);
    expect(sync.status.phase, CameraRollPhase.waiting);
    expect(sync.status.message, contains("You may not contribute here."));
    expect(sync.status.inboxNotice, isNull);
    // The notice belongs to a fallback that worked; it must not leak into a
    // later run that succeeds for another reason.
    server.onCreate = (_) => http.Response('{"path":"Inbox"}', 200);
    await sync.syncNow();
    expect(sync.status.phase, CameraRollPhase.idle);
    expect(sync.status.inboxNotice, inboxGoneNotice("Inbox"),
        reason: "This second run did fall back, so it says so.");
  });

  test('after a fallback, an engine reading the store back uses the new inbox',
      () async {
    var server = Server();
    var store = InMemorySettingsStore();
    store.cameraRoll =
        const CameraRollConfig(enabled: true, inbox: storedInbox).toJson();
    var first = engine(
      server,
      store,
      FakePhotoLibrary(items: [
        fakePhoto("a.jpg", "a".codeUnits,
            takenAt: DateTime.utc(2026, 3, 1, 12)),
      ]),
    );
    await first.load();
    await first.syncNow();
    expect(server.uploadPaths, ["Inbox"]);
    // (The tear-down disposes the first engine.)

    // The app is restarted: a new engine over the same store, one newer photo.
    server.requests.clear();
    var second = engine(
      server,
      InMemorySettingsStore()..cameraRoll = store.cameraRoll,
      FakePhotoLibrary(items: [
        fakePhoto("a.jpg", "a".codeUnits,
            takenAt: DateTime.utc(2026, 3, 1, 12)),
        fakePhoto("b.jpg", "b".codeUnits,
            takenAt: DateTime.utc(2026, 3, 2, 12)),
      ]),
    );
    await second.load();
    expect(second.config.inbox, ["Inbox"]);
    await second.syncNow();

    // Nothing was asked of the album that is gone, nothing was created again,
    // and the watermark carried over: only the newer photo went.
    expect(server.requests.where((r) => r.startsWith(storedInbox.single)),
        isEmpty);
    expect(server.creations, ["Inbox"]);
    expect(server.uploadPaths, ["Inbox", "Inbox"]);
    expect(second.status.phase, CameraRollPhase.idle);
    expect(second.status.line, contains("Synced 1 photo"));
  });
}
