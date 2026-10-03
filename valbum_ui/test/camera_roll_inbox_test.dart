/// Tests of the camera-roll inbox: the one inbox of the space the server names
/// in `?type=auth` (issue #226), which the sync uploads into and never chooses,
/// guesses or creates — and the guest who has no space at all (issue #54).
///
/// What a run does with the photos it finds is pinned in
/// `camera_roll_test.dart`; only the inbox is the subject here.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'package:valbum_ui/notices.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role, {String name = "carol"}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "$role", "space": "$name"'
    '${role == roleGuest ? "" : ', "inbox": "Inbox", "inboxCount": 2'}}';

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// A photo taken at noon.
PhotoItem photo(String name) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12),
    );

/// What the sync asked the server, and what it answered.
class FakeServer {
  /// Every request that arrived.
  final List<http.Request> requests = [];

  /// The album creations: where they were addressed and what they carried.
  final List<({String url, String body})> creations = [];

  /// The URLs the photos were uploaded to, in order.
  final List<String> uploadUrls = [];

  /// The answer to an album creation; the default files it into 2026.
  http.Response Function(http.Request request) onCreate =
      (_) => http.Response('{"path":"2026/Inbox"}', 200);

  /// The answer to a GET; the default is an empty listing.
  http.Response Function(http.Request request) onGet =
      (_) => http.Response('["ListingInfo",{"path":"","folders":[]}]', 200);

  /// The transport to hand to the code under test.
  http.Client get transport => MockClient((request) async {
        requests.add(request);
        if (request.method == "POST") {
          // The upload check: the server knows none of the hashes.
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          // An album creation carries a JSON sidecar, an upload the photos
          // themselves; both are a PUT, see [VAlbumClient.createAlbum].
          if (request.headers["content-type"]?.startsWith("application/json") ??
              false) {
            creations.add((url: request.url.toString(), body: request.body));
            return onCreate(request);
          }
          uploadUrls.add(request.url.toString());
          // An empty body means "everything stored", see [uploadResult].
          return http.Response("", 200);
        }
        return onGet(request);
      });
}

