/// Tests of issue #133: a folder of folders has no date, only an album has.
///
/// The server says which an entry is ([FolderInfo.kind]); the listing tile
/// shows its date line for an album alone, and the move picker names a folder
/// by its title alone. The sort key ([FolderInfo.effectiveDate]) is untouched:
/// a folder named `2026` still sorts with the year it names.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_date.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'listing_date_test.dart' show millis;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The listing of the user's report: the folder `2026` beside an album of
/// that year, both dated by the server and only one of them an album.
String listingJson() => '''
["ListingInfo", {
  "path": "",
  "title": "Library",
  "folders": [
    {"name": "2026-05-01 Trip", "title": "Trip", "kind": "ALBUM",
     "effectiveDate": ${millis(2026, 5, 1)}},
    {"name": "2026", "title": "2026", "kind": "FOLDER",
     "effectiveDate": ${millis(2026, 1, 1)}}
  ]
}]''';

/// The same listing from a server that does not know the field yet.
String oldServerJson() => '''
["ListingInfo", {
  "path": "",
  "title": "Library",
  "folders": [
    {"name": "2026", "title": "2026", "effectiveDate": ${millis(2026, 1, 1)}}
  ]
}]''';

/// One entry of a listing, as the server sends it.
FolderInfo folderOf(String json) =>
    (Resource.fromString(json) as ListingInfo).folders.first;

List<String> dateLines(WidgetTester tester) => [
      for (var text
          in tester.widgetList<Text>(find.byKey(const Key("folder-date"))))
        text.data!,
    ];

void main() {
  group('the listing tile', () {
    testWidgets('shows the date of the album and none of the folder',
        (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: clientReturning(
          listingJson(),
        )));
        await tester.pumpAndSettle();
      });

      // Exactly one date line, and it stands under the album.
      expect(dateLines(tester), ["May 1, 2026"]);
      var date = tester.getTopLeft(find.byKey(const Key("folder-date")));
      expect(date.dy, greaterThan(tester.getTopLeft(find.text("Trip")).dy));
      // "2026 - Jan 1 2026" is what the report was about: the folder is named
      // and nothing more.
      expect(find.text("2026"), findsOneWidget);
      expect(find.text("Jan 1, 2026"), findsNothing);
    });

    testWidgets('keeps the order the sort key gives', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: clientReturning(
          listingJson(),
        )));
        await tester.pumpAndSettle();
      });

      // May 1st before January 1st: the folder still sorts by the year it
      // names, see issue #48.
      expect(
        tester.getTopLeft(find.text("Trip")).dy <=
            tester.getTopLeft(find.text("2026")).dy,
        isTrue,
      );
    });

    testWidgets('shows the date of an entry from an older server',
        (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: clientReturning(
          oldServerJson(),
        )));
        await tester.pumpAndSettle();
      });

      // A server that does not send the field says nothing, and nothing is
      // what the app behaved by before: every entry an album.
      expect(dateLines(tester), ["Jan 1, 2026"]);
    });
  });

  group('folderHasDate', () {
    test('is the album with a date, and nothing else', () {
      var folders = (Resource.fromString(listingJson()) as ListingInfo)
          .folders;
      expect(folderHasDate(folders[0]), isTrue);
      expect(folderHasDate(folders[1]), isFalse);
      // An album without a date has none to show either, as before #133.
      expect(folderHasDate(folderOf(oldServerJson())), isTrue);
      expect(
        folderHasDate(FolderInfo(
          name: "Inbox",
          title: "Inbox",
          kind: FolderKind.album,
        )),
        isFalse,
      );
    });
  });

  group('the move picker line', () {
    test('dates an album and names a folder', () {
      var folders = (Resource.fromString(listingJson()) as ListingInfo)
          .folders;
      expect(folderLine(folders[0]), "2026-05-01 Trip");
      expect(folderLine(folders[1]), "2026");
      // An older server's entry keeps the line it always had.
      expect(folderLine(folderOf(oldServerJson())), "2026-01-01 2026");
    });
  });
}
