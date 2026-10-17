/// The keyboard of the grids (issue #295): the arrows walk the tiles in
/// reading order and row by row as they are laid out, `Enter` opens (or
/// toggles where the grid selects), `Space` toggles, `Shift+F10` and the
/// context-menu key open the tile's menu at the tile, `Ctrl+A` selects all —
/// one mechanism ([TileHighlight]) in the album, the search, a collection,
/// the view of an entry of the bell and the listing; and a text field keeps
/// its keys.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/album_layout.dart' show ToImage;
import 'package:valbum_ui/tile_keys.dart';

import 'util/photo_space.dart';

/// The entry of the tile the ring is drawn on, `null` for none.
Object? highlighted(WidgetTester tester) {
  var ring = find.byKey(HighlightedTile.ringKey);
  if (ring.evaluate().isEmpty) {
    return null;
  }
  expect(ring, findsOneWidget);
  var tile = tester.widget<HighlightedTile>(find
      .ancestor(
          of: ring,
          matching: find.byWidgetPredicate((w) => w is HighlightedTile))
      .first);
  var entry = tile.entry;
  if (entry is AbstractImage) {
    return ToImage.toImage(entry).name;
  }
  if (entry is FolderInfo) {
    return entry.name;
  }
  return entry;
}

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) async {
  await tester.sendKeyEvent(key);
  await settle(tester);
}

Future<void> pressWith(WidgetTester tester, LogicalKeyboardKey modifier,
    LogicalKeyboardKey key) async {
  await tester.sendKeyDownEvent(modifier);
  await tester.sendKeyEvent(key);
  await tester.sendKeyUpEvent(modifier);
  await settle(tester);
}

/// The selection of the album page shown.
Set<String> selectionOf(WidgetTester tester) => {
      for (var part in tester
          .state<AlbumContentState>(find.byType(AlbumContent).last)
          .selection)
        if (part is AbstractImage) ToImage.toImage(part).name,
    };

