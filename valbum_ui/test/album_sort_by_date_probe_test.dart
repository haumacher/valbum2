/// Probe for "Sort by date" (issue #76): mixed kinds, empty sections, the
/// representative of a group, idempotence and the sidecar round trip.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/resource.dart';

ImagePart img(String name, int date, {ImageKind kind = ImageKind.image}) =>
    ImagePart(name: name, width: 4, height: 3, date: date, kind: kind);

String label(AlbumPart part) => part is Heading
    ? "H(${part.text})"
    : part is ImageGroup
        ? "G(${part.images.map((i) => i.name).join(",")})"
        : (part as ImagePart).name;

List<String> labels(AlbumInfo album) => [for (var p in album.parts) label(p)];

void main() {
  test('videos sort among photos, empty and trailing sections survive', () {
    var album = AlbumInfo(parts: [
      Heading(text: "One"),
      Heading(text: "Two"),
      img("c.jpg", 30),
      img("v.mp4", 20, kind: ImageKind.video),
      img("a.jpg", 10),
      Heading(text: "Three"),
    ]);
    expect(sortSectionsByDate(album), isTrue);
    expect(labels(album), ["H(One)", "H(Two)", "a.jpg", "v.mp4", "c.jpg", "H(Three)"]);
    expect(sortSectionsByDate(album), isFalse, reason: "sorting twice changes nothing");
  });

  test('a group keeps showing its representative and the set of parts is unchanged', () {
    var group = ImageGroup(
      images: [img("g3.jpg", 300), img("g1.jpg", 100), img("g2.jpg", 200)],
      representative: 1,
    );
    var album = AlbumInfo(parts: [img("z.jpg", 250), group, img("y.jpg", 50)]);
    var before = labels(album).join(",").split(RegExp(r"[,()]")).where((s) => s.endsWith(".jpg")).toSet();

    expect(sortSectionsByDate(album), isTrue);
    expect(labels(album), ["y.jpg", "G(g1.jpg,g2.jpg,g3.jpg)", "z.jpg"]);
    expect(group.images[group.representative].name, "g1.jpg",
        reason: "the representative follows its image");
    var after = labels(album).join(",").split(RegExp(r"[,()]")).where((s) => s.endsWith(".jpg")).toSet();
    expect(after, before);
  });

  test('a group of undated images goes to the end of its section, a mixed one by its dated image', () {
    var undated = ImageGroup(images: [img("u1.jpg", 0), img("u2.jpg", 0)], representative: 0);
    var mixed = ImageGroup(images: [img("m1.jpg", 0), img("m2.jpg", 15)], representative: 0);
    var album = AlbumInfo(parts: [undated, img("b.jpg", 20), mixed, img("a.jpg", 10)]);
    expect(sortSectionsByDate(album), isTrue);
    expect(labels(album), ["a.jpg", "G(m2.jpg,m1.jpg)", "b.jpg", "G(u1.jpg,u2.jpg)"],
        reason: "inside a group the undated image goes last as well");
    expect(dateOf(mixed), 15);
    expect(dateOf(undated), 0);
  });

  test('the sorted order survives the sidecar round trip', () {
    var album = AlbumInfo(title: "Probe", parts: [
      img("c.jpg", 30),
      Heading(text: "Later"),
      img("e.jpg", 50),
      img("d.jpg", 40),
      img("a.jpg", 10),
    ]);
    sortSectionsByDate(album);
    var copy = Resource.fromString(album.toString()) as AlbumInfo;
    expect(labels(copy), ["c.jpg", "H(Later)", "a.jpg", "d.jpg", "e.jpg"]);
  });
}
