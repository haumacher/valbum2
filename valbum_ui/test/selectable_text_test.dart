/// Text that can be selected and copied almost everywhere (issue #250).
///
/// Every page, dialog and sheet is a selectable area, see `selectable.dart`;
/// the gesture surfaces — the thumbnail grids, the viewer's picture — are
/// excluded, and keep every gesture and key they had.
library;

import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:valbum_ui/catch_up.dart';
import 'package:valbum_ui/keyboard_scroll.dart';
import 'package:valbum_ui/load_failure.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/form_dialog.dart';
import 'package:valbum_ui/selectable.dart';
import 'package:valbum_ui/video_view.dart' show VideoView;
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'catch_up_test.dart' show authOfUser, json, statusOf;
import 'load_failure_test.dart' as failure;
import 'move_test.dart' show tile;
import 'place_caption_test.dart' as place;
import 'select_mode_test.dart' show markedNames, pumpMember;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';
import 'viewer_step_test.dart' as viewer;

/// Whether [text] lies inside a selectable area.
bool selectable(Finder text) => find
    .ancestor(of: text, matching: find.byType(SelectionArea))
    .evaluate()
    .isNotEmpty;

/// Records what is put on the clipboard.
List<String> recordClipboard(WidgetTester tester) {
  var copied = <String>[];
  var messenger = tester.binding.defaultBinaryMessenger;
  messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == "Clipboard.setData") {
      copied.add((call.arguments as Map)["text"] as String);
    }
    return null;
  });
  addTearDown(
      () => messenger.setMockMethodCallHandler(SystemChannels.platform, null));
  return copied;
}

/// Selects [text] with a drag of the mouse across it, and copies it with the
/// keyboard, as one does at a desktop.
Future<void> dragAndCopy(WidgetTester tester, Finder text) async {
  var rect = tester.getRect(text);
  var gesture = await tester.startGesture(
    rect.topLeft + const Offset(1, 1),
    kind: PointerDeviceKind.mouse,
  );
  await tester.pump();
  await gesture.moveTo(rect.bottomRight - const Offset(1, 1));
  await tester.pump();
  await gesture.up();
  await tester.pump();
  await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
  await tester.sendKeyEvent(LogicalKeyboardKey.keyC);
  await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
  await tester.pump();
}

/// The gesture detector of the selectable area around [of].
RenderObject areaDetector(WidgetTester tester, Finder of) =>
    tester.renderObject(find
        .descendant(
          of: find.ancestor(of: of, matching: find.byType(SelectableRegion)),
          matching: find.byType(RawGestureDetector),
        )
        .first);

/// Whether a pointer going down in the middle of [target] meets the gesture
/// recognisers of the selectable area around it.
bool areaTakesPointerAt(WidgetTester tester, Finder target) {
  var detector = areaDetector(tester, target);
  var result = HitTestResult();
  tester.binding
      .hitTestInView(result, tester.getCenter(target), tester.view.viewId);
  return result.path.any((entry) => entry.target == detector);
}

