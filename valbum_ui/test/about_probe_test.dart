/// Probes of the About dialog (#187) and the insets (#188) the delivery did
/// not write: the licence page inside a share session, a folder link on a
/// portrait phone with a status bar, and the viewer a link visitor opens.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';

import 'download_test.dart' show menuKeys;
import 'share_album_chrome_test.dart' as chrome;
import 'util/viewer_harness.dart' show withEmptyImageCache;

void main() {
  setUp(withEmptyImageCache);

  testWidgets('the licence page opens from a share session and leads back',
      (tester) async {
    await chrome.pumpSession(tester, chrome.albumLink());
    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("about")));
    await tester.pumpAndSettle();
    await tester.tap(find.text("View licenses"));
    await tester.pumpAndSettle();
    expect(find.byType(LicensePage), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.byType(LicensePage), findsNothing);
    // The dialog is still over the album; closing it shows the album again.
    await tester.tap(find.text("Close"));
    await tester.pumpAndSettle();
    expect(find.byType(AboutDialog), findsNothing);
    expect(find.text("Zoo"), findsOneWidget);
  });

  for (var link in const ["album", "folder"]) {
    testWidgets(
        'a $link link on a portrait phone with a status bar keeps its '
        'menu tappable', (tester) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      tester.view.padding = const FakeViewPadding(top: 47 * 3, bottom: 34 * 3);
      tester.view.viewPadding =
          const FakeViewPadding(top: 47 * 3, bottom: 34 * 3);
      addTearDown(tester.view.reset);
      await chrome.pumpSession(
          tester, link == "album" ? chrome.albumLink() : chrome.folderLink());
      var menu = find.byIcon(Icons.more_vert).last;
      var rect = tester.getRect(menu);
      expect(rect.top, greaterThanOrEqualTo(47), reason: "below the status bar");
      await tester.tapAt(rect.center);
      await tester.pumpAndSettle();
      expect(menuKeys(tester).last, "about");
    });
  }

  testWidgets('the viewer a link visitor opens offers About', (tester) async {
    await chrome.pumpSession(tester, chrome.albumLink());
    var tile = find.byType(Image).first;
    await tester.tap(tile);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("viewer-menu")));
    await tester.pumpAndSettle();
    expect(menuKeys(tester), ["about"]);
  });
}
