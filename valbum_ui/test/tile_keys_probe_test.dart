/// Probe of #295: while the tile's menu stands open, the arrows walk the
/// menu and Enter picks its entry — the grid underneath keeps its highlight
/// and opens nothing of its own.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'tile_keys_test.dart' show desk, highlighted, press, pressWith;
import 'util/photo_space.dart';

void main() {
  testWidgets('an open tile menu keeps the keys from the grid',
      (tester) async {
    desk(tester);
    await openSpace(tester, PhotoSpace(), const ListingOrAlbumRoute(["trip"]));
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(highlighted(tester), "a.jpg");
    await pressWith(
        tester, LogicalKeyboardKey.shiftLeft, LogicalKeyboardKey.f10);
    expect(find.byKey(const Key("tile-context-open")), findsOneWidget);

    await press(tester, LogicalKeyboardKey.arrowRight);
    await press(tester, LogicalKeyboardKey.arrowDown);
    expect(highlighted(tester), "a.jpg", reason: "the grid did not move");
    await press(tester, LogicalKeyboardKey.escape);
    expect(find.byKey(const Key("tile-context-open")), findsNothing);
    expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]),
        reason: "Escape closed the menu, nothing was opened");
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(highlighted(tester), "b.jpg", reason: "the grid walks on after it");
  });
}
