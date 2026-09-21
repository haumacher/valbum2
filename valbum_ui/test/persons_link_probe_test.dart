/// Review probe of "This is me" (issue #128): a member who already is somebody
/// claims nobody else, and takes back only their own link.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'persons_view_test.dart' hide main;

void main() {
  testWidgets('a member who is Anna is offered no second self and unlinks only Anna',
      (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: albumOf(images: [
          imageOf("b.jpg", [faceOf(0, cluster: "c2", person: "p-anna", state: "CONFIRMED")]),
          imageOf("c.jpg", [faceOf(0, cluster: "c3", person: "p-bob", state: "CONFIRMED")]),
        ]),
        people: () => '{"people": ['
            '{"id": "p-anna", "name": "Anna", "user": "carol"},'
            '{"id": "p-bob", "name": "Bob"}]}',
      ),
    );

    await openPersonMenu(tester, "p-anna");
    expect(find.byKey(const Key("persons-unlink")), findsOneWidget,
        reason: "Carol may take her own link back.");
    expect(find.byKey(const Key("persons-link-me")), findsNothing);
    expect(find.byKey(const Key("persons-link-member")), findsNothing,
        reason: "Linking somebody else is the administrator's.");
    await tester.tapAt(const Offset(5, 5));
    await tester.pumpAndSettle();

    await openPersonMenu(tester, "p-bob");
    expect(find.byKey(const Key("persons-link-me")), findsNothing,
        reason: "Carol is Anna already; she cannot be Bob as well.");
    expect(find.byKey(const Key("persons-unlink")), findsNothing,
        reason: "Bob is nobody's, and not hers to unlink.");
    expect(posted(requests, "link-person"), isEmpty);
  });
}
