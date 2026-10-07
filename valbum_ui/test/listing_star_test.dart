/// The app half of issue #239: a starred entry of a folder is drawn 2×2 with
/// a star in its corner, and the star is set and taken away from the entry's
/// own menu and from its tile's menu in the folder.
///
/// The star is the entry's own property ([AlbumInfo.starred],
/// [ListingInfo.starred]), written by the ordinary property save of the
/// entry; the listing above answers it on the tile ([FolderInfo.starred]).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/listing_layout.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'folder_picture_test.dart' show longPressTileNamed, pumpAt;
import 'l10n_german_test.dart' show de, speakGerman;
import 'move_test.dart' hide main;
import 'util/l10n.dart';

/// The folder `F`: the starred album `A`, the plain album `B`, the folder `G`.
String folderF({bool starred = false}) => '["ListingInfo", {"path": "F", '
    '"title": "F", "starred": $starred, "folders": ['
    '{"name": "A", "title": "Holiday", "subTitle": "Two weeks by the sea", '
    '"kind": "ALBUM", "effectiveDate": 1015113600000, "starred": true, '
    '"indexPicture": {"image": "a.jpg", "scale": 1, "tx": 0, "ty": 0}}, '
    '{"name": "B", "title": "B", "kind": "ALBUM", '
    '"indexPicture": {"image": "b.jpg", "scale": 1, "tx": 0, "ty": 0}}, '
    '{"name": "G", "title": "G", "kind": "FOLDER"}'
    ']}]';

/// The album `B` as its own address answers it.
const String albumB = '["AlbumInfo", {"title": "B", "parts": []}]';

/// The album `A`, which is starred.
const String albumA =
    '["AlbumInfo", {"title": "Holiday", "starred": true, "parts": []}]';

/// The folder `G` as its own address answers it.
const String folderG = '["ListingInfo", {"path": "F/G", "title": "G", '
    '"folders": []}]';

/// The server of these tests: the folder, its entries, and a plain answer to
/// every write.
http.Response server(http.Request request, {bool folderStarred = false}) {
  if (request.method == "PUT") {
    return json("");
  }
  return switch (pathOf(request)) {
    "/valbum/data/F/" => json(folderF(starred: folderStarred)),
    "/valbum/data/F/A/" => json(albumA),
    "/valbum/data/F/B/" => json(albumB),
    "/valbum/data/F/G/" => json(folderG),
    _ => json('["ListingInfo", {"path": "", "title": "Library", '
        '"folders": [{"name": "F", "title": "F", "kind": "FOLDER"}]}]'),
  };
}

/// The single write the app made.
http.Request theWrite(List<http.Request> requests) =>
    requests.singleWhere((request) => request.method == "PUT");

/// Whether the sidecar [put] wrote says that its folder is starred -- the
/// folder's own field, not the entries a listing carries along.
bool starredIn(http.Request put) =>
    switch (VAlbumClient.parseResource(put.body, pathOf(put))) {
      AlbumInfo album => album.starred,
      ListingInfo listing => listing.starred,
      _ => throw TestFailure("Not a sidecar: ${put.body}"),
    };

/// The tile of the entry named [name].
Finder tileOf(String name) => find.byKey(ValueKey<String>("tile-$name"));

/// The square picture of the tile of [name].
Finder pictureOf(String name) =>
    find.descendant(of: tileOf(name), matching: find.byType(ClipRect)).first;

