/// After Save the face editor is clean: the way back asks nothing, whatever
/// the buffer still remembers about what it wrote (issue #151).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_actions_test.dart' show chooseInSelectionMenu, saveEditor;
import 'persons_selection_test.dart' show albumOf, imageOf;
import 'persons_view_test.dart'
    show authOf, editorClient, faceOf, json, pumpEditor, selectFace;
import 'util/fake_image_http.dart';

/// Before: one face confirmed as Anna, two unknown faces of one cluster.
final String before = albumOf([
  imageOf("u.jpg", [faceOf(0, cluster: "c3", person: "p-anna", state: "CONFIRMED")]),
  imageOf("t.jpg", [faceOf(0, cluster: "c2"), faceOf(1, cluster: "c2", y: 0.5)]),
]);

/// After the take-back was written: the face is a plain detection again.
final String after = albumOf([
  imageOf("u.jpg", [faceOf(0, cluster: "c3")]),
  imageOf("t.jpg", [faceOf(0, cluster: "c2"), faceOf(1, cluster: "c2", y: 0.5)]),
]);

Future<void> goBack(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(const Key("persons-up")));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets("a saved take-back leaves nothing to ask about on the way back",
      (tester) async {
    var requests = <http.Request>[];
    var current = before;
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: before,
        albumNow: () => current,
        post: (request) {
          current = after;
          return json(after);
        },
      ),
    );
    await selectFace(tester, "u.jpg#0");
    await chooseInSelectionMenu(tester, "persons-forget");
    await saveEditor(tester);
    expect(find.byKey(const Key("persons-refusal")), findsNothing);

    await goBack(tester);
    expect(find.byKey(const Key("persons-leave-dialog")), findsNothing,
        reason: "everything was written; there is nothing to save");
    expect(find.byType(PersonsContent), findsNothing);
  });

  testWidgets("a deferred group is no unsaved change: Save writes nothing "
      "and the way back asks nothing", (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(requests, auth: authOf(), album: before,
          post: (request) => json(before)),
    );
    await selectFace(tester, "t.jpg#0");
    await chooseInSelectionMenu(tester, "persons-defer");
    await saveEditor(tester);
    expect(requests.where((r) => r.method == "POST"), isEmpty,
        reason: "a group of the server's own guesses is nothing the album stores");

    await goBack(tester);
    expect(find.byKey(const Key("persons-leave-dialog")), findsNothing);
  });
}
