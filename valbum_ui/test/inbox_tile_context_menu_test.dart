/// Tests of the context menu of an inbox tile (issue #222): a secondary click
/// opens, at the pointer, the move first and then the inbox's other actions,
/// each only where the caller may, acting on the selection the way the album
/// edit mode's menu does (#156), and the browser's own menu is away while the
/// inbox stands.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/crop_editor.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'inbox_view_test.dart'
    show inboxJson, inboxTree, pumpInbox, reveal, selectedNames, tapHeading;
import 'move_test.dart' show json, movedAll, pathOf, tile;
import 'persons_not_a_face_test.dart' show RecordingBrowserMenu;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// Clicks the tile of [name] with the secondary mouse button and returns
/// where.
Future<Offset> secondaryClick(WidgetTester tester, String name) async {
  await reveal(tester, tile(name));
  var box = tester.getRect(tile(name));
  var at = Offset(box.left + 8, box.center.dy);
  await tester.tapAt(
    at,
    buttons: kSecondaryMouseButton,
    kind: PointerDeviceKind.mouse,
  );
  await tester.pumpAndSettle();
  return at;
}

Finder entry(String name) => find.byKey(Key("inbox-context-$name"));

const List<String> allEntries = [
  "move",
  "delete",
  "properties",
  "time",
  "crop",
];

/// The entries of the open menu, in their order.
List<String> shownEntries(WidgetTester tester) {
  var shown = [
    for (var name in allEntries)
      if (entry(name).evaluate().isNotEmpty) name,
  ];
  shown.sort((a, b) =>
      tester.getRect(entry(a)).top.compareTo(tester.getRect(entry(b)).top));
  return shown;
}

/// The inbox answering every write as accepted, and moves as done.
http.Response Function(http.Request) accepting({
  List<String> rights = const [],
}) =>
    (request) {
      if (request.method == "PUT") {
        return json("");
      }
      if (request.method == "POST") {
        return json(movedAll(["a.jpg", "b.jpg"]));
      }
      if (pathOf(request) == "/valbum/data/2021/") {
        return json('["ListingInfo", {"path": "2021", "title": "2021", '
            '"folders": []}]');
      }
      return inboxTree(request, album: inboxJson(rights: rights));
    };

