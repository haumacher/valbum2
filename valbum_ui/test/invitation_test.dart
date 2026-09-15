/// Tests of the app half of the invitations of issue #52: opening an
/// invitation at `<context>/i/<token>/`, joining with it, pasting one into the
/// server field, issuing one from the settings, and what a guest is offered in
/// the library others share with them.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The app base of that server, what a device stores.
const String serverUrl = "http://server/valbum/";

/// The invitation the session tests are opened at.
const SessionUrl invitation = SessionUrl(
  kind: SessionKind.invitation,
  token: "inv-1",
  dataUrl: dataUrl,
  basePath: "/valbum/i/inv-1/",
);

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal of the server, with the reason it names.
http.Response refusal(int status, String message) => http.Response(
      '["ErrorInfo", {"message": "$message"}]',
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer somebody holding an invitation is given.
String authOfInvitation({
  String role = "member",
  String invitedBy = "alice",
  String note = "Welcome",
}) =>
    '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
    '"userName": "", "role": "", "space": "", '
    '"invitation": {"role": "$role", "invitedBy": "$invitedBy", '
    '"note": "$note", "expires": "2026-12-24T17:00:00Z"}}';

/// The `?type=auth` answer of an ordinary anonymous caller.
const String authOfNobody = '{"mode": "writes", "deviceName": "", '
    '"writeAllowed": false, "userName": "", "role": "", "space": ""}';

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role, {String name = "carol"}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "$role", "space": "$name"}';

/// What the server answers a device that accepted the invitation.
const String pairedAnswer = '{"token": "dev-9", "deviceName": "Phone", '
    '"userName": "carol", "role": "member", "space": "carol"}';

/// A settings store that records whether anybody read it.
///
/// An invitation must not read the device before it has signed it in: it
/// names its own server and carries its own token, and the real
/// `PreferencesSettingsStore` of a browser starts out unread — which is the
/// state a real invitation is opened in.
class RecordingSettingsStore extends InMemorySettingsStore {
  /// How often the stored server URL was read.
  int urlReads = 0;

  /// How often the stored device token was read.
  int tokenReads = 0;

  /// Whether anything of the device was read at all.
  bool get wasRead => urlReads > 0 || tokenReads > 0;

  @override
  Future<String?> load() {
    urlReads++;
    return super.load();
  }

  @override
  Future<String?> loadToken() {
    tokenReads++;
    return super.loadToken();
  }
}

/// Pumps the app as an invitation session over the given handler.
Future<
    ({
      List<http.Request> requests,
      RecordingSettingsStore store,
      ServerSettings settings,
    })> pumpInvitation(
  WidgetTester tester,
  http.Response Function(http.Request request) handler, {
  SessionUrl session = invitation,
}) async {
  var requests = <http.Request>[];
  var store = RecordingSettingsStore();
  var settings = ServerSettings(
    store: store,
    platformDefault: () => "http://server/valbum/i/inv-1/data",
  );
  var client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      return handler(request);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: settings,
    session: session,
  ));
  await tester.pumpAndSettle();
  return (requests: requests, store: store, settings: settings);
}

/// The answers of a live invitation: the probe, and the pairing.
http.Response Function(http.Request) liveInvitation({
  String role = "member",
  http.Response? pairAnswer,
}) =>
    (request) {
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfInvitation(role: role));
      }
      if (request.url.queryParameters["action"] == "pair") {
        return pairAnswer ?? json(pairedAnswer);
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    };

/// The "Sign in" button of the settings screen.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

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

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

/// The rights field as the server spells it.
String rightsField(List<String> names) =>
    '"rights": [${names.map((name) => '{"name": "$name"}').join(", ")}], ';

/// A root listing of one link tile, with a placement rule set.
String rootListing({List<String>? rights = const ["edit"]}) =>
    '["ListingInfo", {"path": "", "title": "My albums", '
    '${rights == null ? "" : rightsField(rights)}'
    '"placement": "BY_YEAR", '
    // The `link` field is what issue #50 made and issue #85 retired: an old
    // server may still send it, and the tile shows the folder, plainly.
    '"folders": [{"name": "Zoo", "title": "Zoo", "link": "~alice/2024/Zoo"}]}]';

/// Opens the app-bar menu of the view shown.
Future<void> openMenu(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
  });
}

