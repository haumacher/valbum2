/// Probe of "Show in album" (issue #289): the album a collection photo lies
/// in is named with blanks and nested — the route and the mark still find it.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_actions.dart';

import 'util/photo_space.dart';

const String rome = "2024 Reisen/Rom Urlaub";

class SpacedSpace extends PhotoSpace {
  @override
  String get best => '["AlbumInfo", {"path": "Best", "kind": "COLLECTION", '
      '"title": "Best", "subTitle": "", "rights": ${rightsOf(rights)}, '
      '"parts": [${photo("pick.jpg", 0, ref: "$rome/forum.jpg")}]}]';

  String get romeAlbum => '["AlbumInfo", {"path": "$rome", "title": "Rom", '
      '"subTitle": "", "rights": ${rightsOf(rights)}, "parts": ['
      '${photo("colosseum.jpg", 0)}, ${photo("forum.jpg", 1)}]}]';

  @override
  http.Response answer(http.Request request) {
    if (Uri.decodeFull(request.url.path) == "/valbum/data/$rome/" &&
        request.method == "GET" &&
        request.url.queryParameters["type"] == "json") {
      return json(romeAlbum);
    }
    return super.answer(request);
  }
}

void main() {
  testWidgets('a collection photo opens its nested album with blanks on it',
      (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await openSpace(tester, SpacedSpace(), const ListingOrAlbumRoute(["Best"]));
    await rightClick(tester, "pick.jpg");
    await tester.tap(find.byKey(const Key("tile-context-show-in-album")));
    await settle(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute(["2024 Reisen", "Rom Urlaub"]));
    expect(
        find.descendant(
            of: tileOf("forum.jpg"), matching: find.byKey(shownInAlbumMarkKey)),
        findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
