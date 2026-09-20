/// Probe for issue #106: the stored orientation composed with a video tile,
/// a group's representative, and a further rotation of an already rotated
/// image within one edit session.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';
import 'util/viewer_harness.dart';

/// A landscape image stored `ROT_L` (given), a video stored `ROT_R`, a group
/// whose representative is stored `ROT_180`, and a plain image.
String probeAlbum(String first) => '''
["AlbumInfo", {"path": "", "title": "Probe", "subTitle": "", "parts": [
  ["ImagePart", {"kind": "IMAGE", "name": "rl.jpg", "date": 1, "width": 900, "height": 600, "orientation": "$first", "rating": 0}],
  ["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", "date": 2, "width": 1920, "height": 1080, "orientation": "ROT_R", "rating": 0}],
  ["ImageGroup", {"representative": 0, "images": [
    {"kind": "IMAGE", "name": "g1.jpg", "date": 3, "width": 900, "height": 600, "orientation": "ROT_180", "rating": 0},
    {"kind": "IMAGE", "name": "g2.jpg", "date": 4, "width": 900, "height": 600, "orientation": "IDENTITY", "rating": 0}
  ]}],
  ["ImagePart", {"kind": "IMAGE", "name": "plain.jpg", "date": 5, "width": 900, "height": 600, "orientation": "IDENTITY", "rating": 0}]
]}]
''';

Finder thumbnailImage(String name) => find.byWidgetPredicate((widget) =>
    widget is Image &&
    thumbnailOf(widget.image)?.imageUrl.endsWith(name) == true);

int quarterTurnsOf(WidgetTester tester, String name) => tester
    .widgetList<RotatedBox>(find.ancestor(
      of: thumbnailImage(name),
      matching: find.byType(RotatedBox),
    ))
    .fold(0, (sum, box) => sum + box.quarterTurns);

void main() {
  setUp(withEmptyImageCache);

  testWidgets('a video tile and a group representative show their stored '
      'orientation', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(probeAlbum("ROT_L"))),
      );
      await tester.pumpAndSettle();
    });
    expect(quarterTurnsOf(tester, "rl.jpg"), 3);
    expect(quarterTurnsOf(tester, "clip.mp4"), 1, reason: "ROT_R is one clockwise");
    expect(quarterTurnsOf(tester, "g1.jpg"), 2, reason: "the representative");
    expect(quarterTurnsOf(tester, "plain.jpg"), 0);
    var video = tester.getSize(find.byKey(const ValueKey("clip.mp4")));
    expect(video.height, greaterThan(video.width), reason: "a turned 16:9 is portrait");
    var group = tester.getSize(find.byKey(const ValueKey("g1.jpg")));
    expect(group.width, greaterThan(group.height), reason: "a half turn swaps nothing");
  });

  testWidgets('rotating an already rotated image further keeps the box while '
      'editing and lays it out anew after the save', (tester) async {
    var stored = probeAlbum("ROT_L");
    var client = clientHandling((request) {
      if (request.method == "PUT") {
        stored = probeAlbum("ROT_180");
        return http.Response("", 200);
      }
      return http.Response(stored, 200);
    });
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
      var boxAtRest = tester.getSize(find.byKey(const ValueKey("rl.jpg")));
      expect(boxAtRest.height, greaterThan(boxAtRest.width));

      await tester.longPress(thumbnailImage("rl.jpg"));
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.rotate_left));
      await tester.pumpAndSettle();
      // ROT_L turned left once more is ROT_180: two clockwise quarters ...
      expect(quarterTurnsOf(tester, "rl.jpg"), 2);
      // ... drawn in the box the album was laid out with, unchanged.
      expect(tester.getSize(find.byKey(const ValueKey("rl.jpg"))), boxAtRest);
      // The neighbours were not touched by the edit.
      expect(quarterTurnsOf(tester, "clip.mp4"), 1);
      expect(quarterTurnsOf(tester, "g1.jpg"), 2);

      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.save), findsNothing);
      expect(quarterTurnsOf(tester, "rl.jpg"), 2);
      var boxAfter = tester.getSize(find.byKey(const ValueKey("rl.jpg")));
      expect(boxAfter.width, greaterThan(boxAfter.height),
          reason: "ROT_180 of a landscape file is laid out landscape");
    });
  });
}
