/// Tests of the copyable link beside a code (issue #91).
///
/// A code is offered three ways now: the characters to type, the QR code to
/// scan, and a link to send — and the link is *the same payload the QR code
/// carries*, so that whatever the other device reads, it reads one thing.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/device_code_payload.dart';
import 'package:valbum_ui/manage_view.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// Pumps the code dialog alone, talking to a server that answers [code].
Future<void> pumpDialog(
  WidgetTester tester, {
  required String code,
  required String expires,
  bool backup = false,
}) async {
  await tester.binding.setSurfaceSize(const Size(800, 1800));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var transport = MockClient((request) async {
    var query = request.url.queryParameters;
    if (query["action"] == "device-code" ||
        query["action"] == "backup-code") {
      return json('{"code": "$code", "expires": "$expires"}');
    }
    return json('{"devices": [], "backupCodeCreated": ""}');
  });
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Scaffold(
        body: DeviceCodeDialog(
          client: VAlbumClient(dataUrl: dataUrl, httpClient: transport),
          onDevices: (_) {},
          backup: backup,
          // No ticking: the dialog is read, not waited out.
          ticker: () => const Stream<void>.empty(),
          now: () => DateTime.parse("2026-09-19T08:00:00Z"),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the link is exactly the payload the QR code carries',
      (tester) async {
    await pumpDialog(
      tester,
      code: "ABCD-2345",
      expires: "2026-09-19T08:10:00Z",
    );

    var qr = tester.widget<DeviceCodeQr>(find.byKey(deviceCodeQrKey));
    var link = tester.widget<SelectableText>(find.byKey(deviceCodeLinkKey));

    expect(link.data, qr.payload);
    expect(
      link.data,
      encodeDeviceCodePayload("http://server/valbum/", "ABCD2345"),
    );
    // The app's own scheme, which no browser and no camera app offers to
    // open; the server is percent-encoded inside it.
    expect(link.data, startsWith("valbum-device://pair?"));
    expect(parseDeviceCodePayload(link.data!)?.code, "ABCD2345");
  });

  testWidgets('copying puts that very text on the clipboard', (tester) async {
    var copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == "Clipboard.setData") {
          copied.add((call.arguments as Map)["text"] as String);
        }
        return null;
      },
    );
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    await pumpDialog(
      tester,
      code: "ABCD-2345",
      expires: "2026-09-19T08:10:00Z",
    );
    await tester.ensureVisible(find.byKey(deviceCodeCopyKey));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(deviceCodeCopyKey));
    await tester.pumpAndSettle();

    var link = tester.widget<SelectableText>(find.byKey(deviceCodeLinkKey));
    expect(copied, [link.data]);
  });

  testWidgets('a backup code carries the same three forms', (tester) async {
    await pumpDialog(
      tester,
      code: "WXYZ-2345-ABCD-6789",
      expires: "",
      backup: true,
    );

    expect(find.text("WXYZ-2345-ABCD-6789"), findsOneWidget);
    var qr = tester.widget<DeviceCodeQr>(find.byKey(deviceCodeQrKey));
    var link = tester.widget<SelectableText>(find.byKey(deviceCodeLinkKey));

    expect(link.data, qr.payload);
    expect(
      parseDeviceCodePayload(link.data!)?.code,
      "WXYZ2345ABCD6789",
    );
    // Sixteen characters go through the same payload as eight: the length
    // says nothing, see issue #92.
    expect(
      link.data,
      encodeDeviceCodePayload("http://server/valbum/", "WXYZ2345ABCD6789"),
    );
  });

  testWidgets('the round trip of the payload keeps the grouping',
      (tester) async {
    var payload =
        encodeDeviceCodePayload("http://nas.local:8080/valbum/", "ABCD2345");
    var read = parseDeviceCodePayload(payload);

    expect(read?.serverUrl, "http://nas.local:8080/valbum/");
    expect(read?.formattedCode, "ABCD-2345");

    var long = encodeDeviceCodePayload(
      "http://nas.local:8080/valbum/",
      "WXYZ2345ABCD6789",
    );
    expect(
      parseDeviceCodePayload(long)?.formattedCode,
      "WXYZ-2345-ABCD-6789",
    );
    // Anything else is no code at all.
    expect(isDeviceCode("ABCD-234"), isFalse);
    expect(isDeviceCode("ABCD-2345-ABCD"), isFalse);
    expect(isDeviceCode("ABCD-234O"), isFalse);
  });
}
