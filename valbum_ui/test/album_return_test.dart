/// What the way back from a photo to its album costs (issue #93).
///
/// Descending into a photo and coming back is the most common step there is,
/// and it used to be the most expensive one: the router showed the viewer *in
/// place of* the album, so the album's whole subtree was disposed, every
/// tile's image stream dropped — and a large album evicts its own thumbnails
/// from Flutter's [ImageCache] while the viewer is open, so the way back
/// fetched and decoded every visible one again.
///
/// Since #93 the router builds one page per level of the route: the album
/// stays mounted beneath the viewer, and ascending is a pop that finds it
/// exactly as it was left — the same [AlbumContentState], the same scroll
/// offset, its thumbnails held live and nothing asked of the server.
///
/// The tests run inside [WidgetTester.runAsync] with a real delay: without
/// actual asynchrony the thumbnails never finish decoding, stay *pending* in
/// the image cache, and the eviction the bug lives on cannot happen at all —
/// which is why no test saw it before.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// An album of [count] landscape images named `img0.jpg`, `img1.jpg`, ….
String landscapeAlbum(int count) =>
    '["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": ['
    '${[
      for (var index = 0; index < count; index++)
        '["ImagePart", {"kind": "IMAGE", "name": "img$index.jpg", '
            '"date": ${index + 1}, "width": 2000, "height": 1000, '
            '"orientation": "IDENTITY", "rating": 0}]'
    ].join(",")}'
    ']}]';

/// The album of [fixture] `album.json`, which holds a group of two shots.
String get groupAlbum => fixture("album.json");

/// A client answering [album] and counting every thumbnail asked for.
VAlbumClient countingClient(String album, List<String> thumbnails) =>
    VAlbumClient(
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
          album,
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        );
      }),
    );

/// Runs [act] and lets the thumbnails really decode, see the library comment.
///
/// The decoding needs the real event loop, which only runs inside
/// [WidgetTester.runAsync]; the pumping needs the test's own clock, which only
/// runs outside it. The two therefore alternate until nothing is pending any
/// more.
Future<void> settle(WidgetTester tester, Future<void> Function() act) async {
  await withFakeImageHttp(() async {
    await act();
    await tester.pumpAndSettle();
  });
  for (var round = 0; round < 5; round++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 50)),
    );
    await withFakeImageHttp(() => tester.pumpAndSettle());
  }
}

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) =>
    settle(tester, () => tester.sendKeyEvent(key));

/// Shrinks the image cache to nothing, so that the album's thumbnails are
/// evicted as soon as no widget holds them any more.
void withTinyImageCache() {
  var cache = PaintingBinding.instance.imageCache;
  var before = cache.maximumSizeBytes;
  cache.maximumSizeBytes = 1;
  addTearDown(() {
    cache.maximumSizeBytes = before;
    cache.clear();
    cache.clearLiveImages();
  });
}

/// The state of the album, whether it is on top or mounted beneath a viewer.
AlbumContentState albumStateOf(WidgetTester tester) => tester
    .state<AlbumContentState>(find.byType(AlbumContent, skipOffstage: false));

/// The route the app is at.
VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

/// The router of the app.
VAlbumRouterDelegate routerOf(WidgetTester tester) => tester
    .widget<MaterialApp>(find.byType(MaterialApp))
    .routerDelegate! as VAlbumRouterDelegate;

/// The image the viewer shows.
AbstractImage shownImage(WidgetTester tester) =>
    tester.widget<ImageView>(find.byType(ImageView)).image;

