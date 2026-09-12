/// Review probe for the app half of issue #49: the rights the app honours
/// composed with what it already had — the placement rule and the create
/// flow of issue #48 inside somebody else's space, the tile menu of issue
/// #47, and the one-question-per-folder rule of the share entry.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:intl/intl.dart';
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

String rightsField(List<String> names) =>
    '"rights": [${names.map((name) => '{"name": "$name"}').join(", ")}], ';

/// Alice's year folder, filing by year, with one album in it.
String yearListing(List<String> rights) => '["ListingInfo", {'
    '"title": "2024", "placement": "BY_YEAR", ${rightsField(rights)}'
    '"folders": [{"name": "2024-05-01 Zoo", "title": "Zoo", '
    '"effectiveDate": 1714514400000}]}]';

/// A signed-in client whose requests are recorded.
VAlbumClient signedIn(
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

List<String> typed(List<http.Request> requests, String type) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == type)
          pathOf(request),
    ];

/// The settings of bob's device: the app takes the token from here.
ServerSettings bobsDevice() => ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "bob-token",
      userName: "bob",
      loaded: true,
    );

Future<void> pumpAt(
  WidgetTester tester,
  VAlbumClient client,
  List<String> path,
) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: bobsDevice(),
      initialRoute: ListingOrAlbumRoute(path),
    ));
    await tester.pumpAndSettle();
  });
}

Future<void> openMenu(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets(
      'a viewer of somebody else\'s year folder gets no rule, no creation, '
      'no tile menu, and the server is not asked about grants',
      (tester) async {
    var requests = <http.Request>[];
    var client = signedIn(
      (request) => json(yearListing(const ["view", "download"])),
      requests,
    );
    await pumpAt(tester, client, const ["~alice", "2024"]);

    await openMenu(tester);
    expect(find.text("Shared by alice — you may look and download"),
        findsOneWidget);
    // The folder carries a rule, but applying it is the owner's business.
    expect(find.text("Apply rule"), findsNothing);
    expect(find.text("Folder properties"), findsNothing);
    expect(find.text("Create album"), findsNothing);
    expect(find.text("Share with…"), findsNothing);
    expect(find.text("Reload"), findsOneWidget);
    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(5, 300)); // close the menu
      await tester.pumpAndSettle();
    });

    // The tile has nothing to offer a viewer, so it offers nothing.
    await withFakeImageHttp(() async {
      await tester.longPress(find.text("Zoo"));
      await tester.pumpAndSettle();
    });
    expect(find.text("Move to…"), findsNothing);
    expect(find.text("Share with…"), findsNothing);

    expect(typed(requests, "grants"), isEmpty,
        reason: "the rights already say the answer would be no");
  });

  testWidgets(
      'an editor of somebody else\'s folder creates an album there and '
      'follows the canonical path the rule filed it to', (tester) async {
    var today = DateTime.now();
    var stamp = DateFormat("yyyy-MM-dd").format(today);
    var landed = "~alice/2024/${today.year}/$stamp Trip";
    var requests = <http.Request>[];
    var client = signedIn((request) {
      if (request.method == "PUT") {
        return json('{"path":"$landed","message":"filed in ${today.year}/"}');
      }
      if (pathOf(request) == "/valbum/data/$landed/") {
        return json(fixture("album-target.json"));
      }
      return json(yearListing(const ["view", "download", "contribute", "edit"]));
    }, requests);
    await pumpAt(tester, client, const ["~alice", "2024"]);

    await openMenu(tester);
    expect(find.text("Shared by alice — you may change it"), findsOneWidget);
    expect(find.text("Apply rule"), findsOneWidget);
    await withFakeImageHttp(() async {
      await tester.tap(find.text("Create album"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Datum"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("OK"));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, "Trip");
      await tester.tap(find.text("Anlegen"));
      await tester.pumpAndSettle();
    });

    var put = requests.singleWhere((request) => request.method == "PUT");
    expect(pathOf(put), "/valbum/data/~alice/2024/$stamp Trip/",
        reason: "asked for in the coordinates of the folder shown");
    expect(typed(requests, "json"), contains("/valbum/data/$landed/"));
    expect(find.text("2020 Trip"), findsOneWidget);
    expect(find.text("filed in ${today.year}/"), findsOneWidget);
    expect(typed(requests, "grants"), isEmpty,
        reason: "sharing somebody else's folder is theirs to do");
  });

  testWidgets(
      'the share entry follows the server\'s answer per folder: offered on '
      'the root, not on a child the server refuses, asked once each',
      (tester) async {
    var requests = <http.Request>[];
    var client = signedIn((request) {
      if (request.url.queryParameters["type"] == "grants") {
        return pathOf(request) == "/valbum/data/"
            ? json('{"grants": []}')
            : json('["ErrorInfo",{"message":"Not yours to share."}]',
                status: 403);
      }
      return json('["ListingInfo", {"title": "Mine", '
          '${rightsField(const ["view", "download", "contribute", "edit"])}'
          '"folders": [{"name": "Zoo", "title": "Zoo"}]}]');
    }, requests);
    await pumpAt(tester, client, const []);

    await openMenu(tester);
    expect(find.text("Share with…"), findsOneWidget);
    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(5, 300));
      await tester.pumpAndSettle();
    });
    await openMenu(tester);
    expect(find.text("Share with…"), findsOneWidget);
    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(5, 300));
      await tester.pumpAndSettle();
    });

    await withFakeImageHttp(() async {
      await tester.longPress(find.text("Zoo"));
      await tester.pumpAndSettle();
    });
    expect(find.text("Move to…"), findsOneWidget);
    expect(find.text("Share with…"), findsNothing);
    // No error is shown for a "no": it is an answer, not a failure.
    expect(find.text("Not yours to share."), findsNothing);

    var asked = typed(requests, "grants");
    expect(asked.where((p) => p == "/valbum/data/").length, 1,
        reason: "asked once per folder, remembered afterwards");
    expect(asked.where((p) => p == "/valbum/data/Zoo/").length, 1);
  });
}
