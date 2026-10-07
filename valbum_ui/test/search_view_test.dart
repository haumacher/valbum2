/// The search view and saved searches of issue #227: criteria built from what
/// the folder offers, the results shown by the album grid, the sentence of an
/// empty result, "Save as view…" creating the album and opening it, "Edit
/// search" for the editors of a saved search, "Create collection from
/// these…", and the German strings.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'l10n_german_test.dart' show speakGerman;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The options the space offers: Anne and Philipp, a label and a camera.
const String options = '{"persons":[{"id":"p1","name":"Anne"},'
    '{"id":"p2","name":"Philipp"}],"places":[{"geonameId":2953481,'
    '"name":"Baden-Württemberg","kind":"ADM1","country":"DE"}],'
    '"labels":[{"name":"Holiday"}],"cameras":[{"name":"Pixel 7"}]}';

/// What a search finds: one photograph of the album `2020 Trip`, named by its
/// path below the folder searched.
const String found = '["AlbumInfo",{"kind":"SEARCH","parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/x.jpg","date":1015113600000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"2020 Trip/x.jpg"}}]],'
    '"rights":[{"name":"view"},{"name":"download"}]}]';

/// A saved search below the root, looking for Anne.
const String saved = '["AlbumInfo",{"kind":"SEARCH","title":"Anne",'
    '"query":{"version":1,"root":["SearchAnd",{"criteria":'
    '[["SearchPerson",{"person":"p1"}]]}]},"parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/x.jpg","date":1015113600000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"2020 Trip/x.jpg"}}]]}]';

class Space {
  final List<http.Request> requests = [];

  /// What the next search finds.
  String searchAnswer = found;

  http.Response answer(http.Request request) {
    var path = pathOf(request);
    var parameters = request.url.queryParameters;
    if (request.method == "PUT") {
      return json('{"path":"Anne","message":""}');
    }
    if (request.method == "POST") {
      var action = parameters["action"];
      if (action == "search") {
        return json(searchAnswer);
      }
      if (action == "collect") {
        return json('{"outcomes":[{"name":"2020 Trip/x.jpg",'
            '"newName":"x.jpg","message":""}]}');
      }
    }
    if (parameters["type"] == "search-options") {
      return json(options);
    }
    switch (path) {
      case "/valbum/data/":
        return json(fixture("listing-collections.json"));
      case "/valbum/data/Best/":
        return json(fixture("collection-best.json"));
      case "/valbum/data/Anne/":
        return json(saved);
      case "/valbum/data/2020 Trip/":
        return json(fixture("album-target.json"));
    }
    return http.Response("No such resource: $path", 404);
  }

  VAlbumClient get client => VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          return answer(request);
        }),
      );

  List<http.Request> posts(String action) => [
        for (var request in requests)
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == action)
            request,
      ];

  List<http.Request> get puts => [
        for (var request in requests)
          if (request.method == "PUT") request,
      ];
}

Future<VAlbumRouterDelegate> pumpRoute(
    WidgetTester tester, Space space, VAlbumRoute route) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: space.client,
      initialRoute: route,
    ));
    await tester.pumpAndSettle();
  });
  return tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
      as VAlbumRouterDelegate;
}

Future<void> settle(WidgetTester tester) =>
    withFakeImageHttp(() => tester.pumpAndSettle());

/// Chooses Anne in the person chooser.
Future<void> chooseAnne(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("search-add-persons")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choice-p1")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choose-done")));
  await settle(tester);
}

