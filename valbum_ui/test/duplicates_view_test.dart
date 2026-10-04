/// Tests of the overview of the photos lying in more than one album, the app
/// half of issue #220.
///
/// The start page's ⋮ menu opens `/.duplicates/`, which asks the server's
/// `?type=duplicates` and shows one row per photograph with every album it
/// lies in; an entry opens its album at the photograph. Nothing is moved.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/duplicates_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

const List<String> editRights = ["view", "download", "contribute", "edit"];

String authOf(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "${role.isEmpty ? "" : "carol"}", "role": "$role", '
    '"space": "", "clearance": "${role.isEmpty ? "" : "all"}", '
    '"mayShare": false}';

String rightsJson(List<String> rights) =>
    rights.map((right) => '{"name": "$right"}').join(",");

String root(List<String> rights) =>
    '["ListingInfo", {"path": "", "title": "Library", "rights": ['
    '${rightsJson(rights)}], "folders": []}]';

String trip() =>
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", "rights": ['
    '${rightsJson(editRights)}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "IMG_1.jpg", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY"}]]}]';

String copy(String album, String title, String name, {int albumDate = 0}) =>
    '{"album": "$album", "title": "$title", "albumDate": $albumDate, '
    '"name": "$name"}';

String groupJson(String hash, List<String> copies) =>
    '{"hash": "$hash", "date": 1690000000000, "copies": [${copies.join(",")}], '
    '"image": {"kind": "IMAGE", "name": "x.jpg", "width": 4, '
    '"height": 3, "orientation": "IDENTITY"}}';

String list(List<String> groups, {int done = 4, int total = 4}) =>
    '{"groups": [${groups.join(",")}], '
    '"indexed": {"done": $done, "total": $total}}';

/// Two photos: one in three albums, one in two.
String twoGroups() => list([
      groupJson("h1", [
        copy("2023-07-01 Trip", "Trip", "IMG_1.jpg", albumDate: 1688169600000),
        copy("2023-08-01 Best of", "Best of", "beach.jpg"),
        copy("Inbox", "", "IMG_1.jpg"),
      ]),
      groupJson("h2", [
        copy("2023-07-01 Trip", "Trip", "IMG_2.jpg"),
        copy("", "", "IMG_2.jpg"),
      ]),
    ]);

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

VAlbumClient server({
  required List<http.Request> requests,
  String role = "edit",
  List<String> rights = editRights,
  http.Response Function()? duplicates,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: role.isEmpty ? null : "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf(role));
        }
        if (query["type"] == "duplicates") {
          return duplicates?.call() ?? json(twoGroups());
        }
        if (request.url.path.contains("Trip")) {
          return json(trip());
        }
        return json(root(rights));
      }),
    );

Future<void> pump(
  WidgetTester tester,
  VAlbumClient client, {
  VAlbumRoute route = const DuplicatesRoute(),
  OfflineState? offlineState,
  Locale locale = const Locale("en"),
}) async {
  await tester.pumpWidget(localizedApp(
    VAlbumApp(
      client: client,
      offlineState: offlineState,
      initialRoute: route,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
    ),
    locale: locale,
  ));
  await tester.pumpAndSettle();
}

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

bool asked(List<http.Request> requests) => requests
    .any((request) => request.url.queryParameters["type"] == "duplicates");