/// The server settings, opened over a page as the app opens them, for an
/// administrator, with an offline cache to clear.
Future<void> pumpSettings(WidgetTester tester) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var settings = ServerSettings(
      store: InMemorySettingsStore(
          "http://server/valbum/", "dev-2", "Phone", "carol"));
  await settings.load();
  var transport = MockClient((request) async {
    var query = request.url.queryParameters;
    return switch (query["type"]) {
      "auth" => json(authOfUser("admin")),
      "catch-up" => json(statusOf()),
      "invitations" => json('{"invitations": []}'),
      "devices" => json('{"devices": []}'),
      "contacts" => json('{"contacts": []}'),
      _ => json('{"users": []}'),
    };
  });
  await tester.pumpWidget(
    OfflineScope(
      state: OfflineState(),
      cache: MemoryOfflineCache(),
      child: MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: ServerSettingsScope(
          settings: settings,
          clientFor: (dataUrl) =>
              VAlbumClient(dataUrl: dataUrl, httpClient: transport),
          child: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                key: const Key("open-settings"),
                onPressed: () => openServerSettings(context),
                child: const Text("Settings"),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.byKey(const Key("open-settings")));
  await tester.pumpAndSettle();
}

void main() {
  group('the settings', () {
    testWidgets(
        'the failure of the catch-up can be selected and copied on the '
        'status page (issue #305)', (tester) async {
      var copied = recordClipboard(tester);
      var monitor = StatusMonitor();
      monitor.attach(VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async =>
            json('{"admin": true, "catchUp": ${statusOf()}}')),
      ));
      await tester.binding.setSurfaceSize(const Size(800, 3600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(StatusScope(
        monitor: monitor,
        child: MaterialApp(
          localizationsDelegates: testLocalizationsDelegates,
          supportedLocales: testSupportedLocales,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                key: const Key("open-status"),
                onPressed: () => openStatusPage(context),
                child: const Text("Status"),
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const Key("open-status")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(catchUpLineKey));
      await tester.pumpAndSettle();

      var line = find.text(testL10n.catchUpFailure(
          "2023/Broken", "previews: broken.jpg: Cannot read."));
      expect(line, findsOneWidget);
      expect(selectable(line), isTrue);

      await dragAndCopy(tester, line);
      expect(copied, isNotEmpty);
      expect(copied.last, contains("previews: broken.jpg: Cannot read."));
      await tester.pumpWidget(const SizedBox());
      monitor.dispose();
    });

    testWidgets(
        'a dialog\'s question can be copied, and a tap beside it '
        'still closes it', (tester) async {
      var copied = recordClipboard(tester);
      await pumpSettings(tester);
      await tester.ensureVisible(find.byKey(clearCacheButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(clearCacheButtonKey));
      await tester.pumpAndSettle();

      var question = find.text(testL10n.clearCacheQuestion);
      expect(question, findsOneWidget);
      expect(selectable(question), isTrue);
      await dragAndCopy(tester, question);
      expect(copied.last, testL10n.clearCacheQuestion);

      // The area around the dialog leaves the barrier its tap.
      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();
      expect(question, findsNothing);
    });

    testWidgets('a form dialog is selectable too, and its field still types',
        (tester) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => showFormDialog<void>(
              context: context,
              builder: (context) => const AlertDialog(
                title: Text("Name the album"),
                content: TextField(key: Key("field")),
              ),
            ),
            child: const Text("open"),
          ),
        ),
      ));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();

      expect(selectable(find.text("Name the album")), isTrue);
      await tester.enterText(find.byKey(const Key("field")), "Trip");
      expect(find.text("Trip"), findsOneWidget);
    });
  });

  group('leaving with a selection', () {
    /// A dialog with a text, a field and a button that closes it, opened by
    /// [open].
    Future<void> pumpDialog(
      WidgetTester tester,
      Future<void> Function(BuildContext, WidgetBuilder) open,
    ) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => open(
              context,
              (context) => AlertDialog(
                title: const Text("Name the album"),
                content: const TextField(key: Key("field")),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text("Close"),
                  ),
                ],
              ),
            ),
            child: const Text("open"),
          ),
        ),
      ));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();
    }

    /// Selects the dialog's title, clicks into its field and closes it with
    /// its button.
    Future<void> selectClickAndClose(WidgetTester tester) async {
      await dragAndCopy(tester, find.text("Name the album"));
      var click = await tester.startGesture(
          tester.getCenter(find.byKey(const Key("field"))),
          kind: PointerDeviceKind.mouse);
      await click.up();
      await tester.pump();
      await tester.tap(find.text("Close"));
      await tester.pumpAndSettle();
      expect(find.text("Name the album"), findsNothing);
      // The tree is taken down with the focus requests the clicks left.
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    }

    testWidgets('a dialog closed by its button', (tester) async {
      recordClipboard(tester);
      await pumpDialog(
          tester,
          (context, builder) =>
              showAppDialog<void>(context: context, builder: builder));
      await selectClickAndClose(tester);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a form dialog closed by its button', (tester) async {
      recordClipboard(tester);
      await pumpDialog(
          tester,
          (context, builder) =>
              showFormDialog<void>(context: context, builder: builder));
      await selectClickAndClose(tester);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a dialog closed by Escape right after a click into it',
        (tester) async {
      recordClipboard(tester);
      await pumpDialog(
          tester,
          (context, builder) =>
              showAppDialog<void>(context: context, builder: builder));
      await dragAndCopy(tester, find.text("Name the album"));
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(find.text("Name the album"), findsNothing);
      await tester.pumpWidget(const SizedBox());
      expect(tester.takeException(), isNull);
    });

    testWidgets('a dialog taken down with its field focused', (tester) async {
      // The field's focus falls back to the area as both leave the tree, the
      // way a page with an open dialog is replaced.
      recordClipboard(tester);
      await pumpDialog(
          tester,
          (context, builder) =>
              showAppDialog<void>(context: context, builder: builder));
      await dragAndCopy(tester, find.text("Name the album"));
      var click = await tester.startGesture(
          tester.getCenter(find.byKey(const Key("field"))),
          kind: PointerDeviceKind.mouse);
      await click.up();
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('the album, left upwards and into the viewer', (tester) async {
      recordClipboard(tester);
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await dragAndCopy(tester, find.text("Morning"));

        // Into the viewer with the heading still selected, and back.
        await tester.tap(tile("a.jpg"));
        await tester.pumpAndSettle();
        expect(find.byType(ImageView), findsOneWidget);
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.byType(ImageView), findsNothing);

        // Selected again, and the album itself left by the back button.
        await dragAndCopy(tester, find.text("Morning"));
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.text("Morning"), findsNothing);
        await tester.pumpWidget(const SizedBox());
        await tester.pumpAndSettle();
      });
      expect(tester.takeException(), isNull);
    });
  });

  group('a failed load', () {
    testWidgets('says what failed in words one can copy', (tester) async {
      var copied = recordClipboard(tester);
      await failure.pumpAt(
        tester,
        const ListingOrAlbumRoute(["Trip"]),
        failure.serverKnowingOnlyTheRoot(),
      );

      var headline = find.byKey(headlineKey);
      expect(selectable(headline), isTrue);
      await dragAndCopy(tester, headline);
      expect(copied.last, failure.headline(tester));

      // The buttons beside it take their taps as before.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(goToStartKey));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(headlineKey), findsNothing);
    });
  });

  group('the album', () {
    testWidgets('a tile is a gesture surface, a heading is text',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);

        expect(
            find.ancestor(
                of: tile("a.jpg"), matching: find.byType(SelectionExcluded)),
            findsOneWidget);
        expect(areaTakesPointerAt(tester, tile("a.jpg")), isFalse);
        expect(areaTakesPointerAt(tester, find.text("Morning")), isTrue);
      });
    });

    testWidgets('a heading can be copied', (tester) async {
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await dragAndCopy(tester, find.text("Morning"));
      });
      expect(copied.last, "Morning");
    });

    testWidgets('a tap opens the photo', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await tester.tap(tile("a.jpg"));
        await tester.pumpAndSettle();
        expect(find.byType(ImageView), findsOneWidget);
      });
    });

    testWidgets('a click held down still opens the photo', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        // Held well past the moment the selection would take it as a press.
        var gesture = await tester.startGesture(tester.getCenter(tile("a.jpg")),
            kind: PointerDeviceKind.mouse);
        await tester.pump(const Duration(milliseconds: 300));
        await gesture.up();
        await tester.pumpAndSettle();
        expect(find.byType(ImageView), findsOneWidget);
      });
    });

    testWidgets('a long press still enters the select mode', (tester) async {
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await tester.longPress(tile("a.jpg"));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
        expect(markedNames(), ["a.jpg"]);
      });
    });

    testWidgets('the arrow keys still scroll after text was selected',
        (tester) async {
      await tester.binding.setSurfaceSize(const Size(800, 300));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await pumpMember(tester);
        await dragAndCopy(tester, find.text("Morning"));
        expect(copied.last, "Morning");

        var scroll =
            tester.state<KeyboardScrollState>(find.byType(KeyboardScroll));
        // The page keeps the focus: the selection did not take it away.
        expect(scroll.focusNode.hasPrimaryFocus, isTrue);
        var before = scroll.controller.position.pixels;
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
        await tester.pumpAndSettle();
        expect(scroll.controller.position.pixels, greaterThan(before));
      });
    });
  });

  group('the viewer', () {
    testWidgets('the picture is a gesture surface, and still pages',
        (tester) async {
      var log = viewer.RouteLog();
      await viewer.pumpApp(tester, log,
          at: const ImageRoute(viewer.albumPath, "landscape.jpg"));

      var surface = find.byKey(const Key("viewer-surface"));
      expect(
          find.descendant(of: surface, matching: find.byType(GestureDetector)),
          findsWidgets);
      expect(areaTakesPointerAt(tester, surface), isFalse);

      await viewer.act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
      expect(viewer.shownName(tester), "portrait.jpg");

      await viewer.swipe(tester, const Offset(420, 0));
      expect(viewer.shownName(tester), "landscape.jpg");
    });

    testWidgets(
        'the album beneath a deep link becomes selectable on the way '
        'back', (tester) async {
      var log = viewer.RouteLog();
      await viewer.pumpApp(tester, log,
          at: const ImageRoute(viewer.albumPath, "landscape.jpg"));
      await viewer.act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowUp));
      expect(find.byType(ImageView), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });

  group('the viewer\'s caption and panels (issue #280)', () {
    late FakeVideoPlayerPlatform platform;

    setUp(() {
      platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
    });

    /// The album of `place_caption_test.dart` opened on the photo [name].
    Future<void> openPhoto(WidgetTester tester, String name,
        {String Function()? body}) async {
      await place.pumpAlbum(tester, [], body: body);
      await tester.tap(place.tile(name));
      await tester.pumpAndSettle();
      expect(viewer.shownName(tester), name);
    }

    /// The text of the caption under the picture.
    Finder captionText() => find
        .descendant(
            of: find.byKey(const Key("image-caption")),
            matching: find.byType(RichText))
        .first;

    /// An album of a described video, beside a photo.
    String videoAlbum() =>
        '["AlbumInfo", {"path": "2021/Trip", "title": "Trip", '
        '"rights": [{"name": "edit"}], "parts": ['
        '["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", '
        '"date": 1015113600000, "width": 1920, "height": 1080, '
        '"orientation": "IDENTITY", "comment": "Down at the river"}], '
        '["ImagePart", {"kind": "IMAGE", "name": "next.jpg", '
        '"date": 1015113700000, "width": 2048, "height": 1536, '
        '"orientation": "IDENTITY"}]'
        ']}]';

    testWidgets('a description is copied, in a colour that shows on black',
        (tester) async {
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await openPhoto(tester, "described.jpg");

        var text = captionText();
        expect(selectable(text), isTrue);
        expect(areaTakesPointerAt(tester, text), isTrue);
        await dragAndCopy(tester, text);
        expect(copied.last, "At the lake");
        // The theme's colour is all but black on the viewer's black.
        expect(tester.renderObject<RenderParagraph>(text).selectionColor,
            selectionHighlight);

        // Selecting neither paged nor zoomed, and the arrows still page.
        expect(viewer.shownName(tester), "described.jpg");
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        expect(viewer.shownName(tester), "placed.jpg");
      });
    });

    testWidgets('the album\'s black page shows a selection too',
        (tester) async {
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await place.pumpAlbum(tester, []);
        var title = find.text("Trip").first;
        var paragraph = find.descendant(
            of: title, matching: find.byType(RichText), matchRoot: true);
        expect(tester.renderObject<RenderParagraph>(paragraph).selectionColor,
            selectionHighlight);
        await dragAndCopy(tester, title);
        expect(copied.last, "Trip");
      });
    });

    testWidgets('a place is copied, and its map still opens', (tester) async {
      var copied = recordClipboard(tester);
      var urls = place.launched(tester);
      await withFakeImageHttp(() async {
        await openPhoto(tester, "placed.jpg");

        await dragAndCopy(tester, captionText());
        expect(copied.last, contains("Karlsruhe"));

        await tester.tap(find.byKey(const Key("image-place-caption-map")));
        await tester.pumpAndSettle();
        expect(urls, hasLength(1));
        expect(viewer.shownName(tester), "placed.jpg");
      });
    });

    testWidgets('a long press on the caption selects, on the picture edits',
        (tester) async {
      await withFakeImageHttp(() async {
        await openPhoto(tester, "described.jpg");

        await tester.longPress(captionText());
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsNothing);
        expect(find.text("Copy"), findsOneWidget);

        // On the picture, the long press is the description's (issue #80).
        await tester.longPressAt(tester.getCenter(find.byType(ImageView)));
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsOneWidget);
      });
    });

    testWidgets('a tap on the picture still zooms, a swipe still pages',
        (tester) async {
      await withFakeImageHttp(() async {
        await openPhoto(tester, "described.jpg");
        var state = viewer.viewerOf(tester);
        var tx = state.transform(tester.getSize(find.byType(ImageView)));
        expect(tx.scale, tx.fitScale);

        await tester.tapAt(tester.getCenter(find.byType(ImageView)));
        await tester.pump(kDoubleTapTimeout);
        await tester.pumpAndSettle();
        expect(tx.scale, greaterThan(tx.fitScale));
        await tester.tapAt(tester.getCenter(find.byType(ImageView)));
        await tester.pump(kDoubleTapTimeout);
        await tester.pumpAndSettle();
        expect(tx.scale, tx.fitScale);

        await viewer.swipe(tester, const Offset(-420, 0));
        expect(viewer.shownName(tester), "placed.jpg");
      });
    });

    testWidgets('the caption of a video is copied too', (tester) async {
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await openPhoto(tester, "clip.mp4", body: videoAlbum);
        expect(find.byType(VideoView), findsOneWidget);

        await dragAndCopy(tester, captionText());
        expect(copied.last, "Down at the river");
        expect(viewer.shownName(tester), "clip.mp4");
      });
    });

    testWidgets(
        'what the video view says is copied, and a long press on it '
        'selects instead of editing', (tester) async {
      platform.failInit = true;
      var copied = recordClipboard(tester);
      await withFakeImageHttp(() async {
        await openPhoto(tester, "clip.mp4", body: videoAlbum);
        var headline = find.byKey(const Key("video-error-headline"));
        expect(headline, findsOneWidget);

        // Inside the surface, and the page's all the same.
        expect(
            find.ancestor(
                of: headline, matching: find.byType(SelectionExcluded)),
            findsOneWidget);
        expect(areaTakesPointerAt(tester, headline), isTrue);
        await dragAndCopy(tester, headline);
        expect(copied.last, tester.widget<Text>(headline).data);
        expect(viewer.shownName(tester), "clip.mp4");

        await tester.longPress(headline);
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsNothing);

        // Beside the panel the surface is the surface: no selection's, a
        // swipe pages.
        var surface = find.byKey(const Key("viewer-surface"));
        var beside = tester.getTopLeft(surface) + const Offset(100, 100);
        var result = HitTestResult();
        tester.binding.hitTestInView(result, beside, tester.view.viewId);
        expect(
            result.path
                .any((entry) => entry.target == areaDetector(tester, headline)),
            isFalse);
        await tester.longPressAt(beside);
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsOneWidget);
        Navigator.of(tester.element(find.byType(TextField))).pop();
        await tester.pumpAndSettle();

        // A video pages on a fling, see `ImageViewState.onSwipe`.
        await viewer.act(tester,
            () => tester.flingFrom(beside, const Offset(-300, 0), 1500));
        expect(viewer.shownName(tester), "next.jpg");
      });
    });
  });

  group('the way dialogs are opened', () {
    test('is the selectable one, everywhere', () {
      var raw = RegExp(
          r"\bshow(Dialog|ModalBottomSheet|GeneralDialog)\s*<|\bSelectionArea\(");
      var offenders = [
        for (var file in Directory("lib").listSync(recursive: true))
          if (file is File &&
              file.path.endsWith(".dart") &&
              !file.path.endsWith("selectable.dart") &&
              raw.hasMatch(file.readAsStringSync()))
            file.path,
      ];
      expect(offenders, isEmpty,
          reason: "open dialogs and sheets with showAppDialog, "
              "showAppModalBottomSheet or showFormDialog, see selectable.dart");
    });
  });
}
