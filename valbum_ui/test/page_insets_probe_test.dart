// Probe of issue #171: a phone on its side carries its navigation bar at the
// side. The server screen and the trash keep every control clear of it, on
// either side, and still end above a bar at the bottom.
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';

import 'page_insets_test.dart';

void main() {
  for (var side in ["left", "right"]) {
    for (var (name, pump) in [
      ("the server screen", pumpSettings),
      ("the trash", pumpTrash),
    ]) {
      testWidgets("$name keeps clear of a bar on the $side", (tester) async {
        useScreen(
          tester,
          landscape,
          left: side == "left" ? bar : 0,
          right: side == "right" ? bar : 0,
          bottom: 24,
        );
        await pump(tester);
        // Everything of the page body; the app bar is the Scaffold's own.
        var body = find.byType(Scaffold).first;

        var clickable = [
          ...find.descendant(of: body, matching: find.byType(Text)).evaluate().where((e) => find.descendant(of: find.byType(AppBar), matching: find.byWidget(e.widget)).evaluate().isEmpty),
          ...find.byType(Image).evaluate(),
        ];
        expect(clickable, isNotEmpty);
        for (var element in clickable) {
          var box = element.renderObject! as RenderBox;
          if (!box.hasSize || !box.attached) {
            continue;
          }
          var rect = box.localToGlobal(Offset.zero) & box.size;
          if (rect.bottom < 0 || rect.top > landscape.height) {
            continue; // scrolled out of view
          }
          if (side == "left") {
            expect(rect.left, greaterThanOrEqualTo(bar), reason: "$element");
          } else {
            expect(rect.right, lessThanOrEqualTo(landscape.width - bar),
                reason: "$element");
          }
        }
      });
    }
  }
}
