/// "Sign out" in the main menu, and "Not you? Switch person" as a real
/// sign-out of a personal link's browser.
///
/// The author: "maybe worth doing is a 'Logout' entry to the menu". The menu
/// entry runs the very code of the settings screen's sign-out button — one
/// way to sign out, not two that drift apart (decision of 2026-09-13,
/// evening) — and a contact switching person ends the server's session of
/// this browser first, so that no orphan keeps counting as "another browser".
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'move_test.dart' show dataUrl, json, openAlbumMenu, pathOf, treeAnswer;
import 'personal_link_open_test.dart' as personal;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

String deviceEntry(String id, String name, {bool current = false}) =>
    '{"id": "$id", "name": "$name", "created": "2026-01-02T10:00:00Z", '
    '"current": $current}';

final String oneDevice =
    '{"devices": [${deviceEntry("d2", "Phone", current: true)}], '
    '"backupCodeCreated": ""}';

final String twoDevices = '{"devices": ['
    '${deviceEntry("d1", "Desk")}, '
    '${deviceEntry("d2", "Phone", current: true)}], '
    '"backupCodeCreated": ""}';

/// The app over the tree of `move_test.dart`, at [route], on a device that
/// is signed in as carol where [token] is given.
class Harness {
  final requests = <http.Request>[];
  late final InMemorySettingsStore store;
  late final ServerSettings settings;

  Harness({String? token = "dev-2"}) {
    store = token == null
        ? InMemorySettingsStore()
        : InMemorySettingsStore(null, token, "Phone", "carol");
    settings = ServerSettings(store: store, platformDefault: () => dataUrl);
  }

  Future<void> pump(
    WidgetTester tester, {
    String devices = "",
    List<String> route = const [],
  }) async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          var query = request.url.queryParameters;
          if (query["type"] == "devices") {
            return json(devices);
          }
          if (query["type"] == "auth") {
            return json('{"mode": "writes", "writeAllowed": true}');
          }
          return treeAnswer(request);
        })),
      ),
      settings: settings,
      initialRoute: ListingOrAlbumRoute(route),
    ));
    await tester.pumpAndSettle();
  }

  /// The requests for the root listing, in order.
  List<http.Request> get rootGets => [
        for (var request in requests)
          if (request.method == "GET" &&
              request.url.queryParameters["type"] == "json" &&
              pathOf(request) == "/valbum/data/")
            request,
      ];
}

Future<void> tapMenuSignOut(WidgetTester tester) async {
  await openAlbumMenu(tester);
  expect(find.byKey(const Key("sign-out")), findsOneWidget);
  expect(find.text(testL10n.signOut), findsOneWidget);
  // The album's menu is longer than the test screen.
  await tester.ensureVisible(find.byKey(const Key("sign-out")));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("sign-out")));
  await tester.pumpAndSettle();
}

/// The device is signed out, says so, and the root was asked again without
/// the token.
void expectSignedOut(Harness h, int rootGetsBefore) {
  expect(h.settings.signedIn, isFalse);
  expect(h.store.token, isNull);
  expect(
    find.descendant(
      of: find.byType(SnackBar),
      matching: find.text(testL10n.signedOutMessage),
    ),
    findsOneWidget,
  );
  var roots = h.rootGets;
  expect(roots.length, greaterThan(rootGetsBefore),
      reason: "the root is reloaded");
  expect(roots.last.headers.containsKey("Authorization"), isFalse,
      reason: "as the anonymous caller");
}

