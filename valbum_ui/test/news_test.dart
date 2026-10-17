/// The bell of issue #268: what arrived from others, read per entry.
///
/// A member's app shows the bell with the number of unread entries; opening
/// its menu marks nothing; visiting an entry marks it and shows its photos
/// alone, which the viewer pages through; back leads to the album, scrolled
/// to them, with them marked as new for that visit. "Mark all as read" marks
/// every entry. A share link, a contact or an anonymous caller has no bell.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/news.dart';

import 'album_return_reveal_test.dart'
    show albumPosition, expectRevealed, longAlbum, screen;
import 'album_return_test.dart' show routeOf, settle, shownImage;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String _auth = '{"mode":"writes","deviceName":"Desk",'
    '"writeAllowed":true,"userName":"carol","role":"edit","space":"",'
    '"clearance":"all","mayShare":false}';

/// The key of the one entry the server knows.
const String _key = "a1b2c3d4e5f60718";

/// The entry: Anna added `img40.jpg` … `img42.jpg` to the album `trip`,
/// unread unless [read], and `img39.jpg` before, read.
String _entry({bool read = false}) => '{"id":"$_key","album":"trip",'
    '"title":"Trip","who":"Anna","first":1000,"last":2000,'
    '"unread":${!read},"photos":['
    '{"name":"img39.jpg","unread":false},'
    '{"name":"img40.jpg","unread":${!read}},'
    '{"name":"img41.jpg","unread":${!read}},'
    '{"name":"img42.jpg","unread":${!read}}]}';

/// A member's server: the album everywhere, the bell of one entry, and every
/// request recorded.
class _Server {
  final List<http.Request> requests = [];

  bool read = false;

  bool known = true;

  String get news => '{"count":${read || !known ? 0 : 1},'
      '"entries":[${known ? _entry(read: read) : ""}]}';

  VAlbumClient client({String? token = "carol-token"}) => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: token,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(
              transparentPixelPng,
              200,
              headers: {"content-type": "image/png"},
            );
          }
          var query = request.url.queryParameters;
          if (query["type"] == "auth") {
            return http.Response(_auth, 200);
          }
          if (query["type"] == "news-count") {
            return http.Response('{"count":${read || !known ? 0 : 1}}', 200);
          }
          if (query["type"] == "news") {
            return http.Response(news, 200);
          }
          if (query["action"] == "news-read") {
            read = true;
            return http.Response(news, 200);
          }
          return http.Response(
            longAlbum,
            200,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }),
      );

  /// The bodies of the requests marking something as read.
  List<Map<String, dynamic>> get marks => [
        for (var request in requests)
          if (request.url.queryParameters["action"] == "news-read")
            jsonDecode(request.body) as Map<String, dynamic>
      ];
}

Future<void> _open(WidgetTester tester, _Server server, VAlbumRoute route,
        {String? token = "carol-token"}) =>
    settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(
          client: server.client(token: token),
          // The device's own sign-in: the token the app asks who it is with.
          settings: ServerSettings(
            store: InMemorySettingsStore(),
            platformDefault: () => "http://server/valbum/data",
            token: token,
            loaded: true,
          ),
          initialRoute: route,
        ),
      ),
    );

/// Opens the menu of the bell.
Future<void> _openMenu(WidgetTester tester) =>
    settle(tester, () => tester.tap(find.byKey(newsBellKey)));

/// The system's (or the browser's) back button.
Future<void> _back(WidgetTester tester) =>
    settle(tester, () => tester.binding.handlePopRoute());

