/// What can be done to a selection of faces without dragging it anywhere
/// (issue #139): the app bar's menu, the context menu of a tile, the
/// "Someone else…" of a suggestion, and the photograph a face was cut from.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_selection_test.dart'
    show albumOf, editorState, imageOf, selectionOf;
import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, faceTile, json, posted, pumpEditor;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// The register of the space: the person the server guesses, and another one.
String annaAndBob() => '{"people": ['
    '{"id": "p-anna", "name": "Anna"}, '
    '{"id": "p-bob", "name": "Bob"}]}';

/// The album the actions are tried on.
///
///  * `s.jpg` carries two faces the server only *suggests* to be Anna;
///  * `t.jpg` two faces of an unknown cluster;
///  * `u.jpg` one face confirmed as Anna.
final String album = albumOf([
  imageOf("s.jpg", [
    faceOf(0, cluster: "c1", person: "p-anna"),
    faceOf(1, cluster: "c1", person: "p-anna", y: 0.5),
  ]),
  imageOf("t.jpg", [
    faceOf(0, cluster: "c2"),
    faceOf(1, cluster: "c2", y: 0.5),
  ]),
  imageOf("u.jpg", [
    faceOf(0, cluster: "c3", person: "p-anna", state: "CONFIRMED"),
  ]),
]);

/// Opens the editor on [album].
Future<void> pumpActions(WidgetTester tester, List<http.Request> requests) =>
    pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: album,
        people: annaAndBob,
        post: (request) => json(album),
      ),
    );

/// Taps the tile of the given face, which selects it (a finger toggles).
Future<void> tapFace(WidgetTester tester, String face) async {
  await withFakeImageHttp(() async {
    await tester.tap(faceTile(face));
    await tester.pumpAndSettle();
  });
}

/// Opens the app bar's selection menu and chooses the entry of [key].
Future<void> chooseInSelectionMenu(WidgetTester tester, String key) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(const Key("persons-selection-menu")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();
  });
}

/// Right-clicks the tile of the given face and chooses the entry of [key].
///
/// [key] may be left out to open the menu and look at what it holds.
Future<void> rightClickFace(
  WidgetTester tester,
  String face, {
  String? key,
}) async {
  await withFakeImageHttp(() async {
    await tester.tapAt(
      tester.getCenter(faceTile(face)),
      buttons: kSecondaryButton,
      kind: PointerDeviceKind.mouse,
    );
    await tester.pumpAndSettle();
    if (key != null) {
      await tester.tap(find.byKey(Key(key)));
      await tester.pumpAndSettle();
    }
  });
}

/// Picks the person of the given id in the chooser standing open.
Future<void> pickPerson(WidgetTester tester, String id) async {
  await withFakeImageHttp(() async {
    expect(find.byKey(const Key("persons-chooser")), findsOneWidget);
    await tester.tap(find.byKey(Key("persons-pick-$id")));
    await tester.pumpAndSettle();
  });
}

/// Taps the Save of the editor.
Future<void> saveEditor(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(const Key("persons-save")));
    await tester.pumpAndSettle();
  });
}

/// The assignments of the one `tag-faces` that was posted.
List<dynamic> assignments(List<http.Request> requests) {
  var requestsMade = posted(requests, "tag-faces");
  expect(requestsMade, hasLength(1));
  return requestsMade.single["faces"] as List<dynamic>;
}

