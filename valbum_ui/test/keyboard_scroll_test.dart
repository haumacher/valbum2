/// Tests of issue #168: a scrollbar one can see, and the paging keys, on the
/// inbox, the album and the listing — through the one [KeyboardScroll].
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/keyboard_scroll.dart';
import 'package:valbum_ui/main.dart';

import 'album_drag_scroll_test.dart' show albumJson, tile, useSurface;
import 'inbox_view_test.dart' show inboxJson, inboxTree, pumpInbox;
import 'listing_view_test.dart' show pumpListing;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The one scroll position the [KeyboardScroll] on the screen drives.
ScrollPosition positionOf(WidgetTester tester) {
  var state = tester.state<KeyboardScrollState>(find.byType(KeyboardScroll));
  var positions = state.controller.positions;
  expect(positions, hasLength(1), reason: "one scroll view, one controller");
  return positions.first;
}

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) async {
  await tester.sendKeyEvent(key);
  await tester.pumpAndSettle();
}

/// PageDown by a viewport less the overlap, PageUp back, End to the end,
/// Home to the top — the acceptance of issue #168 on any screen.
Future<void> expectPaging(WidgetTester tester) async {
  var position = positionOf(tester);
  expect(position.pixels, 0);
  var page = position.viewportDimension - keyboardPageOverlap;
  expect(position.maxScrollExtent, greaterThan(2 * page),
      reason: "the screen must overflow for the test to say anything");

  await press(tester, LogicalKeyboardKey.pageDown);
  expect(position.pixels, moreOrLessEquals(page));
  await press(tester, LogicalKeyboardKey.pageDown);
  expect(position.pixels, moreOrLessEquals(2 * page));
  await press(tester, LogicalKeyboardKey.pageUp);
  expect(position.pixels, moreOrLessEquals(page));

  await press(tester, LogicalKeyboardKey.end);
  expect(position.pixels, position.maxScrollExtent);
  await press(tester, LogicalKeyboardKey.pageDown);
  expect(position.pixels, position.maxScrollExtent, reason: "clamped");

  await press(tester, LogicalKeyboardKey.home);
  expect(position.pixels, 0);

  await press(tester, LogicalKeyboardKey.arrowDown);
  expect(position.pixels, moreOrLessEquals(keyboardLineScroll));
  await press(tester, LogicalKeyboardKey.arrowUp);
  expect(position.pixels, 0);
}

/// Forty photographs, one a day.
String bigInbox() => inboxJson(photos: [
      for (var i = 0; i < 40; i++)
        ("p${i.toString().padLeft(2, "0")}.jpg", 2026, 1 + i ~/ 28, 1 + i % 28),
    ]);

/// A listing of sixty albums.
String bigListing() => '["ListingInfo", {"path": "", "title": "Library", '
    '"folders": [${[
      for (var i = 0; i < 60; i++) '{"name": "A$i", "title": "Album $i"}',
    ].join(", ")}]}]';

Future<void> pumpBigInbox(WidgetTester tester) =>
    pumpInbox(tester, (request) => inboxTree(request, album: bigInbox()));

Future<void> pumpBigAlbum(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson(60))));
    await tester.pumpAndSettle();
  });
}

