/// Probe for the heading anchor of issue #71, composed with hidden parts, a
/// heading as the tile, a display order that moved a part across the block, a
/// stale display order, and the sidecar round trip.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

ImagePart img(String name, int width, int height) =>
    ImagePart(name: name, width: width, height: height);
ImagePart landscape(String name) => img(name, 2048, 1536);
ImagePart portrait(String name) => img(name, 1536, 2048);
String partName(AlbumPart part) =>
    part is Heading ? "Heading(${part.text})" : (part as ImagePart).name;
List<String> names(AlbumInfo album) =>
    [for (var part in album.parts) partName(part)];

void main() {
  test('a part the filter hides is not displayed after anything', () {
    var a = landscape("A"), p = portrait("P"), x = landscape("X"), b = landscape("B");
    var album = AlbumInfo(parts: [a, p, x, b]);
    // P is hidden by the rating filter: the display order does not hold it.
    var index = insertHeadingBeforeDisplayed(album, x, [a, x, b], "T");
    expect(index, 2);
    expect(names(album), ["A", "P", "Heading(T)", "X", "B"]);
  });

  test('a part moved across the block still follows the heading', () {
    var p = portrait("P"), a = landscape("A"), x = landscape("X");
    var album = AlbumInfo(parts: [p, a, x]);
    var index = insertHeadingBeforeDisplayed(album, x, [a, x, p], "T");
    expect(index, 0, reason: "P is displayed after X and stored first");
    expect(names(album), ["Heading(T)", "P", "A", "X"]);
  });

  test('a heading tile is a tile like any other', () {
    var h1 = Heading(text: "One"), l = landscape("L"), p = portrait("P");
    var album = AlbumInfo(parts: [h1, l, p]);
    var index = insertHeadingBeforeDisplayed(album, h1, [h1, p, l], "T");
    expect(index, 0);
    expect(names(album), ["Heading(T)", "Heading(One)", "L", "P"]);
    // And before the portrait displayed right after the heading: between them.
    index = insertHeadingBeforeDisplayed(album, p, [album.parts[0], h1, p, l], "U");
    expect(index, 2);
    expect(names(album), ["Heading(T)", "Heading(One)", "Heading(U)", "L", "P"]);
  });

  test('a stale display order lacking the first heading gives the same result', () {
    var l1 = landscape("L1"), l2 = landscape("L2"), p = portrait("P");
    var album = AlbumInfo(parts: [l1, l2, p]);
    var stale = <AlbumPart>[p, l1, l2];
    expect(insertHeadingBeforeDisplayed(album, p, stale, "First"), 0);
    // The view has not rebuilt yet; the same order is handed in again.
    expect(insertHeadingBeforeDisplayed(album, p, stale, "Second"), 1);
    expect(names(album), ["Heading(First)", "Heading(Second)", "L1", "L2", "P"]);
  });

  test('the order survives the sidecar round trip', () {
    var l1 = landscape("L1"), l2 = landscape("L2"), p = portrait("P");
    var album = AlbumInfo(title: "Probe", parts: [l1, l2, p]);
    insertHeadingBeforeDisplayed(album, p, [p, l1, l2], "Am Morgen");
    var copy = Resource.fromString(album.toString()) as AlbumInfo;
    expect(names(copy), ["Heading(Am Morgen)", "L1", "L2", "P"]);
  });
}
