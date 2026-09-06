/// Review probe of the background camera-roll sync (issue #32), composed with
/// the pieces it borrows: the persisted server settings (#27), the device
/// token (#28), the idempotent upload (#29) and the foreground engine (#30).
///
/// The questions asked here are the ones a unit test cannot: does a run in the
/// background and a run in the foreground share one watermark without either
/// of them transferring a photo twice, does a run that fails halfway keep what
/// it achieved, and does the app that opens afterwards show what happened
/// while nobody was looking?
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fake_timers.dart';
import 'util/fixtures.dart';

const String serverUrl = "http://server/valbum/";
const String dataUrl = "http://server/valbum/data";
const List<String> inbox = ["2026-03-01 Inbox"];

final DateTime noon = DateTime.utc(2026, 3, 1, 12);

PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// A store an app was already configured and paired in.
InMemorySettingsStore configured() {
  var store = InMemorySettingsStore(serverUrl, "token-42", "Phone");
  store.cameraRoll =
      const CameraRollConfig(enabled: true, inbox: inbox).toJson();
  return store;
}

void main() {
  testWidgets('background and foreground share one watermark', (tester) async {
    var uploaded = <String>[];
    var refuse = false;
    var transport = MockClient(servingThumbnails((request) async {
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      if (request.method == "PUT") {
        if (refuse) {
          return http.Response(
            '["ErrorInfo",{"message":"The album is read-only."}]',
            403,
          );
        }
        uploaded.addAll([
          for (var match
              in RegExp('filename="([^"]+)"').allMatches(request.body))
            match.group(1)!
        ]);
        return http.Response("", 200);
      }
      return http.Response(fixture("listing.json"), 200);
    }));

    var store = configured();
    var library = FakePhotoLibrary(items: [photo("one.jpg", 0)]);
    addTearDown(library.dispose);

    // While the app is closed: the platform starts the dispatcher's body.
    var first = await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );
    expect(first.ok, isTrue);
    expect(uploaded, ["one.jpg"]);

    // A photo taken afterwards, still with the app closed.
    library.items.add(photo("two.jpg", 5));
    var second = await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );
    expect(second.record?.stored, 1);
    expect(uploaded, ["one.jpg", "two.jpg"],
        reason: "the watermark of the first run survived the isolate");

    // Now the user opens the app, over the very same store.
    var settings = ServerSettings(store: store, platformDefault: () => null);
    var scheduler = FakeBackgroundScheduler();
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: VAlbumClient(dataUrl: dataUrl, httpClient: transport),
        settings: settings,
        photoLibrary: library,
        backgroundScheduler: scheduler,
      ));
      await tester.pumpAndSettle();
    });
    var sync = tester.state<VAlbumAppState>(find.byType(VAlbumApp)).cameraRoll;

    expect(uploaded, ["one.jpg", "two.jpg"],
        reason: "the foreground start-up run must not offer them again");
    expect(sync.status.phase, CameraRollPhase.idle);
    expect(sync.lastBackgroundRun?.ok, isTrue);
    // An app that starts with a switched-on configuration registers the task
    // again, so that an update does not silently lose it.
    expect(scheduler.scheduled, 1);

    // A third photo, while the app is open: the foreground takes it.
    library.add(photo("three.jpg", 9));
    await withFakeImageHttp(() => tester.pumpAndSettle());
    expect(uploaded, ["one.jpg", "two.jpg", "three.jpg"]);

    // And a run that is refused keeps the watermark where it is.
    refuse = true;
    library.add(photo("four.jpg", 12));
    await withFakeImageHttp(() => tester.pumpAndSettle());
    expect(uploaded, ["one.jpg", "two.jpg", "three.jpg"]);
    expect(sync.config.since, photo("three.jpg", 9).takenAt);
  });

  testWidgets('the settings screen shows what happened while it was closed',
      (tester) async {
    var transport = MockClient(servingThumbnails((request) async {
      if (request.method == "POST") {
        return http.Response(
          '["ErrorInfo",{"message":"This device is not paired."}]',
          401,
        );
      }
      return http.Response(fixture("listing.json"), 200);
    }));

    var store = configured();
    var library = FakePhotoLibrary(items: [photo("one.jpg", 0)]);
    addTearDown(library.dispose);

    var run = await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );
    expect(run.ok, isFalse);
    expect(run.record?.message, contains("not paired"));

    var settings = ServerSettings(store: store, platformDefault: () => null);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: VAlbumClient(dataUrl: dataUrl, httpClient: transport),
        settings: settings,
        photoLibrary: library,
        backgroundScheduler: FakeBackgroundScheduler(),
      ));
      await tester.pumpAndSettle();
    });

    // Open the settings and look at the camera-roll section.
    openServerSettings(tester.element(find.byType(Scaffold).first));
    await withFakeImageHttp(() => tester.pumpAndSettle());

    await tester.dragUntilVisible(
      find.byKey(cameraRollBackgroundKey, skipOffstage: false),
      find.byType(SingleChildScrollView).first,
      const Offset(0, -200),
    );
    await withFakeImageHttp(() => tester.pumpAndSettle());
    var line = tester.widget<Text>(find.byKey(cameraRollBackgroundKey)).data;
    expect(line, contains("Last background sync at"));
    expect(line, contains("failed"));
    expect(line, contains("not paired"));
  });

  test('runOnce keeps the batches the server did accept, arming no timer',
      () async {
    // The second batch is refused: what the first one achieved must survive,
    // so that the next background run continues instead of starting over —
    // and nothing may be left waiting, because the isolate is about to end.
    var accepted = <String>[];
    var uploads = 0;
    var transport = MockClient((request) async {
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      if (request.method == "PUT") {
        uploads++;
        if (uploads > 1) {
          return http.Response(
            '["ErrorInfo",{"message":"Out of disk space."}]',
            507,
          );
        }
        accepted.addAll([
          for (var match
              in RegExp('filename="([^"]+)"').allMatches(request.body))
            match.group(1)!
        ]);
        return http.Response("", 200);
      }
      return http.Response("", 404);
    });

    var store = configured();
    var library = FakePhotoLibrary(items: [
      photo("a.jpg", 1),
      photo("b.jpg", 2),
      photo("c.jpg", 3),
      photo("d.jpg", 4),
    ]);
    addTearDown(library.dispose);
    var timers = FakeTimers();
    var sync = CameraRollSync(
      store: store,
      library: library,
      clientOf: () => VAlbumClient(
        dataUrl: dataUrl,
        token: "token-42",
        httpClient: transport,
      ),
      batchSize: 2,
      clock: () => noon,
      timerFactory: timers.create,
    );
    addTearDown(sync.dispose);
    await sync.load();

    var status = await sync.runOnce();

    expect(status.phase, CameraRollPhase.failed,
        reason: "a background run reports, it does not wait");
    expect(status.message, contains("disk space"));
    expect(timers.pending, isEmpty, reason: "nothing outlives the isolate");
    expect(accepted, ["a.jpg", "b.jpg"]);
    // The watermark stands at the newest item of the accepted batch, so the
    // next run picks up exactly at c.jpg.
    expect((await store.loadCameraRollConfig()).since,
        DateTime.utc(2026, 3, 1, 12, 2));
  });

  test('a library that refuses access is reported, not retried forever',
      () async {
    var store = configured();
    var library = FakePhotoLibrary(
      items: [photo("one.jpg", 0)],
      granted: false,
      accessProblem: "Photo access was revoked in the system settings.",
    );
    addTearDown(library.dispose);
    var requests = 0;
    var transport = MockClient((request) async {
      requests++;
      return http.Response("", 200);
    });

    var result = await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );

    expect(result.ok, isFalse);
    expect(result.record?.message, contains("revoked"));
    expect(requests, 0, reason: "nothing is asked of the server");
  });

  test('a second run overwrites the report of the first', () async {
    // The section shows the *last* background run, not the best one.
    var transport = MockClient((request) async {
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      return http.Response("", 200);
    });
    var store = configured();
    var library = FakePhotoLibrary(items: [photo("one.jpg", 0)]);
    addTearDown(library.dispose);

    await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );
    expect((await store.loadBackgroundRunRecord())?.stored, 1);

    await runBackgroundSync(
      store: store,
      library: library,
      transport: transport,
      clock: () => noon,
    );

    var record = await store.loadBackgroundRunRecord();
    expect(record?.ok, isTrue);
    expect(record?.stored, 0);
    expect(record?.line, contains("0 uploaded, 0 already present"));
  });
}
