/// Tests of scanning a device code on the sign-in screen (issue #66).
///
/// What a scan does is fill two fields and nothing else: the server and the
/// code, exactly as they would have been typed. Nothing is sent — the person
/// sees what was read and presses the sign-in button themselves — and text
/// that is not a device code changes nothing and is said out loud.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/device_code_payload.dart';
import 'package:valbum_ui/device_code_scanner.dart';
import 'package:valbum_ui/main.dart';

/// The server the screen starts at, and the one the QR code names.
const String serverUrl = "http://server/valbum/";
const String scannedServerUrl = "http://nas.local:8080/valbum/";

/// The payload of the QR code shown on the other device.
final String payload = encodeDeviceCodePayload(scannedServerUrl, "ABCD2345");

/// The "Sign in" button; the section carries the same title, so the button is
/// addressed by its widget.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

/// Pumps the settings screen with the given scanner behind it.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
  DeviceCodeScanner scanner,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 2800));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: DeviceCodeScannerScope(
        scanner: scanner,
        child: ServerSettingsScreen(
          settings: settings,
          clientFor: (dataUrl) =>
              VAlbumClient(dataUrl: dataUrl, httpClient: transport),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The text a field holds.
String fieldText(WidgetTester tester, Key key) =>
    tester.widget<TextField>(find.byKey(key)).controller?.text ?? "";

void main() {
  testWidgets('a scanned code fills the server and the code, and sends nothing',
      (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();
    var scanner = FakeDeviceCodeScanner(payload);

    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        return http.Response("{}", 200);
      }),
      scanner,
    );
    var before = requests.length;

    await tapVisible(tester, find.byKey(deviceCodeScanKey));

    expect(scanner.scans, 1);
    expect(fieldText(tester, serverUrlFieldKey), scannedServerUrl);
    // Spelled the way the other screen shows it and the way a typed one is.
    expect(fieldText(tester, deviceCodeFieldKey), "ABCD-2345");
    expect(
      requests.length,
      before,
      reason: "A scan fills the fields; the sign-in is the button below.",
    );
    // Nothing is stored either: signing in is what stores anything.
    expect(settings.token, isNull);
  });

  testWidgets('replaces whatever stood in the fields before', (tester) async {
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      MockClient((request) async => http.Response("{}", 200)),
      FakeDeviceCodeScanner(payload),
    );

    await tester.enterText(find.byKey(serverUrlFieldKey), "http://old/valbum/");
    await tester.enterText(find.byKey(deviceCodeFieldKey), "WXYZ-6789");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(deviceCodeScanKey));

    expect(fieldText(tester, serverUrlFieldKey), scannedServerUrl);
    expect(fieldText(tester, deviceCodeFieldKey), "ABCD-2345");
  });

  testWidgets('says so and changes nothing when what was read is no code',
      (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        return http.Response("{}", 200);
      }),
      // An invitation link is a credential of this very server, and still not
      // something the sign-in screen accepts from a camera.
      FakeDeviceCodeScanner("https://example.org/valbum/i/abc/"),
    );
    await tester.enterText(find.byKey(serverUrlFieldKey), serverUrl);
    await tester.pumpAndSettle();
    var before = requests.length;

    await tapVisible(tester, find.byKey(deviceCodeScanKey));

    expect(
      tester.widget<Text>(find.byKey(signInErrorKey)).data,
      "This is not a device code.",
    );
    expect(fieldText(tester, serverUrlFieldKey), serverUrl);
    expect(fieldText(tester, deviceCodeFieldKey), "");
    expect(requests.length, before);
  });

  testWidgets('offers no scanner where the platform has no camera',
      (tester) async {
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      MockClient((request) async => http.Response("{}", 200)),
      const NoDeviceCodeScanner(),
    );

    // Not a disabled button: a button that can never work promises a camera
    // this machine does not have.
    expect(find.byKey(deviceCodeScanKey), findsNothing);
    expect(find.byKey(deviceCodeFieldKey), findsOneWidget);
  });

  testWidgets('a scan replaces what was typed and signs in with it',
      (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        requests.add(request);
        return http.Response("{}", 200);
      }),
      FakeDeviceCodeScanner(payload),
    );

    await tester.enterText(find.byKey(deviceCodeFieldKey), "WXYZ-9876");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(deviceCodeScanKey));

    // What was scanned is what is in the fields; the scan alone sends nothing.
    expect(fieldText(tester, serverUrlFieldKey), scannedServerUrl);
    expect(fieldText(tester, deviceCodeFieldKey), "ABCD-2345");
    expect(find.byKey(signInErrorKey), findsNothing);
    expect(
      requests.where((request) => request.url.query == "action=pair"),
      isEmpty,
    );

    await tapVisible(tester, signInButton);

    var pair = requests
        .where((request) => request.url.query == "action=pair")
        .single;
    expect(pair.body, contains('"deviceCode":"ABCD-2345"'));
  });
}
