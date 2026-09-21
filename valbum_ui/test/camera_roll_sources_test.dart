/// Tests of the albums the camera-roll sync watches (issue #117): what a
/// configuration without a choice watches, what a run scans, what an album
/// ticked later is fetched from, and the list of tick boxes in the settings.
///
/// Before this the sync asked the platform for the whole photo library — the
/// camera, but also every folder any app ever saved a picture into. What is
/// tested here is above all the migration: a store written before the tick
/// boxes existed must not upload one photo more than it did.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_library_manager.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The album new photos go into.
const List<String> inbox = ["Inbox"];

/// A photo taken [minute] minutes after noon.
PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// The engine of a test: a fake library with albums, an in-memory store, and
/// a server that accepts everything and knows nothing.
class Harness {
  final FakePhotoLibrary library = FakePhotoLibrary();
  final InMemorySettingsStore store = InMemorySettingsStore();

  /// The bodies of the uploads the server received.
  final List<String> uploads = [];

  /// The URLs an album was created at.
  final List<String> created = [];

  late final CameraRollSync sync;

  Harness({
    CameraRollConfig config = const CameraRollConfig(
      enabled: true,
      inbox: inbox,
    ),
  }) {
    store.cameraRoll = config.toJson();
    var client = VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: MockClient((request) async {
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          if (request.headers["content-type"]?.startsWith("application/json") ??
              false) {
            created.add(request.url.toString());
            return http.Response('{"path":"Inbox"}', 200);
          }
          uploads.add(request.body);
        }
        return http.Response("", 200);
      }),
    );
    sync = CameraRollSync(
      store: store,
      library: library,
      clientOf: () => client,
    );
  }

  /// The names of the files handed to the server, in order.
  List<String> get uploadedNames => [
        for (var body in uploads)
          for (var match in RegExp('filename="([^"]+)"').allMatches(body))
            match.group(1)!
      ];

  void dispose() {
    sync.dispose();
    library.dispose();
  }
}

