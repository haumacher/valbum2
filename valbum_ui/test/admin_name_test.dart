/// Tests of the two things the real-browser check of the space model found
/// (issue #86).
///
/// The secret creates the **administrator of a space**, one user among
/// several — not "the library owner" of a library that had exactly one. A user
/// without a name can be neither credited (attribution falls back to
/// `anonymous`) nor managed (`set-permission` and `remove-user` address users
/// by name), so the name is required before anything is sent.
///
/// And the signed-in block is filled from one `?type=auth` right after the
/// pairing, because the pairing answer carries no space, no clearance and no
/// share flag: the block used to be half empty until the caller scope asked
/// the same question a moment later.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// The "Sign in" button; the section carries the same title.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

/// What the server answers a device that paired, without a space of its own:
/// the pairing answer names the user, the device and the role, and no more.
const String paired = '{"token":"tok-9","deviceName":"Desk",'
    '"userName":"haui","role":"admin","space":"haui"}';

/// What `?type=auth` answers the freshly paired device.
const String authOfAdmin = '{"mode":"writes","deviceName":"Desk",'
    '"writeAllowed":true,"userName":"haui","role":"admin","space":"haui",'
    '"clearance":"all","mayShare":true}';

/// An `ErrorInfo` body.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// A transport recording every request, answering the pairing and the auth
/// question separately.
MockClient serverAnswering(
  List<http.Request> requests, {
  http.Response Function()? pair,
  String auth = authOfAdmin,
}) =>
    MockClient((request) async {
      requests.add(request);
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return http.Response(auth, 200);
      }
      if (query["action"] == "pair") {
        return (pair ?? () => http.Response(paired, 200))();
      }
      if (query["type"] == "devices") {
        return http.Response('{"devices":[]}', 200);
      }
      if (query["type"] == "invitations") {
        return http.Response('{"invitations":[]}', 200);
      }
      return http.Response('{"users":[]}', 200);
    });

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
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// Settings of a device that is not signed in anywhere.
Future<ServerSettings> freshSettings(InMemorySettingsStore store) async {
  var settings = ServerSettings(store: store);
  await settings.load();
  return settings;
}

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The pairing requests among [requests].
Iterable<http.Request> pairsIn(List<http.Request> requests) =>
    requests.where((r) => r.url.queryParameters["action"] == "pair");

void main() {
  group('the name the secret signs in under', () {
    testWidgets('is required, and said before anything is sent',
        (tester) async {
      var store = InMemorySettingsStore(serverUrl);
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(store),
        serverAnswering(requests),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(
        tester.widget<Text>(find.byKey(signInErrorKey)).data,
        "Enter your name.",
      );
      expect(pairsIn(requests), isEmpty);
      expect(store.token, isNull);
    });

    testWidgets('goes through once it is there', (tester) async {
      var store = InMemorySettingsStore(serverUrl);
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(store),
        serverAnswering(requests),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.byKey(signInErrorKey), findsNothing);
      expect(pairsIn(requests).single.body, contains('"userName":"haui"'));
      expect(store.token, "tok-9");
    });

    testWidgets('is not required for a device code, which needs none',
        (tester) async {
      // A code adds a further device of the user who issued it: there is
      // nobody to name, see issue #65.
      var store = InMemorySettingsStore(serverUrl);
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(store),
        serverAnswering(requests),
      );

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.byKey(signInErrorKey), findsNothing);
      var pair = pairsIn(requests).single;
      expect(pair.body, contains('"deviceCode":"ABCD-2345"'));
      expect(pair.body, contains('"userName":""'));
      expect(store.token, "tok-9");
    });

    testWidgets('is explained at the field, in the words of the space model',
        (tester) async {
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering([]),
      );

      expect(
        tester.widget<TextField>(find.byKey(userNameFieldKey)).decoration
            ?.helperText,
        "Your name in this space; the first sign-in with the secret names the "
        "administrator.",
      );
      // And the paragraph above it no longer promises a nameless owner.
      expect(find.textContaining("Leave empty"), findsNothing);
      expect(find.textContaining("library owner"), findsNothing);
      expect(
        find.text("The pairing secret, which the server prints at start-up, "
            "signs in the administrator of this space. The first sign-in with "
            "it names that administrator."),
        findsOneWidget,
      );
    });

    testWidgets('and the server\'s own refusal is shown where it comes',
        (tester) async {
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering(
          requests,
          pair: () => http.Response(
            refusal("The first administrator of a space must have a name."),
            400,
          ),
        ),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(userNameFieldKey), " ");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      // A name of blanks is no name here either, so nothing is sent — the
      // sentence the server would answer is shown where it does arrive, see
      // the next test.
      expect(
        tester.widget<Text>(find.byKey(signInErrorKey)).data,
        "Enter your name.",
      );
      expect(pairsIn(requests), isEmpty);
    });

    testWidgets('a server that refuses the name says so, word for word',
        (tester) async {
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering(
          [],
          pair: () => http.Response(
            refusal("The first administrator of a space must have a name."),
            400,
          ),
        ),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(
        find.text("The first administrator of a space must have a name."),
        findsOneWidget,
      );
    });
  });

  group('the signed-in block right after a pairing', () {
    testWidgets('shows the space and the permission of the auth answer',
        (tester) async {
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering(requests),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text("Signed in as haui"), findsOneWidget);
      expect(find.text("Device: Desk"), findsOneWidget);
      // The two the pairing answer does not carry.
      expect(find.text("Space: haui"), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(permissionLineKey)).data,
        "You manage this server; you see all images; you may share links.",
      );
      // Asked with the token the pairing answered, at the server in the field.
      var auth = requests
          .where((r) => r.url.queryParameters["type"] == "auth")
          .first;
      expect(auth.headers["Authorization"], "Bearer tok-9");
    });

    testWidgets('falls back to the pairing answer where auth cannot be asked',
        (tester) async {
      // A server that answers the pairing and nothing else: the block shows
      // what the pairing said, which is what it showed before issue #86.
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        MockClient((request) async {
          if (request.url.queryParameters["action"] == "pair") {
            return http.Response(paired, 200);
          }
          return http.Response(refusal("nope"), 500);
        }),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text("Sign-in succeeded."), findsOneWidget);
      expect(find.text("Signed in as haui"), findsOneWidget);
      expect(find.text("Device: Desk"), findsOneWidget);
      expect(find.text("Space: haui"), findsOneWidget);
    });
  });
}
