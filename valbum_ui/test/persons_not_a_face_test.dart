/// "Not a face" as an action on the selection, and the browser's own context
/// menu that must not stand in the way of the tile's one (issue #144).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_actions_test.dart'
    show
        album,
        annaAndBob,
        chooseInSelectionMenu,
        pumpActions,
        rightClickFace,
        saveEditor,
        tapFace,
        assignments;
import 'persons_selection_test.dart'
    show albumOf, editorState, imageOf, selectionOf;
import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, faceTile, header, json, pumpEditor;
import 'util/fake_image_http.dart';

/// Unfolds the "Not a face" group, which is folded away until it is opened.
Future<void> unfoldNotAFace(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(header(notAFaceGroup));
    await tester.pumpAndSettle();
  });
}

/// The keys of the faces standing under the group of the given key.
Set<String> groupFaces(WidgetTester tester, String key) => {
      for (var group in editorState(tester).groups)
        if (group.key == key)
          for (var face in group.faces) face.key
    };

/// A [BrowserMenu] that only remembers what it was asked to do, standing in
/// for the web the test binding is not.
class RecordingBrowserMenu implements BrowserMenu {
  /// Whether the browser's menu is there, as far as this double knows.
  bool enabled = true;

  /// What was asked of it, in order.
  final List<String> calls = [];

  @override
  Future<void> disable() async {
    calls.add("disable");
    enabled = false;
  }

  @override
  Future<void> enable() async {
    calls.add("enable");
    enabled = true;
  }
}

void main() {
  group("\"Not a face\" on the selection (issue #144)", () {
    testWidgets("puts every selected face into the group and posts it",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await tapFace(tester, "t.jpg#1");
      await chooseInSelectionMenu(tester, "persons-not-a-face");

      // Both stand under "Not a face" now, which the group itself says once
      // it is unfolded — and nothing stands selected any more.
      expect(groupFaces(tester, notAFaceGroup), {"t.jpg#0", "t.jpg#1"});
      expect(selectionOf(tester), isEmpty);
      expect(faceTile("t.jpg#0"), findsNothing);
      await unfoldNotAFace(tester);
      expect(faceTile("t.jpg#0"), findsOneWidget);
      expect(faceTile("t.jpg#1"), findsOneWidget);

      await saveEditor(tester);
      expect(assignments(requests), [
        {"image": "t.jpg", "face": 0, "x": 0.0, "y": 0.0, "w": 0.0, "h": 0.0, "person": "", "state": "NOT_A_FACE"},
        {"image": "t.jpg", "face": 1, "x": 0.0, "y": 0.0, "w": 0.0, "h": 0.0, "person": "", "state": "NOT_A_FACE"},
      ]);
    });

    testWidgets("holds only this session's faces, gone after Save (#155)",
        (tester) async {
      // The server answers a face called no face no more: after Save the
      // album comes back without it.
      var saved = false;
      var after = albumOf([
        imageOf("t.jpg", [faceOf(1, cluster: "c2", y: 0.5)]),
      ]);
      var requests = <http.Request>[];
      await pumpEditor(
        tester,
        editorClient(
          requests,
          auth: authOf(),
          album: album,
          people: annaAndBob,
          post: (request) {
            saved = true;
            return json(after);
          },
          albumNow: () => saved ? after : album,
        ),
      );
      expect(header(notAFaceGroup), findsNothing);

      await tapFace(tester, "t.jpg#0");
      await chooseInSelectionMenu(tester, "persons-not-a-face");
      expect(header(notAFaceGroup), findsOneWidget);

      await saveEditor(tester);
      expect(assignments(requests).single["state"], "NOT_A_FACE");
      expect(header(notAFaceGroup), findsNothing);
      expect(faceTile("t.jpg#0"), findsNothing);
    });

    testWidgets("acts on the one face a right click lands on", (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await rightClickFace(tester, "t.jpg#1");
      expect(
        find.byKey(const Key("persons-context-not-a-face")),
        findsOneWidget,
      );
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-context-not-a-face")));
        await tester.pumpAndSettle();
      });

      // The face the pointer was on, and only that one.
      expect(groupFaces(tester, notAFaceGroup), {"t.jpg#1"});
      expect(
          editorState(tester).placement["t.jpg#0"], "${clusterGroupPrefix}c2");

      await saveEditor(tester);
      expect(assignments(requests), [
        {"image": "t.jpg", "face": 1, "x": 0.0, "y": 0.0, "w": 0.0, "h": 0.0, "person": "", "state": "NOT_A_FACE"},
      ]);
    });
  });

  group("the browser's own context menu (issue #144)", () {
    late RecordingBrowserMenu menu;
    late BrowserMenu before;

    setUp(() {
      before = browserMenu;
      menu = RecordingBrowserMenu();
      browserMenu = menu;
    });

    tearDown(() => browserMenu = before);

    testWidgets("is away while the editor stands open and back afterwards",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      expect(menu.enabled, isFalse);
      expect(menu.calls, ["disable"]);

      // Leaving the editor — here by taking the whole app down — gives the
      // browser its menu back, so the rest of the app keeps it.
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(menu.enabled, isTrue);
      expect(menu.calls, ["disable", "enable"]);
    });
  });
}
