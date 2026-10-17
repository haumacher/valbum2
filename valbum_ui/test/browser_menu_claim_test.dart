/// The counted claim on the browser's context menu (issue #290): every page
/// whose tiles have a menu of their own holds a [BrowserMenuClaim], the menu
/// is taken away by the first claim and given back by the last. A page that
/// leaves therefore never gives it back while the page it went to — built
/// before the old one is disposed — still needs it.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_not_a_face_test.dart' show RecordingBrowserMenu;
import 'persons_view_test.dart'
    show albumOf, authOf, editorClient, openMenu, pumpAlbum;
import 'util/fake_image_http.dart';
import 'util/photo_space.dart';

/// Clicks the tile of the folder `trip` with the secondary mouse button.
Future<void> rightClickFolder(WidgetTester tester) async {
  await tester.tapAt(tester.getCenter(find.byKey(const ValueKey("tile-trip"))),
      buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
  await settle(tester);
}

void main() {
  late RecordingBrowserMenu browser;
  late BrowserMenu before;

  setUp(() {
    before = browserMenu;
    browser = RecordingBrowserMenu();
    browserMenu = browser;
  });

  tearDown(() => browserMenu = before);

  test('the first claim takes the menu, the last gives it back', () {
    var a = BrowserMenuClaim();
    var b = BrowserMenuClaim();
    a.acquire();
    a.acquire();
    b.hold(true);
    expect(browser.calls, ["disable"]);
    a.release();
    a.release();
    expect(browser.calls, ["disable"], reason: "b still holds it");
    b.hold(false);
    expect(browser.calls, ["disable", "enable"]);
    expect(BrowserMenuClaim.held, 0);
  });

  /// A mouse resting on the tile of the folder `trip`.
  Future<TestGesture> mouseOnTrip(WidgetTester tester) async {
    var mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    addTearDown(mouse.removePointer);
    await mouse.addPointer(
        location: tester.getCenter(find.byKey(const ValueKey("tile-trip"))));
    await settle(tester);
    return mouse;
  }

  testWidgets('a folder tile without a menu keeps the browser\'s',
      (tester) async {
    await openSpace(tester, PhotoSpace(role: "view", rights: const ["view"]),
        ListingOrAlbumRoute.root);
    await mouseOnTrip(tester);
    await rightClickFolder(tester);
    expect(menuOpen(), isFalse, reason: "Nothing this member may do here.");
    expect(BrowserMenuClaim.held, 0);
    expect(browser.calls, isEmpty, reason: "The browser's menu stays.");
  });

  testWidgets('a folder tile with a menu takes the browser\'s', (tester) async {
    await openSpace(tester, PhotoSpace(), ListingOrAlbumRoute.root);
    expect(browser.calls, isEmpty, reason: "Not before the pointer is there.");
    await mouseOnTrip(tester);
    expect(browser.calls, ["disable"]);
    await rightClickFolder(tester);
    expect(menuOpen(), isTrue);
    // The open menu covers the tile: the pointer is on the app's menu now,
    // and the claim of the tile under it is given back.
    expect(browser.calls, ["disable", "enable"]);
  });

  testWidgets('listing, album, back, leave: off throughout, back once',
      (tester) async {
    var delegate =
        await openSpace(tester, PhotoSpace(), ListingOrAlbumRoute.root);
    var mouse = await mouseOnTrip(tester);
    expect(browser.calls, ["disable"],
        reason: "The tile under the pointer holds it.");

    await withFakeImageHttp(() async {
      await mouse.down(
          tester.getCenter(find.byKey(const ValueKey("tile-trip"))));
      await mouse.up();
    });
    await settle(tester);
    expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));
    expect(browser.calls, ["disable"],
        reason: "Into the album: never given back in between.");

    delegate.goUp();
    await settle(tester);
    expect(routeOf(tester), ListingOrAlbumRoute.root);
    expect(browser.calls, ["disable"],
        reason: "Back to the listing: never given back in between.");
    expect(browser.enabled, isFalse);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(browser.calls, ["disable", "enable"]);
    expect(browser.enabled, isTrue);
  });

  testWidgets('album edit, persons editor, back: off throughout, back once',
      (tester) async {
    var requests = <http.Request>[];
    await pumpAlbum(
        tester, editorClient(requests, auth: authOf(), album: albumOf()));
    await withFakeImageHttp(() async {
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
    });
    expect(find.byKey(const Key("edit-cancel")), findsOneWidget);
    expect(browser.calls, ["disable"]);

    await openMenu(tester);
    await withFakeImageHttp(() async {
      await tester.ensureVisible(find.byKey(const Key("persons")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("persons")));
      await tester.pumpAndSettle();
    });
    expect(find.byType(PersonsContent), findsOneWidget);
    expect(browser.calls, ["disable"],
        reason: "Into the editor: never given back in between.");

    await withFakeImageHttp(() async {
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
    });
    expect(find.byType(PersonsContent), findsNothing);
    expect(find.byKey(const Key("edit-cancel")), findsOneWidget,
        reason: "Back in the album's edit mode.");
    expect(browser.calls, ["disable"],
        reason: "Out of the editor: never given back in between.");

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(browser.calls, ["disable", "enable"]);
  });
}
