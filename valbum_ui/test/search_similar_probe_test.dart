/// "More like this" chained (issue #257, package 5): asked again on a photo
/// the similarity search found, the search compares with that photo instead —
/// one photo in the query, never two, and the photo named where it lies.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/resource.dart';

import 'search_similar_test.dart' show viewer, moreLikeA, lastQuery;
import 'util/photo_space.dart';

void main() {
  testWidgets("again from a photo it found compares with that one",
      (tester) async {
    var space = viewer();
    await moreLikeA(tester, space);
    await rightClick(tester, "trip/c.jpg");
    await tester.tap(find.byKey(const Key("tile-context-more-like-this")));
    await settle(tester);

    var root = lastQuery(space).root as SearchAnd;
    var similar = root.criteria.whereType<SearchSimilar>();
    expect(similar, hasLength(1), reason: "One photo, the server refuses two.");
    expect(similar.single.ref!.path, "trip/c.jpg");
    expect(find.byKey(const Key("search-similar")), findsOneWidget);
  });
}
