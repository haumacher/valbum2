/// Review probe of the tile spacing (issue #34), composed with pre-existing
/// features: an odd page width, a heading separating two blocks of rows, a
/// mixed row of three images, and the rating filter hiding one image of a row.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';

const double tolerance = 0.5;

String image(String name, int w, int h, {int rating = 0}) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1015113600000, '
    '"width": $w, "height": $h, "orientation": "IDENTITY", "rating": $rating}]';

String albumOf(List<String> parts) => '["AlbumInfo", {'
    '"path": "", "title": "Probe", "subTitle": "", "minRating": 0, '
    '"parts": [${parts.join(",")}]}]';

Finder tile(String name) => find.byWidgetPredicate((widget) {
      if (widget is! Image) {
        return false;
      }
      var provider = widget.image;
      return provider is ThumbnailImage && provider.imageUrl.endsWith("/$name");
    });

Future<void> pumpAlbum(WidgetTester tester, String album,
    {required double width}) async {
  tester.view.physicalSize = Size(width, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(VAlbumApp(client: clientReturning(album)));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'three tiles of unequal aspect at an odd width below the row-height cap: gaps 2px, flush edges',
      (tester) async {
    const pageWidth = 947.0;
    await pumpAlbum(
      tester,
      albumOf([
        image("a.jpg", 1600, 1200),
        image("b.jpg", 1000, 1000),
        image("c.jpg", 1800, 1200),
      ]),
      width: pageWidth,
    );

    var a = tester.getRect(tile("a.jpg"));
    var b = tester.getRect(tile("b.jpg"));
    var c = tester.getRect(tile("c.jpg"));
    expect(a.left, closeTo(0, tolerance));
    expect(b.left - a.right, closeTo(tileSpacing, tolerance));
    expect(c.left - b.right, closeTo(tileSpacing, tolerance));
    expect(c.right, closeTo(pageWidth, tolerance));
    // One row: same top, same height, and the aspect ratios are kept.
    expect(a.top, closeTo(b.top, tolerance));
    expect(a.height, closeTo(c.height, tolerance));
    expect(a.width / a.height, closeTo(4 / 3, 0.01));
    expect(b.width / b.height, closeTo(1, 0.01));
    expect(c.width / c.height, closeTo(1.5, 0.01));
  });

  testWidgets(
      'blocks separated by a heading each fill the width; the filter re-flows the row',
      (tester) async {
    const pageWidth = 741.0;
    await pumpAlbum(
      tester,
      albumOf([
        image("a.jpg", 900, 600),
        image("hidden.jpg", 900, 600, rating: -1),
        '["Heading", {"text": "Later"}]',
        image("c.jpg", 900, 600),
        image("d.jpg", 900, 600),
      ]),
      width: pageWidth,
    );

    // The hidden image is not rendered; the block above the heading is the
    // single remaining image, laid out alone: capped at the maximum row height
    // of 250 (the layout pads the rest of the row), flush with the left edge.
    expect(tile("hidden.jpg"), findsNothing);
    var a = tester.getRect(tile("a.jpg"));
    expect(a.left, closeTo(0, tolerance));
    expect(a.height, closeTo(250, 1.5));
    expect(a.width / a.height, closeTo(1.5, 0.01));
    expect(a.right, lessThan(pageWidth * 0.6));

    var c = tester.getRect(tile("c.jpg"));
    var d = tester.getRect(tile("d.jpg"));
    expect(c.left, closeTo(0, tolerance));
    expect(d.left - c.right, closeTo(tileSpacing, tolerance));
    expect(d.right, closeTo(pageWidth, tolerance));
    expect(find.text("Later"), findsOneWidget);
    // The heading sits between the two blocks.
    var heading = tester.getRect(find.text("Later"));
    expect(heading.top, greaterThan(a.bottom));
    expect(heading.bottom, lessThan(c.top));
  });
}
