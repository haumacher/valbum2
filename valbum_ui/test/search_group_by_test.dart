/// "Group by" of the search view and of a saved search, issue #244: the chip
/// and its dialog, the headings derived in the app without asking the server
/// again, the select mode and the count taking photographs only, the grouping
/// stored by "Save as view…" and shown when a saved search is opened.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'download_test.dart' show postedNames;
import 'search_view_test.dart'
    show Space, chooseAnne, json, pathOf, pumpRoute, settle;
import 'util/l10n.dart';

/// A part of a search result named [name] below the folder searched, taken
/// at noon UTC of [day], in [country].
String found(String name, DateTime day, String country, int id) =>
    '["ImagePart",{"kind":"IMAGE","name":"$name",'
    '"date":${DateTime.utc(day.year, day.month, day.day, 12).millisecondsSinceEpoch},'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"$name"},'
    '"places":{"tags":[{"geonameId":$id,"name":"$country","kind":"COUNTRY"}]}}]';

/// Three photographs, by date: Germany 2020, Italy 2021, Germany 2021.
final String threeParts = [
  found("2020 Trip/x.jpg", DateTime(2020, 7, 1), "Germany", 1),
  found("2021 Zoo/y.jpg", DateTime(2021, 5, 1), "Italy", 2),
  found("2021 Zoo/z.jpg", DateTime(2021, 8, 1), "Germany", 1),
].join(",");

final String foundThree = '["AlbumInfo",{"kind":"SEARCH","parts":[$threeParts],'
    '"rights":[{"name":"view"},{"name":"download"}]}]';

/// A saved search for Anne grouped by year and country.
final String savedGrouped = '["AlbumInfo",{"kind":"SEARCH","title":"Grouped",'
    '"query":{"version":1,"root":["SearchAnd",{"criteria":'
    '[["SearchPerson",{"person":"p1"}]]}],"groupBy":["YEAR","COUNTRY"]},'
    '"parts":[$threeParts]}]';

/// The same saved search without a grouping.
final String savedPlain = '["AlbumInfo",{"kind":"SEARCH","title":"Plain",'
    '"query":{"version":1,"root":["SearchAnd",{"criteria":'
    '[["SearchPerson",{"person":"p1"}]]}]},'
    '"parts":[$threeParts]}]';

class GroupSpace extends Space {
  GroupSpace() {
    searchAnswer = foundThree;
  }

  @override
  http.Response answer(http.Request request) {
    switch (pathOf(request)) {
      case "/valbum/data/Grouped/":
        return json(savedGrouped);
      case "/valbum/data/Plain/":
        return json(savedPlain);
    }
    return super.answer(request);
  }
}

/// The texts of the headings shown, top to bottom.
List<String> headings(WidgetTester tester) => [
      for (var element in find.byType(Text).evaluate())
        if ((element.widget as Text).style?.fontSize == 22)
          "# ${(element.widget as Text).data}"
        else if ((element.widget as Text).style?.fontSize == 17)
          "## ${(element.widget as Text).data}",
    ];

/// The photographs shown, top to bottom.
List<String> tiles(WidgetTester tester) {
  var shown = [
    for (var name in ["2020 Trip/x.jpg", "2021 Zoo/y.jpg", "2021 Zoo/z.jpg"])
      if (find.byKey(ValueKey(name)).evaluate().isNotEmpty)
        (name, tester.getTopLeft(find.byKey(ValueKey(name)).first).dy),
  ];
  shown.sort((a, b) => a.$2.compareTo(b.$2));
  return [for (var (name, _) in shown) name];
}

/// The choice [key] of an open dropdown menu, by its text.
Finder menuItem(String key) => find
    .descendant(of: find.byKey(Key(key)), matching: find.byType(Text))
    .last;

/// Chooses [first] and [second] (`null`: none) in the open dialog.
Future<void> chooseKeys(
    WidgetTester tester, String first, String? second) async {
  await tester.tap(find.byKey(const Key("search-group-first")));
  await settle(tester);
  await tester.tap(menuItem("search-group-first-$first"));
  await settle(tester);
  if (second != null) {
    await tester.tap(find.byKey(const Key("search-group-second")));
    await settle(tester);
    await tester.tap(menuItem("search-group-second-$second"));
    await settle(tester);
  }
  await tester.tap(find.byKey(const Key("search-group-apply")));
  await settle(tester);
}

