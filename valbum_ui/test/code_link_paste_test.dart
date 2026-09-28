/// A pasted code link — the link a code dialog offers beside its QR code —
/// does what the scanned QR code does, wherever a server or a code is typed
/// on the server screen (the author: "wenn ich da den Recovery-Link eingebe
/// funktioniert das überhaupt nicht").
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'first_screen_test.dart' show plainServer;
import 'settings_test.dart' show pumpScreen, settingsWith, enter;
import 'util/fake_image_http.dart';

const String link = "valbum-device://pair?server=http%3A%2F%2Fserver%2Fvalbum%2F"
    "&code=ABCD2345";

String fieldText(WidgetTester tester, Key key) =>
    tester.widget<TextField>(find.byKey(key)).controller?.text ?? "";

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('a code link in the server field fills the server and the code',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var requests = <http.Request>[];
    var store = InMemorySettingsStore();
    await pumpScreen(tester, settingsWith(store, platformDefault: null),
        plainServer(requests: requests));

    await enter(tester, link);

    expect(fieldText(tester, serverUrlFieldKey), "http://server/valbum/");
    expect(fieldText(tester, deviceCodeFieldKey), "ABCD-2345");
    await tapVisible(tester, find.byKey(signInButtonKey));
    expect(store.token, "tok-2");
    expect(
      requests.any((r) =>
          r.url.queryParameters["action"] == "pair" &&
          RegExp("ABCD-?2345").hasMatch(r.body)),
      isTrue,
    );
  });

  testWidgets('a code link of another server in the code field takes it over',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var requests = <http.Request>[];
    var store = InMemorySettingsStore();
    await pumpScreen(tester, settingsWith(store, platformDefault: null),
        plainServer(requests: requests));
    await enter(tester, "http://other/valbum/");

    await tester.enterText(find.byKey(deviceCodeFieldKey), link);
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(signInButtonKey));

    // Like a scan: the server switched, the code waits, nothing was sent.
    expect(fieldText(tester, serverUrlFieldKey), "http://server/valbum/");
    expect(fieldText(tester, deviceCodeFieldKey), "ABCD-2345");
    expect(requests.where((r) => r.url.queryParameters["action"] == "pair"),
        isEmpty);

    await tapVisible(tester, find.byKey(signInButtonKey));
    expect(store.token, "tok-2");
  });
}
