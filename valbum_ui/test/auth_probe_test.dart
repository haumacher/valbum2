/// Review probe of the authentication (issue #28) inside the whole app: a
/// device paired from the settings screen identifies itself on the next load,
/// a move to another server drops the token, and a reset forgets it entirely.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Future<void> settle(WidgetTester tester, Future<void> Function() body) =>
    withFakeImageHttp(() async {
      await body();
      await tester.pumpAndSettle();
    });

/// The listing on every server, pairing on every server.
http.Response anyServer(http.Request request) {
  if (request.url.queryParameters["action"] == "pair") {
    return http.Response('{"token":"tok-${request.url.host}","deviceName":"Pad"}', 200);
  }
  return http.Response(
    fixture("listing.json"),
    200,
    headers: {"content-type": "application/json; charset=utf-8"},
  );
}

void main() {
  testWidgets('a token paired in the app travels with the next load only for its server',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var requests = <http.Request>[];
    var store = InMemorySettingsStore("http://server/valbum/");
    var settings = ServerSettings(store: store, platformDefault: () => null);

    await settle(tester, () async {
      await tester.pumpWidget(VAlbumApp(
        client: clientHandling(anyServer, requests: requests),
        settings: settings,
      ));
    });
    expect(find.text("Test-album"), findsOneWidget);
    expect(requests.single.headers.containsKey("authorization"), isFalse);

    // Pair from within the app.
    await settle(tester, () => tester.tap(find.byIcon(Icons.more_vert)));
    await settle(tester, () => tester.tap(find.text("Server...")));
    await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
    await settle(tester, () async {
      await tester.ensureVisible(find.text("Pair this device"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Pair this device"));
    });
    expect(store.token, "tok-server");

    // Back in the listing, which was reloaded with the token.
    await settle(tester, () => tester.pageBack());
    expect(find.text("Test-album"), findsOneWidget);
    var authorized = requests.where(
      (r) => r.headers["authorization"] == "Bearer tok-server",
    );
    expect(authorized, isNotEmpty);
    expect(authorized.last.url.host, "server");

    // Moving to another server must not leak the token there.
    await settle(tester, () => tester.tap(find.byIcon(Icons.more_vert)));
    await settle(tester, () => tester.tap(find.text("Server...")));
    await tester.enterText(find.byKey(serverUrlFieldKey), "http://other/valbum/");
    await settle(tester, () => tester.tap(find.text("Save")));
    expect(requests.last.url.host, "other");
    expect(requests.last.headers.containsKey("authorization"), isFalse);
    expect(store.token, isNull);

    // A reset forgets the server and with it any pairing.
    await settle(tester, () => tester.tap(find.byIcon(Icons.more_vert)));
    await settle(tester, () => tester.tap(find.text("Server...")));
    await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
    await settle(tester, () async {
      await tester.ensureVisible(find.text("Pair this device"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Pair this device"));
    });
    expect(store.token, "tok-other");
    await settle(tester, () async {
      await tester.ensureVisible(find.text("Forget this server"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Forget this server"));
    });
    expect(store.value, isNull);
    expect(store.token, isNull);
    expect(find.text("Album server"), findsOneWidget);
  });
}
