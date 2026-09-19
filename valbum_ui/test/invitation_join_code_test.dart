/// Joining by an invitation is the ordinary pairing (issue #89, step two).
///
/// An invitation is a pending user carrying a code: the user was created when
/// the invitation was issued, and the link carries the single-use code that
/// adds their first device. So the welcome screen sends what every other
/// sign-in sends — a `deviceCode` and the name the person chooses — and the
/// retired `invitation` field of issue #52 is not filled at all.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/invitation.dart';

import 'invitation_test.dart'
    show
        authOfInvitation,
        json,
        liveInvitation,
        pumpInvitation,
        refusal,
        tapVisible;

void main() {
  testWidgets('the welcome screen joins with the link as the code',
      (tester) async {
    var session = await pumpInvitation(tester, liveInvitation());

    await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(const Key("invitation-join")));
    await tester.pumpAndSettle();

    var pair = session.requests.last;
    expect(pair.url.queryParameters["action"], "pair");
    expect(pair.body, contains('"deviceCode":"inv-1"'));
    // The field of issue #52 is an alias the server still reads; the app has
    // stopped filling it.
    expect(pair.body, contains('"invitation":""'));
    expect(pair.body, contains('"userName":"carol"'));
    // The sign-in carries no bearer: a device is how one *gets* a token.
    expect(pair.headers["Authorization"], isNull);
  });

  testWidgets('the memento is the inviter\'s and is not shown to the invitee',
      (tester) async {
    // The server answers it — a client that wanted to greet by name could —
    // but this one does not put somebody's note about them on their screen.
    await pumpInvitation(tester, (request) {
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfInvitation()
            .replaceFirst('"note"', '"recipient": "Grandma", "note"'));
      }
      return http.Response("No such resource", 404);
    });

    expect(find.byKey(const Key("invitation-welcome")), findsOneWidget);
    expect(find.textContaining("Grandma"), findsNothing);
  });

  testWidgets('a name that is taken keeps the form and the invitation',
      (tester) async {
    var session = await pumpInvitation(
      tester,
      liveInvitation(
        pairAnswer: refusal(409, "There is somebody called 'carol' here."),
      ),
    );

    await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(const Key("invitation-join")));
    await tester.pumpAndSettle();

    expect(find.text("There is somebody called 'carol' here."), findsOneWidget);
    expect(find.byKey(invitationUserFieldKey), findsOneWidget);
    expect(session.requests.last.body, contains('"deviceCode":"inv-1"'));
  });
}