void main() {
  testWidgets('a member sees the bell with the number of unread entries',
      (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const ListingOrAlbumRoute(["other"]));

    expect(find.byKey(newsBellKey), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const Key("news-badge")),
        matching: find.text("1"),
      ),
      findsOneWidget,
    );
    expect(
      server.requests
          .where((r) => r.url.queryParameters["type"] == "news-count"),
      isNotEmpty,
      reason: "the count is asked on start",
    );
  });

  testWidgets('without a member there is no bell', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const ListingOrAlbumRoute(["other"]),
        token: null);

    expect(find.byKey(newsBellKey), findsNothing);
    expect(
      server.requests.where(
          (r) => (r.url.queryParameters["type"] ?? "").startsWith("news")),
      isEmpty,
    );
  });

  testWidgets('opening the menu marks nothing', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const ListingOrAlbumRoute(["other"]));

    await _openMenu(tester);

    expect(find.byKey(newsEntryKey(_key)), findsOneWidget);
    expect(find.byKey(newsUnreadKey(_key)), findsOneWidget);
    expect(find.textContaining("Anna"), findsOneWidget);
    expect(server.marks, isEmpty);
  });

  group('"Close" leaves the menu on a phone (issue #283)', () {
    for (var known in [false, true]) {
      testWidgets(known ? 'with an entry' : 'with nothing new', (tester) async {
        screen(tester, const Size(360, 640));
        var server = _Server()..known = known;
        await _open(tester, server, const ListingOrAlbumRoute(["other"]));
        await _openMenu(tester);
        expect(find.byKey(const Key("news-nothing")),
            known ? findsNothing : findsOneWidget);
        expect(find.byKey(newsEntryKey(_key)),
            known ? findsOneWidget : findsNothing);

        await settle(tester, () => tester.tap(find.byKey(newsCloseKey)));

        expect(find.byKey(const Key("news-menu")), findsNothing);
        expect(server.marks, isEmpty, reason: "closing marks nothing");
      });
    }
  });

  testWidgets('"Mark all as read" marks every entry', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const ListingOrAlbumRoute(["other"]));
    await _openMenu(tester);

    await settle(tester, () => tester.tap(find.byKey(newsMarkAllKey)));

    expect(server.marks, [
      {"keys": [], "all": true}
    ]);
    expect(find.byKey(newsUnreadKey(_key)), findsNothing);
    expect(find.byKey(newsEntryKey(_key)), findsOneWidget,
        reason: "a read entry stays in the list");
    await settle(
        tester,
        () async =>
            Navigator.of(tester.element(find.byKey(newsEntryKey(_key)))).pop());
    expect(
      find.descendant(
        of: find.byKey(const Key("news-badge")),
        matching: find.text("1"),
      ),
      findsNothing,
    );
  });

  testWidgets(
      'an entry shows its unread photos alone, the viewer pages through '
      'them, and back lands at them in the album', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const ListingOrAlbumRoute(["other"]));
    await _openMenu(tester);

    await settle(tester, () => tester.tap(find.byKey(newsEntryKey(_key))));

    expect(
        server.marks,
        [
          {
            "keys": [
              {"id": _key}
            ],
            "all": false
          }
        ],
        reason: "visiting marks the one entry");
    expect(routeOf(tester), const NewsRoute(["trip"], _key));
    for (var name in ["img40.jpg", "img41.jpg", "img42.jpg"]) {
      expect(find.byKey(ValueKey(name)), findsOneWidget, reason: name);
    }
    expect(find.byKey(const ValueKey("img39.jpg")), findsNothing,
        reason: "a photo of the entry already read is not shown again");
    expect(find.byKey(const ValueKey("img0.jpg")), findsNothing);

    await settle(
        tester, () => tester.tap(find.byKey(const ValueKey("img40.jpg"))));
    expect(
        routeOf(tester), const ImageRoute(["trip"], "img40.jpg", news: _key));
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(shownImage(tester).thumbnailName, "img41.jpg");
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(shownImage(tester).thumbnailName, "img42.jpg");
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(shownImage(tester).thumbnailName, "img42.jpg",
        reason: "the viewer pages through the entry alone");
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft));
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft));
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft));
    expect(shownImage(tester).thumbnailName, "img40.jpg");
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));

    await _back(tester);
    expect(routeOf(tester), const NewsRoute(["trip"], _key));
    await _back(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));
    expect(albumPosition(tester).pixels, greaterThan(0));
    expectRevealed(tester, "img41.jpg");
    expect(find.byKey(newsFreshMarkerKey), findsNWidgets(3),
        reason: "the photos of the entry are new for this visit");

    await _back(tester);
    await settle(
      tester,
      () async =>
          VAlbumNavigator.of(tester.element(find.byType(VAlbumView).last))
              .go(const ListingOrAlbumRoute(["trip"])),
    );
    expect(find.byKey(newsFreshMarkerKey), findsNothing,
        reason: "new for that visit only");
  });

  testWidgets('back from the entry itself lands at its first photo',
      (tester) async {
    screen(tester, const Size(390, 844));
    var server = _Server();
    // The album was on the screen before the entry was opened on it.
    await _open(tester, server, const ListingOrAlbumRoute(["trip"]));
    expect(albumPosition(tester).pixels, 0);
    await _openMenu(tester);
    await settle(tester, () => tester.tap(find.byKey(newsEntryKey(_key))));

    await _back(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));
    expectRevealed(tester, "img40.jpg");
  });

  testWidgets('an entry opened by its address asks the server for it',
      (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server();
    await _open(tester, server, const NewsRoute(["trip"], _key));

    expect(find.byKey(const ValueKey("img40.jpg")), findsOneWidget);
    expect(find.byKey(const ValueKey("img42.jpg")), findsOneWidget);
    expect(find.byKey(const ValueKey("img0.jpg")), findsNothing);
  });

  testWidgets('an entry the server no longer knows says so', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server()..known = false;
    await _open(tester, server, const NewsRoute(["trip"], _key));

    expect(find.textContaining("no longer there"), findsOneWidget);
  });

  testWidgets('a read entry shows all its photos', (tester) async {
    screen(tester, const Size(800, 600));
    var server = _Server()..read = true;
    await _open(tester, server, const NewsRoute(["trip"], _key));

    for (var name in ["img39.jpg", "img40.jpg", "img41.jpg", "img42.jpg"]) {
      expect(find.byKey(ValueKey(name)), findsOneWidget, reason: name);
    }
  });

  group('the location of an entry', () {
    test('an entry is a level of its album', () {
      const route = NewsRoute(["2026", "Trip"], _key);
      expect(route.path, "/2026/Trip/.news/$_key/");
      expect(parseRoute(Uri.parse(route.path)), route);
      expect(route.up, const ListingOrAlbumRoute(["2026", "Trip"]));
    });

    test('its viewer pages through it and returns to it', () {
      const route = ImageRoute(["2026", "Trip"], "IMG_1.jpg", news: _key);
      expect(route.path, "/2026/Trip/.news/$_key/IMG_1.jpg");
      expect(parseRoute(Uri.parse(route.path)), route);
      expect(route.up, const NewsRoute(["2026", "Trip"], _key));
      expect(route.withName("IMG_2.jpg").news, _key);
      expect(
        VAlbumRouterDelegate.levelsOf(route),
        [
          const ListingOrAlbumRoute(["2026", "Trip"]),
          const NewsRoute(["2026", "Trip"], _key),
          route,
        ],
      );
    });
  });

  test('the age of an entry in words', () {
    var now = DateTime.fromMillisecondsSinceEpoch(100 * 24 * 3600 * 1000);
    String age(Duration ago) =>
        newsAge(l10nOf(), now, now.subtract(ago).millisecondsSinceEpoch);
    expect(age(const Duration(seconds: 10)), "just now");
    expect(age(const Duration(minutes: 5)), "5 min. ago");
    expect(age(const Duration(hours: 3)), "3 h ago");
    expect(age(const Duration(days: 1, hours: 2)), "a day ago");
    expect(age(const Duration(days: 4)), "4 days ago");
  });
}
