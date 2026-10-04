/// Opening a personal share link in a browser (issue #202): the first open
/// with the name and the notice, "Remember me on this device", the card of a
/// later open with the ways the server offers, the mailed code, the sign-in
/// through a provider, "Not you? Switch person" — and the group link of
/// issue #211.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

const SessionUrl link = SessionUrl(
  kind: SessionKind.share,
  token: "tok-42",
  dataUrl: dataUrl,
  basePath: "/valbum/s/tok-42/",
);

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a contact's session.
const String authOfContact = '{"mode": "writes", "writeAllowed": false, '
    '"share": {"label": "Summer party", "expires": "", '
    '"rights": [{"name": "view"}], "path": "~alice/2024/Zoo", '
    '"type": "PERSONAL", '
    '"contact": {"id": "c1", "displayName": "Tante Petra"}}}';

const String zooAlbum =
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"rights": [{"name": "view"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// The refusal of a personal link that does not know who is asking.
http.Response identifyRefusal({
  int status = 401,
  String message = "This link asks who you are.",
  bool firstOpen = false,
  String? contact = "Tante Petra",
  List<String> masked = const [],
  List<String> methods = const [],
  String sharedBy = "Alice",
  bool group = false,
  String title = "Radtour nach Rom",
}) {
  var identify = {
    "firstOpen": firstOpen,
    if (contact != null) "contact": {"id": "c1", "displayName": contact},
    "addresses": [
      for (var address in masked)
        {
          "kind": address.contains("@") ? "EMAIL" : "PHONE",
          "masked": address,
        },
    ],
    "methods": [
      for (var method in methods)
        {
          "name": method,
          "label": method.startsWith("oidc:") ? "Google" : "",
        },
    ],
    // The link's label is its maker's; a server never sends it any more, and
    // an older one that does is not shown (the title is).
    "label": "Summer party",
    "title": title,
    "sharedBy": sharedBy,
    if (group) "group": true,
  };
  return json(
    jsonEncode([
      "ErrorInfo",
      {"message": message, "identify": identify},
    ]),
    status,
  );
}

/// The answer of a request answering a credential.
http.Response credential(String value, {bool remember = true}) =>
    json('{"credential": "$value", "expires": "2027-01-01T00:00:00Z", '
        '"remember": $remember, '
        '"contact": {"id": "c1", "displayName": "Tante Petra"}}');

/// A server of one personal link: the album opens for [admitted] alone, and
/// everybody else gets [refusal].
http.Response Function(http.Request) personalServer({
  required String admitted,
  required http.Response Function() refusal,
  Map<String, http.Response Function(http.Request)> actions = const {},
}) =>
    (request) {
      var action = request.url.queryParameters["action"];
      if (action != null) {
        var answer = actions[action];
        return answer == null
            ? json('["ErrorInfo", {"message": "no"}]', 400)
            : answer(request);
      }
      if (request.headers[VAlbumClient.contactHeader] != admitted) {
        return refusal();
      }
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfContact);
      }
      return json(zooAlbum);
    };

class Harness {
  final requests = <http.Request>[];
  final store = ContactCredentialStore.memory();
  final opened = <String>[];
  final rewritten = <Uri>[];
  final log = DiagnosticsLog();
  final offline = OfflineState();

  MemoryStorageArea get remembered => store.remembered as MemoryStorageArea;
  MemoryStorageArea get session => store.session as MemoryStorageArea;

  Future<void> pump(
    WidgetTester tester,
    http.Response Function(http.Request) handler, {
    Uri? location,
    Locale locale = const Locale("en"),
  }) async {
    tester.platformDispatcher.localesTestValue = [locale];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );
    await tester.pumpWidget(VAlbumApp(
      // A fresh app per pump: a return from the provider is a page load.
      key: UniqueKey(),
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(dataUrl, "", "", ""),
        platformDefault: () => dataUrl,
      ),
      session: link,
      contactStore: store,
      location: location ?? Uri.parse("http://server/valbum/s/tok-42/"),
      rewriteLocation: rewritten.add,
      openUrl: opened.add,
      diagnostics: log,
      offlineState: offline,
    ));
    await tester.pumpAndSettle();
  }

  http.Request lastOf(String action) => requests
      .lastWhere((request) => request.url.queryParameters["action"] == action);

  Map<String, dynamic> bodyOf(String action) =>
      jsonDecode(lastOf(action).body) as Map<String, dynamic>;
}

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

