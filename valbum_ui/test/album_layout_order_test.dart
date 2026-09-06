import 'dart:math';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_layout.dart' as lay;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Issue #44: within a double-height row section the images read row-wise -
// the upper row holds a prefix of the section's images in stored order, the
// lower row the remaining suffix. A drop right of an image therefore lands
// right of it, not below it.

ImagePart img(String name, int width, int height) =>
    ImagePart(name: name, width: width, height: height);

/// A landscape image of the given name.
ImagePart landscape(String name) => img(name, 2048, 1536);

/// A portrait image of the given name.
ImagePart portrait(String name) => img(name, 1536, 2048);

String nameOf(AbstractImage image) => (image as ImagePart).name;

List<String> namesOf(Iterable<AbstractImage> images) =>
    images.map(nameOf).toList();

/// The names of the images of the given [lay.Row], in the order the row shows them.
List<String> rowNames(lay.Row row) {
  var collector = _Images();
  row.visit(collector, null);
  return namesOf(collector.images);
}

/// All [lay.DoubleRow]s of the given layout, in the order they are laid out.
List<lay.DoubleRow> doubleRows(lay.AlbumLayout layout) {
  var collector = _DoubleRows();
  for (var row in layout) {
    row.visit(collector, null);
  }
  return collector.found;
}

/// Whether the given content contains a [lay.Padding], directly or nested.
bool hasPadding(lay.Content content) {
  var probe = _Paddings();
  content.visit(probe, null);
  return probe.found;
}

class _Images implements lay.ContentVisitor<void, void> {
  final List<AbstractImage> images = [];

  @override
  void visitRow(lay.Row content, void arg) {
    for (var element in content) {
      element.visit(this, null);
    }
  }

  @override
  void visitImg(lay.Img content, void arg) => images.add(content.getImage());

  @override
  void visitDoubleRow(lay.DoubleRow content, void arg) {
    content.getUpper().visit(this, null);
    content.getLower().visit(this, null);
  }

  @override
  void visitPadding(lay.Padding content, void arg) {}
}

class _DoubleRows implements lay.ContentVisitor<void, void> {
  final List<lay.DoubleRow> found = [];

  @override
  void visitRow(lay.Row content, void arg) {
    for (var element in content) {
      element.visit(this, null);
    }
  }

  @override
  void visitImg(lay.Img content, void arg) {}

  @override
  void visitDoubleRow(lay.DoubleRow content, void arg) {
    found.add(content);
    content.getUpper().visit(this, null);
    content.getLower().visit(this, null);
  }

  @override
  void visitPadding(lay.Padding content, void arg) {}
}

class _Paddings implements lay.ContentVisitor<void, void> {
  bool found = false;

  @override
  void visitRow(lay.Row content, void arg) {
    for (var element in content) {
      element.visit(this, null);
    }
  }

  @override
  void visitImg(lay.Img content, void arg) {}

  @override
  void visitDoubleRow(lay.DoubleRow content, void arg) {
    content.getUpper().visit(this, null);
    content.getLower().visit(this, null);
  }

  @override
  void visitPadding(lay.Padding content, void arg) => found = true;
}

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

List<String> partNames(AlbumInfo album) =>
    [for (var part in album.parts) (part as ImagePart).name];

