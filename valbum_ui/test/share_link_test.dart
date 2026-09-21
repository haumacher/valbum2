/// Tests of the app half of the share links of issue #51: opening a link as a
/// session, the plain pages of a link that is gone or does not cover a path,
/// and "Share link…" on the owner's side.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The link the session tests are opened at.
const SessionUrl link = SessionUrl(
  kind: SessionKind.share,
  token: "tok-42",
  dataUrl: dataUrl,
  basePath: "/valbum/s/tok-42/",
);

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

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

/// The `?type=auth` answer of a link caller.
String authOfLink({
  String label = "Party",
  List<String> rights = const ["view"],
  bool writeAllowed = false,
  String path = "~alice/2024/Zoo",
}) =>
    '{"mode": "writes", "deviceName": "", "writeAllowed": $writeAllowed, '
    '"userName": "", "role": "", "space": "alice", '
    '"share": {"label": "$label", "expires": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"path": "$path"}}';

/// The `?type=auth` answer of an ordinary caller: no link at all.
const String authOfNobody = '{"mode": "writes", "deviceName": "", '
    '"writeAllowed": false, "userName": "", "role": "", "space": ""}';

/// The album the link opens: one image, and the rights the link gives.
String sharedAlbum({List<String> rights = const ["view"]}) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// A settings store that records whether anybody read it.
///
/// A link session must neither wait for the device's settings nor read them:
/// a link names its own server and carries its own token. The real
/// `PreferencesSettingsStore` of a browser starts out unread, which is the
/// state that hung the app in the review — so that is the state the session
/// tests use.
class RecordingSettingsStore extends InMemorySettingsStore {
  /// How often the stored server URL was read.
  int urlReads = 0;

  /// How often the stored device token was read.
  int tokenReads = 0;

  RecordingSettingsStore()
      : super("http://other-server/valbum/", "device-token", "phone", "bob");

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

/// The settings of a device carrying a stored server URL and a stored device
/// token, over a store the tests inspect afterwards.
///
/// [loaded] is the one thing that differs between a device that had already
/// read its settings when the link was opened and one that had not — and a
/// real browser opening a link is always the latter, since a link session
/// never calls [ServerSettings.load].
({ServerSettings settings, RecordingSettingsStore store}) deviceSettings({
  bool loaded = false,
}) {
  var store = RecordingSettingsStore();
  return (
    settings: ServerSettings(
      store: store,
      platformDefault: () => "http://other-server/valbum/data",
      serverUrl: loaded ? "http://other-server/valbum/" : null,
      token: loaded ? "device-token" : null,
      userName: loaded ? "bob" : null,
      loaded: loaded,
    ),
    store: store,
  );
}

/// Pumps the app as a link session over the given handler.
Future<
    ({
      List<http.Request> requests,
      RecordingSettingsStore store,
    })> pumpLinkSession(
  WidgetTester tester,
  http.Response Function(http.Request request) handler, {
  SessionUrl session = link,
  bool settingsLoaded = false,
}) async {
  var requests = <http.Request>[];
  var device = deviceSettings(loaded: settingsLoaded);
  var client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      return handler(request);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: device.settings,
    session: session,
  ));
  await tester.pumpAndSettle();
  return (requests: requests, store: device.store);
}

/// The answers of a live link: the auth probe and the album it opens.
http.Response Function(http.Request) liveLink({
  List<String> rights = const ["view"],
  bool writeAllowed = false,
}) =>
    (request) {
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfLink(rights: rights, writeAllowed: writeAllowed));
      }
      if (request.url.queryParameters["type"] == "json") {
        return json(sharedAlbum(rights: rights));
      }
      return http.Response("No such resource: ${pathOf(request)}", 404);
    };

/// The share links `?type=shares` answers: one made here, one inherited.
const String sharesAnswer = '{"links": ['
    '{"id": "l1", "label": "Party", "expires": "2026-12-24T18:00:00Z", '
    '"maxPrivacy": 1, "minRating": -1, '
    '"rights": [{"name": "view"}, {"name": "download"}], '
    '"path": "2024/Zoo", "created": "2026-09-01T10:00:00Z", "revoked": ""}, '
    '{"id": "l2", "label": "Whole year", "expires": "", '
    '"maxPrivacy": 0, "minRating": -2, "rights": [{"name": "view"}], '
    '"path": "2024", "created": "2026-08-01T10:00:00Z", "revoked": ""}'
    ']}';

