/// The sharer's side of personal share links (issues #201, #202, #211): the
/// link type, the recipient chooser, and each recipient's own link sent
/// through the sharer's own apps.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/about.dart' as about;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/recipient_chooser.dart';
import 'package:valbum_ui/recipient_send.dart' as send;

import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status);

/// The space's contacts.
const String contactsAnswer = '{"contacts": ['
    '{"id": "c1", "name": "Tante Petra", "addresses": '
    '[{"kind": "EMAIL", "value": "petra@gmx.de"}]},'
    '{"id": "c2", "name": "Onkel Bernd", "addresses": '
    '[{"kind": "PHONE", "value": "+4917012345"}]},'
    '{"id": "c3", "name": "Oma", "addresses": '
    '[{"kind": "EMAIL", "value": "oma@web.de"}]}'
    ']}';

/// A recipient's own link as the server answers it once.
Map<String, Object> recipientLink(
  String id,
  String name,
  List<(String, String)> addresses,
) =>
    {
      "contact": id,
      "name": name,
      "addresses": [
        for (var (kind, value) in addresses) {"kind": kind, "value": value},
      ],
      "token": "r-$id",
      "url": "/valbum/s/r-$id/",
    };

String createdAnswer(List<Map<String, Object>> recipients) => jsonEncode({
      "link": {"id": "L1", "label": "", "type": "PERSONAL"},
      "token": "own-token",
      "url": "/valbum/s/own-token/",
      "recipients": recipients,
    });

class Server {
  final requests = <http.Request>[];

  /// What the two proof probes answer: 501 without a mail account or
  /// provider, 400 ("not here") once there is one.
  int proof = 501;

  String created = createdAnswer(const []);

  String shares = '{"links": []}';

  http.Response answer(http.Request request) {
    requests.add(request);
    var type = request.url.queryParameters["type"];
    var action = request.url.queryParameters["action"];
    if (type == "shares") {
      return json(shares);
    }
    if (type == "contacts") {
      return json(contactsAnswer);
    }
    if (action == "prove-email" || action == "oidc-start") {
      return refusal(proof, "probe");
    }
    if (action == "share" || action == "resend") {
      return json(created);
    }
    return refusal(404, "No such resource");
  }

  Map<String, dynamic> bodyOf(String action) => jsonDecode(requests
      .lastWhere((request) => request.url.queryParameters["action"] == action)
      .body) as Map<String, dynamic>;
}

