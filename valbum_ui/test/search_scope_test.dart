/// The scope of the search view, issue #249: opened from an album it finds
/// the album's photographs alone, opened from a folder those below it, and
/// the chip "In: …" names where it looks; its removal searches the whole
/// library for the same criteria, a step of the history of its own.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';

import 'search_address_test.dart'
    show AlbumSpace, criteriaInLocation, criteriaOf;
import 'search_view_test.dart'
    show chooseAnne, json, options, pathOf, pumpRoute, settle;
import 'util/l10n.dart';

/// The space of the album `2024/Zoo`, with the folder `2024` titled.
class ScopeSpace extends AlbumSpace {
  @override
  http.Response answer(http.Request request) {
    // The options of any folder, the album's too, before its own answer.
    if (request.url.queryParameters["type"] == "search-options") {
      return json(options);
    }
    if (request.method == "GET" && pathOf(request) == "/valbum/data/2024/") {
      return json('["ListingInfo",{"path":"2024","title":"The year 2024",'
          '"folders":[]}]');
    }
    return super.answer(request);
  }

  /// The folders the searches asked, in order.
  List<String> get searched => [
        for (var request in posts("search")) pathOf(request),
      ];

  /// The folders the options were asked of, in order.
  List<String> get optionsAsked => [
        for (var request in requests)
          if (request.url.queryParameters["type"] == "search-options")
            pathOf(request),
      ];
}

Finder get scopeChip => find.byKey(const Key("search-scope"));

void main() {
  var l10n = l10nOf();

  testWidgets('the search of an album asks the album and names it',
      (tester) async {
    var space = ScopeSpace();
    var delegate = await pumpRoute(
        tester, space, const ListingOrAlbumRoute(["2024", "Zoo"]));

    await tester.tap(find.byKey(const Key("open-search")));
    await settle(tester);
    await chooseAnne(tester);

    expect(delegate.route, const SearchRoute(["2024", "Zoo"]));
    expect(space.searched, ["/valbum/data/2024/Zoo/"],
        reason: "Nothing of another album is asked for.");
    expect(space.optionsAsked, ["/valbum/data/2024/Zoo/"],
        reason: "What is offered to choose is the album's own.");
    expect(scopeChip, findsOneWidget);
    expect(find.text(l10n.searchScope("Zoo")), findsOneWidget,
        reason: "The album is named by its title.");
    expect(
        find.descendant(
            of: scopeChip, matching: find.byIcon(Icons.photo_album_outlined)),
        findsOneWidget);
  });

  testWidgets('the search of a folder names the folder', (tester) async {
    var space = ScopeSpace();
    await pumpRoute(tester, space, const SearchRoute(["2024"]));
    await chooseAnne(tester);

    expect(space.searched, ["/valbum/data/2024/"]);
    expect(find.text(l10n.searchScope("The year 2024")), findsOneWidget);
    expect(
        find.descendant(
            of: scopeChip, matching: find.byIcon(Icons.folder_outlined)),
        findsOneWidget);
  });

  testWidgets('the search of the whole library has no scope chip',
      (tester) async {
    var space = ScopeSpace();
    await pumpRoute(tester, space, const SearchRoute([]));

    expect(find.byKey(const Key("search-any-of")), findsOneWidget);
    expect(scopeChip, findsNothing);
  });

  testWidgets('removing the scope searches the whole library for the same',
      (tester) async {
    var space = ScopeSpace();
    var delegate =
        await pumpRoute(tester, space, const SearchRoute(["2024", "Zoo"]));
    await chooseAnne(tester);

    await tester.tap(find.byTooltip(l10n.searchEverywhere));
    await settle(tester);

    expect(delegate.route, const SearchRoute([]));
    expect(space.searched, ["/valbum/data/2024/Zoo/", "/valbum/data/"]);
    var anne = const SearchCriteria(persons: ["p1"], groupBy: [SearchGroup.album]);
    expect(criteriaOf(space.posts("search").last), anne,
        reason: "The criteria are kept, the scope alone is widened.");
    expect(criteriaInLocation(delegate), anne,
        reason: "The location carries the criteria, the root its scope.");
    expect(scopeChip, findsNothing);
    expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
  });

  testWidgets('widening without criteria shows the library unsearched',
      (tester) async {
    var space = ScopeSpace();
    var delegate = await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    delegate.go(const SearchRoute(["2024", "Zoo"]));
    await settle(tester);

    await tester.tap(find.byTooltip(l10n.searchEverywhere));
    await settle(tester);

    expect(delegate.route, const SearchRoute([]));
    expect(find.byKey(const Key("search-person-p1")), findsNothing,
        reason: "What the library's search held before is not shown.");
    expect(find.byKey(const Key("search-hint")), findsOneWidget);
  });

  testWidgets('a view saved from an album is offered the album\'s folder',
      (tester) async {
    var space = ScopeSpace();
    await pumpRoute(tester, space, const ListingOrAlbumRoute(["2024", "Zoo"]));
    await tester.tap(find.byKey(const Key("open-search")));
    await settle(tester);
    await chooseAnne(tester);

    await tester.tap(find.byKey(const Key("search-save-as-view")));
    await settle(tester);

    expect(tester.widget<FolderPicker>(find.byType(FolderPicker)).initialPath,
        ["2024"],
        reason: "A saved search lies in a folder and looks below it.");
    // With the search field of every folder picker (issue #261).
    expect(find.byKey(const Key("picker-search")), findsOneWidget);
  });

  testWidgets('what is selected among the results is no selection of the album',
      (tester) async {
    var space = ScopeSpace();
    var delegate = await pumpRoute(
        tester, space, const ListingOrAlbumRoute(["2024", "Zoo"]));
    await tester.tap(find.byKey(const Key("open-search")));
    await settle(tester);
    await chooseAnne(tester);

    await tester.longPress(find.byKey(const ValueKey("2020 Trip/x.jpg")));
    await settle(tester);

    expect(find.byKey(const Key("select-mode-count")), findsOneWidget);
    expect(delegate.editSession(const ["2024", "Zoo", searchSegment]).selection,
        hasLength(1));
    expect(delegate.editSession(const ["2024", "Zoo"]).selection, isEmpty,
        reason: "The album beneath shares the path, not the selection.");
  });
}
