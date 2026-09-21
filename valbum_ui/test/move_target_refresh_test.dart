/// Tests of issue #134: a folder an album was moved *into* is fetched again.
///
/// Since issue #93 the router keeps the resource of every path visited, so
/// "up" and "back" show a listing without asking the server. The move flows
/// forgot what they moved *out* of and never what they moved *into*, so a
/// target visited earlier in the session still showed its old listing — "only
/// after a refresh of the folder the albums are displayed".
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the test talks to.
const String dataUrl = "http://server/valbum/data";

/// One entry of a listing.
String entry(String name, String title, String kind) =>
    '{"name":"$name","title":"$title","kind":"$kind","effectiveDate":0}';

String listing(String path, String title, List<String> entries) =>
    '["ListingInfo", {"path":"$path","title":"$title",'
    '"folders":[${entries.join(",")}]}]';

/// The tree the test browses, before and after the move.
///
/// `A` holds the album, `B` is the target and already holds the year folder a
/// placement rule files the album into, `C` is a folder nothing happens to.
class Tree {
  bool moved = false;

  final List<http.Request> requests = [];

  http.Response answer(http.Request request) {
    var path = Uri.decodeComponent(request.url.path);
    if (request.method == "POST") {
      moved = true;
      return _json('{"outcomes":[{"name":"2026-05-01 Trip",'
          '"newName":"2026-05-01 Trip","message":""}]}');
    }
    switch (path) {
      case "/valbum/data/":
        return _json(listing("", "Library", [
          entry("A", "A", "FOLDER"),
          entry("B", "B", "FOLDER"),
          entry("C", "C", "FOLDER"),
        ]));
      case "/valbum/data/A/":
        return _json(listing("A", "A", [
          if (!moved) entry("2026-05-01 Trip", "Trip", "ALBUM"),
        ]));
      case "/valbum/data/B/":
        return _json(listing("B", "B", [entry("2026", "2026", "FOLDER")]));
      case "/valbum/data/B/2026/":
        return _json(listing("B/2026", "2026", [
          entry("2026-01-01 Old", "Old", "ALBUM"),
          if (moved) entry("2026-05-01 Trip", "Trip", "ALBUM"),
        ]));
      case "/valbum/data/C/":
        return _json(listing("C", "C", []));
    }
    return http.Response("No such resource: $path", 404);
  }

  http.Response _json(String body) => http.Response(
        body,
        200,
        headers: {"content-type": "application/json; charset=utf-8"},
      );

  VAlbumClient get client => clientHandling(answer, requests: requests);

  /// The index of the move request in [requests].
  int get postIndex => requests.indexWhere((r) => r.method == "POST");

  /// How often the resource at the given data path was asked for after the
  /// move was posted.
  int fetchesAfterMove(String path) => requests
      .sublist(postIndex + 1)
      .where((r) =>
          r.method == "GET" &&
          r.url.queryParameters["type"] == "json" &&
          Uri.decodeComponent(r.url.path) == path)
      .length;
}

/// Hands the app the given location, as the browser does on back/forward.
Future<void> goTo(WidgetTester tester, String location) async {
  var delegate = tester
      .widget<MaterialApp>(find.byType(MaterialApp))
      .routerDelegate! as VAlbumRouterDelegate;
  await withFakeImageHttp(() async {
    await delegate.setNewRoutePath(parseRoute(Uri.parse(location)));
    delegate.notifyListeners();
    await tester.pumpAndSettle();
  });
}

/// Moves the album of `A` into the folder the picker is walked to.
Future<void> moveTripInto(WidgetTester tester, List<String> target) async {
  await withFakeImageHttp(() async {
    await tester.longPress(find.text("Trip"));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Move to…"));
    await tester.pumpAndSettle();
    for (var name in target) {
      await tester.tap(find.byKey(Key("picker-folder-$name")));
      await tester.pumpAndSettle();
    }
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('the folder an album was moved into is fetched again',
      (tester) async {
    var tree = Tree();

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: tree.client));
      await tester.pumpAndSettle();
    });

    // Both listings are visited, so both are held by the router.
    await goTo(tester, "/B/");
    expect(find.text("2026"), findsOneWidget);
    await goTo(tester, "/C/");
    await goTo(tester, "/A/");
    expect(find.text("Trip"), findsOneWidget);

    var beforeMove = tree.requests.length;
    await moveTripInto(tester, ["B"]);
    expect(tree.moved, isTrue, reason: "The move was posted.");
    expect(beforeMove, lessThan(tree.requests.length));

    // The target was visited before the move and is asked for again.
    await goTo(tester, "/B/");
    expect(tree.fetchesAfterMove("/valbum/data/B/"), 1);

    // A folder the move did not touch is still the one the session holds.
    await goTo(tester, "/C/");
    expect(tree.fetchesAfterMove("/valbum/data/C/"), 0);
  });

  testWidgets('a year folder inside the target is fetched again too',
      (tester) async {
    var tree = Tree();

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: tree.client));
      await tester.pumpAndSettle();
    });

    // The year folder the placement rule of `B` files the album into is
    // visited before the move: the target alone would not be enough.
    await goTo(tester, "/B/2026/");
    expect(find.text("Old"), findsOneWidget);
    expect(find.text("Trip"), findsNothing);
    await goTo(tester, "/C/");
    await goTo(tester, "/A/");

    await moveTripInto(tester, ["B"]);

    await goTo(tester, "/B/2026/");
    expect(tree.fetchesAfterMove("/valbum/data/B/2026/"), 1);
    // And the album that moved is on the screen without a manual reload.
    expect(find.text("Trip"), findsOneWidget);
    expect(find.text("Old"), findsOneWidget);

    await goTo(tester, "/C/");
    expect(tree.fetchesAfterMove("/valbum/data/C/"), 0);
  });
}
