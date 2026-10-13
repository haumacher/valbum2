/// The overview of everything shared with a contact (issue #267): one tile
/// per link, a tile opening that link's own view as a page of its own, "All
/// shared with you" in the menu of every link view, the sign-in offer of a
/// browser the server does not recognise, and a contact's own overview link.
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

const String overviewAddress = "http://server/valbum/shared/";

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The overview of Tante Petra: the zoo (looking and downloading, with a
/// picture) and the year (contributing, without one).
final String shared = jsonEncode({
  "contact": {"id": "c1", "displayName": "Tante Petra"},
  "otherSessions": 0,
  "albums": [
    {
      "link": "L1",
      "folder": {
        "name": "Zoo",
        "title": "Zoo",
        "indexPicture": {"image": "a.jpg"},
      },
      "sharedBy": "alice",
      "expires": "",
      "rights": [
        {"name": "view"},
        {"name": "download"},
      ],
    },
    {
      "link": "L2",
      "folder": {"name": "2024", "title": ""},
      "sharedBy": "bob",
      "expires": "2026-12-24T12:00:00Z",
      "rights": [
        {"name": "view"},
        {"name": "contribute"},
      ],
    },
  ],
});

/// The `?type=auth` answer of a contact's session of a link.
const String authOfContact = '{"mode": "writes", "writeAllowed": false, '
    '"share": {"expires": "", "rights": [{"name": "view"}], '
    '"path": "~alice/2024/Zoo", "type": "PERSONAL", '
    '"contact": {"id": "c1", "displayName": "Tante Petra"}}}';

const String zooAlbum =
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"rights": [{"name": "view"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// The sign-in offer of the overview.
http.Response sharedRefusal() => json(
      jsonEncode([
        "ErrorInfo",
        {
          "message": "Confirm who you are to see everything that was shared "
              "with you.",
          "identify": {
            "firstOpen": false,
            "addresses": [],
            "methods": [
              {"name": "mail-code", "label": ""},
            ],
            "title": "",
            "sharedBy": "",
            "shared": true,
          },
        },
      ]),
      401,
    );

class Harness {
  final requests = <http.Request>[];
  final store = ContactCredentialStore.memory();
  final opened = <String>[];
  final pages = <String>[];

  Future<void> pump(
    WidgetTester tester,
    http.Response Function(http.Request) handler, {
    OverviewUrl? overview,
    SessionUrl? session,
    bool settle = true,
  }) async {
    tester.platformDispatcher.localesTestValue = [const Locale("en")];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    var serve = servingThumbnails(handler);
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient((request) async {
        requests.add(request);
        return serve(request);
      }),
    );
    await tester.pumpWidget(VAlbumApp(
      key: UniqueKey(),
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(dataUrl, "", "", ""),
        platformDefault: () => dataUrl,
      ),
      overview: overview,
      session: session,
      contactStore: store,
      location: Uri.parse(overviewAddress),
      rewriteLocation: (_) {},
      openUrl: opened.add,
      openPage: pages.add,
    ));
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      // A page that leaves shows its progress until the browser goes.
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
    }
  }
}

/// The overview's server: Tante Petra's albums for her credential, the
/// sign-in offer for anybody else.
http.Response overviewServer(http.Request request) {
  if (request.headers[VAlbumClient.contactHeader] != "cred-1") {
    return sharedRefusal();
  }
  if (request.url.queryParameters["type"] == "shared") {
    return json(shared);
  }
  return json('["ErrorInfo", {"message": "no"}]', 400);
}

