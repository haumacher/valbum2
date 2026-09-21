/// Tests of the inbox fallback (issue #132): the album the camera-roll sync
/// uploads into can simply stop existing — since issue #130 a properties write
/// renames an album's directory — and the run must not be a 404 until the user
/// chooses an inbox again.
///
/// What a run does with the photos it finds is pinned in
/// `camera_roll_test.dart`, how the inbox is created in
/// `camera_roll_inbox_test.dart`; only what happens to a run whose stored
/// inbox is gone is the subject here.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The inbox the user chose before they retitled the album.
const List<String> storedInbox = ["2026-01-01 My Inbox"];

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// A photo taken at noon.
PhotoItem photo(String name) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12),
    );

/// A server that knows one folder and answers 404 for every other one.
///
/// The path of a request arrives percent-encoded — the stale inbox carries
/// blanks — so every decision here is made on the decoded path.
class FakeServer {
  /// Every request that arrived, by its decoded path and query.
  final List<String> requests = [];

  /// The folders the photos were uploaded into, in order.
  final List<String> uploadPaths = [];

  /// The folders an album creation was addressed at, in order.
  final List<String> creations = [];

  /// The folders that are there; everything else answers 404.
  Set<String> folders = {"Inbox"};

  /// The answer to an album creation.
  http.Response Function(String path) onCreate =
      (path) => http.Response('{"path":"Inbox"}', 200);

  /// The refusal of a folder that is not there; a 404 by default, which is
  /// what this issue is about, and any other status for the tests that pin
  /// that nothing but a 404 is it.
  int missingStatus = 404;

  /// The decoded folder path of a request, without the trailing slash.
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
          return http.Response(
            refusal("There is no album '$folder'."),
            missingStatus,
          );
        }
        if (request.method == "POST") {
          // The upload check: the server knows none of the hashes.
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          uploadPaths.add(folder);
          // An empty body means "everything stored".
          return http.Response("", 200);
        }
        // A listing or an album sidecar.
        return http.Response(fixture("album.json"), 200);
      });
}

