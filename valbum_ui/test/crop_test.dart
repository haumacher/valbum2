/// The arithmetic of the crop of a photograph (issue #212), without a widget:
/// the two frames and the shared table of the server, the rotation carrying
/// the crop, the layout at the crop's aspect, the viewer's transform of the
/// region, and the editor's rectangle under each constraint.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/crop.dart';
import 'package:valbum_ui/image_transform.dart';
import 'package:valbum_ui/resource.dart';

/// The table the server's `TestCrop` reads too.
const String sharedTable =
    "../image-server/src/test/fixtures/crop-orientations.json";

Orientation orientationNamed(String name) =>
    readOrientation(JsonReader.fromString('"$name"'));

void expectRect(Rect actual, Rect expected, {String? reason}) {
  expect(actual.left, closeTo(expected.left, 1e-9), reason: reason);
  expect(actual.top, closeTo(expected.top, 1e-9), reason: reason);
  expect(actual.width, closeTo(expected.width, 1e-9), reason: reason);
  expect(actual.height, closeTo(expected.height, 1e-9), reason: reason);
}

ImagePart photo({
  int width = 2000,
  int height = 1000,
  Orientation orientation = Orientation.identity,
  Crop? crop,
}) =>
    ImagePart(
      name: "a.jpg",
      width: width,
      height: height,
      orientation: orientation,
      crop: crop,
    );

