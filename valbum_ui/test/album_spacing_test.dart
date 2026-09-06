/// The 2px gap between the tiles of the album layout, see issue #34.
///
/// The GWT client separated the images of a row (and the rows themselves) by
/// two pixels; without that gap the album reads as one big collage. The gap is
/// added when the layout is turned into widgets, so the tests below measure
/// the rendered tiles: the row still fills the page width exactly, the tiles
/// are [tileSpacing] apart, and a double row still has the height of the row
/// it sits in.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';

/// Maximum accepted deviation of a measured coordinate, in logical pixels.
const double tolerance = 0.5;

/// A landscape image (3:2) named [name].
String landscape(String name) => '["ImagePart", {'
    '"kind": "IMAGE", "name": "$name", "date": 1015113600000, '
    '"width": 900, "height": 600, "orientation": "IDENTITY", "rating": 0}]';

/// A portrait image (2:3) named [name].
String portrait(String name) => '["ImagePart", {'
    '"kind": "IMAGE", "name": "$name", "date": 1015113600000, '
    '"width": 600, "height": 900, "orientation": "IDENTITY", "rating": 0}]';

/// An album showing the given parts.
String albumOf(List<String> parts) => '["AlbumInfo", {'
    '"path": "", "title": "Spacing", "subTitle": "", '
    '"parts": [${parts.join(",")}]}]';

/// The tile of the image with the given file name.
///
/// Outside the edit mode a tile is the bare thumbnail [Image], which carries
/// no key; it is identified by the image it shows.
Finder tile(String name) => find.byWidgetPredicate((widget) {
      if (widget is! Image) {
        return false;
      }
      var image = widget.image;
      return image is ThumbnailImage && image.imageUrl.endsWith("/$name");
    });

/// Renders the given album at the given page width.
Future<void> pumpAlbum(
  WidgetTester tester,
  String album, {
  required double width,
  double height = 1400,
}) async {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(VAlbumApp(client: clientReturning(album)));
  await tester.pumpAndSettle();
}

void main() {
  test('the gap is the 2px of the GWT client', () {
    expect(tileSpacing, 2.0);
  });

  testWidgets('separates the tiles of a row by 2px and still fills the page',
      (tester) async {
    const pageWidth = 720.0;
    await pumpAlbum(
      tester,
      albumOf([landscape("a.jpg"), landscape("b.jpg")]),
      width: pageWidth,
    );

    var left = tester.getRect(tile("a.jpg"));
    var right = tester.getRect(tile("b.jpg"));

    expect(left.left, closeTo(0, tolerance));
    expect(right.left - left.right, closeTo(tileSpacing, tolerance));
    expect(right.right, closeTo(pageWidth, tolerance));
    // The two images are alike, so the gap is the only asymmetry.
    expect(left.width, closeTo(right.width, tolerance));
    expect(left.height, closeTo(right.height, tolerance));
  });

  testWidgets('separates two consecutive rows by 2px', (tester) async {
    await pumpAlbum(
      tester,
      albumOf([
        landscape("a.jpg"),
        landscape("b.jpg"),
        landscape("c.jpg"),
        landscape("d.jpg"),
      ]),
      width: 720,
    );

    var firstRow = tester.getRect(tile("a.jpg"));
    var secondRow = tester.getRect(tile("c.jpg"));

    expect(secondRow.top - firstRow.bottom, closeTo(tileSpacing, tolerance));
  });

  testWidgets('separates the halves of a double row, keeping its height',
      (tester) async {
    // This is the `portrait-landscapes-portrait` layout fixture: at a page
    // width of 1280 the first row is a portrait, a double row of four
    // landscapes (upper: l2, l4; lower: l3, l5) and a portrait; the last
    // landscape and a padding form the second row.
    const pageWidth = 1280.0;
    await pumpAlbum(
      tester,
      albumOf([
        portrait("p1.jpg"),
        landscape("l2.jpg"),
        landscape("l3.jpg"),
        landscape("l4.jpg"),
        landscape("l5.jpg"),
        landscape("l6.jpg"),
        portrait("p7.jpg"),
      ]),
      width: pageWidth,
    );

    var upperLeft = tester.getRect(tile("l2.jpg"));
    var upperRight = tester.getRect(tile("l4.jpg"));
    var lowerLeft = tester.getRect(tile("l3.jpg"));
    var lowerRight = tester.getRect(tile("l5.jpg"));
    var before = tester.getRect(tile("p1.jpg"));
    var after = tester.getRect(tile("p7.jpg"));

    // The halves are 2px apart, and each of them is a row of its own.
    expect(lowerLeft.top - upperLeft.bottom, closeTo(tileSpacing, tolerance));
    expect(upperRight.left - upperLeft.right, closeTo(tileSpacing, tolerance));
    expect(lowerRight.left - lowerLeft.right, closeTo(tileSpacing, tolerance));

    // The double row is as high as the images beside it, and as wide as the
    // gap between them.
    expect(lowerLeft.bottom - upperLeft.top, closeTo(before.height, tolerance));
    expect(upperLeft.left - before.right, closeTo(tileSpacing, tolerance));
    expect(after.left - upperRight.right, closeTo(tileSpacing, tolerance));

    // The row as a whole still fills the page exactly.
    expect(before.left, closeTo(0, tolerance));
    expect(after.right, closeTo(pageWidth, tolerance));

    // ... and the row below it keeps its distance.
    var below = tester.getRect(tile("l6.jpg"));
    expect(below.top - before.bottom, closeTo(tileSpacing, tolerance));
  });

  testWidgets('shows the same spacing in the alternatives view',
      (tester) async {
    const pageWidth = 720.0;
    tester.view.physicalSize = const Size(pageWidth, 1200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    var group = ImageGroup(
      representative: 0,
      images: [
        ImagePart(name: "a.jpg", width: 900, height: 600),
        ImagePart(name: "b.jpg", width: 900, height: 600),
      ],
    );

    await tester.pumpWidget(
      MaterialApp(
        home: GroupView(
          client: clientReturning("{}"),
          baseUrl: "http://server/valbum/data",
          group: group,
          onUp: () {},
          onShowDetail: (image) {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    var left = tester.getRect(find.byKey(const ValueKey("group-tile-a.jpg")));
    var right = tester.getRect(find.byKey(const ValueKey("group-tile-b.jpg")));

    expect(left.left, closeTo(0, tolerance));
    expect(right.left - left.right, closeTo(tileSpacing, tolerance));
    expect(right.right, closeTo(pageWidth, tolerance));
  });
}
