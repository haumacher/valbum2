/// An authenticator app for a contact of a personal link (issue #208): the
/// card offers a code only where the server says somebody set one up, the
/// setup offers the `otpauth://` link and the setup key on a phone and the QR
/// code on a computer, and the contact's own sign-in options set it up and
/// remove it.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/sign_in_options.dart';

import 'personal_link_open_test.dart'
    show
        Harness,
        credential,
        dataUrl,
        identifyRefusal,
        json,
        personalServer,
        tapKey,
        link;
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

const String uri = "otpauth://totp/Familie:Tante%20Petra?secret=$secret"
    "&issuer=Familie&algorithm=SHA1&digits=6&period=30";

final TotpSetup setup = TotpSetup(
  secret: secret,
  uri: uri,
  issuer: "Familie",
  account: "Tante Petra",
);

/// Pumps the setup at the given screen width.
Future<void> pumpSetup(WidgetTester tester, double width) async {
  tester.view.physicalSize = Size(width, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  var code = TextEditingController();
  addTearDown(code.dispose);
  await tester.pumpWidget(localizedApp(
    Scaffold(
      body: SingleChildScrollView(
        child: TotpSetupView(
          setup: setup,
          code: code,
          busy: false,
          onConfirm: () {},
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

/// The `?type=auth` answer of a contact's session with the given sign-ins.
String authOfContact({
  String authenticator = "",
  bool passkeysOffered = false,
  List<String> passkeys = const [],
}) =>
    jsonEncode({
      "mode": "writes",
      "writeAllowed": false,
      "share": {
        "label": "Summer party",
        "expires": "",
        "rights": [
          {"name": "view"},
        ],
        "path": "~alice/2024/Zoo",
        "type": "PERSONAL",
        "contactHasEmail": true,
        "signIns": {
          "authenticator": authenticator,
          "passkeysOffered": passkeysOffered,
          "passkeys": [
            for (var id in passkeys)
              {"id": id, "created": "2026-10-01T10:00:00Z", "lastUsed": ""},
          ],
        },
        "contact": {"id": "c1", "displayName": "Tante Petra"},
      },
    });

const String zooAlbum =
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"rights": [{"name": "view"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// A contact's session; answers the requests made, and the store.
Future<(List<http.Request>, ContactCredentialStore)> pumpSession(
  WidgetTester tester, {
  String authenticator = "",
  bool passkeysOffered = false,
  List<String> passkeys = const [],
  Map<String, http.Response Function(http.Request)> actions = const {},
  Locale locale = const Locale("en"),
  double width = 1000,
}) async {
  tester.platformDispatcher.localesTestValue = [locale];
  addTearDown(tester.platformDispatcher.clearLocalesTestValue);
  tester.view.physicalSize = Size(width, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  var requests = <http.Request>[];
  var store = ContactCredentialStore.memory()
    ..write(dataUrl, "cred-1", remember: true);
  var client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      var query = request.url.queryParameters;
      var action = query["action"];
      if (action != null) {
        var answer = actions[action];
        return answer == null
            ? json('["ErrorInfo", {"message": "no"}]', 400)
            : answer(request);
      }
      if (query["type"] == "auth") {
        return json(authOfContact(
            authenticator: authenticator,
            passkeysOffered: passkeysOffered,
            passkeys: passkeys));
      }
      return json(zooAlbum);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: ServerSettings(
      store: InMemorySettingsStore(dataUrl, "", "", ""),
      platformDefault: () => dataUrl,
    ),
    session: link,
    contactStore: store,
    location: Uri.parse("http://server/valbum/s/tok-42/"),
    rewriteLocation: (_) {},
    openUrl: (_) {},
    diagnostics: DiagnosticsLog(),
    offlineState: OfflineState(),
  ));
  await tester.pumpAndSettle();
  return (requests, store);
}

Future<void> openSignInOptions(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("sign-in-options")));
  await tester.pumpAndSettle();
}

void main() {
  group('the identification card', () {
    testWidgets('offers no authenticator to a contact who set none up',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(masked: const ["+49•••67"]),
        ),
      );
      expect(find.byKey(const Key("identify-totp")), findsNothing);
      expect(find.byKey(const Key("identify-ask-again")), findsOneWidget);
    });

    testWidgets('signs a recipient in with a code from the app',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
            masked: const ["+49•••67"],
            methods: const ["totp"],
          ),
          actions: {"totp-verify": (_) => credential("cred-2")},
        ),
      );
      expect(find.byKey(const Key("identify-ask-again")), findsNothing);
      expect(find.byKey(const Key("identify-totp-code")), findsNothing,
          reason: "Behind one tap, never the default.");
      await tapKey(tester, "identify-totp");
      // Incomplete: said here and never sent (issue #233).
      await tester.enterText(find.byKey(const Key("identify-totp-code")), "12");
      await tapKey(tester, "identify-totp-verify");
      expect(find.text(testL10n.totpCodeIncomplete), findsOneWidget);
      expect(
          h.requests.where((request) =>
              request.url.queryParameters["action"] == "totp-verify"),
          isEmpty);
      // At most six digits are taken.
      await tester.enterText(
          find.byKey(const Key("identify-totp-code")), "123 456 789");
      expect(
          tester
              .widget<TextField>(find.byKey(const Key("identify-totp-code")))
              .controller!
              .text,
          "123456");
      await tapKey(tester, "identify-totp-verify");

      expect(h.bodyOf("totp-verify"), {
        // The blank the app shows is no part of the code (issue #233).
        "code": "123456",
        "address": "",
        "remember": true,
        "displayName": "",
        // The browser's name, should the code be a member's (issue #233).
        "deviceName": "Android phone",
      });
      expect(h.remembered.values.values, ["cred-2"]);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('on the group link the code comes with the typed address',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
            contact: null,
            group: true,
            methods: const ["mail-code", "totp"],
          ),
          actions: {"totp-verify": (_) => credential("cred-2")},
        ),
      );
      expect(find.byKey(const Key("identify-address")), findsOneWidget,
          reason: "One address field for both ways.");
      await tester.enterText(
          find.byKey(const Key("identify-address")), "petra@gmx.de");
      await tapKey(tester, "identify-totp");
      await tester.enterText(
          find.byKey(const Key("identify-totp-code")), "123456");
      await tapKey(tester, "identify-totp-verify");
      expect(h.bodyOf("totp-verify")["address"], "petra@gmx.de");
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('a wrong code is said in the server\'s words', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
              masked: const ["+49•••67"], methods: const ["totp"]),
          actions: {
            "totp-verify": (_) => json(
                '["ErrorInfo", {"message": "This code is not right."}]', 400),
          },
        ),
      );
      await tapKey(tester, "identify-totp");
      await tester.enterText(
          find.byKey(const Key("identify-totp-code")), "000000");
      await tapKey(tester, "identify-totp-verify");
      expect(find.text("This code is not right."), findsOneWidget);
      expect(h.remembered.values, isEmpty);
    });
  });

  group('the setup', () {
    late List<Uri> opened;
    setUp(() {
      opened = [];
      openAppLink = (url) async {
        opened.add(url);
        return true;
      };
    });

    testWidgets('on a phone offers the link and the setup key, no QR code',
        (tester) async {
      await pumpSetup(tester, 400);
      expect(find.byKey(const Key("totp-qr")), findsNothing);
      expect(find.byKey(const Key("totp-open-app")), findsOneWidget);
      expect(find.byKey(const Key("totp-setup-key")), findsNothing);

      await tapKey(tester, "totp-open-app");
      expect(opened, [Uri.parse(uri)]);

      await tapKey(tester, "totp-show-key");
      var key = tester
          .widget<SelectableText>(find.byKey(const Key("totp-setup-key")));
      expect(key.data, "JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP");
    });

    testWidgets('on a computer shows the QR code with the key below',
        (tester) async {
      await pumpSetup(tester, 1000);
      expect(find.byKey(const Key("totp-qr")), findsOneWidget);
      // An authenticator app's setup, not a device code (issue #233).
      expect(find.bySemanticsLabel(testL10n.totpQrSemantics), findsOneWidget);
      expect(find.bySemanticsLabel(testL10n.deviceCodeQrSemantics), findsNothing);
      expect(find.byKey(const Key("totp-open-app")), findsNothing);
      expect(find.byKey(const Key("totp-setup-key")), findsOneWidget);
      expect(
          tester.getTopLeft(find.byKey(const Key("totp-setup-key"))).dy,
          greaterThan(
              tester.getBottomLeft(find.byKey(const Key("totp-qr"))).dy));
    });

    testWidgets('copies the setup key without its blanks', (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == "Clipboard.setData") {
          copied = (call.arguments as Map)["text"] as String;
        }
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));
      await pumpSetup(tester, 1000);
      await tapKey(tester, "totp-copy-key");
      expect(copied, secret);
    });

    test('groups the setup key by four', () {
      expect(groupedSetupKey("ABCDEFGHIJ"), "ABCD EFGH IJ");
      expect(groupedSetupKey(""), "");
    });
  });

  group('the contact\'s own sign-in options', () {
    testWidgets('set up an authenticator with one code, then remove it',
        (tester) async {
      var (requests, _) = await pumpSession(tester, actions: {
        "totp-setup": (_) => json(jsonEncode({
              "secret": secret,
              "uri": uri,
              "issuer": "Familie",
              "account": "Tante Petra",
            })),
        "totp-confirm": (_) =>
            json('{"authenticator": "2026-10-03T12:00:00Z"}'),
        "remove-sign-in": (_) => json('{"authenticator": ""}'),
      });
      await openSignInOptions(tester);
      expect(find.byKey(const Key("sign-in-options-dialog")), findsOneWidget);
      await tapKey(tester, "sign-in-totp-setup");
      expect(find.byKey(const Key("totp-qr")), findsOneWidget);
      expect(find.byKey(const Key("totp-setup-key")), findsOneWidget);

      await tester.enterText(find.byKey(const Key("totp-code")), "65432");
      await tester.pumpAndSettle();
      expect(
          tester
              .widget<FilledButton>(find.byKey(const Key("totp-confirm")))
              .onPressed,
          isNull,
          reason: "five digits are no code yet");
      await tester.enterText(
          find.byKey(const Key("totp-code")), "654321654321");
      await tester.pumpAndSettle();
      expect(
          tester
              .widget<TextField>(find.byKey(const Key("totp-code")))
              .controller!
              .text,
          "654321",
          reason: "a code has six digits");
      await tapKey(tester, "totp-confirm");
      var confirm = requests.lastWhere(
          (request) => request.url.queryParameters["action"] == "totp-confirm");
      expect(jsonDecode(confirm.body)["code"], "654321");
      expect(confirm.headers[VAlbumClient.contactHeader], "cred-1");
      expect(find.byKey(const Key("sign-in-totp-active")), findsOneWidget);

      await tapKey(tester, "sign-in-totp-remove");
      var remove = requests.lastWhere((request) =>
          request.url.queryParameters["action"] == "remove-sign-in");
      expect(jsonDecode(remove.body)["method"], "totp");
      expect(find.byKey(const Key("sign-in-totp-setup")), findsOneWidget);
    });

    testWidgets('a wrong code is said in the server\'s words', (tester) async {
      await pumpSession(tester, actions: {
        "totp-setup": (_) => json(jsonEncode(
            {"secret": secret, "uri": uri, "issuer": "F", "account": "P"})),
        "totp-confirm": (_) =>
            json('["ErrorInfo", {"message": "This code is not right."}]', 400),
      });
      await openSignInOptions(tester);
      await tapKey(tester, "sign-in-totp-setup");
      await tester.enterText(find.byKey(const Key("totp-code")), "000000");
      await tester.pumpAndSettle();
      await tapKey(tester, "totp-confirm");
      expect(find.text("This code is not right."), findsOneWidget);
      expect(find.byKey(const Key("totp-code")), findsOneWidget);
    });

    testWidgets('are offered once after the first open, until dismissed',
        (tester) async {
      var (_, store) = await pumpSession(tester);
      expect(find.byKey(const Key("sign-in-offer")), findsOneWidget);
      await tapKey(tester, "sign-in-offer-dismiss");
      expect(find.byKey(const Key("sign-in-offer")), findsNothing);
      expect(store.signInOfferDismissed(dataUrl), isTrue);
    });

    testWidgets('are not offered to a contact who has an authenticator',
        (tester) async {
      await pumpSession(tester, authenticator: "2026-10-01T10:00:00Z");
      expect(find.byKey(const Key("sign-in-offer")), findsNothing);
      await openSignInOptions(tester);
      expect(find.byKey(const Key("sign-in-totp-active")), findsOneWidget);
    });

    testWidgets('speak German', (tester) async {
      await pumpSession(tester,
          locale: const Locale("de"),
          width: 400,
          actions: {
            "totp-setup": (_) => json(jsonEncode(
                {"secret": secret, "uri": uri, "issuer": "F", "account": "P"})),
          });
      expect(
          find.text(
              "Möchten Sie auch auf Ihren anderen Geräten erkannt werden?"),
          findsOneWidget);
      await openSignInOptions(tester);
      expect(find.text("Anmeldeoptionen"), findsOneWidget);
      await tapKey(tester, "sign-in-totp-setup");
      expect(find.text("Zur Authenticator-App hinzufügen"), findsOneWidget);
      expect(find.text("Einrichtungsschlüssel anzeigen"), findsOneWidget);
    });
  });
}
