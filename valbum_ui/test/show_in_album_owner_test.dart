/// "Show in album" in another user's space (issue #301): the album a photo
/// of a collection at `~alice/Best` lies in is `~alice/<ref's folder>`, the
/// `~owner` prefix of the space the view stands in kept — never the caller's
/// own album of that name.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/collections.dart' show forgetSources;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart' show clientHandling;

import 'util/photo_space.dart';

class AliceSpace extends PhotoSpace {
  AliceSpace() : super(role: "view", rights: const ["view"]);

  String get aliceBest =>
      '["AlbumInfo", {"path": "~alice/Best", "kind": "COLLECTION", '
      '"title": "Best", "subTitle": "", "rights": ${rightsOf(rights)}, '
      '"parts": [${photo("pick.jpg", 0, ref: "2020/Rome/x.jpg")}]}]';

  String get aliceRome =>
      '["AlbumInfo", {"path": "~alice/2020/Rome", "title": "Rome", '
      '"subTitle": "", "rights": ${rightsOf(rights)}, "parts": ['
      '${photo("w.jpg", 0)}, ${photo("x.jpg", 1)}]}]';

  @override
  http.Response answer(http.Request request) {
    if (request.method == "GET" &&
        request.url.queryParameters["type"] == "json") {
      switch (Uri.decodeFull(request.url.path)) {
        case "/valbum/data/~alice/Best/":
          return json(aliceBest);
        case "/valbum/data/~alice/2020/Rome/":
          return json(aliceRome);
      }
    }
    return super.answer(request);
  }
}

void main() {
  testWidgets('a photo of ~alice/Best opens ~alice/2020/Rome, marked',
      (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await openSpace(
        tester, AliceSpace(), const ListingOrAlbumRoute(["~alice", "Best"]));
    await rightClick(tester, "pick.jpg");
    await tester.tap(find.byKey(const Key("tile-context-show-in-album")));
    await settle(tester);

    expect(
        routeOf(tester), const ListingOrAlbumRoute(["~alice", "2020", "Rome"]));
    expect(
        find.descendant(
            of: tileOf("x.jpg"), matching: find.byKey(shownInAlbumMarkKey)),
        findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('the viewer opened there goes the same way', (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await openSpace(
        tester, AliceSpace(), const ListingOrAlbumRoute(["~alice", "Best"]));
    await tester.tap(tileOf("pick.jpg"));
    await settle(tester);
    expect(routeOf(tester), isA<ImageRoute>());
    await tester.tap(find.byKey(const Key("viewer-menu")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("viewer-show-in-album")));
    await settle(tester);

    expect(
        routeOf(tester), const ListingOrAlbumRoute(["~alice", "2020", "Rome"]));
    expect(
        find.descendant(
            of: tileOf("x.jpg"), matching: find.byKey(shownInAlbumMarkKey)),
        findsOneWidget);
  });

  test('a write through ~alice/Best forgets ~alice/2020/Rome, not 2020/Rome',
      () async {
    var requests = <http.Request>[];
    var delegate = VAlbumRouterDelegate(
      client: clientHandling(
        (request) => json('["ListingInfo", {"path": "", "title": "", '
            '"folders": []}]'),
        requests: requests,
      ),
      initialRoute: parseRoute(Uri.parse("/")),
    );
    addTearDown(delegate.dispose);
    int fetches(String path) => requests
        .where((r) => Uri.decodeComponent(r.url.path) == "/valbum/data/$path")
        .length;
    Future<void> visit() async {
      await delegate.resourceAt(["~alice", "2020", "Rome"]);
      await delegate.resourceAt(["2020", "Rome"]);
    }

    await visit();
    var best = Resource.fromString(AliceSpace().aliceBest) as AlbumInfo;
    forgetSources(delegate, best, folder: const ["~alice", "Best"]);
    await visit();

    expect(fetches("~alice/2020/Rome/"), 2);
    expect(fetches("2020/Rome/"), 1);
  });
}
