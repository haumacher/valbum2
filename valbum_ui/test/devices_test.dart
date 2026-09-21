/// Tests of the "My devices" section of the server settings (issue #55): the
/// devices this person is signed in on, the one they are looking at, and the
/// two meanings of removing one.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// A JSON answer of the server.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal of the server, with the reason it names.
http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status: status);

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role, {String name = "carol"}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "$role", "space": "$name"}';

/// A device as the server lists it.
String deviceEntry(
  String id,
  String name, {
  bool current = false,
  String created = "2026-01-02T10:00:00Z",
}) =>
    '{"id": "$id", "name": "$name", "created": "$created", '
    '"current": $current}';

/// The list of the three devices the tests start from.
String threeDevices() => '{"devices": ['
    '${deviceEntry("d1", "Desk")}, '
    '${deviceEntry("d2", "Phone", current: true)}, '
    '${deviceEntry("d3", "Tablet")}]}';

/// Settings of a device signed in at [serverUrl].
Future<ServerSettings> signedIn(InMemorySettingsStore store) async {
  var settings = ServerSettings(store: store);
  await settings.load();
  return settings;
}

/// A store holding a token, as a signed-in device has one.
InMemorySettingsStore storeSignedIn() =>
    InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol");

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// A handler answering the auth probe, the invitations and the devices.
http.Response Function(http.Request) serverWith({
  required http.Response Function(http.Request) devices,
  String role = "member",
}) =>
    (request) {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser(role));
      }
      if (query["type"] == "invitations") {
        return json('{"invitations": []}');
      }
      if (query["type"] == "devices" || query["action"] == "unpair") {
        return devices(request);
      }
      return json('{"users": []}');
    };

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('lists the devices, marking the one being looked at',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async =>
          serverWith(devices: (_) => json(threeDevices()))(request)),
    );

    expect(find.byKey(devicesSectionKey), findsOneWidget);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
    expect(find.byKey(const Key("device-d2")), findsOneWidget);
    expect(find.byKey(const Key("device-d3")), findsOneWidget);
    expect(find.text("Phone (this device)"), findsOneWidget);
    expect(find.text("Desk"), findsOneWidget);
    expect(find.textContaining("Paired on"), findsNWidgets(3));
  });

  testWidgets('removing another device names it and shows what remains',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async {
        requests.add(request);
        return serverWith(
          devices: (request) => request.url.queryParameters["action"] == null
              ? json(threeDevices())
              : json('{"devices": ['
                  '${deviceEntry("d2", "Phone", current: true)}, '
                  '${deviceEntry("d3", "Tablet")}]}'),
        )(request);
      }),
    );

    await tapVisible(tester, find.byKey(const Key("device-remove-d1")));
    expect(find.byKey(const Key("device-confirm")), findsOneWidget);
    await tester.tap(find.byKey(const Key("device-confirmed")));
    await tester.pumpAndSettle();

    var unpair = requests.last;
    expect(unpair.method, "POST");
    expect(unpair.url.queryParameters["action"], "unpair");
    expect(unpair.body, contains('"id":"d1"'));
    expect(unpair.headers["Authorization"], "Bearer dev-2");

    expect(find.byKey(const Key("device-d1")), findsNothing);
    expect(find.byKey(const Key("device-d3")), findsOneWidget);
    // The device the app runs on is still signed in.
    expect(find.byKey(devicesSectionKey), findsOneWidget);
  });

  testWidgets('the confirmation can be declined, and nothing is sent',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async {
        requests.add(request);
        return serverWith(devices: (_) => json(threeDevices()))(request);
      }),
    );

    await tapVisible(tester, find.byKey(const Key("device-remove-d1")));
    await tester.tap(find.text("Cancel"));
    await tester.pumpAndSettle();

    expect(
      requests.any((r) => r.url.queryParameters["action"] == "unpair"),
      isFalse,
    );
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
  });

  testWidgets('removing the current device signs out here', (tester) async {
    var store = storeSignedIn();
    await pumpSettings(
      tester,
      await signedIn(store),
      MockClient((request) async => serverWith(
            devices: (request) => request.url.queryParameters["action"] == null
                ? json(threeDevices())
                : json('{"devices": ['
                    '${deviceEntry("d1", "Desk")}, '
                    '${deviceEntry("d3", "Tablet")}]}'),
          )(request)),
    );

    await tapVisible(tester, find.byKey(const Key("device-remove-d2")));
    expect(find.text("Sign out this device?"), findsOneWidget);
    await tester.tap(find.byKey(const Key("device-confirmed")));
    await tester.pumpAndSettle();

    // The token is gone from the device, exactly as "Sign out" leaves it.
    expect(store.token, isNull);
    expect(store.userName, isNull);
    // Nothing to list any more: this device is signed out.
    expect(find.byKey(devicesSectionKey), findsNothing);
    expect(find.text("Not signed in"), findsOneWidget);
  });

  testWidgets('a share link is told why it sees no devices', (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async => serverWith(
            devices: (_) => refusal(
              403,
              "A share link has no devices of its own.",
            ),
          )(request)),
    );

    expect(find.byKey(const Key("settings.devices.error")), findsOneWidget);
    expect(
      find.text("A share link has no devices of its own."),
      findsOneWidget,
    );
  });

  testWidgets('an unauthenticated answer shows its own sentence',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async => serverWith(
            devices: (_) => refusal(401, "Sign in to see your devices."),
          )(request)),
    );

    expect(find.text("Sign in to see your devices."), findsOneWidget);
  });

  testWidgets('a server that cannot be reached says so', (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      MockClient((request) async {
        if (request.url.queryParameters["type"] == "devices") {
          throw http.ClientException("Connection refused");
        }
        return serverWith(devices: (_) => json('{"devices": []}'))(request);
      }),
    );

    expect(find.textContaining("Connection refused"), findsOneWidget);
  });

  testWidgets('no devices are listed while this device is signed out',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      await signedIn(InMemorySettingsStore(serverUrl)),
      MockClient((request) async {
        requests.add(request);
        return serverWith(devices: (_) => json(threeDevices()))(request);
      }),
    );

    expect(find.byKey(devicesSectionKey), findsNothing);
    expect(
      requests.any((r) => r.url.queryParameters["type"] == "devices"),
      isFalse,
    );
  });
}
