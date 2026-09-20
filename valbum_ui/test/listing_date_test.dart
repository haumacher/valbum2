/// Tests of the album date on the screen (issue #107): the line between the
/// title and the subtitle of a listing tile, and the same line under the
/// album's own heading.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_date.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// Local midnight of the given day, the way a date travels on the wire.
int millis(int year, int month, int day) =>
    DateTime(year, month, day).millisecondsSinceEpoch;

/// A listing of one dated and one undated folder, both with a subtitle.
String listingJson() => '''
["ListingInfo", {
  "path": "",
  "title": "Library",
  "folders": [
    {"name": "2002-03-03 Lake", "title": "Lake", "subTitle": "At the shore",
     "effectiveDate": ${millis(2002, 3, 3)}},
    {"name": "Inbox", "title": "Inbox", "subTitle": "Unsorted",
     "effectiveDate": 0}
  ]
}]''';

/// An album with the given effective date.
String albumJson(int effectiveDate) => '''
["AlbumInfo", {
  "path": "",
  "title": "Lake",
  "subTitle": "At the shore",
  "date": 0,
  "effectiveDate": $effectiveDate,
  "parts": [
    ["ImagePart", {
      "kind": "IMAGE", "name": "a.jpg", "date": 1015113600000,
      "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0
    }]
  ]
}]''';

/// The date lines of the listing, in the order they stand on the screen.
List<String> dateLines(WidgetTester tester) => [
      for (var text in tester.widgetList<Text>(find.byKey(const Key(
        "folder-date",
      ))))
        text.data!,
    ];

void main() {
  group('albumDateLabel', () {
    test('writes the date out the way the locale does', () {
      expect(albumDateLabel(millis(2002, 3, 3)), "Mar 3, 2002");
    });

    test('says nothing where nothing is known', () {
      expect(albumDateLabel(0), isNull);
    });
  });

  group('the listing tile', () {
    testWidgets('shows the date between the title and the subtitle',
        (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
            client: clientReturning(
          listingJson(),
        )));
        await tester.pumpAndSettle();
      });

      // One line, on the dated folder only.
      expect(dateLines(tester), ["Mar 3, 2002"]);

      var title = tester.getTopLeft(find.text("Lake"));
      var date = tester.getTopLeft(find.byKey(const Key("folder-date")));
      var subTitle = tester.getTopLeft(find.text("At the shore"));
      expect(date.dy, greaterThan(title.dy));
      expect(subTitle.dy, greaterThan(date.dy));

      // The undated tile is a title and a subtitle, as it always was.
      expect(find.text("Inbox"), findsOneWidget);
      expect(find.text("Unsorted"), findsOneWidget);
    });

    testWidgets('leaves the order and the tile menu alone', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
            client: clientReturning(
          listingJson(),
        )));
        await tester.pumpAndSettle();
      });

      // The dated folder still comes before the undated one, see issue #48.
      expect(
        tester.getTopLeft(find.text("Lake")).dy <=
            tester.getTopLeft(find.text("Inbox")).dy,
        isTrue,
      );

      await withFakeImageHttp(() async {
        await tester.longPress(find.text("Lake"));
        await tester.pumpAndSettle();
      });
      expect(find.text("Move to\u2026"), findsOneWidget);
    });
  });

  group('the album heading', () {
    testWidgets('shows the date under the title', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: clientReturning(albumJson(millis(2002, 3, 3))),
          initialRoute: const ListingOrAlbumRoute(["2002-03-03 Lake"]),
        ));
        await tester.pumpAndSettle();
      });

      var date = find.byKey(const Key("album-heading-date"));
      expect(tester.widget<Text>(date).data, "Mar 3, 2002");
      expect(tester.getTopLeft(date).dy,
          greaterThan(tester.getTopLeft(find.text("Lake")).dy));
      expect(tester.getTopLeft(find.text("At the shore")).dy,
          greaterThan(tester.getTopLeft(date).dy));
    });

    testWidgets('says nothing where nothing is known', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: clientReturning(albumJson(0)),
          initialRoute: const ListingOrAlbumRoute(["Lake"]),
        ));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(const Key("album-heading-date")), findsNothing);
      expect(find.text("At the shore"), findsOneWidget);
    });
  });
}
