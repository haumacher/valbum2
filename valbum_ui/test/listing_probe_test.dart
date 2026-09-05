import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/listing_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:vector_math/vector_math_64.dart';

// Probe review for issue #23: the server's derived ThumbnailInfo (see
// ResourceCache.loadFolderInfo) must make the contained image fill the square.
void main() {
  // Applies the tile transform about the tile centre, as Transform(alignment:
  // Alignment.center) does, to a point in tile coordinates.
  Vector3 map(Matrix4 m, double tile, double x, double y) {
    var c = tile / 2;
    var full = Matrix4.translationValues(c, c, 0)
      ..multiply(m)
      ..multiply(Matrix4.translationValues(-c, -c, 0));
    return full.transform3(Vector3(x, y, 0));
  }

  test('landscape picture: server scale w/h fills the square vertically', () {
    const tile = 200.0;
    const w = 900.0, h = 600.0;
    var info = ThumbnailInfo();
    info.image = "x.jpg";
    info.scale = w / h; // what the server derives for a landscape picture
    info.tx = 0;
    info.ty = 0;
    var m = thumbnailTransform(info, tile);
    // BoxFit.contain puts the landscape image across the full width, centred.
    var containedTop = (tile - tile * h / w) / 2;
    var containedBottom = tile - containedTop;
    expect(map(m, tile, 0, containedTop).y, closeTo(0, 1e-9));
    expect(map(m, tile, tile, containedBottom).y, closeTo(tile, 1e-9));
    expect(map(m, tile, 0, containedTop).x, closeTo(-tile / 4, 1e-9));
  });

  test('portrait picture: server ty top-aligns the crop at any tile size', () {
    const w = 600.0, h = 900.0;
    var info = ThumbnailInfo();
    info.image = "x.jpg";
    info.scale = h / w;
    info.tx = 0;
    info.ty = (h - w) / h * 150; // ResourceCache formula
    for (var tile in [150.0, 300.0, 512.0]) {
      var m = thumbnailTransform(info, tile);
      // Contained portrait: full height, width tile*w/h, centred.
      expect(map(m, tile, tile / 2, 0).y, closeTo(0, 1e-6),
          reason: "top edge at tile $tile");
      var containedLeft = (tile - tile * w / h) / 2;
      expect(map(m, tile, containedLeft, 0).x, closeTo(0, 1e-6),
          reason: "left edge at tile $tile");
    }
  });
}
