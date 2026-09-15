/// Tests of the administrator's half of the permission model (issue #85,
/// fourth slice, against the server of issue #83).
///
/// A user's permission is the same in every album of the space, so the users
/// section *is* the sharing of the space: who is in it, what each of them may
/// do and see, and who is no longer in it at all. Both writes answer the whole
/// list, so the section shows what the server holds rather than what was asked
/// for — and a refusal is the server's own sentence, never a guess made here.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// A JSON answer.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal carrying the server's own sentence.
http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status: status);

/// One user as `?type=users` lists them.
String userEntry(
  String name, {
  String role = "view",
  String clearance = "nonPrivate",
  bool mayShare = false,
  String space = "",
  int devices = 1,
}) =>
    '{"name": "$name", "role": "$role", "clearance": "$clearance", '
    '"mayShare": $mayShare, "space": "${space.isEmpty ? name : space}", '
    '"created": "2026-02-03T09:00:00Z", "devices": $devices}';

/// The users the tests start from: the administrator and two others.
String someUsers() => '{"users": ['
    '${userEntry("haui", role: "admin", clearance: "all", mayShare: true, space: " ")}, '
    '${userEntry("bob", role: "edit", clearance: "all", mayShare: true)}, '
    '${userEntry("carol")}]}';

/// The list after carol became a contributor.
String carolChanged() => '{"users": ['
    '${userEntry("haui", role: "admin", clearance: "all", mayShare: true, space: " ")}, '
    '${userEntry("bob", role: "edit", clearance: "all", mayShare: true)}, '
    '${userEntry("carol", role: "contribute", clearance: "all", mayShare: true)}]}';

/// The list after carol was removed.
String carolGone() => '{"users": ['
    '${userEntry("haui", role: "admin", clearance: "all", mayShare: true, space: " ")}, '
    '${userEntry("bob", role: "edit", clearance: "all", mayShare: true)}]}';

/// The answer of `?type=auth` for the administrator.
const String adminAuth = '{"mode": "writes", "deviceName": "Desk", '
    '"writeAllowed": true, "userName": "haui", "role": "admin", '
    '"space": "haui", "clearance": "all", "mayShare": true}';