void main() {
  group("the two frames", () {
    test("follow the table the server reads", () {
      var table = jsonDecode(File(sharedTable).readAsStringSync())
          as Map<String, dynamic>;
      var cases = table["cases"] as List<dynamic>;
      expect(cases, hasLength(8));
      for (var entry in cases.cast<Map<String, dynamic>>()) {
        var name = entry["orientation"] as String;
        var orientation = orientationNamed(name);
        var shown = (entry["shown"] as List).cast<num>();
        var rendition = (entry["rendition"] as List).cast<num>();
        var crop = Crop(
          x: shown[0].toDouble(),
          y: shown[1].toDouble(),
          w: shown[2].toDouble(),
          h: shown[3].toDouble(),
        );
        var computed = cropToRendition(orientation, crop);
        expectRect(
          computed,
          Rect.fromLTWH(
            rendition[0].toDouble(),
            rendition[1].toDouble(),
            rendition[2].toDouble(),
            rendition[3].toDouble(),
          ),
          reason: name,
        );
        var back = cropToShown(orientation, computed);
        expect(back.x, closeTo(crop.x, 1e-9), reason: name);
        expect(back.y, closeTo(crop.y, 1e-9), reason: name);
        expect(back.w, closeTo(crop.w, 1e-9), reason: name);
        expect(back.h, closeTo(crop.h, 1e-9), reason: name);
      }
    });

    test("the cut thumbnail is asked for by the region", () {
      var image = photo(
        orientation: Orientation.rotL,
        crop: Crop(x: 0, y: 0, w: 0.5, h: 0.5),
      );
      // rotL turns counter-clockwise: the shown top left is the rendition's
      // top right (the server's TestCrop says the same).
      expectRect(
          renditionRegion(image)!, const Rect.fromLTWH(0.5, 0, 0.5, 0.5));
      var url = croppedImageUrl("http://s/data/A/a.jpg", image);
      expect(url, "http://s/data/A/a.jpg?crop=0.5000,0.0000,0.5000,0.5000");
      var client = VAlbumClient(dataUrl: "http://s/data");
      expect(client.thumbnailUrl(url),
          "http://s/data/A/a.jpg?crop=0.5000,0.0000,0.5000,0.5000&type=tn");
      expect(client.thumbnailUrl("http://s/data/A/a.jpg"),
          "http://s/data/A/a.jpg?type=tn");

      expect(croppedImageUrl("u", photo()), "u", reason: "no crop, no region");
      expect(
        croppedImageUrl("u", photo(crop: Crop(x: 0, y: 0, w: 1, h: 1))),
        "u",
        reason: "the whole picture is no crop",
      );
      var video = photo(crop: Crop(x: 0, y: 0, w: 0.5, h: 0.5))
        ..kind = ImageKind.video;
      expect(croppedImageUrl("u", video), "u", reason: "a video is never cut");
    });
  });

  group("a rotation carries the crop", () {
    final operations = <String, Orientation Function(Orientation)>{
      "rotL": OrientationOps.rotL,
      "rotR": OrientationOps.rotR,
      "flipH": OrientationOps.flipH,
      "flipV": OrientationOps.flipV,
    };
    for (var start in Orientation.values) {
      for (var op in operations.entries) {
        test("${start.name} by ${op.key}", () {
          var image = photo(
            orientation: start,
            crop: Crop(x: 0.1, y: 0.2, w: 0.3, h: 0.4),
          );
          var before = renditionRegion(image)!;
          turnImage(image, op.value(start));
          expect(image.orientation, op.value(start));
          // The same region of the file stays selected.
          expectRect(renditionRegion(image)!, before);
          // And it is shown at the turned aspect.
          var swaps = PlaneTransform.of(op.value(start)).swapsDimensions !=
              PlaneTransform.of(start).swapsDimensions;
          if (swaps) {
            expect(image.crop!.w, closeTo(0.4, 1e-9));
            expect(image.crop!.h, closeTo(0.3, 1e-9));
          } else {
            expect(image.crop!.w, closeTo(0.3, 1e-9));
            expect(image.crop!.h, closeTo(0.4, 1e-9));
          }
        });
      }
    }

    test("an image without a crop stays without one", () {
      var image = photo();
      turnImage(image, Orientation.rotL);
      expect(image.crop, isNull);
    });
  });

  group("the layout", () {
    test("lays a cropped tile out at the crop's aspect", () {
      // 2000 x 1000, the left quarter: 500 x 1000 is a portrait of unit 0.5.
      var cropped = photo(crop: Crop(x: 0, y: 0, w: 0.25, h: 1));
      expect(layouter.Img(cropped).getUnitWidth(), 0.5);
      // Turned a quarter, the stored crop is of the turned picture: the left
      // quarter of a 1000 x 2000 portrait is 250 x 2000, unit 0.125.
      var turned = photo(
        orientation: Orientation.rotL,
        crop: Crop(x: 0, y: 0, w: 0.25, h: 1),
      );
      expect(layouter.Img(turned).getUnitWidth(), 0.125);
      expect(layouter.Img(photo()).getUnitWidth(), 2.0);
    });

    test("the rows are as high as for a picture of the region's size", () {
      List<double> heights(List<AbstractImage> images) => [
            for (var row in layouter.AlbumLayout(1000, 250, images).getRows())
              1000 / row.getUnitWidth(),
          ];
      var withCrop = [
        photo(),
        photo(crop: Crop(x: 0.5, y: 0, w: 0.5, h: 1)),
        photo(),
      ];
      // The region of the second one is 1000 x 1000: a square.
      var asIf = [photo(), photo(width: 1000, height: 1000), photo()];
      expect(heights(withCrop), heights(asIf));
      // Unit widths 2 + 1 + 2 = 5: one row of 1000 / 5 = 200 px.
      expect(heights(withCrop), [200.0]);
    });
  });

  group("the viewer", () {
    for (var orientation in Orientation.values) {
      test(
          "maps the crop's corners onto the fitted rectangle (${orientation.name})",
          () {
        var image = photo(
          orientation: orientation,
          crop: Crop(x: 0.25, y: 0.1, w: 0.5, h: 0.6),
        );
        var tx = ImageTransform.ofImage(image, pageWidth: 800, pageHeight: 600);
        var region = renditionRegion(image)!;
        expect(tx.region, region);
        // What the transform places is the region alone.
        expect(tx.rawWidth, closeTo(2000 * region.width, 1e-9));
        expect(tx.rawHeight, closeTo(1000 * region.height, 1e-9));
        var fitted = Rect.fromLTWH(
          tx.fitTx,
          tx.fitTy,
          tx.width * tx.fitScale,
          tx.height * tx.fitScale,
        );
        expectRect(
          pageRectOfBox(
              tx, region.left, region.top, region.width, region.height),
          fitted,
        );
        expectRect(pictureRectOnPage(tx), fitted);
        // It fills the page in one direction and is centred in the other.
        expect(
          (fitted.width - 800).abs() < 1e-6 ||
              (fitted.height - 600).abs() < 1e-6,
          isTrue,
        );
        // The shown aspect is the crop's.
        var shownW = layouter.Orientations.width(orientation, 2000, 1000) * 0.5;
        var shownH = layouter.Orientations.height(orientation, 2000, 1000) * 0.6;
        expect(fitted.width / fitted.height, closeTo(shownW / shownH, 1e-9));

        // A rectangle drawn on the page comes back in the frame of the whole
        // picture, clamped to the region.
        var box = markedBox(tx, fitted.topLeft - const Offset(50, 50),
            fitted.bottomRight + const Offset(50, 50))!;
        expect(box.x, closeTo(region.left, 1e-9));
        expect(box.y, closeTo(region.top, 1e-9));
        expect(box.w, closeTo(region.width, 1e-9));
        expect(box.h, closeTo(region.height, 1e-9));
      });
    }

    test("a face entirely outside the region is not drawn", () {
      var region = const Rect.fromLTWH(0.5, 0, 0.5, 0.5);
      expect(faceInRegion(FaceInfo(x: 0.1, y: 0.1, w: 0.1, h: 0.1), region),
          isFalse);
      expect(faceInRegion(FaceInfo(x: 0.45, y: 0.1, w: 0.1, h: 0.1), region),
          isTrue);
      expect(
          faceInRegion(FaceInfo(x: 0.1, y: 0.1, w: 0.1, h: 0.1), null), isTrue);
    });
  });

  group("the editor's rectangle", () {
    // A 2:1 picture.
    const picture = 2.0;

    test("drawn freely is spanned between the two points", () {
      expectRect(
        drawnCrop(
            const Offset(0.6, 0.6), const Offset(0.2, 0.1), null, picture),
        const Rect.fromLTRB(0.2, 0.1, 0.6, 0.6),
      );
    });

    test("drawn under a ratio keeps the ratio and stays inside", () {
      var rect =
          drawnCrop(const Offset(0.5, 0.5), const Offset(2, 2), 1, picture);
      // 1:1 in pixels is w * 2 = h in normalised units.
      expect(rect.width * picture / rect.height, closeTo(1, 1e-9));
      expect(rect.right, lessThanOrEqualTo(1 + 1e-9));
      expect(rect.bottom, lessThanOrEqualTo(1 + 1e-9));
      expect(rect.topLeft, const Offset(0.5, 0.5));
    });

    test("moved stays inside the picture", () {
      var rect = movedCrop(
          const Rect.fromLTWH(0.2, 0.2, 0.3, 0.3), const Offset(5, -5));
      expectRect(rect, const Rect.fromLTWH(0.7, 0, 0.3, 0.3));
    });

    test("resized at a corner under each constraint", () {
      var start = const Rect.fromLTWH(0.2, 0.2, 0.4, 0.4);
      for (var aspect in CropAspect.values) {
        for (var portrait in [false, true]) {
          var ratio = targetRatio(aspect, portrait, picture);
          var rect = resizedCrop(start, CropHandle.bottomRight,
              const Offset(0.9, 0.7), ratio, picture);
          expect(rect.left, closeTo(0.2, 1e-9),
              reason: "the opposite corner stays ($aspect)");
          expect(rect.top, closeTo(0.2, 1e-9),
              reason: "the opposite corner stays ($aspect)");
          expect(rect.right, lessThanOrEqualTo(1 + 1e-9));
          expect(rect.bottom, lessThanOrEqualTo(1 + 1e-9));
          if (ratio != null) {
            expect(rect.width * picture / rect.height, closeTo(ratio, 1e-9),
                reason: "$aspect portrait: $portrait");
          } else {
            expectRect(rect, const Rect.fromLTRB(0.2, 0.2, 0.9, 0.7));
          }
        }
      }
    });

    test("resized at an edge keeps the other axis centred under a ratio", () {
      var start = const Rect.fromLTWH(0.2, 0.2, 0.2, 0.4);
      var rect = resizedCrop(
          start, CropHandle.right, const Offset(0.5, 0.4), 1, picture);
      expect(rect.left, 0.2);
      expect(rect.width, closeTo(0.3, 1e-9));
      expect(rect.height, closeTo(0.6, 1e-9));
      expect(rect.center.dy, closeTo(start.center.dy, 1e-9));
      var free = resizedCrop(
          start, CropHandle.top, const Offset(0.9, 0.05), null, picture);
      expectRect(free, const Rect.fromLTRB(0.2, 0.05, 0.4, 0.6));
    });

    test("never smaller than the least side", () {
      var rect = resizedCrop(const Rect.fromLTWH(0.2, 0.2, 0.4, 0.4),
          CropHandle.bottomRight, const Offset(0, 0), null, picture);
      expect(rect.width, closeTo(minCropSide, 1e-9));
      expect(rect.height, closeTo(minCropSide, 1e-9));
    });

    test("the toggle turns the ratio and keeps the centre", () {
      var start = const Rect.fromLTWH(0.3, 0.25, 0.4, 0.5);
      var landscape = targetRatio(CropAspect.fourThree, false, picture)!;
      var portrait = targetRatio(CropAspect.fourThree, true, picture)!;
      expect(landscape, closeTo(4 / 3, 1e-9));
      expect(portrait, closeTo(3 / 4, 1e-9));
      var turned = withRatio(start, portrait, picture);
      expect(turned.width * picture / turned.height, closeTo(3 / 4, 1e-9));
      expect(turned.center.dx, closeTo(start.center.dx, 1e-9));
      expect(turned.center.dy, closeTo(start.center.dy, 1e-9));
      // The image ratio is the picture's own, either way round.
      expect(targetRatio(CropAspect.image, false, picture), 2);
      expect(targetRatio(CropAspect.image, true, picture), 0.5);
      expect(targetRatio(CropAspect.free, true, picture), isNull);
    });
  });

  group("the album picture", () {
    test("is measured on the cropped photograph", () {
      // 2000 x 1000, the left quarter: 500 x 1000, a portrait.
      var info = indexPictureOf(photo(crop: Crop(x: 0, y: 0, w: 0.25, h: 1)));
      expect(info.scale, 2.0);
      expect(info.ty, closeTo((1000 - 500) / 1000 * (indexPictureTileSize / 2), 1e-9));
      // Whole, the landscape fills the square by its height.
      expect(indexPictureOf(photo()).scale, 2.0);
      expect(indexPictureOf(photo()).ty, 0);
      // And held to covering the square by the region's size.
      expect(
        leastIndexPictureScale(info, 500, 1000),
        2.0,
      );
    });
  });
}
