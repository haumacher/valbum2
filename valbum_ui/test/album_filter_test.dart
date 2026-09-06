import 'package:flutter/material.dart' hide Action;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The number of image tiles currently shown.
int tileCount() => find.byType(Image).evaluate().length;

/// Opens the album menu, the home of the rating filter.
Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert));
  await tester.pumpAndSettle();
}

/// Closes the album menu by tapping beside it.
Future<void> closeMenu(WidgetTester tester) async {
  await tester.tapAt(const Offset(1, 1));
  await tester.pumpAndSettle();
}

/// The threshold the album menu tells.
Future<String> threshold(WidgetTester tester) async {
  await openMenu(tester);
  var text = tester.widget<Text>(find.byKey(const Key("minRating"))).data!;
  await closeMenu(tester);
  return text;
}

/// Whether the menu entry carrying the given icon can be chosen.
Future<bool> enabled(WidgetTester tester, IconData icon) async {
  await openMenu(tester);
  var item = tester.widget<PopupMenuItem>(
    find.ancestor(
      of: find.byIcon(icon),
      matching: find.byType(PopupMenuItem<Action>),
    ),
  );
  await closeMenu(tester);
  return item.enabled;
}

Future<void> showMore(WidgetTester tester) async {
  await openMenu(tester);
  await tester.tap(find.byIcon(Icons.add_circle_outline));
  await tester.pumpAndSettle();
}

Future<void> showLess(WidgetTester tester) async {
  await openMenu(tester);
  await tester.tap(find.byIcon(Icons.remove_circle_outline));
  await tester.pumpAndSettle();
}

void main() {
  group('the rating filter helpers', () {
    test('let every rating through the alternatives view', () {
      // Once `-1 << 31`, which dart2js turns into a positive number.
      expect(noMinRating, lessThan(0));
      for (var rating = -2; rating <= 2; rating++) {
        expect(isVisiblePart(ImagePart(rating: rating), noMinRating), isTrue);
      }
    });

    test('walk between the GWT bounds', () {
      // `+`: `if (minRating > -1) minRating--`, `-`: `if (minRating < 2)
      // minRating++` of the retired GWT AlbumDisplay.
      expect(showMoreRating(0), -1);
      expect(showMoreRating(-1), -1);
      expect(showLessRating(0), 1);
      expect(showLessRating(1), 2);
      expect(showLessRating(2), 2);
      expect(minMinRating, -1);
      expect(maxMinRating, 2);
    });

    test('let headings through and rate a group by its representative', () {
      expect(isVisiblePart(Heading(text: "x"), 2), isTrue);

      var image = ImagePart(name: "a.jpg", rating: -1);
      expect(isVisiblePart(image, -1), isTrue);
      expect(isVisiblePart(image, 0), isFalse);

      var group = ImageGroup(
        representative: 1,
        images: [ImagePart(name: "a.jpg", rating: 2), image],
      );
      expect(ratingOf(group), -1);
      expect(isVisiblePart(group, 0), isFalse);
    });
  });

  group('the album rating filter', () {
    testWidgets('hides the images rated below the threshold', (tester) async {
      var client = clientReturning(fixture("album-ratings.json"));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        // The default threshold of a loaded album is 0: the images rated -1
        // and -2 are hidden.
        expect(await threshold(tester), "≥ 0");
        expect(tileCount(), 2);
        expect(find.text("Alle Bewertungen"), findsOneWidget);

        // `+` shows one level more.
        await showMore(tester);
        expect(await threshold(tester), "≥ -1");
        expect(tileCount(), 3);

        // -1 is the floor of the GWT client: the trash stays hidden and the
        // button that would show it is disabled.
        expect(await enabled(tester, Icons.add_circle_outline), isFalse);
        expect(tileCount(), 3);

        // `-` narrows the filter down to the starred image.
        await showLess(tester);
        expect(await threshold(tester), "≥ 0");
        expect(tileCount(), 2);
        await showLess(tester);
        expect(await threshold(tester), "≥ 1");
        expect(tileCount(), 1);
        await showLess(tester);
        expect(await threshold(tester), "≥ 2");
        expect(tileCount(), 1);

        // 2 is the ceiling.
        expect(await enabled(tester, Icons.remove_circle_outline), isFalse);

        // The heading stayed visible all the way.
        expect(find.text("Alle Bewertungen"), findsOneWidget);
      });
    });

    testWidgets('is bound to the + and - keys', (tester) async {
      var client = clientReturning(fixture("album-ratings.json"));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        await tester.sendKeyEvent(LogicalKeyboardKey.minus);
        await tester.pumpAndSettle();
        expect(await threshold(tester), "≥ 1");
        expect(tileCount(), 1);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadAdd);
        await tester.pumpAndSettle();
        expect(await threshold(tester), "≥ 0");
        expect(tileCount(), 2);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadAdd);
        await tester.pumpAndSettle();
        expect(await threshold(tester), "≥ -1");
        expect(tileCount(), 3);
      });
    });

    testWidgets('reflows the rows and is not saved to the sidecar',
        (tester) async {
      var client = clientReturning(fixture("album-ratings.json"));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        var narrow = tester.getSize(find.byType(Image).first);

        await showMore(tester);
        var wide = tester.getSize(find.byType(Image).first);

        // One more image in the row: every tile got smaller.
        expect(wide.width, lessThan(narrow.width));

        var state = tester.state<AlbumContentState>(find.byType(AlbumContent));
        expect(state.minRating, -1);
        // The threshold is transient, it never reaches the server.
        expect(state.widget.album.toString(), isNot(contains("minRating")));
      });
    });
  });

  group('a filter that hides everything', () {
    testWidgets('says so and keeps the title centred', (tester) async {
      // With no image left the content column used to shrink to the width of
      // the title, which then sat in the top left corner under the filter bar,
      // see issue #35.
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute(["album"]),
        ));
        await tester.pumpAndSettle();
      });
      expect(tileCount(), greaterThan(0));

      // Every image of the fixture is rated 0, so one step is enough.
      await showLess(tester);

      expect(await threshold(tester), "≥ 1");
      expect(tileCount(), 0);
      expect(
        find.textContaining("No image is rated 1 or better"),
        findsOneWidget,
        reason: "an empty page would look like an empty album",
      );

      var title = tester.getRect(find.text("Schlosspark Karlsruhe"));
      var available = tester.getRect(find.byType(Scaffold)).width;
      expect(
        title.center.dx,
        closeTo(available / 2, 1),
        reason: "the title stays centred without any image",
      );

      var menuButton = tester.getRect(find.byIcon(Icons.more_vert));
      expect(title.overlaps(menuButton), isFalse, reason: "the menu is beside");
      expect(
        title.top,
        lessThan(menuButton.bottom),
        reason: "the title shares the row of the floating controls, "
            "there is no gap above it",
      );
      expect(find.byTooltip("Home"), findsNothing);
    });
  });
}
