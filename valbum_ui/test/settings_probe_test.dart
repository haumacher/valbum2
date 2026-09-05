/// Probe of the server settings (issue #27) composed with the rest of the app:
/// a server swap while a listing and an album are cached, a root that is an
/// album rather than a listing, a stored value an older build might have
/// written, and a user pasting the API URL instead of the app base.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Future<void> pump(WidgetTester tester, Widget app) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(app);
    await tester.pumpAndSettle();
  });
}

Future<void> tapAndSettle(WidgetTester tester, Finder finder) async {
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

/// Two servers behind one transport, told apart by host: `server` holds the
/// listing fixture with the Schlosspark album below it, `nas.local` holds an
/// album at its root.
http.Response twoServers(http.Request request) {
  var url = request.url;
  if (url.host == "nas.local") {
    return url.path == "/valbum/data/"
        ? _json(fixture("album-ratings.json"))
        : http.Response("no such resource", 404);
  }
  if (url.path == "/valbum/data/") {
    return _json(fixture("listing.json"));
  }
  if (url.path.startsWith("/valbum/data/2002-03-03")) {
    return _json(fixture("album.json"));
  }
  return http.Response("no such resource", 404);
}

http.Response _json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

void main() {
  testWidgets(
      'switching servers drops cached listing and album and shows an album root',
      (tester) async {
    var requests = <http.Request>[];
    var store = InMemorySettingsStore();
    var settings = ServerSettings(
      store: store,
      platformDefault: () => "http://server/valbum/data",
    );

    await pump(
      tester,
      VAlbumApp(
        client: clientHandling(twoServers, requests: requests),
        settings: settings,
      ),
    );
    expect(find.text("Test-album"), findsOneWidget);

    // Descend into the album and come back: both are cached now.
    await tapAndSettle(tester, find.text("Schlosspark Karlsruhe"));
    expect(find.text("Test-album"), findsNothing);
    var albumRequests = requests.length;
    // A full album shows no app bar; the system back button goes up.
    var router = tester
        .widget<MaterialApp>(find.byType(MaterialApp))
        .routerDelegate! as VAlbumRouterDelegate;
    expect(await router.popRoute(), isTrue);
    await withFakeImageHttp(() => tester.pumpAndSettle());
    expect(find.text("Test-album"), findsOneWidget);
    expect(requests.length, albumRequests, reason: "the listing is cached");

    // Point the app at the other server, whose root is an album.
    await tapAndSettle(tester, find.byIcon(Icons.more_vert));
    await tapAndSettle(tester, find.text("Server..."));
    await tester.enterText(find.byType(TextField), "http://nas.local/valbum");
    await tapAndSettle(tester, find.text("Save"));

    expect(store.value, "http://nas.local/valbum");
    expect(find.text("Album server"), findsNothing);
    expect(find.text("Test-album"), findsNothing);
    expect(find.text("Bewertungen"), findsOneWidget);
    expect(
      requests.last.url.toString(),
      "http://nas.local/valbum/data/?type=json",
    );

    // Back to the loaded-from server: the old cache is gone, so it is fetched
    // again rather than shown from memory.
    var before = requests.length;
    await settings.reset();
    await withFakeImageHttp(() => tester.pumpAndSettle());
    expect(find.text("Test-album"), findsOneWidget);
    expect(requests.length, before + 1);
    expect(requests.last.url.host, "server");
  });

  testWidgets('an unparseable stored value opens the settings on a device',
      (tester) async {
    var store = InMemorySettingsStore("not a url");
    await pump(
      tester,
      VAlbumApp(
        client: clientHandling(twoServers),
        settings: ServerSettings(store: store, platformDefault: () => null),
      ),
    );
    expect(find.text("Album server"), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('pasting the API URL instead of the app base is reported',
      (tester) async {
    await pump(
      tester,
      MaterialApp(
        home: ServerSettingsScreen(
          settings: ServerSettings(
            store: InMemorySettingsStore(),
            platformDefault: () => null,
          ),
          clientFor: (dataUrl) => clientHandling(twoServers, dataUrl: dataUrl),
        ),
      ),
    );
    await tester.enterText(
      find.byType(TextField),
      "http://nas.local/valbum/data",
    );
    await tapAndSettle(tester, find.text("Test connection"));
    expect(find.textContaining("404"), findsOneWidget);
  });
}
