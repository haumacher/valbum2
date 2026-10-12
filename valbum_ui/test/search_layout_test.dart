/// The layout of the search view on a phone, issue #248: the criteria are no
/// fixed panel over the results but the top of one scrolling page, so that
/// what was found takes the screen once they are scrolled away, and a new
/// result starts at the top, the criteria in view again.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/search_view.dart';

import 'search_view_test.dart' show Space, chooseAnne, pumpRoute, settle;
import 'util/l10n.dart';

/// The number of photographs [many] finds.
const int count = 24;

/// What a search finds: [count] photographs of the album `2020 Trip`.
final String many = '["AlbumInfo",{"kind":"SEARCH","parts":[${[
  for (var i = 0; i < count; i++)
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/x$i.jpg",'
        '"date":${1015113600000 + i * 60000},"width":2048,"height":1536,'
        '"orientation":"IDENTITY","ref":{"hash":"","path":"2020 Trip/x$i.jpg"}}]'
].join(",")}],'
    '"rights":[{"name":"view"},{"name":"download"}]}]';

/// A phone in portrait.
void phone(WidgetTester tester) {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

Finder get criteria => find.byKey(const Key("search-criteria"));

Finder tile(int i) => find.byKey(ValueKey("2020 Trip/x$i.jpg"));

/// The bottom edge of the search view's app bar.
double appBarBottom(WidgetTester tester) => tester
    .getRect(
      find.descendant(
        of: find.byType(SearchView),
        matching: find.byType(AppBar),
      ),
    )
    .bottom;

/// The scroll view of the results.
Finder get resultsScrollable => find
    .descendant(
      of: find.byType(AlbumContent),
      matching: find.byType(Scrollable),
    )
    .first;

void main() {
  var l10n = l10nOf();

  testWidgets('the criteria scroll away and the results take the screen', (
    tester,
  ) async {
    phone(tester);
    var space = Space()..searchAnswer = many;
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    var top = appBarBottom(tester);
    expect(tester.getRect(criteria).top, greaterThanOrEqualTo(top));
    expect(
      tester.getRect(tile(0)).top,
      lessThan(844),
      reason: "The first photograph is on the screen below the criteria.",
    );

    await tester.drag(resultsScrollable, const Offset(0, -700));
    await settle(tester);

    expect(
      criteria.hitTestable(),
      findsNothing,
      reason: "The criteria scrolled out of view with the page.",
    );
    var shown = [
      for (var i = 0; i < count; i++)
        if (tile(i).evaluate().isNotEmpty) tester.getRect(tile(i)),
    ].where((rect) => rect.bottom > top && rect.top < 844).toList();
    expect(shown, isNotEmpty);
    expect(
      shown.map((rect) => rect.top).reduce((a, b) => a < b ? a : b),
      lessThanOrEqualTo(top),
      reason: "The photographs reach the top of the screen.",
    );

    await tester.scrollUntilVisible(
      tile(count - 1),
      300,
      scrollable: resultsScrollable,
    );
    expect(tile(count - 1), findsOneWidget, reason: "The last one is reached.");
  });

  testWidgets('a new result starts at the top, the criteria in view', (
    tester,
  ) async {
    phone(tester);
    var space = Space()..searchAnswer = many;
    var delegate = await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    var atTop = tester.getRect(criteria).top;
    await tester.drag(resultsScrollable, const Offset(0, -700));
    await settle(tester);
    expect(criteria.hitTestable(), findsNothing);

    var session = delegate.searchSession(const []);
    session.choose(
      session.criteria.copyWith(camera: "Pixel 7"),
      space.client,
      const [],
    );
    await settle(tester);

    expect(space.posts("search"), hasLength(2));
    expect(
      tester.getRect(criteria).top,
      atTop,
      reason: "The page of the new result is at its top.",
    );
  });

  testWidgets('before anything is chosen the hint is below the criteria', (
    tester,
  ) async {
    phone(tester);
    var space = Space();
    await pumpRoute(tester, space, const SearchRoute([]));

    var page = find.byKey(const Key("search-page"));
    expect(page, findsOneWidget);
    var hint = find.byKey(const Key("search-hint"));
    await tester.scrollUntilVisible(
      hint,
      200,
      scrollable:
          find.descendant(of: page, matching: find.byType(Scrollable)).first,
    );
    expect(find.text(l10n.searchChooseHint), findsOneWidget);
    expect(
      tester.getRect(hint).top,
      greaterThan(tester.getRect(criteria).bottom),
    );
  });
}
