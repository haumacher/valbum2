/// Probe of the album-selects-by-tap picker: a tap moves the suggestion's
/// selection, never adds a second one; going up forgets it; the confirm acts
/// on the album tapped last.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'folder_picker_select_test.dart';

void main() {
  testWidgets('a tap moves the suggested selection to the album tapped',
      (tester) async {
    var outcome = await openPicker(
        tester, (api) => imageMove(api, near: DateTime(2002, 12, 20)));
    expect(pathLine(tester), "2002 / ${albumName(50)}");

    await tester.scrollUntilVisible(
        find.byKey(Key("picker-folder-${albumName(3)}")), -200,
        scrollable: find
            .descendant(
                of: find.byKey(const Key("folder-picker")),
                matching: find.byType(Scrollable))
            .last);
    await tapRow(tester, albumName(3));
    expect(find.byKey(const Key("picker-selected")), findsOneWidget,
        reason: "one selection, not two");
    expect(row(tester, albumName(3)).selected, isTrue);
    expect(pathLine(tester), "2002 / ${albumName(3)}");

    await tapKey(tester, "picker-up");
    expect(find.byKey(const Key("picker-selected")), findsNothing);
    expect(confirm(tester).onPressed, isNull,
        reason: "the top level takes no images");

    await tapRow(tester, "2002");
    await tapRow(tester, albumName(7));
    await tapKey(tester, "picker-confirm");
    expect(outcome.closed, isTrue);
    expect(outcome.picked!.path, ["2002", albumName(7)]);
  });
}
