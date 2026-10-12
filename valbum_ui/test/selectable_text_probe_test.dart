/// Probe for issue #250: a text field inside a selectable dialog keeps its
/// own selection, focus and clipboard keys, also after the dialog's text was
/// selected with the mouse.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/form_dialog.dart';

import 'selectable_text_test.dart' show dragAndCopy, recordClipboard;
import 'util/l10n.dart';

void main() {
  testWidgets('a field in a selectable dialog owns its focus and Ctrl+A/C',
      (tester) async {
    var copied = recordClipboard(tester);
    var controller = TextEditingController(text: "Camino del Norte");
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showFormDialog<void>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text("Name the album"),
              content: TextField(key: const Key("field"), controller: controller),
            ),
          ),
          child: const Text("open"),
        ),
      ),
    ));
    await tester.tap(find.text("open"));
    await tester.pumpAndSettle();

    // First the title is selected and copied with the mouse.
    await dragAndCopy(tester, find.text("Name the album"));
    expect(copied.last, "Name the album");

    // Then a click into the field: it takes the focus.
    var gesture = await tester.startGesture(
        tester.getCenter(find.byKey(const Key("field"))),
        kind: PointerDeviceKind.mouse);
    await gesture.up();
    await tester.pumpAndSettle();
    var editable = tester.widget<EditableText>(find.byType(EditableText));
    expect(editable.focusNode.hasFocus, isTrue,
        reason: "A click into the field focuses it.");

    // Ctrl+A, Ctrl+C there copy the field's text, not the dialog's.
    await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.keyA);
    await tester.sendKeyEvent(LogicalKeyboardKey.keyC);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
    await tester.pumpAndSettle();
    expect(copied.last, "Camino del Norte");
    expect(controller.selection,
        const TextSelection(baseOffset: 0, extentOffset: 16));
  });
}
