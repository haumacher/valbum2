/// Tests of WebP and GIF pictures in the viewer (issue #190).
library;

import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/thumbnails.dart';

const String base = "http://server/valbum/data/album";

void main() {
  group('the viewer', () {
    test(
      'shows a WebP and a GIF original itself, never a display rendition',
      () {
        // Every browser and the app's own codec decode both (an animated one
        // plays), so they are originals as a JPEG is; only a HEIC needs the
        // server's display rendition.
        var client = VAlbumClient(dataUrl: "http://server/valbum/data");
        for (var name in ["a.webp", "B.WEBP", "c.gif", "D.GIF"]) {
          expect(isHeifName(name), isFalse);
          var picture = viewerPicture(client, "$base/$name", mayDownload: true);
          expect(picture, isA<NetworkImage>());
          expect((picture as NetworkImage).url, "$base/$name");
          // Without download, the preview, as for a JPEG.
          expect(
            viewerPicture(client, "$base/$name", mayDownload: false),
            isA<ThumbnailImage>(),
          );
        }
      },
    );
  });
}