/// The answer of a created link, with its token shown once.
const String createdAnswer = '{"link": {"id": "l3", "label": "Party", '
    '"expires": "", "maxPrivacy": 1, "minRating": -1, '
    '"rights": [{"name": "view"}], "path": "2024/Zoo", "created": "", '
    '"revoked": ""}, "token": "tok", "url": "/valbum/s/tok/"}';

/// A client for the owner's dialog, signed in as `alice`.
VAlbumClient ownerClient(
  http.Response Function(http.Request request) handler, {
  List<http.Request>? requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "alice-token",
      userName: "alice",
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return handler(request);
      })),
    );

/// The standard answers of the owner's server.
http.Response ownerAnswers(http.Request request) {
  var type = request.url.queryParameters["type"];
  var action = request.url.queryParameters["action"];
  if (type == "shares") {
    return json(sharesAnswer);
  }
  if (action == "share") {
    return json(createdAnswer);
  }
  if (action == "unshare") {
    return json('{"links": []}');
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

/// Shows the share-link dialog on `2024/Zoo` of the caller's own space.
Future<void> pumpLinkDialog(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: testLocalizationsDelegates,
    supportedLocales: testSupportedLocales,
    home: Scaffold(
      body: ShareLinkDialog(client: client, path: const ["2024", "Zoo"]),
    ),
  ));
  await tester.pumpAndSettle();
}

/// Taps the widget with the given key, scrolling it into view first.
Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

/// The body of the last request of the given action.
String bodyOf(List<http.Request> requests, String action) => requests
    .lastWhere((request) => request.url.queryParameters["action"] == action)
    .body;

