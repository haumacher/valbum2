/// Tests of the upload target of issue #240: where the new photos of this
/// device go — the inbox by default, for a while an album — and the rules that
/// keep a forgotten or vanished album from losing anything: the end date, the
/// fallback to the inbox on the run's own requests, and the rename that is
/// followed through the hash index.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';
import 'package:valbum_ui/upload_target.dart';

import 'util/l10n.dart';

const String serverDataUrl = "http://server/valbum/data";
const String otherDataUrl = "http://other/valbum/data";

const List<String> inbox = ["Inbox"];
const List<String> mallorca = ["2026", "Mallorca 2026"];

/// Wednesday, 7 October 2026, at noon.
final DateTime today = DateTime(2026, 10, 7, 12);

PhotoItem photo(String name, {int minute = 0}) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 10, 7, 9, minute),
    );

/// A server answering the sync's requests per folder.
class FakeServer {
  final List<http.Request> requests = [];

  /// The folder URLs uploads were addressed to, in order, with the names.
  final List<({String url, List<String> names})> uploads = [];

  /// The status a PUT into the given folder URL is answered with, 200 where
  /// none is named.
  final Map<String, int> putStatus = {};

  /// The status a check of the given folder URL is answered with.
  final Map<String, int> checkStatus = {};

  /// What a check answers present, by hash: the path the photo lies at.
  final Map<String, String> present = {};

  /// The `?type=auth` answer, for a background run that asks it.
  String? auth;

  http.Client get transport => MockClient((request) async {
        if (auth != null && request.url.queryParameters["type"] == "auth") {
          return http.Response(auth!, 200);
        }
        requests.add(request);
        var folder = request.url.replace(query: "").toString();
        if (folder.endsWith("?")) {
          folder = folder.substring(0, folder.length - 1);
        }
        if (request.method == "POST") {
          var status = checkStatus[folder] ?? 200;
          if (status != 200) {
            return http.Response(
                '["ErrorInfo",{"message":"Not here: $status"}]', status);
          }
          var asked = jsonDecode(request.body)["hashes"] as List;
          var found = [
            for (var hash in asked)
              if (present.containsKey(hash["hash"]))
                {"hash": hash["hash"], "name": present[hash["hash"]]}
          ];
          return http.Response(jsonEncode({"present": found}), 200);
        }
        if (request.method == "PUT") {
          var status = putStatus[folder] ?? 200;
          if (status != 200) {
            return http.Response(
                '["ErrorInfo",{"message":"Refused: $status"}]', status);
          }
          uploads.add((
            url: folder,
            names: [
              for (var match
                  in RegExp('filename="([^"]+)"').allMatches(request.body))
                match.group(1)!
            ],
          ));
          return http.Response("", 200);
        }
        return http.Response('["ListingInfo",{"path":"","folders":[]}]', 200);
      });
}

/// The folder URL as the transport sees it, its blanks encoded.
String folderUrl(List<String> path) =>
    Uri.parse("$serverDataUrl/${path.join("/")}/").toString();

/// A sync engine over [server] with the given target stored.
({
  CameraRollSync sync,
  InMemorySettingsStore store,
  FakePhotoLibrary library,
  UploadTargets targets,
}) engine(
  FakeServer server, {
  UploadTarget? target,
  List<PhotoItem>? items,
  DateTime? now,
}) {
  var store = InMemorySettingsStore();
  store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
  if (target != null) {
    store.uploadTargets = jsonEncode({serverDataUrl: target.toJson()});
  }
  var library = FakePhotoLibrary(items: items ?? [photo("a.jpg")]);
  var client =
      VAlbumClient(dataUrl: serverDataUrl, httpClient: server.transport);
  DateTime clock() => now ?? today;
  var targets = UploadTargets(store: store, clock: clock);
  var sync = CameraRollSync(
    store: store,
    library: library,
    clientOf: () => client,
    callerOf: () async =>
        const CallerInfo(role: roleMember, space: "carol", inbox: "Inbox"),
    clock: clock,
    targets: targets,
  );
  addTearDown(() {
    sync.dispose();
    library.dispose();
    targets.dispose();
  });
  return (sync: sync, store: store, library: library, targets: targets);
}

