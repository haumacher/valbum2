/// Probe of #305: the status indicator stands on pages that had none before
/// (the search, the persons) and leads to the status page from there.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/status.dart' show statusIndicatorKey, StatusPage;

import 'util/photo_space.dart';

void main() {
  for (var (name, route) in const [
    ("the search", SearchRoute([])),
    ("the persons", PersonsRoute([])),
  ]) {
    testWidgets("$name shows the indicator, and it opens the status page",
        (tester) async {
      await openSpace(tester, PhotoSpace(), route);
      expect(find.byKey(statusIndicatorKey), findsOneWidget);
      await tester.tap(find.byKey(statusIndicatorKey));
      await settle(tester);
      expect(find.byType(StatusPage), findsOneWidget);
    });
  }
}
