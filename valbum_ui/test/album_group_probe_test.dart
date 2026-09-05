import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

// Probe review for issues #19/#20: grouping composed with a heading in the
// selection range, the rating filter, a JSON round trip and ungrouping.
void main() {
  ImagePart part(String n, int date, int rating) {
    var p = ImagePart();
    p.name = n;
    p.width = 300;
    p.height = 200;
    p.date = date;
    p.rating = rating;
    return p;
  }

  test('group across a heading, filter by representative, round-trip, ungroup',
      () {
    var a = part("a", 30, 2);
    var h = Heading();
    h.text = "Mitte";
    var b = part("b", 10, -1);
    var c = part("c", 20, 1);
    var album = AlbumInfo();
    album.title = "T";
    album.parts = [a, h, b, c];
    AlbumInitializer().init(album);

    // Representative b has rating -1 and the latest position among the
    // selected images; a sits before the heading.
    var group = groupSelection(album, {a, b, c}, b)!;
    expect(album.parts.map((p) => p is Heading ? "H" : "G").join(),
        "HG"); // heading stays first, group takes b's slot
    expect(group.images.map((i) => i.name).toList(), ["b", "c", "a"]); // by date
    expect(group.representative, 0);
    expect(group.images.every((i) => i.group == group), isTrue);
    expect(group.owner, same(album));

    // The group is rated like its representative: hidden at minRating 0.
    album.minRating = 0;
    expect(visibleParts(album).whereType<AbstractImage>(), isEmpty);
    album.minRating = -1;
    expect(visibleParts(album).whereType<AbstractImage>().single, same(group));

    // Round trip through the generated writer and reader.
    var json = album.toString();
    var loaded = Resource.read(JsonReader.fromString(json)) as AlbumInfo;
    AlbumInitializer().init(loaded);
    var lg = loaded.parts.whereType<ImageGroup>().single;
    expect(lg.representative, 0);
    expect(lg.images.map((i) => i.name).toList(), ["b", "c", "a"]);
    expect(lg.images[0].next, same(lg.images[1]));
    expect(lg.images[2].next, isNull);
    expect(lg.previous, isNull); // the heading is not an image

    // Ungrouping the reloaded album restores the members in stored order.
    var members = ungroup(loaded, lg);
    expect(members.map((i) => i.name).toList(), ["b", "c", "a"]);
    expect(loaded.parts.map((p) => p is Heading ? "H" : (p as ImagePart).name)
        .join(","), "H,b,c,a");
    expect(members.every((i) => i.group == null), isTrue);
    expect(members[0].next, same(members[1]));
  });

  test('grouping refuses a single image and a foreign representative', () {
    var a = part("a", 1, 0);
    var b = part("b", 2, 0);
    var album = AlbumInfo();
    album.parts = [a, b];
    AlbumInitializer().init(album);
    expect(groupSelection(album, {a}, a), isNull);
    expect(groupSelection(album, {a}, b), isNull);
    expect(album.parts.length, 2);
  });
}