Future<void> pumpDialog(
  WidgetTester tester,
  Server server, {
  bool isWeb = true,
  Locale locale = const Locale("en"),
  OfflineState? offline,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "device",
    httpClient: MockClient((request) async => server.answer(request)),
  );
  await tester.pumpWidget(OfflineScope(
    state: offline ?? OfflineState(),
    cache: MemoryOfflineCache(),
    child: localizedApp(
      Scaffold(
        body: ShareLinkDialog(
          client: client,
          path: const ["2024", "Zoo"],
          label: "Zoo",
          isWeb: isWeb,
        ),
      ),
      locale: locale,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

Future<void> chooseFrom(WidgetTester tester, String key, String text) async {
  await tapKey(tester, key);
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

bool createEnabled(WidgetTester tester) =>
    tester
        .widget<ElevatedButton>(find.byKey(const Key("link-create")))
        .onPressed !=
    null;

/// The launched addresses, and the texts handed to the share sheet.
final launched = <Uri>[];
final shared = <String>[];

void main() {
  setUp(() {
    launched.clear();
    shared.clear();
    about.openExternalUrl = (uri) async {
      launched.add(uri);
      return true;
    };
    send.openShareSheet = (text, {subject}) async => shared.add(text);
  });

  group('the link type', () {
    testWidgets('anonymous is the default and creates the link as before',
        (tester) async {
      var server = Server()
        ..created = '{"link": {"id": "L0"}, "token": "t0", '
            '"url": "/valbum/s/t0/"}';
      await pumpDialog(tester, server);
      await tapKey(tester, "new-link");

      expect(find.text(testL10n.linkTypeAnonymous), findsWidgets);
      expect(find.byKey(const Key("recipient-chooser")), findsNothing);
      await tapKey(tester, "link-create");

      var body = server.bodyOf("share");
      expect(body["type"], isNot("PERSONAL"));
      expect(body["recipients"] ?? const [], isEmpty);
      expect(find.text("http://server/valbum/s/t0/"), findsOneWidget);
    });

    testWidgets('personalized is disabled with its reason without a proof',
        (tester) async {
      var server = Server()..proof = 501;
      await pumpDialog(tester, server);
      await tapKey(tester, "new-link");
      await tapKey(tester, "link-type");

      expect(find.byKey(const Key("link-type-reason")), findsOneWidget);
      expect(find.text(testL10n.linkTypeNeedsProof), findsOneWidget);
      await tester.tap(find.text(testL10n.linkTypeOpenPersonal).last,
          warnIfMissed: false);
      await tester.pumpAndSettle();
      // Still anonymous: the choice was shown, and not offered.
      await tester.tap(find.text(testL10n.linkTypeAnonymous).last);
      await tester.pumpAndSettle();
      await tapKey(tester, "link-create");
      expect(server.bodyOf("share")["type"], isNot("PERSONAL"));
    });

    testWidgets('personalized creates a personal link without recipients',
        (tester) async {
      var server = Server()
        ..proof = 400
        ..created = '{"link": {"id": "L2", "type": "PERSONAL"}, '
            '"token": "t2", "url": "/valbum/s/t2/"}';
      await pumpDialog(tester, server);
      await tapKey(tester, "new-link");
      await chooseFrom(tester, "link-type", testL10n.linkTypeOpenPersonal);

      expect(find.byKey(const Key("recipient-chooser")), findsNothing);
      await tapKey(tester, "link-create");
      var body = server.bodyOf("share");
      expect(body["type"], "PERSONAL");
      expect(body["recipients"] ?? const [], isEmpty);
      expect(find.text("http://server/valbum/s/t2/"), findsOneWidget);
    });

    testWidgets('only selected contacts shows the chooser and needs one',
        (tester) async {
      await pumpDialog(tester, Server());
      await tapKey(tester, "new-link");
      await chooseFrom(tester, "link-type", testL10n.linkTypeSelected);

      expect(find.byKey(const Key("recipient-chooser")), findsOneWidget);
      expect(find.text(testL10n.recipientsNeeded), findsOneWidget);
      expect(createEnabled(tester), isFalse);
      await tapKey(tester, "recipient-contact-c1");
      expect(createEnabled(tester), isTrue);
    });

    testWidgets('is refused offline with the usual reason', (tester) async {
      var server = Server();
      var offline = OfflineState();
      await pumpDialog(tester, server, offline: offline);
      await tapKey(tester, "new-link");
      offline.goneOffline(null);
      await tester.pumpAndSettle();
      await tapKey(tester, "link-create");

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(
          server.requests.where(
              (request) => request.url.queryParameters["action"] == "share"),
          isEmpty);
    });
  });

  group('the recipient chooser', () {
    Future<void> openChooser(WidgetTester tester, Server server) async {
      await pumpDialog(tester, server);
      await tapKey(tester, "new-link");
      await chooseFrom(tester, "link-type", testL10n.linkTypeSelected);
    }

    testWidgets('the search narrows by name and by address', (tester) async {
      await openChooser(tester, Server());
      expect(find.byKey(const Key("recipient-contact-c1")), findsOneWidget);
      expect(find.byKey(const Key("recipient-contact-c2")), findsOneWidget);

      await tester.enterText(find.byKey(const Key("recipient-search")), "Pet");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("recipient-contact-c1")), findsOneWidget);
      expect(find.byKey(const Key("recipient-contact-c2")), findsNothing);
      expect(find.byKey(const Key("recipient-contact-c3")), findsNothing);

      await tester.enterText(
          find.byKey(const Key("recipient-search")), "web.de");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("recipient-contact-c3")), findsOneWidget);
      expect(find.byKey(const Key("recipient-contact-c1")), findsNothing);

      await tester.enterText(
          find.byKey(const Key("recipient-search")), "nobody");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("recipient-no-match")), findsOneWidget);
    });

    testWidgets('ticking adds a recipient', (tester) async {
      var server = Server()
        ..created = createdAnswer([
          recipientLink("c1", "Tante Petra", [("EMAIL", "petra@gmx.de")]),
        ]);
      await openChooser(tester, server);
      await tapKey(tester, "recipient-contact-c1");
      await tapKey(tester, "link-create");

      var body = server.bodyOf("share");
      expect(body["type"], "PERSONAL");
      var recipients = body["recipients"] as List;
      expect(recipients, hasLength(1));
      expect(recipients.single["contact"], "c1");
    });

    testWidgets('the full form fills both fields and names the contact',
        (tester) async {
      var server = Server();
      await openChooser(tester, server);
      await tapKey(tester, "recipient-new-contact");
      await tester.enterText(find.byKey(const Key("recipient-new-email-0")),
          "Tante Erna <erna@gmx.de>");
      await tester.pumpAndSettle();

      expect(
          tester
              .widget<TextField>(find.byKey(const Key("recipient-new-name-0")))
              .controller!
              .text,
          "Tante Erna");
      expect(
          tester
              .widget<TextField>(find.byKey(const Key("recipient-new-email-0")))
              .controller!
              .text,
          "erna@gmx.de");
      await tapKey(tester, "link-create");
      var recipient = (server.bodyOf("share")["recipients"] as List).single;
      expect(recipient["name"], "Tante Erna");
      expect(recipient["contact"] ?? "", "");
      expect(recipient["addresses"], [
        {"kind": "EMAIL", "value": "erna@gmx.de", "proven": false},
      ]);
    });

    testWidgets('a pasted list gives one new contact per address',
        (tester) async {
      await openChooser(tester, Server());
      await tapKey(tester, "recipient-new-contact");
      await tester.enterText(find.byKey(const Key("recipient-new-name-0")),
          'a@x.de, "B, C" <b@y.de>');
      await tester.pumpAndSettle();

      String text(String key) =>
          tester.widget<TextField>(find.byKey(Key(key))).controller!.text;
      expect(text("recipient-new-name-0"), "");
      expect(text("recipient-new-email-0"), "a@x.de");
      expect(text("recipient-new-name-1"), "B, C");
      expect(text("recipient-new-email-1"), "b@y.de");
    });

    testWidgets('an address a contact holds ticks that contact',
        (tester) async {
      var server = Server();
      await openChooser(tester, server);
      await tapKey(tester, "recipient-new-contact");
      await tester.enterText(
          find.byKey(const Key("recipient-new-email-0")), "Petra@GMX.de");
      await tester.pumpAndSettle();

      expect(
          find.text(testL10n.newContactAlready("Tante Petra")), findsOneWidget);
      expect(find.byKey(const Key("recipient-new-email-0")), findsNothing);
      expect(
          tester
              .widget<CheckboxListTile>(
                  find.byKey(const Key("recipient-contact-c1")))
              .value,
          isTrue);
    });

    test('the address parser reads the full form, quotes and lists', () {
      expect(parseAddressList("Tante Petra <petra@gmx.de>"),
          [(name: "Tante Petra", address: "petra@gmx.de")]);
      expect(parseAddressList('"Müller, Petra" <p@x.de>; q@y.de'), [
        (name: "Müller, Petra", address: "p@x.de"),
        (name: "", address: "q@y.de"),
      ]);
      expect(parseAddressList("no address, at all"), isEmpty);
    });
  });

  group('after creating', () {
    final recipients = [
      recipientLink("c1", "Tante Petra", [("EMAIL", "petra@gmx.de")]),
      recipientLink("c2", "Onkel Bernd", [("PHONE", "+4917012345")]),
      recipientLink("c3", "Oma", [("EMAIL", "oma@web.de")]),
    ];

    Future<void> create(WidgetTester tester, Server server,
        {bool isWeb = true, Locale locale = const Locale("en")}) async {
      server.created = createdAnswer(recipients);
      await pumpDialog(tester, server, isWeb: isWeb, locale: locale);
      await tapKey(tester, "new-link");
      var l10n = l10nOf(locale);
      await chooseFrom(tester, "link-type", l10n.linkTypeSelected);
      for (var id in ["c1", "c2", "c3"]) {
        await tapKey(tester, "recipient-contact-$id");
      }
    }

    testWidgets('each recipient gets their own mailto on the web',
        (tester) async {
      var server = Server();
      await create(tester, server);
      await tapKey(tester, "link-create");

      expect(find.text(testL10n.recipientLinksHeading), findsOneWidget);
      await tapKey(tester, "send-email-c1-0");
      await tapKey(tester, "send-email-c3-0");
      expect(launched, hasLength(2));
      var petra = launched[0];
      expect(petra.scheme, "mailto");
      expect(petra.path, "petra@gmx.de");
      expect(petra.queryParameters["subject"],
          testL10n.shareMessageSubject("Zoo"));
      expect(petra.queryParameters["body"],
          contains("http://server/valbum/s/r-c1/"));
      expect(petra.toString(), isNot(contains("+")),
          reason: "a blank is %20, never a plus");
      expect(launched[1].path, "oma@web.de");
      expect(launched[1].queryParameters["body"],
          contains("http://server/valbum/s/r-c3/"));
      // The web has no phone apps: the phone-only contact gets "Copy link".
      expect(find.byKey(const Key("send-whatsapp-c2-0")), findsNothing);
      expect(find.byKey(const Key("send-copy-c2")), findsOneWidget);
    });

    testWidgets('in the app every channel carries the recipient\'s own link',
        (tester) async {
      var server = Server();
      await create(tester, server, isWeb: false);
      await tapKey(tester, "link-create");

      await tapKey(tester, "send-whatsapp-c2-0");
      await tapKey(tester, "send-sms-c2-0");
      await tapKey(tester, "send-other-c2");
      expect(launched[0].toString(), startsWith("https://wa.me/4917012345?"));
      expect(launched[0].queryParameters["text"],
          contains("http://server/valbum/s/r-c2/"));
      expect(launched[1].toString(), startsWith("sms:+4917012345?"));
      expect(launched[1].queryParameters["body"],
          contains("http://server/valbum/s/r-c2/"));
      expect(shared.single, contains("http://server/valbum/s/r-c2/"));
    });

    testWidgets(
        'one link for the group: one mail in BCC, and own links for '
        'those without an address', (tester) async {
      var server = Server()..proof = 400;
      await create(tester, server);
      await chooseFrom(tester, "link-delivery", testL10n.linkDeliveryGroup);
      await tapKey(tester, "link-create");

      expect(find.text("http://server/valbum/s/own-token/"), findsOneWidget);
      await tapKey(tester, "send-group-email");
      var mail = launched.single;
      expect(mail.queryParameters["bcc"], "petra@gmx.de,oma@web.de");
      expect(mail.queryParameters["body"],
          contains("http://server/valbum/s/own-token/"));
      // Onkel Bernd has no e-mail address: named, with his own link.
      expect(find.byKey(const Key("group-link-without-email")), findsOneWidget);
      expect(find.byKey(const Key("recipient-link-c2")), findsOneWidget);
      expect(find.byKey(const Key("recipient-link-c1")), findsNothing);
    });

    testWidgets('the group choice needs a proof on the server', (tester) async {
      var server = Server()..proof = 501;
      await create(tester, server);
      await tapKey(tester, "link-delivery");
      expect(find.byKey(const Key("link-type-reason")), findsOneWidget);
    });

    testWidgets('the form speaks German', (tester) async {
      var server = Server();
      await create(tester, server, locale: const Locale("de"));
      var de = l10nOf(const Locale("de"));
      expect(find.text(de.linkTypeSelected), findsWidgets);
      expect(find.text(de.linkDeliveryEach), findsWidgets);
      await tapKey(tester, "link-create");
      expect(find.text(de.recipientLinksHeading), findsOneWidget);
    });
  });

  testWidgets('send again shows the fresh link of that recipient',
      (tester) async {
    var server = Server()
      ..shares = jsonEncode({
        "links": [
          {
            "id": "L1",
            "label": "Party",
            "path": "2024/Zoo",
            "type": "PERSONAL",
            "recipients": [
              {"contact": "c1", "name": "Tante Petra"},
            ],
          },
        ],
      })
      ..created = createdAnswer([
        recipientLink("c1", "Tante Petra", [("EMAIL", "petra@gmx.de")]),
      ]);
    await pumpDialog(tester, server);
    await tapKey(tester, "resend-L1-c1");

    expect(server.bodyOf("resend"), {"link": "L1", "contact": "c1"});
    expect(find.text(testL10n.sendAgainHeading("Tante Petra")), findsOneWidget);
    expect(find.text(testL10n.sendAgainNote), findsOneWidget);
    expect(find.byKey(const Key("send-email-c1-0")), findsOneWidget);
  });
}
