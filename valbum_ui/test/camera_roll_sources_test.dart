/// Tests of the albums the camera-roll sync watches (issue #117): what a
/// configuration without a choice watches, what a run scans, what an album
/// ticked later is fetched from, and the list of tick boxes in the settings.
///
/// Before this the sync asked the platform for the whole photo library — the
/// camera, but also every folder any app ever saved a picture into. What is
/// tested here is above all the migration: a store written before the tick
/// boxes existed must not upload one photo more than it did.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_library_manager.dart';
import 'util/l10n.dart';
import 'package:valbum_ui/notices.dart';

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
  final FakePhotoLibrary library;
  final InMemorySettingsStore store = InMemorySettingsStore();

  /// The bodies of the uploads the server received.
  final List<String> uploads = [];

  /// The URLs an album was created at.
  final List<String> created = [];

  late final CameraRollSync sync;

  Harness({
    CameraRollConfig config = const CameraRollConfig(
      enabled: true,
    ),
    FakePhotoLibrary? library,
  }) : library = library ?? FakePhotoLibrary() {
    store.cameraRoll = config.toJson();
    var client = VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: MockClient((request) async {
        // Where the inbox is, as `?type=auth` names it (issue #226).
        if (request.url.queryParameters["type"] == "auth") {
          return http.Response(
              '{"mode":"writes","role":"admin","inbox":"${inbox.join("/")}"}',
              200);
        }
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
      callerOf: () async =>
          CallerInfo(role: roleMember, inbox: inbox.join("/")),
      store: store,
      library: this.library,
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

/// Pumps the camera-roll section of the settings on its own, as [caller]
/// where one is given.
///
/// [settle] waits for every animation; a progress indicator never settles.
Future<void> pumpSection(
  WidgetTester tester,
  CameraRollSync sync, {
  CallerInfo? caller,
  bool settle = true,
}) async {
  Widget section = CameraRollScope(
    sync: sync,
    child: const Scaffold(
      body: SingleChildScrollView(child: CameraRollSection()),
    ),
  );
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: caller == null
          ? section
          : CallerScope(caller: caller, child: section),
    ),
  );
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
  }
}

/// A library whose albums arrive when the test says so ([gate]), or fail
/// [failures] times with the platform's [reason] first (issue #298).
class SlowLibrary extends FakePhotoLibrary {
  /// Completed by the test to let the albums arrive; `null` for at once.
  Completer<void>? gate;

  /// How many of the next requests fail.
  int failures = 0;

  /// What the platform says when one fails.
  String reason = "The gallery is busy.";

  /// How many times the albums were asked for.
  int asked = 0;

  @override
  Future<List<PhotoAlbum>> albums() async {
    asked++;
    await gate?.future;
    if (failures > 0) {
      failures--;
      throw PlatformException(code: "busy", message: reason);
    }
    return super.albums();
  }
}

/// Adds the camera and eleven albums more to [library]: twelve to choose
/// from, in this order.
void addTwelveAlbums(FakePhotoLibrary library) {
  library.addAlbum("Camera", [photo("cam.jpg", 1)], id: "cam", camera: true);
  library.addAlbum("WhatsApp Images", [photo("wa.jpg", 2)], id: "wa");
  for (var n = 2; n < 12; n++) {
    library.addAlbum(
      "Album $n",
      [for (var i = 0; i < n; i++) photo("a$n-$i.jpg", n)],
      id: "a$n",
    );
  }
}

/// Opens the dialog choosing the albums.
Future<void> openSources(WidgetTester tester, {bool settle = true}) async {
  await tester.tap(find.byKey(cameraRollChooseSourcesKey));
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
  }
  expect(find.byKey(cameraRollSourcesDialogKey), findsOneWidget);
}

/// The box of the album [id] in the dialog.
CheckboxListTile sourceBox(WidgetTester tester, String id) =>
    tester.widget<CheckboxListTile>(find.byKey(cameraRollSourceKey(id)));

