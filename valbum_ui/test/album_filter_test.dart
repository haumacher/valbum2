import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The number of image tiles currently shown.
int tileCount() => find.byType(Image).evaluate().length;

/// The threshold the filter control displays.
String threshold(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("minRating"))).data!;

/// The [IconButton] carrying the given icon.
IconButton buttonFor(WidgetTester tester, IconData icon) => tester.widget(
      find.ancestor(
        of: find.byIcon(icon),
        matching: find.byType(IconButton),
      ),
    );

Future<void> showMore(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.add_circle_outline));
  await tester.pumpAndSettle();
}

Future<void> showLess(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.remove_circle_outline));
  await tester.pumpAndSettle();
}

void main() {
  group('the rating filter helpers', () {
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
        expect(threshold(tester), "≥ 0");
        expect(tileCount(), 2);
        expect(find.text("Alle Bewertungen"), findsOneWidget);

        // `+` shows one level more.
        await showMore(tester);
        expect(threshold(tester), "≥ -1");
        expect(tileCount(), 3);

        // -1 is the floor of the GWT client: the trash stays hidden and the
        // button that would show it is disabled.
        expect(buttonFor(tester, Icons.add_circle_outline).onPressed, isNull);
        expect(tileCount(), 3);

        // `-` narrows the filter down to the starred image.
        await showLess(tester);
        expect(threshold(tester), "≥ 0");
        expect(tileCount(), 2);
        await showLess(tester);
        expect(threshold(tester), "≥ 1");
        expect(tileCount(), 1);
        await showLess(tester);
        expect(threshold(tester), "≥ 2");
        expect(tileCount(), 1);

        // 2 is the ceiling.
        expect(
          buttonFor(tester, Icons.remove_circle_outline).onPressed,
          isNull,
        );

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
        expect(threshold(tester), "≥ 1");
        expect(tileCount(), 1);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadAdd);
        await tester.pumpAndSettle();
        expect(threshold(tester), "≥ 0");
        expect(tileCount(), 2);

        await tester.sendKeyEvent(LogicalKeyboardKey.numpadAdd);
        await tester.pumpAndSettle();
        expect(threshold(tester), "≥ -1");
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
}
