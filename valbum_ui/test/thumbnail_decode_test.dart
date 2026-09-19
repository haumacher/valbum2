/// At what size a tile decodes its thumbnail (issue #93).
///
/// The server's thumbnails are 600 px high, roughly 2 MB decoded; an album
/// tile shows perhaps 200 px of that. Decoding every tile at its full size
/// fills Flutter's [ImageCache] with a few dozen images, so a large album
/// evicts its own thumbnails — and the way back from a photo pays for it. A
/// tile that knows the height it is drawn at therefore asks for that height
/// times the device's pixel ratio: never fewer pixels than are shown, and
/// never more than the thumbnail holds.
library;

import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// An album of two landscape images, which lays them out side by side.
const String twoLandscapes =
    '["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, '
    '"width": 900, "height": 600, "orientation": "IDENTITY", "rating": 0}],'
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 2, '
    '"width": 900, "height": 600, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

/// The tile showing the image of the given file name.
Finder tile(String name) => find.byWidgetPredicate((widget) =>
    widget is Image && thumbnailOf(widget.image)?.imageUrl.endsWith(name) == true);

/// The client the app is pumped with.
VAlbumClient client() => clientReturning(twoLandscapes);

void main() {
  group('the size asked for', () {
    test('is the displayed height times the device pixel ratio', () {
      var provider = resizedThumbnail(
        client(),
        "http://server/valbum/data/a.jpg",
        displayHeight: 200,
        devicePixelRatio: 2,
      ) as ResizeImage;

      expect(provider.height, 400);
      expect(provider.width, isNull);
      expect(provider.allowUpscaling, isFalse);
      expect(thumbnailOf(provider)?.imageUrl, endsWith("/a.jpg"));
    });

    test('is rounded up, so nothing is drawn from fewer pixels than it shows',
        () {
      var provider = resizedThumbnail(
        client(),
        "http://server/valbum/data/a.jpg",
        displayHeight: 133.4,
        devicePixelRatio: 1.5,
      ) as ResizeImage;

      expect(provider.height, 201);
    });
  });

  testWidgets('an album tile decodes at the height it is drawn at',
      (tester) async {
    tester.view.physicalSize = const Size(1600, 1200);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client()));
      await tester.pumpAndSettle();
    });

    var drawn = tester.getSize(tile("a.jpg"));
    var provider = tester.widget<Image>(tile("a.jpg")).image;

    expect(provider, isA<ResizeImage>());
    expect((provider as ResizeImage).height, (drawn.height * 2).ceil());
    expect(provider.allowUpscaling, isFalse);
  });

  testWidgets('the index picture of a listing is left alone', (tester) async {
    // Its transform magnifies a part of the thumbnail, so the height of the
    // box it sits in is not the height the image is drawn at.
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(fixture("listing.json"))),
      );
      await tester.pumpAndSettle();
    });

    var pictures = find
        .byWidgetPredicate(
            (widget) => widget is Image && thumbnailOf(widget.image) != null)
        .evaluate();
    expect(pictures, isNotEmpty);
    for (var picture in pictures) {
      expect((picture.widget as Image).image, isA<ThumbnailImage>());
    }
  });

  testWidgets('a tile larger than the thumbnail decodes it unchanged',
      (tester) async {
    // The test server answers every thumbnail with a 1x1 pixel PNG: asked for
    // 800 px, `allowUpscaling: false` still decodes the one pixel it has,
    // instead of blowing it up in memory.
    var provider = resizedThumbnail(
      client(),
      "http://server/valbum/data/a.jpg",
      displayHeight: 400,
      devicePixelRatio: 2,
    );

    late ui.Image decoded;
    await tester.runAsync(() async {
      var done = Completer<void>();
      provider.resolve(ImageConfiguration.empty).addListener(
            ImageStreamListener((info, _) {
              decoded = info.image;
              done.complete();
            }),
          );
      await done.future;
    });

    expect(decoded.height, 1);
    expect(decoded.width, 1);
  });
}
