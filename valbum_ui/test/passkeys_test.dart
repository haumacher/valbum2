/// Passkeys for a contact of a personal link (issue #204): the card offers
/// "Sign in with passkey" only where the server names the method and the
/// browser has passkeys, a declined passkey is said in the app's words, and
/// the contact's sign-in options add and remove passkeys. The browser is a
/// fake [PasskeyAuthenticator]; the real one is probed with Chrome's virtual
/// authenticator.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/passkeys.dart';

import 'authenticator_test.dart' show openSignInOptions;
import 'personal_link_open_test.dart'
    show Harness, credential, identifyRefusal, json, personalServer, tapKey;
import 'authenticator_test.dart' as session show pumpSession;
import 'util/l10n.dart';

/// A browser whose passkeys answer what the test says.
class FakePasskeys extends PasskeyAuthenticator {
  final created = <String>[];
  final used = <String>[];

  /// What a ceremony throws instead of answering, `null` for an answer.
  Exception? failure;

  @override
  Future<String> create(String options) async {
    created.add(options);
    var stop = failure;
    if (stop != null) {
      throw stop;
    }
    return '{"id": "pk-new", "type": "public-key"}';
  }

  @override
  Future<String> get(String options) async {
    used.add(options);
    var stop = failure;
    if (stop != null) {
      throw stop;
    }
    return '{"id": "pk-1", "type": "public-key"}';
  }
}

const String signInOptions =
    '{"ticket": "T1", "options": "{\\"challenge\\": \\"abc\\"}", '
    '"expires": "2026-10-03T12:05:00Z"}';

void main() {
  late FakePasskeys browser;
  setUp(() {
    browser = FakePasskeys();
    passkeyAuthenticator = browser;
  });
  tearDown(() => passkeyAuthenticator = null);

  group('the identification card', () {
    testWidgets('signs in with a passkey where somebody made one',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
            masked: const ["+49•••67"],
            methods: const ["passkey"],
          ),
          actions: {
            "passkey-start": (_) => json(signInOptions),
            "passkey-verify": (_) => credential("cred-2"),
          },
        ),
      );
      await tapKey(tester, "identify-passkey");

      expect(browser.used, ['{"challenge": "abc"}']);
      var verify = h.bodyOf("passkey-verify");
      expect(verify["ticket"], "T1");
      expect(verify["response"], '{"id": "pk-1", "type": "public-key"}');
      expect(verify["remember"], isTrue);
      expect(h.remembered.values.values, ["cred-2"]);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('offers no passkey the server does not name', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(masked: const ["+49•••67"]),
        ),
      );
      expect(find.byKey(const Key("identify-passkey")), findsNothing);
      expect(find.byKey(const Key("identify-ask-again")), findsOneWidget);
    });

    testWidgets('offers no passkey in a browser that has none', (tester) async {
      passkeyAuthenticator = null;
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
              masked: const ["+49•••67"], methods: const ["passkey"]),
        ),
      );
      expect(find.byKey(const Key("identify-passkey")), findsNothing);
      expect(find.byKey(const Key("identify-ask-again")), findsOneWidget);
    });

    testWidgets('a declined passkey is said, and nothing is verified',
        (tester) async {
      browser.failure = const PasskeyCancelled();
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
              masked: const ["+49•••67"], methods: const ["passkey"]),
          actions: {"passkey-start": (_) => json(signInOptions)},
        ),
      );
      await tapKey(tester, "identify-passkey");
      expect(find.text(testL10n.passkeyCancelled), findsOneWidget);
      expect(
          h.requests.where((request) =>
              request.url.queryParameters["action"] == "passkey-verify"),
          isEmpty);
    });

    testWidgets('a refused passkey is said in the server\'s words',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
              contact: null, group: true, methods: const ["passkey"]),
          actions: {
            "passkey-start": (_) => json(signInOptions),
            "passkey-verify": (_) => json(
                '["ErrorInfo", {"message": "This passkey does not open this link."}]',
                403),
          },
        ),
      );
      await tapKey(tester, "identify-passkey");
      expect(
          find.text("This passkey does not open this link."), findsOneWidget);
      expect(h.remembered.values, isEmpty);
    });
  });

  group('the contact\'s own sign-in options', () {
    String signIns(List<String> ids) => jsonEncode({
          "authenticator": "",
          "passkeysOffered": true,
          "passkeys": [
            for (var id in ids)
              {"id": id, "created": "2026-10-03T12:00:00Z", "lastUsed": ""},
          ],
        });

    testWidgets('add a passkey and remove it', (tester) async {
      var (requests, _) = await session.pumpSession(
        tester,
        passkeysOffered: true,
        actions: {
          "passkey-register-start": (_) => json(signInOptions),
          "passkey-register": (_) => json(signIns(const ["pk-new"])),
          "remove-sign-in": (_) => json(signIns(const [])),
        },
      );
      await openSignInOptions(tester);
      expect(find.text(testL10n.passkeyExplanation), findsOneWidget);
      await tapKey(tester, "sign-in-passkey-add");

      expect(browser.created, ['{"challenge": "abc"}']);
      var register = requests.lastWhere((request) =>
          request.url.queryParameters["action"] == "passkey-register");
      var body = jsonDecode(register.body) as Map<String, dynamic>;
      expect(body["ticket"], "T1");
      expect(body["response"], '{"id": "pk-new", "type": "public-key"}');
      expect(find.byKey(const Key("sign-in-passkey-pk-new")), findsOneWidget);

      await tapKey(tester, "sign-in-passkey-remove-pk-new");
      var remove = requests.lastWhere((request) =>
          request.url.queryParameters["action"] == "remove-sign-in");
      expect(jsonDecode(remove.body)["method"], "passkey");
      expect(jsonDecode(remove.body)["id"], "pk-new");
      expect(find.byKey(const Key("sign-in-passkey-pk-new")), findsNothing);
    });

    testWidgets('offer no passkey where the server has no public address',
        (tester) async {
      await session.pumpSession(tester);
      await openSignInOptions(tester);
      expect(find.byKey(const Key("sign-in-passkey-add")), findsNothing);
      expect(find.byKey(const Key("sign-in-totp-setup")), findsOneWidget);
    });

    testWidgets(
        'are not offered after the first open to a contact with a '
        'passkey', (tester) async {
      await session
          .pumpSession(tester, passkeysOffered: true, passkeys: const ["pk-1"]);
      expect(find.byKey(const Key("sign-in-offer")), findsNothing);
    });
  });
}
