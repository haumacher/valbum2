/// Tests of the groups screen (issue #55): listing the groups one has,
/// creating one, renaming one, changing its members and removing it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/groups_view.dart';
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

/// A group as the server answers it.
String groupEntry(String name, List<String> members, {String owner = "bob"}) =>
    '{"name": "$name", "owner": "$owner", "members": '
    '[${members.map((m) => '{"name": "$m"}').join(", ")}], '
    '"created": "2026-01-01T10:00:00Z"}';

/// A list of groups, as the server answers it.
String groupList(List<String> groups) => '{"groups": [${groups.join(", ")}]}';

/// The two users the server knows.
const String someUsers = '{"users": ['
    '{"name": "bob", "role": "member", "space": "bob", "created": "", '
    '"devices": 1}, '
    '{"name": "dora", "role": "member", "space": "dora", "created": "", '
    '"devices": 1}]}';

/// The groups the tests start from.
String twoGroups() => groupList([
      groupEntry("family", const ["dora"]),
      groupEntry("club", const [], owner: "dora"),
    ]);

/// A handler answering as the server the groups screen talks to.
http.Response Function(http.Request) groupServer({
  http.Response Function(http.Request)? groups,
  http.Response Function(http.Request)? group,
  http.Response Function(http.Request)? regroup,
  http.Response Function(http.Request)? ungroup,
}) =>
    (request) {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser("member", name: "bob"));
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
      if (query["type"] == "groups") {
        return (groups ?? (_) => json(twoGroups()))(request);
      }
      if (query["action"] == "group") {
        return (group ?? (_) => json(twoGroups()))(request);
      }
      if (query["action"] == "regroup") {
        return (regroup ??
            (_) => json(groupList([
                  groupEntry("relatives", const ["dora"]),
                  groupEntry("club", const [], owner: "dora"),
                ])))(request);
      }
      if (query["action"] == "ungroup") {
        return (ungroup ??
            (_) => json(groupList([
                  groupEntry("club", const [], owner: "dora"),
                ])))(request);
      }
      return http.Response("No such resource: ${request.url}", 404);
    };

