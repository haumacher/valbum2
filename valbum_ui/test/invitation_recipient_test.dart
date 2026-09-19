/// The inviter's memento, see issue #89: "whom did I send this to?"
///
/// An optional note the inviter makes to themselves when they hand out an
/// invitation. It is stored on the pending user the invitation creates, it is
/// what names that seat in the users list until the person chooses a name —
/// and it is never shown to the person who opens the link.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/invitation.dart';
import 'package:valbum_ui/settings.dart';

import 'devices_test.dart'
    show authOfUser, json, pumpSettings, serverUrl, signedIn, tapVisible;
import 'invitations_test.dart' show invitationEntry, invitationList;

/// A server an administrator is signed in at, recording what it was asked.
http.Response Function(http.Request) serverFor(
  List<http.Request> seen, {
  String invitations = '{"invitations": []}',
}) =>
    (request) {
      seen.add(request);
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser("admin", name: "haui"));
      }
      if (query["type"] == "devices") {
        return json('{"devices": []}');
      }
      if (query["type"] == "users") {
        return json('{"users": []}');
      }
      if (query["type"] == "invitations") {
        return json(invitations);
      }
      if (query["action"] == "invite") {
        return json('{"invitation": '
            '${invitationEntry("i9", note: "Come and look")}, '
            '"token": "tok", "url": "/valbum/i/tok/"}');
      }
      return json('{"users": []}');
    };

Future<ServerSettings> adminSettings() =>
    signedIn(InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"));

void main() {
  testWidgets('the dialog asks whom the invitation is for', (tester) async {
    var seen = <http.Request>[];
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor(seen)(request)),
    );

    await tapVisible(tester, find.byKey(inviteButtonKey));
    await tester.pumpAndSettle();
    expect(find.byKey(invitationRecipientFieldKey), findsOneWidget);
    expect(
      find.text("A note to yourself: whom this invitation is for. Optional."),
      findsOneWidget,
    );

    await tester.enterText(find.byKey(invitationRecipientFieldKey), "Grandma");
    await tester.enterText(
        find.byKey(const Key("invite-note")), "Come and look");
    await tapVisible(tester, find.byKey(const Key("invite-create")));
    await tester.pumpAndSettle();

    var invite = seen.lastWhere(
      (request) => request.url.queryParameters["action"] == "invite",
    );
    expect(invite.body, contains('"recipient":"Grandma"'));
    // The note is the other thing, and it is still its own field: one is read
    // by the inviter, the other by the person who opens the link.
    expect(invite.body, contains('"note":"Come and look"'));
    expect(find.byKey(const Key("invite-url")), findsOneWidget);
  });

  testWidgets('issuing one puts the seat into the users list', (tester) async {
    // An invitation is a pending user (issue #89): the list of who is here
    // has to notice, or the old model shows through.
    var issued = false;
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async {
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOfUser("admin", name: "haui"));
        }
        if (query["type"] == "devices") {
          return json('{"devices": []}');
        }
        if (query["type"] == "invitations") {
          return json('{"invitations": []}');
        }
        if (query["action"] == "invite") {
          issued = true;
          return json('{"invitation": ${invitationEntry("i9")}, '
              '"token": "tok", "url": "/valbum/i/tok/"}');
        }
        if (query["type"] == "users") {
          return json(issued
              ? '{"users": [{"name": "", "role": "view", "space": "", '
                  '"created": "2026-09-13T10:00:00Z", "devices": 0, '
                  '"pending": true, "recipient": "Grandma", '
                  '"invitedBy": "haui", "invitation": "i9"}]}'
              : '{"users": []}');
        }
        return json('{"users": []}');
      }),
    );

    expect(find.textContaining("Invited for"), findsNothing);

    await tapVisible(tester, find.byKey(inviteButtonKey));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(invitationRecipientFieldKey), "Grandma");
    await tapVisible(tester, find.byKey(const Key("invite-create")));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(const Key("invite-close")));
    await tester.pumpAndSettle();

    expect(find.text("Invited for Grandma (pending)"), findsOneWidget);
    expect(find.byKey(const Key("user-withdraw-pending-i9")), findsOneWidget);
  });

  testWidgets('the open invitations say whom they were made for',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor(
            [],
            invitations: invitationList([
              invitationEntry("i1", note: "Come and look", invitedBy: "haui"),
            ]).replaceFirst('"note"', '"recipient": "Grandma", "note"'),
          )(request)),
    );

    expect(find.byKey(const Key("invitation-i1")), findsOneWidget);
    expect(find.textContaining("for Grandma"), findsOneWidget);
    expect(find.textContaining("Come and look"), findsOneWidget);
  });
}
