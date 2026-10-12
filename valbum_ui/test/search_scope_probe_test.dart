/// Probe for issue #249: an album whose folder name carries a date and
/// spaces, reached by its address alone, is searched by itself and named by
/// its title; widening keeps the criteria, and the browser's Back returns to the album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'search_address_test.dart' show recordLocations;
import 'search_view_test.dart'
    show Space, chooseAnne, json, options, pathOf, pumpRoute, settle;
import 'util/l10n.dart';

const folder = "2025-08-14 Mission Camino del Norte";

class MissionSpace extends Space {
  @override
  http.Response answer(http.Request request) {
    if (request.url.queryParameters["type"] == "search-options") {
      return json(options);
    }
    if (request.method == "GET" && pathOf(request) == "/valbum/data/2025/$folder/") {
      return json('["AlbumInfo",{"path":"","title":"Camino del Norte",'
          '"subTitle":"","parts":[["ImagePart",{"kind":"IMAGE",'
          '"name":"a.jpg","date":1015113600000,"width":2048,"height":1536,'
          '"orientation":"IDENTITY","rating":0}]]}]');
    }
    return super.answer(request);
  }

  List<String> get searched => [
        for (var request in posts("search")) pathOf(request),
      ];
}

void main() {
  var l10n = l10nOf();

  testWidgets('a dated album with spaces, reached by its address',
      (tester) async {
    var reported = recordLocations(tester);
    var space = MissionSpace();
    var delegate =
        await pumpRoute(tester, space, const SearchRoute(["2025", folder]));
    await chooseAnne(tester);

    expect(space.searched, ["/valbum/data/2025/$folder/"]);
    expect(find.text(l10n.searchScope("Camino del Norte")), findsOneWidget,
        reason: "Named by the album's title, not its folder name.");

    await tester.tap(find.byTooltip(l10n.searchEverywhere));
    await settle(tester);
    expect(delegate.route, const SearchRoute([]));
    expect(space.searched.last, "/valbum/data/");

    expect(reported.last.replace, isFalse,
        reason: "Widening is a step of the browser history: Back returns.");
    expect(reported.last.uri, startsWith("/.search/"));
    expect(
        reported.any((r) => r.uri.startsWith(
            "/2025/2025-08-14%20Mission%20Camino%20del%20Norte/.search/")),
        isTrue,
        reason: "The album's search was a location of its own before.");
  });
}
