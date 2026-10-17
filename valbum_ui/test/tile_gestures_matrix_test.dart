/// The tile shell of issue #290: in every view and every mode a secondary
/// click and a long press on a photo tile lead to a menu, a mode change or a
/// change of the selection — never to nothing (the search view's select mode
/// had no menu at all). In a mode with a selection the menu acts on the whole
/// selection when the tile is part of it, and on the tile alone otherwise.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/l10n.dart';
import 'util/photo_space.dart';

/// What the screen is in: its mode and what is selected.
typedef Shown = (String mode, Set<String> selection);

Shown shownOf(WidgetTester tester) {
  var mode = find.byKey(const Key("select-mode-bar")).evaluate().isNotEmpty
      ? "select"
      : find.byKey(const Key("edit-cancel")).evaluate().isNotEmpty
          ? "edit"
          : "view";
  Set<String> selection;
  if (find.byType(InboxContent).evaluate().isNotEmpty) {
    selection = {
      for (var image in tester
          .state<InboxContentState>(find.byType(InboxContent))
          .selected)
        image.name,
    };
  } else {
    selection = {
      for (var part in tester
          .state<AlbumContentState>(find.byType(AlbumContent).last)
          .selection)
        if (part is AbstractImage) part.thumbnailName,
    };
  }
  return (mode, selection);
}

/// One view of the matrix: how it is reached, and the tile it is asked on.
class Case {
  final String name;
  final PhotoSpace Function() space;
  final VAlbumRoute route;
  final Future<void> Function(WidgetTester tester)? prepare;
  final String tile;

  Case(this.name, this.space, this.route, this.tile, {this.prepare});
}

PhotoSpace editor() => PhotoSpace();

PhotoSpace viewer() =>
    PhotoSpace(role: "view", rights: const ["view", "download"]);

final List<Case> cases = [
  Case("album, view mode, editor", editor, const ListingOrAlbumRoute(["trip"]),
      "b.jpg"),
  Case("album, view mode, member", viewer, const ListingOrAlbumRoute(["trip"]),
      "b.jpg"),
  Case("album, edit mode", editor, const ListingOrAlbumRoute(["trip"]), "b.jpg",
      prepare: (tester) => longPressOn(tester, "a.jpg")),
  Case("album, select mode", viewer, const ListingOrAlbumRoute(["trip"]),
      "b.jpg",
      prepare: (tester) => longPressOn(tester, "a.jpg")),
  Case("search, view mode", editor, const SearchRoute([]), "trip/b.jpg",
      prepare: chooseAnne),
  Case("search, select mode", editor, const SearchRoute([]), "trip/b.jpg",
      prepare: (tester) async {
    await chooseAnne(tester);
    await longPressOn(tester, "trip/a.jpg");
  }),
  Case("saved search", editor, const ListingOrAlbumRoute(["Saved"]),
      "trip/b.jpg"),
  Case("collection", editor, const ListingOrAlbumRoute(["Best"]), "b.jpg"),
  Case("news", editor, const NewsRoute(["trip"], newsKey), "b.jpg"),
  Case("inbox", editor, const ListingOrAlbumRoute(["Inbox"]), "b.jpg"),
];

void main() {
  for (var view in cases) {
    for (var gesture in ["secondary click", "long press"]) {
      testWidgets('${view.name}: a $gesture is never nothing', (tester) async {
        await openSpace(tester, view.space(), view.route);
        await view.prepare?.call(tester);
        var before = shownOf(tester);
        expect(menuOpen(), isFalse);
        expect(
            before.$1,
            view.name.contains("select mode")
                ? "select"
                : view.name.contains("edit mode")
                    ? "edit"
                    : "view");

        if (gesture == "secondary click") {
          await rightClick(tester, view.tile);
          expect(menuOpen(), isTrue,
              reason: "A secondary click opens the tile's menu.");
        } else {
          await longPressOn(tester, view.tile);
          var after = shownOf(tester);
          expect(
            menuOpen() || after.$1 != before.$1 || after.$2 != before.$2,
            isTrue,
            reason: "A long press opens the menu, changes the mode or the "
                "selection.",
          );
        }
      });
    }
  }

  group('the menu of the select mode', () {
    Future<void> pumpTwoSelected(WidgetTester tester, VAlbumRoute route,
        String first, String second) async {
      await openSpace(tester, viewer(), route);
      if (route is SearchRoute) {
        await chooseAnne(tester);
      }
      await longPressOn(tester, first);
      await tester.tap(find.descendant(
          of: tileOf(second), matching: find.byKey(const Key("select-mark"))));
      await settle(tester);
    }

    for (var (route, a, b, c) in [
      (const ListingOrAlbumRoute(["trip"]), "a.jpg", "b.jpg", "c.jpg"),
      (const SearchRoute([]), "trip/a.jpg", "trip/b.jpg", "trip/c.jpg"),
    ]) {
      testWidgets('acts on the selection in $route', (tester) async {
        await pumpTwoSelected(tester, route, a, b);
        await rightClick(tester, b);
        expect(
          find.descendant(
              of: find.byKey(const Key("tile-context-download")),
              matching: find.text(testL10n.downloadSelection(2))),
          findsOneWidget,
        );
        expect(
          find.descendant(
              of: find.byKey(const Key("tile-context-collect")),
              matching: find.text(testL10n.addCountToCollection(2))),
          findsOneWidget,
        );
        expect(shownOf(tester).$2, {a, b});
      });

      testWidgets('on an unselected tile, on that tile alone in $route',
          (tester) async {
        await pumpTwoSelected(tester, route, a, b);
        await rightClick(tester, c);
        expect(shownOf(tester).$2, {c});
        expect(
          find.descendant(
              of: find.byKey(const Key("tile-context-download")),
              matching: find.text(testL10n.downloadSelection(1))),
          findsOneWidget,
        );
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await settle(tester);
      });
    }
  });
}