void desk(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// A listing of three folders, a row of them on the desk.
class FolderSpace extends PhotoSpace {
  @override
  http.Response answer(http.Request request) {
    if (Uri.decodeFull(request.url.path) == "/valbum/data/" &&
        request.url.queryParameters["type"] == "json") {
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"rights": ${rightsOf(rights)}, "folders": ['
          '{"name": "trip", "title": "Trip", "kind": "ALBUM"}, '
          '{"name": "zoo", "title": "Zoo", "kind": "ALBUM"}, '
          '{"name": "more", "title": "More", "kind": "FOLDER"}]}]');
    }
    return super.answer(request);
  }
}

void main() {
  group('the album grid', () {
    testWidgets('the arrows walk the tiles, row by row as laid out',
        (tester) async {
      desk(tester);
      await openSpace(
          tester, PhotoSpace(extra: 9), const ListingOrAlbumRoute(["trip"]));
      expect(highlighted(tester), isNull,
          reason: "No highlight before the keyboard is used.");

      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "a.jpg");
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "b.jpg");
      await press(tester, LogicalKeyboardKey.arrowLeft);
      await press(tester, LogicalKeyboardKey.arrowLeft);
      expect(highlighted(tester), "a.jpg", reason: "It stops at the edge.");

      // Down lands on a tile of the next row, below the first one.
      var first = tester.getRect(tileOf("a.jpg"));
      await press(tester, LogicalKeyboardKey.arrowDown);
      var below = highlighted(tester) as String;
      var rect = tester.getRect(tileOf(below));
      expect(rect.top, greaterThan(first.bottom - 1));
      expect(rect.left, lessThan(first.right));
      await press(tester, LogicalKeyboardKey.arrowUp);
      expect(highlighted(tester), "a.jpg");
      expect(tester.takeException(), isNull);
    });

    testWidgets('Enter opens the viewer on the highlighted photo',
        (tester) async {
      desk(tester);
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["trip"]));
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.enter);
      var route = routeOf(tester);
      expect(route, isA<ImageRoute>());
      expect((route as ImageRoute).name, "b.jpg");
    });

    testWidgets('Shift+F10 and the menu key open its menu', (tester) async {
      desk(tester);
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["trip"]));
      await press(tester, LogicalKeyboardKey.arrowRight);
      await pressWith(
          tester, LogicalKeyboardKey.shiftLeft, LogicalKeyboardKey.f10);
      expect(find.byKey(const Key("tile-context-open")), findsOneWidget);
      // The menu stands at the tile.
      var tile = tester.getRect(tileOf("a.jpg"));
      expect(
          tile.inflate(200).contains(
              tester.getTopLeft(find.byKey(const Key("tile-context-open")))),
          isTrue);
      await press(tester, LogicalKeyboardKey.escape);
      expect(menuOpen(), isFalse);

      await press(tester, LogicalKeyboardKey.contextMenu);
      expect(find.byKey(const Key("tile-context-open")), findsOneWidget);
    });

    testWidgets('in the select mode Space toggles and Ctrl+A selects all',
        (tester) async {
      desk(tester);
      await openSpace(
          tester,
          PhotoSpace(role: "view", rights: const ["view", "download"]),
          const ListingOrAlbumRoute(["trip"]));
      await longPressOn(tester, "a.jpg");
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(selectionOf(tester), {"a.jpg"});

      // The long press put the highlight on a.jpg, unseen: the first arrow
      // shows it there, the next one moves it.
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "a.jpg");
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "b.jpg");
      await press(tester, LogicalKeyboardKey.space);
      expect(selectionOf(tester), {"a.jpg", "b.jpg"});
      await press(tester, LogicalKeyboardKey.space);
      expect(selectionOf(tester), {"a.jpg"});
      // Enter toggles in a mode that selects, and opens no viewer.
      await press(tester, LogicalKeyboardKey.enter);
      expect(selectionOf(tester), {"a.jpg", "b.jpg"});
      expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));

      await pressWith(
          tester, LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.keyA);
      expect(selectionOf(tester), {"a.jpg", "b.jpg", "c.jpg"});

      await press(tester, LogicalKeyboardKey.escape);
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
    });

    testWidgets('the edit mode selects by the same keys', (tester) async {
      desk(tester);
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["trip"]));
      await longPressOn(tester, "a.jpg");
      expect(find.byKey(const Key("edit-cancel")), findsOneWidget);
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.space);
      expect(selectionOf(tester), {"a.jpg", "b.jpg"});
      await pressWith(
          tester, LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.keyA);
      expect(selectionOf(tester), {"a.jpg", "b.jpg", "c.jpg"});
      await pressWith(
          tester, LogicalKeyboardKey.shiftLeft, LogicalKeyboardKey.f10);
      expect(find.byKey(const Key("tile-context-move")), findsOneWidget);
    });

    testWidgets('in the view mode Space and Ctrl+A select nothing',
        (tester) async {
      desk(tester);
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["trip"]));
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.space);
      await pressWith(
          tester, LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.keyA);
      expect(selectionOf(tester), isEmpty);
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
    });
  });

  testWidgets('a collection walks the same way', (tester) async {
    desk(tester);
    await openSpace(tester, PhotoSpace(), const ListingOrAlbumRoute(["Best"]));
    await press(tester, LogicalKeyboardKey.arrowRight);
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(highlighted(tester), "b.jpg");
    await press(tester, LogicalKeyboardKey.enter);
    expect(routeOf(tester), isA<ImageRoute>());
  });

  testWidgets('the view of an entry of the bell walks the same way',
      (tester) async {
    desk(tester);
    await openSpace(tester, PhotoSpace(), const NewsRoute(["trip"], newsKey));
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(highlighted(tester), "a.jpg");
    await press(tester, LogicalKeyboardKey.enter);
    expect(routeOf(tester), isA<ImageRoute>());
  });

  group('the search', () {
    testWidgets('its grid walks the same way', (tester) async {
      desk(tester);
      await openSpace(tester, PhotoSpace(), const SearchRoute([]));
      await chooseAnne(tester);
      // The chooser gave the focus back to the page.
      await press(tester, LogicalKeyboardKey.arrowRight);
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "trip/b.jpg");
      await press(tester, LogicalKeyboardKey.enter);
      expect(routeOf(tester), isA<ImageRoute>());
    });

    testWidgets('the arrows in its field still move the cursor',
        (tester) async {
      desk(tester);
      await openSpace(tester, PhotoSpace(), const SearchRoute([]));
      await chooseAnne(tester);
      var field = find.byKey(const Key("search-text"));
      await tester.tap(field);
      await tester.enterText(field, "rome");
      await settle(tester);
      var editable = tester.widget<EditableText>(
          find.descendant(of: field, matching: find.byType(EditableText)));
      expect(editable.controller.selection.baseOffset, 4);
      await press(tester, LogicalKeyboardKey.arrowLeft);
      await press(tester, LogicalKeyboardKey.arrowLeft);
      expect(editable.controller.selection.baseOffset, 2);
      expect(highlighted(tester), isNull);
    });
  });

  group('the listing', () {
    testWidgets('the arrows walk the folders, Enter opens one', (tester) async {
      desk(tester);
      await openSpace(tester, FolderSpace(), const ListingOrAlbumRoute([]));
      // The tiles in the order they are drawn, left to right.
      var order = ["trip", "zoo", "more"]..sort((a, b) => tester
          .getRect(find.byKey(ValueKey("tile-$a")))
          .left
          .compareTo(tester.getRect(find.byKey(ValueKey("tile-$b"))).left));
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), order[0]);
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), order[1]);
      await press(tester, LogicalKeyboardKey.enter);
      expect(routeOf(tester), ListingOrAlbumRoute([order[1]]));
    });

    testWidgets('Shift+F10 opens the menu of the folder', (tester) async {
      desk(tester);
      await openSpace(tester, FolderSpace(), const ListingOrAlbumRoute([]));
      await press(tester, LogicalKeyboardKey.arrowRight);
      await pressWith(
          tester, LogicalKeyboardKey.shiftLeft, LogicalKeyboardKey.f10);
      expect(find.byKey(const Key("delete-entry")), findsOneWidget);
    });
  });
}
