/// The way back from a viewer opened by its address (issue #272).
///
/// The album beneath a viewer opened by its address — a reload, a bookmark,
/// a link, the browser's history — was built there without ever being shown.
/// Going back scrolls it to the photo the viewer showed last, the one paged
/// to, a group by its tile, in an album and among the results of a search.
/// An album the user descended from keeps its offset exactly as before
/// (issue #93).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/inbox_view.dart' show InboxContent;
import 'package:valbum_ui/main.dart';

import 'album_return_test.dart'
    show countingClient, routeOf, routerOf, settle, shownImage;
import 'inbox_view_test.dart' show inboxJson;
import 'search_layout_test.dart' show many, phone;
import 'search_leave_test.dart' show LongSpace, browserTo;
import 'search_view_test.dart' as search show chooseAnne, pumpRoute, settle;

/// A landscape photo of the album.
String _image(String name, int date) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": $date, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]';

/// An album of two runs of photos `img0.jpg` … `img69.jpg` under two
/// headings, with the group `g-a.jpg`/`g-b.jpg` between `img59.jpg` and
/// `img60.jpg` — far more than a screen.
final String longAlbum = '["AlbumInfo", {"path": "", "title": "Album", '
    '"subTitle": "", "parts": ['
    '["Heading", {"text": "Early"}],'
    '${[for (var i = 0; i < 30; i++) _image("img$i.jpg", i + 1)].join(",")},'
    '["Heading", {"text": "Late"}],'
    '${[for (var i = 30; i < 60; i++) _image("img$i.jpg", i + 1)].join(",")},'
    '["ImageGroup", {"representative": 0, "images": ['
    '{"kind": "IMAGE", "name": "g-a.jpg", "date": 100, "width": 2000, '
    '"height": 1000, "orientation": "IDENTITY", "rating": 0},'
    '{"kind": "IMAGE", "name": "g-b.jpg", "date": 101, "width": 2000, '
    '"height": 1000, "orientation": "IDENTITY", "rating": 0}]}],'
    '${[for (var i = 60; i < 70; i++) _image("img$i.jpg", i + 1)].join(",")}'
    ']}]';

/// A screen of [size] logical pixels.
void screen(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// Opens the app on [route] in the long album.
Future<void> open(WidgetTester tester, VAlbumRoute route) => settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(
          client: countingClient(longAlbum, []),
          initialRoute: route,
        ),
      ),
    );

/// The system's (or the browser's) back button.
Future<void> back(WidgetTester tester) =>
    settle(tester, () => tester.binding.handlePopRoute());

/// The scroll position of the album (or the inbox) on the screen.
ScrollPosition albumPosition(WidgetTester tester) => tester
    .state<ScrollableState>(
      find
          .descendant(
            of: find.byWidgetPredicate(
              (widget) => widget is AlbumContent || widget is InboxContent,
            ),
            matching: find.byType(Scrollable),
          )
          .first,
    )
    .position;

/// The tile keyed [name] stands fully on the screen, and in its middle unless
/// an end of the album keeps it from there.
void expectRevealed(WidgetTester tester, String name) {
  var tile = find.byKey(ValueKey(name));
  expect(tile.hitTestable(), findsOneWidget, reason: "$name is on the screen");
  var rect = tester.getRect(tile);
  var height = tester.view.physicalSize.height / tester.view.devicePixelRatio;
  expect(rect.top, greaterThanOrEqualTo(0), reason: "$name is fully visible");
  expect(rect.bottom, lessThanOrEqualTo(height),
      reason: "$name is fully visible");
  var position = albumPosition(tester);
  if (position.pixels > position.minScrollExtent &&
      position.pixels < position.maxScrollExtent) {
    expect((rect.center.dy - height / 2).abs(), lessThan(rect.height / 2 + 4),
        reason: "$name stands in the middle of the screen");
  }
}

