/// The owner screens of issue #203: who came through a personal link and
/// what they added, the contacts of the space in the server settings, and a
/// contact's own "sign out others".
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/resource.dart';

import 'devices_test.dart' show authOfUser, pumpSettings, signedIn;
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status);

/// An anonymous link, an open personal link with one visitor, an addressed
/// one with two recipients, and an addressed one whose recipients were all
/// deleted.
Map<String, Object> linksOfZoo({String petraShutOut = ""}) => {
      "links": [
        {"id": "A1", "label": "Anyone", "path": "2024/Zoo"},
        {
          "id": "O1",
          "label": "Group",
          "path": "2024/Zoo",
          "type": "PERSONAL",
          "visitors": [
            {
              "contact": "c3",
              "name": "Oma",
              "firstSeen": "2026-05-03T10:00:00Z",
              "lastSeen": "2026-05-09T10:00:00Z",
              "uploads": 1,
            },
          ],
        },
        {
          "id": "L1",
          "label": "Party",
          "path": "2024/Zoo",
          "type": "PERSONAL",
          "addressed": true,
          "recipients": [
            {
              "contact": "c1",
              "name": "Tante Petra",
              "opened": "2026-05-03T10:00:00Z",
              "firstOpened": "2026-05-03T10:00:00Z",
              "lastSeen": "2026-05-07T10:00:00Z",
              "uploads": 2,
              "shutOut": petraShutOut,
            },
            {"contact": "c2", "name": "Klaus"},
          ],
        },
        {
          "id": "E1",
          "label": "Emptied",
          "path": "2024/Zoo",
          "type": "PERSONAL",
          "addressed": true,
        },
      ],
    };

class LinkServer {
  final requests = <http.Request>[];

  String shares = jsonEncode(linksOfZoo());

  http.Response Function(http.Request)? onShutOut;

  http.Response answer(http.Request request) {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "shares") {
      return json(shares);
    }
    if (query["action"] == "shut-out") {
      var handler = onShutOut;
      if (handler != null) {
        return handler(request);
      }
      var body = jsonDecode(request.body) as Map<String, dynamic>;
      var links = linksOfZoo(
          petraShutOut: body["shutOut"] == true ? "2026-05-10T10:00:00Z" : "");
      var link = (links["links"] as List).firstWhere((l) => l["id"] == "L1");
      return json(jsonEncode({
        "links": [link]
      }));
    }
    return refusal(404, "No such resource");
  }

  Map<String, dynamic> bodyOf(String action) => jsonDecode(requests
      .lastWhere((request) => request.url.queryParameters["action"] == action)
      .body) as Map<String, dynamic>;
}

