import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_model.dart';
import 'package:valbum_ui/image_transform.dart';
import 'package:valbum_ui/resource.dart';
import 'package:vector_math/vector_math_64.dart';

// Probe review for issue #21: orientation composed with repeated zooms about
// different points, and filter-aware navigation over a trailing group.
void main() {
  test('rotated image: the screen point stays fixed across zooms about it', () {
    var t = ImageTransform.fit(
      orientation: Orientation.rotL,
      rawWidth: 3000,
      rawHeight: 2000,
      pageWidth: 800,
      pageHeight: 600,
    );
    // Rotated: displayed 2000x3000 fitted into 800x600 → scale 0.2, 400x600.
    expect(t.scale, closeTo(0.2, 1e-12));
    expect(t.tx, closeTo(200, 1e-9));

    // Image-space point under screen (300, 100) before zooming.
    double imgX() => (300 - t.tx) / t.scale;
    double imgY() => (100 - t.ty) / t.scale;
    var x0 = imgX(), y0 = imgY();
    t.zoom(1, 300, 100);
    t.zoom(1, 300, 100);
    expect(imgX(), closeTo(x0, 1e-9));
    expect(imgY(), closeTo(y0, 1e-9));
    // A zoom about another point keeps *that* point instead.
    var x1 = (500 - t.tx) / t.scale, y1 = (400 - t.ty) / t.scale;
    t.zoom(1, 500, 400);
    expect((500 - t.tx) / t.scale, closeTo(x1, 1e-9));
    expect((400 - t.ty) / t.scale, closeTo(y1, 1e-9));
    // Zooming out three times returns exactly to the fitted state.
    t.zoom(-1, 0, 0);
    t.zoom(-1, 0, 0);
    t.zoom(-1, 0, 0);
    expect(t.isInitial, isTrue);
    expect(t.scale, closeTo(t.fitScale, 1e-12));
    expect(t.tx, closeTo(t.fitTx, 1e-9));

    // The matrix maps the raw image's corners onto the fitted 400x600 box.
    var m = t.matrix;
    var corners = [
      Vector3(0, 0, 0),
      Vector3(3000, 0, 0),
      Vector3(0, 2000, 0),
      Vector3(3000, 2000, 0),
    ].map(m.transform3).toList();
    var xs = corners.map((c) => c.x), ys = corners.map((c) => c.y);
    expect(xs.reduce((a, b) => a < b ? a : b), closeTo(200, 1e-6));
    expect(xs.reduce((a, b) => a > b ? a : b), closeTo(600, 1e-6));
    expect(ys.reduce((a, b) => a < b ? a : b), closeTo(0, 1e-6));
    expect(ys.reduce((a, b) => a > b ? a : b), closeTo(600, 1e-6));
  });

  test('lastVisible skips a trailing group whose representative is hidden', () {
    ImagePart part(String n, int rating) {
      var p = ImagePart();
      p.name = n;
      p.width = 300;
      p.height = 200;
      p.rating = rating;
      return p;
    }
    var a = part("a", 1);
    var b = part("b", 0);
    var group = ImageGroup();
    group.images = [part("g1", -1), part("g2", 2)];
    group.representative = 0; // hidden at minRating 0
    var album = AlbumInfo();
    album.parts = [a, b, group];
    AlbumInitializer().init(album);

    expect(lastVisible(endOf(a), 0), same(b));
    expect(lastVisible(endOf(a), -1), same(group));
    expect(nextVisible(b, 0), isNull);
    expect(nextVisible(b, -1), same(group));
    expect(firstVisible(homeOf(group), 2), isNull);
  });
}
