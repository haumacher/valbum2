/// Probe of issue #316: the stacking switch and the rating floor composed —
/// switched in the album's menu while a shot is hidden, remembered for the
/// next start opened by link, and a link straight to the hidden best shot.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_stacks_test.dart' show albumState;
import 'album_stacks_walk_test.dart';
import 'util/viewer_harness.dart';

void main() {
  setUp(withEmptyImageCache);

  testWidgets('the switch in the menu, under a floor, holds for the next start',
      (tester) async {
    var store = stackingStore(stacking: true);
    var album = walkAlbum(ratings: {"a.jpg": -1});
    await withFakeImageHttp(() async {
      await pumpWalk(tester, album: album, store: store);
      expect(walkTiles(), ["s.jpg", "b.jpg", "z.jpg"]);

      albumState(tester).setStacking(false);
      await tester.pumpAndSettle();
      expect(walkTiles(), ["s.jpg", "b.jpg", "c.jpg", "z.jpg"]);
      await open(tester, "s.jpg");
      expect(await walk(tester, 3), ["s.jpg", "b.jpg", "c.jpg", "z.jpg"],
          reason: "Unstacked, every shown shot is a step; the hidden none.");
      await back(tester);

      albumState(tester).setStacking(true);
      await tester.pumpAndSettle();
      await open(tester, "s.jpg");
      expect(await walk(tester, 2), ["s.jpg", "b.jpg", "z.jpg"]);
      await back(tester);

      albumState(tester).setStacking(false);
      await tester.pumpAndSettle();
    });
    expect(store.albumStacking, isFalse);

    await tester.pumpWidget(const SizedBox());
    await withFakeImageHttp(() async {
      await pumpWalk(tester,
          album: album,
          store: store,
          route: const ImageRoute(["Walk"], "s.jpg"));
      expect(await walk(tester, 3), ["s.jpg", "b.jpg", "c.jpg", "z.jpg"],
          reason: "A new start by link pages as the switch was left.");
    });
  });

  testWidgets('a link to the hidden best shot walks on into the burst tile',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpWalk(tester,
          album: walkAlbum(ratings: {"a.jpg": -1}),
          store: stackingStore(stacking: true),
          route: const ImageRoute(["Walk"], "a.jpg"));
      expect(shownImage(tester), "a.jpg");
      expect(await walk(tester, 2), ["a.jpg", "b.jpg", "z.jpg"]);
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pumpAndSettle();
      expect(shownImage(tester), "s.jpg",
          reason: "Back past the burst, its hidden best shot skipped.");
    });
  });
}