Future<void> pumpLinks(
  WidgetTester tester,
  LinkServer server, {
  Locale locale = const Locale("en"),
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
    state: OfflineState(),
    cache: MemoryOfflineCache(),
    child: localizedApp(
      Scaffold(
        body: ShareLinkDialog(
          client: client,
          path: const ["2024", "Zoo"],
          label: "Zoo",
          isWeb: true,
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

String textOf(WidgetTester tester, String key) =>
    tester.widget<Text>(find.byKey(Key(key))).data!;

/// The contacts of the space as the server lists them.
Map<String, Object> contactsAnswer({String petraName = "Tante Petra"}) => {
      "contacts": [
        {
          "id": "c1",
          "name": petraName,
          "displayName": "Petra",
          "addresses": [
            {"kind": "EMAIL", "value": "petra@gmx.de", "proven": true},
            {"kind": "PHONE", "value": "+4917012345"},
          ],
          "lastSeen": "2026-05-07T10:00:00Z",
          "uploads": 3,
          "sessions": [
            {
              "id": "s1",
              "link": "L1",
              "linkLabel": "Party",
              "created": "2026-05-03T10:00:00Z",
              "expires": "2099-01-01T00:00:00Z",
              "lastUsed": "2026-05-07T10:00:00Z",
            },
            {
              "id": "s2",
              "link": "L1",
              "linkLabel": "Party",
              "created": "2026-05-05T10:00:00Z",
              "expires": "2099-01-01T00:00:00Z",
              "lastUsed": "2026-05-05T10:00:00Z",
            },
          ],
        },
        {
          "id": "c2",
          "name": "Klaus",
          "addresses": [
            {"kind": "PHONE", "value": "+491711234567"},
          ],
          "blocked": "2026-05-08T10:00:00Z",
        },
      ],
    };

Map<String, Object> contactOf(Map<String, Object> list, String id) =>
    ((list["contacts"] as List).cast<Map<String, Object>>())
        .firstWhere((contact) => contact["id"] == id);

class ContactServer {
  final requests = <http.Request>[];

  Map<String, Object> contacts = contactsAnswer();

  /// A refusal every contact action is answered with, `null` for none.
  http.Response? refuse;

  http.Response answer(http.Request request) {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "auth") {
      return json(authOfUser("admin", name: "haui"));
    }
    if (query["type"] == "contacts") {
      return json(jsonEncode(contacts));
    }
    if (query["type"] == "devices") {
      return json('{"devices": []}');
    }
    if (query["type"] == "users") {
      return json('{"users": []}');
    }
    if (query["type"] == "invitations") {
      return json('{"invitations": []}');
    }
    var action = query["action"];
    if (action == null) {
      return refusal(404, "No such resource");
    }
    var stop = refuse;
    if (stop != null) {
      return stop;
    }
    var body = jsonDecode(request.body) as Map<String, dynamic>;
    var id = body["contact"] as String;
    var contact = Map<String, Object>.of(contactOf(contacts, id));
    switch (action) {
      case "rename-contact":
        contact["name"] = body["name"] as String;
      case "end-contact-session":
        var session = body["session"] as String? ?? "";
        contact["sessions"] = [
          for (var s in (contact["sessions"] as List? ?? const []))
            if (session.isNotEmpty && s["id"] != session) s,
        ];
      case "block-contact":
        contact["blocked"] =
            body["shutOut"] == true ? "2026-05-10T10:00:00Z" : "";
        if (body["shutOut"] == true) {
          contact["sessions"] = const [];
        }
      case "remove-contact-sign-in":
        if (body["method"] == "totp") {
          contact["authenticator"] = "";
        } else {
          contact["passkeys"] = [
            for (var p in (contact["passkeys"] as List? ?? const []))
              if (p["id"] != body["id"]) p,
          ];
        }
      case "delete-contact":
        contacts = {
          "contacts": [
            for (var c in contacts["contacts"] as List)
              if (c["id"] != id) c,
          ],
        };
        return json(jsonEncode(contact));
      default:
        return refusal(400, "Unknown action");
    }
    contacts = {
      "contacts": [
        for (var c in contacts["contacts"] as List) c["id"] == id ? contact : c,
      ],
    };
    return json(jsonEncode(contact));
  }

  Map<String, dynamic> bodyOf(String action) => jsonDecode(requests
      .lastWhere((request) => request.url.queryParameters["action"] == action)
      .body) as Map<String, dynamic>;

  int count(String action) => requests
      .where((request) => request.url.queryParameters["action"] == action)
      .length;
}

Future<void> pumpContacts(
  WidgetTester tester,
  ContactServer server, {
  bool mayManage = true,
  Locale locale = const Locale("en"),
}) async {
  tester.view.physicalSize = const Size(1000, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "device",
    httpClient: MockClient((request) async => server.answer(request)),
  );
  await tester.pumpWidget(localizedApp(
    Scaffold(
      body: SingleChildScrollView(
        child: ContactsSection(client: client, mayManage: mayManage),
      ),
    ),
    locale: locale,
  ));
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester, String id, String entry) async {
  await tapKey(tester, "contact-menu-$id");
  await tester.tap(find.byKey(Key("contact-$entry-$id")));
  await tester.pumpAndSettle();
}

void main() {
  group('the link list', () {
    testWidgets('names the type of every link', (tester) async {
      await pumpLinks(tester, LinkServer());
      String described(String id) => tester
          .widget<ListTile>(find.byKey(Key("link-$id")))
          .subtitle
          .toString();
      expect(described("A1"), contains(testL10n.linkAnonymous));
      expect(described("O1"), contains(testL10n.linkPersonalOpen));
      expect(described("L1"), contains(testL10n.linkRecipientCount(2)));
      expect(described("E1"), contains(testL10n.linkTypeSelected),
          reason: "A link whose recipients were deleted stays addressed.");
    });

    testWidgets('shows each recipient with when they came and what they added',
        (tester) async {
      await pumpLinks(tester, LinkServer());
      var petra = textOf(tester, "recipient-state-L1-c1");
      expect(petra, contains(testL10n.openedOn(dayOf("2026-05-03T10:00:00Z"))));
      expect(
          petra, contains(testL10n.lastSeenOn(dayOf("2026-05-07T10:00:00Z"))));
      expect(petra, contains(testL10n.photosAdded(2)));
      expect(petra, isNot(contains(testL10n.shutOutMark)));
      expect(
          textOf(tester, "recipient-state-L1-c2"), testL10n.recipientNotOpened);
      expect(find.byKey(const Key("resend-L1-c1")), findsOneWidget);
      expect(find.byKey(const Key("shut-out-L1-c2")), findsOneWidget);
    });

    testWidgets('shows the visitors of an open personal link', (tester) async {
      await pumpLinks(tester, LinkServer());
      expect(find.byKey(const Key("visitor-O1-c3")), findsOneWidget);
      var oma = textOf(tester, "visitor-state-O1-c3");
      expect(oma, contains(testL10n.openedOn(dayOf("2026-05-03T10:00:00Z"))));
      expect(oma, contains(testL10n.photosAdded(1)));
      expect(find.byKey(const Key("shut-out-O1-c3")), findsOneWidget);
      expect(find.byKey(const Key("resend-O1-c3")), findsNothing,
          reason: "A visitor was sent nothing.");
    });

    testWidgets('shuts a recipient out and lets them in again', (tester) async {
      var server = LinkServer();
      await pumpLinks(tester, server);
      expect(
          tester
              .widget<IconButton>(find.byKey(const Key("shut-out-L1-c1")))
              .tooltip,
          testL10n.shutOutOfLink);

      await tapKey(tester, "shut-out-L1-c1");
      expect(server.bodyOf("shut-out"),
          {"link": "L1", "contact": "c1", "shutOut": true});
      expect(server.requests.last.url.path, "/valbum/data/2024/Zoo/");
      expect(textOf(tester, "recipient-state-L1-c1"),
          startsWith(testL10n.shutOutMark));
      expect(
          tester
              .widget<IconButton>(find.byKey(const Key("shut-out-L1-c1")))
              .tooltip,
          testL10n.letInAgain);

      await tapKey(tester, "shut-out-L1-c1");
      expect(server.bodyOf("shut-out"),
          {"link": "L1", "contact": "c1", "shutOut": false});
      expect(textOf(tester, "recipient-state-L1-c1"),
          isNot(contains(testL10n.shutOutMark)));
    });

    testWidgets('a refused shutting out is said in the server\'s words',
        (tester) async {
      var server = LinkServer()
        ..onShutOut = (_) => refusal(404, "There is no such share link.");
      await pumpLinks(tester, server);
      await tapKey(tester, "shut-out-L1-c2");
      expect(find.text("There is no such share link."), findsOneWidget);
    });

    testWidgets('speaks German', (tester) async {
      await pumpLinks(tester, LinkServer(), locale: const Locale("de"));
      expect(textOf(tester, "recipient-state-L1-c2"), "Noch nicht geöffnet");
      expect(textOf(tester, "recipient-state-L1-c1"),
          contains("2 Fotos hinzugefügt"));
    });
  });

  group('the contacts section', () {
    testWidgets('lists the contacts with what is known of them',
        (tester) async {
      await pumpContacts(tester, ContactServer());
      expect(find.text("Tante Petra"), findsOneWidget);
      expect(find.text(testL10n.contactOwnName("Petra")), findsOneWidget);
      expect(find.text("petra@gmx.de"), findsOneWidget);
      expect(find.byKey(const Key("contact-proven-c1-petra@gmx.de")),
          findsOneWidget);
      expect(
          find.byKey(const Key("contact-proven-c1-+4917012345")), findsNothing);
      var petra = textOf(tester, "contact-details-c1");
      expect(petra, contains(testL10n.contactSessionCount(2)));
      expect(petra, contains(testL10n.photosAdded(3)));
      expect(
          petra, contains(testL10n.lastSeenOn(dayOf("2026-05-07T10:00:00Z"))));
      expect(textOf(tester, "contact-details-c2"),
          startsWith(testL10n.shutOutEverywhereMark));
    });

    testWidgets('says when there is nobody', (tester) async {
      await pumpContacts(tester, ContactServer()..contacts = {"contacts": []});
      expect(find.byKey(const Key("settings.contacts.empty")), findsOneWidget);
    });

    testWidgets('renames a contact', (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "rename");
      expect(find.text(testL10n.contactRenameNote), findsOneWidget);
      await tester.enterText(
          find.byKey(const Key("contact-rename-field")), "  Petra Müller ");
      await tapKey(tester, "contact-rename-save");

      expect(server.bodyOf("rename-contact"),
          {"contact": "c1", "name": "Petra Müller"});
      expect(find.text("Petra Müller"), findsOneWidget);
      expect(find.text("Tante Petra"), findsNothing);
    });

    testWidgets('deletes a contact after naming what goes and what stays',
        (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "delete");
      expect(find.text(testL10n.deleteContactTitle("Tante Petra")),
          findsOneWidget);
      expect(find.text(testL10n.deleteContactMessage), findsOneWidget);
      await tapKey(tester, "delete-contact-confirmed");

      expect(server.bodyOf("delete-contact"), {"contact": "c1"});
      expect(find.byKey(const Key("contact-c1")), findsNothing);
      expect(find.byKey(const Key("contact-c2")), findsOneWidget);
    });

    testWidgets('a cancelled delete sends nothing', (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "delete");
      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();
      expect(server.count("delete-contact"), 0);
      expect(find.byKey(const Key("contact-c1")), findsOneWidget);
    });

    testWidgets('ends one browser, then all of them', (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "sessions");
      expect(find.text(testL10n.contactSessionsTitle("Tante Petra")),
          findsOneWidget);
      expect(find.byKey(const Key("contact-session-s1")), findsOneWidget);
      expect(find.textContaining(testL10n.contactSessionVia("Party")),
          findsNWidgets(2));

      await tapKey(tester, "contact-session-end-s1");
      expect(server.bodyOf("end-contact-session"),
          {"contact": "c1", "session": "s1"});
      expect(find.byKey(const Key("contact-session-s1")), findsNothing);
      expect(find.byKey(const Key("contact-session-s2")), findsOneWidget);
      expect(find.byKey(const Key("contact-sessions-end-all")), findsNothing,
          reason: "One browser has no 'End all'.");

      await tapKey(tester, "contact-session-end-s2");
      expect(find.byKey(const Key("contact-sessions-none")), findsOneWidget);
      await tapKey(tester, "contact-sessions-close");
      expect(textOf(tester, "contact-details-c1"),
          contains(testL10n.contactSessionCount(0)));
    });

    testWidgets('"End all" ends every browser at once', (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "sessions");
      await tapKey(tester, "contact-sessions-end-all");
      expect(server.bodyOf("end-contact-session"),
          {"contact": "c1", "session": ""});
      expect(find.byKey(const Key("contact-sessions-none")), findsOneWidget);
    });

    testWidgets('shuts a contact out of every link and lets them in again',
        (tester) async {
      var server = ContactServer();
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "block");
      expect(
          server.bodyOf("block-contact"), {"link": "", "contact": "c1", "shutOut": true});
      expect(textOf(tester, "contact-details-c1"),
          startsWith(testL10n.shutOutEverywhereMark));

      await tapKey(tester, "contact-menu-c2");
      expect(find.text(testL10n.letInAgain), findsOneWidget);
      await tester.tap(find.byKey(const Key("contact-block-c2")));
      await tester.pumpAndSettle();
      expect(
          server.bodyOf("block-contact"),
          {"link": "", "contact": "c2", "shutOut": false});
    });

    testWidgets('a refusal is said in the server\'s words', (tester) async {
      var server = ContactServer()
        ..refuse = refusal(403,
            "Only a member who may share links manages the contacts of the space.");
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "block");
      expect(
          find.text(
              "Only a member who may share links manages the contacts of the space."),
          findsOneWidget);
      expect(find.text("Tante Petra"), findsOneWidget);
    });

    testWidgets('shows a contact\'s authenticator app and removes it',
        (tester) async {
      var server = ContactServer();
      var petra = contactOf(server.contacts, "c1");
      petra["authenticator"] = "2026-05-04T10:00:00Z";
      await pumpContacts(tester, server);
      expect(textOf(tester, "contact-details-c1"),
          contains(testL10n.contactAuthenticatorMark));
      expect(textOf(tester, "contact-details-c2"),
          isNot(contains(testL10n.contactAuthenticatorMark)));

      await openMenu(tester, "c1", "sign-ins");
      expect(find.byKey(const Key("contact-sign-ins-dialog")), findsOneWidget);
      expect(
          find.text(testL10n
              .authenticatorActiveSince(dayOf("2026-05-04T10:00:00Z"))),
          findsOneWidget);
      await tapKey(tester, "contact-sign-in-totp-remove");
      expect(find.text(testL10n.contactAuthenticatorRemoveTitle("Tante Petra")),
          findsOneWidget);
      await tapKey(tester, "contact-sign-in-remove-confirmed");

      expect(server.bodyOf("remove-contact-sign-in"),
          {"contact": "c1", "method": "totp", "id": ""});
      expect(find.byKey(const Key("contact-sign-ins-none")), findsOneWidget);
      await tapKey(tester, "contact-sign-ins-close");
      expect(textOf(tester, "contact-details-c1"),
          isNot(contains(testL10n.contactAuthenticatorMark)));
    });

    testWidgets('shows a contact\'s passkeys and removes one', (tester) async {
      var server = ContactServer();
      contactOf(server.contacts, "c1")["passkeys"] = [
        {
          "id": "pk1",
          "created": "2026-05-04T10:00:00Z",
          "lastUsed": "2026-05-06T10:00:00Z",
        },
        {"id": "pk2", "created": "2026-05-05T10:00:00Z"},
      ];
      await pumpContacts(tester, server);
      expect(textOf(tester, "contact-details-c1"),
          contains(testL10n.contactPasskeyCount(2)));

      await openMenu(tester, "c1", "sign-ins");
      expect(find.text(testL10n.passkeyFrom(dayOf("2026-05-04T10:00:00Z"))),
          findsOneWidget);
      expect(
          find.text(
              testL10n.passkeyLastUsed(dayOf("2026-05-06T10:00:00Z"))),
          findsOneWidget);
      await tapKey(tester, "contact-sign-in-passkey-remove-pk1");
      expect(find.text(testL10n.contactPasskeyRemoveTitle("Tante Petra")),
          findsOneWidget);
      await tapKey(tester, "contact-sign-in-remove-confirmed");
      expect(server.bodyOf("remove-contact-sign-in"),
          {"contact": "c1", "method": "passkey", "id": "pk1"});
      expect(find.byKey(const Key("contact-sign-in-passkey-pk1")), findsNothing);
      expect(find.byKey(const Key("contact-sign-in-passkey-pk2")), findsOneWidget);
    });

    testWidgets('a cancelled removal of an authenticator sends nothing',
        (tester) async {
      var server = ContactServer();
      contactOf(server.contacts, "c1")["authenticator"] =
          "2026-05-04T10:00:00Z";
      await pumpContacts(tester, server);
      await openMenu(tester, "c1", "sign-ins");
      await tapKey(tester, "contact-sign-in-totp-remove");
      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();
      expect(server.count("remove-contact-sign-in"), 0);
      expect(find.byKey(const Key("contact-sign-in-totp")), findsOneWidget);
    });

    testWidgets('a member without the share flag reads and changes nothing',
        (tester) async {
      await pumpContacts(tester, ContactServer(), mayManage: false);
      expect(find.text("Tante Petra"), findsOneWidget);
      expect(find.byKey(const Key("contact-menu-c1")), findsNothing);
    });

    testWidgets('speaks German', (tester) async {
      await pumpContacts(tester, ContactServer(), locale: const Locale("de"));
      expect(find.text("Kontakte"), findsOneWidget);
      expect(textOf(tester, "contact-details-c1"),
          contains("In 2 Browsern angemeldet"));
      expect(textOf(tester, "contact-details-c2"),
          startsWith("Überall ausgesperrt"));
    });

    testWidgets('stands in the server settings beside the members',
        (tester) async {
      var server = ContactServer();
      await pumpSettings(
        tester,
        await signedIn(InMemorySettingsStore(
            "http://server/valbum/", "dev-1", "Phone", "haui")),
        MockClient((request) async => server.answer(request)),
      );
      expect(find.byKey(contactsSectionKey), findsOneWidget);
      expect(find.byKey(const Key("contact-menu-c1")), findsOneWidget,
          reason: "The administrator manages the contacts.");
    });
  });

  group("a contact's own sessions", () {
    const SessionUrl link = SessionUrl(
      kind: SessionKind.share,
      token: "tok-42",
      dataUrl: dataUrl,
      basePath: "/valbum/s/tok-42/",
    );

    String authOfContact(int others) =>
        '{"mode": "writes", "writeAllowed": false, '
        '"share": {"label": "Summer party", "expires": "", '
        '"rights": [{"name": "view"}], "path": "~alice/2024/Zoo", '
        '"type": "PERSONAL", "otherSessions": $others, '
        '"contact": {"id": "c1", "displayName": "Tante Petra"}}}';

    const String zooAlbum =
        '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
        '"rights": [{"name": "view"}], '
        '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
        '"date": 1015113600000, "width": 2048, "height": 1536, '
        '"orientation": "IDENTITY", "rating": 0}]]}]';

    Future<List<http.Request>> pumpSession(
      WidgetTester tester, {
      int others = 1,
      http.Response Function()? onEnd,
      Locale locale = const Locale("en"),
    }) async {
      tester.platformDispatcher.localesTestValue = [locale];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      var requests = <http.Request>[];
      var store = ContactCredentialStore.memory()
        ..write(dataUrl, "cred-1", remember: true);
      var client = VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          var query = request.url.queryParameters;
          if (query["action"] == "end-other-sessions") {
            return onEnd == null ? json('{"ended": $others}') : onEnd();
          }
          if (query["type"] == "auth") {
            return json(authOfContact(others));
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
      return requests;
    }

    testWidgets('signs out the other browsers from the menu', (tester) async {
      var requests = await pumpSession(tester);
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.otherSessionsSignOut(1)), findsOneWidget);
      await tester.tap(find.byKey(const Key("sign-out-others")));
      await tester.pumpAndSettle();

      var ended = requests.lastWhere((request) =>
          request.url.queryParameters["action"] == "end-other-sessions");
      expect(ended.method, "POST");
      expect(ended.headers[VAlbumClient.contactHeader], "cred-1");
      expect(find.text(testL10n.otherSessionsEnded), findsOneWidget);

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("sign-out-others")), findsNothing,
          reason: "Nothing left to sign out.");
      expect(find.byKey(const Key("switch-person")), findsOneWidget);
    });

    testWidgets('is not offered where there is no other browser',
        (tester) async {
      await pumpSession(tester, others: 0);
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("sign-out-others")), findsNothing);
      expect(find.byKey(const Key("switch-person")), findsOneWidget);
    });

    testWidgets('a refusal is said in the server\'s words', (tester) async {
      await pumpSession(tester,
          onEnd: () => refusal(403,
              "Only a person who opened a personal link signs out their other browsers."));
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("sign-out-others")));
      await tester.pumpAndSettle();
      expect(
          find.text(
              "Only a person who opened a personal link signs out their other browsers."),
          findsOneWidget);
    });

    testWidgets('speaks German', (tester) async {
      await pumpSession(tester, others: 2, locale: const Locale("de"));
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(
          find.text("Auch in 2 anderen Browsern angemeldet – andere abmelden"),
          findsOneWidget);
    });
  });
}
