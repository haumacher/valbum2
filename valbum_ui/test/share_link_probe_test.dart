/// Review probe of the app half of the share links of issue #51: the session
/// composed with the features that were there before it — a listing root
/// with a nested, date-named album and a link tile of issue #50, a link that
/// dies while the visitor is browsing, a whole-space link listed on a nested
/// folder, and the expiry the server has to be able to parse.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

const ShareSessionUrl link = ShareSessionUrl(
  token: "tok-42",
  dataUrl: dataUrl,
  basePath: "/valbum/s/tok-42/",
);

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

http.Response refusal(int status, String message) => http.Response(
      '["ErrorInfo", {"message": "$message"}]',
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A link onto a *folder*: the visitor's root is a listing, not an album.
String authOfFolderLink({bool writeAllowed = false}) =>
    '{"mode": "writes", "deviceName": "", "writeAllowed": $writeAllowed, '
    '"userName": "", "role": "", "space": "alice", '
    '"share": {"label": "Family 2002", "expires": "", '
    '"rights": [{"name": "view"}'
    '${writeAllowed ? ', {"name": "contribute"}' : ''}], '
    '"path": "~alice/2002"}}';

/// The listing the link opens: a date-named album with spaces in its name,
/// and a link tile the owner had filed there (issue #50).
const String sharedListing = '["ListingInfo", {"path": "", "title": "2002", '
    '"rights": [{"name": "view"}], '
    '"folders": ['
    '{"name": "2002-03-03 Schlosspark Karlsruhe", '
    '"title": "Schlosspark", "effectiveDate": 1015113600000}, '
    '{"name": "Zoo", "title": "Zoo", "link": "~bob/2002/Zoo", '
    '"effectiveDate": 1014508800000}'
    ']}]';

const String schlossparkAlbum = '["AlbumInfo", '
    '{"path": "2002-03-03 Schlosspark Karlsruhe", "title": "Schlosspark", '
    '"subTitle": "", "rights": [{"name": "view"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

Future<List<http.Request>> pumpSession(
  WidgetTester tester,
  http.Response Function(http.Request request) handler,
) async {
  var requests = <http.Request>[];
  var store = InMemorySettingsStore("http://other/valbum/", null, null, null);
  var client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      return handler(request);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: ServerSettings(
      store: store,
      platformDefault: () => "http://other/valbum/data",
      serverUrl: "http://other/valbum/",
      loaded: true,
    ),
    shareLink: link,
  ));
  await tester.pumpAndSettle();
  return requests;
}

/// A settings store that records whether anybody read it.
class WatchedStore extends InMemorySettingsStore {
  int reads = 0;

  WatchedStore() : super("http://other/valbum/", "device-token", "dev", "me");

  @override
  Future<String?> load() {
    reads++;
    return super.load();
  }

  @override
  Future<String?> loadToken() {
    reads++;
    return super.loadToken();
  }
}

