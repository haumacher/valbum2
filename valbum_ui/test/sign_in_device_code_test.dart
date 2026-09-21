/// Tests of signing a device in with a device code (issue #65): the second
/// field beside the pairing secret, what travels on the wire, and the two
/// refusals — the one the app makes itself and the one the server speaks.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// The "Sign in" button; the section carries the same title, so the button is
/// addressed by its widget.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

/// The answer of a server that accepted a device code.
const String paired = '{"token":"tok-2","deviceName":"Tablet",'
    '"userName":"haui","role":"admin","space":"haui"}';

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 2800));
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

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// A transport recording every request and answering [answer] to the pairing.
MockClient serverAnswering(
  List<http.Request> requests,
  http.Response Function() answer,
) =>
    MockClient((request) async {
      requests.add(request);
      var query = request.url.queryParameters;
      if (query["action"] == "pair") {
        return answer();
      }
      if (query["type"] == "auth") {
        return http.Response(
          '{"mode":"writes","deviceName":"Tablet","writeAllowed":true,'
          '"userName":"haui","role":"admin","space":"haui"}',
          200,
        );
      }
      if (query["type"] == "devices") {
        return http.Response('{"devices":[]}', 200);
      }
      if (query["type"] == "invitations") {
        return http.Response('{"invitations":[]}', 200);
      }
      return http.Response('{"users":[]}', 200);
    });

void main() {
  testWidgets('signs in with a device code instead of the secret',
      (tester) async {
    var store = InMemorySettingsStore(serverUrl);
    var settings = ServerSettings(store: store);
    await settings.load();

    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      settings,
      serverAnswering(requests, () => http.Response(paired, 200)),
    );

    await tester.enterText(find.byKey(serverUrlFieldKey), serverUrl);
    // Typed in lower case, as it is read off the other screen; the field
    // spells it the way the code is shown.
    await tester.enterText(find.byKey(deviceCodeFieldKey), "abcd-2345");
    await tester.enterText(find.byKey(deviceNameFieldKey), "Tablet");
    await tester.pumpAndSettle();
    await tapVisible(tester, signInButton);

    var pair =
        requests.where((request) => request.url.query == "action=pair").single;
    expect(
      pair.body,
      '{"secret":"","deviceName":"Tablet","userName":"",'
      '"invitation":"","deviceCode":"ABCD-2345"}',
    );
    expect(pair.headers.containsKey("Authorization"), isFalse,
        reason: "A sign-in is how a device gets a token, not what it uses.");

    // Stored exactly as a sign-in with the secret is stored.
    expect(store.token, "tok-2");
    expect(store.deviceName, "Tablet");
    expect(store.userName, "haui");
    expect(find.text("Sign-in succeeded."), findsOneWidget);
  });

  testWidgets('clears the code once it has been used', (tester) async {
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      serverAnswering([], () => http.Response(paired, 200)),
    );

    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
    await tester.pumpAndSettle();
    await tapVisible(tester, signInButton);

    expect(
      tester.widget<TextField>(find.byKey(deviceCodeFieldKey)).controller?.text,
      "",
      reason: "A code works once; it must not linger in the field.",
    );
  });

  testWidgets('refuses an empty code field, before asking anybody',
      (tester) async {
    // There is one way in and it is a code (issue #89); an empty field is
    // said here rather than sent to be refused.
    var store = InMemorySettingsStore(serverUrl);
    var settings = ServerSettings(store: store);
    await settings.load();

    var requests = <http.Request>[];
    await pumpSettings(
      tester,
      settings,
      serverAnswering(requests, () => http.Response(paired, 200)),
    );

    await tester.pumpAndSettle();
    await tapVisible(tester, signInButton);

    expect(
      tester.widget<Text>(find.byKey(signInErrorKey)).data,
      codeRequiredRefusal(testL10n),
    );
    expect(
      requests.where((request) => request.url.query == "action=pair"),
      isEmpty,
      reason: "Nothing is sent while there is nothing to send.",
    );
    expect(store.token, isNull);
  });

  testWidgets('shows the server\'s refusal of a dead code word for word',
      (tester) async {
    var store = InMemorySettingsStore(serverUrl);
    var settings = ServerSettings(store: store);
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      serverAnswering(
        [],
        () => http.Response(refusal("This device code has expired."), 410),
      ),
    );

    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
    await tester.pumpAndSettle();
    await tapVisible(tester, signInButton);

    expect(find.text("This device code has expired."), findsOneWidget);
    expect(store.token, isNull);
  });

  testWidgets('says where the code comes from and never says "invite"',
      (tester) async {
    var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      serverAnswering([], () => http.Response(paired, 200)),
    );

    expect(find.byKey(deviceCodeFieldKey), findsOneWidget);
    expect(
      find.text(
        "From the server's start-up, from My devices on a device you are "
        "already signed in on, from your administrator, or your backup code.",
      ),
      findsOneWidget,
    );
    expect(find.textContaining("invite"), findsNothing);
  });
}
