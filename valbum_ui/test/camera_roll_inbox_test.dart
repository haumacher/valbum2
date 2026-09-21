/// Tests of the camera-roll inbox (issue #54): the album the sync creates in
/// the user's own space when none was chosen, the picker that offers nothing
/// but that space, and the guest who has no space at all.
///
/// What a run does with the photos it finds is pinned in
/// `camera_roll_test.dart`; only the inbox is the subject here.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'package:valbum_ui/notices.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role, {String name = "carol"}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "$role", "space": "$name"}';

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
  CallerInfo? caller = const CallerInfo(role: roleMember, space: "carol"),
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
  group('the inbox a run creates', () {
    test('is created at the root of the own space and stored where it landed',
        () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      // One creation, addressed at the root of the caller's own space, with
      // the album the app sends as its body.
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(server.creations, hasLength(1));
      expect(server.creations.single.url, "$serverDataUrl/Inbox/");
      var sent = Resource.fromString(server.creations.single.body);
      expect(sent, isA<AlbumInfo>());
      // The name is in the URL the creation is addressed to; the body is the
      // sidecar of the new album.
      expect((sent as AlbumInfo).title, "Inbox");
      // And it is an *inbox*, not an ordinary album (issues #131, #136): what
      // the sync uploads is exactly what waits to be sorted.
      expect(sent.kind, AlbumKind.inbox);
      expect(sent.date, 0);
      // The placement rule of the root filed it away; the sync believes the
      // server, not the path it asked for.
      expect(harness.sync.config.inbox, ["2026", "Inbox"]);
      expect(
        (await harness.store.loadCameraRollConfig()).inbox,
        ["2026", "Inbox"],
      );
      expect(server.uploadUrls, ["$serverDataUrl/2026/Inbox/"]);
    });

    test('is created once; a second run uses what the first one stored',
        () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();
      harness.library.add(photo("b.jpg"));
      await harness.sync.syncNow();

      expect(server.creations, hasLength(1));
      expect(server.uploadUrls, [
        "$serverDataUrl/2026/Inbox/",
        "$serverDataUrl/2026/Inbox/",
      ]);
    });

    test('is the album that is already there when the name is taken', () async {
      var server = FakeServer();
      server.onCreate = (_) => http.Response(
            refusal("'Inbox' already exists in the target folder; "
                "nothing is overwritten."),
            409,
          );
      // The album the server refused to overwrite.
      server.onGet = (request) => request.url.path.contains("/Inbox")
          ? http.Response(fixture("album.json"), 200)
          : http.Response('["ListingInfo",{"path":"","folders":[]}]', 200);
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      // An inbox from an earlier installation is adopted, not a failure.
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.message, isNull);
      expect(harness.sync.config.inbox, ["Inbox"]);
      expect((await harness.store.loadCameraRollConfig()).inbox, ["Inbox"]);
      expect(server.uploadUrls, ["$serverDataUrl/Inbox/"]);
    });

    test('says the server\'s own sentence when the creation is refused',
        () async {
      var server = FakeServer();
      server.onCreate = (_) => http.Response(
            refusal("A guest has no library of their own."),
            403,
          );
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(
          harness.sync.status.message, "A guest has no library of their own.");
      expect(harness.sync.config.inbox, isEmpty);
      expect(server.uploadUrls, isEmpty);
      // The switch stays on and the next run tries again: a refusal that was
      // lifted on the server must not need the user to switch anything.
      expect(harness.sync.config.enabled, isTrue);
      expect(harness.sync.status.phase, CameraRollPhase.waiting);

      await harness.sync.syncNow();
      expect(server.creations, hasLength(2));
    });

    test('is no longer a condition of switching the sync on', () async {
      var server = FakeServer();
      var harness = engine(server, config: CameraRollConfig.disabled);
      await harness.sync.load();

      expect(await harness.sync.setEnabled(true), isNull);
      expect(harness.sync.config.enabled, isTrue);
    });
  });

  group('the inbox picker', () {
    testWidgets('offers the own space only, never a link into another one',
        (WidgetTester tester) async {
      // A photo dropped into a shared album would land in somebody else's
      // library, see `InboxPickerDialog` (issues #50, #54).
      var client = clientReturning(
        '["ListingInfo",{"path":"","folders":['
        '{"name":"2024","title":"2024"},'
        '{"name":"Zoo","title":"Zoo","link":"~alice/2024/Zoo"}]}]',
      );
      await tester.pumpWidget(
        MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: Scaffold(body: InboxPickerDialog(client: client))),
      );
      await tester.pumpAndSettle();

      expect(find.text("2024"), findsWidgets);
      expect(find.text("Zoo"), findsNothing);
    });
  });

  group('a guest', () {
    testWidgets('is told to ask for a space instead of syncing',
        (WidgetTester tester) async {
      await pumpSettingsAs(tester, roleGuest);

      expect(find.byKey(cameraRollNoSpaceKey), findsOneWidget);
      expect(find.text(noticeText(guestNoSpaceNotice, testL10n)), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(cameraRollSwitchKey))
            .onChanged,
        isNull,
      );
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(cameraRollChooseKey))
            .onPressed,
        isNull,
      );
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
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(cameraRollChooseKey))
            .onPressed,
        isNotNull,
      );
    });

    test('uploads nothing although the stored config says the sync is on',
        () async {
      // The role can change under a device: a demoted member carries a
      // switched-on configuration into a library they no longer have.
      var server = FakeServer();
      var harness = engine(
        server,
        config: const CameraRollConfig(enabled: true, inbox: ["Inbox"]),
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
        config: const CameraRollConfig(enabled: true, inbox: ["Inbox"]),
        caller: const CallerInfo(role: roleGuest, space: "carol"),
      );
      await harness.sync.load();
      await harness.sync.syncNow();

      await pumpSection(
        tester,
        harness.sync,
        caller: const CallerInfo(role: roleGuest, space: "carol"),
      );

      expect(find.textContaining(noticeText(guestNoSpaceNotice, testL10n)), findsWidgets);
    });
  });
}
