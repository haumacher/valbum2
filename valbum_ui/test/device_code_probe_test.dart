/// Probe for the device code of issue #65, composed with what was there before:
/// the unpairing of #55 on a device that joined by code, the polling that must
/// stop with the dialog, and a second code after the first ran out.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/manage_view.dart';

const String dataUrl = "http://server/valbum/data";
const String deviceToken = "dev-token";
final DateTime fixedNow = DateTime.utc(2026, 9, 15, 10, 0, 0);

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String deviceEntry(String id, String name, {bool current = false}) =>
    '{"id": "$id", "name": "$name", "created": "2026-01-02T10:00:00Z", '
    '"current": $current}';

String devicesAnswer(List<String> entries) =>
    '{"devices": [${entries.join(", ")}]}';

String codeAnswer(String code, DateTime expires) =>
    '{"code": "$code", "expires": "${expires.toIso8601String()}"}';

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

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> beats(WidgetTester tester, StreamController<void> ticks,
    int count) async {
  for (var beat = 0; beat < count; beat++) {
    ticks.add(null);
    await tester.pump();
  }
  await tester.pumpAndSettle();
}

int devicesRequests(List<http.Request> requests) => requests
    .where((request) => request.url.queryParameters["type"] == "devices")
    .length;

void main() {
  testWidgets(
      'a device that joined by code is an ordinary device: it can be signed out '
      'from here, and this device stays the current one', (tester) async {
    var requests = <http.Request>[];
    var joined = false;
    var removed = false;
    var ticks = await pumpDevices(
      tester,
      MockClient((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["action"] == "device-code") {
          joined = true;
          return json(codeAnswer(
              "ABCD-2345", fixedNow.add(const Duration(minutes: 10))));
        }
        if (query["action"] == "unpair") {
          removed = true;
        }
        return json(devicesAnswer([
          deviceEntry("d1", "Phone", current: true),
          if (joined && !removed) deviceEntry("d2", "Tablet"),
        ]));
      }),
    );

    await tapVisible(tester, find.byKey(addDeviceButtonKey));
    await beats(tester, ticks, 3);
    expect(find.byKey(deviceCodeJoinedKey), findsOneWidget);

    await tester.tap(find.text("Done"));
    await tester.pumpAndSettle();
    expect(find.byKey(deviceCodeDialogKey), findsNothing);
    expect(find.byKey(const Key("device-d2")), findsOneWidget);

    // The newcomer is signed out like any other device of one's own (#55),
    // and the question is not the "this device" one.
    await tapVisible(tester, find.byKey(const Key("device-remove-d2")));
    expect(find.text("Sign out this device?"), findsNothing);
    await tester.tap(find.byKey(const Key("device-confirmed")));
    await tester.pumpAndSettle();

    var unpair = requests
        .where((request) => request.url.queryParameters["action"] == "unpair")
        .single;
    expect(unpair.body, contains('"id":"d2"'));
    expect(unpair.headers["Authorization"], "Bearer $deviceToken");
    expect(find.byKey(const Key("device-d2")), findsNothing);
    expect(find.byKey(const Key("device-d1")), findsOneWidget);
  });

  testWidgets('the polling stops with the dialog', (tester) async {
    var requests = <http.Request>[];
    var ticks = await pumpDevices(
      tester,
      MockClient((request) async {
        requests.add(request);
        if (request.url.queryParameters["action"] == "device-code") {
          return json(codeAnswer(
              "ABCD-2345", fixedNow.add(const Duration(minutes: 10))));
        }
        return json(
            devicesAnswer([deviceEntry("d1", "Phone", current: true)]));
      }),
    );

    await tapVisible(tester, find.byKey(addDeviceButtonKey));
    await beats(tester, ticks, 6);
    var whileOpen = devicesRequests(requests);
    expect(whileOpen, greaterThanOrEqualTo(3),
        reason: "six beats are two polls of the device list");

    await tester.tap(find.text("Done"));
    await tester.pumpAndSettle();
    await beats(tester, ticks, 9);

    expect(devicesRequests(requests), whileOpen,
        reason: "a closed dialog asks the server nothing");
  });

  testWidgets('a new code after the first ran out is a second code',
      (tester) async {
    var requests = <http.Request>[];
    var issued = 0;
    var ticks = await pumpDevices(
      tester,
      MockClient((request) async {
        requests.add(request);
        if (request.url.queryParameters["action"] == "device-code") {
          issued++;
          return json(issued == 1
              ? codeAnswer(
                  "ABCD-2345", fixedNow.subtract(const Duration(seconds: 1)))
              : codeAnswer(
                  "WXYZ-6789", fixedNow.add(const Duration(minutes: 10))));
        }
        return json(
            devicesAnswer([deviceEntry("d1", "Phone", current: true)]));
      }),
    );

    await tapVisible(tester, find.byKey(addDeviceButtonKey));
    expect(
      tester.widget<Text>(find.byKey(deviceCodeRemainingKey)).data,
      "This code has expired.",
    );

    await tester.tap(find.byKey(deviceCodeRenewKey));
    await tester.pumpAndSettle();

    expect(issued, 2);
    expect(
        tester.widget<SelectableText>(find.byKey(deviceCodeKey)).data,
        "WXYZ-6789");
    expect(
      tester.widget<Text>(find.byKey(deviceCodeRemainingKey)).data,
      "Expires in 10:00",
    );
    expect(find.byKey(deviceCodeRenewKey), findsNothing);
    // The old code is nowhere on the screen any more.
    expect(find.text("ABCD-2345"), findsNothing);
    // And the dialog keeps watching for the newcomer with the new code.
    await beats(tester, ticks, 3);
    expect(devicesRequests(requests), greaterThanOrEqualTo(2));
  });
}
