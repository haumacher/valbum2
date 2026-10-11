/// Review probe of the criteria in the address composed with what the
/// search view has gained today: a location carrying a grouping and an
/// exclusion opens grouped with the exclusion shown, removing the exclusion
/// replaces the location, and the select mode still enters from there.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';

import 'search_address_test.dart' show criteriaInLocation, criteriaOf;
import 'search_select_test.dart' show SelectSpace, tile;
import 'search_view_test.dart' show pumpRoute, settle;

void main() {
  testWidgets('a location with a grouping and an exclusion opens as such',
      (tester) async {
    const criteria = SearchCriteria(
      persons: ["p1"],
      excludedLabels: ["Holiday"],
      groupBy: [SearchGroup.year],
    );
    var route = SearchRoute(const [], query: criteria.toAddress());
    var opened = parseRoute(Uri.parse(routeToUri(route).toString()));
    var space = SelectSpace();
    var delegate = await pumpRoute(tester, space, opened);

    expect(criteriaOf(space.posts("search").single), criteria);
    expect(find.byKey(const Key("search-label-Holiday-excluded")),
        findsOneWidget);
    expect(find.byType(Heading), findsNothing, reason: "headings are parts");
    var headings = tester
        .widgetList<Text>(find.byWidgetPredicate(
            (w) => w is Text && (w.data ?? "").startsWith("200")))
        .length;
    expect(headings, greaterThan(0), reason: "grouped by year");
    expect(find.byKey(const Key("search-count")), findsOneWidget);

    // The exclusion taken away: the location follows, one more search.
    tester
        .widget<InputChip>(
            find.byKey(const Key("search-label-Holiday-excluded")))
        .onDeleted!();
    await settle(tester);
    expect(criteriaInLocation(delegate),
        const SearchCriteria(persons: ["p1"], groupBy: [SearchGroup.year]));
    expect(space.posts("search"), hasLength(2));

    // The select mode from a search opened by its address.
    await tester.longPress(tile("2020 Trip/x.jpg"));
    await settle(tester);
    expect(find.byKey(const Key("select-mode-count")), findsOneWidget);
    expect(delegate.route, const SearchRoute([]));
  });
}
