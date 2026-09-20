/// What opening a large album costs (issue #111).
///
/// The album used to be one `Column` in a `SingleChildScrollView`: every row
/// and every tile was built the moment the album arrived, so an album of 300
/// images asked the server for 300 thumbnails at once and the rows on the
/// screen waited behind the ones nobody was looking at. Since #111 the rows
/// are built by a lazy list — the visible ones and the reasonable context
/// around them ([contextViewports] viewport heights), the rest as the album
/// is scrolled.
///
/// The layout itself is unchanged: a tile's box is the one `album_layout`
/// computes, before its bytes arrive as well as after, so nothing reflows.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The size of the test window, in logical pixels.
const Size window = Size(800, 600);

/// The number of images the album is generated with.
const int imageCount = 300;

/// A landscape image (2:1) named `img<index>.jpg`.
ImagePart landscape(int index) => ImagePart(
      name: "img$index.jpg",
      width: 2000,
      height: 1000,
      orientation: Orientation.identity,
    );

/// The album of [imageCount] landscape images, as the server sends it.
String get albumJson => AlbumInfo(
      path: "",
      title: "A large album",
      subTitle: "",
      parts: [for (var i = 0; i < imageCount; i++) landscape(i)],
    ).toString();

/// A client answering the album and recording every thumbnail asked for.
VAlbumClient countingClient(List<String> thumbnails) => VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (isThumbnailRequest(request)) {
          thumbnails.add(request.url.path);
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          );
        }
        return http.Response(
          albumJson,
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }),
    );

/// The name of the image the given thumbnail request asked for.
String nameOf(String path) => path.split("/").last;

/// The tile of the image with the given file name, found by the thumbnail it
/// shows — outside the edit mode a tile carries no key of its own.
Finder tile(String name) => find.byWidgetPredicate((widget) {
      if (widget is! Image) {
        return false;
      }
      var image = thumbnailOf(widget.image);
      return image != null && image.imageUrl.endsWith("/$name");
    });

/// The album's scroll position.
ScrollPosition scrollPosition(WidgetTester tester) => tester
    .state<ScrollableState>(find.descendant(
      of: find.byType(AlbumContent),
      matching: find.byType(Scrollable),
    ))
    .position;

/// Opens the album of [imageCount] images at [window].
Future<void> pumpAlbum(WidgetTester tester, VAlbumClient client) async {
  tester.view.physicalSize = window;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: client));
    await tester.pumpAndSettle();
  });
}

/// The height a row is drawn at, as `ContentWidgetBuilder` computes it: the
/// page width minus the gaps, divided by the row's unit width.
double rowHeight(layouter.Row row) =>
    (window.width - (row.size() - 1) * tileSpacing) / row.getUnitWidth();

/// The rows the album layout computes for [window].
List<layouter.Row> layoutRows() => layouter.AlbumLayout(
      window.width,
      250,
      [for (var i = 0; i < imageCount; i++) landscape(i)],
    ).getRows();

