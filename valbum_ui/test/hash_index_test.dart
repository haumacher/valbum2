/// Tests of the app half of the space-wide hash index (issue #118): a sync that
/// waits while the server is still reading the library, the choice to go ahead
/// anyway, and what the app says about a photo the server already has
/// somewhere else.
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'camera_roll_test.dart';

/// The answer of a check whose index has read [done] of [total] folders and
/// knows none of the asked hashes.
String indexing(int done, int total) =>
    '{"present":[],"indexed":{"done":$done,"total":$total}}';

/// The answer of a check with a complete index that knows none of the hashes.
String indexed(int folders) =>
    '{"present":[],"indexed":{"done":$folders,"total":$folders}}';

/// The answer of a check naming every asked content at [where].
String presentAt(http.Request request, String where) {
  var asked = [
    for (var entry in (jsonDecode(request.body)["hashes"] as List))
      entry["hash"]
  ];
  return '{"present":[${asked.map((hash) => '{"hash":"$hash",'
      '"name":"$where"}').join(",")}],"indexed":{"done":3,"total":3}}';
}

void main() {
  group('a run against an incomplete index', () {
    test('waits, says so and transfers nothing', () async {
      var harness = Harness(items: [photo("a.jpg", 1), photo("b.jpg", 2)]);
      addTearDown(harness.dispose);
      harness.check = (_) => indexing(312, 1480);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      var status = harness.sync.status;
      expect(status.phase, CameraRollPhase.waiting);
      expect(status.indexing, isTrue);
      expect(
        status.message,
        "The library is still being indexed (312 of 1480 folders); photos "
        "already in an unindexed album may be uploaded again.",
      );
      expect(status.line, isNot(contains("Failed")));
      expect(status.line, contains("still being indexed"));
    });

    test('comes back by itself when the index is done', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.check = (_) => indexing(1, 4);
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.uploads, isEmpty);

      // The index finished while the run was waiting; the timer it armed is
      // the one that picks the sync up again.
      harness.check = (_) => indexed(4);
      expect(harness.timers.fire(harness.sync.nextDelay), 1);
      await pumpEventQueue();

      expect(harness.uploadedNames, [
        ["a.jpg"]
      ]);
      expect(harness.sync.status.phase, CameraRollPhase.idle);
      expect(harness.sync.status.indexing, isFalse);
    });

    test('goes ahead where the user said to sync anyway', () async {
      var harness = Harness(
        items: [photo("a.jpg", 1)],
        config: const CameraRollConfig(
          enabled: true,
          inbox: inbox,
          syncWhileIndexing: true,
        ),
      );
      addTearDown(harness.dispose);
      harness.check = (_) => indexing(1, 4);
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, [
        ["a.jpg"]
      ]);
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });

    test('a server that keeps no index is synced as ever', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      // An older server: the answer carries no progress at all.
      harness.check = (_) => knowsNothing;
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, [
        ["a.jpg"]
      ]);
    });
  });

  group('the section', () {
    testWidgets('offers "Sync anyway" while the sync waits', (tester) async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.check = (_) => indexing(2, 9);
      await harness.sync.load();
      await harness.sync.syncNow();

      await pumpSection(tester, harness.sync);

      expect(
          find.textContaining("The library is still being indexed (2 of 9 "
              "folders); photos already in an unindexed album may be uploaded "
              "again."),
          findsOneWidget);
      expect(find.byKey(cameraRollSyncAnywayKey), findsOneWidget);

      harness.check = (_) => indexing(3, 9);
      await tester.tap(find.byKey(cameraRollSyncAnywayKey));
      await tester.pumpAndSettle();

      expect(harness.sync.config.syncWhileIndexing, isTrue,
          reason: "The choice is remembered, not asked again.");
      expect(harness.uploadedNames, [
        ["a.jpg"]
      ]);
    });

    testWidgets('offers nothing of the kind while nothing waits',
        (tester) async {
      var harness = Harness(items: []);
      addTearDown(harness.dispose);
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSyncAnywayKey), findsNothing);
    });
  });

  group('a photo the server already has elsewhere', () {
    test('is counted as present and named in the summary', () async {
      var client = syncClient((request) async {
        if (request.method == "POST") {
          return http.Response(presentAt(request, "2020/Trip/IMG_1.jpg"), 200);
        }
        return http.Response("", 200);
      });

      var summary = await client.uploadNew(
        inbox,
        [uploadOf("a.jpg", "pixels")],
      );

      expect(summary.stored, 0);
      expect(summary.present, 1);
      expect(summary.presentIn, ["2020/Trip/IMG_1.jpg"]);
      expect(summary.message, contains("2020/Trip/IMG_1.jpg"));
    });

    test('says nothing of the kind when it is in the album itself', () async {
      var client = syncClient((request) async {
        if (request.method == "POST") {
          return http.Response(presentAt(request, "known.jpg"), 200);
        }
        return http.Response("", 200);
      });

      var summary = await client.uploadNew(
        inbox,
        [uploadOf("a.jpg", "pixels")],
      );

      expect(summary.present, 1);
      expect(summary.presentIn, isEmpty);
      expect(summary.message, isNot(contains("Mediathek")));
    });

    test('the sync names where it is, once the run is over', () async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.check = (request) => presentAt(request, "2020/Trip/a.jpg");
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty);
      var status = harness.sync.status;
      expect(status.lastPresent, 1);
      expect(status.lastPresentIn, ["2020/Trip/a.jpg"]);
      expect(status.line, contains("1 already there"));
      expect(status.line, contains("Already in the library: 2020/Trip/a.jpg."));
    });
  });

  group('the stored configuration', () {
    test('keeps the choice to sync while indexing', () async {
      var store = InMemorySettingsStore();
      const config = CameraRollConfig(enabled: true, syncWhileIndexing: true);

      await store.saveCameraRollConfig(config);

      expect((await store.loadCameraRollConfig()).syncWhileIndexing, isTrue);
    });

    test('is off in a store that never heard of it', () {
      expect(CameraRollConfig.parse('{"enabled":true}').syncWhileIndexing,
          isFalse);
    });
  });
}

/// One file to upload, with contents of its own.
UploadFile uploadOf(String name, String contents) {
  var bytes = utf8.encode(contents);
  return UploadFile(
    name: name,
    length: bytes.length,
    openRead: () => Stream.value(bytes),
  );
}
