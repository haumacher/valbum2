/// Probe for the sign-in of issue #45, composed with what existed before it:
/// the server switch that forgets a token (issue #35), a token the server no
/// longer knows, and the way from the refusal page into the settings.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';

String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

const String authAsHaui = '{"mode":"writes","deviceName":"Kamera",'
    '"writeAllowed":true,"userName":"haui","role":"admin","space":"haui"}';

const String authUnknownDevice = '{"mode":"writes","deviceName":"",'
    '"writeAllowed":false,"userName":"","role":"","space":""}';

const String anonymousListing =
    '["ListingInfo",{"title":"Other library","folders":[]}]';

Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
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
  testWidgets('pointing the app at another server signs this device out there',
      (tester) async {
    var store = InMemorySettingsStore(
        "http://server/valbum/", "tok-1", "Kamera", "haui");
    var settings = ServerSettings(store: store);
    await settings.load();

    var authRequests = <Uri>[];
    await pumpSettings(
      tester,
      settings,
      MockClient((request) async {
        if (request.url.query.contains("type=auth")) {
          authRequests.add(request.url);
          // Only the server that issued the token knows the device.
          return http.Response(
            request.url.host == "server" ? authAsHaui : authUnknownDevice,
            200,
          );
        }
        return http.Response(anonymousListing, 200);
      }),
    );

    expect(find.text("Signed in as haui"), findsOneWidget);
    expect(find.text("Space: haui"), findsOneWidget);
    expect(authRequests.single.host, "server");

    await tester.enterText(
        find.byKey(serverUrlFieldKey), "http://elsewhere/valbum/");
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text("Save"));

    expect(store.token, isNull);
    expect(store.userName, isNull);
    expect(settings.signedIn, isFalse);
    expect(find.text("Not signed in"), findsOneWidget);
    expect(find.text("Signed in as haui"), findsNothing);
    // The sign-in fields are back, ready for the other server.
    expect(find.byKey(userNameFieldKey), findsOneWidget);
    expect(find.widgetWithText(FilledButton, "Sign in"), findsOneWidget);
    // The token of the old server was never sent to the new one.
    expect(authRequests.where((url) => url.host == "elsewhere"), isEmpty);
  });

  testWidgets('a token the server no longer knows asks for a new sign-in',
      (tester) async {
    var store = InMemorySettingsStore(
        "http://server/valbum/", "tok-revoked", "Kamera", "haui");
    var settings = ServerSettings(store: store);
    await settings.load();

    await pumpSettings(
      tester,
      settings,
      MockClient((_) async => http.Response(authUnknownDevice, 200)),
    );

    expect(
      find.text("This server does not know this device. Sign in again."),
      findsOneWidget,
    );
    // The stored identity is still shown from the store, not invented.
    expect(find.text("Role: admin"), findsNothing);
    // Signing in again is possible right here.
    expect(find.widgetWithText(FilledButton, "Sign in"), findsOneWidget);
    expect(find.byKey(pairingSecretFieldKey), findsOneWidget);
  });

  testWidgets('the refusal page of a migrated library leads to the sign-in',
      (tester) async {
    const refused = "This library belongs to its users. Sign in on this "
        "device to see your photos, or open a share link you were given.";
    var store = InMemorySettingsStore("http://server/valbum/");
    var settings = ServerSettings(store: store);
    await settings.load();
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (request.url.query.contains("type=auth")) {
          return http.Response(authUnknownDevice, 200);
        }
        return http.Response(refusal(refused), 401);
      }),
    );

    await tester.binding.setSurfaceSize(const Size(800, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client, settings: settings));
      await tester.pumpAndSettle();
    });

    expect(find.text("Sign-in required"), findsWidgets);
    expect(find.text(refused), findsOneWidget);
    // Nothing on the page speaks of pairing a device any more.
    expect(find.textContaining("Pair"), findsNothing);
    expect(find.textContaining("pairing secret"), findsWidgets);

    await tapVisible(tester, find.text("Server settings..."));

    expect(find.text("Sign in"), findsWidgets);
    expect(find.byKey(userNameFieldKey), findsOneWidget);
    expect(find.byKey(pairingSecretFieldKey), findsOneWidget);
    expect(find.text("Not signed in"), findsOneWidget);
  });
}