void main() {
  group('the listing menu of a signed-in device', () {
    testWidgets('asks before signing out the only device', (tester) async {
      await withFakeImageHttp(() async {
        var h = Harness();
        await h.pump(tester, devices: oneDevice);
        var before = h.rootGets.length;

        await tapMenuSignOut(tester);

        expect(find.byKey(const Key("sign-out-confirm")), findsOneWidget);
        expect(find.textContaining("This is your only signed-in device."),
            findsOneWidget);
        expect(h.settings.signedIn, isTrue, reason: "nothing happened yet");

        await tester.tap(find.byKey(const Key("sign-out-confirmed")));
        await tester.pumpAndSettle();

        expectSignedOut(h, before);
        // The anonymous device has nothing to sign out of.
        await openAlbumMenu(tester);
        expect(find.byKey(const Key("sign-out")), findsNothing);
      });
    });

    testWidgets('cancelling keeps the device signed in', (tester) async {
      await withFakeImageHttp(() async {
        var h = Harness();
        await h.pump(tester, devices: oneDevice);

        await tapMenuSignOut(tester);
        await tester.tap(find.text(testL10n.cancel));
        await tester.pumpAndSettle();

        expect(h.settings.signedIn, isTrue);
        expect(h.store.token, "dev-2");
      });
    });

    testWidgets('a second device signs out without a question, as the '
        'settings button does', (tester) async {
      await withFakeImageHttp(() async {
        var h = Harness();
        await h.pump(tester, devices: twoDevices);
        var before = h.rootGets.length;

        await tapMenuSignOut(tester);

        expect(find.byKey(const Key("sign-out-confirm")), findsNothing);
        expectSignedOut(h, before);
      });
    });

    testWidgets('a server that cannot list the devices is asked about as '
        'possibly the last one', (tester) async {
      await withFakeImageHttp(() async {
        var h = Harness();
        // An unreadable answer: the devices cannot be known.
        await h.pump(tester, devices: "not json");

        await tapMenuSignOut(tester);

        expect(find.byKey(const Key("sign-out-confirm")), findsOneWidget);
        expect(find.textContaining(testL10n.maybeLastDeviceWarning),
            findsOneWidget);
      });
    });
  });

  testWidgets('an anonymous device is offered no sign-out', (tester) async {
    await withFakeImageHttp(() async {
      var h = Harness(token: null);
      await h.pump(tester);

      await openAlbumMenu(tester);

      expect(find.byKey(const Key("about")), findsOneWidget);
      expect(find.byKey(const Key("sign-out")), findsNothing);
    });
  });

  testWidgets('the album menu signs out the same way and shows the root',
      (tester) async {
    await withFakeImageHttp(() async {
      var h = Harness();
      await h.pump(tester, devices: oneDevice, route: const ["Inbox"]);
      expect(find.byType(AlbumContent), findsOneWidget);
      var before = h.rootGets.length;

      await tapMenuSignOut(tester);
      expect(find.byKey(const Key("sign-out-confirm")), findsOneWidget);
      await tester.tap(find.byKey(const Key("sign-out-confirmed")));
      await tester.pumpAndSettle();

      expectSignedOut(h, before);
      expect(find.byType(AlbumContent), findsNothing);
    });
  });

  group('Not you? Switch person, in a contact\'s session', () {
    testWidgets('ends this session on the server before forgetting it',
        (tester) async {
      var h = personal.Harness();
      h.store.write(personal.dataUrl, "cred-1", remember: true);
      String? storedWhenAsked;
      await h.pump(
        tester,
        personal.personalServer(
          admitted: "cred-1",
          refusal: () => personal.identifyRefusal(
              methods: const ["mail-code"], masked: const ["p•••@gmx.de"]),
          actions: {
            "end-own-session": (request) {
              storedWhenAsked = h.store.read(personal.dataUrl);
              return personal.json('{"ended": 1}');
            },
          },
        ),
      );
      expect(find.byType(AlbumContent), findsOneWidget);

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      // A contact's session has no device sign-in: one way out, the switch.
      expect(find.byKey(const Key("sign-out")), findsNothing);
      expect(find.text(testL10n.switchPerson), findsOneWidget);
      await tester.tap(find.byKey(const Key("switch-person")));
      await tester.pumpAndSettle();

      var ended = h.lastOf("end-own-session");
      expect(ended.method, "POST");
      expect(ended.headers[VAlbumClient.contactHeader], "cred-1",
          reason: "the session ended is this browser's");
      expect(storedWhenAsked, "cred-1",
          reason: "the server first, then the store");
      expect(h.store.read(personal.dataUrl), isNull);
      expect(find.byKey(const Key("identify-screen")), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('a refusal still switches, and is said', (tester) async {
      var h = personal.Harness();
      h.store.write(personal.dataUrl, "cred-1", remember: true);
      await h.pump(
        tester,
        personal.personalServer(
          admitted: "cred-1",
          refusal: () => personal.identifyRefusal(
              methods: const ["mail-code"], masked: const ["p•••@gmx.de"]),
          actions: {
            "end-own-session": (_) => personal.json(
                '["ErrorInfo", {"message": "The server broke."}]', 500),
          },
        ),
      );

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("switch-person")));
      await tester.pumpAndSettle();

      expect(h.store.read(personal.dataUrl), isNull);
      expect(find.byKey(const Key("identify-screen")), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(SnackBar),
          matching: find.text("The server broke."),
        ),
        findsOneWidget,
      );
    });
  });
}
