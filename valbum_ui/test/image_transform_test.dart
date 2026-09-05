import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_transform.dart';
import 'package:valbum_ui/resource.dart';

/// The page coordinates the given image pixel is displayed at.
List<double> project(ImageTransform tx, double x, double y) {
  var m = tx.matrix;
  return [
    m.entry(0, 0) * x + m.entry(0, 1) * y + m.entry(0, 3),
    m.entry(1, 0) * x + m.entry(1, 1) * y + m.entry(1, 3),
  ];
}

/// An album of images with the given ratings, linked as an album is loaded.
List<ImagePart> album(List<int> ratings, {int minRating = 0}) {
  var info = AlbumInfo(minRating: minRating);
  var images = [
    for (var i = 0; i < ratings.length; i++)
      ImagePart(
        name: "image$i.jpg",
        rating: ratings[i],
        owner: info,
        width: 100,
        height: 100,
      ),
  ];
  info.parts = images;
  for (var i = 0; i < images.length; i++) {
    images[i].previous = i > 0 ? images[i - 1] : null;
    images[i].next = i < images.length - 1 ? images[i + 1] : null;
    images[i].home = images.first;
    images[i].end = images.last;
  }
  return images;
}

void main() {
  group("fit", () {
    test("fits a landscape image into the viewport, centered", () {
      var tx = ImageTransform.fit(
        rawWidth: 2000,
        rawHeight: 1000,
        pageWidth: 800,
        pageHeight: 600,
      );

      // Limited by the width: 800 / 2000.
      expect(tx.fitScale, 0.4);
      expect(tx.scale, 0.4);
      expect(tx.tx, 0);
      expect(tx.ty, (600 - 400) / 2);
      expect(tx.isInitial, isTrue);
    });

    test("fits a portrait image into the viewport, centered", () {
      var tx = ImageTransform.fit(
        rawWidth: 1000,
        rawHeight: 2000,
        pageWidth: 800,
        pageHeight: 600,
      );

      // Limited by the height: 600 / 2000.
      expect(tx.fitScale, 0.3);
      expect(tx.tx, (800 - 300) / 2);
      expect(tx.ty, 0);
    });

    test("fits a rotated image by its displayed size", () {
      var tx = ImageTransform.fit(
        orientation: Orientation.rotL,
        rawWidth: 2000,
        rawHeight: 1000,
        pageWidth: 800,
        pageHeight: 600,
      );

      // Rotated, the image is 1000 x 2000 on screen.
      expect(tx.width, 1000);
      expect(tx.height, 2000);
      expect(tx.fitScale, 0.3);
      expect(tx.tx, (800 - 300) / 2);
      expect(tx.ty, 0);
    });

    test("never scales an image up", () {
      var tx = ImageTransform.fit(
        rawWidth: 100,
        rawHeight: 50,
        pageWidth: 800,
        pageHeight: 600,
      );

      expect(tx.fitScale, 1.0);
      expect(tx.tx, (800 - 100) / 2);
      expect(tx.ty, (600 - 50) / 2);
    });

    test("places the corners of a rotated image on screen", () {
      var tx = ImageTransform.fit(
        orientation: Orientation.rotL,
        rawWidth: 2000,
        rawHeight: 1000,
        pageWidth: 800,
        pageHeight: 600,
      );

      // The top right corner of the raw image becomes the top left one.
      expect(project(tx, 2000, 0), [closeTo(250, 0.001), closeTo(0, 0.001)]);
      // The top left corner of the raw image becomes the bottom left one.
      expect(project(tx, 0, 0), [closeTo(250, 0.001), closeTo(600, 0.001)]);
      // The bottom right corner becomes the top right one.
      expect(
        project(tx, 2000, 1000),
        [closeTo(550, 0.001), closeTo(0, 0.001)],
      );
    });
  });

  group("zoom", () {
    ImageTransform landscape() => ImageTransform.fit(
          rawWidth: 2000,
          rawHeight: 1000,
          pageWidth: 800,
          pageHeight: 600,
        );

    test("keeps the image point under the cursor when zooming in", () {
      var tx = landscape();

      // The image pixel currently shown at (600, 300).
      var imgX = (600 - tx.tx) / tx.scale;
      var imgY = (300 - tx.ty) / tx.scale;

      tx.zoom(1, 600, 300);

      expect(tx.scale, closeTo(0.48, 0.0001));
      expect(tx.isInitial, isFalse);
      expect(
        project(tx, imgX, imgY),
        [closeTo(600, 0.001), closeTo(300, 0.001)],
      );
    });

    test("zooming out below the fit scale resets to the fitted state", () {
      var tx = landscape();
      tx.zoom(1, 600, 300);
      tx.zoom(-1, 600, 300);

      // 0.4 * 1.2 * 0.8 = 0.384 < 0.4, so it snapped back.
      expect(tx.scale, tx.fitScale);
      expect(tx.tx, tx.fitTx);
      expect(tx.ty, tx.fitTy);
      expect(tx.isInitial, isTrue);
    });

    test("zooming out distributes the slack evenly", () {
      var tx = landscape();

      // Zoomed in far, with the image dragged to the top left corner.
      tx.setCustom(-100, -100, 1.0);

      // One step out: scale 0.8, the image is 1600 x 800 on a 800 x 600 page.
      // Anchored at (0, 0) the translation stays negative in x (no slack), but
      // in y there is slack: -80 above, 600 - 800 + 80 = -120 below.
      tx.zoom(-1, 0, 0);

      expect(tx.scale, closeTo(0.8, 0.0001));
      expect(tx.tx, closeTo(-80, 0.001));
      expect(tx.ty, closeTo(-80, 0.001));

      // Two more steps: 0.512, the image is 1024 x 512, so the height has slack
      // that gets distributed to center the image vertically.
      tx.zoom(-1, 0, 0);
      tx.zoom(-1, 0, 0);

      expect(tx.scale, closeTo(0.512, 0.0001));
      expect(tx.ty, closeTo((600 - 512) / 2, 0.001));
    });

    test("a shift never empties the side that was filled", () {
      var tx = landscape();

      // Image of 800 x 400 (scale 0.4 after the zoom step) placed so that it
      // fills the page to the right but leaves slack on the left.
      tx.setCustom(100, 0, 0.5);
      tx.zoom(-1, 800, 0);

      // Scale 0.4 is the fit scale, so this resets.
      expect(tx.isInitial, isTrue);
      expect(tx.tx, tx.fitTx);
    });
  });

  group("toggle", () {
    test("shows the clicked point 1:1 and back", () {
      var tx = ImageTransform.fit(
        rawWidth: 2000,
        rawHeight: 1000,
        pageWidth: 800,
        pageHeight: 600,
      );

      var imgX = (600 - tx.tx) / tx.scale;
      var imgY = (300 - tx.ty) / tx.scale;

      tx.toggle(600, 300);

      expect(tx.scale, 1.0);
      expect(tx.isInitial, isFalse);
      expect(
        project(tx, imgX, imgY),
        [closeTo(600, 0.001), closeTo(300, 0.001)],
      );

      tx.toggle(600, 300);

      expect(tx.scale, tx.fitScale);
      expect(tx.tx, tx.fitTx);
      expect(tx.ty, tx.fitTy);
      expect(tx.isInitial, isTrue);
    });
  });

  group("navigation", () {
    test("steps to the neighbours", () {
      var images = album([0, 0, 0]);

      expect(nextVisible(images[0], 0), same(images[1]));
      expect(previousVisible(images[2], 0), same(images[1]));
      expect(previousVisible(images[0], 0), isNull);
      expect(nextVisible(images[2], 0), isNull);
    });

    test("skips images below the minimum rating", () {
      var images = album([0, -1, 0]);

      expect(nextVisible(images[0], 0), same(images[2]));
      expect(previousVisible(images[2], 0), same(images[0]));

      // Without a filter, the hidden image is reached.
      expect(nextVisible(images[0], -2), same(images[1]));
    });

    test("returns null if all remaining images are filtered", () {
      var images = album([0, -1, -1]);

      expect(nextVisible(images[0], 0), isNull);
    });

    test("home and end start at the image itself", () {
      var images = album([0, 0, 0]);

      expect(firstVisible(homeOf(images[1]), 0), same(images[0]));
      expect(lastVisible(endOf(images[1]), 0), same(images[2]));
    });

    test("home and end skip filtered images", () {
      var images = album([-1, 0, 0, -1]);

      expect(firstVisible(homeOf(images[2]), 0), same(images[1]));
      expect(lastVisible(endOf(images[1]), 0), same(images[2]));
    });

    test("home and end fall back to walking the links", () {
      var images = album([0, 0, 0]);
      for (var image in images) {
        image.home = null;
        image.end = null;
      }

      expect(homeOf(images[1]), same(images[0]));
      expect(endOf(images[1]), same(images[2]));
    });

    test("a group is rated by its representative", () {
      var group = ImageGroup(
        representative: 1,
        images: [
          ImagePart(name: "a.jpg", rating: 2),
          ImagePart(name: "b.jpg", rating: -1),
        ],
      );

      expect(ratingOf(group), -1);
      expect(isVisible(group, 0), isFalse);
      expect(isVisible(group, -2), isTrue);
    });
  });

  group("commentParagraphs", () {
    test("splits at line breaks and drops empty paragraphs", () {
      expect(
        commentParagraphs("First paragraph.\n\nSecond paragraph."),
        ["First paragraph.", "Second paragraph."],
      );
      expect(
        commentParagraphs("First. \r\n \r\n Second."),
        ["First.", "Second."],
      );
      expect(commentParagraphs("Just one."), ["Just one."]);
      expect(commentParagraphs(""), isEmpty);
    });
  });
}
