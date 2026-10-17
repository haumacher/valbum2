/// The photo action catalogue of issue #290 and "Show in album" with the
/// grouping by album of issue #289: one rule for "Add to collection…"
/// ([mayCollectFrom], the server's), the inbox's gaps closed (no turn, time or
/// crop of a file that cannot be read; the download and the collection of the
/// selection; Escape clears it), a new search grouped by album while a stored
/// grouping stays, and the way from a photo found elsewhere into its album,
/// the photo in view and marked.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_actions.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_view.dart';

import 'album_return_reveal_test.dart' show expectRevealed, screen;
import 'share_album_chrome_test.dart' as chrome;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';
import 'util/photo_space.dart';

/// The headings the album on the screen shows, top to bottom.
List<String> headings(WidgetTester tester) => [
      for (var heading in tester
          .state<AlbumContentState>(find.byType(AlbumContent).last)
          .shownParts(tester
              .state<AlbumContentState>(find.byType(AlbumContent).last)
              .shownAlbum)
          .whereType<Heading>())
        heading.text,
    ];

void main() {
  group('the rule of "Add to collection…"', () {
    test('is the server\'s: a member, or contribute; never a link', () {
      var view = Rights.ofNames(const [rightView]);
      var contribute = Rights.ofNames(const [rightContribute]);
      expect(mayCollectFrom(shareLink: false, signedIn: true, rights: view),
          isTrue);
      expect(mayCollectFrom(shareLink: false, signedIn: false, rights: view),
          isFalse);
      expect(
          mayCollectFrom(shareLink: false, signedIn: false, rights: contribute),
          isTrue);
      expect(
          mayCollectFrom(shareLink: true, signedIn: true, rights: contribute),
          isFalse);
    });

    testWidgets(
        'a view-only member gathers for a collection in the select '
        'mode', (tester) async {
      await openSpace(tester, PhotoSpace(role: "view", rights: const ["view"]),
          const ListingOrAlbumRoute(["trip"]));
      await longPressOn(tester, "a.jpg");
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(find.byKey(const Key("select-mode-collect")), findsOneWidget);
      expect(find.byKey(const Key("select-mode-download")), findsNothing);
      await rightClick(tester, "a.jpg");
      expect(find.byKey(const Key("tile-context-collect")), findsOneWidget);
      expect(find.byKey(const Key("tile-context-download")), findsNothing);
    });

    testWidgets('a share link is never offered it', (tester) async {
      await withFakeImageHttp(() async {
        await chrome.pumpSession(tester, (request) {
          if (request.url.queryParameters["type"] == "auth") {
            return chrome.json(chrome.authOfLink().replaceFirst(
                '[{"name": "view"}]',
                '[{"name": "view"}, {"name": "download"}]'));
          }
          return chrome.json('["AlbumInfo", {"path": "Zoo", "title": "Zoo", '
              '"subTitle": "", "rights": [{"name": "view"}, '
              '{"name": "download"}], "parts": [${photo("a.jpg", 0)}, '
              '${photo("b.jpg", 1)}]}]');
        });
      });
      await longPressOn(tester, "a.jpg");
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(find.byKey(const Key("select-mode-collect")), findsNothing);
      await rightClick(tester, "a.jpg");
      expect(find.byKey(const Key("tile-context-download")), findsOneWidget);
      expect(find.byKey(const Key("tile-context-collect")), findsNothing);
    });
  });

  group('the inbox', () {
    InboxContentState inbox(WidgetTester tester) =>
        tester.state<InboxContentState>(find.byType(InboxContent));

    testWidgets('offers no turn, time or crop on a file that cannot be read',
        (tester) async {
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["Inbox"]));
      await tester.tap(tileOf("broken.jpg"));
      await settle(tester);
      Finder inTile(String key) => find.descendant(
          of: tileOf("broken.jpg"), matching: find.byKey(Key(key)));
      expect(inTile("inbox-select"), findsOneWidget,
          reason: "The tools are shown: the tile is selected.");
      expect(inTile("inbox-rotate-right"), findsNothing);
      expect(inTile("inbox-rotate-left"), findsNothing);
      expect(inTile("inbox-adjust-time"), findsNothing);
      expect(inTile("rating-trash"), findsOneWidget,
          reason: "It is still rated, and thrown away.");

      await rightClick(tester, "broken.jpg");
      expect(find.byKey(const Key("inbox-context-delete")), findsOneWidget);
      expect(find.byKey(const Key("inbox-context-time")), findsNothing);
      expect(find.byKey(const Key("inbox-context-crop")), findsNothing);
      expect(find.byKey(const Key("inbox-context-collect")), findsNothing);
    });

    testWidgets('downloads and collects the selection', (tester) async {
      var space = PhotoSpace();
      await openSpace(tester, space, const ListingOrAlbumRoute(["Inbox"]));
      await tester.tap(tileOf("a.jpg"));
      await settle(tester);
      await longPressOn(tester, "b.jpg");
      expect([for (var image in inbox(tester).selected) image.name],
          ["a.jpg", "b.jpg"]);

      await rightClick(tester, "b.jpg");
      expect(
          find.descendant(
              of: find.byKey(const Key("inbox-context-download")),
              matching: find.text(testL10n.downloadSelection(2))),
          findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const Key("inbox-context-collect")),
              matching: find.text(testL10n.addCountToCollection(2))),
          findsOneWidget);
      await tester.tap(find.byKey(const Key("inbox-context-collect")));
      await settle(tester);
      expect(find.byKey(const Key("collection-picker")), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);

      // The inbox's own menu offers both as well.
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await settle(tester);
      expect(find.byKey(const Key("download-selection")), findsOneWidget);
      expect(find.byKey(const Key("add-to-collection")), findsOneWidget);
    });

    testWidgets('Escape clears the selection', (tester) async {
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["Inbox"]));
      await tester.tap(tileOf("a.jpg"));
      await settle(tester);
      expect(inbox(tester).selected, hasLength(1));
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(inbox(tester).selected, isEmpty);
    });
  });

  group('the grouping of a search (issue #289)', () {
    test('a new search starts grouped by album, and saves so', () {
      var session = SearchSession();
      expect(session.criteria.groupBy, [SearchGroup.album]);
      expect(session.criteria.isEmpty, isTrue,
          reason: "The grouping is no criterion: the view still asks first.");
      expect(session.criteria.toQuery().groupBy, [SearchGroup.album],
          reason: "\"Save as view…\" stores what the session holds.");
      session.endEdit();
      expect(session.criteria.groupBy, [SearchGroup.album]);
    });

    testWidgets('a fresh search shows the albums as headings', (tester) async {
      await openSpace(tester, PhotoSpace(), const SearchRoute([]));
      await chooseAnne(tester);
      expect(headings(tester), ["trip"]);
    });

    testWidgets('a saved search stored by year stays by year', (tester) async {
      await openSpace(tester, PhotoSpace(savedGroupBy: "YEAR"),
          const ListingOrAlbumRoute(["Saved"]));
      expect(headings(tester), ["2002"]);
    });

    testWidgets('an old saved search without a grouping shows none',
        (tester) async {
      await openSpace(
          tester, PhotoSpace(), const ListingOrAlbumRoute(["Saved"]));
      expect(headings(tester), isEmpty);
    });

    testWidgets('the heading of an album opens it', (tester) async {
      await openSpace(tester, PhotoSpace(), const SearchRoute([]));
      await chooseAnne(tester);
      await tester.tap(find.text("trip"));
      await settle(tester);
      expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));
    });
  });

  group('"Show in album" (issue #289)', () {
    Future<void> showInAlbum(WidgetTester tester, String name) async {
      await rightClick(tester, name);
      expect(
          find.byKey(const Key("tile-context-show-in-album")), findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const Key("tile-context-show-in-album")),
              matching: find.text(testL10n.showInAlbum)),
          findsOneWidget);
      await tester.tap(find.byKey(const Key("tile-context-show-in-album")));
      await settle(tester);
    }

    void expectShownAndMarked(WidgetTester tester, String name) {
      expect(routeOf(tester), const ListingOrAlbumRoute(["trip"]));
      expectRevealed(tester, name);
      expect(
          find.descendant(
              of: tileOf(name), matching: find.byKey(shownInAlbumMarkKey)),
          findsOneWidget);
      expect(find.byKey(shownInAlbumMarkKey), findsOneWidget,
          reason: "The one photo is marked.");
    }

    testWidgets('of a search result opens its album on it', (tester) async {
      screen(tester, const Size(800, 600));
      await openSpace(tester, PhotoSpace(extra: 60), const SearchRoute([]));
      await chooseAnne(tester);
      await showInAlbum(tester, "trip/img59.jpg");
      expectShownAndMarked(tester, "img59.jpg");
    });

    testWidgets('of a collection photo opens its album on it', (tester) async {
      screen(tester, const Size(800, 600));
      await openSpace(
          tester, PhotoSpace(extra: 60), const ListingOrAlbumRoute(["Best"]));
      await showInAlbum(tester, "last.jpg");
      expectShownAndMarked(tester, "img59.jpg");
    });

    testWidgets('the viewer\'s entry is the same action', (tester) async {
      screen(tester, const Size(800, 600));
      await openSpace(tester, PhotoSpace(extra: 60),
          const ImageRoute(["Best"], "last.jpg"));
      await tester.tap(find.descendant(
          of: find.byKey(const Key("viewer-menu")),
          matching: find.byType(PopupMenuButton<void Function(BuildContext)>)));
      await settle(tester);
      await tester.tap(find.byKey(const Key("viewer-show-in-album")));
      await settle(tester);
      expectShownAndMarked(tester, "img59.jpg");
    });

    testWidgets('is not offered where the collection does not name the album', (tester) async {
      await openSpace(
          tester, PhotoSpace(refs: false), const ListingOrAlbumRoute(["Best"]));
      await rightClick(tester, "a.jpg");
      expect(menuOpen(), isTrue);
      expect(find.byKey(const Key("tile-context-show-in-album")), findsNothing);
    });

    testWidgets('is not offered where the search does not name the album',
        (tester) async {
      await openSpace(tester, PhotoSpace(refs: false), const SearchRoute([]));
      await chooseAnne(tester);
      await rightClick(tester, "trip/a.jpg");
      expect(menuOpen(), isTrue);
      expect(find.byKey(const Key("tile-context-show-in-album")), findsNothing);
    });
  });
}
