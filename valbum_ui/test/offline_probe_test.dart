/// Review probe of the offline cache (issue #31) composed with the server
/// settings (#27) and deep links (#24): the cache is keyed by server, so a
/// server switched to while offline shows nothing of the previous one, while an
/// album seen before and a deep link into it still open from the copy.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Future<void> settle(WidgetTester tester, Future<void> Function() body) =>
    withFakeImageHttp(() async {
      await body();
      await tester.pumpAndSettle();
    });

VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

void main() {
  const album = "2002-03-03 Schlosspark Karlsruhe";

  testWidgets('the copy belongs to its server and serves deep links',
      (tester) async {
    var reachable = true;
    var transport = MockClient(servingThumbnails((request) async {
      if (!reachable) {
        throw http.ClientException("Connection refused", request.url);
      }
      if (request.url.host != "server") {
        return http.Response("", 404);
      }
      // The path on the wire is percent-encoded.
      var body = Uri.decodeComponent(request.url.path).contains(album)
          ? fixture("album.json")
          : fixture("listing.json");
      return http.Response(
        body,
        200,
        headers: {"content-type": "application/json; charset=utf-8"},
      );
    }));
    var cache = MemoryOfflineCache();
    var state = OfflineState();
    var store = InMemorySettingsStore("http://server/valbum/");
    var settings = ServerSettings(store: store, platformDefault: () => null);

    await settle(tester, () async {
      await tester.pumpWidget(VAlbumApp(
        client: VAlbumClient(
          dataUrl: "http://server/valbum/data",
          httpClient: transport,
        ),
        settings: settings,
        cache: cache,
        offlineState: state,
      ));
    });
    expect(find.text("Test-album"), findsOneWidget);

    // Visit the album, so that it is cached too, and come back.
    await settle(tester, () => tester.tap(find.text("Schlosspark Karlsruhe")));
    expect(await routerOf(tester).popRoute(), isTrue);
    await settle(tester, () async {});
    expect(find.text("Test-album"), findsOneWidget);

    reachable = false;

    // Pointed at another server while offline: nothing of this one may show.
    await settings.save("http://other/valbum/");
    await settle(tester, () async {});
    expect(find.text("Test-album"), findsNothing);
    expect(find.textContaining("nothing is cached"), findsOneWidget);
    // Unreachable is offline even without a copy, but no stamp is claimed.
    expect(state.offline, isTrue);
    expect(state.lastUpdated, isNull);

    // Back to the server seen before: its copy, marked as such.
    await settings.save("http://server/valbum/");
    await settle(tester, () async {});
    expect(find.text("Test-album"), findsOneWidget);
    expect(state.lastUpdated, isNotNull);
    expect(find.textContaining("showing the copy from"), findsOneWidget);

    // Descending into the album seen before works from the copy as well ...
    await settle(tester, () => tester.tap(find.text("Schlosspark Karlsruhe")));
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(find.textContaining("showing the copy from"), findsOneWidget);

    // ... and so does a deep link into one of its images.
    routerOf(tester).go(const ImageRoute([album], "landscape.jpg"));
    await settle(tester, () async {});
    expect(find.byType(ImageView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
