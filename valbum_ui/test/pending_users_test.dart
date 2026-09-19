/// An invitation is a pending user, and the users list says so (issue #89).
///
/// The list the administrator reads is the whole story of who is here: the
/// people who arrived, and the seats that are still waiting for somebody. A
/// pending seat is named by the inviter's own memento, and the one thing to do
/// with it is to take the invitation back.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/settings.dart';

import 'devices_test.dart'
    show authOfUser, json, pumpSettings, serverUrl, signedIn, tapVisible;

/// One arrived user, one pending seat with a memento, one without.
const String usersWithPending = '{"users": ['
    '{"name": "haui", "role": "admin", "space": "", "created": '
    '"2025-01-01T09:00:00Z", "devices": 2}, '
    '{"name": "bob", "role": "edit", "clearance": "all", "mayShare": true, '
    '"space": "", "created": "2026-02-03T09:00:00Z", "devices": 1, '
    '"recipient": "Uncle Bob", "invitedBy": "haui", "invitation": "i0"}, '
    '{"name": "", "role": "view", "clearance": "public", "space": "", '
    '"created": "2026-09-13T10:00:00Z", "devices": 0, "pending": true, '
    '"recipient": "Grandma", "invitedBy": "haui", "invitation": "i1"}, '
    '{"name": "", "role": "view", "clearance": "public", "space": "", '
    '"created": "2026-09-14T10:00:00Z", "devices": 0, "pending": true, '
    '"invitedBy": "haui", "invitation": "i2"}]}';

/// The list after the pending seat "i1" was withdrawn.
const String usersAfterWithdrawal = '{"users": ['
    '{"name": "haui", "role": "admin", "space": "", "created": '
    '"2025-01-01T09:00:00Z", "devices": 2}, '
    '{"name": "bob", "role": "edit", "clearance": "all", "mayShare": true, '
    '"space": "", "created": "2026-02-03T09:00:00Z", "devices": 1, '
    '"recipient": "Uncle Bob", "invitedBy": "haui", "invitation": "i0"}, '
    '{"name": "", "role": "view", "clearance": "public", "space": "", '
    '"created": "2026-09-14T10:00:00Z", "devices": 0, "pending": true, '
    '"invitedBy": "haui", "invitation": "i2"}]}';

/// A server the administrator is signed in at, recording what it was asked.
http.Response Function(http.Request) serverFor(List<http.Request> seen) {
  var withdrawn = false;
  return (request) {
    seen.add(request);
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
    if (query["action"] == "uninvite") {
      withdrawn = true;
      return json('{"invitations": []}');
    }
    if (query["type"] == "users") {
      return json(withdrawn ? usersAfterWithdrawal : usersWithPending);
    }
    return json('{"users": []}');
  };
}

Future<ServerSettings> adminSettings() =>
    signedIn(InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"));

void main() {
  testWidgets('a pending seat is listed with the memento it was made for',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor([])(request)),
    );

    expect(find.byKey(const Key("user-pending-i1")), findsOneWidget);
    expect(find.text("Invited for Grandma (pending)"), findsOneWidget);
    // An inviter who wrote no memento gets the plain fact.
    expect(find.byKey(const Key("user-pending-i2")), findsOneWidget);
    expect(find.text("Invited (pending)"), findsOneWidget);
    // What they would become, and who asked them: no library and no devices,
    // because they have neither until somebody redeems the invitation.
    expect(
      tester
          .widget<Text>(find.byKey(const Key("user-permission-pending-i1")))
          .data,
      "may look — sees the public images — no links — invited by haui — "
      "since Sep 13, 2026",
    );
    // The memento stays beside the name once the person has chosen one.
    expect(
      tester.widget<Text>(find.byKey(const Key("user-permission-bob"))).data,
      contains("invited for Uncle Bob"),
    );
  });

  testWidgets('a pending seat offers nothing but withdrawing', (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor([])(request)),
    );

    expect(find.byKey(const Key("user-withdraw-pending-i1")), findsOneWidget);
    // Nobody changes the permission of a seat nobody sits in, nobody makes a
    // recovery code for a user with no name, and removing them is withdrawing.
    expect(find.byKey(const Key("user-edit-")), findsNothing);
    expect(find.byKey(const Key("user-remove-")), findsNothing);
    expect(find.byKey(const Key("user-recovery-")), findsNothing);
    // An arrived user is unchanged.
    expect(find.byKey(const Key("user-withdraw-pending-i0")), findsNothing);
    expect(find.byKey(const Key("user-edit-bob")), findsOneWidget);
    expect(find.byKey(const Key("user-remove-bob")), findsOneWidget);
  });

  testWidgets('withdrawing a pending seat takes it out of the list',
      (tester) async {
    var seen = <http.Request>[];
    var handler = serverFor(seen);
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => handler(request)),
    );

    await tapVisible(tester, find.byKey(const Key("user-withdraw-pending-i1")));
    await tester.pumpAndSettle();
    // What it does is said before it is done.
    expect(find.byKey(const Key("withdraw-user-confirm")), findsOneWidget);
    await tapVisible(tester, find.byKey(const Key("withdraw-user-confirmed")));
    await tester.pumpAndSettle();

    var uninvite = seen.lastWhere(
      (request) => request.url.queryParameters["action"] == "uninvite",
    );
    expect(uninvite.body, contains('"id":"i1"'));
    expect(find.byKey(const Key("user-pending-i1")), findsNothing);
    expect(find.byKey(const Key("user-pending-i2")), findsOneWidget);
  });
}