void main() {
  group("the app bar's selection menu", () {
    testWidgets("names the selected faces as one person", (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await tapFace(tester, "t.jpg#1");
      await chooseInSelectionMenu(tester, "persons-name");
      await pickPerson(tester, "p-bob");

      // Both stand under Bob now, and nothing stands selected any more.
      expect(editorState(tester).placement["t.jpg#0"],
          "${personGroupPrefix}p-bob");
      expect(editorState(tester).placement["t.jpg#1"],
          "${personGroupPrefix}p-bob");
      expect(selectionOf(tester), isEmpty);
      expect(find.byKey(const Key("persons-heading-person:p-bob")),
          findsOneWidget);

      await saveEditor(tester);
      expect(assignments(requests), [
        {"image": "t.jpg", "face": 0, "person": "p-bob", "state": "CONFIRMED"},
        {"image": "t.jpg", "face": 1, "person": "p-bob", "state": "CONFIRMED"},
      ]);
    });

    testWidgets("defers them into a group of their own", (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await tapFace(tester, "u.jpg#0");
      await chooseInSelectionMenu(tester, "persons-defer");

      var group = "${newGroupPrefix}1";
      expect(editorState(tester).placement["t.jpg#0"], group);
      expect(editorState(tester).placement["u.jpg#0"], group);
      expect(selectionOf(tester), isEmpty);
      expect(find.byKey(Key("persons-heading-$group")), findsOneWidget);
    });

    testWidgets("forgets a placement that is only in the buffer",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      // A face of a cluster, named by hand: nothing the server knows about.
      await tapFace(tester, "t.jpg#0");
      await chooseInSelectionMenu(tester, "persons-name");
      await pickPerson(tester, "p-bob");
      expect(editorState(tester).dirty, isTrue);

      await tapFace(tester, "t.jpg#0");
      await chooseInSelectionMenu(tester, "persons-forget");

      // Back where the server put it, and nothing to write about it.
      expect(
          editorState(tester).placement["t.jpg#0"], "${clusterGroupPrefix}c2");
      expect(editorState(tester).dirty, isFalse);

      await saveEditor(tester);
      expect(posted(requests, "tag-faces"), isEmpty);
    });

    testWidgets("is offered whenever something stands selected",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      expect(find.byKey(const Key("persons-selection-menu")), findsNothing);
      await tapFace(tester, "t.jpg#0");
      expect(find.byKey(const Key("persons-selection-menu")), findsOneWidget);
    });
  });

  group("the context menu of a tile", () {
    testWidgets("acts on the whole selection when the face is part of it",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await tapFace(tester, "t.jpg#1");
      await rightClickFace(tester, "t.jpg#1", key: "persons-context-defer");

      var group = "${newGroupPrefix}1";
      expect(editorState(tester).placement["t.jpg#0"], group);
      expect(editorState(tester).placement["t.jpg#1"], group);
    });

    testWidgets("replaces the selection where the face stands outside it",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await tapFace(tester, "t.jpg#0");
      await rightClickFace(tester, "t.jpg#1", key: "persons-context-defer");

      var group = "${newGroupPrefix}1";
      expect(editorState(tester).placement["t.jpg#1"], group);
      // The one that stood selected was not touched.
      expect(
          editorState(tester).placement["t.jpg#0"], "${clusterGroupPrefix}c2");
    });

    testWidgets("offers the photograph first, and nothing to an onlooker",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await rightClickFace(tester, "t.jpg#0");
      expect(find.byKey(const Key("persons-context-show")), findsOneWidget);
      expect(find.byKey(const Key("persons-context-name")), findsOneWidget);
      expect(find.byKey(const Key("persons-context-defer")), findsOneWidget);
      // Nothing was decided about this face, so there is nothing to forget.
      expect(find.byKey(const Key("persons-context-forget")), findsNothing);
    });
  });

  group("\"Someone else…\" on a suggestion (issue #139)", () {
    testWidgets("names the right person and rejects nobody", (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      expect(
        find.text(testL10n.personsSuggestedHeading("Anna")),
        findsOneWidget,
      );
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-someone-else-p-anna")));
        await tester.pumpAndSettle();
      });
      await pickPerson(tester, "p-bob");

      expect(editorState(tester).placement["s.jpg#0"],
          "${personGroupPrefix}p-bob");
      expect(editorState(tester).placement["s.jpg#1"],
          "${personGroupPrefix}p-bob");

      await saveEditor(tester);
      // Two confirmations for Bob — and no rejection of Anna: naming the
      // right person is the whole statement.
      expect(assignments(requests), [
        {"image": "s.jpg", "face": 0, "person": "p-bob", "state": "CONFIRMED"},
        {"image": "s.jpg", "face": 1, "person": "p-bob", "state": "CONFIRMED"},
      ]);
    });

    testWidgets("choosing the suggested person is a confirmation",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("persons-someone-else-p-anna")));
        await tester.pumpAndSettle();
      });
      await pickPerson(tester, "p-anna");
      await saveEditor(tester);

      expect(assignments(requests), [
        {"image": "s.jpg", "face": 0, "person": "p-anna", "state": "CONFIRMED"},
        {"image": "s.jpg", "face": 1, "person": "p-anna", "state": "CONFIRMED"},
      ]);
    });
  });

  group("the photograph a face was cut from (issue #139)", () {
    testWidgets("opens from the context menu and marks the face on it",
        (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await rightClickFace(tester, "t.jpg#0", key: "persons-context-show");
      expect(find.byKey(const Key("persons-photo")), findsOneWidget);

      // The file name stands under it, and the box is drawn inside the
      // picture's own rectangle.
      expect(find.byKey(const Key("persons-photo-name")), findsOneWidget);
      expect(find.text("t.jpg"), findsOneWidget);

      var picture =
          tester.getRect(find.byKey(const Key("persons-photo-picture")));
      var box = tester.getRect(find.byKey(const Key("persons-photo-box")));
      expect(picture.contains(box.topLeft), isTrue);
      expect(picture.contains(box.bottomRight - const Offset(1, 1)), isTrue);
      // And it is large: the dialog is not the 480 px thumbnail it was.
      expect(picture.width, greaterThan(480));
    });

    testWidgets("opens on a long press as it always did", (tester) async {
      var requests = <http.Request>[];
      await pumpActions(tester, requests);

      await withFakeImageHttp(() async {
        await tester.longPress(faceTile("u.jpg#0"));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("persons-photo")), findsOneWidget);
    });
  });
}
