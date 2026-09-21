/// Review probe for the app half of issue #48: the album date and the folder
/// placement rule composed with what the app already had — a listing nested
/// two folders deep, a month rule, folder names with spaces on a
/// percent-encoded wire, the year folder as a plain tile, and the outcome
/// dialog the move of issue #47 brought.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_date.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The paths the app asked the server for.
List<String> jsonGets(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          pathOf(request),
    ];

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

int millis(int year, int month, int day) =>
    DateTime(year, month, day).millisecondsSinceEpoch;

/// The listing at `Family/Trips`, filing by year and month, served in an
/// order no current server would send: the undated first, the year folder
/// before the album that belongs into it.
String tripsListing({String placement = "BY_YEAR_MONTH"}) => '''
["ListingInfo", {
  "path": "Trips",
  "title": "Trips",
  "placement": "$placement",
  "folders": [
    {"name": "Zoo", "title": "Zoo", "effectiveDate": 0},
    {"name": "2025", "title": "2025", "effectiveDate": ${millis(2025, 1, 1)}},
    {"name": "2025-03-01 Hike", "title": "Hike",
     "effectiveDate": ${millis(2025, 3, 1)}},
    {"name": "alpha", "title": "alpha", "effectiveDate": 0}
  ]
}]''';

const trips = "/valbum/data/Family/Trips/";

Future<void> pumpTrips(WidgetTester tester, VAlbumClient client) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      initialRoute: const ListingOrAlbumRoute(["Family", "Trips"]),
    ));
    await tester.pumpAndSettle();
  });
}

