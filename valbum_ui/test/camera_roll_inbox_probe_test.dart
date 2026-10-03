/// Review probe of the camera-roll inbox (issues #54, #226): the inbox the
/// server names, composed with being offline, with a guest whose device still
/// says "enabled", and with a server that did not say who is asking.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'package:valbum_ui/notices.dart';

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
  CallerInfo? caller =
      const CallerInfo(role: roleMember, space: "carol", inbox: "Inbox"),
  bool Function()? isOffline,
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
  var library = FakePhotoLibrary(items: [photo("a.jpg")]);
  var client =
      VAlbumClient(dataUrl: serverDataUrl, httpClient: server.transport);
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
  test('offline, a run uploads nothing; back online into the inbox named',
      () async {
    var server = Recorder();
    var offline = true;
    var harness = engine(server, isOffline: () => offline);
    await harness.sync.load();

    await harness.sync.syncNow();
    expect(server.requests, isEmpty, reason: "nothing is asked while away");

    offline = false;
    await harness.sync.syncNow();
    expect(server.creations, isEmpty, reason: "the server makes the inbox");
    expect(
        server.uploads.map((r) => r.url.toString()), ["$serverDataUrl/Inbox/"]);
  });

  test('a guest whose device still says "enabled" uploads nothing', () async {
    var server = Recorder();
    var harness = engine(
      server,
      caller: const CallerInfo(userName: "eve", role: roleGuest, space: ""),
    );
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(server.creations, isEmpty);
    expect(server.uploads, isEmpty);
    expect(harness.sync.status.notice, guestNoSpaceNotice);
  });

  test('a run whose caller is unknown uploads nothing and asks again later',
      () async {
    // A server that could not be asked names no inbox; "nobody said" must
    // neither read as "guest" nor make the app invent an album.
    var server = Recorder();
    var harness = engine(server, caller: null);
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(server.creations, isEmpty);
    expect(server.uploads, isEmpty);
    expect(harness.sync.status.notice, const NoInboxForCaller());
    expect(harness.sync.status.phase, CameraRollPhase.waiting);
  });
}