void main() {
  group('a link session', () {
    testWidgets('opens the shared album as its owner sees it (issue #97)',
        (tester) async {
      var run = await pumpLinkSession(tester, liveLink());

      // The state of a real browser: the device's settings were never read,
      // and the router did not wait for them — the review's endless splash.
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(run.store.wasRead, isFalse);
      // The album's own title, once, in its own immersive heading: no app bar
      // over it, and the link's label is not a caption.
      expect(find.text("Zoo"), findsOneWidget);
      expect(find.byType(AppBar), findsNothing);
      expect(find.text("Party"), findsNothing);
      expect(find.byKey(const Key("share-label")), findsNothing);
      // Nothing of the edit mode, and no way into the settings.
      expect(find.text("Share link…"), findsNothing);
      expect(find.text("Server..."), findsNothing);
      expect(find.byType(ServerSettingsScreen), findsNothing);
      // No upload: the link allows looking only.
      expect(find.byTooltip("Upload"), findsNothing);
    });

    testWidgets('offers the upload when the link allows contributions',
        (tester) async {
      await pumpLinkSession(
        tester,
        liveLink(rights: const ["view", "contribute"], writeAllowed: true),
      );

      expect(find.byTooltip("Upload"), findsOneWidget);
      // Still no edit mode: a link is not an account.
      expect(find.byKey(const Key("album-properties")), findsNothing);
      expect(find.byKey(const Key("share-with")), findsNothing);
    });

    testWidgets('carries the token of the URL and stores nothing',
        (tester) async {
      var run = await pumpLinkSession(tester, liveLink());

      expect(run.requests, isNotEmpty);
      for (var request in run.requests) {
        expect(
          request.headers["Authorization"],
          "Bearer tok-42",
          reason: pathOf(request),
        );
        // The link names its own server, never the one stored on the device.
        expect(request.url.toString(), startsWith(dataUrl));
      }
      // Nothing of the session reaches the device, and nothing of the device
      // is read: neither the stored server URL nor the stored device token.
      expect(run.store.urlReads, 0);
      expect(run.store.tokenReads, 0);
      expect(run.store.token, "device-token", reason: "left as it was");
      expect(run.store.value, "http://other-server/valbum/");
    });

    testWidgets(
        'shows the album on a device that had already loaded its '
        'settings', (tester) async {
      var run = await pumpLinkSession(
        tester,
        liveLink(),
        settingsLoaded: true,
      );

      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(find.text("Zoo"), findsOneWidget);
      // The stored token of that device takes no part in the session.
      for (var request in run.requests) {
        expect(request.headers["Authorization"], "Bearer tok-42");
      }
    });

    testWidgets('shows the server reason for a link that is gone',
        (tester) async {
      await pumpLinkSession(
        tester,
        (request) => refusal(410, "This link has expired."),
      );

      expect(find.text("This link has expired."), findsOneWidget);
      expect(find.byKey(const Key("share-gone")), findsOneWidget);
      expect(find.textContaining("Loading failed"), findsNothing);
      expect(find.byTooltip("Reload"), findsNothing);
      expect(find.textContaining("Server settings"), findsNothing);
      expect(find.byIcon(Icons.settings), findsNothing);
    });

    testWidgets('shows the server reason for a withdrawn link', (tester) async {
      await pumpLinkSession(
        tester,
        (request) => refusal(410, "This link was withdrawn."),
      );

      expect(find.text("This link was withdrawn."), findsOneWidget);
    });

    testWidgets('offers the way back from a path outside the link',
        (tester) async {
      const confined =
          "This link opens one album; there is nothing else to see from here.";
      await pumpLinkSession(
        tester,
        (request) {
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfLink());
          }
          if (pathOf(request).endsWith("/Elsewhere/")) {
            return refusal(404, confined);
          }
          return json(sharedAlbum());
        },
        session: link,
      );

      // The app starts at the root of the link; navigate outside it.
      var state = tester.state<VAlbumAppState>(find.byType(VAlbumApp));
      state.router.go(const ListingOrAlbumRoute(["Elsewhere"]));
      await tester.pumpAndSettle();

      expect(find.text(confined), findsOneWidget);
      expect(find.byKey(const Key("share-confined")), findsOneWidget);
      expect(find.textContaining("Loading failed"), findsNothing);

      await tapKey(tester, "share-home");
      expect(find.text("Zoo"), findsOneWidget);
    });

    testWidgets('falls back to an ordinary start when the token is no link',
        (tester) async {
      await pumpLinkSession(tester, (request) {
        if (request.url.queryParameters["type"] == "auth") {
          return json(authOfNobody);
        }
        return json(sharedAlbum(rights: const ["edit"]));
      });

      // No link to name, and the ordinary app is on the screen: the album
      // shows and the settings are reachable again.
      expect(find.byKey(const Key("share-label")), findsNothing);
      expect(find.byKey(const Key("share-gone")), findsNothing);
      expect(find.text("Zoo"), findsWidgets);
    });

    testWidgets('reads the device again when the token is no link',
        (tester) async {
      var run = await pumpLinkSession(tester, (request) {
        if (request.url.queryParameters["type"] == "auth") {
          return json(authOfNobody);
        }
        return json(sharedAlbum(rights: const ["edit"]));
      });

      // The ordinary start is the fallback, and it *is* the ordinary start:
      // the stored server URL and the stored token are read after all.
      expect(run.store.urlReads, greaterThan(0));
      expect(run.store.tokenReads, greaterThan(0));
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });
  });

  group('the share-link dialog', () {
    testWidgets('lists the links and marks the inherited one', (tester) async {
      await pumpLinkDialog(tester, ownerClient(ownerAnswers));

      expect(find.byKey(const Key("link-l1")), findsOneWidget);
      expect(find.byKey(const Key("link-l2")), findsOneWidget);
      expect(find.text("Party"), findsOneWidget);
      expect(find.textContaining("View, Download"), findsOneWidget);
      expect(find.textContaining("up to members"), findsOneWidget);
      expect(find.textContaining("Schlecht and better"), findsOneWidget);
      expect(find.textContaining("never expires"), findsOneWidget);
      expect(find.textContaining("public only"), findsOneWidget);
      // The inherited one says where it was made, and offers no withdrawal.
      expect(
        find.textContaining("inherited from '2024', withdraw it there"),
        findsOneWidget,
      );
      expect(find.byKey(const Key("withdraw-l1")), findsOneWidget);
      expect(find.byKey(const Key("withdraw-l2")), findsNothing);
    });

    testWidgets('withdraws a link by its id, after asking', (tester) async {
      var requests = <http.Request>[];
      await pumpLinkDialog(
        tester,
        ownerClient(ownerAnswers, requests: requests),
      );

      await tapKey(tester, "withdraw-l1");
      expect(find.byKey(const Key("withdraw-confirm")), findsOneWidget);
      await tapKey(tester, "withdraw-confirm-ok");

      expect(bodyOf(requests, "unshare"), contains('"id":"l1"'));
      expect(
        requests.any((request) =>
            request.url.queryParameters["action"] == "unshare" &&
            pathOf(request) == "/valbum/data/2024/Zoo/"),
        isTrue,
      );
    });

    testWidgets('creates a link with what the form shows', (tester) async {
      var requests = <http.Request>[];
      await pumpLinkDialog(
        tester,
        ownerClient(ownerAnswers, requests: requests),
      );

      await tapKey(tester, "new-link");
      await tester.enterText(find.byKey(const Key("link-label")), "Party");
      await tester.pumpAndSettle();
      await tapKey(tester, "expiry-week");
      await tapKey(tester, "privacy-members");
      await tapKey(tester, "rating--1");
      await tapKey(tester, "link-right-download");
      await tapKey(tester, "link-right-contribute");
      await tapKey(tester, "link-create");

      var body = bodyOf(requests, "share");
      expect(body, contains('"label":"Party"'));
      expect(body, contains('"maxPrivacy":1'));
      expect(body, contains('"minRating":-1'));
      expect(body, contains('"name":"view"'));
      expect(body, contains('"name":"download"'));
      expect(body, contains('"name":"contribute"'));
      expect(body, isNot(contains('"name":"edit"')));

      // About a week from now, as an ISO-8601 instant.
      var expires = RegExp('"expires":"([^"]*)"').firstMatch(body)!.group(1)!;
      var days =
          DateTime.parse(expires).difference(DateTime.now()).inHours / 24;
      expect(days, closeTo(7, 0.1));

      // The URL, once, made absolute against the server this client talks to.
      expect(find.byKey(const Key("share-link-url")), findsOneWidget);
      expect(find.text("http://server/valbum/s/tok/"), findsOneWidget);
      expect(find.byKey(const Key("copy-link")), findsOneWidget);
      expect(find.textContaining("can never show it again"), findsOneWidget);
    });

    testWidgets('shows the server reason for a refused link', (tester) async {
      await pumpLinkDialog(
        tester,
        ownerClient((request) {
          if (request.url.queryParameters["action"] == "share") {
            return refusal(403, "This is not your library.");
          }
          return ownerAnswers(request);
        }),
      );

      await tapKey(tester, "new-link");
      await tapKey(tester, "link-create");

      expect(find.text("This is not your library."), findsOneWidget);
      expect(find.byKey(const Key("share-link-url")), findsNothing);
    });

    testWidgets('says why the links could not be read', (tester) async {
      await pumpLinkDialog(
        tester,
        ownerClient((request) => refusal(403, "Not your library.")),
      );

      expect(find.byKey(const Key("share-link-error")), findsOneWidget);
      expect(find.text("Not your library."), findsOneWidget);
    });
  });

  group('"Share link…"', () {
    testWidgets('is offered to whoever manages the folder', (tester) async {
      var client = ownerClient((request) {
        var type = request.url.queryParameters["type"];
        if (type == "grants") {
          return json('{"grants": []}');
        }
        if (type == "shares") {
          return json(sharesAnswer);
        }
        if (type == "json") {
          return json('["ListingInfo", {"path": "", "title": "My albums", '
              '"folders": [{"name": "Zoo", "title": "Zoo"}]}]');
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      });
      await tester.pumpWidget(VAlbumApp(
        client: client,
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => dataUrl,
          token: "alice-token",
          userName: "alice",
          loaded: true,
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();

      expect(find.text("Share link…"), findsOneWidget);

      await tester.tap(find.text("Share link…"));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("share-link-dialog")), findsOneWidget);
      expect(find.byKey(const Key("link-l1")), findsOneWidget);
    });
  });
}
