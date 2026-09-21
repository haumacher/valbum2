/// Tests of the backup code in the devices section (issue #92): the way back
/// from signing out of one's last device.
///
/// A code like every other — typed into the one sign-in field, single use —
/// and two things that differ: it is sixteen characters, and it never expires.
/// Shown once, and afterwards only "made on <day>".
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/device_code_payload.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/settings.dart';
import 'util/l10n.dart';

const String serverUrl = "http://server/valbum/";

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status: status);

const String theCode = "WXYZ-2345-ABCD-6789";

String devicesAnswer({String backupCodeCreated = ""}) =>
    '{"devices": [{"id": "d2", "name": "Phone", '
    '"created": "2026-01-02T10:00:00Z", "current": true}], '
    '"backupCodeCreated": "$backupCodeCreated"}';

/// A server that makes a backup code and remembers that it did.
MockClient backupServer({List<http.Request>? requests}) {
  var made = "";
  return MockClient((request) async {
    requests?.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "auth") {
      return json('{"mode": "writes", "deviceName": "Phone", '
          '"writeAllowed": true, "userName": "carol", "role": "edit", '
          '"space": ""}');
    }
    if (query["type"] == "invitations") {
      return json('{"invitations": []}');
    }
    if (query["action"] == "backup-code") {
      made = "2026-09-19T08:00:00Z";
      return json('{"code": "$theCode", "expires": ""}');
    }
    if (query["action"] == "revoke-backup-code") {
      made = "";
      return json(devicesAnswer());
    }
    if (query["type"] == "devices" || query["action"] == "unpair") {
      return json(devicesAnswer(backupCodeCreated: made));
    }
    return json('{"users": []}');
  });
}

Future<ServerSettings> signedIn() async {
  var settings = ServerSettings(
    store: InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol"),
  );
  await settings.load();
  return settings;
}

Future<void> pumpSettings(WidgetTester tester, http.Client transport) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ServerSettingsScreen(
        settings: await signedIn(),
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
  testWidgets('says that there is none, and offers to make one',
      (tester) async {
    await pumpSettings(tester, backupServer());

    expect(find.byKey(backupCodeStateKey), findsOneWidget);
    expect(find.text(noBackupCode(testL10n)), findsOneWidget);
    expect(find.byKey(backupCodeCreateKey), findsOneWidget);
    // Nothing to withdraw yet.
    expect(find.byKey(backupCodeRevokeKey), findsNothing);
  });

  testWidgets('shows the code once, and the day afterwards', (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, backupServer(requests: requests));

    await tapVisible(tester, find.byKey(backupCodeCreateKey));

    expect(find.byKey(deviceCodeDialogKey), findsOneWidget);
    expect(find.text("Backup code"), findsOneWidget);
    expect(find.text(theCode), findsOneWidget);
    // Sixteen characters of the alphabet, in four groups.
    expect(normalizeDeviceCode(theCode).length, backupCodeLength);
    expect(isDeviceCode(theCode), isTrue);
    // It does not run out, and it says so instead of counting down.
    expect(find.byKey(deviceCodeRemainingKey), findsOneWidget);
    expect(find.text("This code does not expire. It works once."),
        findsOneWidget);
    expect(find.text(backupCodeAdvice(testL10n)), findsOneWidget);

    await tapVisible(tester, find.text("Done"));

    // Afterwards: only that there is one, and since when.
    expect(find.text(theCode), findsNothing);
    expect(
      find.textContaining("Backup code: made on"),
      findsOneWidget,
    );
    expect(find.byKey(backupCodeRevokeKey), findsOneWidget);
    expect(
      requests
          .where((r) => r.url.queryParameters["action"] == "backup-code")
          .length,
      1,
    );
  });

  testWidgets('withdrawing it clears the line', (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, backupServer(requests: requests));
    await tapVisible(tester, find.byKey(backupCodeCreateKey));
    await tapVisible(tester, find.text("Done"));

    await tapVisible(tester, find.byKey(backupCodeRevokeKey));
    // It asks first: the piece of paper stops working.
    expect(find.byKey(const Key("backup-code-confirm")), findsOneWidget);
    await tapVisible(tester, find.byKey(const Key("backup-code-confirmed")));

    expect(find.text(noBackupCode(testL10n)), findsOneWidget);
    expect(find.byKey(backupCodeRevokeKey), findsNothing);
    expect(
      requests.any(
          (r) => r.url.queryParameters["action"] == "revoke-backup-code"),
      isTrue,
    );
  });

  testWidgets('cancelling the withdrawal keeps the code', (tester) async {
    await pumpSettings(tester, backupServer());
    await tapVisible(tester, find.byKey(backupCodeCreateKey));
    await tapVisible(tester, find.text("Done"));

    await tapVisible(tester, find.byKey(backupCodeRevokeKey));
    await tapVisible(tester, find.text("Cancel"));

    expect(find.textContaining("Backup code: made on"), findsOneWidget);
  });

  testWidgets('the server refusing says so, word for word', (tester) async {
    await pumpSettings(
      tester,
      MockClient((request) async {
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json('{"mode": "writes", "deviceName": "Phone", '
              '"writeAllowed": true, "userName": "carol", "role": "edit", '
              '"space": ""}');
        }
        if (query["type"] == "invitations") {
          return json('{"invitations": []}');
        }
        if (query["action"] == "backup-code") {
          return refusal(403, "This server does not pair devices.");
        }
        if (query["type"] == "devices") {
          return json(devicesAnswer());
        }
        return json('{"users": []}');
      }),
    );

    await tapVisible(tester, find.byKey(backupCodeCreateKey));

    expect(find.text("This server does not pair devices."), findsOneWidget);
  });
}
