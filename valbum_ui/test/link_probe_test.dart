/// Review probe for the app half of issue #50: link entries composed with
/// what the app already had — the image route of issue #24 behind a canonical
/// URL, the listing order of issue #48 with a link among real folders, and the
/// share gating of issue #49 on a link tile.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

List<String> jsonGets(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          pathOf(request),
    ];

int millis(int year, int month, int day) =>
    DateTime(year, month, day).millisecondsSinceEpoch;

const String allRights = '"rights": [{"name": "view"}, {"name": "download"}, '
    '{"name": "contribute"}, {"name": "edit"}], ';

/// Alice's zoo album as bob's grant shows it: view and download.
const String zoo = '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"rights": [{"name": "view"}, {"name": "download"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// Bob's root: a link to alice's year folder among his own folders, sent by
/// the server in no particular order.
String bobsRoot() => '["ListingInfo", {"path": "", "title": "My albums", '
    '$allRights'
    '"folders": ['
    '{"name": "Old", "title": "Old", "effectiveDate": ${millis(2020, 1, 1)}}, '
    '{"name": "Alices year", "title": "2024", "link": "~alice/2024", '
    '"effectiveDate": ${millis(2024, 1, 1)}}, '
    '{"name": "Notes", "title": "Notes", "effectiveDate": 0}'
    ']}]';

VAlbumClient bob(
  http.Response Function(http.Request request) handler,
  List<http.Request> requests,
) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "bob-token",
      userName: "bob",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

ServerSettings bobsDevice() => ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "bob-token",
      userName: "bob",
      loaded: true,
    );

Future<void> pumpApp(
  WidgetTester tester,
  VAlbumClient client,
  VAlbumRoute route,
) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: bobsDevice(),
      initialRoute: route,
    ));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets(
      'a canonical URL of a photo opens the viewer on the photo below the '
      'viewer\'s own link', (tester) async {
    var requests = <http.Request>[];
    var client = bob((request) => switch (pathOf(request)) {
          "/valbum/data/" => json(bobsRoot()),
          "/valbum/data/Alices year/2024-05-01 Zoo/" => json(zoo),
          _ => json('["ErrorInfo",{"message":"No such resource."}]',
              status: 404),
        }, requests);

    await pumpApp(
      tester,
      client,
      const ImageRoute(["~alice", "2024", "2024-05-01 Zoo"], "a.jpg"),
    );

    expect(jsonGets(requests),
        contains("/valbum/data/Alices year/2024-05-01 Zoo/"));
    expect(jsonGets(requests),
        isNot(contains("/valbum/data/~alice/2024/2024-05-01 Zoo/")));
    // The viewer is on the photo, below the viewer's own path.
    expect(find.byType(ImageView), findsOneWidget);
  });

  testWidgets('a link is ordered among the real folders by its target\'s date',
      (tester) async {
    var requests = <http.Request>[];
    var client = bob((request) => json(bobsRoot()), requests);

    await pumpApp(tester, client, const ListingOrAlbumRoute([]));

    double left(String title) => tester.getTopLeft(find.text(title)).dx;
    expect(left("2024"), lessThan(left("Old")));
    expect(left("Old"), lessThan(left("Notes")));
    expect(find.byKey(const Key("link-badge-Alices year")), findsOneWidget);
    expect(find.text("from alice"), findsOneWidget);
    expect(find.byKey(const Key("link-badge-Old")), findsNothing);
  });

  testWidgets(
      'the menu of a link tile offers moving and removing, but not sharing '
      'what is not one\'s own', (tester) async {
    var requests = <http.Request>[];
    var client = bob((request) {
      if (request.url.queryParameters["type"] == "grants") {
        return pathOf(request) == "/valbum/data/"
            ? json('{"grants": []}')
            : json('["ErrorInfo",{"message":"Not yours to share."}]',
                status: 403);
      }
      return json(bobsRoot());
    }, requests);

    await pumpApp(tester, client, const ListingOrAlbumRoute([]));

    await withFakeImageHttp(() async {
      await tester.longPress(find.text("2024"));
      await tester.pumpAndSettle();
    });

    expect(find.text("Move to…"), findsOneWidget);
    expect(find.text("Remove from my albums"), findsOneWidget);
    expect(find.text("Share with…"), findsNothing);
    expect(find.text("Not yours to share."), findsNothing,
        reason: "a 'no' from the server is an answer, not an error");
  });
}