void main() {
  group('the route', () {
    test('is /.duplicates/ at the root and back', () {
      expect(parseRoute(Uri.parse("/.duplicates/")), const DuplicatesRoute());
      expect(
          parseRoute(Uri.parse("/valbum/.duplicates/"), basePath: "/valbum/"),
          const DuplicatesRoute());
      expect(routeToUri(const DuplicatesRoute(), basePath: "/valbum/").path,
          "/valbum/.duplicates/");
      expect(const DuplicatesRoute().up, ListingOrAlbumRoute.root);
    });

    test('is no route below the root', () {
      expect(parseRoute(Uri.parse("/A/.duplicates/")),
          const ListingOrAlbumRoute(["A", ".duplicates"]));
    });

    test('sits on the start page', () {
      expect(VAlbumRouterDelegate.levelsOf(const DuplicatesRoute()),
          [ListingOrAlbumRoute.root, const DuplicatesRoute()]);
    });
  });

  group('the menu entry', () {
    testWidgets('is offered to a member on the start page', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              role: "view",
              rights: const ["view", "download"]),
          route: ListingOrAlbumRoute.root);

      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("open-duplicates")), findsOneWidget);
      expect(find.text("Photos in several albums"), findsOneWidget);

      await tester.tap(find.byKey(const Key("open-duplicates")));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const DuplicatesRoute());
      expect(asked(requests), isTrue);
      expect(find.text("Photos in several albums (2)"), findsOneWidget);
    });

    testWidgets('is not offered to an anonymous visitor', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests, role: "", rights: const ["view", "download"]),
          route: ListingOrAlbumRoute.root);

      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("open-duplicates")), findsNothing);
    });
  });

  group('the page', () {
    testWidgets('shows every photo with every album it lies in',
        (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
      expect(find.byKey(const ValueKey("duplicates-group-h2")), findsOneWidget);
      expect(find.textContaining("in 3 albums"), findsOneWidget);
      expect(find.textContaining("in 2 albums"), findsOneWidget);
      expect(
          find.byKey(
              const ValueKey("duplicates-copy-2023-08-01 Best of/beach.jpg")),
          findsOneWidget);
      // An album without a title is named by its folder, the root of the
      // space by the start page.
      expect(find.text("Inbox"), findsOneWidget);
      expect(find.text("Start page"), findsOneWidget);
      expect(find.byKey(const Key("duplicates-indexing")), findsNothing);
      expect(requests.where((request) => request.method != "GET"), isEmpty,
          reason: "Nothing is moved or written.");
    });

    testWidgets('opens the album of an entry at its photo', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(find
          .byKey(const ValueKey("duplicates-copy-2023-07-01 Trip/IMG_1.jpg")));
      await tester.pumpAndSettle();

      expect(
          routeOf(tester),
          const ImageRoute(["2023-07-01 Trip"], "IMG_1.jpg",
              fromDuplicates: true));
    });

    testWidgets('opens the first album from the thumbnail', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(find.byKey(const ValueKey("duplicates-thumbnail-h1")));
      await tester.pumpAndSettle();

      expect(
          routeOf(tester),
          const ImageRoute(["2023-07-01 Trip"], "IMG_1.jpg",
              fromDuplicates: true));
    });

    testWidgets('says so when no photo is in two albums', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester, server(requests: requests, duplicates: () => json(list([]))));

      expect(find.text("No photo is in more than one album."), findsOneWidget);
      expect(find.text("Photos in several albums (0)"), findsOneWidget);
    });

    testWidgets('says so while the index is still being built', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              duplicates: () => json(list([
                    groupJson("h2", [
                      copy("A", "A", "a.jpg"),
                      copy("B", "B", "a.jpg"),
                    ])
                  ], done: 3, total: 10))));

      expect(find.byKey(const Key("duplicates-indexing")), findsOneWidget);
      expect(find.textContaining("3 of 10 folders"), findsOneWidget);
      expect(find.byKey(const ValueKey("duplicates-group-h2")), findsOneWidget,
          reason: "What is known is shown.");
    });

    testWidgets('is refused offline with the usual reason', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState()..goneOffline(null);
      await pump(tester, server(requests: requests), offlineState: state);

      expect(find.byKey(const Key("duplicates-failure")), findsOneWidget);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(asked(requests), isFalse);
    });

    testWidgets('says the server\'s own reason for a refusal', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              duplicates: () =>
                  json('["ErrorInfo", {"message": "Members only."}]', 403)));

      expect(find.text("Members only."), findsOneWidget);
      expect(find.byKey(const Key("duplicates-retry")), findsOneWidget);
    });

    testWidgets('goes back to the start page', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(find.byKey(const Key("duplicates-up")));
      await tester.pumpAndSettle();

      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });
  });

  test('the name of a copy', () {
    expect(duplicateAlbumName(DuplicateCopy(album: "A/B", title: "")), "B");
    expect(
        duplicateAlbumName(DuplicateCopy(album: "A/B", title: "Trip")), "Trip");
    expect(duplicateAlbumPath(DuplicateCopy(album: "")), isEmpty);
  });
}
