/// Probe of the stacks of #257 (package 6) with the keyboard walk of the
/// grid (#295): a folded burst is one stop of the arrows, and Enter opens
/// the viewer on its best shot, as on a group's tile (#314).
library;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_stacks_test.dart' show pumpBurst, routeOf, tiles;
import 'tile_keys_test.dart' show desk, highlighted, press;
import 'util/viewer_harness.dart';

void main() {
  setUp(withEmptyImageCache);

  testWidgets('the arrows walk a burst as one tile, Enter opens its best shot',
      (tester) async {
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
      expect(routeOf(tester), const ImageRoute(["Burst"], "p2.jpg"));
      await press(tester, LogicalKeyboardKey.escape);
      expect(tiles(), ["p2.jpg", "x.jpg"], reason: "nothing unfolded");
    });
  });
}
