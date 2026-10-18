/// The text of the search field sits on the centre line of the search bar,
/// with the chips and icons beside it — at the default text scale and at
/// 1.3, and with the AI switch and the chip of the cut beside it (issue
/// #302): the underline border once put it at the top of the row its prefix
/// button makes, visibly higher than the chips' labels.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'search_similarity_cut_test.dart' show CutSpace, searchByContent;
import 'search_view_test.dart' show pumpRoute, settle;
import 'util/fake_image_http.dart';

/// The vertical centre of what [finder] finds.
double centreOf(WidgetTester tester, Finder finder) =>
    tester.getRect(finder).center.dy;

/// The centre of the text of the search field.
double fieldTextCentre(WidgetTester tester) => centreOf(
      tester,
      find.descendant(
        of: find.byKey(const Key("search-text")),
        matching: find.byType(EditableText),
      ),
    );

/// The centre of the label of the chip [key].
double chipLabelCentre(WidgetTester tester, String key) => centreOf(
      tester,
      find
          .descendant(of: find.byKey(Key(key)), matching: find.byType(Text))
          .first,
    );

Future<void> wide(WidgetTester tester) async {
  // Wide enough that the field and the chips after it share a row.
  await tester.binding.setSurfaceSize(const Size(3600, 1200));
  addTearDown(() => tester.binding.setSurfaceSize(null));
}

void main() {
  for (var scale in [1.0, 1.3]) {
    testWidgets('the field\'s text is on the chips\' centre line at $scale',
        (tester) async {
      await wide(tester);
      tester.platformDispatcher.textScaleFactorTestValue = scale;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      var space = CutSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      await tester.enterText(find.byKey(const Key("search-text")), "sea");
      await settle(tester);

      var field = fieldTextCentre(tester);
      for (var chip in ["search-by-content", "search-group-by"]) {
        expect(
          (field - chipLabelCentre(tester, chip)).abs(),
          lessThanOrEqualTo(2),
          reason: "Beside $chip at $scale",
        );
      }
      // The search icon in front of the field as well.
      expect(
        (field - centreOf(tester, find.byIcon(Icons.search))).abs(),
        lessThanOrEqualTo(2),
      );

      // With the AI search on and the chip of the cut beside it.
      await searchByContent(tester, "sea");
      expect(find.byKey(const Key("search-similarity")), findsOneWidget);
      field = fieldTextCentre(tester);
      for (var chip in ["search-by-content", "search-similarity"]) {
        expect(
          (field - chipLabelCentre(tester, chip)).abs(),
          lessThanOrEqualTo(2),
          reason: "Beside $chip at $scale, the AI search on",
        );
      }
      await withFakeImageHttp(() async {});
    });
  }
}
