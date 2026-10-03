/// What a re-layout costs the tiles that merely moved (issue #223).
///
/// A tile decodes its thumbnail at the height it is drawn at (issue #93), so
/// the decoding is keyed by that height. Taking a photograph out of an album
/// or an inbox lays out every row behind it again, and each of those rows
/// comes out a few pixels taller or shorter: every tile there used to get a
/// new key, decode bytes the cache already had, show the placeholder and fade
/// its picture in again — the whole album flashed.
///
/// Since #223 a tile keeps the picture it shows while a new decoding is on its
/// way, fades in only where it had no picture yet, and reuses a decoding of
/// its image that is already tall enough — and at most twice as tall — rather
/// than decoding again.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_return_test.dart' show countingClient;
import 'inbox_view_test.dart' hide main;
import 'move_test.dart' show json, tile;
import 'util/fake_image_http.dart';

/// A photograph of the inbox of the given width, all taken on one day, so
/// that the rows of that day reflow when one of them leaves.
String widePart(String name, int hour, int width) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", '
    '"date": ${DateTime(2026, 3, 1, hour).millisecondsSinceEpoch}, '
    '"width": $width, "height": 1000, "orientation": "IDENTITY", '
    '"rating": 0}]';

/// Ten photographs of mixed aspect ratios on one day.
const List<int> widths = [
  1500,
  1333,
  2000,
  750,
  1778,
  1000,
  2400,
  1500,
  667,
  1333,
];

String mixedInbox() =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
    '"subTitle": "", "kind": "INBOX", "parts": [${[
      // The server answers newest first.
      for (var i = widths.length - 1; i >= 0; i--) widePart("p$i.jpg", 8 + i, widths[i]),
    ].join(", ")}]}]';

/// Lets the thumbnails really decode: that needs the real event loop, which
/// only runs inside [WidgetTester.runAsync] (issue #149).
Future<void> decodeAll(WidgetTester tester) async {
  for (var round = 0; round < 5; round++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 30)),
    );
    await withFakeImageHttp(() => tester.pumpAndSettle());
  }
}

/// The thumbnail [Image] inside the tile of [name].
Finder imageOf(String name) =>
    find.descendant(of: tile(name), matching: find.byType(Image));

/// Whether the tile of [name] shows its placeholder or is fading its picture
/// in, which is what the flash is.
bool flashes(WidgetTester tester, String name) {
  var placeholder = find.descendant(
    of: tile(name),
    matching: find.byWidgetPredicate(
      (widget) =>
          widget is DecoratedBox &&
          widget.decoration is BoxDecoration &&
          (widget.decoration as BoxDecoration).color ==
              thumbnailPlaceholderColor,
    ),
  );
  var hidden = find.descendant(
    of: tile(name),
    matching: find.byWidgetPredicate(
      (widget) => widget is AnimatedOpacity && widget.opacity < 1,
    ),
  );
  return placeholder.evaluate().isNotEmpty || hidden.evaluate().isNotEmpty;
}

/// A tile of the given height, changed by [TileHost.resize].
class TileHost extends StatefulWidget {
  final VAlbumClient client;
  final double initialHeight;
  const TileHost(this.client, this.initialHeight, {super.key});

  @override
  State<TileHost> createState() => TileHostState();
}

class TileHostState extends State<TileHost> {
  late double height = widget.initialHeight;

  void resize(double to) => setState(() => height = to);

  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topLeft,
    child: thumbnail(
      widget.client,
      "http://server/valbum/data/a.jpg",
      width: height * 1.5,
      height: height,
      displayHeight: height,
      fit: BoxFit.contain,
    ),
  );
}

/// The [ResizeImage] the one tile of [TileHost] draws.
ResizeImage hostProvider(WidgetTester tester) =>
    tester.widget<Image>(find.byType(Image)).image as ResizeImage;

Future<TileHostState> pumpHost(
  WidgetTester tester,
  List<String> thumbnails,
  double height,
) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: MediaQuery(
          data: const MediaQueryData(devicePixelRatio: 2),
          child: TileHost(
            countingClient('["AlbumInfo", {}]', thumbnails),
            height,
          ),
        ),
      ),
    );
  });
  await decodeAll(tester);
  return tester.state<TileHostState>(find.byType(TileHost));
}