void main() {
  setUp(() {
    // Every test counts the requests of an album opened afresh: what a
    // previous test decoded would otherwise be answered from Flutter's own
    // cache and never asked for again.
    var cache = PaintingBinding.instance.imageCache;
    cache.clear();
    cache.clearLiveImages();
  });

  testWidgets('opening a large album asks only for what is near the screen',
      (tester) async {
    var thumbnails = <String>[];
    await pumpAlbum(tester, countingClient(thumbnails));

    expect(find.byType(AlbumContent), findsOneWidget);
    expect(thumbnails, isNotEmpty, reason: "the visible rows were fetched");

    // What the rows within the viewport and the cache extent hold, generously
    // counted: the row straddling the far boundary is built as well, and the
    // title sits above the first one.
    var reach = window.height * (1 + contextViewports);
    var within = 0;
    var covered = 0.0;
    for (var row in layoutRows()) {
      within += row.size();
      covered += rowHeight(row) + tileSpacing;
      if (covered > reach) {
        break;
      }
    }
    expect(
      within,
      lessThan(imageCount),
      reason: "the album is taller than four viewports, or it proves nothing",
    );
    expect(
      thumbnails.length,
      lessThanOrEqualTo(within),
      reason: "nothing beyond the reasonable context was asked for",
    );

    // The tiles on the screen are the ones that were asked for first: the
    // build order is the visual order.
    var asked = thumbnails.map(nameOf).toList();
    for (var index = 0; index < 4; index++) {
      expect(asked, contains("img$index.jpg"));
    }
    expect(asked, isNot(contains("img${imageCount - 1}.jpg")));
  });

  testWidgets('scrolling to the end fetches the rest, each exactly once',
      (tester) async {
    var thumbnails = <String>[];
    await pumpAlbum(tester, countingClient(thumbnails));

    var position = scrollPosition(tester);
    await withFakeImageHttp(() async {
      // A screenful at a time, as a reader scrolls, until the album stops
      // moving: the end of a lazy list is an estimate that grows as the rows
      // below are measured, so the last jump is the one that changes nothing.
      var resting = 0;
      for (var step = 0; step < 500 && resting < 2; step++) {
        var before = position.pixels;
        var target = position.pixels + window.height;
        position.jumpTo(
          target > position.maxScrollExtent ? position.maxScrollExtent : target,
        );
        await tester.pumpAndSettle();
        resting = position.pixels > before ? 0 : resting + 1;
      }
    });

    var asked = thumbnails.map(nameOf).toList();
    expect(
      asked.toSet().length,
      imageCount,
      reason: "every image of the album was fetched on the way down",
    );
    expect(
      asked.length,
      imageCount,
      reason: "and none of them twice: what is decoded stays in the cache",
    );
  });

  testWidgets('a jump to the end builds the rows there, not the way there',
      (tester) async {
    var thumbnails = <String>[];
    await pumpAlbum(tester, countingClient(thumbnails));
    var onOpen = thumbnails.length;
    var position = scrollPosition(tester);
    var extentOnOpen = position.maxScrollExtent;

    // The scrollbar, the End key, an offset restored on a deep link: one jump
    // to an offset far away. The rows have their extents from the layout, so
    // the list skips to the end instead of laying out — and fetching — every
    // row on the way, see issue #111.
    await withFakeImageHttp(() async {
      position.jumpTo(position.maxScrollExtent);
      await tester.pumpAndSettle();
    });

    expect(
      position.maxScrollExtent,
      extentOnOpen,
      reason: "the height of the album was exact from the start",
    );
    expect(
      thumbnails.length - onOpen,
      lessThanOrEqualTo(onOpen),
      reason: "the jump cost about what the opening did, not the whole album: "
          "${thumbnails.length - onOpen} requests",
    );
    expect(thumbnails.length, lessThan(imageCount));
    expect(
      thumbnails.toSet().length,
      thumbnails.length,
      reason: "and nothing twice",
    );
    // The end of the album is really on the screen.
    expect(tile("img${imageCount - 1}.jpg"), findsOneWidget);
  });

  testWidgets('a tile has the layout\'s size before and after its bytes arrive',
      (tester) async {
    // The thumbnails are asked for but answered only later, so every tile is
    // first a placeholder waiting for its picture.
    var waiting = <Completer<http.Response>>[];
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (isThumbnailRequest(request)) {
          var answer = Completer<http.Response>();
          waiting.add(answer);
          return answer.future;
        }
        return http.Response(
          albumJson,
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }),
    );

    tester.view.physicalSize = window;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });

    var first = layoutRows().first;
    var height = rowHeight(first);

    /// The boxes of the tiles of the first row, left to right.
    List<Rect> boxes() => [
          for (var index = 0; index < first.size(); index++)
            tester.getRect(tile("img$index.jpg")),
        ];

    var waited = boxes();
    for (var box in waited) {
      expect(box.height, closeTo(height, 0.5));
    }
    // The row fills the page, the gaps between the tiles included.
    expect(
      waited.map((box) => box.width).reduce((a, b) => a + b) +
          (first.size() - 1) * tileSpacing,
      closeTo(window.width, 0.5),
    );
    // And it is painted while it waits, see [thumbnailPlaceholderColor].
    expect(
      find.byWidgetPredicate((widget) =>
          widget is DecoratedBox &&
          widget.decoration is BoxDecoration &&
          (widget.decoration as BoxDecoration).color ==
              thumbnailPlaceholderColor),
      findsWidgets,
    );

    // The pictures arrive: nothing moves and nothing changes size.
    await withFakeImageHttp(() async {
      for (var answer in waiting) {
        answer.complete(
          http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          ),
        );
      }
      await tester.pumpAndSettle();
    });

    expect(boxes(), waited);
  });
}
