/// Probe of the stacks of #257 (package 6) with the keyboard walk of the
/// grid (#295): a folded burst is one stop of the arrows, Enter opens it in
/// place, the arrows then walk through its shots, and the viewer opened from
/// a shot pages on through the whole album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'album_stacks_test.dart' show pumpBurst, tiles;
import 'tile_keys_test.dart' show desk, highlighted, press;
import 'util/viewer_harness.dart';

void main() {
  setUp(withEmptyImageCache);

  testWidgets('the arrows walk a folded burst as one tile, an open one shot '
      'by shot', (tester) async {
    await withFakeImageHttp(() async {
      desk(tester);
      await pumpBurst(tester);
      expect(tiles(), ["p2.jpg", "x.jpg"]);
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "p2.jpg");
      await press(tester, LogicalKeyboardKey.arrowRight);
      expect(highlighted(tester), "x.jpg", reason: "past the folded burst");
      await press(tester, LogicalKeyboardKey.arrowLeft);
      expect(highlighted(tester), "p2.jpg");

      await press(tester, LogicalKeyboardKey.enter);
      expect(tiles(), containsAll(["p0.jpg", "p1.jpg", "p3.jpg", "p4.jpg"]),
          reason: "Enter opened the burst in place");
      await press(tester, LogicalKeyboardKey.arrowLeft);
      await press(tester, LogicalKeyboardKey.arrowLeft);
      expect(highlighted(tester), "p0.jpg",
          reason: "open, the shots stand in recording order");
      var walked = <Object?>[highlighted(tester)];
      for (var n = 0; n < 5; n++) {
        await press(tester, LogicalKeyboardKey.arrowRight);
        walked.add(highlighted(tester));
      }
      expect(walked.toSet(), containsAll(["p0.jpg", "p1.jpg", "p2.jpg",
          "p3.jpg", "p4.jpg", "x.jpg"]),
          reason: "every shot is a stop once the burst is open: $walked");
    });
  });
}
