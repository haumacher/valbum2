/// Probe of the block move of a multi-selection (issue #41), composed with
/// groups, headings, hidden parts and the corner cases of the block itself.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

List<String> names(AlbumInfo album) => [
      for (var part in album.parts)
        part is Heading
            ? "H(${part.text})"
            : part is ImagePart
                ? part.name
                : "G",
    ];

ImagePart image(String name) =>
    ImagePart(name: name, width: 2000, height: 1000);

void main() {
  test('a block of a heading and a group moves intact behind an image', () {
    var h1 = Heading(text: "one");
    var a = image("a.jpg");
    var group = ImageGroup(representative: 1, images: [
      image("g1.jpg"),
      image("g2.jpg"),
    ]);
    var video = ImagePart(
        name: "v.mp4", kind: ImageKind.video, rating: -1, width: 1, height: 1);
    var c = image("c.jpg");
    var h2 = Heading(text: "two");
    var album = AlbumInfo(parts: [h1, a, group, video, c, h2]);
    AlbumInitializer().init(album);

    // The block {h1, group} lands behind c; the hidden video stays where the
    // remaining parts leave it, and the group is not dissolved.
    expect(moveParts(album, [group, h1], c), isTrue);
    expect(names(album), ["a.jpg", "v.mp4", "c.jpg", "H(one)", "G", "H(two)"]);
    expect(group.images.map((i) => i.name), ["g1.jpg", "g2.jpg"]);
    expect(group.representative, 1);
    expect(group.owner, same(album));
    expect(c.next, same(group));
    expect(group.previous, same(c));
  });

  test('a block holding every part cannot move anywhere', () {
    var a = image("a.jpg");
    var b = image("b.jpg");
    var c = image("c.jpg");
    var album = AlbumInfo(parts: [a, b, c]);
    AlbumInitializer().init(album);

    expect(moveParts(album, [a, b, c], b), isFalse);
    expect(moveParts(album, [c, a, b], null), isFalse);
    expect(names(album), ["a.jpg", "b.jpg", "c.jpg"]);
  });

  test('a scattered block moved to the very beginning keeps its order', () {
    var a = image("a.jpg");
    var b = image("b.jpg");
    var c = image("c.jpg");
    var d = image("d.jpg");
    var e = image("e.jpg");
    var album = AlbumInfo(parts: [a, b, c, d, e]);
    AlbumInitializer().init(album);

    expect(moveParts(album, [e, b], null), isTrue);
    expect(names(album), ["b.jpg", "e.jpg", "a.jpg", "c.jpg", "d.jpg"]);
    expect(b.previous, isNull);
    expect(e.next, same(a));
  });

  test('a part named twice in the block is moved once', () {
    var a = image("a.jpg");
    var b = image("b.jpg");
    var c = image("c.jpg");
    var album = AlbumInfo(parts: [a, b, c]);
    AlbumInitializer().init(album);

    expect(moveParts(album, [a, a], c), isTrue);
    expect(names(album), ["b.jpg", "c.jpg", "a.jpg"]);
    expect(album.parts.length, 3);
  });

  test('the block move survives a round trip through JSON', () {
    var a = image("a.jpg");
    var b = image("b.jpg");
    var h = Heading(text: "H");
    var c = image("c.jpg");
    var album = AlbumInfo(parts: [a, b, h, c]);
    AlbumInitializer().init(album);

    expect(moveParts(album, [h, a], c), isTrue);
    var copy = Resource.read(JsonReader.fromString(album.toString())) as AlbumInfo;
    expect(names(copy), ["b.jpg", "c.jpg", "a.jpg", "H(H)"]);
  });
}