void main() {
  group('a starred tile', () {
    testWidgets('is twice as wide and as high, with a star in its corner',
        (tester) async {
      await tester.binding.setSurfaceSize(const Size(1000, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await pumpAt(tester, server, route: const ["F"]);

      // Five columns of 216 (200 and a border of 8 at each side); the star
      // takes two of them and the two plain tiles the next two.
      expect(tester.getSize(pictureOf("B")), const Size(200, 200));
      expect(tester.getSize(pictureOf("A")), const Size(416, 416),
          reason: "two pictures and the gutter between them");
      expect(tester.getSize(tileOf("A")).width, 2 * 216);
      var grid = tester.getTopLeft(find.byType(TileGrid));
      expect(tester.getTopLeft(tileOf("A")) - grid, Offset.zero);
      expect(tester.getTopLeft(tileOf("B")) - grid, const Offset(2 * 216, 0));
      expect(tester.getTopLeft(tileOf("G")) - grid, const Offset(3 * 216, 0));

      expect(
        find.descendant(
            of: tileOf("A"), matching: find.byKey(const Key("star-badge"))),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: tileOf("B"), matching: find.byKey(const Key("star-badge"))),
        findsNothing,
      );
      // The title has room, and the subtitle and the date are shown.
      expect(find.text("Holiday"), findsOneWidget);
      expect(find.text("Two weeks by the sea"), findsOneWidget);
      expect(
        find.descendant(
            of: tileOf("A"), matching: find.byKey(const Key("folder-date"))),
        findsOneWidget,
      );
      // The tiles below it start below it.
      expect(tester.takeException(), isNull);
    });

    testWidgets('spans the whole width of a phone', (tester) async {
      await tester.binding.setSurfaceSize(const Size(400, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await pumpAt(tester, server, route: const ["F"]);

      // Two columns of 200: the star is the whole row, the plain tiles share
      // the next one.
      expect(tester.getSize(tileOf("A")).width, 400);
      expect(tester.getSize(tileOf("B")).width, 200);
      var below = tester.getRect(tileOf("A")).bottom;
      expect(tester.getTopLeft(tileOf("B")).dy, greaterThanOrEqualTo(below));
      expect(
          tester.getTopLeft(tileOf("G")).dy, tester.getTopLeft(tileOf("B")).dy);
      expect(tester.getTopLeft(tileOf("G")).dx, 200);
    });
  });

  group('the menu of a tile', () {
    testWidgets('stars a plain album by its own property save', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F"], requests: requests);

      await longPressTileNamed(tester, "B");
      expect(find.text(testL10n.addStarAction), findsOneWidget);
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(pathOf(put), "/valbum/data/F/B/");
      expect(put.body, startsWith('["AlbumInfo"'));
      expect(starredIn(put), isTrue);
      expect(put.body, contains('"title":"B"'));
      // The entry was read before it was written, and the listing is asked
      // again afterwards.
      var before = requests.sublist(0, requests.indexOf(put));
      expect(jsonGets(before).last, "/valbum/data/F/B/");
      expect(jsonGets(requests.sublist(requests.indexOf(put))),
          contains("/valbum/data/F/"));
    });

    testWidgets('takes the star of a starred album away', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F"], requests: requests);

      await longPressTileNamed(tester, "Holiday");
      expect(find.text(testL10n.removeStarAction), findsOneWidget);
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(pathOf(put), "/valbum/data/F/A/");
      expect(starredIn(put), isFalse);
    });

    testWidgets('stars a folder of folders as a folder', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F"], requests: requests);

      await longPressTileNamed(tester, "G");
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(pathOf(put), "/valbum/data/F/G/");
      expect(put.body, startsWith('["ListingInfo"'));
      expect(starredIn(put), isTrue);
    });

    testWidgets('says why when the server refuses the write', (tester) async {
      await pumpAt(
        tester,
        (request) => request.method == "PUT"
            ? http.Response(
                '["ErrorInfo", {"message": "You may not edit here."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : server(request),
        route: const ["F"],
      );

      await longPressTileNamed(tester, "B");
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      expect(find.text("You may not edit here."), findsOneWidget);
    });

    testWidgets('is not offered to a caller who may only look', (tester) async {
      await pumpAt(
        tester,
        (_) => json('["ListingInfo", {"path": "F", "title": "F", '
            '"rights": [{"name": "view"}, {"name": "download"}], '
            '"folders": [{"name": "A", "title": "A", "kind": "ALBUM"}]}]'),
        route: const ["F"],
      );

      await longPressTileNamed(tester, "A");

      expect(find.byKey(const Key("toggle-star")), findsNothing);
    });
  });

  group('the menu of the entry itself', () {
    testWidgets('the album\'s menu stars the album', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F", "B"], requests: requests);

      await openAlbumMenu(tester);
      expect(find.text(testL10n.addStarAction), findsOneWidget);
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(pathOf(put), "/valbum/data/F/B/");
      expect(starredIn(put), isTrue);
      // The listing above shows the tile that has just changed.
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(jsonGets(requests.sublist(requests.indexOf(put))),
          contains("/valbum/data/F/"));
    });

    testWidgets('the album\'s menu takes the star away', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F", "A"], requests: requests);

      await openAlbumMenu(tester);
      expect(find.text(testL10n.removeStarAction), findsOneWidget);
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      expect(starredIn(theWrite(requests)), isFalse);
    });

    testWidgets('a refused write takes the star back', (tester) async {
      await pumpAt(
        tester,
        (request) => request.method == "PUT"
            ? http.Response(
                '["ErrorInfo", {"message": "You may not edit here."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : server(request),
        route: const ["F", "B"],
      );

      await openAlbumMenu(tester);
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();
      expect(find.text("You may not edit here."), findsOneWidget);

      await openAlbumMenu(tester);
      expect(find.text(testL10n.addStarAction), findsOneWidget);
    });

    testWidgets('the folder\'s menu stars the folder, keeping the rest',
        (tester) async {
      var requests = <http.Request>[];
      await pumpAt(tester, server, route: const ["F"], requests: requests);

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("toggle-star")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(pathOf(put), "/valbum/data/F/");
      expect(put.body, startsWith('["ListingInfo"'));
      expect(starredIn(put), isTrue);
      expect(put.body, contains('"title":"F"'));
    });

    testWidgets('choosing the folder\'s picture keeps its star',
        (tester) async {
      var requests = <http.Request>[];
      await pumpAt(
        tester,
        (request) => server(request, folderStarred: true),
        route: const ["F"],
        requests: requests,
      );

      await longPressTileNamed(tester, "B");
      await tester.tap(find.byKey(const Key("use-as-folder-picture")));
      await tester.pumpAndSettle();

      var put = theWrite(requests);
      expect(put.body, contains('"index":"B"'));
      expect(starredIn(put), isTrue);
    });

    testWidgets('writing the folder\'s properties keeps its star',
        (tester) async {
      var requests = <http.Request>[];
      await pumpAt(
        tester,
        (request) => server(request, folderStarred: true),
        route: const ["F"],
        requests: requests,
      );

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.removeStarAction), findsOneWidget);
      await tester.tap(find.text(testL10n.folderProperties));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      expect(starredIn(theWrite(requests)), isTrue);
    });

    testWidgets('is not offered at the root, which lies in no folder',
        (tester) async {
      await pumpAt(tester, server);

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("toggle-star")), findsNothing);
    });
  });

  testWidgets('speaks German', (tester) async {
    speakGerman(tester);
    await pumpAt(tester, server, route: const ["F"]);

    await longPressTileNamed(tester, "B");
    expect(find.text(de.addStarAction), findsOneWidget);
    await tester.tapAt(Offset.zero);
    await tester.pumpAndSettle();

    await longPressTileNamed(tester, "Holiday");
    expect(find.text(de.removeStarAction), findsOneWidget);
    await tester.tapAt(Offset.zero);
    await tester.pumpAndSettle();

    var badge = tester.widget<Tooltip>(find.ancestor(
      of: find.byKey(const Key("star-badge")),
      matching: find.byType(Tooltip),
    ));
    expect(badge.message, de.starredBadge);

    var en = l10nOf(const Locale("en"));
    expect(de.addStarAction, isNot(en.addStarAction));
    expect(de.removeStarAction, isNot(en.removeStarAction));
    expect(de.starredBadge, isNot(en.starredBadge));
  });
}
