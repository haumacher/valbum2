/// Probe for issue #89 (step one): the name prompt composed with a taken name,
/// and a code that is not for a nameless user.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

const String serverUrl = "http://server/valbum/";

final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

const String paired = '{"token":"tok-9","deviceName":"Desk",'
    '"userName":"haui","role":"admin","space":""}';

const String authOfAdmin = '{"mode":"writes","deviceName":"Desk",'
    '"writeAllowed":true,"userName":"haui","role":"admin","space":"",'
    '"clearance":"all","mayShare":true}';

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

Future<void> pumpSettings(WidgetTester tester, http.Client transport) async {
  await tester.binding.setSurfaceSize(const Size(800, 2800));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var settings = ServerSettings(store: InMemorySettingsStore(serverUrl));
  await settings.load();
  await tester.pumpWidget(MaterialApp(
    home: ServerSettingsScreen(
      settings: settings,
      clientFor: (dataUrl) => VAlbumClient(dataUrl: dataUrl, httpClient: transport),
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a taken name keeps the name field and the code, and the next name signs in',
      (tester) async {
    var pairBodies = <String>[];
    var transport = MockClient((request) async {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return http.Response(authOfAdmin, 200);
      }
      if (query["action"] == "pair") {
        pairBodies.add(request.body);
        var named = RegExp(r'"userName":"([^"]*)"').firstMatch(request.body);
        if (named == null || named.group(1)!.isEmpty) {
          return http.Response(refusal(nameRequiredMessage), 400);
        }
        if (request.body.contains('"userName":"taken"')) {
          return http.Response(refusal("The name 'taken' is already in use in this space."), 409);
        }
        return http.Response(paired, 200);
      }
      return http.Response('{"users":[],"devices":[],"invitations":[]}', 200);
    });
    await pumpSettings(tester, transport);

    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
    await tapVisible(tester, signInButton);
    expect(find.byKey(userNameFieldKey), findsOneWidget);

    await tester.enterText(find.byKey(userNameFieldKey), "taken");
    await tapVisible(tester, signInButton);
    // The server's own sentence (as the sign-in outcome), the name field still
    // there, the code still in its field.
    expect(find.textContaining("already in use"), findsOneWidget);
    expect(find.byKey(userNameFieldKey), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(deviceCodeFieldKey)).controller!.text, "ABCD-2345");

    await tester.enterText(find.byKey(userNameFieldKey), "haui");
    await tapVisible(tester, signInButton);
    expect(find.textContaining("already in use"), findsNothing);
    expect(find.textContaining("Sign-in succeeded"), findsOneWidget);
    expect(pairBodies, hasLength(3));
    expect(pairBodies.last, contains('"userName":"haui"'));
    // The code goes as typed; the server accepts the dashed spelling as well.
    expect(pairBodies.last, contains('"deviceCode":"ABCD-2345"'));
    // The retired field is never sent.
    expect(pairBodies.last, contains('"secret":""'));
  });
}
