/// Probe of #294: a crop of a collection photo, and of one found by a saved
/// search, is written into the album the photo lies in, under its own name —
/// never through the collection's or the search's address.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'crop_viewer_test.dart' show drawAndApply;
import 'util/fake_image_http.dart';
import 'util/photo_space.dart';
import 'viewer_follows_photo_test.dart' show openBy;

void main() {
  for (var (label, route, tile, source) in [
    ("a collection", const ListingOrAlbumRoute(["Best"]), "b.jpg", "b.jpg"),
    ("a saved search", const ListingOrAlbumRoute(["Saved"]), "trip/b.jpg",
        "b.jpg"),
  ]) {
    testWidgets('a crop from $label goes to the album the photo lies in',
        (tester) async {
      tester.view.physicalSize = const Size(1000, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var space = PhotoSpace();
      await openBy(tester, space, (label, route, tile));
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await settle(tester);
      await tester.tap(find.byKey(const Key("viewer-crop")));
      await settle(tester);
      await withFakeImageHttp(() async {
        await drawAndApply(
            tester, const Offset(0.5, 0.1), const Offset(0.9, 0.6));
      });
      await settle(tester);

      var post = space.posts("crop").single;
      expect(post.url.path, "/valbum/data/trip/");
      var sent =
          AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
      expect(sent.name, source);
    });
  }
}
