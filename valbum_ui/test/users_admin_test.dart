/// Tests of the users section of the server settings (issue #55): who is on
/// this server, and the administrator making a guest a member.
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

/// The users of the server the tests talk to.
const String someUsers = '{"users": ['
    '{"name": "haui", "role": "admin", "space": "", "created": '
    '"2025-01-01T09:00:00Z", "devices": 2}, '
    '{"name": "bob", "role": "member", "space": "bob", "created": '
    '"2026-02-03T09:00:00Z", "devices": 1}, '
    '{"name": "carol", "role": "guest", "space": "", "created": '
    '"2026-03-04T09:00:00Z", "devices": 0}]}';

/// A handler answering as a server whose caller has the given role.
http.Response Function(http.Request) serverFor(
  String role, {
  http.Response Function(http.Request)? users,
  http.Response Function(http.Request)? promote,
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
      if (query["action"] == "promote") {
        return (promote ??
            (_) => json('{"name": "carol", "role": "member", '
                '"space": "carol", "created": "2026-03-04T09:00:00Z", '
                '"devices": 0}'))(request);
      }
      return json('{"groups": []}');
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
    // The role, the library and the devices of every user.
    expect(
      find.textContaining("member — library: bob — 1 device"),
      findsOneWidget,
    );
    expect(
      find.textContaining("admin — library: the whole library — 2 devices"),
      findsOneWidget,
    );
    expect(
      find.textContaining("guest — no library of their own — 0 devices"),
      findsOneWidget,
    );
  });

  testWidgets('only a guest is offered the way to become a member',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor("admin")(request)),
    );

    expect(find.byKey(const Key("user-promote-carol")), findsOneWidget);
    expect(find.byKey(const Key("user-promote-bob")), findsNothing);
    expect(find.byKey(const Key("user-promote-haui")), findsNothing);
  });

  testWidgets('making a guest a member asks first, then shows the new role',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async {
        requests.add(request);
        return serverFor("admin")(request);
      }),
    );

    await tapVisible(tester, find.byKey(const Key("user-promote-carol")));
    expect(find.byKey(const Key("promote-confirm")), findsOneWidget);
    // What it does is said before it is done.
    expect(find.textContaining("folder of their own"), findsOneWidget);
    await tester.tap(find.byKey(const Key("promote-confirmed")));
    await tester.pumpAndSettle();

    var promote = requests.last;
    expect(promote.method, "POST");
    expect(promote.url.queryParameters["action"], "promote");
    expect(promote.body, contains('"name":"carol"'));

    expect(find.byKey(const Key("user-promote-carol")), findsNothing);
    expect(
      find.textContaining("member — library: carol"),
      findsOneWidget,
    );
  });

  testWidgets('a declined confirmation sends nothing', (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async {
        requests.add(request);
        return serverFor("admin")(request);
      }),
    );

    await tapVisible(tester, find.byKey(const Key("user-promote-carol")));
    await tester.tap(find.text("Cancel"));
    await tester.pumpAndSettle();

    expect(
      requests.any((r) => r.url.queryParameters["action"] == "promote"),
      isFalse,
    );
    expect(find.byKey(const Key("user-promote-carol")), findsOneWidget);
  });

  testWidgets('a refused promotion shows the server\'s own sentence',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor(
            "admin",
            promote: (_) =>
                refusal(409, "There is already a folder named 'carol'."),
          )(request)),
    );

    await tapVisible(tester, find.byKey(const Key("user-promote-carol")));
    await tester.tap(find.byKey(const Key("promote-confirmed")));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key("settings.users.error")), findsOneWidget);
    expect(
      find.text("There is already a folder named 'carol'."),
      findsOneWidget,
    );
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