void main() {
  setUp(() {
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    forgetDecodedThumbnailHeights();
  });

  testWidgets('deleting a photograph flashes no tile behind it', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await pumpInbox(
      tester,
      (request) => request.method == "PUT"
          ? json("")
          : inboxTree(request, album: mixedInbox()),
    );
    await decodeAll(tester);

    var names = [for (var i = 0; i < widths.length; i++) "p$i.jpg"];
    var before = {
      for (var name in names) name: tester.widget<Image>(imageOf(name)).image,
    };
    var heights = {
      for (var name in names) name: tester.getSize(imageOf(name)).height,
    };
    for (var name in names) {
      expect(flashes(tester, name), isFalse, reason: "$name is decoded");
    }

    // The earliest photograph of the day stands first; rating it trash takes
    // it off the screen and lays out every row of the day again.
    await tapTileOf(tester, "p0.jpg");
    await withFakeImageHttp(() async {
      await tester.tap(find.byKey(const Key("rating-trash")));
      // A few frames, and no decoding may land in between: what is on the
      // screen now is what the eye sees while the new heights are decoded.
      for (var frame = 0; frame < 5; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
    });
    expect(tile("p0.jpg"), findsNothing);

    var rest = names.skip(1).toList();
    var grown = [
      for (var name in rest)
        if (tester.getSize(imageOf(name)).height > heights[name]!) name,
    ];
    var shrunk = [
      for (var name in rest)
        if (tester.getSize(imageOf(name)).height < heights[name]!) name,
    ];
    expect(grown, isNotEmpty, reason: "the rows reflowed, some tile grew");
    expect(shrunk, isNotEmpty, reason: "and some tile shrank");
    for (var name in rest) {
      expect(
        flashes(tester, name),
        isFalse,
        reason: "$name keeps its picture while it is decoded anew",
      );
    }

    // And once the new decodings landed, every tile still shows a picture —
    // a grown one decoded at its new height, never drawn from fewer pixels
    // than it shows, a shrunk one from the decoding it had.
    await decodeAll(tester);
    for (var name in rest) {
      expect(flashes(tester, name), isFalse, reason: name);
      var shown = tester.widget<Image>(imageOf(name)).image as ResizeImage;
      var drawn = tester.getSize(imageOf(name)).height.ceil();
      expect(shown.height, greaterThanOrEqualTo(drawn), reason: name);
      expect(shown.height, lessThanOrEqualTo(2 * drawn), reason: name);
      if (shrunk.contains(name)) {
        expect(shown, before[name], reason: "$name decoded nothing");
      }
    }
  });

  testWidgets('a tile a few pixels shorter decodes nothing', (tester) async {
    var thumbnails = <String>[];
    var host = await pumpHost(tester, thumbnails, 200);
    var decoded = hostProvider(tester);
    expect(decoded.height, 400);
    var cache = PaintingBinding.instance.imageCache;
    var cached = cache.currentSize;

    host.resize(196.5);
    await withFakeImageHttp(() => tester.pump());

    expect(
      hostProvider(tester),
      decoded,
      reason: "the decoding of 400 px still serves 393 px",
    );
    expect(cache.pendingImageCount, 0, reason: "no decoding was started");
    expect(cache.currentSize, cached);
    expect(thumbnails, hasLength(1));
    expect(
      decodedThumbnailHeight("http://server/valbum/data/a.jpg"),
      400,
      reason: "the viewer's underlay finds the decoding the tile shows",
    );
    expect(flashes(tester, "a.jpg"), isFalse);
  });

  testWidgets('a taller tile decodes sharper, without a flash', (tester) async {
    var thumbnails = <String>[];
    var host = await pumpHost(tester, thumbnails, 200);

    host.resize(203);
    await withFakeImageHttp(() => tester.pump());

    // Never drawn from fewer pixels than it shows: 406 px are decoded.
    expect(hostProvider(tester).height, 406);
    expect(find.byType(Image), findsOneWidget);
    expect(tester.widget<Image>(find.byType(Image)).gaplessPlayback, isTrue);
    var placeholder = find.byWidgetPredicate(
      (widget) =>
          widget is DecoratedBox &&
          (widget.decoration as BoxDecoration).color ==
              thumbnailPlaceholderColor,
    );
    expect(
      placeholder,
      findsNothing,
      reason: "the old picture stays until the sharper one is there",
    );
    expect(
      find.byWidgetPredicate(
        (widget) => widget is AnimatedOpacity && widget.opacity < 1,
      ),
      findsNothing,
    );

    await decodeAll(tester);
    expect(decodedThumbnailHeight("http://server/valbum/data/a.jpg"), 406);
  });

  testWidgets('a tile less than half as tall decodes smaller again', (
    tester,
  ) async {
    // Reusing a decoding more than twice as tall would hold four times the
    // memory the tile needs, which is what #93 decodes at the drawn size for.
    var thumbnails = <String>[];
    var host = await pumpHost(tester, thumbnails, 200);

    host.resize(100);
    await withFakeImageHttp(() => tester.pump());
    expect(hostProvider(tester).height, 400,
        reason: "exactly twice as tall is still reused");

    host.resize(99);
    await withFakeImageHttp(() => tester.pump());
    expect(hostProvider(tester).height, 198,
        reason: "more than twice as tall is decoded anew at the drawn size");
    await decodeAll(tester);
    expect(decodedThumbnailHeight("http://server/valbum/data/a.jpg"), 198);
  });
}
