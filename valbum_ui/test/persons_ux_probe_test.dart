/// Probe of issue #139: the regular selection semantics composed with the
/// folded "Not a face" group, the context menu on an unselected face, the
/// generalised "Forget" and the Save delta — one flow, never seen by the
/// delivery.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_actions_test.dart'
    show pickPerson, rightClickFace, saveEditor, chooseInSelectionMenu;
import 'persons_selection_test.dart'
    show albumOf, clickFace, editorState, imageOf, selectionOf;
import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, faceTile, header, json, posted,
        pumpEditor;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// Anna's two faces, cluster c1 (C, D), cluster c2 (E, F), and one box
/// somebody said is no face at all (N) — the folded group at the end.
final String probeAlbum = albumOf([
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
  imageOf("n.jpg", [
    faceOf(0, cluster: "", state: "NOT_A_FACE"),
  ]),
]);

String people() => '{"people": ['
    '{"id": "p-anna", "name": "Anna"}, '
    '{"id": "p-bob", "name": "Bob"}]}';

Future<List<http.Request>> pumpProbe(WidgetTester tester) async {
  var requests = <http.Request>[];
  await pumpEditor(
    tester,
    editorClient(
      requests,
      auth: authOf(),
      album: probeAlbum,
      people: people,
      post: (request) => json(probeAlbum),
    ),
  );
  return requests;
}

List<dynamic> tagFaces(List<http.Request> requests) {
  var posts = posted(requests, "tag-faces");
  expect(posts, hasLength(1));
  return posts.single["faces"] as List<dynamic>;
}

/// The keys of the faces standing under the group of the given key.
Set<String> groupFaces(WidgetTester tester, String key) => {
      for (var group in editorState(tester).groups)
        if (group.key == key)
          for (var face in group.faces) face.key
    };

void main() {
  testWidgets(
      "a shift range reaches into the unfolded 'Not a face' group and "
      "Forget takes back only the stored decision", (tester) async {
    var requests = await pumpProbe(tester);

    // Folded: N is not on the page, so a range to F ends at F.
    expect(faceTile("n.jpg#0"), findsNothing);
    await clickFace(tester, "q.jpg#0");
    await clickFace(tester, "r.jpg#1", keys: [LogicalKeyboardKey.shiftLeft]);
    expect(selectionOf(tester),
        {"q.jpg#0", "q.jpg#1", "r.jpg#0", "r.jpg#1"});

    // Unfold and extend the range to N: the anchor is still C.
    await withFakeImageHttp(() async {
      await tester.tap(header(notAFaceGroup));
      await tester.pumpAndSettle();
    });
    expect(faceTile("n.jpg#0"), findsOneWidget);
    await tester.ensureVisible(faceTile("n.jpg#0"));
    await tester.pumpAndSettle();
    await clickFace(tester, "n.jpg#0", keys: [LogicalKeyboardKey.shiftLeft]);
    expect(selectionOf(tester),
        {"q.jpg#0", "q.jpg#1", "r.jpg#0", "r.jpg#1", "n.jpg#0"});

    // Forget: only N carries a decision; the others are passed over.
    await chooseInSelectionMenu(tester, "persons-forget");
    expect(selectionOf(tester), isEmpty);
    // N fell back into "Who is this?" — it is no longer under "Not a face".
    expect(groupFaces(tester, notAFaceGroup), isNot(contains("n.jpg#0")));

    await saveEditor(tester);
    var posted = tagFaces(requests);
    expect(posted, hasLength(1));
    expect(posted.single["image"], "n.jpg");
    expect(posted.single["state"], "UNDECIDED");
  });

  testWidgets(
      "a right click on an unselected face acts on it alone, a right click "
      "on a selected one on the whole selection, and Save posts the sum",
      (tester) async {
    var requests = await pumpProbe(tester);

    // C is ctrl-selected, then E (unselected) is right-clicked and deferred.
    await clickFace(tester, "q.jpg#0", keys: [LogicalKeyboardKey.controlLeft]);
    await rightClickFace(tester, "r.jpg#0", key: "persons-context-defer");
    expect(selectionOf(tester), isEmpty);
    var state = editorState(tester);
    expect(state.placement["r.jpg#0"], startsWith(newGroupPrefix));
    expect(state.placement["q.jpg#0"], state.stored["q.jpg#0"],
        reason: "the ctrl-selected face was not part of the right click");

    // Now C and D (a range), right-clicked on D → named Bob.
    await clickFace(tester, "q.jpg#0");
    await clickFace(tester, "q.jpg#1", keys: [LogicalKeyboardKey.shiftLeft]);
    await rightClickFace(tester, "q.jpg#1", key: "persons-context-name");
    await pickPerson(tester, "p-bob");
    expect(selectionOf(tester), isEmpty);
    expect(groupFaces(tester, "${personGroupPrefix}p-bob"),
        {"q.jpg#0", "q.jpg#1"});

    await saveEditor(tester);
    var posted = tagFaces(requests);
    // Two CONFIRMED for Bob; the deferred E is a move between guesses and
    // posts nothing.
    expect(posted, hasLength(2));
    expect(posted.map((a) => a["image"]).toSet(), {"q.jpg"});
    expect(posted.map((a) => a["person"]).toSet(), {"p-bob"});
    expect(posted.map((a) => a["state"]).toSet(), {"CONFIRMED"});
  });

  testWidgets(
      "a modified click is never half a double click: ctrl-click then a plain "
      "click on the same face selects it and opens nothing", (tester) async {
    await pumpProbe(tester);
    await clickFace(tester, "q.jpg#0", keys: [LogicalKeyboardKey.controlLeft]);
    await clickFace(tester, "q.jpg#0");
    expect(find.byKey(const Key("persons-photo")), findsNothing);
    expect(selectionOf(tester), {"q.jpg#0"});

    // The other way round: a plain click and then a shift-click on the same
    // face is a range of one, not a double click either.
    await clickFace(tester, "q.jpg#1");
    await clickFace(tester, "q.jpg#1", keys: [LogicalKeyboardKey.shiftLeft]);
    expect(find.byKey(const Key("persons-photo")), findsNothing);
    expect(selectionOf(tester), {"q.jpg#1"});
  });

  testWidgets(
      "a double click opens the photograph and leaves the anchor where it was",
      (tester) async {
    await pumpProbe(tester);
    await withFakeImageHttp(() async {
      await tester.tap(faceTile("p.jpg#0"), kind: PointerDeviceKind.mouse);
      await tester.pump();
      await tester.tap(faceTile("p.jpg#0"), kind: PointerDeviceKind.mouse);
      await tester.pumpAndSettle();
    });
    expect(find.byKey(const Key("persons-photo")), findsOneWidget);
    expect(find.byKey(const Key("persons-photo-box")), findsOneWidget);
    expect(selectionOf(tester), {"p.jpg#0"});
    await withFakeImageHttp(() async {
      await tester.tap(find.text(testL10n.close));
      await tester.pumpAndSettle();
    });
    expect(find.byKey(const Key("persons-photo")), findsNothing);

    // The anchor is still A: a shift-click on D spans A..D.
    await clickFace(tester, "q.jpg#1", keys: [LogicalKeyboardKey.shiftLeft]);
    expect(selectionOf(tester), {"p.jpg#0", "p.jpg#1", "q.jpg#0", "q.jpg#1"});
  });
}