void main() {
  testWidgets(
      'a link session on a device whose settings were never loaded shows the '
      'album without ever reading them (the browser hang of the review)',
      (tester) async {
    var store = WatchedStore();
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        if (request.url.queryParameters["type"] == "auth") {
          return json(authOfFolderLink());
        }
        return json(sharedListing);
      })),
    );
    await tester.pumpWidget(VAlbumApp(
      client: client,
      // Exactly the state of a real device: nothing loaded yet. A link
      // session must not wait for that, nor trigger it.
      settings: ServerSettings(store: store, platformDefault: () => null),
      shareLink: link,
    ));
    await tester.pumpAndSettle();

    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.byKey(const Key("share-label")), findsOneWidget);
    expect(find.text("Schlosspark"), findsOneWidget);
    expect(store.reads, 0, reason: "a link names its own server and token");
  });

  group('a link onto a folder', () {
    http.Response Function(http.Request) folderLink({
      bool writeAllowed = false,
      bool albumGone = false,
    }) =>
        (request) {
          var type = request.url.queryParameters["type"];
          if (type == "auth") {
            return json(authOfFolderLink(writeAllowed: writeAllowed));
          }
          var path = pathOf(request);
          if (type == "json" && path == "/valbum/data/") {
            return json(sharedListing);
          }
          if (type == "json" &&
              path == "/valbum/data/2002-03-03 Schlosspark Karlsruhe/") {
            return albumGone
                ? refusal(410, "This link has expired.")
                : json(schlossparkAlbum);
          }
          return http.Response("No such resource: $path", 404);
        };

    testWidgets('opens a listing root, named by the link, and descends '
        'into a date-named album without the token in the data path',
        (tester) async {
      var requests = await pumpSession(tester, folderLink());

      expect(find.byKey(const Key("share-label")), findsOneWidget);
      expect(find.text("Family 2002"), findsOneWidget);
      expect(find.text("Schlosspark"), findsOneWidget);

      await tester.tap(find.text("Schlosspark"));
      await tester.pumpAndSettle();

      var albumRequests = requests
          .where((r) => r.url.queryParameters["type"] == "json")
          .map(pathOf)
          .toList();
      expect(albumRequests,
          contains("/valbum/data/2002-03-03 Schlosspark Karlsruhe/"));
      expect(
        albumRequests.where((p) => p.contains("/s/")),
        isEmpty,
        reason: "the token belongs in the bearer, never in the data path",
      );
      for (var request in requests) {
        expect(request.headers["Authorization"], "Bearer tok-42");
      }
      expect(find.byKey(const Key("share-label")), findsOneWidget);
    });

    testWidgets('never offers to remove a link tile the owner filed there',
        (tester) async {
      await pumpSession(tester, folderLink());

      await tester.longPress(find.text("Zoo"));
      await tester.pumpAndSettle();

      expect(find.text("Remove from my albums"), findsNothing);
      expect(find.text("Share with…"), findsNothing);
      expect(find.text("Share link…"), findsNothing);
      expect(find.text("Move to…"), findsNothing);
    });

    testWidgets(
        'shows the plain page when the link dies while the visitor browses',
        (tester) async {
      await pumpSession(tester, folderLink(albumGone: true));

      await tester.tap(find.text("Schlosspark"));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("share-gone")), findsOneWidget);
      expect(find.text("This link has expired."), findsOneWidget);
      expect(find.textContaining("Loading failed"), findsNothing);
      expect(find.byTooltip("Reload"), findsNothing);
      expect(find.textContaining("Server settings"), findsNothing);
    });

    testWidgets('a contributing link on a folder creates no albums',
        (tester) async {
      await pumpSession(tester, folderLink(writeAllowed: true));

      expect(find.byKey(const Key("share-label")), findsOneWidget);
      // Whatever the listing offers for new albums or folders in a real
      // session, a link may only upload into the album it opens.
      expect(find.textContaining("New album"), findsNothing);
      expect(find.textContaining("New folder"), findsNothing);
      expect(find.byIcon(Icons.create_new_folder), findsNothing);
      expect(find.byIcon(Icons.add), findsNothing);
    });
  });

  group('the owner side', () {
    testWidgets(
        'lists a whole-space link on a nested folder as inherited, '
        'and sends an expiry the server can parse as an Instant',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling((request) {
        var type = request.url.queryParameters["type"];
        var action = request.url.queryParameters["action"];
        if (type == "shares") {
          return json('{"links": ['
              '{"id": "all", "label": "", "expires": "", "maxPrivacy": 0, '
              '"minRating": 2, "rights": [{"name": "view"}], "path": "", '
              '"created": "2026-01-01T00:00:00Z", "revoked": ""}]}');
        }
        if (action == "share") {
          return json('{"link": {"id": "n1", "label": "Day", "expires": "", '
              '"maxPrivacy": 0, "minRating": -2, "rights": [{"name": "view"}], '
              '"path": "2024/Zoo", "created": "2026-09-13T00:00:00Z", '
              '"revoked": ""}, "token": "t", "url": "/valbum/s/t/"}');
        }
        return http.Response("unexpected", 500);
      }, requests: requests);

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: ShareLinkDialog(client: client, path: const ["2024", "Zoo"]),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("link-all")), findsOneWidget);
      expect(find.byKey(const Key("withdraw-all")), findsNothing);
      expect(find.textContaining("inherited"), findsOneWidget);
      expect(find.textContaining("whole space"), findsOneWidget);

      await tester.tap(find.byKey(const Key("new-link")));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key("expiry-day")));
      await tester.tap(find.byKey(const Key("expiry-day")));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key("link-create")));
      await tester.tap(find.byKey(const Key("link-create")));
      await tester.pumpAndSettle();

      var body = requests
          .firstWhere((r) => r.url.queryParameters["action"] == "share")
          .body;
      var expires = RegExp('"expires":"([^"]*)"').firstMatch(body)!.group(1)!;
      // Java's Instant.parse wants an offset; a zone-less local timestamp is
      // refused by the server with a message.
      expect(expires, endsWith("Z"));
      expect(DateTime.parse(expires).isUtc, isTrue);
      var hours = DateTime.parse(expires).difference(DateTime.now()).inMinutes / 60;
      expect(hours, closeTo(24, 0.1));
      expect(body, isNot(contains('"name":"edit"')));
    });
  });

  group('the session URL', () {
    test('a two-segment context path keeps its whole context', () {
      var session = shareSessionUrl(
        Uri.parse("http://h:8080/photos/valbum/s/abc/"),
      )!;
      expect(session.token, "abc");
      expect(session.dataUrl, "http://h:8080/photos/valbum/data");
      expect(session.basePath, "/photos/valbum/s/abc/");
    });

    test('a folder that happens to be named "s" inside an ordinary app is '
        'not a session when the base path is given', () {
      // The base path is the app base, so an album path `/valbum/s/2005/`
      // browsed in an ordinary session never reaches this function; the
      // only base carrying `/s/` is the one the server rebased.
      expect(
        shareSessionUrl(
          Uri.parse("http://h/valbum/s/2005/"),
          basePath: "/valbum/",
        ),
        isNull,
      );
    });

    test('the absolute URL keeps the port and drops the data path', () {
      expect(
        absoluteServerUrl("http://nas.local:8080/valbum/data", "/valbum/s/t/"),
        "http://nas.local:8080/valbum/s/t/",
      );
      expect(absoluteServerUrl("https://h/data", "/s/t/"), "https://h/s/t/");
    });
  });
}
