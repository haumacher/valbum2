/// Probe for the QR device code (issue #66), composing the payload with the
/// server-URL conventions and with the pairing flow it feeds.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/device_code_payload.dart';
import 'package:valbum_ui/device_code_scanner.dart';
import 'package:valbum_ui/main.dart';

final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

String fieldText(WidgetTester tester, Key key) =>
    tester.widget<TextField>(find.byKey(key)).controller?.text ?? "";

void main() {
  test('the payload round-trips the data URL through the settings field', () {
    for (var dataUrl in [
      "http://nas.local:8080/valbum/data",
      "https://photos.example.org/data",
      "http://192.168.1.20:9090/valbum/data",
      "https://example.org/Fotos%20der%20Familie/data",
    ]) {
      var payload = encodeDeviceCodePayload(appBaseOf(dataUrl), "abcd-efgh");
      var parsed = parseDeviceCodePayload(payload);
      expect(parsed, isNotNull, reason: payload);
      expect(dataUrlOf(parsed!.serverUrl), dataUrl, reason: payload);
      expect(parsed.code, "ABCDEFGH");
      expect(parsed.formattedCode, "ABCD-EFGH");
    }
  });

  test('parsing does not depend on parameter order, case or whitespace', () {
    var server = "http://nas.local:8080/valbum/";
    var reference = parseDeviceCodePayload(encodeDeviceCodePayload(server, "ABCD2345"));
    expect(reference, isNotNull);
    var encodedServer = Uri.encodeQueryComponent(server);
    expect(
      parseDeviceCodePayload("valbum-device://pair?code=ABCD2345&server=$encodedServer"),
      reference,
    );
    expect(
      parseDeviceCodePayload("VALBUM-DEVICE://pair?server=$encodedServer&code=abcd-2345"),
      reference,
    );
    expect(
      parseDeviceCodePayload("  valbum-device://pair?server=$encodedServer&code=ABCD2345\n"),
      reference,
    );
    // The same words as a share or an invitation link are not a code.
    expect(parseDeviceCodePayload("valbum-device://pair?server=$encodedServer"), isNull);
    expect(parseDeviceCodePayload("valbum-device://pair?code=ABCD2345"), isNull);
    expect(parseDeviceCodePayload("valbum-device://share?server=$encodedServer&code=ABCD2345"), isNull);
    expect(parseDeviceCodePayload("valbum-device://pair?server=nas.local&code=ABCD2345"), isNull,
        reason: "a server without a scheme is not a server URL");
    expect(parseDeviceCodePayload("WIFI:T:WPA;S:home;P:secret;;"), isNull);
  });

  testWidgets('what a scan filled in is what the sign-in sends, to the scanned server',
      (tester) async {
    var requests = <http.Request>[];
    var settings = ServerSettings(store: InMemorySettingsStore("http://server/valbum/"));
    await settings.load();
    var payload = encodeDeviceCodePayload("http://nas.local:8080/valbum/", "ABCD-2345");

    await tester.binding.setSurfaceSize(const Size(800, 2800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        home: DeviceCodeScannerScope(
          scanner: FakeDeviceCodeScanner(payload),
          child: ServerSettingsScreen(
            settings: settings,
            clientFor: (dataUrl) => VAlbumClient(
              dataUrl: dataUrl,
              httpClient: MockClient((request) async {
                requests.add(request);
                return http.Response("{}", 200);
              }),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(deviceCodeScanKey));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(deviceCodeScanKey));
    await tester.pumpAndSettle();
    expect(requests, isEmpty);

    await tester.ensureVisible(signInButton);
    await tester.pumpAndSettle();
    await tester.tap(signInButton);
    await tester.pumpAndSettle();

    var pair = requests.where((r) => r.url.queryParameters["action"] == "pair").toList();
    expect(pair, hasLength(1), reason: requests.map((r) => r.url).toList().toString());
    expect(pair.single.url.toString(), startsWith("http://nas.local:8080/valbum/data/"));
    // Sent as the field shows it; the server normalises dashes and case.
    var sent = RegExp(r'"deviceCode":"([^"]*)"').firstMatch(pair.single.body)!.group(1)!;
    expect(normalizeDeviceCode(sent), "ABCD2345");
    expect(pair.single.body, contains('"secret":""'));
  });
}