/// Pumps the settings of a signed-in administrator, recording every request.
Future<List<http.Request>> pumpSettings(
  WidgetTester tester, {
  String users = "",
  http.Response Function(http.Request request)? onWrite,
}) async {
  var requests = <http.Request>[];
  var settings = ServerSettings(
    store: InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"),
  );
  await settings.load();
  await tester.binding.setSurfaceSize(const Size(800, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (url) => VAlbumClient(
          dataUrl: url,
          httpClient: MockClient((request) async {
            requests.add(request);
            var query = request.url.queryParameters;
            if (query["type"] == "auth") {
              return json(adminAuth);
            }
            if (query["type"] == "users") {
              return json(users.isEmpty ? someUsers() : users);
            }
            if (query["action"] == "set-permission" ||
                query["action"] == "remove-user") {
              return onWrite == null
                  ? json(someUsers())
                  : onWrite(request);
            }
            if (query["type"] == "invitations") {
              return json('{"invitations": []}');
            }
            return json('{"devices": []}');
          }),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return requests;
}

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The line under a user's name.
String lineOf(WidgetTester tester, String name) =>
    tester.widget<Text>(find.byKey(Key("user-permission-$name"))).data!;

void main() {
  group('changing what somebody may do', () {
    testWidgets('opens the choices prefilled with what they have',
        (tester) async {
      await pumpSettings(tester);

      await tapVisible(tester, find.byKey(const Key("user-edit-carol")));

      expect(find.byKey(const Key("permission-dialog")), findsOneWidget);
      expect(find.text("What carol may do"), findsOneWidget);
      // Prefilled: a view user who sees all but the private images and shares
      // no links.
      expect(chosen(tester, "permission-role-view"), isTrue);
      expect(chosen(tester, "permission-role-edit"), isFalse);
      expect(chosen(tester, "permission-clearance-nonPrivate"), isTrue);
      expect(
        tester
            .widget<SwitchListTile>(
              find.byKey(const Key("permission-may-share")),
            )
            .value,
        isFalse,
      );
    });

    testWidgets('sends what was chosen and shows what came back',
        (tester) async {
      var requests = await pumpSettings(
        tester,
        onWrite: (_) => json(carolChanged()),
      );

      await tapVisible(tester, find.byKey(const Key("user-edit-carol")));
      await tapVisible(
        tester,
        find.byKey(const Key("permission-role-contribute")),
      );
      await tapVisible(
        tester,
        find.byKey(const Key("permission-clearance-all")),
      );
      await tapVisible(tester, find.byKey(const Key("permission-may-share")));
      await tapVisible(tester, find.byKey(const Key("permission-save")));

      var write = requests
          .where((r) => r.url.queryParameters["action"] == "set-permission")
          .single;
      expect(write.method, "POST");
      expect(write.body, contains('"name":"carol"'));
      expect(write.body, contains('"role":"contribute"'));
      expect(write.body, contains('"clearance":"all"'));
      expect(write.body, contains('"mayShare":true'));

      // The dialog is gone and the row says what the server answered.
      expect(find.byKey(const Key("permission-dialog")), findsNothing);
      expect(
        lineOf(tester, "carol"),
        startsWith("may add photos — sees all images — may share links"),
      );
    });

    testWidgets('shows the server\'s own sentence when it refuses',
        (tester) async {
      await pumpSettings(
        tester,
        onWrite: (_) => refusal(
          409,
          "The last administrator of this space stays an administrator.",
        ),
      );

      await tapVisible(tester, find.byKey(const Key("user-edit-haui")));
      await tapVisible(tester, find.byKey(const Key("permission-role-view")));
      await tapVisible(tester, find.byKey(const Key("permission-save")));

      // The dialog stays, holding the refusal and what was chosen.
      expect(find.byKey(const Key("permission-dialog")), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key("permission-refusal"))).data,
        "The last administrator of this space stays an administrator.",
      );
      expect(chosen(tester, "permission-role-view"), isTrue);
    });

    testWidgets('sends nothing when it is cancelled', (tester) async {
      var requests = await pumpSettings(tester);

      await tapVisible(tester, find.byKey(const Key("user-edit-carol")));
      await tapVisible(tester, find.byKey(const Key("permission-role-edit")));
      await tapVisible(tester, find.text("Cancel"));

      expect(
        requests.where((r) => r.url.queryParameters["action"] != null),
        isEmpty,
      );
      expect(lineOf(tester, "carol"), startsWith("may look"));
    });
  });

  group('removing somebody', () {
    testWidgets('says what it does before it does it', (tester) async {
      var requests = await pumpSettings(tester);

      await tapVisible(tester, find.byKey(const Key("user-remove-carol")));

      expect(find.byKey(const Key("remove-user-confirm")), findsOneWidget);
      expect(find.text("Remove carol?"), findsOneWidget);
      expect(
        find.text("Their devices are signed out; their photos and their name "
            "on them stay."),
        findsOneWidget,
      );
      expect(
        requests.where((r) => r.url.queryParameters["action"] != null),
        isEmpty,
        reason: "nothing is sent before the confirmation",
      );
    });

    testWidgets('sends the request and shows what is left', (tester) async {
      var requests = await pumpSettings(
        tester,
        onWrite: (_) => json(carolGone()),
      );

      await tapVisible(tester, find.byKey(const Key("user-remove-carol")));
      await tapVisible(tester, find.byKey(const Key("remove-user-confirmed")));

      var write = requests
          .where((r) => r.url.queryParameters["action"] == "remove-user")
          .single;
      expect(write.method, "POST");
      expect(write.body, contains('"name":"carol"'));

      expect(find.byKey(const Key("user-carol")), findsNothing);
      expect(find.byKey(const Key("user-bob")), findsOneWidget);
    });

    testWidgets('sends nothing when the confirmation is declined',
        (tester) async {
      var requests = await pumpSettings(tester);

      await tapVisible(tester, find.byKey(const Key("user-remove-carol")));
      await tapVisible(tester, find.text("Cancel"));

      expect(
        requests.where((r) => r.url.queryParameters["action"] != null),
        isEmpty,
      );
      expect(find.byKey(const Key("user-carol")), findsOneWidget);
    });

    testWidgets('shows the server\'s sentence where it refuses',
        (tester) async {
      await pumpSettings(
        tester,
        onWrite: (_) => refusal(409, "The last administrator stays."),
      );

      await tapVisible(tester, find.byKey(const Key("user-remove-haui")));
      await tapVisible(tester, find.byKey(const Key("remove-user-confirmed")));

      expect(find.byKey(const Key("settings.users.error")), findsOneWidget);
      expect(find.text("The last administrator stays."), findsOneWidget);
      // Nobody vanished from the list on a refusal.
      expect(find.byKey(const Key("user-haui")), findsOneWidget);
    });
  });

  group('the address of a space', () {
    testWidgets('is explained at the server field', (tester) async {
      await pumpSettings(tester);

      var help = tester.widget<Text>(find.byKey(serverUrlHelpKey)).data!;
      expect(help, contains("http://nas.local:8080/valbum/"));
      expect(help, contains("https://host/valbum/<space>/"));
    });

    testWidgets('is named in the signed-in block where the server names one',
        (tester) async {
      await pumpSettings(tester);

      expect(find.text("Space: haui"), findsOneWidget);
    });

    testWidgets('is said nothing about on a server with one library',
        (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore(serverUrl, "admin-token", "Desk", "haui"),
      );
      await settings.load();
      await tester.pumpWidget(
        MaterialApp(
          home: ServerSettingsScreen(
            settings: settings,
            clientFor: (url) => VAlbumClient(
              dataUrl: url,
              httpClient: MockClient((request) async => json(
                    '{"mode": "writes", "deviceName": "Desk", '
                    '"writeAllowed": true, "userName": "haui", '
                    '"role": "admin", "space": "", "clearance": "all", '
                    '"mayShare": true}',
                  )),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining("Space:"), findsNothing);
    });
  });
}

/// Whether the choice with the given key is the one ticked.
bool chosen(WidgetTester tester, String key) =>
    tester.widget<ListTile>(find.byKey(Key(key))).selected;
