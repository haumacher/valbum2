/// A dialog on a phone uses the width of the screen, see [appTheme]: 12
/// pixels to either edge, not Flutter's 40.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/app.dart';

void main() {
  testWidgets('a dialog on a phone keeps 12 pixels from the edges',
      (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: appTheme(),
      home: Builder(
        builder: (context) => TextButton(
          child: const Text("open"),
          onPressed: () => showDialog<void>(
            context: context,
            builder: (context) => const AlertDialog(
              key: Key("dialog"),
              content: SizedBox(width: 400, child: Text("wide content")),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text("open"));
    await tester.pumpAndSettle();

    var box = tester.getRect(find
        .descendant(
            of: find.byKey(const Key("dialog")), matching: find.byType(Material))
        .first);
    expect(box.left, 12);
    expect(box.width, 336);
  });
}
