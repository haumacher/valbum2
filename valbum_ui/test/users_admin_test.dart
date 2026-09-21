/// Tests of the users section of the server settings (issues #55 and #85):
/// who is on this server, and what each of them may do and see.
///
/// Changing a permission is the administrator's business and waits for the
/// server's `?action=set-permission`; what this section does is *say* it, in
/// the same words the settings tell the caller about themselves.
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
        signedIn;

/// The users of the server the tests talk to.
const String someUsers = '{"users": ['
    '{"name": "haui", "role": "admin", "space": "", "created": '
    '"2025-01-01T09:00:00Z", "devices": 2}, '
    '{"name": "bob", "role": "edit", "clearance": "all", "mayShare": true, '
    '"space": "bob", "created": "2026-02-03T09:00:00Z", "devices": 1}, '
    '{"name": "carol", "role": "view", "clearance": "public", '
    '"space": "carol", "created": "2026-03-04T09:00:00Z", "devices": 0}]}';

/// A handler answering as a server whose caller has the given role.
http.Response Function(http.Request) serverFor(
  String role, {
  http.Response Function(http.Request)? users,
}) =>
    (request) {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser(role, name: role == "admin" ? "haui" : "bob"));
      }
      if (query["type"] == "devices") {
        return json('{"devices": []}');
      }
      if (query["type"] == "invitations") {
        return json('{"invitations": []}');
      }
      if (query["type"] == "users") {
        return (users ?? (_) => json(someUsers))(request);
      }
      return json('{"users": []}');
    };

/// The settings of a signed-in administrator.
Future<ServerSettings> adminSettings() =>
    signedIn(InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"));

void main() {
  testWidgets('the administrator sees who is here, and what they are',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor("admin")(request)),
    );

    expect(find.byKey(usersSectionKey), findsOneWidget);
    expect(find.byKey(const Key("user-haui")), findsOneWidget);
    expect(find.byKey(const Key("user-bob")), findsOneWidget);
    expect(find.byKey(const Key("user-carol")), findsOneWidget);
    // What each of them may do and see, in words (issue #85).
    expect(
      tester.widget<Text>(find.byKey(const Key("user-permission-bob"))).data,
      "may edit the albums — sees all images — may share links — "
      "library: bob — 1 device — since Feb 3, 2026",
    );
    expect(
      tester.widget<Text>(find.byKey(const Key("user-permission-carol"))).data,
      "may look — sees the public images — no links — library: carol — "
      "0 devices — since Mar 4, 2026",
    );
    expect(
      tester.widget<Text>(find.byKey(const Key("user-permission-haui"))).data,
      startsWith("manages this server — sees all images — may share links"),
    );
  });

  testWidgets('says which person of the register a member is', (tester) async {
    // Issue #128: the link is stored once and answered from both ends, so the
    // users list needs no second fetch to say it — and it says it only, the
    // editing being where the people are.
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor(
            "admin",
            users: (_) => json('{"users": ['
                '{"name": "haui", "role": "admin", "devices": 1, '
                '"person": "p-anna", "personName": "Anna"}, '
                '{"name": "bob", "role": "edit", "devices": 1}]}'),
          )(request)),
    );

    expect(find.byKey(const Key("user-person")), findsOneWidget);
    expect(find.text("Appears in photos as Anna"), findsOneWidget);
  });

  testWidgets('a refused list shows why, instead of nothing', (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor(
            "admin",
            users: (_) => refusal(403, "Only the administrator may."),
          )(request)),
    );

    expect(find.text("Only the administrator may."), findsOneWidget);
  });

  testWidgets('a member is shown no user list at all', (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await signedIn(
        InMemorySettingsStore(serverUrl, "member-token", "Desk", "bob"),
      ),
      MockClient((request) async {
        requests.add(request);
        return serverFor("member")(request);
      }),
    );

    expect(find.byKey(usersSectionKey), findsNothing);
    expect(
      requests.any((r) => r.url.queryParameters["type"] == "users"),
      isFalse,
    );
  });
}
