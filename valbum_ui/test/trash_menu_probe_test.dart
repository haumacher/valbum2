/// Probe of issue #152 composed with the edit mode: "Show trash" is an entry
/// of the album's menu in the view mode only — a restore written from the
/// trash page would carry an unsaved edit along — and comes back when the edit
/// mode is left.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'trash_view_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

Future<void> closeMenu(WidgetTester tester) async {
  await tester.sendKeyEvent(LogicalKeyboardKey.escape);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('"Show trash" steps aside while the album is being edited',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pump(tester, server(requests: requests, role: "edit"));

      await openMenu(tester);
      expect(find.byKey(const Key("show-trash")), findsOneWidget);
      await closeMenu(tester);

      // The long press of a tile enters the edit mode.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await openMenu(tester);
      expect(find.byKey(const Key("show-trash")), findsNothing,
          reason: "an edit in progress must not ride along with a restore");
      await closeMenu(tester);

      // Cancel leaves the edit mode; the entry is back.
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      await openMenu(tester);
      expect(find.byKey(const Key("show-trash")), findsOneWidget);
      expect(find.text(testL10n.showTrash), findsOneWidget);
    });
  });
}