void main() {
  var l10n = l10nOf();

  testWidgets(
      'the chip groups the result by year and country, without a new '
      'search', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = GroupSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    expect(space.posts("search"), hasLength(1));
    // A new search starts grouped by album (issue #289).
    expect(headings(tester), ["# 2020 Trip", "# 2021 Zoo"]);
    expect(find.text(l10n.searchGroupedBy(l10n.searchGroupByAlbum)),
        findsOneWidget);

    await tester.tap(find.byKey(const Key("search-group-by")));
    await settle(tester);
    expect(find.byKey(const Key("search-group-by-dialog")), findsOneWidget);
    await chooseKeys(tester, "year", "country");

    expect(space.posts("search"), hasLength(1),
        reason: "A grouping alone is derived from the result there is.");
    expect(
        find.text(l10n.searchGroupedBy(
            "${l10n.searchGroupByYear} › ${l10n.groupByKeyCountry}")),
        findsOneWidget);
    expect(headings(tester), [
      "# 2020",
      "## Germany",
      "# 2021",
      "## Germany",
      "## Italy",
    ]);
    expect(
        tiles(tester), ["2020 Trip/x.jpg", "2021 Zoo/z.jpg", "2021 Zoo/y.jpg"]);
    expect(tester.widget<Text>(find.byKey(const Key("search-count"))).data,
        l10n.searchCount(3),
        reason: "The headings are no photographs.");

    // Taken off again: the result by date, still without a new search.
tester
        .widget<InputChip>(find.byKey(const Key("search-group-by")))
        .onDeleted!();
    await settle(tester);
    expect(space.posts("search"), hasLength(1));
    expect(headings(tester), isEmpty);
    expect(
        tiles(tester), ["2020 Trip/x.jpg", "2021 Zoo/y.jpg", "2021 Zoo/z.jpg"]);
  });

  testWidgets('the second key is never the first, and needs one',
      (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = GroupSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    // Without the grouping a new search starts with (issue #289).
    tester
        .widget<InputChip>(find.byKey(const Key("search-group-by")))
        .onDeleted!();
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-group-by")));
    await settle(tester);

    var second = tester.widget<DropdownButtonFormField<SearchGroup?>>(
        find.descendant(
            of: find.byKey(const Key("search-group-second")),
            matching: find.byType(DropdownButtonFormField<SearchGroup?>)));
    expect(second.onChanged, isNull, reason: "No sections, no subsections.");

    await tester.tap(find.byKey(const Key("search-group-first")));
    await settle(tester);
    await tester.tap(menuItem("search-group-first-camera"));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-group-second")));
    await settle(tester);
    expect(find.byKey(const Key("search-group-second-camera")), findsNothing);
    expect(find.byKey(const Key("search-group-second-album")), findsWidgets);
  });

  testWidgets(
      'the select mode selects photographs, and collects their names '
      'alone', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = GroupSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await tester.tap(find.byKey(const Key("search-group-by")));
    await settle(tester);
    await chooseKeys(tester, "year", null);
    expect(headings(tester), ["# 2020", "# 2021"]);

    await tester.longPress(find.byKey(const ValueKey("2020 Trip/x.jpg")));
    await settle(tester);
    expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
    // The check box of the heading takes what stands under it.
    await tester.tap(find.text("2021"));
    await settle(tester);
    expect(tester.widget<Text>(find.byKey(const Key("select-mode-count"))).data,
        l10n.selectedCount(3));

    await tester.tap(find.byKey(const Key("select-mode-collect")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-folder-Best")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await settle(tester);

    var collect = space.posts("collect").single;
    expect(postedNames(collect)..sort(),
        ["2020 Trip/x.jpg", "2021 Zoo/y.jpg", "2021 Zoo/z.jpg"]);
  });

  testWidgets('"Save as view…" stores the grouping with the query',
      (tester) async {
    var space = GroupSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await tester.tap(find.byKey(const Key("search-group-by")));
    await settle(tester);
    await chooseKeys(tester, "month", "town");

    await tester.tap(find.byKey(const Key("search-save-as-view")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await settle(tester);
    await tester.tap(find.text(l10n.apply));
    await settle(tester);

    var album = Resource.fromString(space.puts.single.body) as AlbumInfo;
    expect(album.query!.groupBy, [SearchGroup.month, SearchGroup.town]);
    expect(album.parts, isEmpty, reason: "No heading is ever stored.");
    expect(jsonDecode(space.puts.single.body).toString(),
        contains("[MONTH, TOWN]"));
  });

  testWidgets('a saved search with a grouping shows its headings',
      (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = GroupSpace();
    await pumpRoute(tester, space, const ListingOrAlbumRoute(["Grouped"]));

    expect(headings(tester), [
      "# 2020",
      "## Germany",
      "# 2021",
      "## Germany",
      "## Italy",
    ]);
    expect(
        tiles(tester), ["2020 Trip/x.jpg", "2021 Zoo/z.jpg", "2021 Zoo/y.jpg"]);
    expect(find.byIcon(Icons.edit), findsNothing);
  });

  testWidgets('a saved search without a grouping is shown by date',
      (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = GroupSpace();
    await pumpRoute(tester, space, const ListingOrAlbumRoute(["Plain"]));

    expect(headings(tester), isEmpty);
    expect(
        tiles(tester), ["2020 Trip/x.jpg", "2021 Zoo/y.jpg", "2021 Zoo/z.jpg"]);
  });

  testWidgets('"Edit search" keeps the grouping, and Save writes it back',
      (tester) async {
    var space = GroupSpace();
    await pumpRoute(tester, space, const ListingOrAlbumRoute(["Grouped"]));
    await tester.tap(find.byIcon(Icons.more_vert).last);
    await settle(tester);
    await tester.tap(find.byKey(const Key("edit-search")));
    await settle(tester);
    expect(
        find.text(l10n.searchGroupedBy(
            "${l10n.searchGroupByYear} › ${l10n.groupByKeyCountry}")),
        findsOneWidget);

    await tester.tap(find.byKey(const Key("search-save-edit")));
    await settle(tester);
    var album = Resource.fromString(space.puts.single.body) as AlbumInfo;
    expect(album.query!.groupBy, [SearchGroup.year, SearchGroup.country]);
  });
}