/// Room for the dialog to show all twelve albums at once.
void tallScreen(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
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
    testWidgets('names the watched album in one line', (tester) async {
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
      expect(find.text(testL10n.albumsToSync), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera",
        reason: "the camera alone, where nothing was chosen yet",
      );
      expect(find.byType(CheckboxListTile), findsNothing);
      expect(find.byKey(cameraRollNoSourcesKey), findsNothing);
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(cameraRollChooseSourcesKey))
            .onPressed,
        isNotNull,
      );
    });

    testWidgets('shows a summary, not a box for each of twelve albums',
        (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await harness.sync.chooseSources(
        const ["cam", "wa", "a5", "a9"],
        albums: await harness.library.albums(),
      );

      await pumpSection(tester, harness.sync);

      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera, WhatsApp Images and 2 more",
      );
      expect(find.byType(CheckboxListTile), findsNothing);
      expect(find.text("Album 5"), findsNothing);
      expect(find.byKey(cameraRollSourceKey("a5")), findsNothing);
    });

    test('names three albums rather than counting one', () {
      expect(sourcesSummary(["A", "B", "C"], testL10n), "Watched: A, B, C");
      expect(
        sourcesSummary(["A", "B", "C", "D"], testL10n),
        "Watched: A, B and 2 more",
      );
    });

    testWidgets('"Choose..." opens the dialog with every album',
        (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await openSources(tester);

      var dialog = find.byKey(cameraRollSourcesDialogKey);
      expect(
        find.descendant(of: dialog, matching: find.byType(CheckboxListTile)),
        findsNWidgets(12),
      );
      expect(sourceBox(tester, "cam").value, isTrue);
      expect(sourceBox(tester, "wa").value, isFalse);
      expect(sourceBox(tester, "a11").value, isFalse);
      expect(
        find.descendant(of: dialog, matching: find.text("11 photos")),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: dialog, matching: find.text(testL10n.cameraRollNewSource)),
        findsOneWidget,
      );
      expect(find.byKey(cameraRollSourcesFilterKey), findsOneWidget);
    });

    testWidgets('narrows the dialog by the filter', (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "whats");
      await tester.pump();

      expect(find.byType(CheckboxListTile), findsOneWidget);
      expect(find.byKey(cameraRollSourceKey("wa")), findsOneWidget);

      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "zzz");
      await tester.pump();

      expect(find.byType(CheckboxListTile), findsNothing);
      expect(find.text(testL10n.cameraRollSourcesNoMatch), findsOneWidget);
    });

    testWidgets('keeps what was ticked behind the filter', (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "whats");
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourceKey("wa")));
      await tester.pump();
      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "");
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourcesApplyKey));
      await tester.pumpAndSettle();

      expect(harness.sync.config.sources, ["cam", "wa"],
          reason: "the camera, hidden by the filter, stays ticked");
    });

    testWidgets('applies the ticks in the order the device names the albums',
        (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      // Ticked the other way round from the order they stand in.
      await tester.tap(find.byKey(cameraRollSourceKey("a9")));
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourceKey("a3")));
      await tester.pump();
      expect(sourceBox(tester, "a9").value, isTrue);
      expect(harness.sync.config.sources, isNull,
          reason: "nothing is stored before Apply");

      await tester.tap(find.byKey(cameraRollSourcesApplyKey));
      await tester.pumpAndSettle();

      expect(find.byKey(cameraRollSourcesDialogKey), findsNothing);
      expect(harness.sync.config.sources, ["cam", "a3", "a9"]);
      expect(
        (await harness.store.loadCameraRollConfig()).sources,
        ["cam", "a3", "a9"],
      );
      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera, Album 3, Album 9",
      );
    });

    testWidgets('unticking everything leaves the warning', (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("Camera", [photo("a.jpg", 1)], id: "cam", camera: true);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      await tester.tap(find.byKey(cameraRollSourceKey("cam")));
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourcesApplyKey));
      await tester.pumpAndSettle();

      expect(harness.sync.config.sources, isEmpty);
      expect(find.byKey(cameraRollNoSourcesKey), findsOneWidget);
      expect(find.byKey(cameraRollSourcesSummaryKey), findsNothing);
    });

    testWidgets('Cancel and Escape change nothing', (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await openSources(tester);
      await tester.tap(find.byKey(cameraRollSourceKey("wa")));
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourcesCancelKey));
      await tester.pumpAndSettle();

      expect(find.byKey(cameraRollSourcesDialogKey), findsNothing);
      expect(harness.sync.config.sources, isNull);
      expect(harness.store.cameraRoll, isNot(contains('"sources"')));
      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera",
      );

      await openSources(tester);
      expect(sourceBox(tester, "wa").value, isFalse,
          reason: "a cancelled tick is not remembered either");
      await tester.tap(find.byKey(cameraRollSourceKey("wa")));
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();

      expect(find.byKey(cameraRollSourcesDialogKey), findsNothing);
      expect(harness.sync.config.sources, isNull);
    });

    testWidgets('ticks the highlighted album with Enter', (tester) async {
      tallScreen(tester);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "album 1");
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pump();

      expect(sourceBox(tester, "a10").value, isTrue);
      expect(sourceBox(tester, "a11").value, isFalse);
    });

    testWidgets('waits for the albums, in the section and in the dialog',
        (tester) async {
      var library = SlowLibrary()..gate = Completer<void>();
      var harness = Harness(library: library);
      addTearDown(harness.dispose);
      addTwelveAlbums(library);
      await harness.sync.load();

      await pumpSection(tester, harness.sync, settle: false);

      expect(find.byKey(const Key("$cameraRollAlbumsKeyPrefix-loading")),
          findsOneWidget);
      expect(find.byKey(cameraRollSourcesSummaryKey), findsNothing);

      await openSources(tester, settle: false);
      var dialog = find.byKey(cameraRollSourcesDialogKey);
      expect(
        find.descendant(
            of: dialog,
            matching:
                find.byKey(const Key("$cameraRollAlbumsKeyPrefix-loading"))),
        findsOneWidget,
      );
      expect(
        tester.widget<FilledButton>(find.byKey(cameraRollSourcesApplyKey))
            .onPressed,
        isNull,
        reason: "nothing to apply before the albums are there",
      );

      library.gate!.complete();
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("$cameraRollAlbumsKeyPrefix-loading")),
          findsNothing);
      expect(sourceBox(tester, "cam").value, isTrue,
          reason: "what is watched is ticked once the albums arrive");
      expect(library.asked, 1, reason: "the dialog waits for the same request");

      await tester.tap(find.byKey(cameraRollSourcesCancelKey));
      await tester.pumpAndSettle();
      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera",
      );
    });

    testWidgets('says why the albums failed, and asks again on Retry',
        (tester) async {
      var library = SlowLibrary()..failures = 1;
      var harness = Harness(library: library);
      addTearDown(harness.dispose);
      addTwelveAlbums(library);
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(const Key("$cameraRollAlbumsKeyPrefix-failed")),
          findsOneWidget);
      expect(find.text(testL10n.loadingFailed("The gallery is busy.")),
          findsOneWidget);
      expect(find.byKey(cameraRollNoSourcesKey), findsNothing,
          reason: "a failure is not an empty device");

      await tester.tap(find.byKey(const Key("$cameraRollAlbumsKeyPrefix-retry")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("$cameraRollAlbumsKeyPrefix-failed")),
          findsNothing);
      expect(
        tester.widget<Text>(find.byKey(cameraRollSourcesSummaryKey)).data,
        "Watched: Camera",
      );
    });

    testWidgets('says why in the dialog too, and Retry there lists them',
        (tester) async {
      tallScreen(tester);
      // The dialog asks again as it opens over a failure: it fails twice.
      var library = SlowLibrary()..failures = 2;
      var harness = Harness(library: library);
      addTearDown(harness.dispose);
      addTwelveAlbums(library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);

      await openSources(tester);

      var dialog = find.byKey(cameraRollSourcesDialogKey);
      var retry = find.descendant(
          of: dialog,
          matching: find.byKey(const Key("$cameraRollAlbumsKeyPrefix-retry")));
      expect(retry, findsOneWidget);
      expect(
        find.descendant(
            of: dialog,
            matching:
                find.text(testL10n.loadingFailed("The gallery is busy."))),
        findsOneWidget,
      );

      await tester.tap(retry);
      await tester.pumpAndSettle();

      expect(
        find.descendant(of: dialog, matching: find.byType(CheckboxListTile)),
        findsNWidgets(12),
      );
      expect(library.asked, 3);
    });

    testWidgets('offers no choice to a guest', (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library
          .addAlbum("Camera", [photo("a.jpg", 1)], id: "cam", camera: true);
      await harness.sync.load();

      await pumpSection(tester, harness.sync,
          caller: const CallerInfo(role: roleGuest, space: "carol"));

      expect(find.byKey(cameraRollChooseSourcesKey), findsOneWidget);
      expect(
        tester
            .widget<OutlinedButton>(find.byKey(cameraRollChooseSourcesKey))
            .onPressed,
        isNull,
      );
    });

    testWidgets('offers no choice without a photo library', (tester) async {
      var sync = CameraRollSync(
        callerOf: () async => const CallerInfo(role: roleMember, inbox: "Inbox"),
        store: InMemorySettingsStore()
          ..cameraRoll = const CameraRollConfig(enabled: true).toJson(),
        library: const UnavailablePhotoLibrary(),
        clientOf: () => null,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await pumpSection(tester, sync);

      expect(find.byKey(cameraRollSourcesKey), findsNothing);
      expect(find.byKey(cameraRollChooseSourcesKey), findsNothing);
    });

    testWidgets('fits a phone with the keyboard up', (tester) async {
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var harness = Harness();
      addTearDown(harness.dispose);
      addTwelveAlbums(harness.library);
      await harness.sync.load();
      await pumpSection(tester, harness.sync);
      await openSources(tester);

      tester.view.viewInsets = const FakeViewPadding(bottom: 340);
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      await tester.enterText(find.byKey(cameraRollSourcesFilterKey), "11");
      await tester.pumpAndSettle();
      // The content scrolls below the title, the buttons stay in place.
      await tester.ensureVisible(find.byKey(cameraRollSourceKey("a11")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(cameraRollSourceKey("a11")));
      await tester.pump();
      await tester.tap(find.byKey(cameraRollSourcesApplyKey));
      await tester.pumpAndSettle();

      expect(harness.sync.config.sources, ["cam", "a11"]);
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
      expect(find.text(testL10n.cameraRollNoSources), findsOneWidget);
      expect(find.byKey(cameraRollChooseSourcesKey), findsOneWidget);
    });

    testWidgets('says why the albums cannot be listed', (tester) async {
      var harness = Harness();
      addTearDown(harness.dispose);
      harness.library.granted = false;
      harness.library.accessProblem = const PhotoAccessDenied();
      await harness.sync.load();

      await pumpSection(tester, harness.sync);

      expect(find.byKey(cameraRollSourcesKey), findsNothing);
      expect(find.byKey(cameraRollSourcesProblemKey), findsOneWidget);
      expect(
        find.text(testL10n.noticePhotoAccessDenied),
        findsOneWidget,
      );
    });

    testWidgets('asks the device for nothing while the sync is off',
        (tester) async {
      var harness = Harness(config: const CameraRollConfig());
      addTearDown(harness.dispose);
      harness.library.granted = false;
      harness.library.accessProblem = const PhotoAccessDenied();
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
      expect(find.byKey(cameraRollChooseSourcesKey), findsNothing);
    });
  });
}
