/// Tests of the sign-in form on the page a server refuses an anonymous caller
/// with (issue #91).
///
/// A browser knows its server, so the refusal page is where the sign-in
/// belongs: a code, a device name, and the album behind it. The name field
/// appears exactly where the server asks for one.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/sign_in_form.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The body a refusing server answers with.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// What a server answers a pairing it accepted.
const String paired = '{"token":"tok-2","deviceName":"Tablet",'
    '"userName":"haui","role":"admin","space":""}';

/// A transport that refuses everything until a pairing succeeds.
///
/// Exactly what a fresh `--auth all` server does: `401` to the listing while
/// nobody is signed in, the album once the request carries the token.
MockClient refusingUntilPaired({
  required List<http.Request> requests,
  bool askForAName = false,
}) {
  var namedYet = !askForAName;
  return MockClient((request) async {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["action"] == "pair") {
      if (!namedYet && !request.body.contains('"userName":"haui"')) {
        namedYet = true;
        return http.Response(refusal(nameRequiredMessage), 400);
      }
      return http.Response(paired, 200);
    }
    if (query["type"] == "auth") {
      if (request.headers["authorization"] == "Bearer tok-2") {
        return http.Response(
          '{"mode":"all","deviceName":"Tablet","writeAllowed":true,'
          '"userName":"haui","role":"admin"}',
          200,
        );
      }
      return http.Response('{"mode":"all","writeAllowed":false}', 200);
    }
    if (request.headers["authorization"] == "Bearer tok-2") {
      return http.Response(fixture("listing.json"), 200);
    }
    return http.Response(refusal("This server shows nothing to strangers."),
        401);
  });
}

/// Pumps the app against a server that refuses it.
Future<InMemorySettingsStore> pumpRefused(
  WidgetTester tester,
  MockClient transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 1400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var store = InMemorySettingsStore("http://server/valbum/");
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport,
      ),
      settings: ServerSettings(store: store, platformDefault: () => null),
    ));
    await tester.pumpAndSettle();
  });
  return store;
}

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('the refusal page is the sign-in form', (tester) async {
    var requests = <http.Request>[];
    var store = await pumpRefused(
      tester,
      refusingUntilPaired(requests: requests),
    );

    // The server's own sentence, and the form right under it: no detour
    // through a settings screen (issue #91).
    expect(
      find.textContaining("This server shows nothing to strangers."),
      findsOneWidget,
    );
    expect(find.byKey(signInRequiredFormKey), findsOneWidget);
    expect(find.byKey(deviceCodeFieldKey), findsOneWidget);
    expect(find.byKey(deviceNameFieldKey), findsOneWidget);
    // Nothing is asked for that the code does not need.
    expect(find.byKey(userNameFieldKey), findsNothing);

    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-EFGH");
    await tester.enterText(find.byKey(deviceNameFieldKey), "Tablet");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(signInButtonKey));

    expect(store.token, "tok-2");
    // And the album the page stood in front of.
    expect(find.text("Test-album"), findsOneWidget);
    expect(
      requests
          .where((request) =>
              request.url.queryParameters["action"] == "pair" &&
              request.body.contains("ABCD-EFGH"))
          .length,
      1,
    );
  });

  testWidgets('the name field appears where the server asks for a name',
      (tester) async {
    var requests = <http.Request>[];
    var store = await pumpRefused(
      tester,
      refusingUntilPaired(requests: requests, askForAName: true),
    );

    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-EFGH");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(signInButtonKey));

    // The server's own sentence, word for word, and the field it asks for.
    expect(find.text(nameRequiredMessage), findsOneWidget);
    expect(find.byKey(userNameFieldKey), findsOneWidget);
    expect(store.token, isNull);
    // The code stays: it is still good, and the next press sends it with the
    // name.
    expect(
      (tester.widget<TextField>(find.byKey(deviceCodeFieldKey)))
          .controller
          ?.text,
      "ABCD-EFGH",
    );

    await tester.enterText(find.byKey(userNameFieldKey), "haui");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(signInButtonKey));

    expect(store.token, "tok-2");
    expect(find.text("Test-album"), findsOneWidget);
  });

  testWidgets('an empty code is refused before anything is sent',
      (tester) async {
    var requests = <http.Request>[];
    await pumpRefused(tester, refusingUntilPaired(requests: requests));

    await tapVisible(tester, find.byKey(signInButtonKey));

    expect(find.text(codeRequiredRefusal(testL10n)), findsOneWidget);
    expect(
      requests.where((r) => r.url.queryParameters["action"] == "pair"),
      isEmpty,
    );
  });

  testWidgets('the way to the server screen stays', (tester) async {
    await pumpRefused(tester, refusingUntilPaired(requests: []));

    await tapVisible(tester, find.text("Server settings..."));

    expect(find.text("Album server"), findsOneWidget);
  });
}