/// A sync engine over [server], with a fake library and an in-memory store.
({
  CameraRollSync sync,
  InMemorySettingsStore store,
  FakePhotoLibrary library,
}) engine(
  FakeServer server, {
  CameraRollConfig config =
      const CameraRollConfig(enabled: true, inbox: storedInbox),
  List<PhotoItem>? items,
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = config.toJson();
  var library = FakePhotoLibrary(items: items ?? [photo("a.jpg")]);
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
  return (sync: sync, store: store, library: library);
}

void main() {
  group('a run whose stored inbox is gone', () {
    test('creates the default inbox, uploads there and stores the path',
        () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      // The album that was renamed away was asked first, and answered 404.
      expect(
        server.requests.first,
        "${storedInbox.single}?action=check",
      );
      // Exactly one creation, by the rule of a run that has no inbox at all.
      expect(server.creations, ["Inbox"]);
      // The photo landed in the album the fallback made.
      expect(server.uploadPaths, ["Inbox"]);
      expect(harness.sync.config.inbox, ["Inbox"]);
      expect((await harness.store.loadCameraRollConfig()).inbox, ["Inbox"]);
      // The run succeeded, and it says where the photos went.
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.inboxNotice, inboxGoneNotice("Inbox"));
      expect(harness.sync.status.line, contains(inboxGoneNotice("Inbox")));
      expect(harness.sync.status.line, contains("Synced 1 photo"));
    });

    test('adopts an "Inbox" that is already there', () async {
      var server = FakeServer();
      server.onCreate = (_) => http.Response(
            refusal("'Inbox' already exists in the target folder; "
                "nothing is overwritten."),
            409,
          );
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.config.inbox, ["Inbox"]);
      expect((await harness.store.loadCameraRollConfig()).inbox, ["Inbox"]);
      expect(server.uploadPaths, ["Inbox"]);
      expect(harness.sync.status.inboxNotice, inboxGoneNotice("Inbox"));
    });

    test('leaves the watermarks alone, so nothing is uploaded twice', () async {
      var server = FakeServer();
      var harness = engine(
        server,
        config: const CameraRollConfig(
          enabled: true,
          inbox: storedInbox,
          marks: {"camera": SourceMark(done: ["a.jpg"])},
        ),
      );
      await harness.sync.load();

      await harness.sync.syncNow();

      var stored = await harness.store.loadCameraRollConfig();
      expect(stored.inbox, ["Inbox"]);
      expect(stored.markOf("camera").done, contains("a.jpg"));
    });

    test('re-creates the default inbox when that is the one that is gone',
        () async {
      // No loop: the fallback is the same rule, and it runs once per run.
      var server = FakeServer();
      server.folders = {};
      server.onCreate = (_) {
        server.folders = {"Inbox"};
        return http.Response('{"path":"Inbox"}', 200);
      };
      var harness = engine(
        server,
        config: const CameraRollConfig(enabled: true, inbox: ["Inbox"]),
      );
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.creations, ["Inbox"]);
      expect(server.uploadPaths, ["Inbox"]);
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });

    test('fails ordinarily when the new inbox answers 404 as well', () async {
      var server = FakeServer();
      server.folders = {};
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      // One creation, one retry, and then the server's own sentence.
      expect(server.creations, ["Inbox"]);
      expect(server.uploadPaths, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(harness.sync.status.message, contains("There is no album"));
      expect(harness.sync.status.inboxNotice, isNull);
    });

    test('says the notice until the next successful run', () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.sync.status.inboxNotice, inboxGoneNotice("Inbox"));

      harness.library.items.add(photo("b.jpg"));
      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.inboxNotice, isNull);
      expect(server.creations, hasLength(1), reason: "Only the first run.");
    });

    test('says the notice until the user chooses an inbox', () async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();
      await harness.sync.syncNow();

      await harness.sync.chooseInbox(const ["2026", "Pictures"]);

      expect(harness.sync.status.inboxNotice, isNull);
      expect(harness.sync.config.inbox, ["2026", "Pictures"]);
    });

    testWidgets('shows the notice in the section', (WidgetTester tester) async {
      var server = FakeServer();
      var harness = engine(server);
      await harness.sync.load();
      await harness.sync.syncNow();

      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: CallerScope(
          caller: const CallerInfo(role: roleMember, space: "carol"),
          child: CameraRollScope(
            sync: harness.sync,
            child: const Scaffold(
              body: SingleChildScrollView(child: CameraRollSection()),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(
        find.textContaining(inboxGoneNotice("Inbox")),
        findsWidgets,
      );
    });
  });

  group('a run whose stored inbox answers something else', () {
    test('does not fall back on a 403 and says why', () async {
      var server = FakeServer();
      server.missingStatus = 403;
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.creations, isEmpty);
      expect(server.uploadPaths, isEmpty);
      expect(harness.sync.config.inbox, storedInbox);
      expect((await harness.store.loadCameraRollConfig()).inbox, storedInbox);
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
      expect(harness.sync.status.message, contains("There is no album"));
      expect(harness.sync.status.inboxNotice, isNull);
    });

    test('does not fall back on a 500 either', () async {
      var server = FakeServer();
      server.missingStatus = 500;
      var harness = engine(server);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.creations, isEmpty);
      expect(harness.sync.config.inbox, storedInbox);
      expect(harness.sync.status.phase, CameraRollPhase.waiting);
    });
  });

  group('a run with nothing new', () {
    test('asks the server nothing at all', () async {
      var server = FakeServer();
      var harness = engine(
        server,
        config: const CameraRollConfig(enabled: true, inbox: ["Inbox"]),
      );
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      server.requests.clear();
      server.creations.clear();
      server.uploadPaths.clear();

      await harness.sync.syncNow();

      expect(server.requests, isEmpty);
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.line, contains("Nothing new"));
    });
  });
}
