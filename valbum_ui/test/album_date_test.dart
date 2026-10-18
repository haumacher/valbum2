/// Tests of the album date (issue #48): where the date shown comes from, the
/// order a listing is read in, and the date row of the album properties.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_date.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// Local midnight of the given day, the way the app stores a picked date.
int millis(int year, int month, int day) =>
    DateTime(year, month, day).millisecondsSinceEpoch;

/// An album whose folder name carries its date, without an explicit one.
String albumJson({required int date, required int effectiveDate}) => '''
["AlbumInfo", {
  "path": "",
  "title": "Lake",
  "subTitle": "",
  "date": $date,
  "effectiveDate": $effectiveDate,
  "parts": [
    ["ImagePart", {
      "kind": "IMAGE", "name": "a.jpg", "date": 1015113600000,
      "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0
    }]
  ]
}]''';

/// Shows the album below the folder [name], in the edit mode, with its
/// properties open.
Future<void> openProperties(
  WidgetTester tester,
  VAlbumClient client,
  String name,
) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      VAlbumApp(client: client, initialRoute: ListingOrAlbumRoute([name])),
    );
    await tester.pumpAndSettle();
    await tester.longPress(find.byType(Image).first);
    await tester.pumpAndSettle();
    // The properties live in the album's menu since issue #121.
    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("album-properties")));
    await tester.pumpAndSettle();
  });
}

