/// A member's ways to sign in on a new browser besides a code (issue #233):
/// the sign-in form's other ways — an authenticator app, a mailed code, a
/// passkey —, the member's own sign-in options in the devices section, the
/// last-device warning naming them, and a proof on a link that names a
/// member: the browser is signed in as the member.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/passkeys.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/sign_in_form.dart';

import 'passkeys_test.dart' show FakePasskeys;
import 'personal_link_open_test.dart'
    show Harness, identifyRefusal, json, link, personalServer, tapKey;
import 'sign_in_test.dart' show pumpSettings, tapVisible;
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The `?type=auth` of an anonymous caller naming the given ways in.
http.Response authWith(List<String> methods) => json(jsonEncode({
      "mode": "writes",
      "signInMethods": [
        for (var method in methods)
          {"name": method, "label": method.startsWith("oidc:") ? "Google" : ""},
      ],
    }));

/// The pairing answer of a member's sign-in.
http.Response signedIn() => json('{"token": "tok-m", "deviceName": "Laptop", '
    '"userName": "alice", "role": "member", "space": ""}');

void main() {
  late FakePasskeys browser;
  setUp(() {
    browser = FakePasskeys();
    passkeyAuthenticator = browser;
  });
  tearDown(() => passkeyAuthenticator = null);

  group('the sign-in form', () {
    Future<(ServerSettings, InMemorySettingsStore, List<http.Request>)> pump(
        WidgetTester tester,
        http.Response Function(http.Request request) handler) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();
      var requests = <http.Request>[];
      await pumpSettings(tester, settings, MockClient((request) async {
        requests.add(request);
        return handler(request);
      }));
      return (settings, store, requests);
    }

    testWidgets('offers exactly the ways the server names', (tester) async {
      await pump(tester, (request) => authWith(const ["totp", "mail-code"]));
      expect(find.byKey(const Key("signIn.way.totp")), findsNothing,
          reason: "nothing before it is asked");
      await tapVisible(tester, find.byKey(otherWaysKey));

      expect(find.byKey(const Key("signIn.way.totp")), findsOneWidget);
      expect(find.byKey(const Key("signIn.way.email")), findsOneWidget);
      expect(find.byKey(const Key("signIn.way.passkey")), findsNothing,
          reason: "the server offers no passkey");
    });

    testWidgets('offers a passkey only where the server says so',
        (tester) async {
      var (_, store, requests) = await pump(tester, (request) {
        switch (request.url.queryParameters["action"]) {
          case "passkey-start":
            return json('{"ticket": "T1", "options": "{}", '
                '"expires": "2026-10-03T12:05:00Z"}');
          case "pair":
            return signedIn();
        }
        return authWith(const ["passkey"]);
      });
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.passkey")));

      var pair = requests.lastWhere(
          (request) => request.url.queryParameters["action"] == "pair");
      var body = jsonDecode(pair.body) as Map<String, dynamic>;
      expect(body["passkey"]["ticket"], "T1");
      expect(body["deviceCode"], "");
      expect(browser.used, hasLength(1));
      expect(store.token, "tok-m", reason: "stored as a redeemed code is");
    });

    testWidgets('signs in with a name and a code of the authenticator app',
        (tester) async {
      var (_, store, requests) = await pump(
          tester,
          (request) => request.url.queryParameters["action"] == "pair"
              ? signedIn()
              : authWith(const ["totp"]));
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.totp")));
      expect(find.byKey(deviceCodeFieldKey), findsNothing);
      await tester.enterText(find.byKey(memberNameFieldKey), "alice");
      await tester.enterText(find.byKey(memberTotpFieldKey), "123456");
      await tester.enterText(find.byKey(deviceNameFieldKey), "Laptop");
      await tapVisible(tester, find.byKey(signInButtonKey));

      var pair = requests.lastWhere(
          (request) => request.url.queryParameters["action"] == "pair");
      var body = jsonDecode(pair.body) as Map<String, dynamic>;
      expect(body["userName"], "alice");
      expect(body["totpCode"], "123456");
      expect(body["deviceName"], "Laptop");
      expect(store.token, "tok-m");
      expect(store.userName, "alice");
    });

    testWidgets('refuses an incomplete code before sending anything',
        (tester) async {
      var (_, store, requests) = await pump(tester,
          (request) => request.url.queryParameters["action"] == "pair"
              ? signedIn()
              : authWith(const ["totp"]));
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.totp")));
      await tester.enterText(find.byKey(memberNameFieldKey), "nobody");
      await tapVisible(tester, find.byKey(signInButtonKey));
      expect(find.text(testL10n.totpCodeIncomplete), findsOneWidget);

      await tester.enterText(find.byKey(memberTotpFieldKey), "12345");
      await tapVisible(tester, find.byKey(signInButtonKey));
      expect(find.text(testL10n.totpCodeIncomplete), findsOneWidget);
      expect(
          requests.where(
              (request) => request.url.queryParameters["action"] == "pair"),
          isEmpty);

      await tester.enterText(find.byKey(memberNameFieldKey), "");
      await tester.enterText(find.byKey(memberTotpFieldKey), "123 456");
      await tapVisible(tester, find.byKey(signInButtonKey));
      expect(find.text(testL10n.memberNameRequired), findsOneWidget);
      expect(store.token, isNull);
    });

    testWidgets('takes the six digits of a code and no more', (tester) async {
      await pump(tester, (request) => authWith(const ["totp"]));
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.totp")));
      await tester.enterText(find.byKey(memberTotpFieldKey), "123456789012");
      expect(
          tester
              .widget<TextField>(find.byKey(memberTotpFieldKey))
              .controller!
              .text,
          "123456");
      await tester.enterText(find.byKey(memberTotpFieldKey), "654 321");
      expect(
          tester
              .widget<TextField>(find.byKey(memberTotpFieldKey))
              .controller!
              .text,
          "654321",
          reason: "the blank the app shows is no part of the code");
    });

    testWidgets('says a refusal in the server\'s own words', (tester) async {
      const sentence =
          "This code is not right. Enter the code your authenticator app shows now.";
      var (_, store, _) = await pump(
          tester,
          (request) => request.url.queryParameters["action"] == "pair"
              ? json('["ErrorInfo", {"message": "$sentence"}]', 400)
              : authWith(const ["totp"]));
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.totp")));
      await tester.enterText(find.byKey(memberNameFieldKey), "nobody");
      await tester.enterText(find.byKey(memberTotpFieldKey), "000000");
      await tapVisible(tester, find.byKey(signInButtonKey));

      expect(find.text(sentence), findsOneWidget);
      expect(store.token, isNull);
    });

    testWidgets('signs in with a code mailed to the member\'s address',
        (tester) async {
      var (_, store, requests) = await pump(tester, (request) {
        switch (request.url.queryParameters["action"]) {
          case "prove-email":
            return json('{"address": "a•••@web.de", '
                '"expires": "2027-01-01T00:00:00Z", "attempts": 5}');
          case "verify-email":
            return json('{"credential": "", "member": {"token": "tok-m", '
                '"deviceName": "Laptop", "userName": "alice", '
                '"role": "member", "space": ""}}');
        }
        return authWith(const ["mail-code"]);
      });
      await tapVisible(tester, find.byKey(otherWaysKey));
      await tapVisible(tester, find.byKey(const Key("signIn.way.email")));
      await tester.enterText(find.byKey(memberEmailFieldKey), "alice@web.de");
      await tapVisible(tester, find.byKey(signInButtonKey));
      expect(find.text(testL10n.memberCodeSent), findsOneWidget);

      await tester.enterText(find.byKey(memberEmailCodeFieldKey), "654321");
      await tapVisible(tester, find.byKey(signInButtonKey));
      var verify = jsonDecode(requests
          .lastWhere((request) =>
              request.url.queryParameters["action"] == "verify-email")
          .body) as Map<String, dynamic>;
      expect(verify["address"], "alice@web.de");
      expect(verify["code"], "654321");
      expect(
          requests
              .where((request) => request.url.queryParameters
                  .containsKey("action"))
              .every((request) => request.headers["Authorization"] == null),
          isTrue,
          reason: "a sign-in carries no token");
      expect(store.token, "tok-m");
    });
  });

  group('the member\'s own sign-in options', () {
    const String devices = '{"devices": [{"id": "d1", "name": "Phone", '
        '"created": "2026-10-01T10:00:00Z", "current": true}], '
        '"backupCodeCreated": "", '
        '"signIns": {"authenticator": "2026-10-01T10:00:00Z", '
        '"passkeys": [{"id": "pk1", "created": "2026-10-01T10:00:00Z", '
        '"lastUsed": ""}], "passkeysOffered": true, '
        '"addresses": [{"kind": "EMAIL", "value": "alice@web.de", '
        '"proven": true}]}}';

    testWidgets('open the dialog a contact uses, as the member',
        (tester) async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "tok-a",
        httpClient: MockClient((request) async =>
            request.url.queryParameters["type"] == "auth"
                ? authWith(const [])
                : json(devices)),
      );
      await tester.binding.setSurfaceSize(const Size(800, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: DevicesSection(client: client, onSignedOutHere: () async {}),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("settings.signIns.state")), findsOneWidget);
      await tapVisible(tester, find.byKey(memberSignInsKey));
      expect(find.byKey(const Key("sign-in-options-dialog")), findsOneWidget);
      expect(find.text(testL10n.signInOptionsMemberLead), findsOneWidget);
      expect(find.byKey(const Key("sign-in-totp-active")), findsOneWidget);
      expect(find.byKey(const Key("sign-in-passkey-pk1")), findsOneWidget);
      expect(find.byKey(const Key("sign-in-address-alice@web.de")),
          findsOneWidget);
    });

    Future<void> openOptions(WidgetTester tester, String signIns,
        List<String> proofMethods) async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "tok-a",
        httpClient: MockClient((request) async =>
            request.url.queryParameters["type"] == "auth"
                ? json(jsonEncode({
                    "mode": "writes",
                    "proofMethods": [
                      for (var method in proofMethods) {"name": method},
                    ],
                  }))
                : json('{"devices": [], "backupCodeCreated": "", '
                    '"signIns": $signIns}')),
      );
      await tester.binding.setSurfaceSize(const Size(800, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: DevicesSection(client: client, onSignedOutHere: () async {}),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tapVisible(tester, find.byKey(memberSignInsKey));
    }

    testWidgets('hide the addresses where this server proves none',
        (tester) async {
      await openOptions(tester, '{"authenticator": "", "passkeys": [], '
          '"passkeysOffered": false, "addresses": []}', const []);
      expect(find.byKey(const Key("sign-in-options-dialog")), findsOneWidget);
      expect(find.text(testL10n.memberAddressesHeading), findsNothing);
      expect(find.text(testL10n.memberAddressesExplanation), findsNothing);
      expect(find.byKey(const Key("sign-in-address-add")), findsNothing);
    });

    testWidgets('offer an address where the server mails a code',
        (tester) async {
      await openOptions(tester, '{"authenticator": "", "passkeys": [], '
          '"passkeysOffered": false, "addresses": []}', const ["mail-code"]);
      expect(find.text(testL10n.memberAddressesHeading), findsOneWidget);
      expect(find.byKey(const Key("sign-in-address-add")), findsOneWidget);
    });

    testWidgets('keep an address proven before, to remove it',
        (tester) async {
      await openOptions(
          tester,
          '{"authenticator": "", "passkeys": [], "passkeysOffered": false, '
          '"addresses": [{"kind": "EMAIL", "value": "alice@web.de", '
          '"proven": true}]}',
          const []);
      expect(find.text(testL10n.memberAddressesHeading), findsOneWidget);
      expect(find.text(testL10n.memberAddressesExplanation), findsNothing);
      expect(find.byKey(const Key("sign-in-address-remove-alice@web.de")),
          findsOneWidget);
      expect(find.byKey(const Key("sign-in-address-add")), findsNothing);
    });

    test('the last-device warning names them among the ways back', () {
      var l10n = testL10n;
      var warning = lastDeviceWarning(
        l10n,
        isAdmin: false,
        hasBackupCode: false,
        signIns: ContactSignIns(
          authenticator: "2026-10-01T10:00:00Z",
          passkeys: [ContactPasskey(id: "pk1")],
        ),
      );
      expect(warning, contains(l10n.wayAuthenticator));
      expect(warning, contains(l10n.wayPasskey));
      expect(lastDeviceWarning(l10n, isAdmin: false, hasBackupCode: false),
          isNot(contains(l10n.wayAuthenticator)));
    });
  });

  group('a proof on a link that names a member', () {
    testWidgets('signs the browser in as the member and leaves the link',
        (tester) async {
      var h = Harness();
      var settingsStore = InMemorySettingsStore(dataUrl, "", "", "");
      var server = personalServer(
        admitted: "cred-2",
        refusal: () => identifyRefusal(
          masked: const ["a•••@web.de"],
          methods: const ["mail-code"],
        ),
        actions: {
          "prove-email": (_) => json('{"address": "a•••@web.de", '
              '"expires": "2027-01-01T00:00:00Z", "attempts": 5}'),
          "verify-email": (_) => json('{"credential": "", "member": '
              '{"token": "tok-m", "deviceName": "Android phone", '
              '"userName": "alice", "role": "member", "space": ""}}'),
        },
      );
      await tester.pumpWidget(VAlbumApp(
        key: UniqueKey(),
        client: VAlbumClient(
          dataUrl: dataUrl,
          httpClient: MockClient(servingThumbnails((request) async {
            h.requests.add(request);
            return server(request);
          })),
        ),
        settings: ServerSettings(
            store: settingsStore, platformDefault: () => dataUrl),
        session: link,
        contactStore: h.store,
        location: Uri.parse("http://server/valbum/s/tok-42/"),
        rewriteLocation: h.rewritten.add,
        openUrl: h.opened.add,
      ));
      await tester.pumpAndSettle();
      await tapKey(tester, "identify-send-code-1");
      await tester.enterText(find.byKey(const Key("identify-code")), "123456");
      await tapKey(tester, "identify-verify");

      expect(settingsStore.token, "tok-m",
          reason: "the member's device, stored as a redeemed code is");
      expect(settingsStore.userName, "alice");
      expect(h.remembered.values, isEmpty, reason: "no contact credential");
      expect(h.opened, ["http://server/valbum/"],
          reason: "on to the member's own application");
    });
  });
  group('a member\'s sign-in with a provider', () {
    late ContactCredentialStore pendingStore;
    late List<String> left;
    setUp(() {
      pendingStore = ContactCredentialStore.memory();
      var store = memberSignInStore;
      memberSignInStore = pendingStore;
      addTearDown(() => memberSignInStore = store);
      var page = pageLocation;
      pageLocation = () => Uri.parse("http://server/valbum/");
      addTearDown(() => pageLocation = page);
      var leave = leaveForProvider;
      left = [];
      leaveForProvider = left.add;
      addTearDown(() => leaveForProvider = leave);
    });

    /// The application coming back from the provider, its saved server
    /// [saved].
    Future<(InMemorySettingsStore, List<http.Request>)> comeBack(
        WidgetTester tester, String saved) async {
      var settingsStore = InMemorySettingsStore(saved, "tok-old", "Old", "bob");
      var requests = <http.Request>[];
      await tester.pumpWidget(VAlbumApp(
        key: UniqueKey(),
        client: VAlbumClient(
          dataUrl: dataUrl,
          httpClient: MockClient(servingThumbnails((request) async {
            requests.add(request);
            if (request.url.queryParameters["action"] == "oidc-exchange") {
              return json('{"credential": "", "member": {"token": "tok-g", '
                  '"deviceName": "Train", "userName": "alice", '
                  '"role": "member", "space": ""}}');
            }
            return json('["ListingInfo", {"path": "", "title": "", '
                '"parts": []}]');
          })),
        ),
        settings: ServerSettings(
            store: settingsStore, platformDefault: () => dataUrl),
        location: Uri.parse("http://server/valbum/#oidc=code-1"),
        rewriteLocation: (_) {},
      ));
      await tester.pumpAndSettle();
      return (settingsStore, requests);
    }

    testWidgets('finishes at the server it was started at, saved or not',
        (tester) async {
      // Started in the settings at an address that was only typed.
      pendingStore.keepMemberSignIn(const PendingMemberSignIn(
          dataUrl: "http://other/valbum/data",
          binding: "bind-1",
          deviceName: "Train"));
      var (settingsStore, requests) =
          await comeBack(tester, "http://server/valbum/");

      var exchange = requests.firstWhere((request) =>
          request.url.queryParameters["action"] == "oidc-exchange");
      expect(exchange.url.host, "other");
      expect(exchange.headers["Authorization"], isNull,
          reason: "a sign-in carries no token of another server");
      expect(jsonDecode(exchange.body),
          {"code": "code-1", "binding": "bind-1", "deviceName": "Train"});
      expect(settingsStore.value, "http://other/valbum/",
          reason: "the device belongs at the server it signed in at");
      expect(settingsStore.token, "tok-g");
      expect(pendingStore.memberSignIn(), isNull, reason: "used once");
      expect(find.text(testL10n.signInSucceeded), findsOneWidget);
    });

    testWidgets('says so where this tab started nothing', (tester) async {
      var (settingsStore, requests) =
          await comeBack(tester, "http://server/valbum/");
      expect(
          requests.where((request) =>
              request.url.queryParameters["action"] == "oidc-exchange"),
          isEmpty);
      expect(find.text(testL10n.providerReturnUnknown), findsOneWidget);
      expect(settingsStore.token, "tok-old");
    });

    Future<List<http.Request>> startOnForm(
        WidgetTester tester, String returnUrl) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();
      var requests = <http.Request>[];
      await pumpSettings(
          tester,
          settings,
          MockClient((request) async {
            requests.add(request);
            if (request.url.queryParameters["action"] == "oidc-start") {
              return json(jsonEncode({
                "url": "https://accounts.example/auth",
                "binding": "bind-2",
                "expires": "2027-01-01T00:00:00Z",
                "returnUrl": returnUrl,
              }));
            }
            return authWith(const ["oidc:google"]);
          }));
      await tester.enterText(find.byKey(deviceNameFieldKey), "Laptop");
      await tapVisible(tester, find.byKey(otherWaysKey));
      var google = find.byKey(const Key("signIn.way.oidc:google"));
      await tester.ensureVisible(google);
      await tester.tap(google);
      // The page leaves for the provider: the form waits for nothing more.
      for (var n = 0; n < 5; n++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      return requests;
    }

    testWidgets('keeps the server and the device name for the return',
        (tester) async {
      await startOnForm(tester, "http://server/valbum/");
      var pending = pendingStore.memberSignIn()!;
      expect(pending.dataUrl, "http://server/valbum/data");
      expect(pending.binding, "bind-2");
      expect(pending.deviceName, "Laptop");
      expect(pending.adding, isFalse);
      expect(left, ["https://accounts.example/auth"]);
    });

    testWidgets('refuses before leaving where it could not come back',
        (tester) async {
      await startOnForm(tester, "https://photos.example.org/valbum/");
      expect(
          find.text(
              testL10n.providerReturnsElsewhere("https://photos.example.org")),
          findsOneWidget);
      expect(pendingStore.memberSignIn(), isNull);
      expect(left, isEmpty, reason: "the page stays");
    });

    testWidgets('is not offered off the web', (tester) async {
      pageLocation = () => null;
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();
      await pumpSettings(tester, settings,
          MockClient((request) async => authWith(const ["oidc:google", "totp"])));
      await tapVisible(tester, find.byKey(otherWaysKey));
      expect(find.byKey(const Key("signIn.way.totp")), findsOneWidget);
      expect(find.byKey(const Key("signIn.way.oidc:google")), findsNothing);
    });

    testWidgets('on a link, keeps the stored device name for the return',
        (tester) async {
      var h = Harness();
      var server = personalServer(
        admitted: "cred-3",
        refusal: () => identifyRefusal(
            masked: const ["p•••@gmx.de"], methods: const ["oidc:google"]),
        actions: {
          "oidc-start": (_) => json('{"url": "https://accounts.example/auth", '
              '"binding": "bind-1", "expires": "2027-01-01T00:00:00Z", '
              '"returnUrl": "http://server/valbum/s/tok-42/"}'),
        },
      );
      await tester.pumpWidget(VAlbumApp(
        key: UniqueKey(),
        client: VAlbumClient(
          dataUrl: dataUrl,
          httpClient: MockClient(servingThumbnails((request) async {
            h.requests.add(request);
            return server(request);
          })),
        ),
        settings: ServerSettings(
            store: InMemorySettingsStore(dataUrl, "", "Kitchen tablet", ""),
            platformDefault: () => dataUrl),
        session: link,
        contactStore: h.store,
        location: Uri.parse("http://server/valbum/s/tok-42/"),
        rewriteLocation: h.rewritten.add,
        openUrl: h.opened.add,
      ));
      await tester.pumpAndSettle();
      await tapKey(tester, "identify-oidc-google");

      expect(h.opened, ["https://accounts.example/auth"]);
      expect(h.store.pendingSignIn(dataUrl)!.deviceName, "Kitchen tablet",
          reason: "the name a code redemption offers, not 'Unnamed device'");
    });
  });

  group('the administrator\'s view of a member\'s ways to sign in', () {
    const String bob = '{"name": "bob", "role": "member", "devices": 1, '
        '"authenticator": "2026-10-01T10:00:00Z", '
        '"passkeys": [{"id": "pk1", "created": "2026-10-02T10:00:00Z", '
        '"lastUsed": ""}], '
        '"addresses": [{"kind": "EMAIL", "value": "bob@web.de", '
        '"proven": true}]}';
    const String bobWithoutApp = '{"name": "bob", "role": "member", '
        '"devices": 1, "authenticator": "", '
        '"passkeys": [{"id": "pk1", "created": "2026-10-02T10:00:00Z", '
        '"lastUsed": ""}], '
        '"addresses": [{"kind": "EMAIL", "value": "bob@web.de", '
        '"proven": true}]}';

    Future<List<http.Request>> pumpUsers(WidgetTester tester,
        http.Response Function(http.Request request) remove) async {
      var requests = <http.Request>[];
      await tester.binding.setSurfaceSize(const Size(900, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(localizedApp(
        Scaffold(
          body: SingleChildScrollView(
            child: PeopleSection(
              isAdmin: true,
              onInvite: () {},
              client: VAlbumClient(
                dataUrl: "http://server/valbum/data",
                httpClient: MockClient((request) async {
                  requests.add(request);
                  if (request.url.queryParameters["action"] ==
                      "remove-user-sign-in") {
                    return remove(request);
                  }
                  return request.url.queryParameters["type"] == "invitations"
                      ? json('{"invitations": []}')
                      : json('{"users": [$bob]}');
                }),
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return requests;
    }

    testWidgets('lists them and removes one after asking', (tester) async {
      var requests = await pumpUsers(
          tester, (_) => json('{"users": [$bobWithoutApp]}'));
      // The users list says what there is, in the member's details line.
      expect(
          find.textContaining(testL10n.memberSignInsState(
              "${testL10n.authenticatorHeading}, "
              "${testL10n.contactPasskeyCount(1)}, bob@web.de")),
          findsOneWidget);
      await tapKey(tester, "user-sign-ins-bob");

      expect(find.byKey(const Key("user-sign-ins-dialog")), findsOneWidget);
      expect(find.byKey(const Key("user-sign-in-totp")), findsOneWidget);
      expect(find.byKey(const Key("user-sign-in-passkey-pk1")), findsOneWidget);
      expect(find.byKey(const Key("user-sign-in-address-bob@web.de")),
          findsOneWidget);

      await tapKey(tester, "user-sign-in-totp-remove");
      expect(find.byKey(const Key("user-sign-in-remove-confirm")),
          findsOneWidget);
      await tapKey(tester, "user-sign-in-remove-confirmed");

      var removal = requests.lastWhere((request) =>
          request.url.queryParameters["action"] == "remove-user-sign-in");
      expect(jsonDecode(removal.body),
          {"contact": "", "user": "bob", "method": "totp", "id": ""});
      expect(find.byKey(const Key("user-sign-in-totp")), findsNothing);
      expect(find.byKey(const Key("user-sign-in-passkey-pk1")), findsOneWidget);
    });

    testWidgets('says a refusal in the server\'s words', (tester) async {
      const sentence = "There is no such way to sign in (any more).";
      await pumpUsers(tester,
          (_) => json('["ErrorInfo", {"message": "$sentence"}]', 404));
      await tapKey(tester, "user-sign-ins-bob");
      await tapKey(tester, "user-sign-in-passkey-remove-pk1");
      await tapKey(tester, "user-sign-in-remove-confirmed");

      expect(find.byKey(const Key("user-sign-ins-error")), findsOneWidget);
      expect(find.text(sentence), findsOneWidget);
      expect(find.byKey(const Key("user-sign-in-passkey-pk1")), findsOneWidget,
          reason: "nothing changed");
    });
  });
}

const String dataUrl = "http://server/valbum/data";
