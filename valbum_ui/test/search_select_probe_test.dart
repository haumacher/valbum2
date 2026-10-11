/// Review probe of the select mode in the search view composed with what it
/// meets: the labels chooser, which shares the filter field with persons and
/// places, and the caption tooltip of a found photograph, which the select
/// mode's tile must keep.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'search_select_test.dart'
    show SelectSpace, listedChoices, markOf, tile;
import 'search_view_test.dart' show chooseAnne, pumpRoute, settle;
import 'util/l10n.dart';

String manyLabels() => '{"persons":[{"id":"p1","name":"Anne"}],"places":[],'
    '"labels":[${[
      for (var name in [
        "Holiday", "Hiking", "Birthday", "Beach", "Christmas", "City",
        "Family", "Food", "Garden", "Winter", "Work"
      ])
        '{"name":"$name"}'
    ].join(",")}],"cameras":[]}';

String foundWithCaption() => '["AlbumInfo",{"kind":"SEARCH","parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/x.jpg","date":1015113600000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY","comment":"At the lake",'
    '"ref":{"hash":"","path":"2020 Trip/x.jpg"}}],'
    '["ImagePart",{"kind":"IMAGE","name":"2021 Zoo/z.jpg","date":1015113700000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"2021 Zoo/z.jpg"}}]],'
    '"rights":[{"name":"view"},{"name":"download"}]}]';

void main() {
  testWidgets('the labels chooser filters like the others, and keeps a tick',
      (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = SelectSpace()..optionsAnswer = manyLabels();
    await pumpRoute(tester, space, const SearchRoute([]));

    await tester.tap(find.byKey(const Key("search-add-labels")));
    await settle(tester);
    expect(listedChoices(tester), hasLength(11));
    expect(find.byKey(const Key("search-choose-filter")), findsOneWidget);

    await tester.enterText(find.byKey(const Key("search-choose-filter")), "bi");
    await settle(tester);
    expect(listedChoices(tester), ["search-choice-Birthday"]);
    await tester.tap(find.byKey(const Key("search-choice-Birthday")));
    await settle(tester);

    await tester.enterText(find.byKey(const Key("search-choose-filter")), "win");
    await settle(tester);
    expect(listedChoices(tester),
        containsAll(["search-choice-Birthday", "search-choice-Winter"]),
        reason: "the ticked label stays listed under a filter it misses");
    await tester.tap(find.byKey(const Key("search-choice-Winter")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-choose-done")));
    await settle(tester);

    expect(find.byKey(const Key("search-label-Birthday")), findsOneWidget);
    expect(find.byKey(const Key("search-label-Winter")), findsOneWidget);
    expect(find.byKey(const Key("search-label-Holiday")), findsNothing);
  });

  testWidgets('a found photograph keeps its caption tooltip in the select mode',
      (tester) async {
    var space = SelectSpace()..searchAnswer = foundWithCaption();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    Finder captionOf(String name) => find.descendant(
        of: tile(name), matching: find.byKey(const Key("tile-caption")));
    expect(captionOf("2020 Trip/x.jpg"), findsOneWidget);
    expect(captionOf("2021 Zoo/z.jpg"), findsNothing);

    await tester.longPress(tile("2021 Zoo/z.jpg"));
    await settle(tester);
    expect(find.byKey(const Key("select-mode-count")), findsOneWidget);
    expect(captionOf("2020 Trip/x.jpg"), findsOneWidget,
        reason: "the select mode wraps the same thumbnail");
    expect(markOf("2020 Trip/x.jpg"), findsOneWidget);

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await settle(tester);
    expect(find.byKey(const Key("select-mode-count")), findsNothing);
    expect(find.byKey(const Key("search-criteria")), findsOneWidget);
    expect(find.text(testL10n.searchAction), findsWidgets);
  });
}
