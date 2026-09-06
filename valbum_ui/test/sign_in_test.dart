/// Tests of the sign-in that replaced the pairing of a device (issue #45): the
/// user name on the wire, who the settings say this device is signed in as, the
/// server's own refusals, the sign-in required page and the sign-out.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// The "Sign in" button; the section carries the same title, so the button is
/// addressed by its widget.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  // The screen scrolls; a tall surface builds the sign-in section as well.
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

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  group('signing in', () {
    testWidgets('sends the user name and shows who this device is',
        (tester) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();

      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          requests.add(request);
          return http.Response(
            '{"token":"tok-1","deviceName":"Kamera","userName":"haui",'
            '"role":"admin","space":""}',
            200,
          );
        }),
      );

      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(deviceNameFieldKey), "Kamera");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(requests.single.url.query, "action=pair");
      expect(
        requests.single.body,
        '{"secret":"demo","deviceName":"Kamera","userName":"haui"}',
      );
      expect(store.token, "tok-1");
      expect(store.deviceName, "Kamera");
      expect(store.userName, "haui");

      expect(find.text("Signed in as haui"), findsOneWidget);
      expect(find.text("Role: admin"), findsOneWidget);
      expect(find.text("Device: Kamera"), findsOneWidget);
      expect(find.text("Space: the whole library"), findsOneWidget);
    });

    testWidgets('an owner without a name is the library owner', (tester) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient(
          (_) async => http.Response(
            '{"token":"tok-2","deviceName":"Phone","userName":"",'
            '"role":"admin","space":""}',
            200,
          ),
        ),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text("Signed in as the library owner"), findsOneWidget);
      expect(find.text("Role: admin"), findsOneWidget);
    });

    testWidgets('a refused name shows the server message and stores nothing',
        (tester) async {
      // The server's own words, see AuthService of the image server.
      const refused = "The pairing secret signs in the library owner 'haui'. "
          "Sign in under that name, or ask the owner for an invitation.";
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((_) async => http.Response(refusal(refused), 403)),
      );

      await tester.enterText(find.byKey(userNameFieldKey), "someone");
      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text(refused), findsOneWidget);
      expect(store.token, isNull);
      expect(settings.signedIn, isFalse);
      expect(find.text("Not signed in"), findsOneWidget);
    });

    testWidgets('signing out clears the token and asks again', (tester) async {
      var store = InMemorySettingsStore(
        "http://server/valbum/",
        "tok",
        "Phone",
        "alice",
      );
      var settings = ServerSettings(store: store);
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient(
          (_) async => http.Response(
            '{"mode":"writes","deviceName":"Phone","writeAllowed":true,'
            '"userName":"alice","role":"member","space":"alice"}',
            200,
          ),
        ),
      );

      expect(find.text("Signed in as alice"), findsOneWidget);

      await tapVisible(tester, find.text("Sign out"));

      expect(store.token, isNull);
      expect(store.userName, isNull);
      expect(settings.signedIn, isFalse);
      expect(find.text("Not signed in"), findsOneWidget);
      expect(find.byKey(userNameFieldKey), findsOneWidget);
      expect(find.byKey(pairingSecretFieldKey), findsOneWidget);
    });
  });

  group('the stored sign-in', () {
    testWidgets('shows the user, the role and the space the server reports',
        (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore(
          "http://server/valbum/",
          "tok",
          "Pad",
          "alice",
        ),
      );
      await settings.load();

      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          requests.add(request);
          return http.Response(
            '{"mode":"writes","deviceName":"Pad","writeAllowed":true,'
            '"userName":"alice","role":"member","space":"alice"}',
            200,
          );
        }),
      );

      // Nothing was entered and no button was pressed: opening the settings is
      // enough to see who this device is.
      expect(requests.single.url.query, "type=auth");
      expect(requests.single.headers["Authorization"], "Bearer tok");
      expect(find.text("Signed in as alice"), findsOneWidget);
      expect(find.text("Role: member"), findsOneWidget);
      expect(find.text("Device: Pad"), findsOneWidget);
      expect(find.text("Space: alice"), findsOneWidget);
    });

    testWidgets('a token stored before issue #45 gets its user from the server',
        (tester) async {
      // The store of a device paired when there were no users: a token and a
      // device name, no user name at all.
      var store =
          InMemorySettingsStore("http://server/valbum/", "old", "Phone");
      var settings = ServerSettings(store: store);
      await settings.load();

      expect(settings.token, "old");
      expect(settings.userName, isNull);
      expect(settings.signedIn, isTrue);

      await pumpSettings(
        tester,
        settings,
        MockClient(
          (_) async => http.Response(
            '{"mode":"writes","deviceName":"Phone","writeAllowed":true,'
            '"userName":"haui","role":"admin","space":""}',
            200,
          ),
        ),
      );

      expect(find.text("Signed in as haui"), findsOneWidget);
      expect(find.text("Role: admin"), findsOneWidget);
      expect(find.text("Device: Phone"), findsOneWidget);
      expect(find.text("Space: the whole library"), findsOneWidget);
    });
  });

  group('the sign-in required page', () {
    testWidgets('shows the message of a migrated library verbatim',
        (tester) async {
      // The words a migrated library refuses an anonymous caller with.
      const refused = "This library belongs to its users. Sign in on this "
          "device to see your photos, or open a share link you were given.";
      var client = clientReturning(refusal(refused), status: 401);

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
      });

      expect(find.text("Sign-in required"), findsWidgets);
      expect(find.text(refused), findsOneWidget);
      expect(find.textContaining("Loading failed"), findsNothing);
      expect(find.text("Server settings..."), findsOneWidget);
    });
  });
}
