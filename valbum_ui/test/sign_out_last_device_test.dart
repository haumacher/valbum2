/// Tests of the sign-out that has no way back (issue #92).
///
/// Signing out on a foreign device is harmless — the device that showed the
/// code is still signed in. The danger is one's **last** device, and there the
/// question names the ways back there are, in words.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';

const String serverUrl = "http://server/valbum/";

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String authOfUser(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": ""}';

String deviceEntry(String id, String name, {bool current = false}) =>
    '{"id": "$id", "name": "$name", "created": "2026-01-02T10:00:00Z", '
    '"current": $current}';

/// A device list of one device, with or without a backup code.
String oneDevice({String backupCodeCreated = ""}) =>
    '{"devices": [${deviceEntry("d2", "Phone", current: true)}], '
    '"backupCodeCreated": "$backupCodeCreated"}';

String twoDevices() => '{"devices": ['
    '${deviceEntry("d1", "Desk")}, '
    '${deviceEntry("d2", "Phone", current: true)}], '
    '"backupCodeCreated": ""}';

/// A server answering the given device list.
MockClient serverListing(String devices, {String role = "edit"}) =>
    MockClient((request) async {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(authOfUser(role));
      }
      if (query["type"] == "invitations") {
        return json('{"invitations": []}');
      }
      if (query["type"] == "devices" || query["action"] == "unpair") {
        return json(devices);
      }
      return json('{"users": []}');
    });

Future<ServerSettings> signedIn(InMemorySettingsStore store) async {
  var settings = ServerSettings(store: store);
  await settings.load();
  return settings;
}

InMemorySettingsStore storeSignedIn() =>
    InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol");

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

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the only device asks, and names the way back', (tester) async {
    var store = storeSignedIn();
    await pumpSettings(tester, await signedIn(store), serverListing(
      oneDevice(),
    ));

    await tapVisible(tester, find.byKey(signOutButtonKey));

    expect(find.byKey(const Key("sign-out-confirm")), findsOneWidget);
    expect(
      find.textContaining("This is your only signed-in device."),
      findsOneWidget,
    );
    expect(
      find.textContaining("a recovery code from your administrator"),
      findsOneWidget,
    );
    // Nothing happened while the question is open.
    expect(store.token, "dev-2");

    await tapVisible(tester, find.byKey(const Key("sign-out-confirmed")));

    expect(store.token, isNull);
  });

  testWidgets('the administrator is told about the server restart',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      serverListing(oneDevice(), role: roleAdmin),
    );

    await tapVisible(tester, find.byKey(signOutButtonKey));

    expect(
      find.textContaining(
        "a restart of the server, which prints a new sign-in code",
      ),
      findsOneWidget,
    );
  });

  testWidgets('a backup code is named as the way back where there is one',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      serverListing(oneDevice(backupCodeCreated: "2026-09-01T08:00:00Z")),
    );

    await tapVisible(tester, find.byKey(signOutButtonKey));

    expect(
      find.descendant(
        of: find.byKey(const Key("sign-out-confirm")),
        matching: find.textContaining("your backup code"),
      ),
      findsOneWidget,
    );
  });

  testWidgets('a second device means no question at all', (tester) async {
    var store = storeSignedIn();
    await pumpSettings(tester, await signedIn(store), serverListing(
      twoDevices(),
    ));

    await tapVisible(tester, find.byKey(signOutButtonKey));

    expect(find.byKey(const Key("sign-out-confirm")), findsNothing);
    expect(store.token, isNull);
  });

  testWidgets('cancelling keeps the device signed in', (tester) async {
    var store = storeSignedIn();
    await pumpSettings(tester, await signedIn(store), serverListing(
      oneDevice(),
    ));

    await tapVisible(tester, find.byKey(signOutButtonKey));
    await tapVisible(tester, find.text("Cancel"));

    expect(store.token, "dev-2");
    expect(find.textContaining("Signed in as carol"), findsOneWidget);
  });

  testWidgets('signing the last device out in the list asks the same thing',
      (tester) async {
    await pumpSettings(
      tester,
      await signedIn(storeSignedIn()),
      serverListing(oneDevice(), role: roleAdmin),
    );

    await tapVisible(tester, find.byKey(const Key("device-remove-d2")));

    expect(
      find.textContaining("This is your only signed-in device."),
      findsOneWidget,
    );
  });

  testWidgets('the words of the warning', (tester) async {
    expect(
      lastDeviceWarning(isAdmin: false, hasBackupCode: false),
      "This is your only signed-in device. To sign in again you need a "
      "recovery code from your administrator.",
    );
    expect(
      lastDeviceWarning(isAdmin: true, hasBackupCode: true),
      "This is your only signed-in device. To sign in again you need your "
      "backup code, a recovery code from another administrator, or a restart "
      "of the server, which prints a new sign-in code.",
    );
  });
}