/// Pumps the groups screen alone, talking to the given handler.
Future<List<http.Request>> pumpGroups(
  WidgetTester tester,
  http.Response Function(http.Request) handler,
) async {
  var requests = <http.Request>[];
  await tester.binding.setSurfaceSize(const Size(800, 1200));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: GroupsScreen(
        client: VAlbumClient(
          dataUrl: "http://server/valbum/data",
          token: "member-token",
          userName: "bob",
          httpClient: MockClient((request) async {
            requests.add(request);
            return handler(request);
          }),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return requests;
}

void main() {
  testWidgets('lists the groups with their members', (tester) async {
    await pumpGroups(tester, groupServer());

    expect(find.byKey(const Key("group-family")), findsOneWidget);
    expect(find.byKey(const Key("group-club")), findsOneWidget);
    expect(find.text("dora"), findsOneWidget);
    expect(find.text("no members"), findsOneWidget);
  });

  testWidgets('offers the actions only for the groups one owns',
      (tester) async {
    await pumpGroups(tester, groupServer());

    expect(find.byKey(const Key("group-rename-family")), findsOneWidget);
    expect(find.byKey(const Key("group-remove-family")), findsOneWidget);
    expect(find.byKey(const Key("group-edit-family")), findsOneWidget);
    // 'club' belongs to somebody else: it can be shared with, not changed.
    expect(find.byKey(const Key("group-rename-club")), findsNothing);
  });

  testWidgets('creates a group with the members that were ticked',
      (tester) async {
    var requests = await pumpGroups(tester, groupServer());

    await tapVisible(tester, find.byKey(const Key("group-create")));
    expect(find.byKey(const Key("group-dialog")), findsOneWidget);
    await tester.enterText(find.byKey(const Key("group-name")), "neighbours");
    await tester.tap(find.byKey(const Key("member-dora")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("group-save")));
    await tester.pumpAndSettle();

    var created = requests.last;
    expect(created.method, "POST");
    expect(created.url.queryParameters["action"], "group");
    expect(created.body, contains('"name":"neighbours"'));
    expect(created.body, contains('"name":"dora"'));
  });

  testWidgets('renames a group, sending the old and the new name',
      (tester) async {
    var requests = await pumpGroups(tester, groupServer());

    await tapVisible(tester, find.byKey(const Key("group-rename-family")));
    expect(find.byKey(const Key("group-rename-dialog")), findsOneWidget);
    // What a rename does to the grants is said before it is done.
    expect(find.byKey(const Key("group-rename-note")), findsOneWidget);
    await tester.enterText(
        find.byKey(const Key("group-new-name")), "relatives");
    await tester.tap(find.byKey(const Key("group-rename-save")));
    await tester.pumpAndSettle();

    var renamed = requests.last;
    expect(renamed.method, "POST");
    expect(renamed.url.queryParameters["action"], "regroup");
    expect(renamed.body, contains('"name":"family"'));
    expect(renamed.body, contains('"newName":"relatives"'));

    expect(find.byKey(const Key("group-relatives")), findsOneWidget);
    expect(find.byKey(const Key("group-family")), findsNothing);
  });

  testWidgets('a refused rename shows the server\'s own sentence',
      (tester) async {
    await pumpGroups(
      tester,
      groupServer(
        regroup: (_) => refusal(
          409,
          "There is a user named 'dora'; a group cannot be named like one.",
        ),
      ),
    );

    await tapVisible(tester, find.byKey(const Key("group-rename-family")));
    await tester.enterText(find.byKey(const Key("group-new-name")), "dora");
    await tester.tap(find.byKey(const Key("group-rename-save")));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key("groups-error")), findsOneWidget);
    expect(
      find.text("There is a user named 'dora'; a group cannot be named like "
          "one."),
      findsOneWidget,
    );
    // The group is still there under its old name.
    expect(find.byKey(const Key("group-family")), findsOneWidget);
  });

  testWidgets('removing a group asks first and then drops it', (tester) async {
    var requests = await pumpGroups(tester, groupServer());

    await tapVisible(tester, find.byKey(const Key("group-remove-family")));
    expect(find.byKey(const Key("group-remove-confirm")), findsOneWidget);
    await tester.tap(find.byKey(const Key("group-remove-confirmed")));
    await tester.pumpAndSettle();

    var removed = requests.last;
    expect(removed.url.queryParameters["action"], "ungroup");
    expect(removed.body, contains('"name":"family"'));
    expect(find.byKey(const Key("group-family")), findsNothing);
  });

  testWidgets('a declined confirmation removes nothing', (tester) async {
    var requests = await pumpGroups(tester, groupServer());

    await tapVisible(tester, find.byKey(const Key("group-remove-family")));
    await tester.tap(find.text("Cancel"));
    await tester.pumpAndSettle();

    expect(
      requests.any((r) => r.url.queryParameters["action"] == "ungroup"),
      isFalse,
    );
    expect(find.byKey(const Key("group-family")), findsOneWidget);
  });

  testWidgets('a refused list says why', (tester) async {
    await pumpGroups(
      tester,
      groupServer(groups: (_) => refusal(401, "Sign in to see your groups.")),
    );

    expect(find.text("Sign in to see your groups."), findsOneWidget);
  });

  testWidgets('the settings open it for a member', (tester) async {
    await pumpSettings(
      tester,
      await signedIn(
        InMemorySettingsStore(serverUrl, "member-token", "Desk", "bob"),
      ),
      MockClient((request) async => groupServer()(request)),
    );

    expect(find.byKey(groupsButtonKey), findsOneWidget);
    await tapVisible(tester, find.byKey(groupsButtonKey));

    expect(find.text("Groups"), findsOneWidget);
    expect(find.byKey(const Key("group-family")), findsOneWidget);
  });

  testWidgets('a guest is offered no groups at all', (tester) async {
    await pumpSettings(
      tester,
      await signedIn(
        InMemorySettingsStore(serverUrl, "guest-token", "Phone", "carol"),
      ),
      MockClient((request) async {
        if (request.url.queryParameters["type"] == "auth") {
          return json(authOfUser("guest"));
        }
        return groupServer()(request);
      }),
    );

    expect(find.byKey(groupsButtonKey), findsNothing);
  });
}
