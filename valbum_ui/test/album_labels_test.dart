/// The labels of an album as pure functions (issue #213): adding and taking
/// off on a selection, rename and removal across the album, the counts of the
/// chips, and the stored form of the server's fixture.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_labels.dart';
import 'package:valbum_ui/resource.dart';

/// The stored form both toolchains read, relative to `valbum_ui/`.
const String fixturePath = "../image-server/src/test/fixtures/labels/index.json";

ImagePart _photo(String name, [List<String> labels = const []]) => ImagePart(
      name: name,
      labels: [for (var label in labels) LabelName(name: label)],
    );

AlbumInfo _fixture() => Resource.read(
        JsonReader.fromString(File(fixturePath).readAsStringSync()))
    as AlbumInfo;

ImagePart _named(AlbumInfo album, String name) => [
      for (var part in album.parts) ...imagesOfPart(part),
    ].firstWhere((image) => image.name == name);

void main() {
  test("the stored form reads, and an older part reads as no label", () {
    var album = _fixture();
    expect(labelsOf(_named(album, "rejected.jpg")), ["Day", "Party"]);
    expect(labelsOf(_named(album, "other.jpg")), isEmpty);
  });

  test("read -> write -> read is equal", () {
    var album = _fixture();
    var written = album.toString();
    var again = Resource.read(JsonReader.fromString(written)) as AlbumInfo;
    expect(again.toString(), written);
    expect(written, contains('"labels":[{"name":"Day"},{"name":"Party"}]'));
  });

  test("the chips count every photograph, alphabetically", () {
    var album = _fixture();
    expect(albumLabels(album.parts), [
      (label: "Day", count: 4),
      (label: "Party", count: 2),
    ]);
    expect(
      albumLabels(album.parts,
          shown: (part) => imagesOfPart(part).every((i) => i.rating >= 0)),
      [(label: "Day", count: 3), (label: "Party", count: 1)],
      reason: "the count is what a tap shows under the rating filter",
    );
    expect(
      albumLabels([_photo("a", ["b"]), _photo("c", ["B", "a"])])
          .map((entry) => entry.label),
      ["a", "B", "b"],
    );
  });

  test("a label is added to and taken off a selection, groups member by member",
      () {
    var a = _photo("a.jpg");
    var b = _photo("b.jpg", ["Day"]);
    var group = ImageGroup(images: [_photo("c.jpg"), _photo("d.jpg")]);
    expect(addLabel([a, b, group], "Day"), isTrue);
    expect(labelsOf(a), ["Day"]);
    expect(labelsOf(b), ["Day"], reason: "never twice");
    expect([for (var image in group.images) labelsOf(image)], [
      ["Day"],
      ["Day"],
    ]);
    expect(addLabel([a], "Day"), isFalse);
    expect(labelCoverage([a, b, group], "Day"), (carrying: 4, total: 4));

    expect(removeLabel([a, group], "Day"), isTrue);
    expect(labelsOf(a), isEmpty);
    expect(labelsOf(b), ["Day"], reason: "outside the selection");
    expect(labelCoverage([a, b], "Day"), (carrying: 1, total: 2));
    expect(removeLabel([a], "Day"), isFalse);
  });

  test("a rename acts on the whole album and keeps the place; empty removes",
      () {
    var album = _fixture();
    renameLabelIn(album.parts, "Day", "Day with Anna");
    expect(labelsOf(_named(album, "rejected.jpg")), ["Day with Anna", "Party"]);
    expect(labelsOf(_named(album, "public.jpg")), ["Day with Anna"]);

    renameLabelIn(album.parts, "Party", "Day with Anna");
    expect(labelsOf(_named(album, "rejected.jpg")), ["Day with Anna"],
        reason: "a photograph carrying both keeps one");

    renameLabelIn(album.parts, "Day with Anna", "");
    expect(albumLabels(album.parts), isEmpty);
  });

  test("the server's answer is taken over by name", () {
    var shown = _fixture();
    var answer = _fixture();
    renameLabelIn(answer.parts, "Day", "Night");
    applyLabelsFrom(shown, answer);
    expect(labelsOf(_named(shown, "members.jpg")), ["Night"]);
  });

  test("the viewer pages through what the chip shows", () {
    var album = AlbumInfo(parts: [
      _photo("a.jpg", ["Day"]),
      _photo("b.jpg"),
    ]);
    for (var part in album.parts) {
      part.owner = album;
    }
    expect(shownByLabelFilter(album.parts[1] as AbstractImage), isTrue);
    setLabelFilter(album, "Day");
    expect(shownByLabelFilter(album.parts[0] as AbstractImage), isTrue);
    expect(shownByLabelFilter(album.parts[1] as AbstractImage), isFalse);
    setLabelFilter(album, null);
    expect(labelFilterOf(album), isNull);
  });
}
