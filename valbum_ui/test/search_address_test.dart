/// The last two items of issue #243: the search from inside an album, and
/// the criteria of the search view in the location.
///
/// An album offers its own search (issue #249), under the rule of a folder's
/// app bar and never inside a link. The criteria chosen travel in
/// the location as `?q=<stored query>`: a change of them replaces the
/// location rather than adding a step to the history, a location carrying
/// them runs the search at once (a reload, a bookmark), and one this screen
/// cannot show says so instead of failing.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';
import 'package:valbum_ui/search_view.dart';

import 'search_view_test.dart'
    show Space, chooseAnne, json, pathOf, pumpRoute, settle;
import 'share_album_chrome_test.dart' as chrome;
import 'util/l10n.dart';

/// An album below a folder, holding one photograph, so that it shows its
/// floating controls rather than an app bar.
const String zoo = '["AlbumInfo",{"path":"","title":"Zoo","subTitle":"",'
    '"parts":[["ImagePart",{"kind":"IMAGE","name":"a.jpg",'
    '"date":1015113600000,"width":2048,"height":1536,'
    '"orientation":"IDENTITY","rating":0}]]}]';

/// The space of `search_view_test.dart`, with the album `2024/Zoo`.
class AlbumSpace extends Space {
  @override
  http.Response answer(http.Request request) {
    if (request.method == "GET" &&
        pathOf(request) == "/valbum/data/2024/Zoo/") {
      return json(zoo);
    }
    return super.answer(request);
  }
}

/// Anne, without Philipp, any of these, grouped by album.
const SearchCriteria rich = SearchCriteria(
  persons: ["p1"],
  excludedPersons: ["p2"],
  anyOf: true,
  groupBy: [SearchGroup.album],
);

/// The locations the router reported to the engine, and whether each
/// replaced the entry of the history.
List<({String uri, bool replace})> recordLocations(WidgetTester tester) {
  var reported = <({String uri, bool replace})>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
    SystemChannels.navigation,
    (call) async {
      if (call.method == "routeInformationUpdated") {
        var arguments = call.arguments as Map;
        reported.add((
          uri: arguments["uri"] as String,
          replace: arguments["replace"] as bool,
        ));
      }
      return null;
    },
  );
  addTearDown(() => tester.binding.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.navigation, null));
  return reported;
}

/// The criteria of the search request [request] asked for.
SearchCriteria? criteriaOf(http.Request request) =>
    SearchCriteria.fromQuery(SearchQuery.fromString(request.body));

/// The criteria the location of [delegate] carries.
SearchCriteria? criteriaInLocation(VAlbumRouterDelegate delegate) {
  var route = delegate.currentConfiguration as SearchRoute;
  var query = route.query;
  return query == null ? null : SearchCriteria.fromAddress(query);
}