/// The keys of the card's ways in, top to bottom.
List<String> offeredWays(WidgetTester tester) {
  var keys = <(double, String)>[];
  for (var element in find
      .byWidgetPredicate((widget) =>
          widget.key is ValueKey<String> &&
          RegExp(r"^identify-(send-code|oidc-|address|ask-again)")
              .hasMatch((widget.key as ValueKey<String>).value))
      .evaluate()) {
    var key = (element.widget.key as ValueKey<String>).value;
    keys.add((tester.getTopLeft(find.byKey(Key(key))).dy, key));
  }
  keys.sort((a, b) => a.$1.compareTo(b.$1));
  return [for (var (_, key) in keys) key];
}

void main() {
  group('the first open of a recipient\'s own link', () {
    testWidgets('names an untitled album by the fallback, never the label',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(firstOpen: true, title: ""),
        ),
      );

      var heading = tester.widget<Text>(find.byKey(const Key("identify-label")));
      expect(heading.data, testL10n.sharedAlbumFallback);
      expect(find.text("Summer party"), findsNothing);
    });

    testWidgets('shows the name and the notice, and remembers the credential',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(firstOpen: true),
          actions: {"identify": (_) => credential("cred-1")},
        ),
      );

      expect(find.byKey(const Key("identify-screen")), findsOneWidget);
      expect(find.text("Radtour nach Rom"), findsOneWidget);
      expect(find.text("Summer party"), findsNothing,
          reason: "the label is private to the link's maker");
      expect(find.text(testL10n.identifySharedBy("Alice")), findsOneWidget);
      expect(find.text(testL10n.identifyNotice("Alice")), findsOneWidget);
      var name =
          tester.widget<TextField>(find.byKey(const Key("identify-name")));
      expect(name.controller!.text, "Tante Petra");
      var remember = tester
          .widget<CheckboxListTile>(find.byKey(const Key("identify-remember")));
      expect(remember.value, isTrue, reason: "ticked by default");

      await tester.enterText(find.byKey(const Key("identify-name")), "Petra");
      await tapKey(tester, "identify-continue");

      expect(h.bodyOf("identify"), {"remember": true, "displayName": "Petra"});
      expect(h.remembered.values.values, ["cred-1"]);
      expect(h.session.values, isEmpty);
      // The album, as that contact.
      expect(find.byType(AlbumContent), findsOneWidget);
      var album = h.requests.lastWhere(
          (request) => request.url.queryParameters["type"] == "json");
      expect(album.headers[VAlbumClient.contactHeader], "cred-1");
      expect(album.headers["Authorization"], "Bearer tok-42");
      // Never in an address, never in a log line.
      for (var request in h.requests) {
        expect(request.url.toString(), isNot(contains("cred-1")));
      }
      for (var entry in h.log.entries) {
        expect("${entry.headline} ${entry.facts} ${entry.cause}",
            isNot(contains("cred-1")));
      }
    });

    testWidgets('unticked, keeps the credential for this tab alone',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(firstOpen: true),
          actions: {"identify": (_) => credential("cred-1", remember: false)},
        ),
      );
      await tapKey(tester, "identify-remember");
      await tapKey(tester, "identify-continue");

      expect(h.bodyOf("identify")["remember"], isFalse);
      expect(h.session.values.values, ["cred-1"]);
      expect(h.remembered.values, isEmpty);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('a refusal is said in the server\'s own words', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(firstOpen: true),
          actions: {
            "identify": (_) => json(
                '["ErrorInfo", {"message": "This contact is shut out."}]', 410),
          },
        ),
      );
      await tapKey(tester, "identify-continue");

      expect(find.text("This contact is shut out."), findsOneWidget);
      expect(h.remembered.values, isEmpty);
    });

    testWidgets('is refused offline with the usual reason', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(firstOpen: true),
          actions: {"identify": (_) => credential("cred-1")},
        ),
      );
      h.offline.goneOffline(null);
      await tester.pumpAndSettle();
      await tapKey(tester, "identify-continue");

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(
          h.requests.where(
              (request) => request.url.queryParameters["action"] == "identify"),
          isEmpty);
    });
  });

  group('a later open in a browser that does not know the visitor', () {
    Future<List<String>> waysFor(
        WidgetTester tester, List<String> methods) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(
            masked: const ["p•••@gmx.de", "+49•••67"],
            methods: methods,
          ),
        ),
      );
      expect(find.text(testL10n.identifyWhoTitle), findsOneWidget);
      return offeredWays(tester);
    }

    testWidgets('offers the mailed code alone', (tester) async {
      expect(
          await waysFor(tester, const ["mail-code"]), ["identify-send-code-1"]);
      expect(find.text(testL10n.identifySendCodeTo("p•••@gmx.de")),
          findsOneWidget);
    });

    testWidgets('offers Google alone', (tester) async {
      expect(await waysFor(tester, const ["oidc:google"]),
          ["identify-oidc-google"]);
      expect(
          find.text(testL10n.identifyContinueWith("Google")), findsOneWidget);
    });

    testWidgets('offers the code first, then Google', (tester) async {
      expect(await waysFor(tester, const ["oidc:google", "mail-code"]),
          ["identify-send-code-1", "identify-oidc-google"]);
    });

    testWidgets('without any way says to ask for the link again',
        (tester) async {
      expect(await waysFor(tester, const []), ["identify-ask-again"]);
      expect(find.text(testL10n.identifyAskAgain("Alice")), findsOneWidget);
      expect(find.byKey(const Key("identify-remember")), findsNothing);
    });

    testWidgets('the code flow signs in', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-2",
          refusal: () => identifyRefusal(
            masked: const ["+49•••67", "p•••@gmx.de"],
            methods: const ["mail-code"],
          ),
          actions: {
            "prove-email": (_) => json('{"address": "p•••@gmx.de", '
                '"expires": "2027-01-01T00:00:00Z", "attempts": 5}'),
            "verify-email": (_) => credential("cred-2"),
          },
        ),
      );
      // The phone is not a way to send a code; the e-mail is the second.
      expect(offeredWays(tester), ["identify-send-code-2"]);
      await tapKey(tester, "identify-send-code-2");
      expect(h.bodyOf("prove-email"), {"address": "p•••@gmx.de", "choice": 2});
      expect(
          find.text(testL10n.identifyCodeSent("p•••@gmx.de")), findsOneWidget);

      await tester.enterText(find.byKey(const Key("identify-code")), "123456");
      await tapKey(tester, "identify-verify");

      var verify = h.bodyOf("verify-email");
      expect(verify["code"], "123456");
      expect(verify["choice"], 2);
      expect(verify["remember"], isTrue);
      expect(h.remembered.values.values, ["cred-2"]);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('Google leaves the page and comes back with a code',
        (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-3",
          refusal: () => identifyRefusal(
            masked: const ["p•••@gmx.de"],
            methods: const ["oidc:google"],
          ),
          actions: {
            "oidc-start": (_) =>
                json('{"url": "https://accounts.example/auth", '
                    '"binding": "bind-1", "expires": "2027-01-01T00:00:00Z"}'),
          },
        ),
      );
      await tapKey(tester, "identify-oidc-google");

      expect(h.bodyOf("oidc-start")["provider"], "google");
      expect(h.opened, ["https://accounts.example/auth"]);
      var pending = h.store.pendingSignIn(dataUrl)!;
      expect(pending.binding, "bind-1");
      expect(pending.remember, isTrue);

      // The provider sends the browser back to the link.
      var back = Harness();
      back.store.keepSignIn(
          dataUrl, const PendingSignIn(binding: "bind-1", remember: false));
      await back.pump(
        tester,
        personalServer(
          admitted: "cred-3",
          refusal: () => identifyRefusal(methods: const ["oidc:google"]),
          actions: {"oidc-exchange": (_) => credential("cred-3")},
        ),
        location: Uri.parse("http://server/valbum/s/tok-42/#oidc=code-9"),
      );

      expect(back.bodyOf("oidc-exchange"),
          {"code": "code-9", "binding": "bind-1"});
      // The code leaves the address at once, the binding the tab.
      expect(back.rewritten, [Uri.parse("http://server/valbum/s/tok-42/")]);
      expect(back.store.pendingSignIn(dataUrl), isNull);
      expect(back.session.values.values, ["cred-3"]);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('a refused return is said over the card', (tester) async {
      var h = Harness();
      h.store.keepSignIn(
          dataUrl, const PendingSignIn(binding: "bind-1", remember: true));
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-3",
          refusal: () => identifyRefusal(methods: const ["oidc:google"]),
          actions: {
            "oidc-exchange": (_) => json(
                '["ErrorInfo", {"message": "This link was shared with '
                'someone else."}]',
                403),
          },
        ),
        location: Uri.parse("http://server/valbum/s/tok-42/#oidc=code-9"),
      );

      expect(find.byKey(const Key("identify-screen")), findsOneWidget);
      expect(
          find.text("This link was shared with someone else."), findsOneWidget);
    });
  });

  group('Not you? Switch person', () {
    testWidgets('forgets the credential and asks again', (tester) async {
      var h = Harness();
      h.store.write(dataUrl, "cred-1", remember: true);
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(
              methods: const ["mail-code"], masked: const ["p•••@gmx.de"]),
        ),
      );
      expect(find.byType(AlbumContent), findsOneWidget);

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(
          find.text(testL10n.signedInAsContact("Tante Petra")), findsOneWidget);
      await tester.tap(find.byKey(const Key("switch-person")));
      await tester.pumpAndSettle();

      expect(h.store.read(dataUrl), isNull);
      expect(h.remembered.values, isEmpty);
      expect(find.byKey(const Key("identify-screen")), findsOneWidget);
      expect(h.requests.last.headers[VAlbumClient.contactHeader], isNull);
    });

    testWidgets('a link not sent to this contact offers to switch',
        (tester) async {
      var h = Harness();
      h.store.write(dataUrl, "cred-9", remember: false);
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-1",
          refusal: () => identifyRefusal(
            status: 403,
            message: "This link was not sent to you.",
            contact: null,
            methods: const ["oidc:google"],
          ),
        ),
      );
      expect(find.text("This link was not sent to you."), findsOneWidget);
      await tapKey(tester, "identify-switch-person");
      expect(h.store.read(dataUrl), isNull);
    });
  });

  group('the group link of issue #211', () {
    testWidgets('asks for a typed address and no name', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-4",
          refusal: () => identifyRefusal(
            message: "This link asks who you are. Open the link you were "
                "sent yourself.",
            contact: null,
            methods: const ["mail-code", "oidc:google"],
            group: true,
          ),
          actions: {
            "prove-email": (_) => json('{"address": "p•••@gmx.de", '
                '"expires": "2027-01-01T00:00:00Z", "attempts": 5}'),
            "verify-email": (_) => credential("cred-4"),
          },
        ),
      );
      expect(find.text(testL10n.identifyGroupIntro), findsOneWidget);
      expect(find.byKey(const Key("identify-name")), findsNothing);
      expect(offeredWays(tester),
          ["identify-address", "identify-send-code", "identify-oidc-google"]);

      await tester.enterText(
          find.byKey(const Key("identify-address")), "petra@gmx.de");
      await tapKey(tester, "identify-send-code");
      expect(h.bodyOf("prove-email")["address"], "petra@gmx.de");
      await tester.enterText(find.byKey(const Key("identify-code")), "654321");
      await tapKey(tester, "identify-verify");

      expect(h.bodyOf("verify-email")["displayName"] ?? "", "");
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets(
        'an open personal link asks for the name as well: the field '
        'decides, never the sentence', (tester) async {
      var h = Harness();
      await h.pump(
        tester,
        personalServer(
          admitted: "cred-4",
          refusal: () => identifyRefusal(
            // A group link's sentence, without `group`: still an open link.
            message: "This link asks who you are. Open the link you were "
                "sent yourself.",
            contact: null,
            methods: const ["mail-code"],
          ),
        ),
      );
      expect(find.text(testL10n.identifyOpenIntro), findsOneWidget);
      expect(find.byKey(const Key("identify-name")), findsOneWidget);
      expect(find.byKey(const Key("identify-address")), findsOneWidget);
    });
  });

  group('"Add your e-mail" in a contact session (#211)', () {
    /// The `?type=auth` answer of a contact's session with [methods] and
    /// [hasEmail].
    String authWith({
      List<String> methods = const ["mail-code"],
      bool hasEmail = false,
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
            "contact": {"id": "c1", "displayName": "Tante Petra"},
            "methods": [
              for (var method in methods) {"name": method, "label": ""},
            ],
            "contactHasEmail": hasEmail,
          },
        });

    /// A contact session of `cred-1`, the credential held by the browser.
    http.Response Function(http.Request) contactServer(
      String auth, {
      Map<String, http.Response Function(http.Request)> actions = const {},
    }) =>
        (request) {
          var action = request.url.queryParameters["action"];
          if (action != null) {
            var answer = actions[action];
            return answer == null
                ? json('["ErrorInfo", {"message": "no"}]', 400)
                : answer(request);
          }
          if (request.headers[VAlbumClient.contactHeader] != "cred-1") {
            return identifyRefusal();
          }
          if (request.url.queryParameters["type"] == "auth") {
            return json(auth);
          }
          return json(zooAlbum);
        };

    Harness recognised() =>
        Harness()..store.write(dataUrl, "cred-1", remember: true);

    final sent = json('{"address": "p•••@gmx.de", '
        '"expires": "2027-01-01T00:00:00Z", "attempts": 5}');

    testWidgets('is offered to a contact without an e-mail address',
        (tester) async {
      var h = recognised();
      await h.pump(tester, contactServer(authWith()));

      expect(find.byType(AlbumContent), findsOneWidget);
      expect(find.byKey(const Key("add-email-offer")), findsOneWidget);
      expect(find.text(testL10n.addEmailOffer), findsOneWidget);
    });

    testWidgets(
        'is not offered with an address saved, without the mailed code, '
        'or on an anonymous link', (tester) async {
      for (var auth in [
        authWith(hasEmail: true),
        authWith(methods: const ["oidc:google"]),
        authWith(methods: const []),
      ]) {
        var h = recognised();
        await h.pump(tester, contactServer(auth));
        expect(find.byType(AlbumContent), findsOneWidget, reason: auth);
        expect(find.byKey(const Key("add-email-offer")), findsNothing,
            reason: auth);
      }
      var h = Harness();
      await h.pump(
        tester,
        (request) => request.url.queryParameters["type"] == "auth"
            ? json('{"mode": "writes", "writeAllowed": false, '
                '"share": {"label": "Zoo", "expires": "", '
                '"rights": [{"name": "view"}], "path": "~alice/2024/Zoo", '
                '"methods": [{"name": "mail-code"}]}}')
            : json(zooAlbum),
      );
      expect(find.byType(AlbumContent), findsOneWidget);
      expect(find.byKey(const Key("add-email-offer")), findsNothing);
    });

    testWidgets('"Not now" is remembered for the space in this browser',
        (tester) async {
      var h = recognised();
      await h.pump(tester, contactServer(authWith()));
      await tapKey(tester, "add-email-dismiss");

      expect(find.byKey(const Key("add-email-offer")), findsNothing);
      expect(h.remembered.values[ContactCredentialStore.emailOfferKey(dataUrl)],
          "1");
      // A new page load in the same browser: not offered again.
      await h.pump(tester, contactServer(authWith()));
      expect(find.byType(AlbumContent), findsOneWidget);
      expect(find.byKey(const Key("add-email-offer")), findsNothing);
      expect(h.requests.where((r) => r.url.queryParameters["action"] != null),
          isEmpty);
    });

    testWidgets('adds the address by a mailed code and keeps the credential',
        (tester) async {
      var h = recognised();
      await h.pump(
        tester,
        contactServer(authWith(), actions: {
          "prove-email": (_) => sent,
          "verify-email": (_) => credential(""),
        }),
      );
      await tapKey(tester, "add-email-open");
      expect(find.text(testL10n.addEmailTitle), findsOneWidget);
      await tester.enterText(
          find.byKey(const Key("add-email-address")), " petra@gmx.de ");
      await tester.pump();
      await tapKey(tester, "add-email-send");

      expect(h.bodyOf("prove-email")["address"], "petra@gmx.de");
      expect(h.lastOf("prove-email").headers[VAlbumClient.contactHeader],
          "cred-1");
      expect(find.text(testL10n.identifyCodeSent("p•••@gmx.de")),
          findsOneWidget);
      await tester.enterText(find.byKey(const Key("add-email-code")), "123456");
      await tester.pump();
      await tapKey(tester, "add-email-verify");

      var verify = h.bodyOf("verify-email");
      expect(verify["address"], "petra@gmx.de");
      expect(verify["code"], "123456");
      expect(find.byKey(const Key("add-email-dialog")), findsNothing);
      expect(find.byKey(const Key("add-email-offer")), findsNothing);
      expect(find.text(testL10n.addEmailDone), findsOneWidget);
      // The session's credential stays what it was.
      expect(h.store.read(dataUrl), "cred-1");
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('a refusal is said in the server\'s words and keeps the dialog',
        (tester) async {
      var h = recognised();
      await h.pump(
        tester,
        contactServer(authWith(), actions: {
          "prove-email": (_) => sent,
          "verify-email": (_) => json(
              '["ErrorInfo", {"message": "Another contact of this space '
              'holds this address."}]',
              409),
        }),
      );
      await tapKey(tester, "add-email-open");
      await tester.enterText(
          find.byKey(const Key("add-email-address")), "petra@gmx.de");
      await tester.pump();
      await tapKey(tester, "add-email-send");
      await tester.enterText(find.byKey(const Key("add-email-code")), "123456");
      await tester.pump();
      await tapKey(tester, "add-email-verify");

      expect(find.text("Another contact of this space holds this address."),
          findsOneWidget);
      expect(find.byKey(const Key("add-email-dialog")), findsOneWidget);
      await tapKey(tester, "add-email-cancel");
      // Still offered: nothing was added.
      expect(find.byKey(const Key("add-email-offer")), findsOneWidget);
    });

    testWidgets('is refused offline', (tester) async {
      var h = recognised();
      await h.pump(
        tester,
        contactServer(authWith(), actions: {"prove-email": (_) => sent}),
      );
      await tapKey(tester, "add-email-open");
      await tester.enterText(
          find.byKey(const Key("add-email-address")), "petra@gmx.de");
      await tester.pump();
      h.offline.goneOffline(null);
      await tester.pumpAndSettle();
      await tapKey(tester, "add-email-send");

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(
          h.requests
              .where((r) => r.url.queryParameters["action"] == "prove-email"),
          isEmpty);
    });

    testWidgets('speaks German', (tester) async {
      var h = recognised();
      await h.pump(tester, contactServer(authWith()),
          locale: const Locale("de"));
      var de = l10nOf(const Locale("de"));
      expect(find.text(de.addEmailOffer), findsOneWidget);
      expect(find.text(de.addEmailNotNow), findsOneWidget);
      await tapKey(tester, "add-email-open");
      expect(find.text(de.addEmailTitle), findsOneWidget);
      expect(find.text(de.addEmailExplanation), findsOneWidget);
    });
  });

  testWidgets('a contact session signs its videos with the credential',
      (tester) async {
    var seen = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: dataUrl,
      token: "tok-42",
      contact: "cred-1",
      httpClient: MockClient((request) async {
        seen.add(request);
        return json('{"url": "", "media": "c.l1~s1.9.mac", "expires": ""}');
      }),
    );
    var signer = client.mediaSigner(isWeb: true)!;
    var signed = await signer("$dataUrl/a.mp4?type=video");

    expect(seen.single.headers[VAlbumClient.contactHeader], "cred-1");
    expect(signed.url, isNot(contains("cred-1")));
    expect(signed.url, contains("media=c.l1"));
  });

  testWidgets('the first open speaks German', (tester) async {
    var h = Harness();
    await h.pump(
      tester,
      personalServer(
        admitted: "cred-1",
        refusal: () => identifyRefusal(firstOpen: true),
      ),
      locale: const Locale("de"),
    );
    var de = l10nOf(const Locale("de"));
    expect(find.text(de.identifyNotice("Alice")), findsOneWidget);
    expect(find.text(de.identifyRemember), findsOneWidget);
    expect(find.text("Weiter"), findsWidgets);
  });
}
