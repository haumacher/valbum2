/// The selection of the face editor (issue #139): the regular click, ctrl and
/// shift semantics of a mouse, the toggling tap of a finger, and the page
/// scrolling while a face is carried near an edge.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, faceTile, pumpEditor, selectFace;
import 'util/fake_image_http.dart';

/// One photograph of the album, with the given faces.
String imageOf(String name, List<String> faces) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1, '
    '"width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0, '
    '"faces": [${faces.join(",")}]}]';

/// An album of the given photographs.
String albumOf(List<String> images) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"facesPending": false, "rights": [{"name": "edit"}], '
    '"parts": [${images.join(",")}]}]';

/// The album the range tests measure in: six faces in three groups.
///
/// The display order is Anna's two faces, then the two of the cluster `c1`,
/// then the two of `c2` — so a range from the third to the sixth face runs
/// across a group boundary, which is what issue #139 asks for.
///
///     A = p.jpg#0   B = p.jpg#1    Anna
///     C = q.jpg#0   D = q.jpg#1    cluster c1
///     E = r.jpg#0   F = r.jpg#1    cluster c2
final String sixFaces = albumOf([
  imageOf("p.jpg", [
    faceOf(0, cluster: "c0", person: "p-anna", state: "CONFIRMED"),
    faceOf(1, cluster: "c0", person: "p-anna", state: "CONFIRMED", y: 0.5),
  ]),
  imageOf("q.jpg", [
    faceOf(0, cluster: "c1"),
    faceOf(1, cluster: "c1", y: 0.5),
  ]),
  imageOf("r.jpg", [
    faceOf(0, cluster: "c2"),
    faceOf(1, cluster: "c2", y: 0.5),
  ]),
]);

/// The state of the editor on the screen.
PersonsContentState editorState(WidgetTester tester) =>
    tester.state<PersonsContentState>(find.byType(PersonsContent));

/// What stands selected, by `<image>#<index>`.
Set<String> selectionOf(WidgetTester tester) => editorState(tester).selection;

/// Clicks the given face with a mouse, the given modifier keys held down.
Future<void> clickFace(
  WidgetTester tester,
  String face, {
  List<LogicalKeyboardKey> keys = const [],
}) async {
  await withFakeImageHttp(() async {
    for (var key in keys) {
      await tester.sendKeyDownEvent(key);
    }
    await tester.tap(faceTile(face), kind: PointerDeviceKind.mouse);
    await tester.pumpAndSettle();
    for (var key in keys) {
      await tester.sendKeyUpEvent(key);
    }
  });
}

/// Opens the editor on the six-face fixture.
Future<void> pumpSixFaces(WidgetTester tester) async {
  var requests = <http.Request>[];
  await pumpEditor(
    tester,
    editorClient(requests, auth: authOf(), album: sixFaces),
  );
}

// ---------------------------------------------------------------------------
// The edge scrolling: an album tall enough to overflow a small window.
// ---------------------------------------------------------------------------

/// An album of [count] faces, one per photograph, all in one cluster.
String manyFaces(int count) => albumOf([
      for (var i = 0; i < count; i++)
        imageOf("f$i.jpg", [faceOf(0, cluster: "c1")]),
    ]);

