/// Review probe of the camera-roll sync (issue #30) inside the whole app,
/// composed with the server settings (#27), pairing (#28) and the offline
/// state (#31): after a server switch the sync talks to the new server and
/// reports its refusal, and while offline it waits instead of failing.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const List<String> inbox = ["2026-03-01 Inbox"];

void main() {
  testWidgets('a server switch redirects the sync and its refusal is shown',
      (tester) async {
    var requests = <http.Request>[];
    var transport = MockClient(servingThumbnails((request) async {
      requests.add(request);
      if (request.url.host == "other") {
        if (request.method == "GET") {
          return http.Response(fixture("listing.json"), 200);
        }
        return http.Response(
          '["ErrorInfo",{"message":"This server requires a paired device for changes."}]',
          401,
        );
      }
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      if (request.method == "PUT") {
        return http.Response("", 200);
      }
      return http.Response(fixture("listing.json"), 200);
    }));
    var store = InMemorySettingsStore("http://server/valbum/");
    store.cameraRoll =
        const CameraRollConfig(enabled: true, inbox: inbox).toJson();
    var settings = ServerSettings(store: store, platformDefault: () => null);
    var library = FakePhotoLibrary(items: [
      fakePhoto("one.jpg", "one".codeUnits,
          takenAt: DateTime.utc(2026, 3, 1, 12, 0)),
    ]);
    var offline = OfflineState();

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: VAlbumClient(
          dataUrl: "http://server/valbum/data",
          httpClient: transport,
        ),
        settings: settings,
        photoLibrary: library,
        offlineState: offline,
      ));
      await tester.pumpAndSettle();
    });
    var sync = tester.state<VAlbumAppState>(find.byType(VAlbumApp)).cameraRoll;

    // The start-up run uploaded the photo to the first server.
    var uploads = requests.where((r) => r.method == "PUT").toList();
    expect(uploads, hasLength(1));
    expect(uploads.single.url.host, "server");
    expect(uploads.single.url.path, contains("Inbox"));
    var watermark = sync.config.since;
    expect(watermark, isNotNull);

    // A new photo arrives after the app was pointed at another server.
    await settings.save("http://other/valbum/");
    await withFakeImageHttp(() => tester.pumpAndSettle());
    library.add(fakePhoto("two.jpg", "two".codeUnits,
        takenAt: DateTime.utc(2026, 3, 1, 12, 5)));
    await withFakeImageHttp(() => tester.pumpAndSettle());

    var toOther = requests.where((r) => r.url.host == "other").toList();
    expect(toOther.where((r) => r.method == "PUT"), isEmpty,
        reason: "nothing may be uploaded to a server that refused");
    // A refusal is retried with back-off; the reason is on display meanwhile.
    expect(sync.status.phase, CameraRollPhase.waiting);
    expect(sync.status.line, contains("paired device"));
    expect(sync.config.since, watermark, reason: "the watermark stays");

    // Offline: the sync waits rather than failing.
    offline.goneOffline(DateTime.utc(2026, 3, 1));
    sync.trigger();
    await withFakeImageHttp(() => tester.pumpAndSettle());
    expect(sync.status.phase, CameraRollPhase.waiting);
    expect(sync.status.line.toLowerCase(), contains("offline"));
  });
}
