/// Review probe of the management sections (issue #55): the sections compose
/// with the caller's role and with the sign-in the settings already had — a
/// guest is offered exactly their devices and nothing that would be refused,
/// a signed-out device asks for nothing, and signing out the asking device
/// through its list leaves the screen in the signed-out state.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';

const String serverUrl = "http://server/valbum/";

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String authOf(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "carol"}';

String devices(List<String> ids, String current) => '{"devices": ['
    '${ids.map((id) => '{"id": "$id", "name": "Device $id", '
        '"created": "2026-03-04T05:06:07Z", "current": ${id == current}}').join(", ")}'
    ']}';

Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a guest sees their devices and nothing that would be refused',
      (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(
        store: InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol"));
    await settings.load();
    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf("guest"));
        }
        if (query["type"] == "devices") {
          return json(devices(const ["d1", "d2"], "d2"));
        }
        return json('["ErrorInfo", {"message": "refused"}]', status: 403);
      }),
    );

    expect(find.byKey(devicesSectionKey), findsOneWidget);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
    expect(find.byKey(groupsButtonKey), findsNothing);
    expect(find.byKey(inviteButtonKey), findsNothing);
    expect(find.byKey(usersSectionKey), findsNothing);
    expect(find.byKey(invitationsSectionKey), findsNothing);
    // Nothing was asked that the server would have refused by role.
    var asked = requests.map((r) => r.url.queryParameters).toList();
    expect(asked.where((q) => q["type"] == "users"), isEmpty);
    expect(asked.where((q) => q["type"] == "invitations"), isEmpty);
    expect(asked.where((q) => q["type"] == "groups"), isEmpty);
    expect(find.text("refused"), findsNothing);
  });

  testWidgets('a signed-out device asks for no device list', (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();
    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json('{"mode": "writes", "writeAllowed": false}');
        }
        return json('["ErrorInfo", {"message": "who are you"}]', status: 401);
      }),
    );

    expect(find.byKey(devicesSectionKey), findsNothing);
    expect(find.byKey(usersSectionKey), findsNothing);
    expect(
      requests.where((r) => r.url.queryParameters["type"] == "devices"),
      isEmpty,
    );
    expect(find.text("who are you"), findsNothing);
  });

  testWidgets(
      'signing out the asking device leaves the screen signed out and quiet',
      (tester) async {
    var requests = <http.Request>[];
    var store = InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol");
    var settings = ServerSettings(store: store);
    await settings.load();
    var unpaired = false;
    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return unpaired
              ? json('{"mode": "writes", "writeAllowed": false}')
              : json(authOf("admin"));
        }
        if (query["type"] == "devices") {
          return json(devices(const ["d1", "d2"], "d2"));
        }
        if (query["action"] == "unpair") {
          expect(request.headers["authorization"], "Bearer dev-2");
          expect(request.body, contains('"d2"'));
          unpaired = true;
          return json(devices(const ["d1"], ""));
        }
        if (query["type"] == "users") {
          return json('{"users": [{"name": "carol", "role": "admin"}]}');
        }
        if (query["type"] == "invitations") {
          return json('{"invitations": []}');
        }
        return json('["ErrorInfo", {"message": "unexpected"}]', status: 400);
      }),
    );
    expect(find.byKey(usersSectionKey), findsOneWidget);

    var remove = find.byKey(const Key("device-remove-d2"));
    await tester.ensureVisible(remove);
    await tester.pumpAndSettle();
    await tester.tap(remove);
    await tester.pumpAndSettle();
    var confirm = find.byKey(const Key("device-confirmed"));
    expect(confirm, findsOneWidget);
    var before = requests.length;
    await tester.tap(confirm);
    await tester.pumpAndSettle();

    // The device forgot its token, the whole management part is gone with
    // the sign-in, and no request after the unpair carried the dead token.
    expect(store.token, isNull);
    expect(settings.signedIn, isFalse);
    expect(find.byKey(devicesSectionKey), findsNothing);
    expect(find.byKey(usersSectionKey), findsNothing);
    expect(find.byKey(groupsButtonKey), findsNothing);
    for (var request in requests.skip(before + 1)) {
      expect(request.headers["authorization"], isNot("Bearer dev-2"),
          reason: "${request.url} still carried the unpaired token");
    }
    expect(find.text("unexpected"), findsNothing);
  });

  testWidgets('a device the server does not know leaves the list as it was',
      (tester) async {
    var settings = ServerSettings(
        store: InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol"));
    await settings.load();
    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf("member"));
        }
        if (query["type"] == "devices") {
          return json(devices(const ["d1", "d2"], "d2"));
        }
        if (query["action"] == "unpair") {
          return json('["ErrorInfo", {"message": "No such device: d1"}]',
              status: 404);
        }
        return json('{"invitations": [], "users": [], "groups": []}');
      }),
    );

    var remove = find.byKey(const Key("device-remove-d1"));
    await tester.ensureVisible(remove);
    await tester.pumpAndSettle();
    await tester.tap(remove);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("device-confirmed")));
    await tester.pumpAndSettle();

    expect(find.text("No such device: d1"), findsOneWidget);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
    expect(find.byKey(const Key("device-d2")), findsOneWidget);
    expect(settings.signedIn, isTrue);
  });
}
