/// Probe of #306 with the grid's keyboard walk of #295: while the search
/// field has the focus, the arrows and Enter belong to the field — no tile is
/// highlighted, no photo is opened — and after leaving it the arrows walk the
/// answer again.
///
/// And the caret keys of a field inside a page's or a dialog's
/// [SelectableArea]: `ArrowUp`/`ArrowDown` threw a cast error from the
/// area's overridable action (`selectable.dart`), with and without `Shift`.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/selectable.dart';

import 'image_description_test.dart'
    show RecordingServer, imagePart, linkedAlbum, pumpViewer;
import 'util/fake_image_http.dart';

import 'search_view_test.dart' show Space, chooseAnne, pumpRoute, settle;
import 'tile_keys_test.dart' show highlighted;

void main() {
  testWidgets('arrows and Enter in the search field move no tile',
      (tester) async {
    await pumpRoute(tester, Space(), const SearchRoute([]));
    await chooseAnne(tester);
    await tester.tap(find.byKey(const Key("search-text")));
    await settle(tester);
    for (var key in [
      LogicalKeyboardKey.arrowRight,
      LogicalKeyboardKey.arrowDown,
      LogicalKeyboardKey.enter,
    ]) {
      await tester.sendKeyEvent(key);
      await settle(tester);
    }
    expect(highlighted(tester), isNull, reason: "the grid was not walked");
    expect(find.byType(ImageView), findsNothing, reason: "nothing opened");
  });

  /// The caret keys of a field, each pressed with or without `Shift`; none
  /// may throw.
  Future<void> caretKeys(WidgetTester tester) async {
    for (var (key, shift) in [
      (LogicalKeyboardKey.arrowDown, false),
      (LogicalKeyboardKey.arrowUp, false),
      (LogicalKeyboardKey.arrowDown, true),
      (LogicalKeyboardKey.arrowUp, true),
      (LogicalKeyboardKey.home, false),
      (LogicalKeyboardKey.end, false),
      (LogicalKeyboardKey.home, true),
      (LogicalKeyboardKey.end, true),
    ]) {
      if (shift) {
        await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
      }
      await tester.sendKeyEvent(key);
      if (shift) {
        await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
      }
      await tester.pump();
      expect(tester.takeException(), isNull,
          reason: "${shift ? "Shift+" : ""}${key.keyLabel}");
    }
  }

  testWidgets('the caret keys in the search field throw nothing and walk no tile',
      (tester) async {
    await pumpRoute(tester, Space(), const SearchRoute([]));
    await chooseAnne(tester);
    var field = find.byKey(const Key("search-text"));
    expect(find.ancestor(of: field, matching: find.byType(SelectableArea)),
        findsWidgets);
    await tester.tap(field);
    await settle(tester);
    await tester.enterText(field, "Meer");
    await settle(tester);

    await caretKeys(tester);
    await settle(tester);

    expect(highlighted(tester), isNull, reason: "the grid was not walked");
    expect(find.byType(ImageView), findsNothing, reason: "nothing opened");
  });

  testWidgets('the arrows move the caret between the lines of a dialog field',
      (tester) async {
    var image = imagePart("a.jpg", comment: "");
    linkedAlbum([image]);
    await pumpViewer(tester, image, server: RecordingServer());
    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.keyE, character: "e");
      await tester.pumpAndSettle();
    });
    expect(find.byType(TextInputDialog), findsOneWidget);
    var field = find.descendant(
        of: find.byType(TextInputDialog), matching: find.byType(TextField));
    expect(find.ancestor(of: field, matching: find.byType(SelectableArea)),
        findsWidgets);
    await tester.enterText(field, "Am Meer\nzweite Zeile");
    await tester.pump();
    var controller = tester.widget<TextField>(field).controller!;
    controller.selection = const TextSelection.collapsed(offset: 20);
    await tester.pump();

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(controller.selection.baseOffset, lessThan(8),
        reason: "ArrowUp took the caret to the first line");

    await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(controller.selection.extentOffset, greaterThan(8),
        reason: "Shift+ArrowDown extended the selection to the second line");
    expect(controller.selection.isCollapsed, isFalse);

    await caretKeys(tester);
  });
}
