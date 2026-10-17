/// The small parity gaps between the views (issue #295): a long press is
/// never nothing, the view of an entry of the bell has a menu of what applies
/// to its photographs, and the tile of an album in a listing offers what the
/// album's own menu does with it — its properties (read-only to whoever may
/// not edit) and its download.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/downloads.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'select_mode_test.dart' show sectionedAlbum;
import 'share_album_chrome_test.dart' as chrome;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';
import 'util/photo_space.dart';

/// A listing holding the album `trip` and a folder `more`, with [rights].
class ListingSpace extends PhotoSpace {
  ListingSpace({super.role, super.rights});

  @override
  http.Response answer(http.Request request) {
    var path = Uri.decodeFull(request.url.path);
    if (path == "/valbum/data/" &&
        request.url.queryParameters["type"] == "json") {
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"rights": ${rightsOf(rights)}, "folders": ['
          '{"name": "trip", "title": "Trip", "kind": "ALBUM"}, '
          '{"name": "more", "title": "More", "kind": "FOLDER"}]}]');
    }
    if (path == "/valbum/data/trip/" && request.method == "PUT") {
      return json('["CreateResult", {"path": "trip"}]');
    }
    return super.answer(request);
  }
}

/// A saver that opens what it is handed, as a desktop's does.
class OpeningSaver extends DownloadSaver {
  final List<String> names = [];

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    names.add(source.name);
    var stream = await source.open();
    await stream.content.drain<void>();
    return SaveOutcome.saved;
  }
}

