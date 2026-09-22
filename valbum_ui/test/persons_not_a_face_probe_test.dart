/// Probe of issue #144 composed with #139 and #138: a shift range marked
/// "Not a face", one of them forgotten again from the buffer, and a face the
/// server already stored as no face.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_actions_test.dart'
    show chooseInSelectionMenu, rightClickFace, saveEditor;
import 'persons_selection_test.dart'
    show albumOf, clickFace, editorState, imageOf, selectionOf;
import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, faceTile, header, json, posted, pumpEditor;
import 'util/fake_image_http.dart';

final String probeAlbum = albumOf([
  imageOf("q.jpg", [
    faceOf(0, cluster: "c1"),
    faceOf(1, cluster: "c1", y: 0.5),
  ]),
  imageOf("r.jpg", [
    faceOf(0, cluster: "c2"),
  ]),
  imageOf("n.jpg", [
    faceOf(0, cluster: "", state: "NOT_A_FACE"),
  ]),
]);

Set<String> groupFaces(WidgetTester tester, String key) => {
      for (var group in editorState(tester).groups)
        if (group.key == key)
          for (var face in group.faces) face.key
    };

void main() {
  testWidgets(
      "a range marked as no face, one forgotten back, and a stored no-face "
      "marked again posts exactly the new decisions", (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(requests, auth: authOf(), album: probeAlbum,
          post: (request) => json(probeAlbum)),
    );

    // C, D, E by a shift range → "Not a face".
    await clickFace(tester, "q.jpg#0");
    await clickFace(tester, "r.jpg#0", keys: [LogicalKeyboardKey.shiftLeft]);
    expect(selectionOf(tester), {"q.jpg#0", "q.jpg#1", "r.jpg#0"});
    await chooseInSelectionMenu(tester, "persons-not-a-face");
    expect(selectionOf(tester), isEmpty);
    expect(groupFaces(tester, notAFaceGroup),
        {"q.jpg#0", "q.jpg#1", "r.jpg#0", "n.jpg#0"});

    // Unfold and take E back from the buffer: it falls into its own cluster.
    await withFakeImageHttp(() async {
      await tester.tap(header(notAFaceGroup));
      await tester.pumpAndSettle();
      await tester.ensureVisible(faceTile("r.jpg#0"));
      await tester.pumpAndSettle();
    });
    await rightClickFace(tester, "r.jpg#0", key: "persons-context-forget");
    expect(groupFaces(tester, notAFaceGroup), {"q.jpg#0", "q.jpg#1", "n.jpg#0"});
    expect(groupFaces(tester, "${clusterGroupPrefix}c2"), {"r.jpg#0"});

    // N is already stored as no face: marking it again is no statement.
    await withFakeImageHttp(() async {
      await tester.ensureVisible(faceTile("n.jpg#0"));
      await tester.pumpAndSettle();
    });
    await rightClickFace(tester, "n.jpg#0", key: "persons-context-not-a-face");

    await saveEditor(tester);
    var assignments = posted(requests, "tag-faces").single["faces"] as List;
    expect(assignments.map((a) => "${a["image"]}#${a["face"]}").toSet(),
        {"q.jpg#0", "q.jpg#1"});
    expect(assignments.map((a) => a["state"]).toSet(), {"NOT_A_FACE"});
  });
}
