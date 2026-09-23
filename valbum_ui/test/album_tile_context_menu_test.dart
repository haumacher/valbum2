/// Tests of the context menu of an edit-mode tile (issue #156): a secondary
/// click opens, at the pointer, the move of the album's menu and the tile's
/// properties, acting on the selection the way the persons editor's menu
/// does (#139/#144), and the browser's own menu is away while the edit mode
/// stands.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart'
    show
        confirmPicker,
        enterFolder,
        json,
        longPressTile,
        movedAll,
        pumpAlbumEditMode,
        recordingClient,
        tile,
        treeAnswer;
import 'persons_not_a_face_test.dart' show RecordingBrowserMenu;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// The selected parts of the album on the screen, by file name.
Set<String> selectedNames(WidgetTester tester) => {
      for (var part in tester
          .state<AlbumContentState>(find.byType(AlbumContent))
          .selection)
        if (part is AbstractImage) part.thumbnailName,
    };

/// Clicks the tile of [name] with the secondary mouse button, beside its
/// toolbars, and returns where.
Future<Offset> secondaryClick(WidgetTester tester, String name) async {
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

final Finder moveEntry = find.byKey(const Key("tile-context-move"));
final Finder propertiesEntry = find.byKey(const Key("tile-context-properties"));

void main() {
  late RecordingBrowserMenu browser;
  late BrowserMenu before;

  setUp(() {
    before = browserMenu;
    browser = RecordingBrowserMenu();
    browserMenu = browser;
  });

  tearDown(() => browserMenu = before);

  http.Response answer(http.Request request) => request.method == "POST"
      ? json(movedAll(["a.jpg", "b.jpg"]))
      : treeAnswer(request);

  testWidgets('a tile of the selection acts on the whole selection',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbumEditMode(tester, recordingClient(answer, requests));
      await longPressTile(tester, "b.jpg");
      expect(selectedNames(tester), {"a.jpg", "b.jpg"});

      var at = await secondaryClick(tester, "b.jpg");

      expect(selectedNames(tester), {"a.jpg", "b.jpg"},
          reason: "a tile of the selection leaves the selection alone");
      expect(moveEntry, findsOneWidget);
      expect(
        find.descendant(
            of: moveEntry,
            matching: find.text(
                testL10n.moveSubjectTo(const ImageSubject(2).asked(testL10n)))),
        findsOneWidget,
      );
      expect(
        find.descendant(
            of: propertiesEntry, matching: find.text(testL10n.imageProperties)),
        findsOneWidget,
      );
      // The menu opens at the pointer.
      var menuBox = tester.getRect(moveEntry);
      expect((menuBox.left - at.dx).abs(), lessThan(48));

      // The entry opens the very picker the album's menu opens ...
      await tester.tap(moveEntry);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("folder-picker")), findsOneWidget);
      expect(find.text("Move 2 images to the top level"), findsOneWidget);
      await enterFolder(tester, "2020 Trip");
      await confirmPicker(tester);
    });

    // ... and posts what `moveSelection` posts.
    var post = requests.singleWhere((r) => r.method == "POST");
    expect(post.url.queryParameters["action"], "move");
    expect(
      post.body,
      '{"target":"2020 Trip","names":[{"name":"a.jpg"},{"name":"b.jpg"}]}',
    );
  });

  testWidgets('a tile outside the selection first becomes the selection',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbumEditMode(tester, recordingClient(answer, requests));
      expect(selectedNames(tester), {"a.jpg"});

      await secondaryClick(tester, "b.jpg");

      expect(selectedNames(tester), {"b.jpg"});
      expect(
        find.descendant(
            of: moveEntry,
            matching: find.text(
                testL10n.moveSubjectTo(const ImageSubject(1).asked(testL10n)))),
        findsOneWidget,
      );
      await tester.tap(moveEntry);
      await tester.pumpAndSettle();
      expect(find.text("Move 1 image to the top level"), findsOneWidget);
      await enterFolder(tester, "2020 Trip");
      await confirmPicker(tester);
    });

    var post = requests.singleWhere((r) => r.method == "POST");
    expect(post.body, '{"target":"2020 Trip","names":[{"name":"b.jpg"}]}');
  });

  testWidgets('the properties entry opens what the tile tool opens',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbumEditMode(tester, recordingClient(answer, []));
      await secondaryClick(tester, "b.jpg");
      await tester.tap(propertiesEntry);
      await tester.pumpAndSettle();
    });

    expect(find.byType(ImagePropertiesDialog), findsOneWidget);
    expect(
      find.descendant(
          of: find.byKey(const Key("property-file")),
          matching: find.textContaining("b.jpg")),
      findsOneWidget,
    );
  });

  testWidgets('a dismissed menu does nothing, and the long press toggles',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbumEditMode(tester, recordingClient(answer, requests));
      await secondaryClick(tester, "a.jpg");
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(moveEntry, findsNothing);
      expect(find.byKey(const Key("folder-picker")), findsNothing);

      await longPressTile(tester, "b.jpg");
      expect(selectedNames(tester), {"a.jpg", "b.jpg"});
      await longPressTile(tester, "b.jpg");
      expect(selectedNames(tester), {"a.jpg"});
    });
    expect(requests.where((r) => r.method == "POST"), isEmpty);
  });

  testWidgets('no menu in the view mode', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(tester);
      var image = tester.getCenter(find.byType(Image).first);
      await tester.tapAt(image,
          buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
      await tester.pumpAndSettle();
    });
    expect(moveEntry, findsNothing);
  });

  testWidgets("the browser's menu is away while the edit mode stands",
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(tester);
      expect(browser.enabled, isTrue);
      expect(browser.calls, isEmpty, reason: "the view mode leaves it alone");

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(browser.enabled, isFalse);

      await tester.tap(find.byKey(const Key("edit-cancel")));
      await tester.pumpAndSettle();
      expect(browser.enabled, isTrue);
      expect(browser.calls.last, "enable");

      // A page left in the middle of an edit gives it back as well.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(browser.enabled, isFalse);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(browser.enabled, isTrue);
    });
  });
}

/// The album `Inbox` of the move tests in its view mode.
Future<void> pumpAlbum(WidgetTester tester) async {
  await tester.pumpWidget(VAlbumApp(
    client: recordingClient(treeAnswer, []),
    initialRoute: const ListingOrAlbumRoute(["Inbox"]),
  ));
  await tester.pumpAndSettle();
}