void desk(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// Opens the menu of the tile of the folder [name] by a secondary click.
Future<void> folderMenu(WidgetTester tester, String name) async {
  await tester.longPress(find.byKey(ValueKey("tile-$name")));
  await settle(tester);
}

/// The keys of the entries of the menu open.
List<String> entryKeys() => [
      for (var element in find
          .byWidgetPredicate((w) => w is PopupMenuItem && w.key is ValueKey)
          .evaluate())
        ((element.widget.key as ValueKey).value as String),
    ];

void main() {
  group('a long press', () {
    testWidgets(
        'in a link that may not download opens the tile menu, not nothing',
        (tester) async {
      await withFakeImageHttp(() async {
        await chrome.pumpSession(tester, (request) {
          if (request.url.queryParameters["type"] == "auth") {
            return chrome.json(chrome.authOfLink());
          }
          return chrome.json(sectionedAlbum(const ["view"]));
        });
        var tile = tester.getRect(find.byKey(const ValueKey("b.jpg")));
        await tester.longPressAt(Offset(tile.left + 8, tile.center.dy));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key("tile-context-open")), findsOneWidget);
        expect(
            find.byKey(const Key("tile-context-properties")), findsOneWidget);
        expect(find.byKey(const Key("tile-context-download")), findsNothing);
        expect(find.byKey(const Key("select-mode-bar")), findsNothing);
      });
    });
  });

  group('the view of an entry of the bell', () {
    testWidgets('has a menu: the download of its photos, select, filter',
        (tester) async {
      desk(tester);
      await openSpace(tester, PhotoSpace(), const NewsRoute(["trip"], newsKey));
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await settle(tester);

      expect(find.text(testL10n.downloadSelection(2)), findsOneWidget,
          reason: "the two photos the entry shows");
      expect(entryKeys(), containsAll(["download-selection", "select-mode"]));
      expect(find.byKey(const Key("minRating")), findsOneWidget);
      expect(find.text(testL10n.showMoreImages), findsOneWidget);
      // Nothing of the album itself.
      for (var key in [
        "album-properties",
        "move-to",
        "delete-album",
        "show-trash",
        "persons",
      ]) {
        expect(entryKeys(), isNot(contains(key)), reason: key);
      }

      await tester.tap(find.byKey(const Key("select-mode")));
      await settle(tester);
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
    });

    testWidgets('downloads the photos it shows, from the album',
        (tester) async {
      desk(tester);
      var saver = OpeningSaver();
      var before = downloadSaver;
      downloadSaver = saver;
      addTearDown(() => downloadSaver = before);
      var space = PhotoSpace();
      await openSpace(tester, space, const NewsRoute(["trip"], newsKey));
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await settle(tester);
      await tester.tap(find.byKey(const Key("download-selection")));
      await settle(tester);

      var zip = space.posts("zip").single;
      expect(Uri.decodeFull(zip.url.path), "/valbum/data/trip/");
      expect(zip.body, allOf(contains("a.jpg"), contains("b.jpg")));
      expect(zip.body, isNot(contains("c.jpg")));
      expect(saver.names, ["Trip.zip"]);
    });

    testWidgets('offers no download to whoever may not', (tester) async {
      desk(tester);
      await openSpace(tester, PhotoSpace(role: "view", rights: const ["view"]),
          const NewsRoute(["trip"], newsKey));
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await settle(tester);
      expect(entryKeys(), isNot(contains("download-selection")));
      expect(find.byKey(const Key("minRating")), findsOneWidget);
    });
  });

  group('the tile of an album in a listing', () {
    testWidgets('shows its properties read-only to whoever may not edit',
        (tester) async {
      desk(tester);
      var space = ListingSpace(role: "view", rights: const ["view"]);
      await openSpace(tester, space, const ListingOrAlbumRoute([]));
      await folderMenu(tester, "trip");

      expect(entryKeys(), contains("tile-properties"));
      expect(entryKeys(), isNot(contains("tile-download")));
      expect(entryKeys(), isNot(contains("delete-entry")));
      await tester.tap(find.byKey(const Key("tile-properties")));
      await settle(tester);

      var title =
          tester.widget<TextField>(find.byKey(const Key("album-title")));
      expect(title.readOnly, isTrue);
      expect(title.controller!.text, "Trip");
      expect(find.text(testL10n.apply), findsNothing);
      await tester.tap(find.byKey(const Key("album-properties-close")));
      await settle(tester);
      expect(find.byKey(const Key("album-title")), findsNothing);
      expect([
        for (var r in space.requests)
          if (r.method == "PUT") r
      ], isEmpty);
    });

    testWidgets('edits and writes them for an editor', (tester) async {
      desk(tester);
      var space = ListingSpace();
      await openSpace(tester, space, const ListingOrAlbumRoute([]));
      await folderMenu(tester, "trip");
      await tester.tap(find.byKey(const Key("tile-properties")));
      await settle(tester);

      expect(
          tester
              .widget<TextField>(find.byKey(const Key("album-title")))
              .readOnly,
          isFalse);
      await tester.enterText(find.byKey(const Key("album-title")), "Rome");
      await tester.tap(find.text(testL10n.apply));
      await settle(tester);

      var put = space.requests.singleWhere((r) => r.method == "PUT");
      expect(Uri.decodeFull(put.url.path), "/valbum/data/trip/");
      var sent = Resource.fromString(put.body) as AlbumInfo;
      expect(sent.title, "Rome");
      expect(sent.parts, hasLength(3), reason: "the album itself, unchanged");
    });

    testWidgets('downloads the album as one archive', (tester) async {
      desk(tester);
      var saver = OpeningSaver();
      var before = downloadSaver;
      downloadSaver = saver;
      addTearDown(() => downloadSaver = before);
      var space =
          ListingSpace(role: "view", rights: const ["view", "download"]);
      await openSpace(tester, space, const ListingOrAlbumRoute([]));
      await folderMenu(tester, "trip");
      await tester.tap(find.byKey(const Key("tile-download")));
      await settle(tester);

      var zip = space.posts("zip").single;
      expect(Uri.decodeFull(zip.url.path), "/valbum/data/trip/");
      for (var name in ["a.jpg", "b.jpg", "c.jpg"]) {
        expect(zip.body, contains(name));
      }
      expect(saver.names, ["Trip.zip"]);
    });

    testWidgets('the album refusing the download says so', (tester) async {
      desk(tester);
      var space = _RefusingSpace();
      await openSpace(tester, space, const ListingOrAlbumRoute([]));
      await folderMenu(tester, "trip");
      await tester.tap(find.byKey(const Key("tile-download")));
      await settle(tester);

      expect(find.text(testL10n.downloadNotAllowed), findsOneWidget);
      expect(space.posts("zip"), isEmpty);
    });

    testWidgets('a folder of folders offers neither', (tester) async {
      desk(tester);
      await openSpace(tester, ListingSpace(), const ListingOrAlbumRoute([]));
      await folderMenu(tester, "more");
      expect(entryKeys(), isNot(contains("tile-properties")));
      expect(entryKeys(), isNot(contains("tile-download")));
      expect(entryKeys(), contains("delete-entry"));
    });
  });
}

/// The listing allows the download, the album itself does not.
class _RefusingSpace extends ListingSpace {
  _RefusingSpace() : super(role: "view", rights: const ["view", "download"]);

  @override
  String get trip => super.trip.replaceFirst(
      rightsOf(const ["view", "download"]), rightsOf(const ["view"]));
}
