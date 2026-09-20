/// What the viewer's underlay costs (issue #120).
///
/// The viewer keeps the album's thumbnail beneath the picture (issue #101) so
/// that paging is never blank -> sharp. It used to ask for that thumbnail
/// through the raw [ThumbnailImage], while the tile decodes it through a
/// [ResizeImage] keyed by the height it is drawn at (issue #93) — and
/// [ResizeImage] resolves its inner provider *outside* the [ImageCache], so
/// the two keys meant two downloads of one picture: opening an image from its
/// tile cost a request for bytes the album already had.
///
/// The underlay now asks with the key the tile decoded under, see
/// `decodedThumbnailHeight`. What that cannot answer is an image no tile has
/// drawn; a deep link straight into the viewer therefore still fetches its
/// underlay once, which is what the second test pins.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_return_test.dart'
    show countingClient, landscapeAlbum, settle, tap;

/// The provider the viewer's underlay draws, see `ImageViewState.buildLayers`.
ImageProvider underlayOf(WidgetTester tester) => tester
    .widget<Image>(find.descendant(
      of: find.byKey(const Key("image-thumbnail")),
      matching: find.byType(Image),
    ))
    .image;

/// The provider the album's tile of [name] draws.
ImageProvider tileProviderOf(WidgetTester tester, String name) => tester
    .widgetList<Image>(find.byWidgetPredicate(
      (widget) =>
          widget is Image &&
          thumbnailOf(widget.image)?.imageUrl.endsWith("/$name") == true,
      skipOffstage: false,
    ))
    .map((image) => image.image)
    .firstWhere((provider) => provider is ResizeImage);

/// How often the thumbnail of [name] was asked for.
int fetchesOf(List<String> thumbnails, String name) =>
    thumbnails.where((path) => path.endsWith("/$name")).length;

void main() {
  // Flutter's [ImageCache] and the remembered decodings are global: what one
  // test fetched would answer the next, and the deep link below is about an
  // image nobody has drawn yet.
  setUp(() {
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    forgetDecodedThumbnailHeights();
  });

  testWidgets('opening an image from its tile fetches no thumbnail again',
      (tester) async {
    var thumbnails = <String>[];
    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(12), thumbnails)),
      ),
    );
    expect(fetchesOf(thumbnails, "img1.jpg"), 1, reason: "the tile fetched it");
    var tile = tileProviderOf(tester, "img1.jpg");
    var afterAlbum = thumbnails.length;

    await tap(tester, find.byKey(const ValueKey("img1.jpg")));

    expect(find.byType(ImageView), findsOneWidget);
    expect(underlayOf(tester), tile,
        reason: "the underlay is the picture the tile decoded");
    expect(thumbnails.length, afterAlbum,
        reason: "the descent fetched ${thumbnails.sublist(afterAlbum)}");
  });

  testWidgets('ascending from the viewer fetches nothing either',
      (tester) async {
    var thumbnails = <String>[];
    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(12), thumbnails)),
      ),
    );
    await tap(tester, find.byKey(const ValueKey("img1.jpg")));
    var inViewer = thumbnails.length;

    await tap(tester, find.byIcon(Icons.arrow_back));

    expect(find.byType(AlbumContent), findsOneWidget);
    expect(thumbnails.length, inViewer,
        reason: "ascending fetched ${thumbnails.sublist(inViewer)}");
  });

  testWidgets('a deep link into the viewer shows an underlay, fetched once',
      (tester) async {
    var thumbnails = <String>[];
    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(
          client: countingClient(landscapeAlbum(12), thumbnails),
          initialRoute: const ImageRoute([], "img1.jpg"),
        ),
      ),
    );

    expect(find.byType(ImageView), findsOneWidget);
    // Nobody had drawn this image before, so there is no tile decoding to
    // reuse — the underlay is fetched, once, and it is there.
    expect(find.byKey(const Key("image-thumbnail")), findsOneWidget);
    expect(tester.getSize(find.byKey(const Key("image-thumbnail"))).width,
        greaterThan(0));
    expect(fetchesOf(thumbnails, "img1.jpg"), 1, reason: "fetched $thumbnails");
  });
}
