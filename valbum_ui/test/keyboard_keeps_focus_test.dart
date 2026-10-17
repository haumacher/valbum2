/// A phone's keyboard shrinks a chooser below the height its list scrolls by
/// itself in, and the whole content scrolls instead (issue #178). That switch
/// must not rebuild the search field: a field built anew loses the cursor, the
/// keyboard goes down, the dialog is roomy again — and the keyboard flickers
/// up and down without a letter ever reaching the field.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/upload_target_view.dart';

import 'folder_picker_search_test.dart' show SearchSpace, field;
import 'person_chooser_test.dart' show pumpChooser;
import 'upload_target_view_test.dart' show pumpSection, tapKey;

/// A phone, its keyboard down.
void phone(WidgetTester tester) {
  tester.view.physicalSize = const Size(360, 640);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// The keyboard comes up, as the cursor in a field asks it to.
Future<void> keyboardUp(WidgetTester tester) async {
  tester.view.viewInsets = const FakeViewPadding(bottom: 300);
  await tester.pumpAndSettle();
}

bool focused(WidgetTester tester, Finder field) => tester
    .widget<EditableText>(
        find.descendant(of: field, matching: find.byType(EditableText)))
    .focusNode
    .hasFocus;

void main() {
  testWidgets('the folder picker keeps the cursor as the keyboard comes up',
      (tester) async {
    phone(tester);
    await pumpSection(tester, client: SearchSpace().client);
    await tapKey(tester, uploadTargetChooseKey);
    await tester.tap(field);
    await tester.pumpAndSettle();
    expect(focused(tester, field), isTrue);

    await keyboardUp(tester);
    expect(focused(tester, field), isTrue,
        reason: "the keyboard must stay up for the typing");
  });

  testWidgets('the person chooser keeps the cursor as the keyboard comes up',
      (tester) async {
    phone(tester);
    await pumpChooser(tester);
    var search = find.byKey(const Key("persons-search"));
    expect(focused(tester, search), isTrue, reason: "it opens with the cursor");

    await keyboardUp(tester);
    expect(focused(tester, search), isTrue,
        reason: "the keyboard must stay up for the typing");
  });
}
