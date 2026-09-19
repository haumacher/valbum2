/// Tests of the name a sign-in gives a user (issues #86, #89).
///
/// A code says whom it signs in, so the app asks for nothing — except where
/// the code's user has **no name yet**: the seat code of a space nobody signed
/// into. A user without a name can be neither credited (attribution falls back
/// to `anonymous`) nor managed (`set-permission` and `remove-user` address
/// users by name), so the server answers `400` with its own sentence, the
/// field appears, and the person chooses their own name.
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
  group('the name a code signs in under', () {
    testWidgets('is not asked for while the server does not ask', (tester) async {
      // A code says whom it signs in; asking would be asking for nothing.
      var store = InMemorySettingsStore(serverUrl);
      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        await freshSettings(store),
        serverAnswering(requests),
      );

      expect(find.byKey(userNameFieldKey), findsNothing);

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.byKey(signInErrorKey), findsNothing);
      var pair = pairsIn(requests).single;
      expect(pair.body, contains('"deviceCode":"ABCD-2345"'));
      expect(pair.body, contains('"userName":""'));
      expect(store.token, "tok-9");
    });

    testWidgets('is asked for when the server says the user has none',
        (tester) async {
      // The seat code of a fresh space: the first answer is the server's
      // sentence, the second press carries the name (issue #89).
      var store = InMemorySettingsStore(serverUrl);
      var requests = <http.Request>[];
      var answered = 0;
      await pumpSettings(
        tester,
        await freshSettings(store),
        serverAnswering(
          requests,
          pair: () => answered++ == 0
              ? http.Response(refusal(nameRequiredMessage), 400)
              : http.Response(paired, 200),
        ),
      );

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      // The server's own words, and the field it asked for.
      expect(
        tester.widget<Text>(find.byKey(signInErrorKey)).data,
        nameRequiredMessage,
      );
      expect(find.byKey(userNameFieldKey), findsOneWidget);
      expect(store.token, isNull);

      await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      var second = pairsIn(requests).last;
      expect(second.body, contains('"userName":"haui"'));
      // The code is still the same one: it was never spent.
      expect(second.body, contains('"deviceCode":"ABCD-2345"'));
      expect(store.token, "tok-9");
      expect(store.userName, "haui");
      expect(find.text("Signed in as haui"), findsOneWidget);
    });

    testWidgets('is explained at the field, in the words of the space model',
        (tester) async {
      var answered = 0;
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering(
          [],
          pair: () => answered++ == 0
              ? http.Response(refusal(nameRequiredMessage), 400)
              : http.Response(paired, 200),
        ),
      );

      // The paragraph above the fields is about the code now.
      expect(find.text(signInCodeExplanation), findsOneWidget);
      expect(find.textContaining("Leave empty"), findsNothing);
      expect(find.textContaining("pairing secret"), findsNothing);

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(
        tester.widget<TextField>(find.byKey(userNameFieldKey)).decoration
            ?.helperText,
        userNameHelp,
      );
    });

    testWidgets('and a refusal that is not about a name is shown as a failure',
        (tester) async {
      await pumpSettings(
        tester,
        await freshSettings(InMemorySettingsStore(serverUrl)),
        serverAnswering(
          [],
          pair: () => http.Response(
            refusal("This device code has expired."),
            410,
          ),
        ),
      );

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text("This device code has expired."), findsOneWidget);
      // No name field: this refusal is not about a name.
      expect(find.byKey(userNameFieldKey), findsNothing);
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

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
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

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-2345");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      expect(find.text("Sign-in succeeded."), findsOneWidget);
      expect(find.text("Signed in as haui"), findsOneWidget);
      expect(find.text("Device: Desk"), findsOneWidget);
      expect(find.text("Space: haui"), findsOneWidget);
    });
  });
}
