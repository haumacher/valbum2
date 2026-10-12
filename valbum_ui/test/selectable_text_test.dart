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

import 'catch_up_test.dart' show authOfUser, json, statusOf;
import 'load_failure_test.dart' as failure;
import 'move_test.dart' show tile;
import 'select_mode_test.dart' show markedNames, pumpMember;
import 'util/fake_image_http.dart';
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
    testWidgets('the failure of the catch-up can be selected and copied',
        (tester) async {
      var copied = recordClipboard(tester);
      await pumpSettings(tester);
      await tester.tap(find.byKey(catchUpLineKey));
      await tester.pumpAndSettle();

      var line = find.text(testL10n.catchUpFailure(
          "2023/Broken", "previews: broken.jpg: Cannot read."));
      expect(line, findsOneWidget);
      expect(selectable(line), isTrue);

      await dragAndCopy(tester, line);
      expect(copied, isNotEmpty);
      expect(copied.last, contains("previews: broken.jpg: Cannot read."));
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
