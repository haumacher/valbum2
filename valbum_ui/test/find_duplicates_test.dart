/// "Find duplicates..." in the album menu, the app half of the sweep of
/// issue #118.
///
/// The index knows where every content of the space is; this is the handle on
/// what slipped through while it was still being built. Nothing is deleted:
/// a photo the library holds elsewhere too is taken out of the album and kept
/// aside, and the answer says where the copy that stays is.
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

/// An album of two images, answered with the given rights.
String albumWith(List<String> rights) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "rights": ['
    '${rights.map((right) => '{"name": "$right"}').join(",")}], "parts": ['
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

/// A server answering the album with the given rights, and the sweep with the
/// given outcomes.
VAlbumClient server({
  required List<String> rights,
  required List<http.Request> requests,
  String outcomes = "",
  String? refusal,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf("edit"));
        }
        if (query["action"] == "find-duplicates") {
          if (refusal != null) {
            return http.Response(
              '["ErrorInfo", {"message": "$refusal"}]',
              403,
              headers: const {"content-type": "application/json"},
            );
          }
          return json('{"outcomes": [$outcomes]}');
        }
        return json(albumWith(rights));
      }),
    );

/// The sweeps asked for so far.
List<http.Request> sweepsIn(List<http.Request> requests) => [
      for (var request in requests)
        if (request.url.queryParameters["action"] == "find-duplicates") request,
    ];

Future<void> pumpAlbum(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: const ListingOrAlbumRoute(["Zoo"]),
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
  setUp(() {
    PaintingBinding.instance.imageCache
      ..clear()
      ..clearLiveImages();
  });

  testWidgets('somebody who may edit is offered the entry', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(rights: const ["view", "download", "contribute", "edit"],
            requests: []),
      );
      await openMenu(tester);

      expect(find.byKey(const Key("find-duplicates")), findsOneWidget);
      expect(find.text("Find duplicates..."), findsOneWidget);
    });
  });

  testWidgets('somebody who may only contribute is not', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(rights: const ["view", "download", "contribute"], requests: []),
      );
      await openMenu(tester);

      expect(find.byKey(const Key("find-duplicates")), findsNothing);
    });
  });

  testWidgets('the entry asks before anything is set aside', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(rights: const ["view", "download", "contribute", "edit"],
            requests: requests),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("find-duplicates")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("find-duplicates-dialog")), findsOneWidget);
      expect(find.textContaining("Nothing is deleted"), findsOneWidget);

      await tester.tap(find.text("Cancel"));
      await tester.pumpAndSettle();
      expect(sweepsIn(requests), isEmpty);
    });
  });

  testWidgets('the sweep posts the action and says what it did',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          rights: const ["view", "download", "contribute", "edit"],
          requests: requests,
          outcomes: '{"name": "a.jpg", "newName": "", '
              '"message": "already in Public/open.jpg"}',
        ),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("find-duplicates")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("find-duplicates-confirm")));
      await tester.pumpAndSettle();

      expect(sweepsIn(requests).length, 1);
      expect(sweepsIn(requests).single.url.path, endsWith("/Zoo/"));
      expect(find.textContaining("1 photo was set aside"), findsOneWidget);
    });
  });

  testWidgets('an album with no duplicate says so', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(rights: const ["view", "download", "contribute", "edit"],
            requests: requests),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("find-duplicates")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("find-duplicates-confirm")));
      await tester.pumpAndSettle();

      expect(
        find.textContaining("No photo of this album is anywhere else"),
        findsOneWidget,
      );
    });
  });

  testWidgets('a refusal is shown in the server own words', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          rights: const ["view", "download", "contribute", "edit"],
          requests: [],
          refusal: "You may not change this album.",
        ),
      );
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("find-duplicates")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("find-duplicates-confirm")));
      await tester.pumpAndSettle();

      expect(find.text("You may not change this album."), findsOneWidget);
    });
  });
}
