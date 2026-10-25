/// Probe of #305 and #309: the status indicator stands on the start page and
/// leads to the status page from there — and on no other page, the search and
/// the persons among them, which #305 had given it and #309 took it from.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/photo_space.dart';

void main() {
  testWidgets("the start page shows the indicator, and it opens the status "
      "page", (tester) async {
    await openSpace(tester, PhotoSpace(), ListingOrAlbumRoute.root);
    expect(find.byKey(statusIndicatorKey), findsOneWidget);
    await tester.tap(find.byKey(statusIndicatorKey));
    await settle(tester);
    expect(find.byType(StatusPage), findsOneWidget);
  });

  for (var (name, route, up) in const [
    ("the search", SearchRoute([]), "search-up"),
    ("the persons", PersonsRoute(["trip"]), "persons-up"),
  ]) {
    testWidgets("$name shows no indicator", (tester) async {
      await openSpace(tester, PhotoSpace(), route);
      expect(find.byKey(Key(up)), findsOneWidget, reason: "the page is shown");
      expect(find.byKey(statusIndicatorKey), findsNothing);
    });
  }
}
