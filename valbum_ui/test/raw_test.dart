/// Raw photographs in the viewer (issue #191): a raw standing alone is shown
/// by the server's display rendition of the JPEG preview it carries, as a
/// HEIC is, because no browser and no app decoder reads a raw.
library;

import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/media_picker.dart';
import 'package:valbum_ui/thumbnails.dart';

const String base = "http://server/valbum/data/album";

void main() {
  test('a raw is shown by its display rendition, never the original', () {
    var client = VAlbumClient(dataUrl: "http://server/valbum/data");
    for (var name in [
      "solo.DNG",
      "IMG_2.cr2",
      "a.CR3",
      "b.nef",
      "c.ARW",
      "d.orf",
      "e.RW2",
      "f.raf"
    ]) {
      var picture = viewerPicture(client, "$base/$name", mayDownload: true);
      expect(picture, isA<NetworkImage>());
      expect((picture as NetworkImage).url, "$base/$name?type=display");
    }
    // Without download a raw is shown by its preview, as a JPEG is.
    expect(viewerPicture(client, "$base/solo.DNG", mayDownload: false),
        isA<ThumbnailImage>());
  });

  test('knows a raw by its extension in any case', () {
    expect(isRawName("a.dng"), isTrue);
    expect(isRawName("a.Cr2"), isTrue);
    expect(isRawName("a.jpg"), isFalse);
    expect(isRawName("dng"), isFalse);
    expect(needsDisplayRendition("a.heic"), isTrue);
    expect(needsDisplayRendition("a.nef"), isTrue);
    expect(needsDisplayRendition("a.png"), isFalse);
  });

  test('the picker offers every raw the app knows', () {
    expect(uploadExtensions.toSet().containsAll(rawExtensions), isTrue);
  });
}
