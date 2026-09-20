/// Probe for #121/#122: album properties saved outside an edit session from
/// a deep link, cancelled without a write, and the properties of a video.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'move_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

String album({String subTitle = ""}) =>
    '["AlbumInfo", {"path": "2021/Trip", "title": "Trip", "subTitle": "$subTitle", '
    '"rights": [{"name": "edit"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}], '
    '["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", "date": 1015113700000, '
    '"width": 1920, "height": 1080, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

Future<void> pumpDeepLink(
  WidgetTester tester,
  http.Response Function(http.Request) handler,
  List<http.Request> requests,
) async {
  await tester.pumpWidget(VAlbumApp(
    client: VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    ),
    initialRoute: const ListingOrAlbumRoute(["2021", "Trip"]),
  ));
  await tester.pumpAndSettle();
}

Future<void> openEntry(WidgetTester tester, String key) async {
  await openAlbumMenu(tester);
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a subtitle set outside an edit session on a deep-linked album '
      'is written with the title kept, and shown', (tester) async {
    var requests = <http.Request>[];
    var stored = album();
    await withFakeImageHttp(() async {
      await pumpDeepLink(tester, (request) {
        if (request.method == "PUT") {
          stored = album(subTitle: "Am Meer");
          return json("");
        }
        if (pathOf(request) == "/valbum/data/2021/Trip/") return json(stored);
        return json('["ListingInfo", {"path": "2021", "title": "2021", '
            '"rights": [{"name": "edit"}], "folders": []}]');
      }, requests);
      await openEntry(tester, "album-properties");
      // The second text field is the subtitle; the first the title.
      await tester.enterText(find.byType(TextField).at(1), "Am Meer");
      await tester.tap(find.text("Übernehmen"));
      await tester.pumpAndSettle();
    });
    var put = requests.singleWhere((r) => r.method == "PUT");
    expect(Uri.decodeFull(put.url.path), "/valbum/data/2021/Trip/");
    expect(put.body, contains('"title":"Trip"'));
    expect(put.body, contains('"subTitle":"Am Meer"'));
    expect(find.text("Am Meer"), findsOneWidget);
    expect(find.byIcon(Icons.save), findsNothing, reason: "no edit session");
  });

  testWidgets('cancelling the properties outside an edit session writes '
      'nothing', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDeepLink(tester, (request) {
        if (pathOf(request) == "/valbum/data/2021/Trip/") return json(album());
        return json('["ListingInfo", {"path": "2021", "title": "2021", '
            '"rights": [{"name": "edit"}], "folders": []}]');
      }, requests);
      await openEntry(tester, "album-properties");
      await tester.enterText(find.byType(TextField).first, "Renamed");
      await tester.tap(find.text("Abbrechen"));
      await tester.pumpAndSettle();
    });
    expect(requests.where((r) => r.method != "GET"), isEmpty);
    expect(find.text("Trip"), findsWidgets);
    expect(find.text("Renamed"), findsNothing);
  });

  testWidgets('the properties of a video name the file and its time and no '
      'camera', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDeepLink(tester, (request) {
        if (pathOf(request) == "/valbum/data/2021/Trip/") return json(album());
        return json('["ListingInfo", {"path": "2021", "title": "2021", '
            '"rights": [{"name": "edit"}], "folders": []}]');
      }, requests);
      var tile = find.byKey(const ValueKey("clip.mp4"));
      await tester.longPress(tile);
      await tester.pumpAndSettle();
      await tester.tap(
        find.descendant(of: tile, matching: find.byIcon(Icons.notes)),
      );
      await tester.pumpAndSettle();
      expect(find.text("Image properties"), findsOneWidget);
      expect(find.byKey(const Key("property-file")), findsOneWidget);
      expect(find.textContaining("clip.mp4"), findsWidgets);
      expect(find.byKey(const Key("property-time")), findsOneWidget);
      expect(find.byKey(const Key("property-camera")), findsNothing);
    });
  });
}
