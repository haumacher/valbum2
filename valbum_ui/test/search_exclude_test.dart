/// Exclusion and "any of these" in the search view, issue #242: a long press
/// or a secondary click on a chip excludes its criterion, the choosers say so
/// with a switch of their own, "All of these" turns into "Any of these", a
/// saved search holding a NOT opens editable with the chip marked, and "Save
/// as view…" stores the tree — beside the grouping, the count and the select
/// mode of before.
library;

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'search_group_by_test.dart' show chooseKeys;
import 'search_view_test.dart'
    show Space, chooseAnne, json, pathOf, pumpRoute, settle;
import 'util/l10n.dart';

/// A saved search for Anne without Philipp.
const String savedWithout = '["AlbumInfo",{"kind":"SEARCH",'
    '"title":"Anne, not Philipp","query":{"version":1,"root":["SearchAnd",'
    '{"criteria":[["SearchPerson",{"person":"p1"}],["SearchNot",{"criterion":'
    '["SearchPerson",{"person":"p2"}]}]]}]},"parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/x.jpg","date":1015113600000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"2020 Trip/x.jpg"}}]]}]';

class ExcludeSpace extends Space {
  @override
  http.Response answer(http.Request request) {
    if (pathOf(request) == "/valbum/data/Without/") {
      return json(savedWithout);
    }
    return super.answer(request);
  }

  /// The query of the last search asked.
  SearchQuery get lastQuery =>
      SearchQuery.read(JsonReader.fromString(posts("search").last.body));
}

/// Ticks Philipp in the person chooser, next to what is ticked.
Future<void> choosePhilipp(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("search-add-persons")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choice-p2")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choose-done")));
  await settle(tester);
}

/// The rows of [query], each as the name of its type, a NOT as `Not(…)`.
List<String> rowsOf(SearchQuery query) {
  String name(SearchCriterion? c) => switch (c) {
        SearchNot(criterion: var inner) => "Not(${name(inner)})",
        SearchPerson(person: var p) => "Person($p)",
        _ => "${c.runtimeType}",
      };
  var root = query.root;
  var rows = root is SearchAnd ? root.criteria : (root as SearchOr).criteria;
  return [for (var row in rows) name(row)];
}

