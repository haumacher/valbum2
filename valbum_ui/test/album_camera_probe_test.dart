/// Probe for the camera selection (issue #78), composed with the adjustment of
/// #77, exact-label semantics and the sidecar round trip.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/resource.dart';

ImagePart img(String name, int date, String camera) =>
    ImagePart(name: name, width: 4, height: 3, date: date, camera: camera);

void main() {
  test('labels compare exactly: the server made them, the app does not guess', () {
    var a = img("a.jpg", 1, "Canon EOS 5D");
    var b = img("b.jpg", 2, "canon eos 5d");
    var c = img("c.jpg", 3, "Canon EOS 5D ");
    var album = AlbumInfo(parts: [a, b, c]);
    expect(sameCamera(album, a), {a});
  });

  test('selecting a camera inside groups and adjusting keeps the labels and survives the round trip', () {
    var g1 = img("g1.jpg", 10, "Nikon D750"), g2 = img("g2.jpg", 11, "Apple iPhone");
    var group = ImageGroup(images: [g1, g2], representative: 0);
    var n = img("n.jpg", 5, "Nikon D750"), p = img("p.jpg", 20, "Apple iPhone");
    var album = AlbumInfo(title: "Probe", parts: [n, group, p]);

    var selection = sameCamera(album, n);
    expect(selection, {n, g1});
    expect(adjustRecordingTime(album, selection, const Duration(milliseconds: 100)), isTrue);
    expect(g1.date, 110);
    expect(n.date, 105);

    var copy = Resource.fromString(album.toString()) as AlbumInfo;
    var cameras = <String, String>{};
    for (var part in copy.parts) {
      if (part is ImagePart) {
        cameras[part.name] = part.camera;
      }
      if (part is ImageGroup) {
        for (var i in part.images) {
          cameras[i.name] = i.camera;
        }
      }
    }
    expect(cameras, {"n.jpg": "Nikon D750", "g1.jpg": "Nikon D750", "g2.jpg": "Apple iPhone", "p.jpg": "Apple iPhone"});
    // g1 left its group of two: the group dissolved into g2.
    expect(copy.parts.whereType<ImageGroup>(), isEmpty);
  });

  test('a video without a label is never gathered, even beside labelled ones', () {
    var v = ImagePart(name: "v.mp4", width: 4, height: 3, date: 7, kind: ImageKind.video);
    var a = img("a.jpg", 1, "Canon EOS 5D");
    var album = AlbumInfo(parts: [a, v]);
    expect(sameCamera(album, v), isEmpty);
    expect(sameCamera(album, a), {a});
  });
}