/// Shrinks the surface to [size] logical pixels for the running test.
void useSurface(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

/// The viewport of the editor's list, in global coordinates.
Rect viewport(WidgetTester tester) => tester.getRect(
      find.descendant(
        of: find.byType(PersonsContent),
        matching: find.byType(Scrollable),
      ),
    );

/// The scroll position of the editor's list.
ScrollPosition scrollPosition(WidgetTester tester) => tester
    .state<ScrollableState>(
      find.descendant(
        of: find.byType(PersonsContent),
        matching: find.byType(Scrollable),
      ),
    )
    .position;

/// Picks a face up by its handle and carries it to [to], pointer still down.
Future<TestGesture> carryFace(
  WidgetTester tester,
  String face,
  Offset to,
) async {
  var handle = find.descendant(
    of: faceTile(face),
    matching: find.byKey(const Key("drag-handle")),
  );
  var start = tester.getCenter(
    handle.evaluate().isEmpty
        ? find
            .descendant(
              of: find.byType(PersonsContent),
              matching: find.byKey(const Key("drag-handle")),
            )
            .first
        : handle,
  );
  var gesture = await tester.startGesture(start);
  await gesture.moveBy(const Offset(0, 20));
  await tester.pump();
  await gesture.moveTo(to);
  await tester.pump();
  return gesture;
}

/// Holds the pointer still for [frames] frames of 16 ms.
Future<void> hold(WidgetTester tester, {int frames = 10}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  group("the selection rule", () {
    test("a plain click replaces what stood selected", () {
      var next = faceSelectionAfterClick(
        order: const ["a", "b", "c"],
        clicked: "b",
        selection: {"a"},
        anchor: "a",
      );
      expect(next.keys, {"b"});
      expect(next.anchor, "b");
    });

    test("a toggling click adds and removes", () {
      var added = faceSelectionAfterClick(
        order: const ["a", "b", "c"],
        clicked: "c",
        selection: {"b"},
        anchor: "b",
        toggle: true,
      );
      expect(added.keys, {"b", "c"});
      expect(added.anchor, "c");

      var removed = faceSelectionAfterClick(
        order: const ["a", "b", "c"],
        clicked: "b",
        selection: added.keys,
        anchor: added.anchor,
        toggle: true,
      );
      expect(removed.keys, {"c"});
      expect(removed.anchor, "b");
    });

    test("a range takes the anchor's own state", () {
      var selected = faceSelectionAfterClick(
        order: const ["a", "b", "c", "d", "e", "f"],
        clicked: "f",
        selection: {"c"},
        anchor: "c",
        range: true,
      );
      expect(selected.keys, {"c", "d", "e", "f"});
      // The anchor does not move, so the range can be drawn anew from it.
      expect(selected.anchor, "c");

      var deselected = faceSelectionAfterClick(
        order: const ["a", "b", "c", "d", "e", "f"],
        clicked: "f",
        selection: {"a", "d", "e"},
        anchor: "c",
        range: true,
      );
      expect(deselected.keys, {"a"});
      expect(deselected.anchor, "c");
    });

    test("a range without an anchor is a plain click", () {
      var next = faceSelectionAfterClick(
        order: const ["a", "b", "c"],
        clicked: "c",
        selection: {"a", "b"},
        range: true,
      );
      expect(next.keys, {"c"});
      expect(next.anchor, "c");
    });

    test("a range whose anchor is gone is a plain click", () {
      var next = faceSelectionAfterClick(
        order: const ["a", "b", "c"],
        clicked: "c",
        selection: {"a"},
        anchor: "x",
        range: true,
      );
      expect(next.keys, {"c"});
      expect(next.anchor, "c");
    });
  });

  group("a mouse in the face editor", () {
    testWidgets("plain click, ctrl-click and ctrl-click again", (tester) async {
      await pumpSixFaces(tester);

      await clickFace(tester, "p.jpg#0");
      expect(selectionOf(tester), {"p.jpg#0"});

      // A plain click on another face replaces the selection.
      await clickFace(tester, "p.jpg#1");
      expect(selectionOf(tester), {"p.jpg#1"});

      await clickFace(tester, "q.jpg#0",
          keys: const [LogicalKeyboardKey.controlLeft]);
      expect(selectionOf(tester), {"p.jpg#1", "q.jpg#0"});

      await clickFace(tester, "p.jpg#1",
          keys: const [LogicalKeyboardKey.controlLeft]);
      expect(selectionOf(tester), {"q.jpg#0"});
    });

    testWidgets("a shift-click selects the range across a group boundary",
        (tester) async {
      await pumpSixFaces(tester);

      await clickFace(tester, "q.jpg#0");
      expect(selectionOf(tester), {"q.jpg#0"});

      await clickFace(tester, "r.jpg#1",
          keys: const [LogicalKeyboardKey.shiftLeft]);
      // C..F in display order: the rest of the cluster and both of the next
      // one, the group boundary between them counting for nothing.
      expect(selectionOf(tester), {
        "q.jpg#0",
        "q.jpg#1",
        "r.jpg#0",
        "r.jpg#1",
      });
      expect(editorState(tester).anchor, "q.jpg#0");
    });

    testWidgets("a shift-click from an anchor clicked off deselects the range",
        (tester) async {
      await pumpSixFaces(tester);

      // Everything from B to F selected, and the anchor then clicked off.
      await clickFace(tester, "p.jpg#1");
      await clickFace(tester, "r.jpg#1",
          keys: const [LogicalKeyboardKey.shiftLeft]);
      expect(selectionOf(tester), hasLength(5));

      await clickFace(tester, "p.jpg#1",
          keys: const [LogicalKeyboardKey.controlLeft]);
      expect(selectionOf(tester).contains("p.jpg#1"), isFalse);

      await clickFace(tester, "r.jpg#0",
          keys: const [LogicalKeyboardKey.shiftLeft]);
      // B..E deselected, F left where it was.
      expect(selectionOf(tester), {"r.jpg#1"});
    });

    testWidgets("a modified click is never half of a double click",
        (tester) async {
      await pumpSixFaces(tester);

      // A ctrl-click is an addition, not the opening half of anything: the
      // plain click that follows it selects, and nothing opens over the page.
      await clickFace(tester, "q.jpg#0",
          keys: const [LogicalKeyboardKey.controlLeft]);
      await clickFace(tester, "q.jpg#0");
      expect(find.byKey(const Key("persons-photo")), findsNothing);
      expect(selectionOf(tester), {"q.jpg#0"});

      // And it is no closing half either: a plain click followed by a
      // shift-click on the same face is a range of one.
      await clickFace(tester, "q.jpg#1");
      await clickFace(tester, "q.jpg#1",
          keys: const [LogicalKeyboardKey.shiftLeft]);
      expect(find.byKey(const Key("persons-photo")), findsNothing);
      expect(selectionOf(tester), {"q.jpg#1"});

      // A click that dismissed a context menu is a first click again.
      await withFakeImageHttp(() async {
        await tester.tapAt(
          tester.getCenter(faceTile("r.jpg#0")),
          buttons: kSecondaryButton,
          kind: PointerDeviceKind.mouse,
        );
        await tester.pumpAndSettle();
        await tester.tapAt(const Offset(5, 5));
        await tester.pumpAndSettle();
      });
      await clickFace(tester, "r.jpg#0");
      expect(find.byKey(const Key("persons-photo")), findsNothing);
      expect(selectionOf(tester), {"r.jpg#0"});
    });

    testWidgets("a second click on the same face shows the photograph",
        (tester) async {
      await pumpSixFaces(tester);

      await clickFace(tester, "q.jpg#0");
      expect(find.byKey(const Key("persons-photo")), findsNothing);

      await clickFace(tester, "q.jpg#0");
      expect(find.byKey(const Key("persons-photo")), findsOneWidget);
      // And the selection is what it was: showing a picture decides nothing.
      expect(selectionOf(tester), {"q.jpg#0"});
    });
  });

  group("a finger in the face editor", () {
    testWidgets("keeps toggling, having no modifier keys", (tester) async {
      await pumpSixFaces(tester);

      await selectFace(tester, "q.jpg#0");
      await selectFace(tester, "r.jpg#0");
      expect(selectionOf(tester), {"q.jpg#0", "r.jpg#0"});

      await selectFace(tester, "q.jpg#0");
      expect(selectionOf(tester), {"r.jpg#0"});
    });
  });

  group("the page scrolling under a carried face (issue #42 through #139)", () {
    testWidgets("a face held at the bottom edge scrolls the page",
        (tester) async {
      useSurface(tester, const Size(500, 400));
      await withFakeImageHttp(() async {
        var requests = <http.Request>[];
        await pumpEditor(
          tester,
          editorClient(requests, auth: authOf(), album: manyFaces(40)),
        );

        var view = viewport(tester);
        var position = scrollPosition(tester);
        expect(position.maxScrollExtent, greaterThan(0));
        expect(position.pixels, 0);

        var gesture = await carryFace(
          tester,
          "f0.jpg#0",
          Offset(view.center.dx, view.bottom - 2),
        );

        await hold(tester, frames: 5);
        var afterFive = position.pixels;
        expect(afterFive, greaterThan(0));
        expect(editorState(tester).dragScrolling, isTrue);

        await hold(tester, frames: 5);
        expect(position.pixels, greaterThan(afterFive));

        // Out of the band it stops, and stays stopped.
        await gesture.moveTo(view.center);
        await tester.pump();
        await hold(tester);
        expect(editorState(tester).dragScrolling, isFalse);
        var stopped = position.pixels;
        await hold(tester);
        expect(position.pixels, stopped);

        await gesture.up();
        await tester.pumpAndSettle();
        expect(editorState(tester).dragScrolling, isFalse);
      });
    });

    testWidgets("the drop still works after the page scrolled", (tester) async {
      useSurface(tester, const Size(500, 400));
      await withFakeImageHttp(() async {
        var requests = <http.Request>[];
        await pumpEditor(
          tester,
          editorClient(requests, auth: authOf(), album: manyFaces(40)),
        );

        var view = viewport(tester);
        var gesture = await carryFace(
          tester,
          "f0.jpg#0",
          Offset(view.center.dx, view.bottom - 2),
        );
        // What was out of reach before: the foot of the page, scrolled into
        // view under the carried face, without the drag ever being let go.
        var target = find.byKey(const Key("persons-new-group"));
        for (var i = 0; i < 200 && target.evaluate().isEmpty; i++) {
          await hold(tester, frames: 5);
        }
        expect(scrollPosition(tester).pixels, greaterThan(0));
        expect(target, findsOneWidget);
        await gesture.moveTo(tester.getCenter(target));
        await tester.pump();
        await gesture.up();
        await tester.pumpAndSettle();

        var state = editorState(tester);
        expect(state.dragScrolling, isFalse);
        expect(state.placement["f0.jpg#0"], startsWith(newGroupPrefix));
      });
    });
  });
}
