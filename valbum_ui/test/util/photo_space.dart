/// A space for the tests of the photo action catalogue and the tile shell
/// (issues #289, #290): an album `trip`, a collection `Best` of its photos,
/// a saved search `Saved`, an inbox `Inbox`, the search, and one entry of the
/// bell on `trip` — every one of them answered to a caller of a role chosen
/// per test.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'fake_image_http.dart';
import 'fixtures.dart';

/// The key of the one entry of the bell.
const String newsKey = "a1b2c3d4e5f60718";

/// The options the search offers: Anne alone.
const String searchOptions = '{"persons":[{"id":"p1","name":"Anne"}],'
    '"places":[],"labels":[],"cameras":[]}';

/// A photograph named [name], the [index]th by date.
String photo(String name, int index,
        {String? ref, String broken = "", String kind = "IMAGE"}) =>
    '["ImagePart", {"kind": "$kind", "name": "$name", '
    '"date": ${1015113600000 + index * 10000}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": 0'
    '${broken.isEmpty ? "" : ', "broken": "$broken"'}'
    '${ref == null ? "" : ', "ref": {"hash": "", "path": "$ref"}'}}]';

/// The rights list of an answer.
String rightsOf(List<String> rights) =>
    '[${rights.map((r) => '{"name": "$r"}').join(", ")}]';

/// The names of the photographs of `trip`: `a.jpg`, `b.jpg`, `c.jpg` and,
/// below them, [extra] more, `img0.jpg` …
List<String> tripNames(int extra) =>
    ["a.jpg", "b.jpg", "c.jpg", for (var i = 0; i < extra; i++) "img$i.jpg"];

class PhotoSpace {
  final List<http.Request> requests = [];

  /// The role the server answers the caller with; `null` for no answer.
  String? role;

  /// The rights of every folder.
  List<String> rights;

  /// How many photographs `trip` holds beyond its first three.
  int extra;

  /// What the saved search groups by, as stored; `null` for nothing stored.
  String? savedGroupBy;

  /// Whether the photographs of the collection and the search say where
  /// they lie, as the server says it to a member alone.
  bool refs;

  /// The kind of `a.jpg`, wherever it is answered: a video for the poster.
  String aKind;

  /// Whether the space looks for faces, as the server answers it (#124).
  bool faces;

  /// Whether the space has content search on, as the server answers it
  /// (#257): what "More like this" is offered by.
  bool content;

  /// What a search answers, [found] where `null`.
  String? searchAnswer;

  PhotoSpace({
    this.role = "edit",
    this.rights = const ["view", "download", "contribute", "edit"],
    this.extra = 0,
    this.savedGroupBy,
    this.refs = true,
    this.aKind = "IMAGE",
    this.faces = false,
    this.content = false,
    this.searchAnswer,
  });

  /// [photo], `a.jpg` of the kind [aKind].
  String _photo(String name, int index, {String? ref}) => photo(name, index,
      ref: ref, kind: name.endsWith("a.jpg") ? aKind : "IMAGE");

  String get _auth => '{"mode":"writes","deviceName":"Desk",'
      '"writeAllowed":true,"userName":"carol","role":"$role","space":"",'
      '"clearance":"all","mayShare":false,"faces":$faces,'
      '"content":$content}';

