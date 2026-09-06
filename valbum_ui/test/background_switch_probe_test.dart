/// Review probe of the background sync (issue #32) against the conditions a
/// phone meets while the app is closed: the server is away, or the stored
/// server URL can no longer be parsed. Neither may throw out of the task, and
/// both leave a record the settings screen shows.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

const List<String> inbox = ["2026-03-01 Inbox"];

InMemorySettingsStore storeWith(String serverUrl) {
  var store = InMemorySettingsStore(serverUrl, "token-42", "Phone");
  store.cameraRoll =
      const CameraRollConfig(enabled: true, inbox: inbox).toJson();
  return store;
}

FakePhotoLibrary onePhoto() => FakePhotoLibrary(items: [
      fakePhoto("one.jpg", "one".codeUnits,
          takenAt: DateTime.utc(2026, 3, 1, 12)),
    ]);

void main() {
  test('an unreachable server is recorded, not thrown', () async {
    var store = storeWith("http://server/valbum/");
    var result = await runBackgroundSync(
      store: store,
      library: onePhoto(),
      transport: MockClient(
        (request) async =>
            throw http.ClientException("Connection refused", request.url),
      ),
      clock: () => DateTime.utc(2026, 3, 1, 13),
    );

    expect(result.record, isNotNull);
    expect(result.record!.ok, isFalse);
    expect(result.record!.message, contains("Connection refused"));
    var stored = await store.loadBackgroundRunRecord();
    expect(stored?.ok, isFalse);
    // The photo is still due: the watermark did not move.
    expect((await store.loadCameraRollConfig()).since, isNull);
  });

  test('a stored server URL that cannot be parsed is recorded as the reason',
      () async {
    var store = storeWith("not a url");
    var requests = <http.Request>[];
    var result = await runBackgroundSync(
      store: store,
      library: onePhoto(),
      transport: MockClient((request) async {
        requests.add(request);
        return http.Response("", 200);
      }),
    );

    expect(requests, isEmpty);
    expect(result.record, isNotNull);
    expect(result.record!.ok, isFalse);
    expect(result.record!.message!.toLowerCase(), contains("server"));
  });
}