void main() {
  group("the helper alone", () {
    Widget page({ScrollController? controller, bool textField = false}) =>
        MaterialApp(
          home: Scaffold(
            body: Column(children: [
              if (textField) const TextField(key: Key("text"), maxLines: 3),
              Expanded(
                child: KeyboardScroll(
                  controller: controller,
                  child: ListView(
                    controller: controller,
                    children: [
                      for (var i = 0; i < 100; i++)
                        SizedBox(height: 50, child: Text("row $i")),
                    ],
                  ),
                ),
              ),
            ]),
          ),
        );

    testWidgets("makes a controller of its own where there is none",
        (tester) async {
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(find.byType(Scrollbar), findsOneWidget);
      await expectPaging(tester);
    });

    testWidgets("drives the controller it is given, and no second one",
        (tester) async {
      var controller = ScrollController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(page(controller: controller));
      await tester.pumpAndSettle();
      var state =
          tester.state<KeyboardScrollState>(find.byType(KeyboardScroll));
      expect(state.controller, same(controller));
      await expectPaging(tester);
      expect(controller.positions, hasLength(1));
    });

    testWidgets("takes no key typed into a text field", (tester) async {
      await tester.pumpWidget(page(textField: true));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("text")));
      await tester.pumpAndSettle();
      for (var key in [
        LogicalKeyboardKey.pageDown,
        LogicalKeyboardKey.end,
        LogicalKeyboardKey.arrowDown,
      ]) {
        await press(tester, key);
        expect(positionOf(tester).pixels, 0, reason: "$key");
      }
      // A click on the page gives the keys back to it.
      await tester.tap(find.text("row 2"));
      await tester.pumpAndSettle();
      await press(tester, LogicalKeyboardKey.end);
      expect(positionOf(tester).pixels, positionOf(tester).maxScrollExtent);
    });

    testWidgets("the thumb stays visible on a desktop", (tester) async {
      debugDefaultTargetPlatformOverride = TargetPlatform.linux;
      try {
        await tester.pumpWidget(page());
        await tester.pumpAndSettle();
        expect(tester.widget<Scrollbar>(find.byType(Scrollbar)).thumbVisibility,
            isTrue);
        // The platform's own scrollbar is not drawn a second time.
        expect(find.byWidgetPredicate((widget) => widget is RawScrollbar),
            findsOneWidget);
      } finally {
        debugDefaultTargetPlatformOverride = null;
      }
    });

    testWidgets("a phone shows the thumb while scrolling only", (tester) async {
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(tester.widget<Scrollbar>(find.byType(Scrollbar)).thumbVisibility,
          isFalse);
    });
  });

  group("the inbox", () {
    testWidgets("shows a scrollbar", (tester) async {
      await pumpBigInbox(tester);
      expect(
          find.descendant(
              of: find.byType(InboxContent), matching: find.byType(Scrollbar)),
          findsOneWidget);
    });

    testWidgets("pages by the keys, without a click first", (tester) async {
      await pumpBigInbox(tester);
      await expectPaging(tester);
    });

    testWidgets("gets the keys back after a tile was clicked", (tester) async {
      await pumpBigInbox(tester);
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      await press(tester, LogicalKeyboardKey.pageDown);
      expect(positionOf(tester).pixels, 0, reason: "the page lost the focus");
      await tester.tapAt(tester.getRect(tile("p00.jpg")).center);
      await tester.pumpAndSettle();
      await press(tester, LogicalKeyboardKey.pageDown);
      expect(positionOf(tester).pixels, greaterThan(0));
    });
  });

  group("the album", () {
    testWidgets("shows a scrollbar and pages by the keys", (tester) async {
      useSurface(tester, const Size(800, 600));
      await pumpBigAlbum(tester);
      expect(find.byType(Scrollbar), findsOneWidget);
      await expectPaging(tester);
    });

    testWidgets("drives the one controller the edge scrolling drives",
        (tester) async {
      useSurface(tester, const Size(800, 600));
      await pumpBigAlbum(tester);
      var album = tester.state<AlbumContentState>(find.byType(AlbumContent));
      expect(album.albumScrollPosition, same(positionOf(tester)));
    });

    testWidgets("takes no key while a dialog's text field has the focus",
        (tester) async {
      useSurface(tester, const Size(800, 600));
      await pumpBigAlbum(tester);
      var dialog = showDialog<void>(
        context: tester.element(find.byType(AlbumContent)),
        builder: (context) => const Dialog(
          child: TextField(key: Key("dialog-text"), autofocus: true),
        ),
      );
      await tester.pumpAndSettle();
      await press(tester, LogicalKeyboardKey.pageDown);
      await press(tester, LogicalKeyboardKey.end);
      expect(positionOf(tester).pixels, 0);

      // Closed, the page has the keys again without a click.
      Navigator.of(tester.element(find.byKey(const Key("dialog-text")))).pop();
      await dialog;
      await tester.pumpAndSettle();
      await press(tester, LogicalKeyboardKey.pageDown);
      expect(positionOf(tester).pixels, greaterThan(0));
    });

    testWidgets("keeps the album's own keys", (tester) async {
      useSurface(tester, const Size(800, 600));
      await pumpBigAlbum(tester);
      var album = tester.state<AlbumContentState>(find.byType(AlbumContent));
      var before = album.minRating;
      await press(tester, LogicalKeyboardKey.minus);
      expect(album.minRating, isNot(before));
    });
  });

  group("the listing", () {
    testWidgets("shows a scrollbar and pages by the keys", (tester) async {
      useSurface(tester, const Size(800, 600));
      await pumpListing(tester, clientReturning(bigListing()));
      expect(find.byType(Scrollbar), findsOneWidget);
      await expectPaging(tester);
    });
  });
}
