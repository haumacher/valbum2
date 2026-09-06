/// Review probe of the first-browser-test fixes (issue #35), composed with
/// pre-existing features: a deep link into a folder with an apostrophe and a
/// space, a server that refuses reads until the device is paired, the pairing
/// from the settings screen, saving the same server under another spelling,
/// and the way back from the album to its listing.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/urls.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Future<void> settle(WidgetTester tester, Future<void> Function() body) =>
    withFakeImageHttp(() async {
      await body();
      await tester.pumpAndSettle();
    });

const String refusal =
    '["ErrorInfo",{"message":"This server requires a paired device."}]';

/// A server started with `--auth all`: pairing always works, anything else
/// needs the token; the inbox folder is an album, the root a listing.
http.Response authAllServer(http.Request request) {
  if (request.url.queryParameters["action"] == "pair") {
    return http.Response('{"token":"tok-all","deviceName":"Probe"}', 200);
  }
  if (request.headers["Authorization"] != "Bearer tok-all") {
    return http.Response(refusal, 401,
        headers: {"content-type": "application/json; charset=utf-8"});
  }
  var path = Uri.decodeComponent(request.url.path);
  var body = path.contains("Haui's inbox") ? "album.json" : "listing.json";
  return http.Response(fixture(body), 200,
      headers: {"content-type": "application/json; charset=utf-8"});
}

void main() {
  group('appBasePath with an encoded app base and deep routes', () {
    test('the app base itself may carry encoded characters', () {
      expect(
        appBasePath("/my%20album/Haui's%20inbox/", "/Haui's inbox/"),
        "/my album/",
      );
      expect(
        appBasePath("/my%20album/Haui%27s%20inbox/", "/Haui's inbox/"),
        "/my album/",
      );
    });

    test('a member deep link and the root still yield the app base', () {
      expect(
        appBasePath(
          "/valbum/2005-08-24%20Blumen/IMG_0417.JPG/alternatives/IMG_0418.JPG",
          "/2005-08-24 Blumen/IMG_0417.JPG/alternatives/IMG_0418.JPG",
        ),
        "/valbum/",
      );
      expect(appBasePath("/valbum/", "/"), "/valbum/");
      expect(appBasePath("/valbum/index.html", "/"), "/valbum/");
      expect(appBasePath("/", "/"), "/");
    });

    test('a route naming another folder falls back to the directory', () {
      expect(appBasePath("/valbum/a/", "/b/"), "/valbum/a/");
    });

    test('the derived data URL is usable for the decoded base', () {
      var base = appBasePath("/valbum/Haui's%20inbox/", "/Haui's inbox/");
      expect(
        deriveDataUrl(
          Uri.parse("http://host:8080/valbum/Haui's%20inbox/"),
          isWeb: true,
          basePath: base,
        ),
        "http://host:8080/valbum/data",
      );
    });
  });

  testWidgets(
      'a deep link at an auth=all server: pairing page, pairing, same-server save, way back',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var requests = <http.Request>[];
    var store = InMemorySettingsStore("http://server/valbum/");
    var settings = ServerSettings(store: store, platformDefault: () => null);

    await settle(tester, () async {
      await tester.pumpWidget(VAlbumApp(
        client: clientHandling(authAllServer, requests: requests),
        settings: settings,
        initialRoute: const ListingOrAlbumRoute(["Haui's inbox"]),
      ));
    });

    // The refusal is a pairing page, not a "Loading failed" with a stack of
    // HTML; the server's own words are on it.
    expect(find.text("Sign-in required"), findsWidgets);
    expect(find.text("This server requires a paired device."), findsOneWidget);
    expect(find.textContaining("FormatException"), findsNothing);
    expect(find.textContaining("Loading failed"), findsNothing);

    // Pair from the page's own button.
    await settle(tester, () => tester.tap(find.text("Server settings...")));
    await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
    await settle(tester, () async {
      await tester.ensureVisible(find.widgetWithText(FilledButton, "Sign in"));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, "Sign in"));
    });
    expect(store.token, "tok-all");

    // Saving the same server under another spelling keeps the token.
    await tester.enterText(
        find.byKey(serverUrlFieldKey), "http://server/valbum/index.html");
    await settle(tester, () async {
      await tester.ensureVisible(find.text("Save"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Save"));
    });
    expect(store.token, "tok-all");

    // Back in the app: the deep link is still the view shown, loaded with
    // the token, i.e. the album of the inbox folder.
    if (find.text("Save").evaluate().isNotEmpty) {
      await settle(tester, () => tester.pageBack());
    }
    expect(find.text("Schlosspark Karlsruhe"), findsWidgets);
    var albumLoads = requests.where((r) =>
        Uri.decodeComponent(r.url.path).contains("Haui's inbox") &&
        r.url.queryParameters["type"] == "json" &&
        r.headers["Authorization"] == "Bearer tok-all");
    expect(albumLoads, isNotEmpty);

    // The album has parts and still offers the way back to the index.
    var up = find.byTooltip("Up");
    expect(up, findsOneWidget);
    await settle(tester, () => tester.tap(up));
    expect(find.text("Test-album"), findsOneWidget);
    expect(find.byTooltip("Home"), findsNothing, reason: "this is the home");
  });

  testWidgets(
      'a 200 that is HTML names the URL that answered, not a parse error',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await settle(tester, () async {
      await tester.pumpWidget(VAlbumApp(
        client: clientHandling(
          (request) => http.Response(
            "<!DOCTYPE html><html><body>app</body></html>",
            200,
            headers: {"content-type": "text/html; charset=utf-8"},
          ),
          dataUrl: "http://wrong/place/data",
        ),
        initialRoute: const ListingOrAlbumRoute(["Haui's inbox"]),
      ));
    });

    expect(find.textContaining("FormatException"), findsNothing);
    expect(find.textContaining("<!DOCTYPE"), findsNothing);
    expect(
        find.textContaining("did not answer with album data"), findsOneWidget);
    expect(find.textContaining("http://wrong/place/data"), findsOneWidget);
    expect(find.text("Server settings..."), findsOneWidget);
  });
}
