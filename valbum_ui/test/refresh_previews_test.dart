/// "Refresh previews" in the album menu, the app half of issue #98.
///
/// A preview written by an early build could be truncated, and nothing ever
/// looks inside a cached file — so a broken thumbnail stayed broken for ever
/// and the author had no handle on the server's own cache. The administrator
/// now throws the album's generated files away; the server makes them anew on
/// demand, and the app forgets what it has decoded so the new ones are really
/// fetched.
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

/// The answer of `?type=auth` for a caller of the given role.
String authOf(String role) =>
    '{"mode": "all", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "", '
    '"clearance": "all", "mayShare": true}';

/// An album of two images.
const String album =
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}],'
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 2, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

http.Response json(String body) => http.Response(
      body,
      200,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// A server that answers the album, names the caller [role], and records the
/// path of every thumbnail asked for and every request that was made.
///
/// [refusal] makes the refresh answer `403` with that message, as the server
/// refuses a caller who is not the administrator.
VAlbumClient server({
  required String role,
  required List<String> thumbnails,
  required List<http.Request> requests,
  int removed = 3,
  String? refusal,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          thumbnails.add(request.url.path);
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf(role));
        }
        if (query["action"] == "refresh-cache") {
          if (refusal != null) {
            return http.Response(
              '["ErrorInfo", {"message": "$refusal"}]',
              403,
              headers: const {"content-type": "application/json"},
            );
          }
          // The bare object the server answers, as every other small result
          // of the API is answered.
          return json('{"removed": $removed}');
        }
        return json(album);
      }),
    );

/// The refresh requests made so far.
List<http.Request> refreshesIn(List<http.Request> requests) => [
      for (var request in requests)
        if (request.url.queryParameters["action"] == "refresh-cache") request,
    ];

/// Pumps the app on the album at `Zoo` and enters the edit session.
///
/// The album menu carries the entry in the edit session, where the album shows
/// its app bar at all; outside of it the same menu floats over the photos.
Future<void> pumpAlbum(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: const ListingOrAlbumRoute(["Zoo"]),
    // The app asks `?type=auth` for a signed-in device only, and the device
    // it signs in as is the one the settings name, see `app.dart`.
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "dev-9",
      userName: "carol",
      loaded: true,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

void main() {
  // Flutter's [ImageCache] is global and survives a test: without this the
  // album of the test before has already decoded these very thumbnails, and
  // the test that counts the fetches would count none at all.
  setUp(() {
    PaintingBinding.instance.imageCache
      ..clear()
      ..clearLiveImages();
  });

  testWidgets('an administrator is offered the entry', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(role: "admin", thumbnails: [], requests: []),
      );
      await openMenu(tester);

      expect(find.byKey(const Key("refresh-previews")), findsOneWidget);
      expect(find.text("Refresh previews"), findsOneWidget);
    });
  });

  testWidgets('somebody who may edit is not', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(role: "edit", thumbnails: [], requests: []),
      );
      await openMenu(tester);

      expect(find.byKey(const Key("refresh-previews")), findsNothing);
    });
  });

  testWidgets('the entry asks before it throws anything away', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(role: "admin", thumbnails: [], requests: requests),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("refresh-previews")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("refresh-previews-dialog")), findsOneWidget);
      expect(
        find.textContaining("The photos themselves are not touched."),
        findsOneWidget,
      );

      // Cancel sends nothing at all.
      await tester.tap(find.text("Cancel"));
      await tester.pumpAndSettle();
      expect(refreshesIn(requests), isEmpty);
    });
  });

  testWidgets('Refresh posts the action on the album and reports the count',
      (tester) async {
    var requests = <http.Request>[];
    var thumbnails = <String>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          role: "admin",
          thumbnails: thumbnails,
          requests: requests,
          removed: 7,
        ),
      );
      var before = thumbnails.length;
      expect(before, greaterThan(0), reason: "the tiles were fetched");

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("refresh-previews")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("refresh-previews-confirm")));
      await tester.pumpAndSettle();

      var refreshes = refreshesIn(requests);
      expect(refreshes.length, 1);
      expect(refreshes.single.method, "POST");
      expect(refreshes.single.url.path, "/valbum/data/Zoo/");
      expect(
        refreshes.single.headers["Authorization"],
        "Bearer dev-9",
        reason: "the admin's own token, or the server would refuse it",
      );

      expect(
        find.text("7 cached files thrown away; the previews are made anew."),
        findsOneWidget,
      );

      // The thumbnails were asked for again: the app forgot what it had
      // decoded, so the regenerated previews are really fetched.
      expect(
        thumbnails.length,
        greaterThan(before),
        reason: "the regenerated previews have to be fetched",
      );
      expect(thumbnails.sublist(before), thumbnails.sublist(0, before));
    });
  });

  testWidgets('a refusal is shown as the server worded it', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          role: "admin",
          thumbnails: [],
          requests: requests,
          refusal: "Only an administrator may refresh the cache.",
        ),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("refresh-previews")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("refresh-previews-confirm")));
      await tester.pumpAndSettle();

      expect(
        find.text("Only an administrator may refresh the cache."),
        findsOneWidget,
      );
    });
  });
}