Future<void> openMenu(WidgetTester tester, String entry) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    await tester.tap(find.text(entry));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets(
      'a listing two folders deep is read newest first, the year folder a '
      'plain tile among the albums', (tester) async {
    var client = clientHandling((request) => json(tripsListing()));
    await pumpTrips(tester, client);

    double top(String title) => tester.getTopLeft(find.text(title)).dy;
    double left(String title) => tester.getTopLeft(find.text(title)).dx;
    // One row of four tiles at the 800px test width.
    expect({top("Hike"), top("2025"), top("alpha"), top("Zoo")}.length, 1);
    expect(left("Hike"), lessThan(left("2025")));
    expect(left("2025"), lessThan(left("alpha")));
    expect(left("alpha"), lessThan(left("Zoo")));

    // The year folder has no chrome of its own: the same folder icon as an
    // album without an index picture.
    expect(find.byIcon(Icons.folder), findsNWidgets(4));
  });

  testWidgets(
      'an album created in a nested folder is asked for below that folder, '
      'carries the picked day, and the app follows it into the month folder',
      (tester) async {
    var today = DateTime.now();
    var day = DateTime(today.year, today.month, today.day);
    var stamp = DateFormat("yyyy-MM-dd").format(day);
    var month = DateFormat("yyyy-MM").format(day);
    var landed = "Family/Trips/${day.year}/$month/$stamp Zoo Day";

    var requests = <http.Request>[];
    var client = clientHandling((request) {
      if (request.method == "PUT") {
        return json(
            '{"path":"$landed","message":"filed in ${day.year}/$month/"}');
      }
      if (pathOf(request) == "/valbum/data/$landed/") {
        return json(fixture("album-target.json"));
      }
      return json(tripsListing());
    }, requests: requests);
    await pumpTrips(tester, client);

    await openMenu(tester, "Create album");
    await withFakeImageHttp(() async {
      await tester.tap(find.text(testL10n.dateLabel));
      await tester.pumpAndSettle();
      await tester.tap(find.text("OK"));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, "Zoo Day");
      await tester.tap(find.text(testL10n.create));
      await tester.pumpAndSettle();
    });

    var put = requests.singleWhere((request) => request.method == "PUT");
    // Asked for below the folder being shown, not below the root.
    expect(pathOf(put), "$trips$stamp Zoo Day/");
    var sent = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    expect(sent.date, day.millisecondsSinceEpoch,
        reason: "local midnight of the picked day");
    expect(sent.effectiveDate, 0, reason: "the app never sets a derived date");

    // The app went to the space-relative path the server answered, through
    // two folders it never saw, and shows the album that is there.
    expect(jsonGets(requests), contains("/valbum/data/$landed/"));
    expect(find.text("2020 Trip"), findsOneWidget);
    expect(find.text("filed in ${day.year}/$month/"), findsOneWidget);
  });

  testWidgets(
      'the folder properties of a nested folder store the new rule and the '
      'folders it holds', (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling((request) {
      if (request.method == "PUT") {
        return json("");
      }
      return json(tripsListing(placement: "NONE"));
    }, requests: requests);
    await pumpTrips(tester, client);

    // No rule, nothing to apply.
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
    });
    expect(find.text("Apply rule"), findsNothing);
    await withFakeImageHttp(() async {
      await tester.tap(find.text("Folder properties"));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("placement-byYearMonth")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
    });

    var put = requests.singleWhere((request) => request.method == "PUT");
    expect(pathOf(put), trips);
    var stored = Resource.read(JsonReader.fromString(put.body)) as ListingInfo;
    expect(stored.placement, Placement.byYearMonth);
    expect(stored.title, "Trips");
    expect([for (var f in stored.folders) f.name],
        ["Zoo", "2025", "2025-03-01 Hike", "alpha"]);
    // The listing was fetched again after the write.
    expect(jsonGets(requests).where((p) => p == trips).length, 2);
  });

  testWidgets(
      'applying the rule of a nested folder posts there, refreshes the '
      'listing and reads the refusals out in the move\'s own dialog',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling((request) {
      if (request.method == "POST") {
        expect(request.url.queryParameters["action"], "place");
        return json('{"outcomes":['
            '{"name":"2025-03-01 Hike","newName":"2025/2025-03/2025-03-01 Hike",'
            '"message":""},'
            '{"name":"Zoo","newName":"","message":"Nothing says when it happened."}'
            ']}');
      }
      return json(tripsListing());
    }, requests: requests);
    await pumpTrips(tester, client);

    await openMenu(tester, "Apply rule");

    var post = requests.singleWhere((request) => request.method == "POST");
    expect(pathOf(post), trips);
    expect(jsonGets(requests).where((p) => p == trips).length, 2);

    expect(find.byKey(const Key("move-outcome")), findsOneWidget);
    expect(find.text("Apply rule"), findsOneWidget);
    expect(find.text("Filed 1 album."), findsOneWidget);
    expect(find.text("'Zoo': Nothing says when it happened."), findsOneWidget);
    expect(
        find.descendant(
          of: find.byKey(const Key("move-outcome")),
          matching: find.textContaining("Hike"),
        ),
        findsNothing,
        reason: "what was filed is not listed as a refusal");
  });

  testWidgets(
      'an anonymous caller applying the rule is told so in the server\'s '
      'words and nothing is re-fetched', (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling((request) {
      if (request.method == "POST") {
        return json('["ErrorInfo",{"message":"Pair this device first."}]',
            status: 401);
      }
      return json(tripsListing());
    }, requests: requests);
    await pumpTrips(tester, client);

    await openMenu(tester, "Apply rule");

    expect(find.text("Pair this device first."), findsOneWidget);
    expect(find.byKey(const Key("move-outcome")), findsNothing);
    expect(jsonGets(requests), [trips]);
  });

  group('describeDateSource', () {
    test('recognises a year-only and a month-only folder name', () {
      var year = AlbumInfo(effectiveDate: millis(2025, 1, 1));
      expect(
          describeDateSource(year, folderName: "2025"), DateSource.folderName);
      expect(describeDateSource(year, folderName: "2025 Trips"),
          DateSource.folderName);
      var month = AlbumInfo(effectiveDate: millis(2025, 3, 1));
      expect(describeDateSource(month, folderName: "2025-03"),
          DateSource.folderName);
      expect(describeDateSource(month, folderName: "2025_03 Hike"),
          DateSource.folderName);
    });

    test('does not mistake a digit run or a later date for a folder date', () {
      var album = AlbumInfo(effectiveDate: millis(2025, 3, 1));
      expect(describeDateSource(album, folderName: "20250301 Hike"),
          DateSource.photos);
      expect(describeDateSource(album, folderName: "Hike 2025-03-01"),
          DateSource.photos);
      // An explicit date wins even when the folder name disagrees.
      expect(
          describeDateSource(
            AlbumInfo(
                date: millis(2024, 1, 1), effectiveDate: millis(2024, 1, 1)),
            folderName: "2025-03-01 Hike",
          ),
          DateSource.explicit);
    });
  });
}
