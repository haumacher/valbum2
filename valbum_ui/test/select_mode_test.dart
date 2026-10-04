/// The select mode of an album, for downloading some of its photographs
/// (issue #209).
///
/// Whoever may download but has no edit mode — a `view` or `contribute`
/// member with `download`, a share link made with it — selects photographs
/// exactly as the edit mode and the inbox select them, and downloads them from
/// the mode's own bar. An editor is never offered it.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'download_test.dart'
    show installSaver, menuKeys, postedNames, pumpSelection, refusal;
import 'move_test.dart' show openAlbumMenu, tile;
import 'share_album_chrome_test.dart' as chrome;
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// An album `Inbox` carrying [rights] in two sections: "Morning" holding
/// `a.jpg` and `b.jpg`, "Evening" holding `c.jpg` (rated -1, which the
/// standing filter hides), `d.jpg` and the video `e.mp4`; `a.jpg` and `d.jpg`
/// carry the label "Kids" (issue #213).
String sectionedAlbum(List<String> rights) =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", "subTitle": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"parts": ['
    '["Heading", {"text": "Morning", "level": 1}], '
    '${image("a.jpg", 0, 0, labels: ["Kids"])}, ${image("b.jpg", 1, 0)}, '
    '["Heading", {"text": "Evening", "level": 1}], '
    '${image("c.jpg", 2, -1)}, ${image("d.jpg", 3, 0, labels: ["Kids"])}, '
    '${image("e.mp4", 4, 0, kind: "VIDEO")}'
    ']}]';

String image(String name, int index, int rating,
        {String kind = "IMAGE", List<String> labels = const []}) =>
    '["ImagePart", {"kind": "$kind", "name": "$name", '
    '"labels": [${labels.map((l) => '{"name": "$l"}').join(", ")}], '
    '"date": ${1015113600000 + index * 10000}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": $rating}]';

/// Opens the sectioned album for a member holding [rights].
Future<void> pumpMember(
  WidgetTester tester, {
  List<String> rights = const ["view", "download"],
  List<http.Request>? requests,
  http.Response Function(http.Request)? zip,
}) =>
    pumpSelection(
      tester,
      requests ?? [],
      rights: rights,
      album: sectionedAlbum,
      select: false,
      zip: zip,
    );

/// Enters the select mode from the album's menu.
Future<void> enterSelectMode(WidgetTester tester) async {
  await openAlbumMenu(tester);
  await tester.tap(find.byKey(const Key("select-mode")));
  await tester.pumpAndSettle();
}

/// Whether the tile of [name] is marked selected.
bool marked(String name) => find
    .descendant(
        of: tile(name), matching: find.byKey(const Key("select-mark-on")))
    .evaluate()
    .isNotEmpty;

/// The names whose tiles are marked selected, among the album's.
List<String> markedNames() => [
      for (var name in ["a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.mp4"])
        if (marked(name)) name,
    ];

Future<void> click(WidgetTester tester, String name) async {
  await tester.ensureVisible(tile(name));
  await tester.pumpAndSettle();
  await tester.tap(tile(name));
  await tester.pumpAndSettle();
}

Future<void> clickWith(
    WidgetTester tester, LogicalKeyboardKey key, String name) async {
  await tester.sendKeyDownEvent(key);
  await click(tester, name);
  await tester.sendKeyUpEvent(key);
}

String countText(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("select-mode-count"))).data!;