void main() {
  const overview = OverviewUrl(dataUrl: dataUrl);

  testWidgets('one tile per link, each as its link shows it', (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(tester, overviewServer, overview: overview);

    expect(find.byKey(const Key("shared-overview")), findsOneWidget);
    expect(find.text(testL10n.sharedWithYouTitle), findsOneWidget);
    expect(find.byKey(const Key("shared-album-L1")), findsOneWidget);
    expect(find.byKey(const Key("shared-album-L2")), findsOneWidget);
    expect(find.text("Zoo"), findsOneWidget);
    expect(find.text("2024"), findsOneWidget,
        reason: "an untitled folder is named by its name");
    expect(find.text(testL10n.sharedAlbumFrom("alice")), findsOneWidget);
    expect(find.text(testL10n.sharedAlbumFrom("bob")), findsOneWidget);
    expect(find.text(testL10n.expiresNever), findsOneWidget);
    expect(find.byKey(const Key("shared-album-download-L1")), findsOneWidget);
    expect(find.byKey(const Key("shared-album-download-L2")), findsNothing);
    expect(find.byKey(const Key("shared-album-contribute-L2")), findsOneWidget);
    expect(find.byKey(const Key("shared-album-contribute-L1")), findsNothing);

    // The overview asks with the credential alone, never a bearer.
    var asked = h.requests.firstWhere(
        (request) => request.url.queryParameters["type"] == "shared");
    expect(asked.headers[VAlbumClient.sharedHeader], VAlbumClient.sharedAll);
    expect(asked.headers[VAlbumClient.contactHeader], "cred-1");
    expect(asked.headers["Authorization"], isNull);

    // The picture is asked as the link whose view it is.
    var picture = h.requests
        .firstWhere((request) => request.url.queryParameters["type"] == "tn");
    expect(picture.url.path, "/valbum/data/a.jpg");
    expect(picture.headers[VAlbumClient.sharedHeader], "L1");
    expect(picture.headers[VAlbumClient.contactHeader], "cred-1");
  });

  testWidgets('a tile opens its album as a page of its own', (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(tester, overviewServer, overview: overview);

    await tester.tap(find.byKey(const Key("shared-album-L1")));
    await tester.pumpAndSettle();

    expect(h.pages, ["${overviewAddress}L1/"],
        reason: "kept in the history: the back button returns here");
    expect(h.opened, isEmpty);
  });

  testWidgets('an album of the overview is its link, and leads back',
      (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(
      tester,
      (request) {
        if (request.headers[VAlbumClient.sharedHeader] != "L1" ||
            request.headers[VAlbumClient.contactHeader] != "cred-1") {
          return json('["ErrorInfo", {"message": "no"}]', 400);
        }
        return request.url.queryParameters["type"] == "auth"
            ? json(authOfContact)
            : json(zooAlbum);
      },
      session: const SessionUrl(
        kind: SessionKind.shared,
        token: "L1",
        dataUrl: dataUrl,
        basePath: "/valbum/shared/L1/",
      ),
    );

    expect(find.byType(AlbumContent), findsOneWidget);
    for (var request in h.requests) {
      expect(request.headers["Authorization"], isNull,
          reason: "the link's id beside the credential, never a token");
      expect(request.headers[VAlbumClient.sharedHeader], "L1");
    }

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("shared-overview-entry")));
    await tester.pumpAndSettle();

    expect(h.pages, [overviewAddress]);
  });

  testWidgets('a link opened at its own address offers the overview too',
      (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(
      tester,
      (request) => request.url.queryParameters["type"] == "auth"
          ? json(authOfContact)
          : json(zooAlbum),
      session: const SessionUrl(
        kind: SessionKind.share,
        token: "tok-42",
        dataUrl: dataUrl,
        basePath: "/valbum/s/tok-42/",
      ),
    );

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    expect(find.text(testL10n.sharedWithYouTitle), findsOneWidget);
    await tester.tap(find.byKey(const Key("shared-overview-entry")));
    await tester.pumpAndSettle();

    expect(h.pages, [overviewAddress]);
  });

  testWidgets('an anonymous link offers no overview', (tester) async {
    var h = Harness();
    await h.pump(
      tester,
      (request) => request.url.queryParameters["type"] == "auth"
          ? json('{"mode": "writes", "share": {"expires": "", '
              '"rights": [{"name": "view"}], "path": "~alice/Zoo", '
              '"type": "ANONYMOUS"}}')
          : json(zooAlbum),
      session: const SessionUrl(
        kind: SessionKind.share,
        token: "tok-42",
        dataUrl: dataUrl,
        basePath: "/valbum/s/tok-42/",
      ),
    );

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("shared-overview-entry")), findsNothing);
  });

  testWidgets('without a credential the overview asks who this is',
      (tester) async {
    var h = Harness();
    await h.pump(tester, overviewServer, overview: overview);

    expect(find.byKey(const Key("identify-screen")), findsOneWidget);
    var heading = tester.widget<Text>(find.byKey(const Key("identify-label")));
    expect(heading.data, testL10n.sharedWithYouTitle);
    expect(find.text(testL10n.identifySharedIntro), findsOneWidget);
    expect(find.byKey(const Key("identify-cover")), findsNothing,
        reason: "the overview is no album and has no cover");
    expect(find.byKey(const Key("identify-name")), findsNothing,
        reason: "the overview enters nobody new");
    expect(find.byKey(const Key("identify-address")), findsOneWidget);
  });

  testWidgets('without a way to sign in it asks for the overview link',
      (tester) async {
    var h = Harness();
    await h.pump(
      tester,
      (request) => json(
        jsonEncode([
          "ErrorInfo",
          {
            "message": "Confirm who you are.",
            "identify": {
              "firstOpen": false,
              "addresses": [],
              "methods": [],
              "title": "",
              "sharedBy": "",
              "shared": true,
            },
          },
        ]),
        401,
      ),
      overview: overview,
    );

    expect(find.text(testL10n.identifyAskOverviewAgain), findsOneWidget);
    expect(find.text(testL10n.identifyAskAgainNobody), findsNothing);
  });

  testWidgets('a proven address opens the overview', (tester) async {
    var h = Harness();
    await h.pump(
      tester,
      (request) {
        var action = request.url.queryParameters["action"];
        if (action == "prove-email") {
          return json('{"address": "p•••@gmx.de", '
              '"expires": "2027-01-01T00:00:00Z", "attempts": 5}');
        }
        if (action == "verify-email") {
          expect(request.headers[VAlbumClient.sharedHeader],
              VAlbumClient.sharedAll);
          return json('{"credential": "cred-1", '
              '"expires": "2027-01-01T00:00:00Z", "remember": true, '
              '"contact": {"id": "c1", "displayName": "Tante Petra"}}');
        }
        return overviewServer(request);
      },
      overview: overview,
    );

    await tester.enterText(
        find.byKey(const Key("identify-address")), "petra@gmx.de");
    await tester.tap(find.byKey(const Key("identify-send-code")));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key("identify-code")), "123456");
    await tester.tap(find.byKey(const Key("identify-verify")));
    await tester.pumpAndSettle();

    expect(h.store.read(dataUrl), "cred-1");
    expect(find.byKey(const Key("shared-overview")), findsOneWidget);
  });

  testWidgets('a proof naming a member signs the member in', (tester) async {
    var h = Harness();
    await h.pump(
      tester,
      (request) {
        var action = request.url.queryParameters["action"];
        if (action == "prove-email") {
          return json('{"address": "a•••@web.de", '
              '"expires": "2027-01-01T00:00:00Z", "attempts": 5}');
        }
        if (action == "verify-email") {
          return json('{"credential": "", "member": {"token": "dev-1", '
              '"deviceName": "Tablet", "userName": "alice"}}');
        }
        return overviewServer(request);
      },
      overview: overview,
    );

    await tester.enterText(
        find.byKey(const Key("identify-address")), "alice@web.de");
    await tester.tap(find.byKey(const Key("identify-send-code")));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key("identify-code")), "123456");
    await tester.tap(find.byKey(const Key("identify-verify")));
    await tester.pumpAndSettle();

    expect(h.opened, ["http://server/valbum/"]);
    expect(h.store.read(dataUrl), isNull, reason: "no contact was made");
  });

  testWidgets('switching person forgets the credential and asks again',
      (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(
      tester,
      (request) => request.url.queryParameters["action"] == "end-own-session"
          ? json('{"ended": 1}')
          : overviewServer(request),
      overview: overview,
    );

    await tester.tap(find.byKey(const Key("shared-overview-menu")));
    await tester.pumpAndSettle();
    expect(
        find.text(testL10n.signedInAsContact("Tante Petra")), findsOneWidget);
    await tester.tap(find.byKey(const Key("switch-person")));
    await tester.pumpAndSettle();

    expect(h.store.read(dataUrl), isNull);
    expect(find.byKey(const Key("identify-screen")), findsOneWidget);
  });

  testWidgets('an album that is gone says why and leads back', (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(
      tester,
      (_) => json(
          '["ErrorInfo", {"message": "This album is no longer shared with '
          'you."}]',
          410),
      session: const SessionUrl(
        kind: SessionKind.shared,
        token: "L9",
        dataUrl: dataUrl,
        basePath: "/valbum/shared/L9/",
      ),
    );

    expect(
        find.text("This album is no longer shared with you."), findsOneWidget);
    await tester.tap(find.byKey(const Key("share-overview")));
    await tester.pumpAndSettle();
    expect(h.opened, [overviewAddress]);
  });

  testWidgets('a contact\'s own overview link leads to the overview',
      (tester) async {
    var h = Harness();
    h.store.write(dataUrl, "cred-1", remember: true);
    await h.pump(
      tester,
      (request) => json('{"mode": "writes", "overview": true}'),
      session: const SessionUrl(
        kind: SessionKind.share,
        token: "ov-tok",
        dataUrl: dataUrl,
        basePath: "/valbum/s/ov-tok/",
      ),
      settle: false,
    );

    expect(h.opened, [overviewAddress]);
  });

  group('addresses', () {
    test('the overview and its albums are recognised in the app base', () {
      var found = overviewUrl(Uri.parse("http://h/valbum/shared/"));
      expect(found?.dataUrl, "http://h/valbum/data");
      expect(overviewUrl(Uri.parse("http://h/valbum/alice/shared/"))?.dataUrl,
          "http://h/valbum/alice/data");
      expect(overviewUrl(Uri.parse("http://h/valbum/")), isNull);
      expect(sessionUrl(Uri.parse("http://h/valbum/shared/")), isNull);

      var album = sessionUrl(Uri.parse("http://h/valbum/shared/L1/"))!;
      expect(album.kind, SessionKind.shared);
      expect(album.token, "L1");
      expect(album.dataUrl, "http://h/valbum/data");
      expect(album.isLink, isTrue);
      expect(album.overviewAddress, "http://h/valbum/shared/");
      expect(sharedAlbumAddress("http://h/valbum/data", "L1"),
          "http://h/valbum/shared/L1/");
    });

    test('the overview names its server in the server field', () {
      var location = serverLocationOf("http://h/valbum/shared/");
      expect(location.dataUrl, "http://h/valbum/data");
      expect(location.serverUrl, "http://h/valbum/");
    });
  });
}
