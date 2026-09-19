/// Tests of the app's first screen off the web: "Where is your album?"
/// (issue #91).
///
/// One field that takes a server address **or** a link carrying both a server
/// and a token, and the scanner beside it. Once the server is known, the same
/// sign-in form the server screen and the refusal page build.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/device_code_scanner.dart';
import 'package:valbum_ui/first_screen.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// What a server answers a pairing it accepted.
const String paired = '{"token":"tok-2","deviceName":"Tablet",'
    '"userName":"haui","role":"admin","space":""}';

/// A server that serves its albums to anybody, and pairs whoever asks.
MockClient plainServer({List<http.Request>? requests}) =>
    MockClient((request) async {
      requests?.add(request);
      var query = request.url.queryParameters;
      if (query["action"] == "pair") {
        return http.Response(paired, 200);
      }
      if (query["type"] == "auth") {
        return http.Response(
          '{"mode":"writes","deviceName":"Tablet","writeAllowed":true,'
          '"userName":"haui","role":"admin"}',
          200,
        );
      }
      if (query["type"] == "devices") {
        return http.Response('{"devices":[]}', 200);
      }
      return http.Response(fixture("listing.json"), 200);
    });

/// A server that answers `?type=auth` with an invitation for the one token.
MockClient invitingServer() => MockClient((request) async {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        if (request.headers["authorization"] == "Bearer inv-1") {
          return http.Response(
            '{"mode":"all","writeAllowed":false,"invitation":'
            '{"invitedBy":"haui","role":"edit","note":"Come and look"}}',
            200,
          );
        }
        return http.Response('{"mode":"all","writeAllowed":false}', 200);
      }
      if (query["action"] == "pair") {
        return http.Response(paired, 200);
      }
      return http.Response(fixture("listing.json"), 200);
    });

/// Pumps the app with nothing stored and no platform default: the first
/// screen, exactly as a fresh install off the web shows it.
Future<InMemorySettingsStore> pumpFirst(
  WidgetTester tester,
  MockClient transport, {
  DeviceCodeScanner? scanner,
}) async {
  await tester.binding.setSurfaceSize(const Size(800, 1400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var store = InMemorySettingsStore();
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport,
      ),
      settings: ServerSettings(store: store, platformDefault: () => null),
      deviceCodeScanner: scanner,
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

Future<void> enter(WidgetTester tester, String text) async {
  await tester.enterText(find.byKey(firstScreenFieldKey), text);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('one field takes a plain server address', (tester) async {
    var store = await pumpFirst(tester, plainServer());

    expect(find.text(firstScreenTitle), findsOneWidget);
    expect(find.byKey(firstScreenFieldKey), findsOneWidget);

    await enter(tester, "http://server/valbum/");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));

    // The server is named, and the sign-in follows on the same screen.
    expect(
      find.text("Album server: http://server/valbum/"),
      findsOneWidget,
    );
    expect(find.byKey(deviceCodeFieldKey), findsOneWidget);

    // A server that shows its albums needs no sign-in at all.
    await tapVisible(tester, find.byKey(firstScreenSkipKey));

    expect(store.value, "http://server/valbum/");
    expect(find.text("Test-album"), findsOneWidget);
  });

  testWidgets('the same field signs in with a code', (tester) async {
    var requests = <http.Request>[];
    var store = await pumpFirst(tester, plainServer(requests: requests));

    await enter(tester, "http://server/valbum/");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));
    await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-EFGH");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.byKey(signInButtonKey));

    // The sign-in stores the server as well: a device that just became
    // somebody's at a server belongs at that server (issue #91).
    expect(store.value, "http://server/valbum/");
    expect(store.token, "tok-2");
    expect(
      requests.any((request) =>
          request.url.queryParameters["action"] == "pair" &&
          request.body.contains("ABCD-EFGH")),
      isTrue,
    );
  });

  testWidgets('an invitation link opens the welcome screen', (tester) async {
    await pumpFirst(tester, invitingServer());

    await enter(tester, "http://server/valbum/i/inv-1/");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));

    // What clicking the link in a browser does, see issue #52.
    expect(find.byKey(const Key("invitation-welcome")), findsOneWidget);
    expect(find.textContaining("haui"), findsWidgets);
  });

  testWidgets('a share link is refused here', (tester) async {
    await pumpFirst(tester, plainServer());

    await enter(tester, "http://server/valbum/s/share-1/");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));

    expect(find.byKey(firstScreenProblemKey), findsOneWidget);
    expect(find.text(shareLinkRefusal), findsOneWidget);
    expect(find.byKey(deviceCodeFieldKey), findsNothing);
  });

  testWidgets('what is not a server address is said, not sent',
      (tester) async {
    var requests = <http.Request>[];
    await pumpFirst(tester, plainServer(requests: requests));

    await enter(tester, "not a url");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));

    expect(find.text(firstScreenNoServer), findsOneWidget);
    expect(requests, isEmpty);
  });

  testWidgets('a scanned payload fills the server and the code',
      (tester) async {
    var scanner = FakeDeviceCodeScanner(
      "valbum-device://pair?server=http%3A%2F%2Fserver%2Fvalbum%2F"
      "&code=ABCD2345",
    );
    await pumpFirst(tester, plainServer(), scanner: scanner);

    await tapVisible(tester, find.byKey(firstScreenScanKey));

    expect(
      tester.widget<TextField>(find.byKey(firstScreenFieldKey)).controller
          ?.text,
      "http://server/valbum/",
    );

    await tapVisible(tester, find.byKey(firstScreenContinueKey));

    // The code the camera read is waiting in the sign-in field; nothing was
    // sent until somebody presses the button.
    expect(
      tester.widget<TextField>(find.byKey(deviceCodeFieldKey)).controller
          ?.text,
      "ABCD-2345",
    );
  });

  testWidgets('a scan that is no code says so', (tester) async {
    var scanner = FakeDeviceCodeScanner("https://example.org/");
    await pumpFirst(tester, plainServer(), scanner: scanner);

    await tapVisible(tester, find.byKey(firstScreenScanKey));

    expect(find.text(notADeviceCodeRefusal), findsOneWidget);
  });

  testWidgets('"Another server" goes back to the field', (tester) async {
    await pumpFirst(tester, plainServer());

    await enter(tester, "http://server/valbum/");
    await tapVisible(tester, find.byKey(firstScreenContinueKey));
    await tapVisible(tester, find.byKey(firstScreenChangeKey));

    expect(find.byKey(firstScreenFieldKey), findsOneWidget);
    expect(find.byKey(deviceCodeFieldKey), findsNothing);
  });
}