/// Loads the given album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, String albumJson) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson)));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// Drags the tile [from] onto the left or right half of [target].
Future<void> dragOnto(
  WidgetTester tester,
  Finder from,
  Finder target, {
  required bool left,
}) async {
  var box = tester.getRect(from);
  var start = Offset(box.left + 8, box.center.dy);
  var targetBox = tester.getRect(target);
  var end = Offset(
    targetBox.left + targetBox.width * (left ? 0.25 : 0.75),
    targetBox.center.dy,
  );

  var gesture = await tester.startGesture(start);
  // Sideways, past the touch slop: this is what picks the tile up.
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(end);
  await tester.pump();
  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  group('the order of a double-height row section', () {
    test('reads row-wise, the upper row first (issue #44)', () {
      var p = portrait("P");
      var l1 = landscape("L1");
      var l2 = landscape("L2");
      var l3 = landscape("L3");
      var l4 = landscape("L4");

      var layout = lay.AlbumLayout(1200, 400, [p, l1, l2, l3, l4]);

      var sections = doubleRows(layout);
      expect(sections, hasLength(1));
      // Four equally wide landscapes split in the middle.
      expect(rowNames(sections[0].getUpper()), ["L1", "L2"]);
      expect(rowNames(sections[0].getLower()), ["L3", "L4"]);

      expect(namesOf(layout.getAllImages()), ["P", "L1", "L2", "L3", "L4"]);
    });

    test('splits an odd section for the best height ratio, upper row first',
        () {
      // Three equally wide landscapes: the split before the last one and the
      // split after the first one are equally unbalanced, the tie puts the
      // additional image into the upper row.
      var builder = lay.DoubleRowBuilder([
        lay.Img(landscape("L1")),
        lay.Img(landscape("L2")),
        lay.Img(landscape("L3")),
      ]);
      expect(builder.acceptable(), isFalse);

      var section = builder.build();
      expect(rowNames(section.getUpper()), ["L1", "L2"]);
      expect(rowNames(section.getLower()), ["L3"]);
      // The narrower lower row is padded, no row is flipped.
      expect(hasPadding(section.getLower()), isTrue);
      expect(hasPadding(section.getUpper()), isFalse);
      expect(section.getH1(), 0.5);
      expect(section.getH2(), 0.5);
    });

    test('a wide image balances an unequal number of images', () {
      // A panorama-ish 3:1 image beside two 4:3 ones: the best split is the
      // one that makes the two rows equally wide.
      var builder = lay.DoubleRowBuilder([
        lay.Img(img("W", 3000, 1000)),
        lay.Img(landscape("L1")),
        lay.Img(landscape("L2")),
      ]);
      expect(builder.acceptable(), isTrue);

      var section = builder.build();
      expect(rowNames(section.getUpper()), ["W"]);
      expect(rowNames(section.getLower()), ["L1", "L2"]);
      expect(section.getH1() + section.getH2(), closeTo(1.0, 1e-9));
    });

    test('a single image keeps the upper row and pads the lower one', () {
      var builder = lay.DoubleRowBuilder([lay.Img(landscape("L1"))]);
      var section = builder.build();
      expect(rowNames(section.getUpper()), ["L1"]);
      expect(rowNames(section.getLower()), isEmpty);
      expect(hasPadding(section.getLower()), isTrue);
    });
  });

  group('the layout of random albums', () {
    const List<List<int>> sizes = [
      [2048, 1536],
      [1536, 2048],
      [1600, 1200],
      [1200, 1600],
      [3000, 1000],
      [1000, 1000],
    ];
    const List<double> pageWidths = [320, 768, 1280, 1920];
    const List<double> maxRowHeights = [250, 400];

    test('shows every image exactly once, sections in stored order', () {
      var random = Random(44);
      var plain = 0;

      for (var run = 0; run < 200; run++) {
        var count = 1 + random.nextInt(40);
        var images = [
          for (var i = 0; i < count; i++)
            () {
              var size = sizes[random.nextInt(sizes.length)];
              return img("i$i", size[0], size[1]);
            }(),
        ];
        var pageWidth = pageWidths[random.nextInt(pageWidths.length)];
        var maxRowHeight = maxRowHeights[random.nextInt(maxRowHeights.length)];
        var what = "run $run, $count images, "
            "page width $pageWidth, max row height $maxRowHeight";

        var layout = lay.AlbumLayout(pageWidth, maxRowHeight, images);
        var shown = layout.getAllImages();

        // Every image is shown exactly once.
        expect(namesOf(shown)..sort(), namesOf(images)..sort(), reason: what);

        var index = {for (var i = 0; i < images.length; i++) images[i].name: i};
        for (var section in doubleRows(layout)) {
          var positions = [
            ...rowNames(section.getUpper()),
            ...rowNames(section.getLower()),
          ].map((name) => index[name]!).toList();

          // Issue #44: a section reads row-wise in the stored order, the
          // upper row before the lower one.
          for (var i = 1; i < positions.length; i++) {
            expect(positions[i], greaterThan(positions[i - 1]),
                reason: "$what: section out of order $positions");
          }

          expect(section.getH1() + section.getH2(), closeTo(1.0, 1e-9),
              reason: what);
          var padded =
              hasPadding(section.getUpper()) || hasPadding(section.getLower());
          if (!padded) {
            var ratio = section.getH1() / section.getH2();
            expect(ratio, greaterThanOrEqualTo(lay.DoubleRowBuilder.lowerLimit),
                reason: what);
            expect(ratio, lessThanOrEqualTo(lay.DoubleRowBuilder.upperLimit),
                reason: what);
          }
        }

        // Two images that are neither portrait nor panorama keep their
        // relative order: a run of ordinary landscape images is laid out from
        // left to right and, inside a double-height section, row-wise.
        //
        // A portrait image can still change places with the landscape images
        // buffered around it (the row computation collects those into a
        // section that is placed as a whole, see the "the layout shows B
        // before A" case of album_reorder_test.dart), and a panorama image
        // opens a full-width row of its own right away, in front of a row
        // still being filled. Both are older than issue #44 and untouched by
        // it.
        bool ordinary(ImagePart image) {
          var unitWidth = image.width / image.height;
          return unitWidth > lay.Content.maxPortraitUnitWidth &&
              unitWidth < pageWidth / maxRowHeight;
        }

        var storedOrdinary = images.where(ordinary).toList();
        var shownOrdinary = shown.cast<ImagePart>().where(ordinary).toList();
        expect(namesOf(shownOrdinary), namesOf(storedOrdinary), reason: what);

        if (storedOrdinary.length == images.length) {
          // Nothing that could change places: the displayed order is the
          // stored order.
          expect(namesOf(shown), namesOf(images), reason: what);
          plain++;
        }

        // Every row states the width of its contents.
        for (var row in layout) {
          var sum = row.fold<double>(0, (s, c) => s + c.getUnitWidth());
          expect(sum, closeTo(row.getUnitWidth(), 1e-9), reason: what);
        }
      }

      // The albums of ordinary landscape images only are a relevant part of
      // the sample, not a handful of one-image cases.
      expect(plain, greaterThan(5));
    });

    test('a portrait image followed by landscape images reads in order', () {
      // The case of issue #44: the section behind the portrait image must not
      // zig-zag between its two rows.
      var random = Random(4444);
      for (var run = 0; run < 50; run++) {
        var count = 2 + random.nextInt(12);
        var images = <ImagePart>[
          portrait("p"),
          for (var i = 1; i < count; i++) landscape("l$i"),
        ];
        for (var pageWidth in pageWidths) {
          for (var maxRowHeight in maxRowHeights) {
            var layout = lay.AlbumLayout(pageWidth, maxRowHeight, images);
            expect(namesOf(layout.getAllImages()), namesOf(images),
                reason: "$count images, $pageWidth x $maxRowHeight");
          }
        }
      }
    });
  });

  group('a drop right of an image in a double-height section', () {
    testWidgets('lands right of it, in the same row', (tester) async {
      var albumJson = AlbumInfo(
        path: "",
        title: "Double rows",
        parts: [
          portrait("P.jpg"),
          landscape("L1.jpg"),
          landscape("L2.jpg"),
          landscape("L3.jpg"),
          landscape("L4.jpg"),
        ],
      ).toString();

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, albumJson);

        expect(partNames(album(tester)),
            ["P.jpg", "L1.jpg", "L2.jpg", "L3.jpg", "L4.jpg"]);
        // The album is displayed in the order it is stored.
        expect(
          albumState(tester).displayOrder.map((p) => (p as ImagePart).name),
          ["P.jpg", "L1.jpg", "L2.jpg", "L3.jpg", "L4.jpg"],
        );

        var upperTop = tester.getRect(tile("L1.jpg")).top;

        await dragOnto(tester, tile("L4.jpg"), tile("L1.jpg"), left: false);

        // Stored directly behind L1.
        expect(partNames(album(tester)),
            ["P.jpg", "L1.jpg", "L4.jpg", "L2.jpg", "L3.jpg"]);

        // And shown directly right of L1, in the upper row of the section.
        var l1 = tester.getRect(tile("L1.jpg"));
        var l4 = tester.getRect(tile("L4.jpg"));
        expect(l1.top, closeTo(upperTop, 0.5));
        expect(l4.top, closeTo(l1.top, 0.5));
        expect(l4.left, greaterThan(l1.left));
      });
    });
  });
}
