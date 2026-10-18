/// Tests of the overview of the shots of the same scenery in several albums,
/// the app half of issue #257 (package 6) beside the duplicates of #220.
///
/// The start page's ⋮ menu opens `/.similar/`, which asks the server's
/// `?type=similar` and shows one row of thumbnails per group, each with its
/// album; a thumbnail opens its album's viewer at the photograph, whose way
/// back leads to the overview. Read-only, and never offered to a link.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/similar_view.dart';

import 'share_album_chrome_test.dart' as chrome;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache;

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

String album(String title, List<String> names) =>
    '["AlbumInfo", {"path": "", "title": "$title", "subTitle": "", '
    '"rights": [${rightsJson(editRights)}], "parts": [${[
      for (var (index, name) in names.indexed)
        '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": $index, '
            '"width": 2000, "height": 1000, "orientation": "IDENTITY"}]'
    ].join(",")}]}]';

String photo(String album, String title, String name) =>
    '{"album": "$album", "title": "$title", "albumDate": 0, "name": "$name", '
    '"image": {"kind": "IMAGE", "name": "$name", "width": 4, "height": 3, '
    '"orientation": "IDENTITY"}}';

String list(List<List<String>> groups,
        {int done = 6, int total = 6, bool content = true}) =>
    '{"groups": [${[
      for (var group in groups)
        '{"date": 1690000000000, "photos": [${group.join(",")}]}'
    ].join(",")}], "described": {"done": $done, "total": $total}, '
    '"content": $content}';

/// Two groups: the hill in three albums, the lake in two.
String twoGroups() => list([
      [
        photo("2023-07-01 Trip", "Trip", "hill.jpg"),
        photo("2024-05-01 Again", "Again", "IMG_7.jpg"),
        photo("Inbox", "", "hill2.jpg"),
      ],
      [
        photo("2023-07-01 Trip", "Trip", "lake.jpg"),
        photo("", "", "lake.jpg"),
      ],
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
  http.Response Function()? similar,
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
        if (query["type"] == "similar") {
          return similar?.call() ?? json(twoGroups());
        }
        var path = Uri.decodeFull(request.url.path);
        if (path.contains("Trip/") && query["type"] == "json") {
          return json(album("Trip", ["hill.jpg", "lake.jpg", "z.jpg"]));
        }
        if (path.contains("Trip/")) {
          return http.Response.bytes(transparentPixelPng, 200,
              headers: const {"content-type": "image/png"});
        }
        return json(root(rights));
      }),
    );

Future<void> pump(
  WidgetTester tester,
  VAlbumClient client, {
  VAlbumRoute route = const SimilarRoute(),
  OfflineState? offlineState,
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
    locale: const Locale("en"),
  ));
  await tester.pumpAndSettle();
}

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

bool asked(List<http.Request> requests) =>
    requests.any((request) => request.url.queryParameters["type"] == "similar");

