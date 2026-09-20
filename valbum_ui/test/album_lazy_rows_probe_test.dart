/// Probe for issue #111: lazy rows composed with ascending from the viewer
/// (#93), the rating filter (#35), and headings in a large album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const Size window = Size(800, 600);
const int imageCount = 300;

/// A large album: a heading before every fiftieth image, every odd image
/// rated below the default filter.
String albumJson() => AlbumInfo(
      path: "",
      title: "Probe",
      subTitle: "",
      parts: [
        for (var i = 0; i < imageCount; i++) ...[
          if (i % 50 == 0) Heading(text: "Block ${i ~/ 50}"),
          ImagePart(
            name: "img$i.jpg",
            width: 2000,
            height: 1000,
            orientation: Orientation.identity,
            rating: i.isOdd ? -1 : 0,
          ),
        ],
      ],
    ).toString();

VAlbumClient countingClient(List<String> thumbnails) => VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (isThumbnailRequest(request)) {
          thumbnails.add(request.url.path.split("/").last);
          return http.Response.bytes(transparentPixelPng, 200,
              headers: {"content-type": "image/png"});
        }
        return http.Response(albumJson(), 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }),
    );

/// The built tile of [name], in the cache region of the list as well.
Finder tile(String name) => find.byWidgetPredicate((widget) {
      if (widget is! Image) return false;
      var image = thumbnailOf(widget.image);
      return image != null && image.imageUrl.endsWith("/$name");
    }, skipOffstage: false);

ScrollPosition scrollPosition(WidgetTester tester) => tester
    .state<ScrollableState>(find.descendant(
      of: find.byType(AlbumContent),
      matching: find.byType(Scrollable),
    ))
    .position;

Future<void> pumpAlbum(WidgetTester tester, VAlbumClient client) async {
  tester.view.physicalSize = window;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: client));
    await tester.pumpAndSettle();
  });
}

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

void main() {
  setUp(() => PaintingBinding.instance.imageCache.clear());

  testWidgets('the tile the viewer left is on screen again after ascending, '
      'without a new request', (tester) async {
    var requests = <String>[];
    await pumpAlbum(tester, countingClient(requests));
    var first = requests.length;
    expect(first, lessThan(imageCount ~/ 2), reason: "bounded on open");

    await settle(tester, () async {
      scrollPosition(tester).jumpTo(scrollPosition(tester).maxScrollExtent);
    });
    expect(tile("img298.jpg"), findsOneWidget, reason: "the last shown image");
    expect(find.text("Block 5", skipOffstage: false), findsOneWidget,
        reason: "the last heading");
    var afterScroll = requests.length;
    expect(requests.toSet().length, afterScroll, reason: "each once");
    // A jump to the end (the scrollbar, the End key, a restored offset) must
    // not lay out — and fetch — every row on the way: the rows' heights are
    // known from the layout, so the list can skip to the end.
    expect(afterScroll, lessThan(3 * first),
        reason: "a jump fetched the rows in between: $afterScroll requests");
    var offset = scrollPosition(tester).pixels;

    await settle(tester, () => tester.tap(tile("img298.jpg")));
    expect(find.byType(ImageView), findsOneWidget);
    await settle(tester, () => tester.binding.handlePopRoute());
    expect(find.byType(ImageView), findsNothing);
    expect(scrollPosition(tester).pixels, closeTo(offset, 1));
    expect(tile("img298.jpg"), findsOneWidget);
    // The viewer's underlay fetches the thumbnail once more through a
    // provider of its own (issue #120); ascending itself fetches nothing.
    expect(requests.length, lessThanOrEqualTo(afterScroll + 1),
        reason: "ascending fetched something: ${requests.sublist(afterScroll)}");
  });

  testWidgets('showing the hidden ratings rebuilds the rows and fetches only '
      'what comes into reach, each once', (tester) async {
    var requests = <String>[];
    await pumpAlbum(tester, countingClient(requests));
    expect(requests.any((name) => name == "img1.jpg"), isFalse,
        reason: "hidden by the filter, never fetched");
    var state = tester.state<AlbumContentState>(find.byType(AlbumContent));
    await settle(tester, () async => state.showMore());
    expect(tile("img1.jpg"), findsOneWidget);
    expect(requests.length, lessThan(imageCount ~/ 2));
    expect(requests.toSet().length, requests.length, reason: "each once");
  });
}
