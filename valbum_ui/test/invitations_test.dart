/// Tests of the open-invitations section of the server settings (issue #55):
/// what is still pending, withdrawing one, and the list picking up what was
/// just issued.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';

import 'devices_test.dart'
    show
        authOfUser,
        json,
        pumpSettings,
        refusal,
        serverUrl,
        signedIn,
        tapVisible;

/// An invitation as the server lists it.
String invitationEntry(
  String id, {
  String role = "member",
  String note = "",
  String expires = "2099-12-24T17:00:00Z",
  String invitedBy = "bob",
  String used = "",
  String usedBy = "",
  String revoked = "",
}) =>
    '{"id": "$id", "role": "$role", "note": "$note", "expires": "$expires", '
    '"invitedBy": "$invitedBy", "created": "2026-09-13T10:00:00Z", '
    '"used": "$used", "usedBy": "$usedBy", "revoked": "$revoked"}';

/// A list of invitations, as the server answers it.
String invitationList(List<String> entries) =>
    '{"invitations": [${entries.join(", ")}]}';

/// The list the tests start from: one open, one accepted, one withdrawn.
String mixedInvitations() => invitationList([
      invitationEntry("i1", note: "Aunt Mary"),
      invitationEntry("i2", role: "guest", expires: "", invitedBy: ""),
      invitationEntry("i3", used: "2026-09-14T08:00:00Z", usedBy: "dora"),
      invitationEntry("i4", revoked: "2026-09-14T09:00:00Z"),
    ]);

/// A handler answering as a server the given caller is signed in at.
http.Response Function(http.Request) serverFor({
  String role = "member",
  http.Response Function(http.Request)? invitations,
  http.Response Function(http.Request)? uninvite,
  http.Response Function(http.Request)? invite,
}) =>
    (request) {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser(role, name: "bob"));
      }
      if (query["type"] == "devices") {
        return json('{"devices": []}');
      }
      if (query["type"] == "users") {
        return json('{"users": []}');
      }
      if (query["type"] == "invitations") {
        return (invitations ?? (_) => json(mixedInvitations()))(request);
      }
      if (query["action"] == "uninvite") {
        return (uninvite ??
            (_) => json(invitationList([
                  invitationEntry("i1",
                      note: "Aunt Mary", revoked: "2026-09-15T08:00:00Z"),
                  invitationEntry("i2", role: "guest", expires: ""),
                ])))(request);
      }
      if (query["action"] == "invite") {
        return (invite ??
            (_) => json('{"invitation": ${invitationEntry("i9")}, '
                '"token": "tok", "url": "/valbum/i/tok/"}'))(request);
      }
      return json('{"groups": []}');
    };

/// The settings of a signed-in member.
Future<ServerSettings> memberSettings() =>
    signedIn(InMemorySettingsStore(serverUrl, "member-token", "Desk", "bob"));

void main() {
  testWidgets('lists what is still waiting to be accepted', (tester) async {
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async => serverFor()(request)),
    );

    expect(find.byKey(invitationsSectionKey), findsOneWidget);
    expect(find.byKey(const Key("invitation-i1")), findsOneWidget);
    expect(find.byKey(const Key("invitation-i2")), findsOneWidget);
    // An accepted and a withdrawn invitation are not pending.
    expect(find.byKey(const Key("invitation-i3")), findsNothing);
    expect(find.byKey(const Key("invitation-i4")), findsNothing);

    expect(find.text("As a member by bob"), findsOneWidget);
    expect(find.textContaining("Aunt Mary"), findsOneWidget);
    expect(find.textContaining("expires Dec 24, 2099"), findsOneWidget);
    // An invitation without an instant lives forever, and says so.
    expect(find.textContaining("expires: never"), findsOneWidget);
    expect(find.text("As a guest"), findsOneWidget);
  });

  testWidgets('an expired invitation is listed as expired', (tester) async {
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async => serverFor(
            invitations: (_) => json(invitationList([
              invitationEntry("i1", expires: "2020-01-02T10:00:00Z"),
            ])),
          )(request)),
    );

    expect(find.textContaining("expired on Jan 2, 2020"), findsOneWidget);
  });

  testWidgets('withdrawing one asks, names its id and shows what remains',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async {
        requests.add(request);
        return serverFor()(request);
      }),
    );

    await tapVisible(tester, find.byKey(const Key("invitation-revoke-i1")));
    expect(find.byKey(const Key("uninvite-confirm")), findsOneWidget);
    await tester.tap(find.byKey(const Key("uninvite-confirmed")));
    await tester.pumpAndSettle();

    var uninvite = requests.last;
    expect(uninvite.method, "POST");
    expect(uninvite.url.queryParameters["action"], "uninvite");
    expect(uninvite.body, contains('"id":"i1"'));

    // The withdrawn one is no longer pending; the other one stays.
    expect(find.byKey(const Key("invitation-i1")), findsNothing);
    expect(find.byKey(const Key("invitation-i2")), findsOneWidget);
  });

  testWidgets('a refused list shows why, instead of nothing', (tester) async {
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async => serverFor(
            invitations: (_) => refusal(403, "Not yours to see."),
          )(request)),
    );

    expect(find.byKey(const Key("settings.invitations.error")), findsOneWidget);
    expect(find.text("Not yours to see."), findsOneWidget);
  });

  testWidgets('an empty list says so', (tester) async {
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async => serverFor(
            invitations: (_) => json(invitationList(const [])),
          )(request)),
    );

    expect(find.byKey(const Key("settings.invitations.empty")), findsOneWidget);
  });

  testWidgets('the list reads itself again after an invitation was issued',
      (tester) async {
    var listings = 0;
    await pumpSettings(
      tester,
      await memberSettings(),
      MockClient((request) async => serverFor(
            invitations: (_) {
              listings++;
              return json(listings == 1
                  ? invitationList(const [])
                  : invitationList([invitationEntry("i9", note: "New one")]));
            },
          )(request)),
    );

    expect(find.byKey(const Key("settings.invitations.empty")), findsOneWidget);

    await tapVisible(tester, find.byKey(inviteButtonKey));
    await tester.tap(find.byKey(const Key("invite-create")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("invite-close")));
    await tester.pumpAndSettle();

    expect(listings, 2);
    expect(find.byKey(const Key("invitation-i9")), findsOneWidget);
    expect(find.textContaining("New one"), findsOneWidget);
  });
}
