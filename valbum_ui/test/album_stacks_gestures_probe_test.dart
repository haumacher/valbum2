/// Probe of issue #314: a burst between a stored group and headings is one
/// step of the viewer's walk like the group — next and previous visit each
/// tile once, across the headings, in both directions, and neither the burst's
/// nor the group's other shots.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'album_stacks_test.dart' show image, routeOf;
import 'move_test.dart' show tile;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fixtures.dart';
import 'util/viewer_harness.dart';

/// The album `Mix`: a heading, a photo, a burst of three (best shot `a2`),
/// a stored group of two (picture `g2`), another heading and a photo.
String mixAlbum() => '["AlbumInfo", {"path": "Mix", "title": "Mix", '
    '"subTitle": "", "rights": [{"name": "view"}, {"name": "download"}, '
    '{"name": "edit"}], '
    '"stacks": [{"members": [{"name": "a2.jpg"}, {"name": "a1.jpg"}, '
    '{"name": "a3.jpg"}]}], '
    '"parts": [["Heading", {"text": "Morning"}], ${image("s.jpg", 0)}, '
    '${image("a1.jpg", 1)}, ${image("a2.jpg", 2)}, ${image("a3.jpg", 3)}, '
    '["ImageGroup", {"representative": 1, "images": [${bare("g1.jpg", 4)}, '
    '${bare("g2.jpg", 5)}]}], '
    '["Heading", {"text": "Evening"}], ${image("z.jpg", 6)}]}]';

/// A photo as a member of a group lists it: no type tag, the list is typed.
String bare(String name, int index) {
  var tagged = image(name, index);
  return tagged.substring('["ImagePart", '.length, tagged.length - 1);
}

Future<void> pumpMix(WidgetTester tester) async {
  var client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    token: "tok-9",
    httpClient: MockClient(servingThumbnails((request) async {
      var path = Uri.decodeFull(request.url.path);
      if (path == "/valbum/data/Mix/") {
        return http.Response(mixAlbum(), 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }
      if (path.startsWith("/valbum/data/Mix/") && request.url.query.isEmpty) {
        return http.Response.bytes(transparentPixelPng, 200,
            headers: {"content-type": "image/png"});
      }
      return http.Response(
          '["ListingInfo", {"path": "", "title": "Library", "folders": []}]',
          200,
          headers: {"content-type": "application/json; charset=utf-8"});
    })),
  );
  var settings = ServerSettings(
    store: InMemorySettingsStore(
        "http://server/valbum/", "tok-9", "Phone", "anna"),
  );
  await settings.load();
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: settings,
    initialRoute: const ListingOrAlbumRoute(["Mix"]),
  ));
  await tester.pumpAndSettle();
}

String shownName(WidgetTester tester) => (routeOf(tester) as ImageRoute).name;

void main() {
  setUp(withEmptyImageCache);

  testWidgets('the walk visits the burst and the group once each, both ways',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpMix(tester);
      expect(tile("a2.jpg"), findsOneWidget, reason: "the burst's tile");
      expect(tile("a1.jpg"), findsNothing);

      await tester.tap(tile("s.jpg"));
      await tester.pumpAndSettle();
      var forward = [shownName(tester)];
      for (var step = 0; step < 4; step++) {
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        forward.add(shownName(tester));
      }
      expect(forward, ["s.jpg", "a2.jpg", "g2.jpg", "z.jpg", "z.jpg"],
          reason: "one step per tile, across the heading, and stops");

      var backward = [shownName(tester)];
      for (var step = 0; step < 4; step++) {
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        backward.add(shownName(tester));
      }
      expect(backward, ["z.jpg", "g2.jpg", "a2.jpg", "s.jpg", "s.jpg"]);
    });
  });
}