void main() {
  for (var size in const [
    Size(800, 600),
    Size(390, 844),
    Size(333, 517),
    Size(1281, 719),
  ]) {
    testWidgets(
        'back from a photo far down opened by its address shows it, '
        '${size.width}x${size.height}', (tester) async {
      screen(tester, size);
      await open(tester, const ImageRoute([], "img45.jpg"));
      expect(find.byType(ImageView), findsOneWidget);

      await back(tester);

      expect(routeOf(tester), const ListingOrAlbumRoute([]));
      expect(albumPosition(tester).pixels, greaterThan(0));
      expectRevealed(tester, "img45.jpg");
    });
  }

  testWidgets('back after paging shows the photo paged to', (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ImageRoute([], "img3.jpg"));

    // A step as a chevron or a swipe takes it, then a long way on.
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(shownImage(tester).thumbnailName, "img4.jpg");
    await settle(
      tester,
      () async => routerOf(tester).go(const ImageRoute([], "img52.jpg")),
    );
    await settle(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(routeOf(tester), const ImageRoute([], "img53.jpg"));

    await back(tester);

    expectRevealed(tester, "img53.jpg");
  });

  testWidgets('back near the end stops at the end of the album',
      (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ImageRoute([], "img69.jpg"));

    await back(tester);

    var position = albumPosition(tester);
    expect(position.pixels, position.maxScrollExtent);
    expectRevealed(tester, "img69.jpg");
  });

  testWidgets('back near the start leaves the album at its top',
      (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ImageRoute([], "img1.jpg"));

    await back(tester);

    expect(albumPosition(tester).pixels, 0);
    expectRevealed(tester, "img1.jpg");
  });

  testWidgets('an album descended from keeps its offset (#93)', (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ListingOrAlbumRoute([]));
    await settle(
      tester,
      () => tester.drag(find.byType(AlbumContent), const Offset(0, -900)),
    );
    var offset = albumPosition(tester).pixels;
    expect(offset, greaterThan(0));

    // Into a photo on the screen, and paged far away from it.
    var shown = [
      for (var i = 0; i < 70; i++)
        if (find
            .byKey(ValueKey("img$i.jpg"))
            .hitTestable()
            .evaluate()
            .isNotEmpty)
          i,
    ];
    routerOf(tester).go(ImageRoute(const [], "img${shown.first}.jpg"));
    await settle(tester, () async {});
    expect(find.byType(ImageView), findsOneWidget);
    await settle(
      tester,
      () async => routerOf(tester).go(const ImageRoute([], "img66.jpg")),
    );

    await back(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute([]));
    expect(albumPosition(tester).pixels, offset);
    expect(routerOf(tester).scrollOffset([]), offset);
  });

  testWidgets('an album revealed once keeps its offset afterwards',
      (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ImageRoute([], "img45.jpg"));
    await back(tester);
    var offset = albumPosition(tester).pixels;

    routerOf(tester).go(const ImageRoute([], "img2.jpg"));
    await settle(tester, () async {});
    await back(tester);

    expect(albumPosition(tester).pixels, offset);
  });

  testWidgets('back from a member of a group shows the group\'s tile',
      (tester) async {
    screen(tester, const Size(390, 844));
    await open(tester, const MemberRoute([], "g-a.jpg", "g-b.jpg"));
    expect(find.byType(GroupDetailView), findsOneWidget);

    await back(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute([]));
    expectRevealed(tester, "g-a.jpg");
  });

  testWidgets('back from the alternatives of a group shows the group\'s tile',
      (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const AlternativesRoute([], "g-a.jpg"));

    await back(tester);

    expectRevealed(tester, "g-a.jpg");
  });

  testWidgets('back from a photo opened from another album shows it',
      (tester) async {
    screen(tester, const Size(800, 600));
    await open(tester, const ListingOrAlbumRoute(["other"]));

    // As an entry of the bell or a hit of a search does: the album is built
    // beneath the viewer while the viewer comes in over it.
    await settle(
      tester,
      () async => routerOf(tester).go(const ImageRoute(["album"], "img48.jpg")),
    );
    expect(find.byType(ImageView), findsOneWidget);

    await back(tester);

    expect(routeOf(tester), const ListingOrAlbumRoute(["album"]));
    expectRevealed(tester, "img48.jpg");
  });

  testWidgets('back from a photo of an inbox opened by its address shows it',
      (tester) async {
    screen(tester, const Size(390, 844));
    var photos = [
      for (var i = 0; i < 40; i++) ("p$i.jpg", 2026, 1 + i ~/ 10, 1 + i % 10),
    ].reversed.toList();
    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(
          client: countingClient(inboxJson(photos: photos), []),
          initialRoute: const ImageRoute(["Inbox"], "p3.jpg"),
        ),
      ),
    );
    expect(find.byType(ImageView), findsOneWidget);

    await back(tester);

    expect(find.byType(InboxContent), findsOneWidget);
    expect(albumPosition(tester).pixels, greaterThan(0));
    expectRevealed(tester, "p3.jpg");
  });

  testWidgets('back from a photo of the search shows its result',
      (tester) async {
    phone(tester);
    var space = LongSpace();
    var delegate = await search.pumpRoute(tester, space, const SearchRoute([]));
    await search.chooseAnne(tester);
    // Away from the search, and back onto one of its photographs by the
    // browser's history: the search is built beneath it, never shown.
    await browserTo(tester, ListingOrAlbumRoute.root);
    expect(find.byType(AlbumContent, skipOffstage: false), findsNothing);
    await browserTo(
      tester,
      const ImageRoute([], "2020 Trip/x22.jpg", fromSearch: true),
    );
    expect(find.byType(ImageView), findsOneWidget);

    await tester.binding.handlePopRoute();
    await search.settle(tester);

    expect(delegate.route, const SearchRoute([]));
    expect(many, contains("x22.jpg"));
    expectRevealed(tester, "2020 Trip/x22.jpg");
  });
}
