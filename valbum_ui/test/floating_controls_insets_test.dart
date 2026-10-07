/// The controls at the top of a page stand clear of the status bar.
///
/// Reported: "on some phones the top-level bar with the buttons is overlapped
/// by the phone's battery display and things like that. This absorbs clicks
/// to the valbum buttons in the top-level row." The album's view mode — and
/// with it every share link (#97) — has no app bar and floated its way up and
/// its menu at a fixed 8 px from the corner of the window, which on an
/// edge-to-edge Android lies under the status bar. Now they stand 8 px from
/// the corner of what the system leaves the page, the viewer's rule, and the
/// album's heading starts below the status bar too; in landscape a cutout or
/// the navigation bar at a side is kept clear the same way.
///
/// The probe at the end holds every tappable control of those pages to the
/// safe area, so a control placed at a constant offset from the window's edge
/// is found wherever it is added.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/listing_layout.dart';
import 'package:valbum_ui/main.dart';

import 'album_menu_actions_test.dart' show pumpAlbum;
import 'inbox_view_test.dart' show inboxTree, pumpInbox;
import 'move_test.dart' show treeAnswer;
import 'page_insets_test.dart' show manyAlternatives;
import 'share_album_chrome_test.dart' as chrome;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// A phone on its side: the status bar at the top, a cutout at the left, the
/// navigation bar at the right.
const Size screen = Size(800, 400);
const double topInset = 48;
const double leftInset = 40;
const double rightInset = 24;

void useEdgeToEdgeScreen(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = screen;
  const insets =
      FakeViewPadding(top: topInset, left: leftInset, right: rightInset);
  tester.view.padding = insets;
  tester.view.viewPadding = insets;
  addTearDown(tester.view.reset);
}

/// What the system leaves the page.
final Rect safeArea = Rect.fromLTRB(
    leftInset, topInset, screen.width - rightInset, screen.height);

void expectInSafeArea(WidgetTester tester, Finder finder, String what) {
  expect(finder, findsWidgets, reason: what);
  for (var element in finder.evaluate()) {
    var rect = tester.getRect(find.byWidget(element.widget).first);
    expect(rect.top, greaterThanOrEqualTo(safeArea.top),
        reason: "$what at $rect lies under the status bar");
    expect(rect.left, greaterThanOrEqualTo(safeArea.left),
        reason: "$what at $rect lies under the left cutout");
    expect(rect.right, lessThanOrEqualTo(safeArea.right),
        reason: "$what at $rect lies under the navigation bar");
  }
}

/// The tiles stay clear of the cutout and the navigation bar.
void expectTilesBetweenTheSides(WidgetTester tester) {
  var images = find.byType(Image).evaluate().toList();
  expect(images, isNotEmpty);
  for (var element in images) {
    var rect = tester.getRect(find.byWidget(element.widget).first);
    if (rect.isEmpty) continue;
    expect(rect.left, greaterThanOrEqualTo(leftInset - 0.01));
    expect(rect.right, lessThanOrEqualTo(screen.width - rightInset + 0.01));
  }
}

/// Every control a finger could aim at.
Finder tappableControls() => find.byWidgetPredicate((w) =>
    w is IconButton ||
    w is PopupMenuButton ||
    w is FloatingActionButton ||
    w is TextButton ||
    w is FilledButton);

/// Taps the centre of the menu button and checks that the menu opened.
Future<void> expectMenuOpensAtItsCentre(WidgetTester tester) async {
  var button = find.byIcon(Icons.more_vert).last;
  await tester.tapAt(tester.getCenter(button));
  await tester.pumpAndSettle();
  expect(find.byKey(const Key("about")), findsOneWidget,
      reason: "a tap at the menu button's centre opens the menu");
}

