/// Probe of the row-wise double-height sections (issue #44): the shape of the
/// bug report generalised — a portrait followed by landscapes, every image
/// moved behind every displayed predecessor — and the section order under
/// rotated images and groups.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_layout.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

List<String> displayed(AlbumInfo album, double width, double height) => [
      for (var image in AlbumLayout(
        width,
        height,
        album.parts.whereType<AbstractImage>(),
      ).getAllImages())
        image is ImagePart ? image.name : "G",
    ];

void main() {
  test('a landscape dropped behind a displayed neighbour lands right behind it',
      () {
    for (var count = 2; count <= 8; count++) {
      for (var width in [320.0, 768.0, 1280.0, 1920.0]) {
        for (var height in [250.0, 400.0]) {
          for (var from = 0; from < count; from++) {
            for (var to = -1; to < count; to++) {
              if (to == from) continue;
              var album = AlbumInfo(parts: [
                ImagePart(name: "P", width: 1536, height: 2048),
                for (var i = 0; i < count; i++)
                  ImagePart(name: "L$i", width: 2048, height: 1536),
              ]);
              AlbumInitializer().init(album);
              var moved = album.parts[1 + from];
              // to == -1: behind the portrait itself.
              var predecessor = album.parts[1 + to];
              if (!movePart(album, moved, predecessor)) continue;

              var shown = displayed(album, width, height);
              var at = shown.indexOf("L$from");
              expect(shown[at - 1], to < 0 ? "P" : "L$to",
                  reason:
                      "count=$count width=$width height=$height from=$from to=$to: $shown");
            }
          }
        }
      }
    }
  });

  test('rotated images and a group keep the stored order inside a section',
      () {
    var group = ImageGroup(representative: 0, images: [
      ImagePart(name: "g1", width: 2048, height: 1536),
      ImagePart(name: "g2", width: 1536, height: 2048),
    ]);
    var album = AlbumInfo(parts: [
      // Portrait pixels shown as landscape, and landscape pixels as portrait.
      ImagePart(name: "R", width: 1536, height: 2048, orientation: Orientation.rotL),
      ImagePart(name: "P", width: 2048, height: 1536, orientation: Orientation.rotR),
      ImagePart(name: "L1", width: 2048, height: 1536),
      group,
      ImagePart(name: "L2", width: 2048, height: 1536, orientation: Orientation.rot180),
      ImagePart(name: "L3", width: 3000, height: 1000),
    ]);
    AlbumInitializer().init(album);

    for (var width in [768.0, 1280.0, 1920.0]) {
      var layout = AlbumLayout(width, 400, album.parts.whereType<AbstractImage>());
      for (var row in layout) {
        for (var content in row) {
          if (content is DoubleRow) {
            var names = [
              for (var r in [content.getUpper(), content.getLower()])
                for (var c in r)
                  if (c is Img) c.getImage() is ImagePart ? (c.getImage() as ImagePart).name : "G",
            ];
            var stored = displayed(album, width, 400);
            var indices = names.map(stored.indexOf).toList();
            expect(indices, [...indices]..sort(),
                reason: "section $names at $width is not in stored order");
          }
        }
      }
    }
  });
}
