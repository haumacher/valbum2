/// Probe for issue #154: whatever the crop editor's gestures do, the index
/// picture covers the whole square tile.
///
/// The picture's four corners are mapped through the very transform the tile
/// draws with ([thumbnailTransform], about the centre as
/// `Transform(alignment: Alignment.center)` applies it, the `map` helper of
/// `listing_probe_test.dart`) after arbitrary sequences of pans and zooms,
/// for a landscape and a portrait file in every orientation.
library;

import 'dart:math';

import 'package:flutter/widgets.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/listing_view.dart';
import 'package:valbum_ui/resource.dart';

/// Applies the tile transform about the tile centre to a point in tile
/// coordinates.
Offset map(Matrix4 m, double tile, double x, double y) {
  var c = tile / 2;
  var full = Matrix4.translationValues(c, c, 0)
    ..multiply(m)
    ..multiply(Matrix4.translationValues(-c, -c, 0));
  return MatrixUtils.transformPoint(full, Offset(x, y));
}

/// Fails unless the picture of [image] under [info] covers a tile of [tile].
void expectCovers(ImagePart image, ThumbnailInfo info, double tile,
    {String reason = ""}) {
  var swapped = PlaneTransform.of(info.orientation).swapsDimensions;
  var w = (swapped ? image.height : image.width).toDouble();
  var h = (swapped ? image.width : image.height).toDouble();
  var longer = max(w, h);
  // BoxFit.contain, centred: the picture's rectangle in the tile before the
  // crop transform.
  var pw = tile * w / longer, ph = tile * h / longer;
  var left = (tile - pw) / 2, top = (tile - ph) / 2;
  var m = thumbnailTransform(info, tile);
  var corners = [
    map(m, tile, left, top),
    map(m, tile, left + pw, top),
    map(m, tile, left, top + ph),
    map(m, tile, left + pw, top + ph),
  ];
  const eps = 1e-6;
  var why = "$reason: scale ${info.scale}, tx ${info.tx}, ty ${info.ty}";
  expect(corners.map((c) => c.dx).reduce(min), lessThanOrEqualTo(eps),
      reason: "left edge uncovered, $why");
  expect(corners.map((c) => c.dy).reduce(min), lessThanOrEqualTo(eps),
      reason: "top edge uncovered, $why");
  expect(corners.map((c) => c.dx).reduce(max), greaterThanOrEqualTo(tile - eps),
      reason: "right edge uncovered, $why");
  expect(corners.map((c) => c.dy).reduce(max), greaterThanOrEqualTo(tile - eps),
      reason: "bottom edge uncovered, $why");
}

void main() {
  final pictures = [
    for (var orientation in Orientation.values) ...[
      ImagePart(
          name: "l.jpg", width: 2048, height: 1536, orientation: orientation),
      ImagePart(
          name: "p.jpg", width: 1536, height: 2048, orientation: orientation),
      ImagePart(
          name: "pano.jpg", width: 4000, height: 900, orientation: orientation),
    ],
  ];

  for (var image in pictures) {
    var label = "${image.name} ${image.orientation.name}";

    test('the default crop covers the square: $label', () {
      for (var tile in [150.0, 200.0, 300.0]) {
        expectCovers(image, indexPictureOf(image), tile, reason: label);
      }
    });

    test('any gesture sequence keeps the square covered: $label', () {
      var random = Random(label.hashCode);
      for (var run = 0; run < 50; run++) {
        var info = indexPictureOf(image);
        for (var step = 0; step < 20; step++) {
          if (random.nextBool()) {
            info = panIndexPicture(
              info,
              (random.nextDouble() - 0.5) * 600,
              (random.nextDouble() - 0.5) * 600,
              200,
              width: image.width,
              height: image.height,
            );
          } else {
            // Wheel steps, buttons and pinches alike, out as well as in.
            info = zoomIndexPicture(
              info,
              0.2 + random.nextDouble() * 2.5,
              width: image.width,
              height: image.height,
            );
          }
          expect(info.orientation, image.orientation);
          expectCovers(image, info, 200, reason: "$label run $run step $step");
          expectCovers(image, info, 300, reason: "$label run $run step $step");
        }
      }
    });

    test('an uncovering stored crop is pulled in once it is touched: $label',
        () {
      var stored = ThumbnailInfo(
        image: image.name,
        scale: 1,
        tx: 120,
        ty: -140,
        orientation: image.orientation,
      );
      var touched = panIndexPicture(stored, 0, 0, 200,
          width: image.width, height: image.height);
      expectCovers(image, touched, 200, reason: label);
      expect(stored.tx, 120, reason: "the stored crop itself is left alone");
    });
  }
}