void main() {
  late RecordingBrowserMenu browser;
  late BrowserMenu before;

  setUp(() {
    before = browserMenu;
    browser = RecordingBrowserMenu();
    browserMenu = browser;
  });

  tearDown(() => browserMenu = before);

  group('the entries per role', () {
    testWidgets('an editor: the move first, then everything else',
        (tester) async {
      await pumpInbox(tester, accepting());
      var at = await secondaryClick(tester, "d.jpg");

      expect(shownEntries(tester), allEntries);
      // The menu opens at the pointer.
      expect((tester.getRect(entry("move")).left - at.dx).abs(), lessThan(48));
      expect(
        find.descendant(
            of: entry("move"),
            matching: find.text(inboxMoveLabel(testL10n, 1))),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: entry("delete"),
            matching: find.text(inboxDeleteLabel(testL10n, 1))),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: entry("time"),
            matching: find.text(testL10n.adjustRecordingTimeAction)),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: entry("crop"), matching: find.text(testL10n.cropMenu)),
        findsOneWidget,
      );
    });

    testWidgets('a contributor: the move, the delete and the properties',
        (tester) async {
      await pumpInbox(
          tester, accepting(rights: const ["view", "download", "contribute"]));
      await secondaryClick(tester, "d.jpg");
      expect(shownEntries(tester), ["move", "delete", "properties"]);
    });

    testWidgets('a viewer: the properties alone', (tester) async {
      await pumpInbox(tester, accepting(rights: const ["view", "download"]));
      await secondaryClick(tester, "d.jpg");
      expect(shownEntries(tester), ["properties"]);
    });

    testWidgets('a video is never cropped', (tester) async {
      var video = '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
          '"kind": "INBOX", "parts": [["ImagePart", {"kind": "VIDEO", '
          '"name": "v.mp4", "date": ${DateTime(2026, 3, 1, 12).millisecondsSinceEpoch}, '
          '"width": 1920, "height": 1080, "orientation": "IDENTITY"}]]}]';
      await pumpInbox(tester, (request) => inboxTree(request, album: video));
      await secondaryClick(tester, "v.mp4");
      expect(shownEntries(tester), ["move", "delete", "properties", "time"]);
    });
  });

  group('what the menu acts on', () {
    testWidgets(
        'a photograph of the selection moves the whole selection, the new '
        'album dated by it', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting(), requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        expect(selectedNames(tester), ["a.jpg", "b.jpg"]);

        await secondaryClick(tester, "b.jpg");
        expect(selectedNames(tester), ["a.jpg", "b.jpg"],
            reason: "a tile of the selection leaves the selection alone");
        expect(
          find.descendant(
              of: entry("move"),
              matching: find.text(inboxMoveLabel(testL10n, 2))),
          findsOneWidget,
        );

        await tester.tap(entry("move"));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("folder-picker")), findsOneWidget);
        await tester.tap(find.byKey(const Key("picker-create-album")));
        await tester.pumpAndSettle();
        // The album about to be made out of the day, dated by it.
        expect(find.text("2026-03-01"), findsOneWidget);
      });
    });

    testWidgets('a photograph outside the selection first becomes it',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting(), requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");

        await secondaryClick(tester, "d.jpg");
        expect(selectedNames(tester), ["d.jpg"]);
        await tester.tap(entry("delete"));
        await tester.pumpAndSettle();
      });

      // The editor's delete is the -2 rating of that photograph alone.
      var put = requests.singleWhere((r) => r.method == "PUT");
      var sent = Resource.fromString(put.body) as AlbumInfo;
      expect({
        for (var p in sent.parts.whereType<ImagePart>()) p.name: p.rating,
      }, {
        "d.jpg": -2,
        "c.jpg": 0,
        "a.jpg": 0,
        "b.jpg": 0
      });
      expect(requests.where((r) => r.method == "POST"), isEmpty);
    });

    testWidgets('a contributor\'s delete asks and posts the selection',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(
            tester, accepting(rights: const ["view", "download", "contribute"]),
            requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await secondaryClick(tester, "a.jpg");
        await tester.tap(entry("delete"));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("inbox-delete-dialog")), findsOneWidget);
        await tester.tap(find.byKey(const Key("inbox-delete-confirm")));
        await tester.pumpAndSettle();
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(post.url.queryParameters["action"], "delete");
      expect(post.body,
          '{"target":"","names":[{"name":"a.jpg"},{"name":"b.jpg"}]}');
    });

    testWidgets('the properties are those of the clicked photograph',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting());
        await tapHeading(tester, "inbox-day-2026-03-01");
        await secondaryClick(tester, "b.jpg");
        await tester.tap(entry("properties"));
        await tester.pumpAndSettle();
      });
      expect(find.byType(ImagePropertiesDialog), findsOneWidget);
      expect(
          find.byKey(const Key("image-properties-read-only")), findsOneWidget);
      expect(
        find.descendant(
            of: find.byKey(const Key("property-file")),
            matching: find.textContaining("b.jpg")),
        findsOneWidget,
      );
    });

    testWidgets('the recording time opens the correction dialog',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting());
        await secondaryClick(tester, "c.jpg");
        await tester.tap(entry("time"));
        await tester.pumpAndSettle();
      });
      expect(find.byType(AdjustRecordingTimeDialog), findsOneWidget);
    });

    testWidgets('the crop opens the crop editor', (tester) async {
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting());
        await secondaryClick(tester, "c.jpg");
        await tester.tap(entry("crop"));
        await tester.pumpAndSettle();
        expect(find.byType(CropEditor), findsOneWidget);
      });
    });

    testWidgets('a dismissed menu does nothing, and the long press toggles',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, accepting(), requests: requests);
        await secondaryClick(tester, "d.jpg");
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(entry("move"), findsNothing);

        await tester.longPress(tile("c.jpg"));
        await tester.pumpAndSettle();
        expect(selectedNames(tester), ["d.jpg", "c.jpg"]);
      });
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  testWidgets("the browser's menu is away while the inbox stands",
      (tester) async {
    expect(browser.enabled, isTrue);
    await pumpInbox(tester, accepting());
    expect(browser.enabled, isFalse);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(browser.enabled, isTrue);
    expect(browser.calls.last, "enable");
  });
}