/// Long-presses the tile with the given title.
Future<void> longPressTile(WidgetTester tester, String title) async {
  await tester.longPress(
    find
        .ancestor(of: find.text(title), matching: find.byType(GestureDetector))
        .first,
  );
  await tester.pumpAndSettle();
}

/// Pumps the whole app signed in as a user of the given role.
Future<List<http.Request>> pumpLibraryOf(
  WidgetTester tester,
  String role, {
  http.Response? grantsAnswer,
  List<String>? rights = const ["edit"],
}) async {
  var requests = <http.Request>[];
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "dev-9",
    userName: "carol",
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfUser(role));
      }
      if (request.url.queryParameters["type"] == "json") {
        return json(rootListing(rights: rights));
      }
      if (request.url.queryParameters["type"] == "grants") {
        return grantsAnswer ?? refusal(403, "Not yours to share.");
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "dev-9",
      userName: "carol",
      loaded: true,
    ),
  ));
  await tester.pumpAndSettle();
  return requests;
}

void main() {
  group('opening an invitation', () {
    testWidgets('shows who invited, as what, and the note', (tester) async {
      var session = await pumpInvitation(tester, liveInvitation());

      expect(find.byKey(const Key("invitation-welcome")), findsOneWidget);
      // What the invitation promises, in the words the settings use about it
      // (issue #85).
      expect(
        find.text("alice invited you to this album server: you may edit the "
            "albums."),
        findsOneWidget,
      );
      expect(find.text("Welcome"), findsOneWidget);
      expect(find.byKey(invitationUserFieldKey), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
      // No album, and no way into the server settings.
      expect(find.byIcon(Icons.more_vert), findsNothing);
      expect(find.byType(ServerSettingsScreen), findsNothing);

      // The probe carried the invitation as its bearer, and nothing of the
      // device was read on the way.
      var probe = session.requests.single;
      expect(probe.url.queryParameters["type"], "auth");
      expect(probe.headers["Authorization"], "Bearer inv-1");
      expect(session.store.wasRead, isFalse);
    });

    testWidgets('says what somebody who may only look gets', (tester) async {
      await pumpInvitation(tester, liveInvitation(role: "view"));

      expect(
        find.text("alice invited you to this album server: you may look at "
            "the albums."),
        findsOneWidget,
      );
    });
  });

  group('joining', () {
    testWidgets('pairs with the invitation and stores the device token',
        (tester) async {
      var session = await pumpInvitation(tester, liveInvitation());

      await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.byKey(const Key("invitation-join")));

      var pair = session.requests.last;
      expect(pair.url.queryParameters["action"], "pair");
      expect(pair.body, contains('"invitation":"inv-1"'));
      expect(pair.body, contains('"userName":"carol"'));
      expect(pair.body, contains('"secret":""'));
      expect(
        RegExp('"deviceName":"[^"]+"').firstMatch(pair.body),
        isNotNull,
      );

      expect(session.store.token, "dev-9");
      expect(session.store.userName, "carol");
      expect(session.store.value, serverUrl);
      // The invitation token is stored nowhere.
      expect(session.store.value, isNot(contains("inv-1")));
      expect(session.store.token, isNot("inv-1"));

      expect(find.text("You're in as carol."), findsOneWidget);
      expect(find.text("Open your albums"), findsOneWidget);
    });

    testWidgets('a refused name is said at the field, the form stays',
        (tester) async {
      await pumpInvitation(
        tester,
        liveInvitation(
          pairAnswer: refusal(
            409,
            "The name 'carol' is already taken on this server. "
                "Choose another one.",
          ),
        ),
      );

      await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.byKey(const Key("invitation-join")));

      expect(
        find.text("The name 'carol' is already taken on this server. "
            "Choose another one."),
        findsOneWidget,
      );
      expect(find.byKey(invitationUserFieldKey), findsOneWidget);
      expect(find.byKey(const Key("invitation-join")), findsOneWidget);
    });

    testWidgets('an invitation that died replaces the form by the plain page',
        (tester) async {
      await pumpInvitation(
        tester,
        liveInvitation(
          pairAnswer: refusal(410, "This invitation was already used."),
        ),
      );

      await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.byKey(const Key("invitation-join")));

      expect(find.byKey(const Key("share-plain-page")), findsOneWidget);
      expect(find.text("This invitation was already used."), findsOneWidget);
      expect(find.byKey(invitationUserFieldKey), findsNothing);
    });
  });

  group('an invitation that is not one', () {
    testWidgets('a 410 at the start is the plain page and nothing else',
        (tester) async {
      await pumpInvitation(
        tester,
        (request) => refusal(410, "This invitation has expired."),
      );

      expect(find.byKey(const Key("share-plain-page")), findsOneWidget);
      expect(find.text("This invitation has expired."), findsOneWidget);
      expect(find.byKey(invitationUserFieldKey), findsNothing);
    });

    testWidgets('a token that is neither starts the app as it always does',
        (tester) async {
      var session = await pumpInvitation(tester, (request) {
        if (request.url.queryParameters["type"] == "auth") {
          return json(authOfNobody);
        }
        if (request.url.queryParameters["type"] == "json") {
          return json(rootListing());
        }
        return http.Response("No such resource", 404);
      });

      // The ordinary start: the device was read, and its library is shown.
      expect(session.store.wasRead, isTrue);
      expect(find.text("My albums"), findsOneWidget);
      expect(find.byKey(invitationUserFieldKey), findsNothing);
    });
  });

  group('an invitation pasted into the server field', () {
    testWidgets('switches the sign-in over and signs in with it',
        (tester) async {
      var store = InMemorySettingsStore();
      var settings = ServerSettings(store: store);
      await settings.load();

      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          requests.add(request);
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfInvitation(role: "view", note: ""));
          }
          return json(pairedAnswer);
        }),
      );

      await tester.enterText(
        find.byKey(serverUrlFieldKey),
        "http://server/valbum/i/inv-1/",
      );
      await tester.pumpAndSettle();

      expect(find.byKey(invitationSectionKey), findsOneWidget);
      expect(find.byKey(pairingSecretFieldKey), findsNothing);
      expect(
        find.text("alice invited you to this album server: you may look at "
            "the albums."),
        findsOneWidget,
      );
      var probe = requests.single;
      expect(probe.url.queryParameters["type"], "auth");
      expect(probe.headers["Authorization"], "Bearer inv-1");
      // The token is shown masked, so that a person sees which one they
      // pasted and nobody reads it off the screen.
      expect(find.text("Invitation ${maskedToken("inv-1")}"), findsOneWidget);

      await tester.enterText(find.byKey(userNameFieldKey), "carol");
      await tester.pumpAndSettle();
      await tapVisible(tester, signInButton);

      // The management sections of issue #55 ask their own questions once the
      // sign-in named a role, so the pairing request is picked out by name.
      var pair = requests
          .where((request) => request.url.queryParameters["action"] == "pair")
          .single;
      expect(pair.url.queryParameters["action"], "pair");
      expect(pair.body, contains('"invitation":"inv-1"'));
      expect(pair.body, contains('"userName":"carol"'));
      expect(store.value, serverUrl);
      expect(store.token, "dev-9");
    });

    testWidgets('a share link is refused, and signs nothing in',
        (tester) async {
      var settings = ServerSettings(store: InMemorySettingsStore());
      await settings.load();
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async => json(authOfNobody)),
      );

      await tester.enterText(
        find.byKey(serverUrlFieldKey),
        "http://server/valbum/s/tok/",
      );
      await tester.pumpAndSettle();

      expect(find.byKey(shareLinkRefusalKey), findsOneWidget);
      expect(find.text(shareLinkRefusal), findsOneWidget);
      expect(find.byKey(userNameFieldKey), findsNothing);
      expect(find.byKey(pairingSecretFieldKey), findsNothing);
      expect(signInButton, findsNothing);
    });
  });

  group('inviting', () {
    testWidgets('an admin creates an invitation and sees its URL once',
        (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore("http://server/valbum/", "admin-token",
            "Desk", "haui"),
      );
      await settings.load();

      var requests = <http.Request>[];
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          requests.add(request);
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfUser("admin", name: "haui"));
          }
          return json(
            '{"invitation": {"id": "i7", "role": "view", "note": "Party", '
            '"expires": "2026-12-24T17:00:00Z", "invitedBy": "haui", '
            '"created": "", "used": "", "usedBy": "", "revoked": ""}, '
            '"token": "tok", "url": "/valbum/i/tok/"}',
          );
        }),
      );

      await tapVisible(tester, find.byKey(inviteButtonKey));
      expect(find.byKey(const Key("invite-dialog")), findsOneWidget);

      // The permission the invited person gets, chosen here (issue #85).
      await tester.tap(find.byKey(const Key("invite-role-contribute")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("invite-clearance-all")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("invite-may-share")));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key("invite-note")), "Party");
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("invite-expiry-week")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("invite-create")));
      await tester.pumpAndSettle();

      var invite = requests.last;
      expect(invite.url.queryParameters["action"], "invite");
      expect(invite.body, contains('"role":"contribute"'));
      expect(invite.body, contains('"clearance":"all"'));
      expect(invite.body, contains('"mayShare":true'));
      expect(invite.body, contains('"note":"Party"'));
      var expires = RegExp('"expires":"([^"]+)"').firstMatch(invite.body)![1]!;
      var instant = DateTime.parse(expires);
      expect(instant.isUtc, isTrue);
      expect(expires.endsWith("Z"), isTrue);
      var days = instant.difference(DateTime.now().toUtc()).inHours / 24;
      expect(days, greaterThan(6.5));
      expect(days, lessThan(7.5));

      expect(
        find.text("http://server/valbum/i/tok/"),
        findsOneWidget,
      );
      expect(find.byKey(const Key("invite-copy")), findsOneWidget);
      expect(find.byKey(const Key("invite-once")), findsOneWidget);
    });

    testWidgets('a refusal is shown in the dialog', (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore(
            "http://server/valbum/", "member-token", "Desk", "bob"),
      );
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          var query = request.url.queryParameters;
          if (query["type"] == "auth") {
            return json(authOfUser("member", name: "bob"));
          }
          // The management sections of issue #55 ask their own questions; the
          // refusal under test is the one of the invite dialog alone.
          if (query["type"] == "devices") {
            return json('{"devices": []}');
          }
          if (query["type"] == "invitations") {
            return json('{"invitations": []}');
          }
          return refusal(
            403,
            "The administrator invites people on this server. "
                "Ask them for an invitation.",
          );
        }),
      );

      await tapVisible(tester, find.byKey(inviteButtonKey));
      await tester.tap(find.byKey(const Key("invite-create")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("invite-refusal")), findsOneWidget);
      expect(
        find.text("The administrator invites people on this server. "
            "Ask them for an invitation."),
        findsOneWidget,
      );
    });

    testWidgets('a guest is offered no invitation at all', (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore(
            "http://server/valbum/", "guest-token", "Phone", "carol"),
      );
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((request) async => json(authOfUser("guest"))),
      );

      expect(find.byKey(inviteButtonKey), findsNothing);
      expect(find.text(guestLibraryNotice), findsOneWidget);
    });
  });

  group('the library of somebody who may only look', () {
    // The guest of issue #52 is a `view` user since issue #85, and what is
    // offered follows from that role where the folder itself carries no
    // rights. Every test here pumps its own app: a popup menu that is
    // dismissed to open another one makes a test about what is offered depend
    // on where a stray tap lands.
    testWidgets('offers nothing that would put something into it',
        (tester) async {
      await pumpLibraryOf(tester, "view", rights: const []);
      await openMenu(tester);

      expect(find.text("Create album"), findsNothing);
      expect(find.text("Create folder"), findsNothing);
      expect(find.text("Folder properties"), findsNothing);
      expect(find.text("Apply rule"), findsNothing);
    });

    testWidgets('a member with the same listing is offered all of it',
        (tester) async {
      await pumpLibraryOf(
        tester,
        "member",
        grantsAnswer: json('{"grants": []}'),
      );
      await openMenu(tester);

      expect(find.text("Create album"), findsOneWidget);
      expect(find.text("Create folder"), findsOneWidget);
      expect(find.text("Folder properties"), findsOneWidget);
      expect(find.text("Apply rule"), findsOneWidget);
      expect(find.text("Share link…"), findsOneWidget);
    });

    testWidgets('a member moves and shares from the tile menu', (tester) async {
      await pumpLibraryOf(
        tester,
        "member",
        grantsAnswer: json('{"grants": []}'),
      );
      await longPressTile(tester, "Zoo");

      expect(find.text("Move to…"), findsOneWidget);
      expect(find.text("Share link…"), findsOneWidget);
    });
  });
}