void main() {
  var l10n = l10nOf();

  testWidgets('the start page opens the search view, which asks first',
      (tester) async {
    var space = Space();
    var delegate = await pumpRoute(tester, space, ListingOrAlbumRoute.root);

    await tester.tap(find.byKey(const Key("open-search")));
    await settle(tester);

    expect(delegate.route, const SearchRoute([]));
    expect(find.byKey(const Key("search-hint")), findsOneWidget);
    expect(space.posts("search"), isEmpty,
        reason: "Nothing is searched before something is chosen.");
  });

  testWidgets('choosing criteria searches, and the grid shows the results',
      (tester) async {
    var space = Space();
    await pumpRoute(tester, space, const SearchRoute([]));

    await chooseAnne(tester);
    await tester.tap(find.byKey(const Key("search-camera")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-camera-Pixel 7")));
    await settle(tester);

    var searches = space.posts("search");
    expect(searches, hasLength(2));
    var query = jsonDecode(searches.last.body) as Map<String, dynamic>;
    expect(query["version"], 1);
    expect(jsonEncode(query["root"]),
        contains('["SearchPerson",{"person":"p1"}]'));
    expect(jsonEncode(query["root"]),
        contains('["SearchCamera",{"camera":"Pixel 7"}]'));
    expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
    expect(find.text("Anne"), findsOneWidget);
    expect(
        space.requests.any((r) =>
            pathOf(r) == "/valbum/data/2020 Trip/x.jpg" &&
            isThumbnailRequest(r)),
        isTrue,
        reason: "A result is the photograph's own address below the folder.");
  });

  testWidgets('a search that finds nothing says so', (tester) async {
    var space = Space()
      ..searchAnswer = '["AlbumInfo",{"kind":"SEARCH","parts":[]}]';
    await pumpRoute(tester, space, const SearchRoute([]));

    await chooseAnne(tester);

    expect(find.byKey(const Key("search-empty")), findsOneWidget);
    expect(find.text(l10n.searchNothingFound), findsOneWidget);
  });

  testWidgets('a refusal of the server is shown as it is said', (tester) async {
    var space = Space()
      ..searchAnswer = '["ErrorInfo",{"message":"This search was saved by a '
          'newer version."}]';
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient((request) async {
        if (request.method == "POST") {
          return http.Response(
              '["ErrorInfo",{"message":"Searching is for the members of this '
              'space."}]',
              403,
              headers: {"content-type": "application/json; charset=utf-8"});
        }
        return space.answer(request);
      }),
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
          VAlbumApp(client: client, initialRoute: const SearchRoute([])));
      await tester.pumpAndSettle();
    });

    await chooseAnne(tester);

    expect(find.byKey(const Key("search-error")), findsOneWidget);
    expect(find.textContaining("Searching is for the members"), findsOneWidget);
  });

  testWidgets('"Save as view…" creates the saved search and opens it',
      (tester) async {
    var space = Space();
    var delegate = await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    await tester.tap(find.byKey(const Key("search-save-as-view")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await settle(tester);
    expect(find.byKey(const Key("save-as-view-title")), findsOneWidget);
    expect(find.widgetWithText(TextField, "Anne"), findsOneWidget,
        reason: "The title is proposed from what was looked for.");
    await tester.tap(find.text(l10n.apply));
    await settle(tester);

    expect(space.puts, hasLength(1));
    var put = space.puts.single;
    expect(pathOf(put), "/valbum/data/Anne/");
    var album = Resource.fromString(put.body) as AlbumInfo;
    expect(album.kind, AlbumKind.search);
    expect(album.title, "Anne");
    expect(album.parts, isEmpty, reason: "Nothing but the query is stored.");
    expect(
        ((album.query!.root as SearchAnd).criteria.single as SearchPerson)
            .person,
        "p1");
    expect(delegate.route, const ListingOrAlbumRoute(["Anne"]));
  });

  testWidgets('a saved search offers "Edit search" to its editors',
      (tester) async {
    var space = Space();
    var delegate =
        await pumpRoute(tester, space, const ListingOrAlbumRoute(["Anne"]));

    await tester.tap(find.byIcon(Icons.more_vert).last);
    await settle(tester);
    expect(find.byKey(const Key("collect-all")), findsOneWidget);
    expect(find.byKey(const Key("open-search")), findsNothing,
        reason: "A saved search holds no photographs to search below.");
    await tester.tap(find.byKey(const Key("edit-search")));
    await settle(tester);

    expect(delegate.route, const SearchRoute([]));
    expect(find.text(l10n.editSearch), findsOneWidget);
    expect(find.byKey(const Key("search-person-p1")), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-camera")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-camera-Pixel 7")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-save-edit")));
    await settle(tester);

    var put = space.puts.single;
    expect(pathOf(put), "/valbum/data/Anne/");
    var album = Resource.fromString(put.body) as AlbumInfo;
    expect(album.kind, AlbumKind.search);
    expect(album.parts, isEmpty);
    expect((album.query!.root as SearchAnd).criteria, hasLength(2));
    expect(delegate.route, const ListingOrAlbumRoute(["Anne"]));
  });

  testWidgets('"Create collection from these…" collects what was found',
      (tester) async {
    var space = Space();
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);

    await tester.tap(find.byKey(const Key("search-create-collection")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-folder-Best")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("picker-confirm")));
    await settle(tester);

    var collect = space.posts("collect").single;
    expect(pathOf(collect), "/valbum/data/");
    var body = jsonDecode(collect.body) as Map<String, dynamic>;
    expect(body["target"], "Best");
    expect(body["names"], [
      {"name": "2020 Trip/x.jpg"}
    ]);
  });

  testWidgets('the search view speaks German', (tester) async {
    speakGerman(tester);
    var german = l10nOf(const Locale("de"));
    var space = Space();
    await pumpRoute(tester, space, const SearchRoute([]));

    expect(find.text(german.searchAction), findsOneWidget);
    expect(find.text(german.searchChooseHint), findsOneWidget);
    expect(german.searchChooseHint, isNot(l10n.searchChooseHint));
    expect(german.saveAsView, isNot(l10n.saveAsView));
    expect(german.editSearch, isNot(l10n.editSearch));
  });
}
