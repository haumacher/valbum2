/// Tests of the recovery code an administrator makes for somebody who lost
/// every device they had (issue #89).
///
/// The same code as every other — one device, one user, ten minutes, once —
/// offered beside the user in the users list and shown in the same dialog the
/// own device code is shown in. A user who never signed in is not offered one:
/// they have no name to make one for, and the space's seat code is what signs
/// them in.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';

import 'devices_test.dart'
    show authOfUser, json, pumpSettings, serverUrl, signedIn;
import 'util/l10n.dart';

/// The users of the server the tests talk to, one of them never signed in.
const String someUsers = '{"users": ['
    '{"name": "haui", "role": "admin", "space": "", "created": '
    '"2025-01-01T09:00:00Z", "devices": 2}, '
    '{"name": "carol", "role": "edit", "clearance": "all", "mayShare": true, '
    '"space": "", "created": "2026-03-04T09:00:00Z", "devices": 0}, '
    '{"name": "", "role": "admin", "clearance": "all", "space": "", '
    '"created": "2026-03-04T09:00:00Z", "devices": 0}]}';

/// What the server answers a device code request.
const String issued =
    '{"code": "WXYZ-9876", "expires": "2999-01-01T00:00:00Z"}';

/// A handler answering as a server whose caller has the given role.
http.Response Function(http.Request) serverFor(String role) => (request) {
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
        return json(someUsers);
      }
      if (query["action"] == "device-code") {
        return json(issued);
      }
      return json('{"users": []}');
    };

/// The settings of a signed-in administrator.
Future<ServerSettings> adminSettings() =>
    signedIn(InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"));

void main() {
  testWidgets('an administrator is offered one beside every named user',
      (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async => serverFor("admin")(request)),
    );

    expect(find.byKey(const Key("user-recovery-haui")), findsOneWidget);
    expect(find.byKey(const Key("user-recovery-carol")), findsOneWidget);
    // The seat of a space nobody signed into: there is nobody to make a code
    // for, and the seat code of the space is what signs them in.
    expect(find.byKey(const Key("user-recovery-")), findsNothing);
  });

  testWidgets('it asks the server for the named user and shows the code',
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

    var button = find.byKey(const Key("user-recovery-carol"));
    await tester.ensureVisible(button);
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    var asked = requests
        .where((r) => r.url.queryParameters["action"] == "device-code")
        .single;
    expect(asked.body, contains('"userName":"carol"'));

    expect(find.byKey(deviceCodeDialogKey), findsOneWidget);
    expect(find.text("Recovery code for carol"), findsOneWidget);
    expect(
      tester.widget<SelectableText>(find.byKey(deviceCodeKey)).data,
      "WXYZ-9876",
    );
    // Whose code it is, said where it is shown; never "signs that device in
    // as you", which is what the own device code says.
    expect(find.text(recoveryCodeAdvice(testL10n, "carol")), findsOneWidget);
  });

  testWidgets('a refusal is shown in the server\'s own words', (tester) async {
    await pumpSettings(
      tester,
      await adminSettings(),
      MockClient((request) async {
        if (request.url.queryParameters["action"] == "device-code") {
          return http.Response(
            '["ErrorInfo",{"message":"Only an administrator of this space may '
            'create a sign-in code for somebody else."}]',
            403,
          );
        }
        return serverFor("admin")(request);
      }),
    );

    var button = find.byKey(const Key("user-recovery-carol"));
    await tester.ensureVisible(button);
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(find.byKey(deviceCodeErrorKey), findsOneWidget);
    expect(
      find.text("Only an administrator of this space may create a sign-in "
          "code for somebody else."),
      findsOneWidget,
    );
  });

  testWidgets('somebody who is no administrator is offered nothing here',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(
        InMemorySettingsStore(serverUrl, "member-token", "Desk", "bob"),
      ),
      MockClient((request) async => serverFor("edit")(request)),
    );

    // The users list is the administrator's, and the action lives in it.
    expect(find.byKey(usersSectionKey), findsNothing);
    expect(find.byKey(const Key("user-recovery-carol")), findsNothing);
  });
}
