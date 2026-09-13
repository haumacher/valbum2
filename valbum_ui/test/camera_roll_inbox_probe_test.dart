/// Review probe of the camera-roll inbox (issue #54): the first-run creation
/// composed with being offline, with a guest whose device still says
/// "enabled", with a name conflict that is a folder rather than an album, and
/// with the section's own wording.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';

const String serverDataUrl = "http://server/valbum/data";

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

PhotoItem photo(String name) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12),
    );

class Recorder {
  final List<http.Request> requests = [];
  http.Response Function(http.Request request) onCreate =
      (_) => http.Response('{"path":"Inbox"}', 200);
  http.Response Function(http.Request request) onGet =
      (_) => http.Response('["ListingInfo",{"path":"","folders":[]}]', 200);

  List<http.Request> get creations => [
        for (var r in requests)
          if (r.method == "PUT" &&
              (r.headers["content-type"]?.startsWith("application/json") ??
                  false))
            r,
      ];

  List<http.Request> get uploads => [
        for (var r in requests)
          if (r.method == "PUT" &&
              !(r.headers["content-type"]?.startsWith("application/json") ??
                  false))
            r,
      ];

  http.Client get transport => MockClient((request) async {
        requests.add(request);
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          if (request.headers["content-type"]?.startsWith("application/json") ??
              false) {
            return onCreate(request);
          }
          return http.Response("", 200);
        }
        return onGet(request);
      });
}

({CameraRollSync sync, InMemorySettingsStore store, FakePhotoLibrary library})
    engine(
  Recorder server, {
  CallerInfo? caller = const CallerInfo(role: roleMember, space: "carol"),
  bool Function()? isOffline,
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
  var library = FakePhotoLibrary(items: [photo("a.jpg")]);
  var client = VAlbumClient(dataUrl: serverDataUrl, httpClient: server.transport);
  var sync = CameraRollSync(
    store: store,
    library: library,
    clientOf: () => client,
    callerOf: () async => caller,
    isOffline: isOffline,
  );
  addTearDown(() {
    sync.dispose();
    library.dispose();
  });
  return (sync: sync, store: store, library: library);
}

void main() {
  test('offline, the first run creates nothing; back online it creates once',
      () async {
    var server = Recorder();
    var offline = true;
    var harness = engine(server, isOffline: () => offline);
    await harness.sync.load();

    await harness.sync.syncNow();
    expect(server.creations, isEmpty, reason: "nothing to create into while away");
    expect(server.uploads, isEmpty);
    expect(harness.sync.config.inbox, isEmpty);

    offline = false;
    await harness.sync.syncNow();
    expect(server.creations, hasLength(1));
    expect(harness.sync.config.inbox, ["Inbox"]);
    expect(server.uploads.map((r) => r.url.toString()), ["$serverDataUrl/Inbox/"]);
  });

  test('a guest whose device still says "enabled" creates and uploads nothing',
      () async {
    var server = Recorder();
    var harness = engine(
      server,
      caller: const CallerInfo(userName: "eve", role: roleGuest, space: ""),
    );
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(server.creations, isEmpty);
    expect(server.uploads, isEmpty);
    expect(harness.sync.status.message, guestNoSpaceNotice);
    expect(harness.sync.config.inbox, isEmpty, reason: "no album was invented");
  });

  test('a folder named Inbox in the way is the server\'s sentence, not an album',
      () async {
    var server = Recorder();
    server.onCreate = (_) => http.Response(
          refusal("'Inbox' already exists in the target folder; nothing is overwritten."),
          409,
        );
    // What stands there is a folder, which holds no photos.
    server.onGet = (request) => request.url.path.contains("/Inbox")
        ? http.Response('["ListingInfo",{"path":"Inbox","title":"Inbox","folders":[]}]', 200)
        : http.Response('["ListingInfo",{"path":"","folders":[]}]', 200);
    var harness = engine(server);
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(harness.sync.status.message, contains("already exists"));
    expect(server.uploads, isEmpty, reason: "a folder is no inbox");
    expect(harness.sync.config.inbox, isEmpty);
  });

  test('a run whose caller is unknown behaves as before: it does not refuse',
      () async {
    // A server from before the roles, or one that could not be asked:
    // "nobody said" must not read as "guest".
    var server = Recorder();
    var harness = engine(server, caller: null);
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(server.creations, hasLength(1));
    expect(server.uploads, hasLength(1));
  });
}