void main() {
  setUp(withEmptyImageCache);

  group('for a member who may download but not edit', () {
    testWidgets('is entered from the menu and left by the close button',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await openAlbumMenu(tester);
        expect(menuKeys(tester), contains("select-mode"));
        expect(find.text(testL10n.selectPhotos), findsOneWidget);
        await tester.tap(find.byKey(const Key("select-mode")));
        await tester.pumpAndSettle();

        // The bar holds the way out, the count and the download, nothing else.
        expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
        expect(countText(tester), testL10n.selectedCount(0));
        var download = tester
            .widget<TextButton>(find.byKey(const Key("select-mode-download")));
        expect(download.onPressed, isNull);
        expect(find.byIcon(Icons.more_vert), findsNothing);
        expect(markedNames(), isEmpty);

        await tester.tap(find.byKey(const Key("select-mode-leave")));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("select-mode-bar")), findsNothing);
        expect(find.byIcon(Icons.more_vert), findsOneWidget);
      });
    });

    testWidgets('is left by Escape, the selection with it', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await enterSelectMode(tester);
        await click(tester, "a.jpg");
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("select-mode-bar")), findsNothing);

        await enterSelectMode(tester);
        expect(markedNames(), isEmpty);
      });
    });

    testWidgets('a plain click replaces the selection and opens nothing',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await enterSelectMode(tester);

        await click(tester, "a.jpg");
        expect(markedNames(), ["a.jpg"]);
        expect(countText(tester), testL10n.selectedCount(1));
        // Still the album: a tap selects, the viewer stays shut.
        expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);

        await click(tester, "b.jpg");
        expect(markedNames(), ["b.jpg"]);
        await click(tester, "b.jpg");
        expect(markedNames(), isEmpty);
      });
    });

    testWidgets('a tap on the mark adds or removes that photo alone',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await enterSelectMode(tester);

        Future<void> tapMark(String name) async {
          await tester.ensureVisible(tile(name));
          await tester.pumpAndSettle();
          await tester.tap(find.descendant(
              of: tile(name), matching: find.byKey(const Key("select-mark"))));
          await tester.pumpAndSettle();
        }

        await click(tester, "a.jpg");
        await tapMark("b.jpg");
        await tapMark("d.jpg");
        expect(markedNames(), ["a.jpg", "b.jpg", "d.jpg"]);
        expect(countText(tester), testL10n.selectedCount(3));
        await tapMark("a.jpg");
        expect(markedNames(), ["b.jpg", "d.jpg"]);
        // The rest of the tile is still a plain click.
        await click(tester, "e.mp4");
        expect(markedNames(), ["e.mp4"]);
      });
    });

    testWidgets('ctrl toggles and shift takes the shown range', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await enterSelectMode(tester);

        // From a.jpg to d.jpg: b.jpg on the way, the hidden c.jpg not.
        await click(tester, "a.jpg");
        await clickWith(tester, LogicalKeyboardKey.shiftLeft, "d.jpg");
        expect(markedNames(), ["a.jpg", "b.jpg", "d.jpg"]);
        expect(countText(tester), testL10n.selectedCount(3));

        await clickWith(tester, LogicalKeyboardKey.controlLeft, "e.mp4");
        expect(markedNames(), ["a.jpg", "b.jpg", "d.jpg", "e.mp4"]);
        await clickWith(tester, LogicalKeyboardKey.controlLeft, "b.jpg");
        expect(markedNames(), ["a.jpg", "d.jpg", "e.mp4"]);
        // A range from an anchor just clicked off takes the range back.
        await clickWith(tester, LogicalKeyboardKey.shiftLeft, "e.mp4");
        expect(markedNames(), ["a.jpg"]);
      });
    });

    testWidgets('a long press toggles, and opens the mode', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);

        // The finger's way in: a long press on a tile, which it selects.
        await tester.longPress(tile("a.jpg"));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
        expect(markedNames(), ["a.jpg"]);

        await tester.ensureVisible(tile("d.jpg"));
        await tester.pumpAndSettle();
        await tester.longPress(tile("d.jpg"));
        await tester.pumpAndSettle();
        expect(markedNames(), ["a.jpg", "d.jpg"]);
        await tester.ensureVisible(tile("a.jpg"));
        await tester.pumpAndSettle();
        await tester.longPress(tile("a.jpg"));
        await tester.pumpAndSettle();
        expect(markedNames(), ["d.jpg"]);
      });
    });

    testWidgets('a heading selects its section and takes it back',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await enterSelectMode(tester);
        expect(find.byIcon(Icons.check_box_outline_blank), findsNWidgets(2));

        await tester.tap(find.text("Evening"));
        await tester.pumpAndSettle();
        // What the section shows: the hidden c.jpg is not there to select.
        expect(markedNames(), ["d.jpg", "e.mp4"]);
        expect(find.byIcon(Icons.check_box), findsOneWidget);

        await tester.tap(find.text("Morning"));
        await tester.pumpAndSettle();
        expect(markedNames(), ["a.jpg", "b.jpg", "d.jpg", "e.mp4"]);

        await tester.tap(find.text("Evening"));
        await tester.pumpAndSettle();
        expect(markedNames(), ["a.jpg", "b.jpg"]);
      });
    });

    testWidgets('the bar downloads exactly the selection', (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpMember(tester, requests: requests);
        await enterSelectMode(tester);
        await click(tester, "d.jpg");
        await clickWith(tester, LogicalKeyboardKey.controlLeft, "a.jpg");
        expect(find.text(testL10n.downloadSelection(2)), findsOneWidget);

        await tester.tap(find.byKey(const Key("select-mode-download")));
        await tester.pumpAndSettle();
      });

      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      // In the album's order, whatever order they were clicked in.
      expect(postedNames(post), ["a.jpg", "d.jpg"]);
      expect(saver.saved.single.name, "Inbox.zip");
      expect(find.text(testL10n.downloadSaved("Inbox.zip")), findsOneWidget);
      // The mode stays, so that more may be taken.
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
    });

    testWidgets('a refusal of the server speaks', (tester) async {
      var saver = installSaver();
      await withFakeImageHttp(() async {
        await pumpMember(tester,
            zip: (_) => refusal("This image is not available to you.", 403));
        await enterSelectMode(tester);
        await click(tester, "a.jpg");
        await clickWith(tester, LogicalKeyboardKey.controlLeft, "b.jpg");
        await tester.tap(find.byKey(const Key("select-mode-download")));
        await tester.pumpAndSettle();
      });

      expect(saver.saved, isEmpty);
      expect(
          find.text(
              testL10n.downloadFailed("This image is not available to you.")),
          findsOneWidget);
    });

    testWidgets('a contributor with download is offered it too',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester,
            rights: const ["view", "download", "contribute"]);
        await openAlbumMenu(tester);
        expect(menuKeys(tester), contains("select-mode"));
      });
    });
  });

  group('under a label chip (issue #213)', () {
    Future<void> pumpKids(
        WidgetTester tester, List<http.Request> requests) async {
      await pumpMember(tester, requests: requests);
      await tester.tap(find.byKey(const Key("label-chip-Kids")));
      await tester.pumpAndSettle();
    }

    testWidgets('the whole view is what the chip shows', (tester) async {
      installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpKids(tester, requests);
        await openAlbumMenu(tester);
        expect(find.text(testL10n.downloadSelection(2)), findsOneWidget);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });
      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      expect(postedNames(post), ["a.jpg", "d.jpg"]);
    });

    testWidgets('a heading selects what it shows under the chip',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpKids(tester, []);
        await enterSelectMode(tester);

        await tester.tap(find.text("Evening"));
        await tester.pumpAndSettle();
        expect(markedNames(), ["d.jpg"]);
        expect(find.byIcon(Icons.check_box), findsOneWidget);

        // A range runs over what the chip shows, b.jpg not among it.
        await click(tester, "a.jpg");
        await clickWith(tester, LogicalKeyboardKey.shiftLeft, "d.jpg");
        expect(markedNames(), ["a.jpg", "d.jpg"]);
      });
    });
  });

  testWidgets('is not offered without the download right', (tester) async {
    await withFakeImageHttp(() async {
      await pumpMember(tester, rights: const ["view"]);
      await openAlbumMenu(tester);
      expect(menuKeys(tester), isNot(contains("select-mode")));
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();

      await tester.longPress(tile("a.jpg"));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
    });
  });

  testWidgets('is never offered to an editor, who has the edit mode',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpMember(tester,
          rights: const ["view", "download", "contribute", "edit"]);
      await openAlbumMenu(tester);
      expect(menuKeys(tester), isNot(contains("select-mode")));
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();

      await tester.longPress(tile("a.jpg"));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
      expect(find.byKey(const Key("edit-cancel")), findsOneWidget);
    });
  });

  group('in a share link', () {
    http.Response Function(http.Request) linkWith(List<String> rights) =>
        (request) {
          var rightList = rights.map((r) => '{"name": "$r"}').join(", ");
          if (request.url.queryParameters["type"] == "auth") {
            return chrome.json(chrome
                .authOfLink()
                .replaceFirst('[{"name": "view"}]', "[$rightList]"));
          }
          return chrome.json(sectionedAlbum(rights));
        };

    testWidgets('made with download, it selects', (tester) async {
      await withFakeImageHttp(() async {
        await chrome.pumpSession(tester, linkWith(const ["view", "download"]));
        await openAlbumMenu(tester);
        expect(menuKeys(tester), contains("select-mode"));
        await tester.tap(find.byKey(const Key("select-mode")));
        await tester.pumpAndSettle();

        await click(tester, "b.jpg");
        await clickWith(tester, LogicalKeyboardKey.shiftLeft, "e.mp4");
        expect(markedNames(), ["b.jpg", "d.jpg", "e.mp4"]);
        expect(find.text(testL10n.downloadSelection(3)), findsOneWidget);
      });
    });

    testWidgets('made without it, it does not', (tester) async {
      await withFakeImageHttp(() async {
        await chrome.pumpSession(tester, linkWith(const ["view"]));
        await openAlbumMenu(tester);
        expect(menuKeys(tester), isNot(contains("select-mode")));
      });
    });
  });
}