void main() {
  var l10n = l10nOf();

  group('the search from an album', () {
    testWidgets('searches the album alone', (tester) async {
      var space = AlbumSpace();
      var delegate = await pumpRoute(
          tester, space, const ListingOrAlbumRoute(["2024", "Zoo"]));

      var button = find.byKey(const Key("open-search"));
      expect(button, findsOneWidget);
      expect(find.byType(AppBar), findsNothing,
          reason: "The album shows its floating controls, the search among "
              "them.");
      expect(tester.widget<IconButton>(button).tooltip, l10n.searchAction);

      await tester.tap(button);
      await settle(tester);

      expect(delegate.route, const SearchRoute(["2024", "Zoo"]),
          reason: "The search is scoped to the album (issue #249).");
      expect(find.byKey(const Key("search-hint")), findsOneWidget);
    });

    testWidgets('is offered in the app bar of an empty album', (tester) async {
      var space = Space();
      var delegate = await pumpRoute(
          tester, space, const ListingOrAlbumRoute(["2020 Trip"]));

      expect(find.byType(AppBar), findsOneWidget);
      await tester.tap(find.byKey(const Key("open-search")));
      await settle(tester);

      expect(delegate.route, const SearchRoute(["2020 Trip"]),
          reason: "An album at the top searches itself (issue #249).");
    });

    testWidgets('fits beside the menu on a phone', (tester) async {
      tester.view.physicalSize = const Size(360, 740);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var space = AlbumSpace();
      await pumpRoute(
          tester, space, const ListingOrAlbumRoute(["2024", "Zoo"]));

      var search = tester.getRect(find.byKey(const Key("open-search")));
      var menu = tester.getRect(find.byIcon(Icons.more_vert));
      var up = tester.getRect(find.byIcon(Icons.arrow_back));
      expect(search.right, lessThanOrEqualTo(menu.left));
      expect(up.right, lessThan(search.left));
      expect(menu.right, lessThanOrEqualTo(360));
    });

    testWidgets('is not offered inside a link', (tester) async {
      await chrome.pumpSession(tester, chrome.albumLink());

      expect(find.byType(AlbumContent), findsOneWidget);
      expect(find.byKey(const Key("open-search")), findsNothing);
    });
  });

  group('the criteria in the location', () {
    test('travel in the query and are read back as they were', () {
      var route = SearchRoute(const ["2024", "Zoo"], query: rich.toAddress());
      var uri = routeToUri(route, basePath: "/valbum/");
      expect(uri.path, "/valbum/2024/Zoo/.search/");
      expect(uri.queryParameters, contains(searchQueryParameter));

      var parsed = parseRoute(Uri.parse(uri.toString()), basePath: "/valbum/");
      expect(parsed, route);
      var query = (parsed as SearchRoute).query!;
      expect(SearchCriteria.fromAddress(query), rich);
    });

    test('a text with the characters of a location survives', () {
      var criteria = const SearchCriteria(
        text: "a&b=c?d#e/f+g %h",
        excluded: {SearchKind.text},
      );
      var route = SearchRoute(const [], query: criteria.toAddress());
      var parsed = parseRoute(Uri.parse(routeToUri(route).toString()));
      expect(parsed, route);
      expect(
          SearchCriteria.fromQuery(
              SearchQuery.fromString((parsed as SearchRoute).query!)),
          criteria);
    });

    test('a location without criteria is the search view as before', () {
      expect(
          parseRoute(Uri.parse("/valbum/2024/.search/"), basePath: "/valbum/"),
          const SearchRoute(["2024"]));
      expect(
          routeToUri(const SearchRoute(["2024"])).toString(), "/2024/.search/");
      expect(parseRoute(Uri.parse("/.search/")), const SearchRoute([]));
    });

    test('a query that is no query is kept as it was written', () {
      expect(parseRoute(Uri.parse("/.search/?q=garbage")),
          const SearchRoute([], query: "garbage"));
    });

    testWidgets('choosing a criterion replaces the location', (tester) async {
      var space = Space();
      var delegate = await pumpRoute(tester, space, const SearchRoute([]));
      var reported = recordLocations(tester);

      await chooseAnne(tester);

      expect(
          criteriaInLocation(delegate), const SearchCriteria(persons: ["p1"], groupBy: [SearchGroup.album]));
      expect(reported, isNotEmpty);
      expect(reported.last.uri, startsWith("/.search/?q="));
      expect(reported.every((location) => location.replace), isTrue,
          reason: "Back leaves the search instead of stepping through "
              "every criterion: $reported");

      // The way back leaves the search at once.
      await delegate.popRoute();
      await settle(tester);
      expect(delegate.route, ListingOrAlbumRoute.root);
    });

    testWidgets(
        'a location with criteria searches at once, and a reload '
        'shows the same search', (tester) async {
      var route = SearchRoute(const [], query: rich.toAddress());
      // Opened the way the browser opens it: from the location.
      var opened = parseRoute(Uri.parse(routeToUri(route).toString()));

      for (var round = 0; round < 2; round++) {
        var space = Space();
        // A fresh app, as after a reload.
        await tester.pumpWidget(const SizedBox());
        var delegate = await pumpRoute(tester, space, opened);

        var searches = space.posts("search");
        expect(searches, hasLength(1), reason: "round $round");
        expect(criteriaOf(searches.single), rich);
        expect(delegate.route, const SearchRoute([]));
        expect(criteriaInLocation(delegate), rich);
        expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
        expect(
            find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);
        expect(find.text(l10n.searchAnyOf), findsOneWidget);
        expect(find.byType(AlbumContent), findsOneWidget,
            reason: "What was found is shown.");
      }
    });

    testWidgets('the text of the location is put into the field',
        (tester) async {
      var criteria = const SearchCriteria(text: "beach");
      var space = Space();
      await pumpRoute(
          tester, space, SearchRoute(const [], query: criteria.toAddress()));

      expect(find.widgetWithText(TextField, "beach"), findsOneWidget);
      expect(criteriaOf(space.posts("search").single), criteria);
    });

    testWidgets('the browser\'s history hands over other criteria',
        (tester) async {
      var space = Space();
      var delegate = await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);
      expect(space.posts("search"), hasLength(1));

      await delegate
          .setNewRoutePath(SearchRoute(const [], query: rich.toAddress()));
      await settle(tester);

      expect(space.posts("search"), hasLength(2));
      expect(criteriaOf(space.posts("search").last), rich);
      expect(
          find.byKey(const Key("search-person-p2-excluded")), findsOneWidget);

      // The same criteria again ask nothing.
      await delegate
          .setNewRoutePath(SearchRoute(const [], query: rich.toAddress()));
      await settle(tester);
      expect(space.posts("search"), hasLength(2));
    });

    testWidgets('a query this screen cannot show says so', (tester) async {
      var space = Space();
      var delegate = await pumpRoute(
          tester, space, parseRoute(Uri.parse("/.search/?q=garbage")));

      expect(find.byKey(const Key("search-cannot-edit")), findsOneWidget);
      expect(find.text(l10n.searchCannotEdit), findsOneWidget);
      expect(space.posts("search"), isEmpty);
      expect((delegate.currentConfiguration as SearchRoute).query, "garbage",
          reason: "A reload says the same.");

      // A well-formed query saying more than the screen shows: a folder.
      var folder = jsonEncode({
        "version": 1,
        "root": [
          "SearchAnd",
          {
            "criteria": [
              [
                "SearchFolder",
                {"path": "2020 Trip"}
              ],
            ],
          },
        ],
      });
      await tester.pumpWidget(const SizedBox());
      space = Space();
      await pumpRoute(tester, space, SearchRoute(const [], query: folder));
      expect(find.byKey(const Key("search-cannot-edit")), findsOneWidget);

      // Choosing a criterion starts a search of its own.
      await chooseAnne(tester);
      expect(find.byKey(const Key("search-cannot-edit")), findsNothing);
      expect(criteriaOf(space.posts("search").single),
          const SearchCriteria(persons: ["p1"], groupBy: [SearchGroup.album]));
    });

    testWidgets('a search started from the folder carries no criteria',
        (tester) async {
      var space = Space();
      var delegate = await pumpRoute(tester, space, ListingOrAlbumRoute.root);
      await tester.tap(find.byKey(const Key("open-search")));
      await settle(tester);

      expect(delegate.currentConfiguration, const SearchRoute([]));
    });

    testWidgets(
        'a photograph opened from the results returns to the '
        'search in the location', (tester) async {
      var space = Space();
      var delegate = await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);

      delegate.go(const ImageRoute([], "2020 Trip/x.jpg", fromSearch: true));
      await settle(tester);
      expect(delegate.currentConfiguration,
          const ImageRoute([], "2020 Trip/x.jpg", fromSearch: true));
      delegate.goUp();
      await settle(tester);

      expect(
          criteriaInLocation(delegate), const SearchCriteria(persons: ["p1"], groupBy: [SearchGroup.album]));
      expect(space.posts("search"), hasLength(1));
    });
  });

  test('the session reads the criteria of a location once', () {
    var session = SearchSession();
    var client = Space().client;
    session.adopt(rich.toAddress(), client, const []);
    expect(session.criteria, rich);
    expect(session.unreadable, isNull);
    var result = session.result;
    session.adopt(session.addressQuery!, client, const []);
    expect(identical(session.result, result), isTrue);
  });
}