void main() {
  testWidgets('going back from an image fetches no thumbnail again',
      (tester) async {
    withTinyImageCache();
    var thumbnails = <String>[];

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(12), thumbnails)),
      ),
    );
    expect(find.byType(AlbumContent), findsOneWidget);
    var afterAlbum = thumbnails.length;
    expect(afterAlbum, greaterThan(0), reason: "the album showed its tiles");

    var album = albumStateOf(tester);

    await tap(tester, find.byKey(const ValueKey("img1.jpg")));
    expect(find.byType(ImageView), findsOneWidget);
    var afterDescent = thumbnails.length;

    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(AlbumContent), findsOneWidget);

    expect(
      thumbnails.length,
      afterDescent,
      reason: "the way back must not fetch a single thumbnail again",
    );
    // The album was never rebuilt from scratch: it is the same view, with the
    // same tiles and the same images, that was there before the descent.
    expect(identical(albumStateOf(tester), album), isTrue);
  });

  testWidgets('the album keeps its scroll offset across a visit to an image',
      (tester) async {
    withTinyImageCache();
    var thumbnails = <String>[];

    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(40), thumbnails)),
      ),
    );

    await settle(
      tester,
      () => tester.drag(find.byType(AlbumContent), const Offset(0, -600)),
    );
    var scrolled = routerOf(tester).scrollOffset([]);
    expect(scrolled, greaterThan(0), reason: "the album was scrolled down");
    var album = albumStateOf(tester);

    // The first tile the scrolled album really shows — a lazy list keeps a
    // few above the viewport as well — tapped beside its toolbars, so that
    // the tap reaches the tile itself.
    var tiles = find.byWidgetPredicate((widget) =>
        widget is GestureDetector &&
        widget.key is ValueKey<String> &&
        (widget.key as ValueKey<String>).value.startsWith("img"));
    var box = tiles
        .evaluate()
        .map((element) => tester.getRect(find.byKey(element.widget.key!)))
        .firstWhere((rect) => rect.top >= 0 && rect.bottom <= 600);
    await settle(
      tester,
      () => tester.tapAt(Offset(box.left + 8, box.center.dy)),
    );
    expect(find.byType(ImageView), findsOneWidget);
    var afterDescent = thumbnails.length;

    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(AlbumContent), findsOneWidget);

    expect(thumbnails.length, afterDescent);
    expect(identical(albumStateOf(tester), album), isTrue);
    expect(routerOf(tester).scrollOffset([]), scrolled);
  });

  testWidgets('a group is left the same way, over two levels', (tester) async {
    withTinyImageCache();
    var thumbnails = <String>[];

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(groupAlbum, thumbnails)),
      ),
    );
    var album = albumStateOf(tester);
    var afterAlbum = thumbnails.length;

    // The album's group tile opens the viewer on the group ...
    await tap(tester, find.byKey(const ValueKey("group-a.jpg")));
    expect(routeOf(tester), const ImageRoute([], "group-a.jpg"));

    // ... whose chevron opens the alternatives, one page above the album.
    await tap(tester, find.byIcon(Icons.expand_more));
    expect(routeOf(tester), const AlternativesRoute([], "group-a.jpg"));
    expect(find.byType(GroupView), findsOneWidget);
    var alternatives = find.byType(GroupView).evaluate().single;

    // One shot of the group sits on the alternatives, which stay mounted.
    await tap(tester, find.byKey(const ValueKey("group-tile-group-b.jpg")));
    expect(
      routeOf(tester),
      const MemberRoute([], "group-a.jpg", "group-b.jpg"),
    );
    var afterDescent = thumbnails.length;
    expect(
      find.byType(GroupView, skipOffstage: false),
      findsOneWidget,
      reason: "the alternatives stayed mounted beneath the shot",
    );

    // Back, one page at a time: the shot is popped, the alternatives are
    // exactly the view that was left.
    await settle(tester, () async {
      routerOf(tester).navigatorKey.currentState!.pop();
    });
    expect(routeOf(tester), const AlternativesRoute([], "group-a.jpg"));
    expect(
      identical(find.byType(GroupView).evaluate().single, alternatives),
      isTrue,
    );

    // And back to the album, which was never touched.
    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(routeOf(tester), const ListingOrAlbumRoute([]));
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(identical(albumStateOf(tester), album), isTrue);
    expect(
      thumbnails.length,
      afterDescent,
      reason: "neither way back fetched a thumbnail",
    );
    expect(afterAlbum, greaterThan(0));
  });

  testWidgets('a deep link to an image builds the album beneath it',
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
    expect(
      find.byType(AlbumContent, skipOffstage: false),
      findsOneWidget,
      reason: "the album the link points into is already there",
    );

    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(routeOf(tester), const ListingOrAlbumRoute([]));
  });

  testWidgets('moving on in the viewer leaves the album untouched',
      (tester) async {
    withTinyImageCache();
    var thumbnails = <String>[];

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(12), thumbnails)),
      ),
    );
    var album = albumStateOf(tester);

    await tap(tester, find.byKey(const ValueKey("img1.jpg")));
    expect(shownImage(tester).thumbnailName, "img1.jpg");
    var afterDescent = thumbnails.length;

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownImage(tester).thumbnailName, "img2.jpg");
    expect(routeOf(tester), const ImageRoute([], "img2.jpg"));
    // Only the topmost page was exchanged.
    expect(identical(albumStateOf(tester), album), isTrue);

    // The viewer draws the thumbnail of the image it shows beneath the
    // picture (issue #101) — with the key the tile decoded it under, so that
    // is a cache hit and not a request (issue #120). Neither paging nor the
    // way back costs a thumbnail, which is what this test is about.
    expect(thumbnails.length, afterDescent);
    var beforeReturn = thumbnails.length;

    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(identical(albumStateOf(tester), album), isTrue);
    expect(thumbnails.length, beforeReturn);
  });

  testWidgets('the system back button leaves the viewer and then the album',
      (tester) async {
    withTinyImageCache();
    var thumbnails = <String>[];

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(
          client: countingClient(landscapeAlbum(12), thumbnails),
          initialRoute: const ListingOrAlbumRoute(["album"]),
        ),
      ),
    );
    var album = albumStateOf(tester);

    await tap(tester, find.byKey(const ValueKey("img1.jpg")));
    expect(find.byType(ImageView), findsOneWidget);
    var afterDescent = thumbnails.length;

    await settle(tester, () => tester.binding.handlePopRoute());
    expect(routeOf(tester), const ListingOrAlbumRoute(["album"]));
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(identical(albumStateOf(tester), album), isTrue);
    expect(thumbnails.length, afterDescent);

    // From the album it leads out of it, as it always did.
    await settle(tester, () => tester.binding.handlePopRoute());
    expect(routeOf(tester), const ListingOrAlbumRoute([]));
  });
}
