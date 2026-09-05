import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Probe review for issues #17/#18: the filter and the tile edits composed
// with saving, heading insertion and the layout.
void main() {
  testWidgets('filtering hides images but the saved sidecar keeps them all',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (r) => http.Response(
          r.method == "PUT" ? "" : fixture("album-ratings.json"), 200,
          headers: {"content-type": "application/json"}),
      requests: requests,
    );
    var original = Resource.read(
        JsonReader.fromString(fixture("album-ratings.json"))) as AlbumInfo;
    var imageCount = original.parts.whereType<AbstractImage>().length;

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
      var before = find.byType(Image).evaluate().length;
      await tester.tap(find.byIcon(Icons.remove_circle_outline));
      await tester.pumpAndSettle();
      expect(find.byType(Image).evaluate().length, lessThan(before));
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
    });

    var puts = requests.where((r) => r.method == "PUT").toList();
    expect(puts, hasLength(1));
    var saved =
        Resource.read(JsonReader.fromString(puts.single.body)) as AlbumInfo;
    expect(saved.parts.whereType<AbstractImage>().length, imageCount);
    expect(puts.single.body, isNot(contains("minRating")));
  });

  test('heading insertion indexes the full part list, not the visible one',
      () {
    var album = Resource.read(
        JsonReader.fromString(fixture("album-ratings.json"))) as AlbumInfo;
    album.minRating = 2;
    var visible = visibleParts(album).whereType<AbstractImage>().toList();
    expect(visible, isNotEmpty);
    var target = visible.last;
    var fullIndex = album.parts.indexOf(target);
    var count = album.parts.length;
    var at = insertHeadingBefore(album, target, "Probe");
    expect(at, fullIndex);
    expect(album.parts.length, count + 1);
    expect(album.parts[fullIndex], isA<Heading>());
    expect(album.parts[fullIndex + 1], same(target));
  });

  test('a rotated landscape lays out as a portrait after relayout', () {
    var part = ImagePart();
    part.name = "a.jpg";
    part.width = 3000;
    part.height = 2000;
    part.orientation = Orientation.identity;
    var landscapeWidth = Img(part).getUnitWidth();
    part.orientation = OrientationOps.rotR(part.orientation);
    expect(part.orientation, Orientation.rotR);
    var rotatedWidth = Img(part).getUnitWidth();
    expect(rotatedWidth, closeTo(1 / landscapeWidth, 1e-12));
    expect(Img(part).isPortrait(), isTrue);
    // Four right turns are the identity, and left undoes right.
    var o = Orientation.identity;
    for (var i = 0; i < 4; i++) {
      o = OrientationOps.rotR(o);
    }
    expect(o, Orientation.identity);
    expect(OrientationOps.rotL(OrientationOps.rotR(Orientation.flipH)),
        Orientation.flipH);
  });
}
