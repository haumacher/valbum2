import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_layout.dart';
import 'package:valbum_ui/resource.dart';

// Probe review for issue #12: the layout must see an ImageGroup exactly as
// its representative image, whatever the other members look like.
void main() {
  ImagePart part(String name, int w, int h,
      [Orientation o = Orientation.identity]) {
    var p = ImagePart();
    p.name = name;
    p.width = w;
    p.height = h;
    p.orientation = o;
    return p;
  }

  List<String> shape(AlbumLayout layout) {
    var out = <String>[];
    for (var row in layout) {
      out.add(row.getUnitWidth().toStringAsFixed(9));
      for (var c in row) {
        out.add("${c.runtimeType}:${c.getUnitWidth().toStringAsFixed(9)}");
      }
    }
    return out;
  }

  test('a group lays out like its representative', () {
    var landscapes = List.generate(6, (i) => part("l$i.jpg", 3000, 2000));
    var representative = part("rep.jpg", 2000, 3000, Orientation.rotL);
    var group = ImageGroup();
    group.representative = 1;
    group.images = [part("other.jpg", 4000, 1000), representative];

    var withGroup = [landscapes[0], group, ...landscapes.sublist(1)];
    var withPart = [landscapes[0], representative, ...landscapes.sublist(1)];

    for (var width in [320.0, 1280.0]) {
      var a = AlbumLayout(width, 250, withGroup);
      var b = AlbumLayout(width, 250, withPart);
      expect(shape(a), shape(b), reason: "page width $width");
      expect(a.getAllImages().length, 7);
      expect(a.getAllImages().contains(group), isTrue);
    }
  });

  test('every row fills the page width', () {
    var images = [
      for (var i = 0; i < 20; i++)
        part("i$i.jpg", i.isEven ? 3000 : 1500, i % 3 == 0 ? 3000 : 2000),
    ];
    var layout = AlbumLayout(1000, 300, images);
    for (var row in layout) {
      var sum = row.fold<double>(0, (s, c) => s + c.getUnitWidth());
      expect(sum, closeTo(row.getUnitWidth(), 1e-9));
    }
  });
}
