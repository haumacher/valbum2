/// Probe for the recording-time adjustment (issue #77), composed with the
/// sort of #76, a group's representative, a video, a backwards move across a
/// heading, and the sidecar round trip.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/resource.dart';

const int h = 3600 * 1000;
const int d = 24 * h;

ImagePart img(String name, int date, {ImageKind kind = ImageKind.image}) =>
    ImagePart(name: name, width: 4, height: 3, date: date, kind: kind);

String label(AlbumPart part) => part is Heading
    ? "H(${part.text})"
    : part is ImageGroup
        ? "G(${part.images.map((i) => i.name).join(",")}|${part.images[part.representative].name})"
        : (part as ImagePart).name;

List<String> labels(AlbumInfo album) => [for (var p in album.parts) label(p)];

void main() {
  test('the representative of a group of three is adjusted away and re-pointed', () {
    var r = img("r.jpg", 10 * h), s = img("s.jpg", 11 * h), t = img("t.jpg", 12 * h);
    var group = ImageGroup(images: [r, s, t], representative: 2);
    var album = AlbumInfo(parts: [img("a.jpg", 9 * h), group, img("z.jpg", 20 * h), Heading(text: "Late"), img("y.jpg", 2 * d)]);

    expect(adjustRecordingTime(album, {t}, const Duration(days: 2, hours: 1)), isTrue);
    expect(labels(album), ["a.jpg", "G(r.jpg,s.jpg|r.jpg)", "z.jpg", "H(Late)", "y.jpg", "t.jpg"]);
    expect(t.date, 12 * h + 2 * d + h);
    expect(r.date, 10 * h, reason: "unselected members untouched");
  });

  test('a video moves backwards across a heading and the album can be sorted afterwards', () {
    var v = img("v.mp4", 3 * d, kind: ImageKind.video);
    var album = AlbumInfo(parts: [
      img("a.jpg", 1 * d), img("b.jpg", 1 * d + h), Heading(text: "Day 3"), img("c.jpg", 3 * d - h), v,
    ]);
    expect(adjustRecordingTime(album, {v}, const Duration(days: -2)), isTrue);
    expect(v.date, 1 * d);
    expect(labels(album), ["a.jpg", "v.mp4", "b.jpg", "H(Day 3)", "c.jpg"],
        reason: "after the last entry not later than it: a.jpg at exactly the same time");
    // Composed with #76: sorting the sections afterwards changes nothing.
    expect(sortSectionsByDate(album), isFalse);
  });

  test('selecting a whole group adjusts all its images and keeps them a group', () {
    var g1 = img("g1.jpg", 5 * h), g2 = img("g2.jpg", 6 * h);
    var group = ImageGroup(images: [g1, g2], representative: 1);
    var album = AlbumInfo(parts: [img("a.jpg", 1 * h), group, img("b.jpg", 10 * h)]);
    expect(adjustRecordingTime(album, {group}, const Duration(hours: 6)), isTrue);
    expect(g1.date, 11 * h);
    expect(g2.date, 12 * h);
    // Both left the group: two images re-inserted after b.jpg; the group is gone.
    expect(labels(album), ["a.jpg", "b.jpg", "g1.jpg", "g2.jpg"]);
  });

  test('the adjusted album survives the sidecar round trip', () {
    var x = img("x.jpg", 5 * h);
    var album = AlbumInfo(title: "Probe", parts: [img("a.jpg", 1 * h), x, img("b.jpg", 10 * h)]);
    adjustRecordingTime(album, {x}, const Duration(hours: 6));
    var copy = Resource.fromString(album.toString()) as AlbumInfo;
    expect(labels(copy), ["a.jpg", "b.jpg", "x.jpg"]);
    expect((copy.parts[2] as ImagePart).date, 11 * h);
  });

  test('the offset in words for odd durations', () {
    expect(offsetInWords(const Duration(seconds: 59)), "+59 s");
    expect(offsetInWords(const Duration(days: 1, seconds: 1)), "+1 d 0 h 0 min 1 s");
    expect(offsetInWords(const Duration(days: 1, seconds: 1) * -1), "−1 d 0 h 0 min 1 s");
  });
}
