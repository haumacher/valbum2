/// Review probe of the app half of issue #53: attribution and the take-back
/// composed with the app around them — the whole route through a #50 link
/// tile in the contributor's own tree, the alternatives view of a group whose
/// members were added by different people, and a refusal that leaves the
/// viewer where it was.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

String authOf(String name) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "member", "space": "$name"}';

/// Bob's root: alice's zoo as a link tile, and an album of his own.
const String bobsRoot = '["ListingInfo", {"path": "", "title": "My albums", '
    '"rights": [{"name": "edit"}], '
    '"folders": ['
    '{"name": "Zoo", "title": "Zoo", "link": "~alice/2024/Zoo"}, '
    '{"name": "Inbox", "title": "Inbox"}'
    ']}]';

String part(String name, String contributor, String label) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0, '
    '"contributor": "$contributor", "contributorLabel": "$label"}]';

/// Alice's zoo as bob reaches it through his link: he may add, not edit.
String zooAlbum() => '["AlbumInfo", {"path": "Zoo", "title": "Zoo", '
    '"subTitle": "", "rights": [{"name": "view"}, {"name": "contribute"}], '
    '"parts": [${part("a.jpg", "user:bob", "bob")}, '
    '${part("b.jpg", "user:alice", "alice")}]}]';

const String emptyInbox = '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
    '"subTitle": "", "rights": [{"name": "edit"}], "parts": []}]';

Future<List<http.Request>> pumpBob(
  WidgetTester tester, {
  http.Response Function(http.Request request)? onMove,
}) async {
  var requests = <http.Request>[];
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "dev-9",
    userName: "bob",
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      var type = request.url.queryParameters["type"];
      var path = pathOf(request);
      if (type == "auth") {
        return json(authOf("bob"));
      }
      if (request.url.queryParameters["action"] == "move") {
        return onMove!(request);
      }
      if (type == "json" && path == "/valbum/data/") {
        return json(bobsRoot);
      }
      if (type == "json" && path == "/valbum/data/Zoo/") {
        return json(zooAlbum());
      }
      if (type == "json" && path == "/valbum/data/Inbox/") {
        return json(emptyInbox);
      }
      if (type == "grants") {
        return json('{"grants": []}');
      }
      return http.Response("No such resource: $path", 404);
    })),
  );
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "bob",
        loaded: true,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Zoo"));
    await tester.pumpAndSettle();
  });
  return requests;
}

void main() {
  testWidgets(
      'through his own link tile, bob sees who added what and takes his '
      'photo back in his own coordinates', (tester) async {
    var requests = await pumpBob(tester, onMove: (request) {
      return json('{"outcomes":[{"name":"a.jpg","newName":"a.jpg",'
          '"message":""}]}');
    });
    expect(find.byKey(const ValueKey("a.jpg")), findsOneWidget);

    await withFakeImageHttp(() async {
      // Somebody else's photo: named, not takeable.
      await tester.tap(find.byKey(const ValueKey("b.jpg")));
      await tester.pumpAndSettle();
      expect(find.text("Added by alice"), findsOneWidget);
      expect(find.byKey(const Key("image-take-back")), findsNothing);

      // Back to the album and into his own.
      await tester.tap(find.byTooltip("Back to the album"));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey("a.jpg")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("image-contributor")), findsNothing);
      expect(find.byKey(const Key("image-take-back")), findsOneWidget);

      await tester.tap(find.byKey(const Key("image-take-back")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("picker-folder-Inbox")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("picker-confirm")));
      await tester.pumpAndSettle();
    });

    var moves = [
      for (var request in requests)
        if (request.url.queryParameters["action"] == "move") request,
    ];
    expect(moves, hasLength(1));
    expect(pathOf(moves.single), "/valbum/data/Zoo/",
        reason: "the source is spelled in the viewer's own path, the link");
    expect(moves.single.headers["Authorization"], "Bearer dev-9");
    expect(moves.single.body, contains('"target":"Inbox"'));
    expect(moves.single.body, contains('"name":"a.jpg"'));
    expect(find.byType(ImageView), findsNothing,
        reason: "the photo left the album; the viewer left with it");
  });

  testWidgets('a refusal leaves the viewer on the photo', (tester) async {
    await pumpBob(tester, onMove: (request) {
      return http.Response(
        '["ErrorInfo", {"message": "You may only move photos out of this '
        'album that you contributed yourself."}]',
        403,
        headers: {"content-type": "application/json; charset=utf-8"},
      );
    });

    await withFakeImageHttp(() async {
      await tester.tap(find.byKey(const ValueKey("a.jpg")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("image-take-back")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("picker-folder-Inbox")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("picker-confirm")));
      await tester.pumpAndSettle();
    });

    expect(find.textContaining("contributed yourself"), findsOneWidget);
    expect(find.byType(ImageView), findsOneWidget);
    expect(find.byKey(const Key("folder-picker")), findsNothing);
  });

  testWidgets('the alternatives of a group name each member\'s own contributor',
      (tester) async {
    var group = ImageGroup(
      representative: 0,
      images: [
        ImagePart(
          name: "a.jpg",
          width: 2000,
          height: 1000,
          contributor: "user:bob",
          contributorLabel: "bob",
        ),
        ImagePart(
          name: "b.jpg",
          width: 2000,
          height: 1000,
          contributor: "user:alice",
          contributorLabel: "alice",
        ),
      ],
    );
    var album = AlbumInfo(parts: [group]);
    group.owner = album;
    for (var member in group.images) {
      member.owner = album;
    }

    for (var (member, label) in [(group.images[0], "bob"), (group.images[1], "alice")]) {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          CallerScope(
            caller: CallerInfo(userName: "carol", role: roleMember, space: "carol"),
            child: MaterialApp(
              home: GroupDetailView(
                client: clientReturning("{}"),
                baseUrl: "$dataUrl/album",
                image: member,
                onShowImage: (_) {},
                onUp: () {},
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
      });
      expect(find.text("Added by $label"), findsOneWidget,
          reason: "each alternative names its own contributor");
      expect(find.byKey(const Key("image-take-back")), findsNothing,
          reason: "carol contributed neither");
    }
  });
}