void main() {
  var l10n = l10nOf();

  testWidgets(
      'a long press on a person excludes the person, a secondary '
      'click includes it again', (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await choosePhilipp(tester);
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Person(p2)"]);

    await tester.longPress(find.byKey(const Key("search-person-p2")));
    await settle(tester);

    expect(find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
    expect(find.text(l10n.searchExcluded("Philipp")), findsOneWidget);
    expect(space.lastQuery.root, isA<SearchAnd>());
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Not(Person(p2))"]);

    await tester.tap(find.byKey(const Key("search-person-p2-excluded")),
        buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
    await settle(tester);
    expect(find.byKey(const Key("search-person-p2")), findsOneWidget);
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Person(p2)"]);

    await tester.tap(find.descendant(
        of: find.byKey(const Key("search-person-p2")),
        matching: find.byIcon(Icons.clear)));
    await settle(tester);
    expect(rowsOf(space.lastQuery), ["Person(p1)"],
        reason: "The delete icon takes the person away.");
  });

  testWidgets('the chooser excludes a person with the switch of its row',
      (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    await tester.tap(find.byKey(const Key("search-add-persons")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-exclude-p2")));
    await settle(tester);
    expect(find.text(l10n.searchExcluded("Philipp")), findsOneWidget,
        reason: "The row says so, and is ticked.");
    await tester.tap(find.byKey(const Key("search-choose-done")));
    await settle(tester);

    expect(rowsOf(space.lastQuery), ["Person(p1)", "Not(Person(p2))"]);
    expect(find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
  });

  testWidgets('the rating and the camera are excluded in their dialogs',
      (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    await tester.tap(find.byKey(const Key("search-camera")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-exclude-switch")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-camera-Pixel 7")));
    await settle(tester);
    expect(find.byKey(const Key("search-camera-excluded")), findsOneWidget);
    expect(find.text(l10n.searchExcluded("Pixel 7")), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-rating")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-exclude-switch")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-rating-1")));
    await settle(tester);

    expect(rowsOf(space.lastQuery), [
      "Person(p1)",
      "Not(SearchRating)",
      "Not(SearchCamera)",
    ]);

    // The camera's dialog opens with its switch on, and turns it off.
    await tester.tap(find.byKey(const Key("search-camera-excluded")));
    await settle(tester);
    expect(
        tester
            .widget<SwitchListTile>(
                find.byKey(const Key("search-exclude-switch")))
            .value,
        isTrue);
    await tester.tap(find.byKey(const Key("search-exclude-switch")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-camera-Pixel 7")));
    await settle(tester);
    expect(rowsOf(space.lastQuery),
        ["Person(p1)", "Not(SearchRating)", "SearchCamera"]);
  });

  testWidgets('the text field excludes its text with its icon', (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await tester.enterText(find.byKey(const Key("search-text")), "lake");
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await settle(tester);

    await tester.tap(find.byKey(const Key("search-text-exclude")));
    await settle(tester);
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Not(SearchText)"]);
    expect(
        find.byKey(const Key("search-text-exclude-excluded")), findsOneWidget);
  });

  testWidgets('"Any of these" turns the AND at the top into an OR',
      (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await choosePhilipp(tester);
    expect(find.text(l10n.searchAllOf), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-any-of")));
    await settle(tester);

    expect(find.text(l10n.searchAnyOf), findsOneWidget);
    expect(space.lastQuery.root, isA<SearchOr>());
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Person(p2)"]);

    await tester.tap(find.byKey(const Key("search-any-of")));
    await settle(tester);
    expect(space.lastQuery.root, isA<SearchAnd>());
    expect(find.text(l10n.searchAllOf), findsOneWidget);
  });

  testWidgets(
      'a saved search holding a NOT opens editable, the chip marked, '
      'and Save writes the tree back', (tester) async {
    var space = ExcludeSpace();
    var delegate =
        await pumpRoute(tester, space, const ListingOrAlbumRoute(["Without"]));

    await tester.tap(find.byIcon(Icons.more_vert).last);
    await settle(tester);
    await tester.tap(find.byKey(const Key("edit-search")));
    await settle(tester);

    expect(find.text(l10n.searchCannotEdit), findsNothing);
    expect(delegate.route, const SearchRoute([]));
    expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
    expect(find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
    expect(find.text(l10n.searchExcluded("Philipp")), findsOneWidget);
    expect(rowsOf(space.lastQuery), ["Person(p1)", "Not(Person(p2))"]);

    await tester.tap(find.byKey(const Key("search-any-of")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-save-edit")));
    await settle(tester);

    var album = Resource.fromString(space.puts.single.body) as AlbumInfo;
    expect(album.query!.root, isA<SearchOr>());
    expect(rowsOf(album.query!), ["Person(p1)", "Not(Person(p2))"]);
    expect(delegate.route, const ListingOrAlbumRoute(["Without"]));
  });

  testWidgets(
      '"Save as view…" proposes "Anne, not Philipp" and stores the '
      'tree', (tester) async {
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await choosePhilipp(tester);
    await tester.longPress(find.byKey(const Key("search-person-p2")));
    await settle(tester);

    await tester.tap(find.byKey(const Key("search-save-as-view")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await settle(tester);
    var proposed = "Anne, ${l10n.searchWordNot} Philipp";
    expect(find.widgetWithText(TextField, proposed), findsOneWidget);
    await tester.tap(find.text(l10n.apply));
    await settle(tester);

    var album = Resource.fromString(space.puts.single.body) as AlbumInfo;
    expect(album.title, proposed);
    expect(album.query!.root, isA<SearchAnd>());
    expect(rowsOf(album.query!), ["Person(p1)", "Not(Person(p2))"]);
  });

  testWidgets(
      'the grouping, the count and the select mode work beside an '
      'exclusion', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var space = ExcludeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await choosePhilipp(tester);
    await tester.longPress(find.byKey(const Key("search-person-p2")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-any-of")));
    await settle(tester);
    var searches = space.posts("search").length;
    expect(find.text(l10n.searchCount(1)), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-group-by")));
    await settle(tester);
    await chooseKeys(tester, "year", null);

    expect(space.posts("search"), hasLength(searches),
        reason: "A grouping alone asks nothing again.");
    expect(find.text(l10n.searchGroupedBy(l10n.searchGroupByYear)),
        findsOneWidget);
    expect(find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
    expect(find.text(l10n.searchAnyOf), findsOneWidget);
    expect(find.text(l10n.searchCount(1)), findsOneWidget);

    await tester.longPress(find.byKey(const ValueKey("2020 Trip/x.jpg")));
    await settle(tester);
    expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
    expect(find.byKey(const Key("search-criteria")), findsNothing);
    await tester.tap(find.byKey(const Key("select-mode-leave")));
    await settle(tester);
    expect(find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
    expect(space.posts("search"), hasLength(searches));
    var body =
        jsonDecode(space.posts("search").last.body) as Map<String, dynamic>;
    expect(body["root"][0], "SearchOr");
  });
}
