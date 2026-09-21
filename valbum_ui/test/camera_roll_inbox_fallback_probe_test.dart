/// Review probe of the inbox fallback (issue #132): the 404 recovery composed
/// with being offline, with an upload that was already half accepted, with a
/// `Inbox` that turns out to be a folder of folders rather than an album, with
/// the deferral of issue #118, and with the watched-album marks.
///
/// The question every test here asks is the same: does the fallback ever fire
/// where the inbox is *not* the thing that is gone, and does it ever swallow
/// what the server said?
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

const String serverDataUrl = "http://server/valbum/data";

const List<String> storedInbox = ["2026-01-01 My Inbox"];

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// The decoded folder of a request, without the trailing slash.
String folderOf(http.Request request) {
  var path = Uri.decodeComponent(request.url.path);
  const prefix = "/valbum/data/";
  var rest = path.startsWith(prefix) ? path.substring(prefix.length) : path;
  return rest.endsWith("/") ? rest.substring(0, rest.length - 1) : rest;
}

bool isCreation(http.Request request) =>
    request.method == "PUT" &&
    (request.headers["content-type"]?.startsWith("application/json") ?? false);

/// A sync over [transport].
({CameraRollSync sync, InMemorySettingsStore store, FakePhotoLibrary library})
    engine(
  http.Client transport, {
  CameraRollConfig config =
      const CameraRollConfig(enabled: true, inbox: storedInbox),
  List<PhotoItem>? items,
  bool offline = false,
  int batchSize = 10,
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = config.toJson();
  var library = FakePhotoLibrary(items: items ?? [photo("a.jpg", 1)]);
  var sync = CameraRollSync(
    store: store,
    library: library,
    batchSize: batchSize,
    isOffline: () => offline,
    clientOf: () => VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: transport,
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
  test('is never reached while the device is offline', () async {
    var requests = <http.Request>[];
    var harness = engine(
      MockClient((request) async {
        requests.add(request);
        return http.Response(refusal("gone"), 404);
      }),
      offline: true,
    );
    await harness.sync.load();

    await harness.sync.syncNow();

    // Nothing was asked, so nothing was found gone, and the chosen inbox
    // survives a week without a network.
    expect(requests, isEmpty);
    expect(harness.sync.config.inbox, storedInbox);
    expect(harness.sync.status.inboxGoneUsing, isNull);
  });

  test('fires on a later batch too, the rest landing in the fallback',
      () async {
    // Every batch of a run is its own request, so an album that is renamed
    // *between* two of them is found by the second one. The rest of the run
    // goes into the fallback inbox rather than failing -- the photos are
    // split over two albums, which is what a rename in the middle of a
    // transfer costs, and the run says where the later ones went.
    var uploads = 0;
    var harness = engine(
      MockClient((request) async {
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (isCreation(request)) {
          return http.Response('{"path":"Inbox"}', 200);
        }
        if (request.method == "PUT") {
          uploads++;
          // The first batch is accepted; then the album is renamed away.
          if (uploads > 1 && folderOf(request) != "Inbox") {
            return http.Response(refusal("The album vanished."), 404);
          }
          return http.Response("", 200);
        }
        return http.Response(refusal("no"), 404);
      }),
      items: [photo("a.jpg", 1), photo("b.jpg", 2)],
      batchSize: 1,
    );
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(harness.sync.config.inbox, ["Inbox"]);
    expect(harness.sync.status.phase, CameraRollPhase.idle);
    expect(harness.sync.status.inboxGoneUsing, "Inbox");
  });

  test('refuses to adopt an "Inbox" that is a folder of folders', () async {
    // The adoption rule of issue #54 is unchanged by the fallback: only an
    // album is an inbox, and the server's own sentence is what is shown.
    var harness = engine(MockClient((request) async {
      if (isCreation(request)) {
        return http.Response(refusal("'Inbox' already exists."), 409);
      }
      if (request.method == "GET") {
        return http.Response(
          '["ListingInfo",{"path":"Inbox","folders":[]}]',
          200,
        );
      }
      return http.Response(refusal("There is no album."), 404);
    }));
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(harness.sync.config.inbox, storedInbox);
    expect(harness.sync.status.message, "'Inbox' already exists.");
    expect(harness.sync.status.inboxGoneUsing, isNull);
  });

  test('keeps the sentence when the run then defers for the index', () async {
    // A run that falls back and then waits for the hash index (issue #118)
    // must not lose what it found out: the next run that works says it.
    var deferring = true;
    var harness = engine(MockClient((request) async {
      var folder = folderOf(request);
      if (isCreation(request)) {
        return http.Response('{"path":"Inbox"}', 200);
      }
      if (folder != "Inbox") {
        return http.Response(refusal("There is no album '$folder'."), 404);
      }
      if (request.method == "POST") {
        return http.Response(
          deferring
              ? '{"present":[],"indexed":{"done":1,"total":4}}'
              : '{"present":[]}',
          200,
        );
      }
      return http.Response("", 200);
    }));
    await harness.sync.load();

    await harness.sync.syncNow();
    expect(harness.sync.status.phase, CameraRollPhase.waiting);
    expect(harness.sync.status.indexing, isTrue);
    expect(harness.sync.config.inbox, ["Inbox"], reason: "Already stored.");

    deferring = false;
    await harness.sync.syncNow();

    expect(harness.sync.status.phase, CameraRollPhase.idle);
    expect(harness.sync.status.inboxGoneUsing, "Inbox");
  });

  test('replaces the inbox once, however many albums are watched', () async {
    var creations = 0;
    var harness = engine(
      MockClient((request) async {
        var folder = folderOf(request);
        if (isCreation(request)) {
          creations++;
          return http.Response('{"path":"Inbox"}', 200);
        }
        if (folder != "Inbox") {
          return http.Response(refusal("There is no album '$folder'."), 404);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        return http.Response("", 200);
      }),
      config: const CameraRollConfig(
        enabled: true,
        inbox: storedInbox,
        sources: ["camera", "screenshots"],
      ),
      items: const [],
    );
    harness.library.addAlbum("camera", [photo("a.jpg", 1)], camera: true);
    harness.library.addAlbum("screenshots", [photo("b.jpg", 2)]);
    await harness.sync.load();

    await harness.sync.syncNow();

    expect(creations, 1);
    expect(harness.sync.status.phase, CameraRollPhase.idle);
    expect(harness.sync.status.inboxGoneUsing, "Inbox");
  });
}
