/// Tests of the device code of issue #65: the way to add a further device of
/// one's own, which must never look or work like inviting somebody else.
///
/// What is asserted here is mostly what the dialog is *not*: no link, no
/// token, no word about invitations — a code read off this screen and typed on
/// the other one, with the sentence that says what it does.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/manage_view.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The token this device is signed in with.
const String deviceToken = "dev-token";

/// The moment the tests measure the remaining time against.
final DateTime fixedNow = DateTime.utc(2026, 9, 15, 10, 0, 0);

/// The code the server issues, and when it runs out.
const String theCode = "ABCD-2345";

/// A JSON answer of the server.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal of the server, with the reason it names.
http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status: status);

/// A device as the server lists it.
String deviceEntry(String id, String name, {bool current = false}) =>
    '{"id": "$id", "name": "$name", "created": "2026-01-02T10:00:00Z", '
    '"current": $current}';

/// The one device the tests start from.
String oneDevice() =>
    '{"devices": [${deviceEntry("d1", "Phone", current: true)}]}';

/// The same device plus the one that typed the code.
String twoDevices() => '{"devices": ['
    '${deviceEntry("d1", "Phone", current: true)}, '
    '${deviceEntry("d2", "Tablet")}]}';

/// The `?action=device-code` answer: the code and an expiry ten minutes on.
String codeAnswer() => '{"code": "$theCode", '
    '"expires": "${fixedNow.add(const Duration(minutes: 10)).toIso8601String()}"}';

/// Pumps the devices section alone, with a ticker the test drives itself.
///
/// The section is what carries the "Add a device..." button; the dialog is
/// opened from it, so this is the whole feature in one widget.
Future<StreamController<void>> pumpDevices(
  WidgetTester tester,
  http.Client transport,
) async {
  var ticks = StreamController<void>.broadcast();
  addTearDown(ticks.close);
  await tester.binding.setSurfaceSize(const Size(800, 1200));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: DevicesSection(
            client: VAlbumClient(
              dataUrl: dataUrl,
              token: deviceToken,
              httpClient: transport,
            ),
            onSignedOutHere: () async {},
            ticker: () => ticks.stream,
            now: () => fixedNow,
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return ticks;
}

/// Taps the "Add a device..." button and lets the dialog settle.
Future<void> openDialog(WidgetTester tester) async {
  await tester.ensureVisible(find.byKey(addDeviceButtonKey));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(addDeviceButtonKey));
  await tester.pumpAndSettle();
}

/// Every piece of text the dialog shows, joined.
String dialogText(WidgetTester tester) {
  var texts = <String>[];
  for (var text in tester.widgetList<Text>(find.descendant(
      of: find.byKey(deviceCodeDialogKey), matching: find.byType(Text)))) {
    texts.add(text.data ?? "");
  }
  for (var text in tester.widgetList<SelectableText>(find.descendant(
      of: find.byKey(deviceCodeDialogKey),
      matching: find.byType(SelectableText)))) {
    texts.add(text.data ?? "");
  }
  return texts.join("\n");
}

void main() {
  testWidgets('shows the code, the remaining time and what the code does',
      (tester) async {
    var requests = <http.Request>[];
    await pumpDevices(
      tester,
      MockClient((request) async {
        requests.add(request);
        if (request.url.queryParameters["action"] == "device-code") {
          return json(codeAnswer());
        }
        return json(oneDevice());
      }),
    );

    await openDialog(tester);

    expect(find.byKey(deviceCodeDialogKey), findsOneWidget);
    expect(
      tester.widget<SelectableText>(find.byKey(deviceCodeKey)).data,
      "ABCD-2345",
    );
    expect(
      tester.widget<Text>(find.byKey(deviceCodeRemainingKey)).data,
      "Expires in 10:00",
    );
    expect(find.text(deviceCodeAdvice), findsOneWidget);
    expect(
      deviceCodeAdvice,
      "Type this on the other device within 10 minutes. It signs that device "
      "in as you — never give it to anyone else.",
    );

    var asked = requests
        .where(
            (request) => request.url.queryParameters["action"] == "device-code")
        .single;
    expect(asked.headers["Authorization"], "Bearer $deviceToken");
    expect(asked.body, isNot(contains("invitation")));
    expect(asked.body, isNot(contains("secret")));
  });

  testWidgets('says nothing about inviting anybody', (tester) async {
    await pumpDevices(
      tester,
      MockClient((request) async =>
          request.url.queryParameters["action"] == "device-code"
              ? json(codeAnswer())
              : json(oneDevice())),
    );

    await openDialog(tester);

    var shown = dialogText(tester);
    expect(shown, isNot(contains("invite")));
    expect(shown, isNot(contains("Invite")));
    expect(shown, isNot(contains("invitation")));
    // Nothing a browser offers to open: the link the dialog shows is the
    // app's own `valbum-device://` payload (issue #91), and the server it
    // names is percent-encoded inside it, never a `http://` address to click.
    expect(shown, isNot(contains("http://")));
    expect(shown, isNot(contains("https://")));
    expect(shown, contains("valbum-device://pair"));
    expect(shown, contains("Add a device"));
  });

  testWidgets('says who joined and refreshes the list behind it',
      (tester) async {
    var joined = false;
    var ticks = await pumpDevices(
      tester,
      MockClient((request) async {
        if (request.url.queryParameters["action"] == "device-code") {
          joined = true;
          return json(codeAnswer());
        }
        return json(joined ? twoDevices() : oneDevice());
      }),
    );

    await openDialog(tester);
    expect(find.byKey(deviceCodeJoinedKey), findsNothing);

    // Three beats is one poll of the device list.
    for (var beat = 0; beat < 3; beat++) {
      ticks.add(null);
      await tester.pump();
    }
    await tester.pumpAndSettle();

    expect(
      tester.widget<Text>(find.byKey(deviceCodeJoinedKey)).data,
      "Tablet joined.",
    );
    // The section behind the dialog lists the new device as an ordinary one.
    expect(find.byKey(const Key("device-d2")), findsOneWidget);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
  });

  testWidgets('counts the code down and offers a new one when it runs out',
      (tester) async {
    var expires = fixedNow.subtract(const Duration(seconds: 1));
    await pumpDevices(
      tester,
      MockClient((request) async =>
          request.url.queryParameters["action"] == "device-code"
              ? json('{"code": "$theCode", '
                  '"expires": "${expires.toIso8601String()}"}')
              : json(oneDevice())),
    );

    await openDialog(tester);

    expect(
      tester.widget<Text>(find.byKey(deviceCodeRemainingKey)).data,
      "This code has expired.",
    );
    expect(find.byKey(deviceCodeRenewKey), findsOneWidget);
  });

  testWidgets('shows the server\'s own reason for refusing a code',
      (tester) async {
    await pumpDevices(
      tester,
      MockClient((request) async =>
          request.url.queryParameters["action"] == "device-code"
              ? refusal(403, "A share link is not a sign-in.")
              : json(oneDevice())),
    );

    await openDialog(tester);

    expect(
      tester.widget<Text>(find.byKey(deviceCodeErrorKey)).data,
      "A share link is not a sign-in.",
    );
    expect(find.byKey(deviceCodeKey), findsNothing);
    expect(find.byKey(deviceCodeRenewKey), findsOneWidget);
  });
}
