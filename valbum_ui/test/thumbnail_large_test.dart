/// The large preview of a starred tile (issue #241).
///
/// The server's preview is 600 px high (1200 for a portrait). A starred
/// 2×2 tile of a listing (#239) is some 416 logical pixels: sharp on a screen
/// of density 1, blown up on one of density 2 or 3. There it asks for the
/// server's large preview (`?type=tn&size=large`, twice as high); everywhere
/// else — a 1×1 tile, the album, the viewer — nothing changes.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_return_test.dart' show countingClient;
import 'folder_picture_test.dart' show pumpAt;
import 'listing_star_test.dart' show server, tileOf;
import 'thumbnail_relayout_test.dart' show decodeAll;
import 'util/fake_image_http.dart';

/// The address the picture of the tile of [name] is fetched from.
String thumbnailUrlOf(WidgetTester tester, String name) {
  var image = tester.widget<Image>(
    find.descendant(of: tileOf(name), matching: find.byType(Image)),
  );
  var provider = thumbnailOf(image.image);
  expect(provider, isNotNull, reason: "the tile of $name shows a thumbnail");
  return provider!.url;
}

/// Shows the folder `F` of `listing_star_test.dart` on a screen of the given
/// density, 1000 logical pixels wide: five columns, the starred `A` 416 px.
Future<void> pumpListing(WidgetTester tester, double density) async {
  tester.view.devicePixelRatio = density;
  tester.view.physicalSize = Size(1000 * density, 1600 * density);
  addTearDown(tester.view.reset);
  await pumpAt(tester, server, route: const ["F"]);
}

const String imageUrl = "http://server/valbum/data/a.jpg";

/// One starred tile of the given side, changed by [LargeTileHostState.resize].
class LargeTileHost extends StatefulWidget {
  final VAlbumClient client;
  final double initialSize;
  const LargeTileHost(this.client, this.initialSize, {super.key});

  @override
  State<LargeTileHost> createState() => LargeTileHostState();
}

class LargeTileHostState extends State<LargeTileHost> {
  late double size = widget.initialSize;

  void resize(double to) => setState(() => size = to);

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.topLeft,
        child: thumbnail(
          widget.client,
          imageUrl,
          width: size,
          height: size,
          displayHeight: size,
          largeTileSize: size,
          fit: BoxFit.contain,
        ),
      );
}

ResizeImage hostProvider(WidgetTester tester) =>
    tester.widget<Image>(find.byType(Image)).image as ResizeImage;

void main() {
  setUp(() {
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    forgetDecodedThumbnailHeights();
    forgetThumbnailBytes();
  });

  test('the address of the large preview', () {
    var client = VAlbumClient(dataUrl: "http://s/data");
    expect(client.thumbnailUrl("http://s/data/A/a.jpg", large: true),
        "http://s/data/A/a.jpg?type=tn&size=large");
    expect(
        client.thumbnailUrl("http://s/data/A/a.jpg?crop=0.5,0,0.5,0.5",
            large: true),
        "http://s/data/A/a.jpg?crop=0.5,0,0.5,0.5&type=tn&size=large",
        reason: "the cut applies to the large preview too");
    expect(client.thumbnailUrl("http://s/data/A/a.jpg"),
        "http://s/data/A/a.jpg?type=tn",
        reason: "the normal preview is asked for as before");
  });

  test('the rule: more pixels than the normal preview', () {
    expect(wantsLargeThumbnail(416, 1), isFalse);
    expect(wantsLargeThumbnail(416, 2), isTrue);
    expect(wantsLargeThumbnail(416, 3), isTrue);
    expect(wantsLargeThumbnail(300, 2), isFalse, reason: "600 px are enough");
    expect(wantsLargeThumbnail(301, 2), isTrue);
  });

  testWidgets('a starred tile at density 3 asks for the large preview',
      (tester) async {
    await pumpListing(tester, 3);
    expect(tester.getSize(find.descendant(
            of: tileOf("A"), matching: find.byType(ClipRect)).first),
        const Size(416, 416));
    expect(thumbnailUrlOf(tester, "A"),
        "http://server/valbum/data/F/A/a.jpg?type=tn&size=large");
    expect(thumbnailUrlOf(tester, "B"),
        "http://server/valbum/data/F/B/b.jpg?type=tn",
        reason: "a 1×1 tile never asks for it");
    expect(tester.takeException(), isNull);
  });

  testWidgets('the same tile at density 1 asks for the normal preview',
      (tester) async {
    await pumpListing(tester, 1);
    expect(thumbnailUrlOf(tester, "A"),
        "http://server/valbum/data/F/A/a.jpg?type=tn");
    expect(thumbnailUrlOf(tester, "B"),
        "http://server/valbum/data/F/B/b.jpg?type=tn");
  });

  testWidgets('the reuse bound keeps the large decoding', (tester) async {
    var thumbnails = <String>[];
    late LargeTileHostState host;
    await withFakeImageHttp(() async {
      await tester.pumpWidget(Directionality(
        textDirection: TextDirection.ltr,
        child: MediaQuery(
          data: const MediaQueryData(devicePixelRatio: 3),
          child: LargeTileHost(
              countingClient('["AlbumInfo", {}]', thumbnails), 416),
        ),
      ));
    });
    await decodeAll(tester);
    host = tester.state<LargeTileHostState>(find.byType(LargeTileHost));

    var decoded = hostProvider(tester);
    expect(decoded.height, 1248);
    expect((decoded.imageProvider as ThumbnailImage).large, isTrue);
    expect(thumbnails, hasLength(1));

    // Shrunk within the bound, still beyond the normal preview: the large
    // decoding is reused, nothing is decoded or fetched.
    host.resize(300);
    await withFakeImageHttp(() => tester.pump());
    expect(hostProvider(tester), decoded,
        reason: "1248 px still serve 900 px, at most twice as many");
    expect(PaintingBinding.instance.imageCache.pendingImageCount, 0);
    expect(thumbnails, hasLength(1));
    expect(decodedThumbnailHeight(imageUrl), isNull,
        reason: "the large decoding never stands in for the normal one");

    // Beyond the bound it is decoded anew, from the same download.
    host.resize(205);
    await withFakeImageHttp(() => tester.pump());
    var smaller = hostProvider(tester);
    expect(smaller.height, 615);
    expect((smaller.imageProvider as ThumbnailImage).large, isTrue);
    await decodeAll(tester);
    expect(thumbnails, hasLength(1), reason: "the bytes are kept");

    // Where the normal preview is enough, the tile asks for it.
    host.resize(200);
    await withFakeImageHttp(() => tester.pump());
    var normal = hostProvider(tester);
    expect((normal.imageProvider as ThumbnailImage).large, isFalse);
    expect(normal.height, 600);
    await decodeAll(tester);
    expect(decodedThumbnailHeight(imageUrl), 600);
  });
}