void main() {
  setUp(withEmptyImageCache);

  group('the route', () {
    test('is /.similar/ at the root and back', () {
      expect(parseRoute(Uri.parse("/.similar/")), const SimilarRoute());
      expect(parseRoute(Uri.parse("/valbum/.similar/"), basePath: "/valbum/"),
          const SimilarRoute());
      expect(routeToUri(const SimilarRoute(), basePath: "/valbum/").path,
          "/valbum/.similar/");
      expect(const SimilarRoute().up, ListingOrAlbumRoute.root);
      expect(VAlbumRouterDelegate.levelsOf(const SimilarRoute()),
          [ListingOrAlbumRoute.root, const SimilarRoute()]);
    });

    test('a viewer opened from it leads back there', () {
      var route =
          const ImageRoute(["2020", "A b"], "c d.jpg", fromSimilar: true);
      expect(route.path, "/.similar/2020/A%20b/c%20d.jpg");
      var parsed = parseRoute(Uri.parse(route.path)) as ImageRoute;
      expect(parsed, route);
      expect(parsed.up, const SimilarRoute());
      expect(parsed.withName("e.jpg").up, const SimilarRoute(),
          reason: "Paging keeps the way back.");
      expect(VAlbumRouterDelegate.levelsOf(route),
          [ListingOrAlbumRoute.root, const SimilarRoute(), route]);
      for (var other in const <VAlbumRoute>[
        AlternativesRoute(["A"], "x.jpg", fromSimilar: true),
        MemberRoute(["A"], "x.jpg", "y.jpg", fromSimilar: true),
      ]) {
        expect(parseRoute(Uri.parse(other.path)), other);
        expect(other.up, const SimilarRoute());
      }
      expect(const ImageRoute(["A"], "x.jpg").up,
          const ListingOrAlbumRoute(["A"]));
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
      expect(find.byKey(const Key("open-similar")), findsOneWidget);
      expect(find.text(testL10n.similarMenuEntry), findsOneWidget);

      await tester.tap(find.byKey(const Key("open-similar")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const SimilarRoute());
      expect(asked(requests), isTrue);
      expect(find.text(testL10n.similarPageTitle(2)), findsOneWidget);
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
      expect(find.byKey(const Key("open-similar")), findsNothing);
    });

    testWidgets('is not offered to a share link', (tester) async {
      var asks = <http.Request>[];
      await chrome.pumpSession(tester, (request) {
        asks.add(request);
        return chrome.folderLink()(request);
      });
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("open-similar")), findsNothing);
      expect(find.byKey(const Key("open-duplicates")), findsNothing);
      expect(asked(asks), isFalse);
    });
  });

  group('the page', () {
    testWidgets('shows every group as a row with its albums', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      expect(find.byKey(const ValueKey("similar-group-0")), findsOneWidget);
      expect(find.byKey(const ValueKey("similar-group-1")), findsOneWidget);
      expect(find.textContaining(testL10n.similarPhotos(3)), findsOneWidget);
      expect(find.textContaining(testL10n.similarPhotos(2)), findsOneWidget);
      expect(
          find.byKey(
              const ValueKey("similar-photo-2024-05-01 Again/IMG_7.jpg")),
          findsOneWidget);
      // An album without a title is named by its folder, the root of the
      // space by the start page.
      expect(find.text("Inbox"), findsOneWidget);
      expect(find.text(testL10n.duplicatesSpaceRoot), findsOneWidget);
      expect(find.byKey(const Key("similar-describing")), findsNothing);
      expect(requests.where((request) => request.method != "GET"), isEmpty,
          reason: "Read-only.");
      // Nothing to act on but the way into the viewer.
      expect(find.byIcon(Icons.delete_outline), findsNothing);
    });

    testWidgets('opens the viewer, whose way up returns here', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: requests));
        await tester.tap(find
            .byKey(const ValueKey("similar-photo-2023-07-01 Trip/lake.jpg")));
        await tester.pumpAndSettle();
        expect(
            routeOf(tester),
            const ImageRoute(["2023-07-01 Trip"], "lake.jpg",
                fromSimilar: true));
        var asks =
            requests.where((r) => r.url.queryParameters["type"] == "similar");
        var before = asks.length;

        await tester.tap(find.byTooltip(testL10n.similarBack));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const SimilarRoute());
        expect(find.byKey(const ValueKey("similar-group-1")), findsOneWidget);
        expect(
            requests
                .where((r) => r.url.queryParameters["type"] == "similar")
                .length,
            before,
            reason: "The overview stayed beneath the viewer, as it was left.");
      });
    });

    testWidgets('says so while the photos are being described', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              similar: () => json(list([
                    [photo("A", "A", "a.jpg"), photo("B", "B", "b.jpg")]
                  ], done: 3, total: 10))));
      expect(find.byKey(const Key("similar-describing")), findsOneWidget);
      expect(find.text(testL10n.similarDescribing(10, 3)), findsOneWidget);
      expect(find.byKey(const ValueKey("similar-group-0")), findsOneWidget);
    });

    testWidgets('says so where no photos look alike', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester, server(requests: requests, similar: () => json(list([]))));
      expect(find.text(testL10n.similarEmpty), findsOneWidget);
    });

    testWidgets('says why it is empty with the AI search off', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              similar: () => json(list([], content: false))));
      expect(find.text(testL10n.similarOff), findsOneWidget);
    });

    testWidgets('says the server\'s own reason for a refusal', (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              similar: () =>
                  json('["ErrorInfo", {"message": "Members only."}]', 403)));
      expect(find.text("Members only."), findsOneWidget);
      expect(find.byKey(const Key("similar-retry")), findsOneWidget);
    });

    testWidgets('is refused offline with the usual reason', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState()..goneOffline(null);
      await pump(tester, server(requests: requests), offlineState: state);
      expect(find.byKey(const Key("similar-failure")), findsOneWidget);
      expect(asked(requests), isFalse);
    });

    testWidgets('goes back to the start page', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));
      await tester.tap(find.byKey(const Key("similar-up")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });
  });

  test('the path of a photo', () {
    expect(similarAlbumPath(SimilarPhoto(album: "A/B")), ["A", "B"]);
    expect(similarAlbumPath(SimilarPhoto(album: "")), isEmpty);
    expect(similarFilePath(SimilarPhoto(album: "", name: "x.jpg")), "x.jpg");
  });
}