void main() {
  setUp(() => imageCache.clear());

  testWidgets(
      'the album in the view mode keeps its controls and its heading '
      'clear of the status bar and the cutouts', (tester) async {
    useEdgeToEdgeScreen(tester);
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, treeAnswer);
    });
    expect(find.byType(AppBar), findsNothing, reason: "the view mode");

    expectInSafeArea(tester, find.byIcon(Icons.arrow_back), "the way up");
    expectInSafeArea(tester, find.byIcon(Icons.more_vert), "the menu");
    expect(tester.getRect(find.byIcon(Icons.arrow_back)).top,
        greaterThanOrEqualTo(topInset + 8));
    expect(tester.getRect(find.byIcon(Icons.more_vert)).top,
        greaterThanOrEqualTo(topInset + 8));

    // The heading of the album is not drawn under the status bar either.
    var heading = find.text("Inbox").first;
    expect(tester.getRect(heading).top, greaterThanOrEqualTo(topInset));

    expectTilesBetweenTheSides(tester);

    await expectMenuOpensAtItsCentre(tester);
  });

  testWidgets('a tap at the way up of the album leaves it', (tester) async {
    useEdgeToEdgeScreen(tester);
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, treeAnswer);
      await tester.tapAt(tester.getCenter(find.byIcon(Icons.arrow_back)));
      await tester.pumpAndSettle();
    });
    expect(find.byType(AlbumContent), findsNothing);
  });

  testWidgets('a share link keeps its menu clear of the status bar',
      (tester) async {
    useEdgeToEdgeScreen(tester);
    await chrome.pumpSession(tester, chrome.albumLink());
    expect(find.byType(AppBar), findsNothing);
    expectInSafeArea(tester, find.byIcon(Icons.more_vert), "the menu");
    expect(
        tester.getRect(find.text("Zoo")).top, greaterThanOrEqualTo(topInset));
    await expectMenuOpensAtItsCentre(tester);
  });

  testWidgets('the page of a dead link keeps its controls clear',
      (tester) async {
    useEdgeToEdgeScreen(tester);
    await tester.pumpWidget(localizedApp(
      ShareConfinedScreen(message: "Not here.", onHome: () {}),
    ));
    await tester.pumpAndSettle();
    expectInSafeArea(tester, tappableControls(), "a control");
  });

  testWidgets('the alternatives of a group keep their way back clear',
      (tester) async {
    useEdgeToEdgeScreen(tester);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(localizedApp(GroupView(
        client: clientReturning("{}"),
        baseUrl: "http://server/valbum/data/album",
        group: manyAlternatives(),
        onUp: () {},
        onShowDetail: (_) {},
      )));
      await tester.pumpAndSettle();
    });
    expectInSafeArea(tester, find.byIcon(Icons.arrow_back), "the way back");
    // The marker of the group's picture rides on its tile, which is laid
    // out between the side insets.
    expectInSafeArea(tester, find.byKey(const Key("group-representative")),
        "the representative's marker");
  });

  group(
      'probe: no control of these pages sits at a constant offset from '
      'the window', () {
    testWidgets('the album', (tester) async {
      useEdgeToEdgeScreen(tester);
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);
      });
      expectInSafeArea(tester, tappableControls(), "a control of the album");
    });

    testWidgets('the root listing', (tester) async {
      useEdgeToEdgeScreen(tester);
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer, route: const []);
      });
      expectInSafeArea(tester, tappableControls(), "a control of the listing");
      // The tiles are laid out between the side insets.
      var tiles = tester.getRect(find.byType(TileGrid).first);
      expect(tiles.left, greaterThanOrEqualTo(leftInset - 0.01));
      expect(tiles.right, lessThanOrEqualTo(screen.width - rightInset + 0.01));
    });

    testWidgets('the inbox', (tester) async {
      useEdgeToEdgeScreen(tester);
      await pumpInbox(tester, inboxTree);
      expectInSafeArea(tester, tappableControls(), "a control of the inbox");
      expectTilesBetweenTheSides(tester);
    });

    testWidgets('the share link', (tester) async {
      useEdgeToEdgeScreen(tester);
      await chrome.pumpSession(tester, chrome.albumLink());
      expectInSafeArea(tester, tappableControls(), "a control of the link");
    });
  });
}
