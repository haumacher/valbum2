/// Probe for #100/#98: the relocated menu entries composed with a share
/// session (nothing an outsider may do is offered) and with a dirty edit
/// session (the "View as" choice from the menu still speaks the #99 refusal).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

const String album =
    '["AlbumInfo", {"path": "Zoo", "title": "Zoo", "subTitle": "", "rights": [{"name": "view"}, {"name": "download"}, {"name": "contribute"}, {"name": "edit"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}],'
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 2, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

http.Response json(String body) => http.Response(body, 200,
    headers: const {"content-type": "application/json; charset=utf-8"});

VAlbumClient adminClient(List<http.Request> requests) => VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json('{"mode": "all", "deviceName": "Phone", "writeAllowed": true, '
              '"userName": "carol", "role": "admin", "space": "", "clearance": "all", "mayShare": true}');
        }
        return json(album);
      })),
    );

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a share visitor is offered neither View as nor Refresh previews',
      (tester) async {
    const share = SessionUrl(kind: SessionKind.share, token: "t0ken", dataUrl: dataUrl, basePath: "/valbum/s/t0ken/");
    var client = VAlbumClient(
      dataUrl: dataUrl,
      token: "t0ken",
      httpClient: MockClient(servingThumbnails((request) async {
        if (request.url.queryParameters["type"] == "auth") {
          return json('{"mode": "all", "deviceName": "", "writeAllowed": false, "userName": "", "role": "", '
              '"space": "", "share": {"id": "s1", "label": "For grandma", "path": "Zoo", "rights": [{"name": "view"}], "expires": ""}}');
        }
        return json(album);
      })),
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client, session: share));
      await tester.pumpAndSettle();
    });
    expect(find.byType(AlbumContent), findsOneWidget);

    await openMenu(tester);
    expect(find.byKey(const Key("refresh-previews")), findsNothing);
    expect(find.byKey(const Key("view-as-state")), findsNothing);
    expect(find.text("Share link…"), findsNothing);
    expect(find.text("Mehr Bilder zeigen"), findsOneWidget, reason: "the rating filter is a visitor's too");
  });

  testWidgets('View as from the menu still refuses while the album is dirty',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: adminClient(requests),
        initialRoute: const ListingOrAlbumRoute(["Zoo"]),
        // The app asks `?type=auth` for a signed-in device only.
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => dataUrl,
          token: "dev-9",
          userName: "carol",
          loaded: true,
        ),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(tile("a.jpg"));
      await tester.pumpAndSettle();
      await dragBehind(tester, "a.jpg", "b.jpg");
      expect(albumStateOf(tester).dirty, isTrue);

      await openMenu(tester);
      expect(find.byKey(const Key("view-as-state")), findsOneWidget);
      expect(find.byKey(const Key("refresh-previews")), findsOneWidget, reason: "an administrator's entry");
      await tester.tap(find.byKey(const Key("view-as-public")));
      await tester.pumpAndSettle();
    });

    expect(find.text("Save or discard your changes first"), findsOneWidget);
    expect(find.byKey(const Key("view-as-banner")), findsNothing);
    expect(albumStateOf(tester).dirty, isTrue, reason: "nothing was thrown away");
    expect(requests.where((r) => r.url.queryParameters.containsKey("viewAs")), isEmpty);
  });
}
