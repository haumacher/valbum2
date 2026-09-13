/// Review probe of the empty-listing sentence (issue #56) composed with the
/// link tiles of issue #50 and with an anonymous caller: a guest whose root
/// holds nothing but links has a library, not an empty page, and a caller
/// the server never named reads an empty root as an empty library without
/// being told to create anything.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

Future<void> pumpRoot(
  WidgetTester tester, {
  required String auth,
  required String root,
  String? token,
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: token,
    userName: token == null ? "" : "carol",
    httpClient: MockClient(servingThumbnails((request) async {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(auth);
      }
      if (query["type"] == "json") {
        return json(root);
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    })),
  );
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: token,
        userName: token == null ? null : "carol",
        loaded: true,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('a guest root holding only links is not empty', (tester) async {
    await pumpRoot(
      tester,
      token: "dev-9",
      auth: '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
          '"userName": "carol", "role": "guest", "space": "carol"}',
      root: '["ListingInfo", {"path": "", "title": "carol", '
          '"rights": [{"name": "view"}], "folders": ['
          '{"name": "Holidays", "title": "Holidays", "link": "~bob/Holidays"}'
          ']}]',
    );

    expect(find.byKey(const Key("listing-empty")), findsNothing);
    expect(find.textContaining("Nothing has been shared"), findsNothing);
    expect(find.text("Holidays"), findsOneWidget);
  });

  testWidgets('an anonymous caller reads an empty root as an empty library',
      (tester) async {
    await pumpRoot(
      tester,
      auth: '{"mode": "writes", "writeAllowed": false}',
      root: '["ListingInfo", {"path": "", "title": "Photos", '
          '"rights": [{"name": "view"}], "folders": []}]',
    );

    expect(find.byKey(const Key("listing-empty")), findsOneWidget);
    expect(find.text("There are no albums here yet."), findsOneWidget);
    expect(find.textContaining("Create the first one"), findsNothing);
    expect(find.textContaining("Nothing has been shared"), findsNothing);
  });
}