  String get trip => '["AlbumInfo", {"path": "trip", "title": "Trip", '
      '"subTitle": "", "rights": ${rightsOf(rights)}, "parts": ['
      '${[
        for (var (i, name) in tripNames(extra).indexed) _photo(name, i)
      ].join(", ")}'
      ']}]';

  String? _ref(String path) => refs ? path : null;

  String get best => '["AlbumInfo", {"path": "Best", "kind": "COLLECTION", '
      '"title": "Best", "subTitle": "", "rights": ${rightsOf(rights)}, '
      '"parts": [${_photo("a.jpg", 0, ref: _ref("trip/a.jpg"))}, '
      '${photo("b.jpg", 1, ref: _ref("trip/b.jpg"))}, '
      '${photo("last.jpg", 2, ref: _ref("trip/${tripNames(extra).last}"))}]}]';

  String get _foundParts =>
      '${_photo("trip/a.jpg", 0, ref: _ref("trip/a.jpg"))}, '
      '${photo("trip/b.jpg", 1, ref: _ref("trip/b.jpg"))}, '
      '${photo("trip/${tripNames(extra).last}", 2, ref: _ref("trip/${tripNames(extra).last}"))}';

  String get saved => '["AlbumInfo", {"path": "Saved", "kind": "SEARCH", '
      '"title": "Saved", "subTitle": "", "rights": ${rightsOf(rights)}, '
      '"query": {"version": 1, "root": ["SearchAnd", {"criteria": '
      '[["SearchPerson", {"person": "p1"}]]}]'
      '${savedGroupBy == null ? "" : ', "groupBy": ["$savedGroupBy"]'}}, '
      '"parts": [$_foundParts]}]';

  String get found => '["AlbumInfo", {"kind": "SEARCH", '
      '"rights": ${rightsOf(rights)}, "parts": [$_foundParts]}]';

  String get inbox => '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
      '"subTitle": "", "kind": "INBOX", "rights": ${rightsOf(rights)}, '
      '"parts": [${photo("a.jpg", 0)}, ${photo("b.jpg", 1)}, '
      '${photo("broken.jpg", 2, broken: "Truncated file")}]}]';

  String get _news => '{"count":1,"entries":[{"id":"$newsKey","album":"trip",'
      '"title":"Trip","who":"Anna","first":1000,"last":2000,"unread":true,'
      '"photos":[{"name":"a.jpg","unread":true},'
      '{"name":"b.jpg","unread":true}]}]}';

  static const String _root = '["ListingInfo", {"path": "", "title": "Library",'
      ' "folders": [{"name": "trip", "title": "Trip"}]}]';

  http.Response answer(http.Request request) {
    var query = request.url.queryParameters;
    var path = Uri.decodeFull(request.url.path);
    if (query["type"] == "auth") {
      return role == null ? http.Response("", 404) : http.Response(_auth, 200);
    }
    if (query["type"] == "news-count") {
      return http.Response('{"count":1}', 200);
    }
    if (query["type"] == "news") {
      return http.Response(_news, 200);
    }
    if (query["type"] == "search-options") {
      return json(searchOptions);
    }
    if (request.method == "POST") {
      switch (query["action"]) {
        case "search":
          return json(searchAnswer ?? found);
        case "collect":
          return json('{"outcomes":[]}');
        case "news-read":
          return http.Response(_news, 200);
      }
      return json('{"outcomes":[]}');
    }
    switch (path) {
      case "/valbum/data/":
        return json(_root);
      case "/valbum/data/trip/":
        return json(trip);
      case "/valbum/data/Best/":
        return json(best);
      case "/valbum/data/Saved/":
        return json(saved);
      case "/valbum/data/Inbox/":
        return json(inbox);
    }
    return http.Response("No such resource: $path", 404);
  }

  VAlbumClient client() => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "carol-token",
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          return answer(request);
        }),
      );

  /// The requests posting [action].
  List<http.Request> posts(String action) => [
        for (var request in requests)
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == action)
            request,
      ];
}

http.Response json(String body) => http.Response(body, 200,
    headers: {"content-type": "application/json; charset=utf-8"});

/// Pumps until the app is still, letting the answers of the server arrive.
Future<void> settle(WidgetTester tester) async {
  await withFakeImageHttp(() => tester.pumpAndSettle());
  for (var round = 0; round < 3; round++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 20)),
    );
    await withFakeImageHttp(() => tester.pumpAndSettle());
  }
}

/// Opens the app over [space] on [route].
Future<VAlbumRouterDelegate> openSpace(
    WidgetTester tester, PhotoSpace space, VAlbumRoute route) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: space.client(),
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => "http://server/valbum/data",
        token: "carol-token",
        loaded: true,
      ),
      initialRoute: route,
    ));
  });
  await settle(tester);
  return tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
      as VAlbumRouterDelegate;
}

/// Chooses Anne in the person chooser of the search view.
Future<void> chooseAnne(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("search-add-persons")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choice-p1")));
  await settle(tester);
  await tester.tap(find.byKey(const Key("search-choose-done")));
  await settle(tester);
}

/// The tile of the photograph [name].
Finder tileOf(String name) => find.byKey(ValueKey(name));

/// Clicks the tile of [name] with the secondary mouse button, near its left
/// edge, clear of an edit tile's toolbars.
Future<void> rightClick(WidgetTester tester, String name) async {
  var box = tester.getRect(tileOf(name));
  await tester.tapAt(
    Offset(box.left + 8, box.center.dy),
    buttons: kSecondaryMouseButton,
    kind: PointerDeviceKind.mouse,
  );
  await settle(tester);
}

/// Long-presses the tile of [name], near its left edge.
Future<void> longPressOn(WidgetTester tester, String name) async {
  var box = tester.getRect(tileOf(name));
  await tester.longPressAt(Offset(box.left + 8, box.center.dy));
  await settle(tester);
}

/// Whether a menu is open.
bool menuOpen() =>
    find.byWidgetPredicate((w) => w is PopupMenuItem).evaluate().isNotEmpty;

/// The route the app shows.
VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;