/// Pumps the camera-roll section of the settings on its own.
Future<void> pumpSection(WidgetTester tester, CameraRollSync sync) async {
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: CameraRollScope(
        sync: sync,
        child: const Scaffold(
          body: SingleChildScrollView(child: CameraRollSection()),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('the albums to choose from', () {
    test('leave out the "all photos" album of the platform', () {
      var all = const PhotoAlbum(id: "all", name: "Recent", isAll: true);
      var camera = const PhotoAlbum(id: "cam", name: "Camera", isCamera: true);

      expect(selectableSources([all, camera]), [camera]);
    });

    test('keep it where it is the camera roll itself, as on iOS', () {
      // iOS has no DCIM/Camera: the camera writes into the user library, the
      // smart album the Photos app shows as "Recents", which is also the
      // album holding everything.
      var recents = const PhotoAlbum(
        id: "recents",
        name: "Recents",
        isAll: true,
        isCamera: true,
      );
      var whatsApp = const PhotoAlbum(id: "wa", name: "WhatsApp");

      expect(selectableSources([recents, whatsApp]), [recents, whatsApp]);
      expect(defaultSources([recents, whatsApp]), ["recents"]);
    });

    test('default to the camera album, and to nothing where there is none', () {
      var camera = const PhotoAlbum(id: "cam", name: "Camera", isCamera: true);
      var whatsApp = const PhotoAlbum(id: "wa", name: "WhatsApp");

      expect(defaultSources([whatsApp, camera]), ["cam"]);
      expect(defaultSources([whatsApp]), isEmpty);
    });

    test('default to the whole library where the device names no album', () {
      // An empty library, or a platform that enumerates none: there is
      // nothing to tick, and scanning everything is what the sync always did.
      expect(defaultSources(const []), [CameraRollConfig.wholeLibrary]);
    });
  });

  group('the camera album of the device', () {
    test('is DCIM/Camera on Android, and nothing else', () {
      expect(
        PhotoManagerLibrary.isCameraPath(
            AssetPathEntity(id: "1", name: "Camera")),
        isTrue,
      );
      expect(
        PhotoManagerLibrary.isCameraPath(
            AssetPathEntity(id: "2", name: "WhatsApp Images")),
        isFalse,
      );
      expect(
        PhotoManagerLibrary.isCameraPath(
            AssetPathEntity(id: "3", name: "Recent", isAll: true)),
        isFalse,
        reason: "the pseudo-album holding everything is not the camera",
      );
    });

    test('is the user library on iOS, whatever it is called there', () {
      expect(
        PhotoManagerLibrary.isCameraPath(AssetPathEntity(
          id: "4",
          name: "Recents",
          isAll: true,
          albumTypeEx: const AlbumType(
            darwin: DarwinAlbumType(
              subtype: PMDarwinAssetCollectionSubtype.smartAlbumUserLibrary,
            ),
          ),
        )),
        isTrue,
      );
    });
  });

  group('the stored choice', () {
    test('is absent in a configuration written before issue #117', () {
      var config = CameraRollConfig.parse(jsonEncode({
        "enabled": true,
        "inbox": ["Inbox"],
        "since": "2026-03-01T12:05:00.000Z",
        "done": ["a.jpg"],
      }));

      expect(config.sources, isNull, reason: "nobody ticked anything yet");
      expect(
        config.sourcesOf([
          const PhotoAlbum(id: "wa", name: "WhatsApp"),
          const PhotoAlbum(id: "cam", name: "Camera", isCamera: true),
        ]),
        ["cam"],
      );
      expect(
        config.markOf("cam").since,
        DateTime.utc(2026, 3, 1, 12, 5),
        reason: "the single watermark carries over to the camera album",
      );
      expect(config.markOf("cam").done, ["a.jpg"]);
    });

    test('round-trips the ticks and the per-album marks', () async {
      var store = InMemorySettingsStore();
      var config = CameraRollConfig(
        enabled: true,
        inbox: const ["Inbox"],
        sources: const ["cam", "wa"],
        marks: {
          "cam": SourceMark(
            since: DateTime.utc(2026, 3, 1, 12, 5),
            done: const ["a.jpg"],
          ),
          "wa": SourceMark.beginning,
        },
      );

      await store.saveCameraRollConfig(config);

      var read = await store.loadCameraRollConfig();
      expect(read, config);
      expect(read.markOf("wa"), SourceMark.beginning);
      expect(read.markOf("cam").since, DateTime.utc(2026, 3, 1, 12, 5));
    });

    test('tells an album with no mark of its own from one at the beginning',
        () {
      var config = CameraRollConfig(
        since: DateTime.utc(2026, 3, 1, 12, 5),
        marks: const {"wa": SourceMark.beginning},
      );

      expect(config.markOf("cam").since, DateTime.utc(2026, 3, 1, 12, 5));
      expect(config.markOf("wa").since, isNull);
    });
  });

  group('a run', () {
    test('hands over the items of the ticked album only', () async {
      var harness = Harness();
      addTearDown(harness.dispose);
      var camera = harness.library.addAlbum(
        "Camera",
        [photo("cam.jpg", 1)],
        id: "cam",
        camera: true,
      );
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 2)], id: "wa");
      harness.library
          .addAlbum("Screenshots", [photo("shot.png", 3)], id: "shot");
      await harness.sync.load();
      await harness.sync.chooseSources(
        [camera.id],
        albums: await harness.library.albums(),
      );
      await pumpEventQueue();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, ["cam.jpg"]);
      expect(
        harness.library.scannedSources,
        allOf(isNotEmpty, everyElement(["cam"])),
        reason: "every scan names the album it is for",
      );
    });

    test('hands an item lying in two ticked albums over once', () async {
      var harness = Harness();
      addTearDown(harness.dispose);
      var shared = photo("both.jpg", 1);
      var camera =
          harness.library.addAlbum("Camera", [shared], id: "cam", camera: true);
      var favourites =
          harness.library.addAlbum("Favourites", [shared], id: "fav");
      await harness.sync.load();
      await harness.sync.chooseSources(
        [camera.id, favourites.id],
        albums: await harness.library.albums(),
      );
      await pumpEventQueue();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, ["both.jpg"]);
      expect(
        harness.sync.config.markOf("fav").since,
        photo("both.jpg", 1).takenAt,
        reason: "the second album's mark moves past it all the same",
      );
    });

    test('watches the camera alone where nothing was ever ticked', () async {
      // The migration: a store written before issue #117 holds one watermark
      // and no ticks at all.
      var harness = Harness(
        config: CameraRollConfig(
          enabled: true,
          inbox: inbox,
          since: DateTime.utc(2026, 3, 1, 12, 1),
          done: const ["old.jpg"],
        ),
      );
      addTearDown(harness.dispose);
      harness.library.addAlbum(
        "Camera",
        [photo("old.jpg", 1), photo("new.jpg", 5)],
        id: "cam",
        camera: true,
      );
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 9)], id: "wa");
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, ["new.jpg"],
          reason: "the camera only, and only what is past its watermark");
    });

    test('finds nothing where the device has no camera album', () async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 2)], id: "wa");
      harness.library
          .addAlbum("Screenshots", [photo("shot.png", 3)], id: "shot");
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploads, isEmpty,
          reason: "no album was ticked, so nothing is watched");
      expect(harness.sync.status.phase, CameraRollPhase.idle);
    });

    test('leaves no empty inbox behind while nothing is watched', () async {
      var harness = Harness(
        config: const CameraRollConfig(enabled: true),
      );
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 2)], id: "wa");
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.created, isEmpty,
          reason: "nothing is watched, so there is nothing to put anywhere");
      expect(harness.uploads, isEmpty);
    });

    test('scans the whole library where the device names no album', () async {
      // Nothing changed for a library that enumerates no albums at all.
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library.items.add(photo("loose.jpg", 1));
      await harness.sync.load();

      await harness.sync.syncNow();

      expect(harness.uploadedNames, ["loose.jpg"]);
      expect(harness.library.scannedSources,
          allOf(isNotEmpty, everyElement(isEmpty)));
    });

    test('fetches an album ticked later from the beginning', () async {
      var harness = Harness();
      addTearDown(harness.dispose);
      var camera = harness.library.addAlbum(
        "Camera",
        [photo("cam-1.jpg", 5)],
        id: "cam",
        camera: true,
      );
      // Older than what the camera already synced: the point of fetching a
      // newly ticked album from the beginning.
      var whatsApp = harness.library
          .addAlbum("WhatsApp Images", [photo("wa-old.jpg", 1)], id: "wa");
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.uploadedNames, ["cam-1.jpg"]);

      harness.library.albumItems[camera]!.add(photo("cam-2.jpg", 9));
      harness.library.items.add(photo("cam-2.jpg", 9));
      await harness.sync.chooseSources(
        [camera.id, whatsApp.id],
        albums: await harness.library.albums(),
      );
      await pumpEventQueue();
      await harness.sync.syncNow();

      expect(
        harness.uploadedNames,
        ["cam-1.jpg", "cam-2.jpg", "wa-old.jpg"],
        reason: "the camera goes on from its mark, the new album starts over",
      );
    });

    test('keeps the mark of an album that is unticked and ticked again',
        () async {
      var harness = Harness();
      addTearDown(harness.dispose);
      var camera = harness.library.addAlbum(
        "Camera",
        [photo("cam.jpg", 5)],
        id: "cam",
        camera: true,
      );
      var albums = await harness.library.albums();
      await harness.sync.load();
      await harness.sync.syncNow();

      await harness.sync.chooseSources(const [], albums: albums);
      await pumpEventQueue();
      await harness.sync.chooseSources([camera.id], albums: albums);
      await pumpEventQueue();
      await harness.sync.syncNow();

      expect(harness.uploadedNames, ["cam.jpg"],
          reason: "what was synced once is not offered a second time");
    });
  });

  group('the settings section', () {
    testWidgets('lists the albums of the device with their counts',
        (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library.addAlbum(
        "Camera",
        [photo("a.jpg", 1), photo("b.jpg", 2)],
        id: "cam",
        camera: true,
      );
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 3)], id: "wa");
      harness.library.addAlbum("Recent", const [], id: "all");
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSourcesKey), findsOneWidget);
      expect(find.text("Camera"), findsOneWidget);
      expect(find.text("2 photos"), findsOneWidget);
      expect(find.text("WhatsApp Images"), findsOneWidget);
      expect(find.text("1 photo"), findsOneWidget);
      expect(find.text(newSourceNotice), findsOneWidget);
      expect(
        tester.widget<CheckboxListTile>(find.byKey(cameraRollSourceKey("cam"))),
        isA<CheckboxListTile>().having((tile) => tile.value, "camera", isTrue),
      );
      expect(
        tester.widget<CheckboxListTile>(find.byKey(cameraRollSourceKey("wa"))),
        isA<CheckboxListTile>()
            .having((tile) => tile.value, "WhatsApp", isFalse),
      );
      expect(find.byKey(cameraRollNoSourcesKey), findsNothing);
    });

    testWidgets('stores what was ticked', (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("Camera", [photo("a.jpg", 1)], id: "cam", camera: true);
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 3)], id: "wa");
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await tester.tap(find.byKey(cameraRollSourceKey("wa")));
      await tester.pumpAndSettle();

      expect(
        (await harness.store.loadCameraRollConfig()).sources,
        ["cam", "wa"],
      );
      expect(
        tester.widget<CheckboxListTile>(find.byKey(cameraRollSourceKey("wa"))),
        isA<CheckboxListTile>()
            .having((tile) => tile.value, "WhatsApp", isTrue),
      );
    });

    testWidgets('asks for a choice where the device has no camera album',
        (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("WhatsApp Images", [photo("wa.jpg", 3)], id: "wa");
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollNoSourcesKey), findsOneWidget);
      expect(find.text(noSourcesNotice), findsOneWidget);
    });

    testWidgets('says why the albums cannot be listed', (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library.granted = false;
      harness.library.accessProblem = "Access to the photo library was denied.";
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSourcesKey), findsNothing);
      expect(find.byKey(cameraRollSourcesProblemKey), findsOneWidget);
      expect(
        find.text("Access to the photo library was denied."),
        findsOneWidget,
      );
    });

    testWidgets('asks the device for nothing while the sync is off',
        (tester) async {
      var harness = Harness(config: const CameraRollConfig(inbox: inbox));
      addTearDown(harness.dispose);
      harness.library.granted = false;
      harness.library.accessProblem = "Access to the photo library was denied.";
      harness.library
          .addAlbum("Camera", [photo("a.jpg", 1)], id: "cam", camera: true);
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSourcesKey), findsNothing);
      expect(find.byKey(cameraRollSourcesProblemKey), findsNothing,
          reason: "opening the settings does not ask for the photos");
    });

    testWidgets('shows nothing at all where the device has no albums',
        (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSourcesKey), findsNothing);
      expect(find.byKey(cameraRollNoSourcesKey), findsNothing);
      expect(find.byKey(cameraRollSourcesProblemKey), findsNothing);
    });
  });
}