void main() {
  group('splitAlbumName', () {
    test('a text without a date is a title alone', () {
      var split = splitAlbumName("  Trip to Bangalore ");
      expect(split.date, isNull);
      expect(split.title, "Trip to Bangalore");
    });

    test('a leading day is the date, the rest the title', () {
      var split = splitAlbumName("2025-11-26 Trip to Bangalore");
      expect(split.date, DateTime(2025, 11, 26));
      expect(split.title, "Trip to Bangalore");
    });

    test('a month and a year are dates too', () {
      expect(splitAlbumName("2025-11 Trip").date, DateTime(2025, 11));
      expect(splitAlbumName("2025 Trip").date, DateTime(2025));
      expect(splitAlbumName("2025_11_26-Trip").title, "Trip");
    });

    test('a date alone leaves the title empty', () {
      var split = splitAlbumName("2025-11-26");
      expect(split.date, DateTime(2025, 11, 26));
      expect(split.title, "");
    });

    test('digits that are no word of their own are part of the title', () {
      expect(splitAlbumName("2025er Urlaub").date, isNull);
      expect(splitAlbumName("2025er Urlaub").title, "2025er Urlaub");
      expect(splitAlbumName("20251126 Trip").date, isNull);
    });
  });

  group('sortedFolders', () {
    test('shows the newest first and the undated behind them by name', () {
      var folders = [
        FolderInfo(
            name: "Trip", title: "Trip", effectiveDate: millis(2024, 5, 1)),
        FolderInfo(
            name: "Lake", title: "Lake", effectiveDate: millis(2026, 9, 6)),
        FolderInfo(name: "Zoo", title: "Zoo"),
        FolderInfo(name: "alpha", title: "alpha"),
      ];

      expect(
        [for (var folder in sortedFolders(folders)) folder.name],
        ["Lake", "Trip", "alpha", "Zoo"],
        reason: "newest first, the undated by name and case-insensitively",
      );
      // The listing the app holds is what the server said.
      expect([for (var folder in folders) folder.name],
          ["Trip", "Lake", "Zoo", "alpha"]);
    });

    List<String> names(List<FolderInfo> folders) =>
        [for (var folder in folders) folder.name];

    FolderInfo dated(String name, int year, [int month = 1, int day = 1]) =>
        FolderInfo(
            name: name, title: name, effectiveDate: millis(year, month, day));

    test('reads the root newest first: the latest year on top', () {
      var years = [
        dated("2023", 2023),
        dated("2025", 2025),
        dated("2024", 2024)
      ];
      expect(names(shownFolders(years, const [])), ["2025", "2024", "2023"]);
    });

    test('reads a dated folder oldest first, the undated behind (#270)', () {
      var albums = [
        dated("2025-12-24 Christmas", 2025, 12, 24),
        FolderInfo(name: "Misc", title: "Misc"),
        dated("2025-01-06 Snow", 2025, 1, 6),
        dated("2025-07 Summer", 2025, 7),
      ];
      expect(
        names(shownFolders(albums, const ["2025"])),
        ["2025-01-06 Snow", "2025-07 Summer", "2025-12-24 Christmas", "Misc"],
      );
      expect(
        names(shownFolders(albums, const ["2025", "2025-07 July"])),
        ["2025-01-06 Snow", "2025-07 Summer", "2025-12-24 Christmas", "Misc"],
        reason: "a month folder is dated as well",
      );
    });

    test('reads an undated folder newest first', () {
      var trips = [
        dated("2019-07-04 Lake", 2019, 7, 4),
        FolderInfo(name: "Somewhere", title: "Somewhere"),
        dated("2023-02-01 Snow", 2023, 2, 1),
      ];
      expect(
        names(shownFolders(trips, const ["Trips"])),
        ["2023-02-01 Snow", "2019-07-04 Lake", "Somewhere"],
      );
    });

    test('orders by the date it is given, as for a dated sidecar', () {
      var albums = [
        dated("Dec", 2025, 12),
        FolderInfo(name: "none", title: "none"),
        dated("Old", 1960),
        dated("Jan", 2025),
      ];
      expect(
        names(sortedFolders(albums, listingDate: DateTime(2025))),
        ["Old", "Jan", "Dec", "none"],
      );
      expect(
        names(sortedFolders(albums)),
        ["Dec", "Jan", "Old", "none"],
        reason: "a date before 1970 is a date, never behind the undated",
      );
    });

    test('keeps the server order at the top of a share link', () {
      // A link on "2025" opens at the empty path: the app cannot see the
      // folder's date, the server has ordered it already.
      var sent = [dated("Jan", 2025), dated("Dec", 2025, 12)];
      expect(
          names(shownFolders(sent, const [], linkRoot: true)), ["Jan", "Dec"]);
      expect(names(shownFolders(sent, const ["2025"], linkRoot: true)),
          ["Jan", "Dec"]);
      expect(names(shownFolders(sent, const ["Trips"], linkRoot: true)),
          ["Dec", "Jan"],
          reason: "below the top of the link the path names the folder");
    });

    test('dates a listing by its folder name, the root by nothing', () {
      expect(listingDateOf(const []), isNull);
      expect(listingDateOf(const ["2025"]), DateTime(2025));
      expect(listingDateOf(const ["2025", "2025-07 July"]), DateTime(2025, 7));
      expect(listingDateOf(const ["Trips"]), isNull);
    });
  });

  group('the listing', () {
    testWidgets('shows the newest first, whatever the server sent',
        (tester) async {
      // A listing as an older server (or the offline cache) holds it: the
      // order it was sent in is not the order it is read in.
      var client = clientReturning('''
["ListingInfo", {
  "path": "",
  "title": "Library",
  "folders": [
    {"name": "Zoo", "title": "Zoo"},
    {"name": "Trip", "title": "Trip", "effectiveDate": ${millis(2024, 5, 1)}},
    {"name": "alpha", "title": "alpha"},
    {"name": "Lake", "title": "Lake", "effectiveDate": ${millis(2026, 9, 6)}}
  ]
}]''');
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
      });

      var shown = ["Lake", "Trip", "alpha", "Zoo"];
      var positions = [
        for (var title in shown) tester.getTopLeft(find.text(title)),
      ];
      for (var i = 1; i < positions.length; i++) {
        expect(
          positions[i - 1].dy < positions[i].dy ||
              (positions[i - 1].dy == positions[i].dy &&
                  positions[i - 1].dx < positions[i].dx),
          isTrue,
          reason: "'${shown[i - 1]}' comes before '${shown[i]}'",
        );
      }
    });
  });

  group('a dated listing', () {
    testWidgets('shows the oldest first, whatever the server sent',
        (tester) async {
      // An older server sends a year newest first; the app reads it through.
      var client = clientReturning('''
["ListingInfo", {
  "path": "2025",
  "title": "2025",
  "folders": [
    {"name": "Misc", "title": "Misc"},
    {"name": "Dec", "title": "Dec", "effectiveDate": ${millis(2025, 12, 24)}},
    {"name": "Jul", "title": "Jul", "effectiveDate": ${millis(2025, 7, 1)}},
    {"name": "Jan", "title": "Jan", "effectiveDate": ${millis(2025, 1, 6)}}
  ]
}]''');
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
            client: client, initialRoute: const ListingOrAlbumRoute(["2025"])));
        await tester.pumpAndSettle();
      });

      var shown = ["Jan", "Jul", "Dec", "Misc"];
      var positions = [
        for (var title in shown) tester.getTopLeft(find.text(title)),
      ];
      for (var i = 1; i < positions.length; i++) {
        expect(
          positions[i - 1].dy < positions[i].dy ||
              (positions[i - 1].dy == positions[i].dy &&
                  positions[i - 1].dx < positions[i].dx),
          isTrue,
          reason: "'${shown[i - 1]}' comes before '${shown[i]}'",
        );
      }
    });
  });

  group('describeDateSource', () {
    test('an explicit date is the author speaking', () {
      expect(
        describeDateSource(
          AlbumInfo(
              date: millis(2026, 9, 6), effectiveDate: millis(2026, 9, 6)),
          folderName: "Lake",
        ),
        DateSource.explicit,
      );
    });

    test('a date matching the folder name comes from it', () {
      expect(
        describeDateSource(
          AlbumInfo(effectiveDate: millis(2026, 9, 6)),
          folderName: "2026-09-06 Lake",
        ),
        DateSource.folderName,
      );
      // A year alone is January 1st, exactly as the server reads it.
      expect(
        describeDateSource(
          AlbumInfo(effectiveDate: millis(2026, 1, 1)),
          folderName: "2026 Lake",
        ),
        DateSource.folderName,
      );
    });

    test('a date the folder name does not say comes from the photos', () {
      expect(
        describeDateSource(
          AlbumInfo(effectiveDate: millis(2026, 9, 6)),
          folderName: "Lake",
        ),
        DateSource.photos,
      );
      // The name says a date, but another one.
      expect(
        describeDateSource(
          AlbumInfo(effectiveDate: millis(2026, 9, 6)),
          folderName: "2020-01-01 Lake",
        ),
        DateSource.photos,
      );
    });

    test('no date at all', () {
      expect(describeDateSource(AlbumInfo()), DateSource.none);
    });

    test('falls back to the path of the album', () {
      expect(
        describeDateSource(
          AlbumInfo(path: "2026-09-06 Lake", effectiveDate: millis(2026, 9, 6)),
        ),
        DateSource.folderName,
      );
    });

    test('reads the leading date of a folder name as the server does', () {
      expect(folderNameDate("20200524 Trip"), isNull,
          reason: "eight digits are not the year 2020");
      expect(folderNameDate("Trip"), isNull);
      expect(folderNameDate("2020-13 Trip"), DateTime(2020));
      expect(folderNameDate("2020-02-31 Trip"), DateTime(2020, 2));
      expect(folderNameDate("2020_05_24 Trip"), DateTime(2020, 5, 24));
    });
  });

  group('the album properties', () {
    testWidgets('says that the date comes from the folder name',
        (tester) async {
      var client = clientReturning(
        albumJson(date: 0, effectiveDate: millis(2026, 9, 6)),
      );
      await openProperties(tester, client, "2026-09-06 Lake");

      expect(
        tester.widget<Text>(find.byKey(const Key("album-date"))).data,
        testL10n.dateIs("2026-09-06"),
      );
      expect(
        tester.widget<Text>(find.byKey(const Key("album-date-source"))).data,
        testL10n.dateFromFolderName,
      );
      // Nothing to clear while nothing explicit is set.
      expect(
        tester
            .widget<IconButton>(find.byKey(const Key("album-date-clear")))
            .onPressed,
        isNull,
      );
    });

    testWidgets('says that the date comes from the photos', (tester) async {
      var client = clientReturning(
        albumJson(date: 0, effectiveDate: millis(2026, 9, 6)),
      );
      await openProperties(tester, client, "Lake");

      expect(
        tester.widget<Text>(find.byKey(const Key("album-date-source"))).data,
        testL10n.dateFromPhotos,
      );
    });

    testWidgets('picks a date, marks the album dirty and saves it',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(
        albumJson(date: 0, effectiveDate: millis(2026, 9, 6)),
        requests: requests,
      );
      await openProperties(tester, client, "2026-09-06 Lake");

      await withFakeImageHttp(() async {
        // The picker opens on the date the album is shown with.
        await tester.tap(find.byTooltip(testL10n.pickDate));
        await tester.pumpAndSettle();
        await tester.tap(find.text("15"));
        await tester.pumpAndSettle();
        await tester.tap(find.text("OK"));
        await tester.pumpAndSettle();
      });

      expect(
        tester.widget<Text>(find.byKey(const Key("album-date"))).data,
        testL10n.dateIs("2026-09-15"),
      );
      // The explicit date silences the hint: it is no longer derived.
      expect(find.byKey(const Key("album-date-source")), findsNothing);

      await withFakeImageHttp(() async {
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var state = tester.state<AlbumContentState>(find.byType(AlbumContent));
      expect(state.widget.album.date, millis(2026, 9, 15));
      expect(state.session.dirty, isTrue,
          reason: "the date rides the ordinary album save, like the title");

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.lastWhere((request) => request.method == "PUT");
      expect(put.body, contains('"date":${millis(2026, 9, 15)}'));
      // The derived date is never set by the app; what the server sent back
      // travels along and is dropped there.
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect(saved.date, millis(2026, 9, 15));
    });

    testWidgets('clears the explicit date', (tester) async {
      var client = clientReturning(
        albumJson(
          date: millis(2026, 9, 6),
          effectiveDate: millis(2026, 9, 6),
        ),
      );
      await openProperties(tester, client, "2026-09-06 Lake");

      // An explicit date is shown without a hint, and can be cleared.
      expect(find.byKey(const Key("album-date-source")), findsNothing);

      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("album-date-clear")));
        await tester.pumpAndSettle();
      });

      // Nothing is claimed about what the server will derive instead.
      expect(
        tester.widget<Text>(find.byKey(const Key("album-date"))).data,
        testL10n.dateNone,
      );

      await withFakeImageHttp(() async {
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var album = tester
          .state<AlbumContentState>(find.byType(AlbumContent))
          .widget
          .album;
      expect(album.date, 0);
    });
  });
}
