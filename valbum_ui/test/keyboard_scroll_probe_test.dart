// Probe of issue #168: the keys belong to the page on top.
//
// The album stays mounted beneath the viewer (#93). While the viewer is on
// top, the arrow keys page the viewer and the album does not scroll; back in
// the album, PageDown scrolls it again without a click first.
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:valbum_ui/main.dart';

import 'album_return_test.dart';

void main() {
  testWidgets('the keys follow the page on top, and come back to the album',
      (tester) async {
    tester.view.physicalSize = const Size(800, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var thumbnails = <String>[];

    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: countingClient(landscapeAlbum(80), thumbnails)),
      ),
    );
    var album = albumStateOf(tester);
    expect(album.albumScrollPosition!.pixels, 0);

    await tap(tester, find.byKey(const ValueKey("img1.jpg")));
    expect(shownImage(tester).thumbnailName, "img1.jpg");

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownImage(tester).thumbnailName, "img2.jpg",
        reason: "the viewer on top takes the arrow");
    await press(tester, LogicalKeyboardKey.pageDown);
    await press(tester, LogicalKeyboardKey.end);
    expect(album.albumScrollPosition!.pixels, 0,
        reason: "the album beneath the viewer does not scroll");

    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(ImageView), findsNothing);
    await press(tester, LogicalKeyboardKey.pageDown);
    expect(album.albumScrollPosition!.pixels, greaterThan(0),
        reason: "back in the album, PageDown scrolls it without a click");
  });
}