void main() {
  group('the stored setting', () {
    test('is kept per server, and never carried to another', () async {
      var store = InMemorySettingsStore();
      var targets = UploadTargets(store: store, clock: () => today);
      await targets.choose(serverDataUrl,
          const UploadTarget(path: mallorca, title: "Mallorca 2026"));

      expect(targets.targetOf(serverDataUrl)?.path, mallorca);
      expect(targets.targetOf("$serverDataUrl/")?.path, mallorca,
          reason: "a trailing slash names the same server");
      expect(targets.targetOf(otherDataUrl), isNull);

      // A fresh engine over the same store reads the same.
      var again = UploadTargets(store: store, clock: () => today);
      await again.load();
      expect(again.targetOf(serverDataUrl),
          const UploadTarget(path: mallorca, title: "Mallorca 2026"));
      expect(again.targetOf(otherDataUrl), isNull);

      await again.backToInbox(serverDataUrl);
      expect(again.targetOf(serverDataUrl), isNull);
      expect(again.endedOf(serverDataUrl), isNull,
          reason: "going back by hand leaves nothing to say");
    });

    test('ends one week ahead by default', () {
      expect(defaultUploadTargetEnd(today), DateTime(2026, 10, 14));
      // Across a month's end, a calendar week.
      expect(defaultUploadTargetEnd(DateTime(2026, 10, 28, 23)),
          DateTime(2026, 11, 4));
    });

    test('is the target through its last day, and the inbox afterwards', () {
      var target = UploadTarget(path: mallorca, until: DateTime(2026, 10, 14));
      expect(target.expiredAt(DateTime(2026, 10, 14, 23, 59)), isFalse);
      expect(target.expiredAt(DateTime(2026, 10, 15)), isTrue);
      // A cleared end date never expires.
      expect(const UploadTarget(path: mallorca).expiredAt(DateTime(2099)),
          isFalse);
    });

    test('reads an unreadable blob as no target', () async {
      var store = InMemorySettingsStore()..uploadTargets = "{not json";
      var targets = UploadTargets(store: store, clock: () => today);
      await targets.load();
      expect(targets.targetOf(serverDataUrl), isNull);
    });
  });

  group('a run with a target', () {
    test('uploads into the album instead of the inbox', () async {
      var server = FakeServer();
      var harness = engine(server,
          target: const UploadTarget(path: mallorca, title: "Mallorca 2026"));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(server.uploads.map((u) => u.url), [folderUrl(mallorca)]);
      expect(harness.sync.status.lastTarget, "Mallorca 2026");
      expect(harness.sync.status.targetNotice, isNull);
      expect(cameraRollLine(harness.sync.status, testL10n),
          contains(testL10n.uploadTargetWentTo("Mallorca 2026")));
      // The newest photo stored there is what a rename is followed by.
      expect(harness.targets.targetOf(serverDataUrl)?.anchors, hasLength(1));
    });

    test('does not upload a photo already in the inbox again', () async {
      var server = FakeServer();
      var harness = engine(server, target: const UploadTarget(path: mallorca));
      var hash = await sha256Of(Stream.value("a.jpg".codeUnits));
      server.present[hash] = "Inbox/a.jpg";
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploads, isEmpty);
      expect(harness.sync.status.lastPresent, 1);
      expect(harness.sync.status.lastPresentIn, ["Inbox/a.jpg"]);
      // The check went to the target: the space-wide index answered it.
      expect(
          server.requests
              .where((r) => r.method == "POST")
              .single
              .url
              .toString(),
          startsWith(folderUrl(mallorca)));
    });

    test('with nothing new asks the server nothing', () async {
      var server = FakeServer();
      var harness = engine(server,
          target: const UploadTarget(path: mallorca), items: const []);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(server.requests, isEmpty);
      expect(harness.targets.targetOf(serverDataUrl)?.path, mallorca);
    });
  });

  group('the end date', () {
    test(
        'past it, the run uploads to the inbox, says so once and clears the '
        'setting', () async {
      var server = FakeServer();
      var harness = engine(
        server,
        target: UploadTarget(
            path: mallorca,
            title: "Mallorca 2026",
            until: DateTime(2026, 10, 6)),
      );
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploads.map((u) => u.url), [folderUrl(inbox)]);
      var notice = UploadTargetExpired("Mallorca 2026", DateTime(2026, 10, 6));
      expect(harness.sync.status.targetNotice, notice);
      expect(harness.sync.status.lastTarget, isNull);
      expect(cameraRollLine(harness.sync.status, testL10n),
          contains(noticeText(notice, testL10n)));
      expect(harness.targets.targetOf(serverDataUrl), isNull);
      expect(jsonDecode(harness.store.uploadTargets!)[serverDataUrl]["path"],
          isNull,
          reason: "the setting is cleared in the store");

      // Once: the next run has nothing to say about it.
      harness.library.items.add(photo("b.jpg", minute: 5));
      await harness.sync.syncNow();
      expect(harness.sync.status.targetNotice, isNull);
      expect(server.uploads.map((u) => u.url),
          [folderUrl(inbox), folderUrl(inbox)]);
    });

    test('of today still sends to the album', () async {
      var server = FakeServer();
      var harness = engine(server,
          target: UploadTarget(path: mallorca, until: DateTime(2026, 10, 7)));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploads.map((u) => u.url), [folderUrl(mallorca)]);
    });

    test('cleared, never expires', () async {
      var server = FakeServer();
      var harness = engine(server,
          target: const UploadTarget(path: mallorca),
          now: DateTime(2030, 1, 1));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploads.map((u) => u.url), [folderUrl(mallorca)]);
      expect(harness.targets.targetOf(serverDataUrl)?.path, mallorca);
    });

    test('is shown as ended before a run clears it, and dismissed by hand',
        () async {
      var store = InMemorySettingsStore();
      var targets = UploadTargets(store: store, clock: () => today);
      await targets.choose(
          serverDataUrl,
          UploadTarget(
              path: mallorca, title: "M", until: DateTime(2026, 10, 1)));

      expect(targets.targetOf(serverDataUrl), isNull);
      expect(targets.endedOf(serverDataUrl),
          UploadTargetExpired("M", DateTime(2026, 10, 1)));

      await targets.dismiss(serverDataUrl);
      expect(targets.endedOf(serverDataUrl), isNull);
      expect(store.uploadTargets, '{}');
    });
  });

  group('a target that refuses', () {
    for (var (status, notice) in [
      (404, const UploadTargetGone("Mallorca 2026")),
      (403, const UploadTargetRefused("Mallorca 2026")),
      (400, const UploadTargetNotAlbum("Mallorca 2026")),
    ]) {
      test(
          'with $status falls back to the inbox: nothing lost, the setting '
          'cleared, the reason said', () async {
        var server = FakeServer();
        server.putStatus[folderUrl(mallorca)] = status;
        if (status == 404) {
          server.checkStatus[folderUrl(mallorca)] = 404;
        }
        var harness = engine(server,
            target: const UploadTarget(path: mallorca, title: "Mallorca 2026"));
        await harness.sync.load();

        await harness.sync.syncNow();

        expect(harness.sync.status.phase, CameraRollPhase.idle);
        // The photo arrived, in the inbox, in this very run.
        expect(server.uploads.single.url, folderUrl(inbox));
        expect(server.uploads.single.names, ["a.jpg"]);
        expect(harness.sync.status.lastStored, 1);
        expect(harness.sync.status.targetNotice, notice);
        expect(cameraRollLine(harness.sync.status, testL10n),
            contains(noticeText(notice, testL10n)));
        expect(harness.targets.targetOf(serverDataUrl), isNull);
        // Said again on the start page, until it is read.
        expect(harness.targets.endedOf(serverDataUrl), notice);
        // The watermark moved past it: the next run sends nothing again.
        server.requests.clear();
        await harness.sync.syncNow();
        expect(server.requests, isEmpty);
      });
    }

    test('with a transport failure keeps the target and retries', () async {
      var client = VAlbumClient(
        dataUrl: serverDataUrl,
        httpClient: MockClient((_) async => throw http.ClientException("down")),
      );
      var store = InMemorySettingsStore();
      store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
      var targets = UploadTargets(store: store, clock: () => today);
      await targets.choose(serverDataUrl, const UploadTarget(path: mallorca));
      var library = FakePhotoLibrary(items: [photo("a.jpg")]);
      var sync = CameraRollSync(
        store: store,
        library: library,
        clientOf: () => client,
        callerOf: () async =>
            const CallerInfo(role: roleMember, space: "carol", inbox: "Inbox"),
        targets: targets,
        timerFactory: (_, __) => _NeverTimer(),
      );
      addTearDown(() {
        sync.dispose();
        library.dispose();
      });
      await sync.load();

      await sync.syncNow();

      expect(sync.status.phase, CameraRollPhase.waiting);
      expect(targets.targetOf(serverDataUrl)?.path, mallorca);
    });
  });

  group('a renamed or moved target', () {
    /// A target `2026/Mallorca` the device stored [stored] photos in; then
    /// the album is renamed or moved on the server, and the index finds each
    /// photo where [where] says (by photo name, the folder it lies in now;
    /// a photo not named there is not found at all).
    Future<
        ({
          FakeServer server,
          ({
            CameraRollSync sync,
            InMemorySettingsStore store,
            FakePhotoLibrary library,
            UploadTargets targets,
          }) harness,
        })> renamed(Map<String, String> where, {int stored = 3}) async {
      const before = ["2026", "Mallorca"];
      var server = FakeServer();
      var names = [for (var i = 0; i < stored; i++) "p$i.jpg"];
      var harness = engine(
        server,
        target: const UploadTarget(path: before, title: "Mallorca"),
        items: [
          for (var (i, name) in names.indexed) photo(name, minute: i)
        ],
      );
      await harness.sync.load();
      // A first run stores the photos there, which the target remembers.
      await harness.sync.syncNow();
      expect(server.uploads.single.url, folderUrl(before));
      var anchors = harness.targets.targetOf(serverDataUrl)!.anchors;
      expect(anchors, hasLength(stored));
      // Then the album is renamed or moved on the server.
      server.checkStatus[folderUrl(before)] = 404;
      server.putStatus[folderUrl(before)] = 404;
      for (var name in names) {
        var folder = where[name];
        if (folder != null) {
          server.present[await sha256Of(Stream.value(name.codeUnits))] =
              "$folder/$name";
        }
      }
      harness.library.items.add(photo("new.jpg", minute: 30));
      server.uploads.clear();
      server.requests.clear();
      return (server: server, harness: harness);
    }

    /// The check requests the run made at the inbox: the one lookup.
    int lookups(FakeServer server) => server.requests
        .where((r) => r.method == "POST" && r.url.path == "/valbum/data/Inbox/")
        .length;

    test('is followed where all its photos agree on a rename', () async {
      var (:server, :harness) = await renamed({
        "p0.jpg": "2026/Mallorca 2026",
        "p1.jpg": "2026/Mallorca 2026",
        "p2.jpg": "2026/Mallorca 2026",
      });

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(["2026", "Mallorca 2026"]));
      expect(server.uploads.single.names, ["new.jpg"]);
      expect(harness.targets.targetOf(serverDataUrl)?.path,
          ["2026", "Mallorca 2026"]);
      expect(harness.sync.status.targetNotice,
          const UploadTargetFollowed("Mallorca", "Mallorca 2026"));
      // One lookup, asked once, on the 404 only.
      expect(lookups(server), 1);
    });

    test('is followed where all its photos agree on a move', () async {
      var (:server, :harness) = await renamed({
        "p0.jpg": "Holidays/Mallorca",
        "p1.jpg": "Holidays/Mallorca",
      });

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(["Holidays", "Mallorca"]));
    });

    test('is not followed into a sibling the last photo was sorted into',
        () async {
      // The album was renamed, and the photo stored last was sorted into
      // "Best of" beside it; the index has forgotten the others.
      var (:server, :harness) = await renamed({"p2.jpg": "2026/Best of"});

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
      expect(server.uploads.single.names, ["new.jpg"]);
      expect(harness.targets.targetOf(serverDataUrl), isNull);
      expect(harness.sync.status.targetNotice,
          const UploadTargetGone("Mallorca"));
      expect(cameraRollLine(harness.sync.status, testL10n),
          contains(noticeText(const UploadTargetGone("Mallorca"), testL10n)));
      expect(lookups(server), 2,
          reason: "the one lookup, then the inbox upload's own check");
    });

    test('is not followed where its photos are split between folders',
        () async {
      var (:server, :harness) = await renamed({
        "p0.jpg": "2026/Mallorca 2026",
        "p1.jpg": "2026/Mallorca 2026",
        "p2.jpg": "2026/Best of",
      });

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
      expect(harness.sync.status.targetNotice,
          const UploadTargetGone("Mallorca"));
    });

    test('is not followed into an unrelated album, even unanimously',
        () async {
      var (:server, :harness) = await renamed({
        "p0.jpg": "Best of/Beaches",
        "p1.jpg": "Best of/Beaches",
      });

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
    });

    test('is not followed into the trash', () async {
      var (:server, :harness) = await renamed({
        "p0.jpg": ".valbum/trash/Mallorca",
        "p1.jpg": ".valbum/trash/Mallorca",
      });

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
    });

    test('with a single upload so far falls back, without a lookup', () async {
      var (:server, :harness) =
          await renamed({"p0.jpg": "2026/Mallorca 2026"}, stored: 1);

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
      expect(lookups(server), 1, reason: "only the inbox upload's own check");
    });

    test('without a photo to find it by falls back to the inbox', () async {
      var server = FakeServer();
      server.putStatus[folderUrl(mallorca)] = 404;
      server.checkStatus[folderUrl(mallorca)] = 404;
      var harness = engine(server, target: const UploadTarget(path: mallorca));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(server.uploads.single.url, folderUrl(inbox));
      expect(
          server.requests.where(
              (r) => r.method == "POST" && r.url.path == "/valbum/data/Inbox/"),
          hasLength(1),
          reason: "only the inbox upload's own check");
    });

    test('remembers no more than the last five photos', () async {
      var server = FakeServer();
      var harness = engine(server,
          target: const UploadTarget(path: mallorca),
          items: [for (var i = 0; i < 7; i++) photo("q$i.jpg", minute: i)]);
      await harness.sync.load();

      await harness.sync.syncNow();

      var anchors = harness.targets.targetOf(serverDataUrl)!.anchors;
      expect(anchors, hasLength(uploadTargetAnchors));
      expect(anchors.last, await sha256Of(Stream.value("q6.jpg".codeUnits)));
    });
  });

  test('followedFolder takes a rename or a move and nothing else', () {
    expect(followedFolder(mallorca, "2026/Mallorca/x.jpg", inbox),
        ["2026", "Mallorca"]);
    expect(followedFolder(mallorca, "Trips/Mallorca 2026/x.jpg", inbox),
        ["Trips", "Mallorca 2026"]);
    expect(followedFolder(mallorca, "2026/Mallorca 2026/x.jpg", inbox), isNull);
    expect(followedFolder(mallorca, "x.jpg", inbox), isNull);
    expect(followedFolder(["Mallorca"], "Inbox/x.jpg", inbox), isNull);
    expect(followedFolder(mallorca, "Other/Album/x.jpg", inbox), isNull);
    expect(followedFolder(mallorca, ".valbum/trash/Mallorca 2026/x.jpg", inbox),
        isNull);
  });

  group('the background sync (#169)', () {
    InMemorySettingsStore configured(UploadTarget target) {
      var store =
          InMemorySettingsStore("http://server/valbum/", "token-42", "Phone");
      store.cameraRoll = const CameraRollConfig(enabled: true).toJson();
      store.uploadTargets = jsonEncode({serverDataUrl: target.toJson()});
      return store;
    }

    http.Client transport(FakeServer server) {
      server.auth = '{"mode":"writes","role":"member","inbox":"Inbox"}';
      return server.transport;
    }

    test('uploads into the same target', () async {
      var server = FakeServer();
      var store = configured(const UploadTarget(path: mallorca));
      var library = FakePhotoLibrary(items: [photo("a.jpg")]);
      addTearDown(library.dispose);

      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: transport(server),
        clock: () => today,
      );

      expect(result.ok, isTrue);
      expect(server.uploads.single.url, folderUrl(mallorca));
    });

    test(
        'past the end date sends to the inbox and leaves the notice for the '
        'app', () async {
      var server = FakeServer();
      var store = configured(UploadTarget(
          path: mallorca,
          title: "Mallorca 2026",
          until: DateTime(2026, 10, 1)));
      var library = FakePhotoLibrary(items: [photo("a.jpg")]);
      addTearDown(library.dispose);

      await runBackgroundSync(
        store: store,
        library: library,
        transport: transport(server),
        clock: () => today,
      );

      expect(server.uploads.single.url, folderUrl(inbox));
      var app = UploadTargets(store: store, clock: () => today);
      await app.load();
      expect(app.targetOf(serverDataUrl), isNull);
      expect(app.endedOf(serverDataUrl),
          UploadTargetExpired("Mallorca 2026", DateTime(2026, 10, 1)));
    });
  });
}

class _NeverTimer implements Timer {
  @override
  void cancel() {}

  @override
  bool get isActive => false;

  @override
  int get tick => 0;
}