/// A sync engine over [server], with a fake library and an in-memory store.
({
  CameraRollSync sync,
  InMemorySettingsStore store,
  FakePhotoLibrary library,
}) engine(
  FakeServer server, {
  CameraRollConfig config = const CameraRollConfig(enabled: true),
  List<PhotoItem>? items,
  CallerInfo? caller =
      const CallerInfo(role: roleMember, space: "carol", inbox: "Inbox"),
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = config.toJson();
  var library = FakePhotoLibrary(items: items ?? [photo("a.jpg")]);
  var client = VAlbumClient(
    dataUrl: serverDataUrl,
    httpClient: server.transport,
  );
  var sync = CameraRollSync(
    store: store,
    library: library,
    clientOf: () => client,
    callerOf: () async => caller,
  );
  addTearDown(() {
    sync.dispose();
    library.dispose();
  });
  return (sync: sync, store: store, library: library);
}

/// Pumps the camera-roll section on its own, as the given caller.
Future<void> pumpSection(
  WidgetTester tester,
  CameraRollSync sync, {
  CallerInfo? caller,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: CallerScope(
        caller: caller,
        child: CameraRollScope(
          sync: sync,
          child: const Scaffold(
            body: SingleChildScrollView(child: CameraRollSection()),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// Opens the settings screen of the whole app, signed in with the given role.
Future<void> pumpSettingsAs(WidgetTester tester, String role) async {
  var library = FakePhotoLibrary();
  addTearDown(library.dispose);
  var client = VAlbumClient(
    dataUrl: serverDataUrl,
    // Only a signed-in device is asked about, see `app.dart`.
    token: "dev-1",
    httpClient: MockClient(servingThumbnails((request) async {
      if (request.url.query == "type=auth") {
        return http.Response(authOfUser(role), 200);
      }
      return http.Response(fixture("listing.json"), 200);
    })),
  );
  // A signed-in device: only then does the app ask who it is, see
  // `_syncCaller` in `app.dart`.
  var settings = ServerSettings(
    store: InMemorySettingsStore(
        "http://server/valbum/", "dev-1", "Phone", "carol"),
  );
  await settings.load();
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: settings,
      photoLibrary: library,
    ));
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
}

void main() {
  group('the inbox the server names', () {
    test('is where the photos go; nothing is created and nothing stored',
        () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(server.uploadUrls, ["$serverDataUrl/Inbox/"]);
      // The server makes the folder with the first upload into it (#226).
      expect(server.creations, isEmpty);
      expect((await harness.store.loadCameraRollConfig()).toJson(),
          isNot(contains("inbox")));
    });

    test('may lie anywhere the space says', () async {
      var server = FakeServer();
      var harness = engine(server,
          caller: const CallerInfo(
              role: roleContribute, inbox: "Family/Phone Uploads"));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploadUrls, ["$serverDataUrl/Family/Phone%20Uploads/"]);
    });

    test('follows the server when it names another one', () async {
      var server = FakeServer();
      var caller = const CallerInfo(role: roleMember, inbox: "Inbox");
      var store = InMemorySettingsStore();
      store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
      var library = FakePhotoLibrary(items: [photo("a.jpg")]);
      var sync = CameraRollSync(
        store: store,
        library: library,
        clientOf: () =>
            VAlbumClient(dataUrl: serverDataUrl, httpClient: server.transport),
        callerOf: () async => caller,
      );
      addTearDown(() {
        sync.dispose();
        library.dispose();
      });
      await sync.load();
      await sync.syncNow();

      caller = const CallerInfo(role: roleMember, inbox: "Unsorted");
      library.add(photo("b.jpg"));
      await sync.syncNow();

      expect(server.uploadUrls,
          ["$serverDataUrl/Inbox/", "$serverDataUrl/Unsorted/"]);
    });

    test('is what a store written by an older app does not change', () async {
      // An app before #226 stored the inbox it chose or created; it is
      // ignored, and nothing of it is written back.
      var server = FakeServer();
      var store = InMemorySettingsStore();
      store.cameraRoll =
          '{"enabled":true,"wifiOnly":false,"inbox":["2024","Phone"],"done":[]}';
      var config = CameraRollConfig.parse(store.cameraRoll);
      expect(config.enabled, isTrue);
      expect(config.toJson(), isNot(contains("inbox")));

      var harness = engine(server, config: config);
      await harness.sync.load();
      await harness.sync.syncNow();

      expect(server.uploadUrls, ["$serverDataUrl/Inbox/"]);
    });

    test('is missing for a caller who may only look: the run says so',
        () async {
      var server = FakeServer();
      var harness = engine(server,
          caller: const CallerInfo(role: roleView, space: "carol"));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.notice, const NoInboxForCaller());
      // No retry changes what the server names: only the administrator does.
      expect(harness.sync.status.phase, CameraRollPhase.failed);
      expect(server.uploadUrls, isEmpty);
      expect(server.creations, isEmpty);
    });

    test('is asked again where the server did not answer', () async {
      var server = FakeServer();
      var harness = engine(server, caller: null);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.notice, const NoInboxForCaller());
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(server.uploadUrls, isEmpty);
    });

    test('is no condition of switching the sync on', () async {
      var server = FakeServer();
      var harness = engine(server, config: CameraRollConfig.disabled);
      await harness.sync.load();

      expect(await harness.sync.setEnabled(true), isNull);
      expect(harness.sync.config.enabled, isTrue);
    });

    testWidgets('is named in the section, and nothing is offered to choose',
        (WidgetTester tester) async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await pumpSection(tester, harness.sync,
          caller: const CallerInfo(role: roleMember, inbox: "Inbox"));

      expect(find.byKey(cameraRollInboxKey), findsOneWidget);
      expect(
          find.text(testL10n.cameraRollInboxTarget("Inbox")), findsOneWidget);
      expect(find.byIcon(Icons.folder_open), findsNothing);
    });

    testWidgets('is not named where the server names none',
        (WidgetTester tester) async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await pumpSection(tester, harness.sync,
          caller: const CallerInfo(role: roleView));

      expect(find.byKey(cameraRollInboxKey), findsNothing);
    });
  });

  group('a guest', () {
    testWidgets('is told to ask for a space instead of syncing',
        (WidgetTester tester) async {
      await pumpSettingsAs(tester, roleGuest);

      expect(find.byKey(cameraRollNoSpaceKey), findsOneWidget);
      expect(
          find.text(noticeText(guestNoSpaceNotice, testL10n)), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(cameraRollSwitchKey))
            .onChanged,
        isNull,
      );
      expect(find.byKey(cameraRollInboxKey), findsNothing);
    });

    testWidgets('is not what a member sees', (WidgetTester tester) async {
      await pumpSettingsAs(tester, roleMember);

      expect(find.byKey(cameraRollNoSpaceKey), findsNothing);
      expect(find.text(noticeText(guestNoSpaceNotice, testL10n)), findsNothing);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(cameraRollSwitchKey))
            .onChanged,
        isNotNull,
      );
      // The member is told where the photos go, from `?type=auth`.
      expect(find.byKey(cameraRollInboxKey), findsOneWidget);
    });

    test('uploads nothing although the stored config says the sync is on',
        () async {
      // The role can change under a device: a demoted member carries a
      // switched-on configuration into a library they no longer have.
      var server = FakeServer();
      var harness = engine(
        server,
        caller: const CallerInfo(role: roleGuest, space: "carol"),
      );
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.notice, guestNoSpaceNotice);
      expect(harness.sync.status.phase, CameraRollPhase.failed);
      expect(server.creations, isEmpty);
      expect(server.uploadUrls, isEmpty);
    });

    testWidgets('sees that reason in the section', (WidgetTester tester) async {
      var server = FakeServer();
      var harness = engine(
        server,
        caller: const CallerInfo(role: roleGuest, space: "carol"),
      );
      await harness.sync.load();
      await harness.sync.syncNow();

      await pumpSection(
        tester,
        harness.sync,
        caller: const CallerInfo(role: roleGuest, space: "carol"),
      );

      expect(find.textContaining(noticeText(guestNoSpaceNotice, testL10n)),
          findsWidgets);
    });
  });
}
