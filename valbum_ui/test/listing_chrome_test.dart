/// The listing's chrome, where issue #100 put it: the way back at the left,
/// the three-dots menu last at the right.
///
/// The same order on every page of the app — the listing, the album and the
/// viewer — so that the way out is always in the same corner. Up used to sit
/// among the *actions*, at the right, behind Home.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// A listing of one folder, answered for every path asked for.
String listing(String title) =>
    '["ListingInfo", {"path": "", "title": "$title", '
    '"folders": [{"name": "Zoo", "title": "Zoo"}]}]';

VAlbumClient listingServer() => clientHandling(
      (request) => http.Response(
        listing("My albums"),
        200,
        headers: const {"content-type": "application/json; charset=utf-8"},
      ),
    );

/// The app bar of the listing on the screen.
AppBar barOf(WidgetTester tester) =>
    tester.widget<AppBar>(find.byType(AppBar).last);

/// The tooltips of the icon buttons in the bar's actions, in order.
List<String?> actionTooltips(AppBar bar) => [
      for (var action in bar.actions!)
        if (action is IconButton) action.tooltip,
    ];

void main() {
  testWidgets('below the root the way back is the leading control',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: listingServer(),
        initialRoute: const ListingOrAlbumRoute(["Zoo"]),
      ));
      await tester.pumpAndSettle();

      var bar = barOf(tester);
      expect(bar.leading, isA<IconButton>());
      expect((bar.leading! as IconButton).tooltip, "Up");

      // Home stays an action, and the menu is the last control at the right.
      expect(actionTooltips(bar), ["Home"]);
      expect(bar.actions!.last, isA<PopupMenuButton>());
      expect(
        find.descendant(
          of: find.byType(AppBar).last,
          matching: find.byTooltip("Up"),
        ),
        findsOneWidget,
      );
    });
  });

  testWidgets('at the root there is nothing to go up to', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: listingServer()));
      await tester.pumpAndSettle();

      var bar = barOf(tester);
      expect(bar.leading, isNull, reason: "this is the home");
      expect(find.byTooltip("Up"), findsNothing);
      expect(find.byTooltip("Home"), findsNothing);
      // The menu is still the last control.
      expect(bar.actions!.last, isA<PopupMenuButton>());
    });
  });

  testWidgets('the way back still goes up', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: listingServer(),
        initialRoute: const ListingOrAlbumRoute(["Zoo"]),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip("Up"));
      await tester.pumpAndSettle();

      expect(barOf(tester).leading, isNull, reason: "the root was reached");
    });
  });
}
