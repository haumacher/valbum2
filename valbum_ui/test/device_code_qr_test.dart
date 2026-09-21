/// Tests of the QR code beside the device code (issue #66).
///
/// The QR code is an *addition* to the eight characters, never a replacement:
/// it carries the same credential, in a scheme nothing offers to open, and it
/// goes away the moment the code it shows is worth nothing any more.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/device_code_payload.dart';
import 'package:valbum_ui/manage_view.dart';
import 'util/l10n.dart';

/// The server the tests talk to, and the app base derived from it.
const String dataUrl = "http://server/valbum/data";
const String serverUrl = "http://server/valbum/";

/// The token this device is signed in with.
const String deviceToken = "dev-token";

/// The moment the tests measure the remaining time against.
final DateTime fixedNow = DateTime.utc(2026, 9, 15, 10, 0, 0);

/// The code the server issues.
const String theCode = "ABCD-2345";

/// A JSON answer of the server.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The one device the tests start from.
const String oneDevice = '{"devices": [{"id": "d1", "name": "Phone", '
    '"created": "2026-01-02T10:00:00Z", "current": true}]}';

/// The `?action=device-code` answer, running out at [expires].
String codeAnswer(DateTime expires) =>
    '{"code": "$theCode", "expires": "${expires.toIso8601String()}"}';

/// Pumps the devices section with a ticker the test drives, exactly as
/// `device_code_test.dart` does.
Future<StreamController<void>> pumpDevices(
  WidgetTester tester,
  http.Client transport,
) async {
  var ticks = StreamController<void>.broadcast();
  addTearDown(ticks.close);
  await tester.binding.setSurfaceSize(const Size(800, 1600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
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

/// A transport answering a code that runs out at [expires].
MockClient serverAnswering(DateTime expires) =>
    MockClient((request) async =>
        request.url.queryParameters["action"] == "device-code"
            ? json(codeAnswer(expires))
            : json(oneDevice));

void main() {
  testWidgets('shows the code as a QR code carrying server and code',
      (tester) async {
    await pumpDevices(
      tester,
      serverAnswering(fixedNow.add(const Duration(minutes: 10))),
    );

    await openDialog(tester);

    // `QrImageView` keeps its data private, so what is drawn is asserted on
    // the widget that draws it, see [DeviceCodeQr].
    var qr = tester.widget<DeviceCodeQr>(find.byKey(deviceCodeQrKey));
    expect(qr.payload, encodeDeviceCodePayload(serverUrl, "ABCD2345"));
    expect(
      find.descendant(
        of: find.byKey(deviceCodeQrKey),
        matching: find.byType(QrImageView),
      ),
      findsOneWidget,
    );
    // The scheme of this app's own: no camera app and no browser offers to
    // open it, see issue #66.
    expect(qr.payload, startsWith("valbum-device://pair?"));
    // What it carries is what the other device needs and nothing else.
    var parsed = parseDeviceCodePayload(qr.payload);
    expect(parsed?.serverUrl, serverUrl);
    expect(parsed?.code, "ABCD2345");

    // The typed code stays: the QR is an addition, not a replacement.
    expect(
      tester.widget<SelectableText>(find.byKey(deviceCodeKey)).data,
      "ABCD-2345",
    );
  });

  testWidgets('takes the QR code away once the code has expired',
      (tester) async {
    await pumpDevices(
      tester,
      serverAnswering(fixedNow.subtract(const Duration(seconds: 1))),
    );

    await openDialog(tester);

    expect(
      tester.widget<Text>(find.byKey(deviceCodeRemainingKey)).data,
      "This code has expired.",
    );
    expect(find.byKey(deviceCodeQrKey), findsNothing);
    expect(find.byKey(deviceCodeRenewKey), findsOneWidget);
  });

  testWidgets('shows no QR code while the server has not issued one',
      (tester) async {
    await pumpDevices(
      tester,
      MockClient((request) async =>
          request.url.queryParameters["action"] == "device-code"
              ? http.Response(
                  '["ErrorInfo", {"message": "A share link is not a sign-in."}]',
                  403,
                  headers: {"content-type": "application/json"},
                )
              : json(oneDevice)),
    );

    await openDialog(tester);

    expect(find.byKey(deviceCodeErrorKey), findsOneWidget);
    expect(find.byKey(deviceCodeQrKey), findsNothing);
  });
}
