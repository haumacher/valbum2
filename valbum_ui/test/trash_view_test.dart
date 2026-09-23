/// Tests of the trash page of an album, the app half of issue #152.
///
/// "Show trash" in the album menu opens `<album>/trash`, which shows the
/// photographs rated −2 — group members one by one — each with a Restore tool
/// that writes at once, and, for an administrator, "Purge…", which deletes
/// them from disk after asking.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/trash_view.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

const List<String> editRights = ["view", "download", "contribute", "edit"];

/// The answer of `?type=auth` for a caller of the given role.
String authOf(String role) =>
    '{"mode": "all", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "", '
    '"clearance": "all", "mayShare": true}';

String part(String name, int rating) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", '
    '"rating": $rating}]';

String member(String name, int rating) =>
    '{"kind": "IMAGE", "name": "$name", "date": 1, "width": 2000, '
    '"height": 1000, "orientation": "IDENTITY", "rating": $rating}';

/// A trashed plain part, a group whose representative is kept and whose
/// second member is trashed, and a poor photograph that is no trash.
String zooWithTrash({List<String> rights = editRights}) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "rights": ['
    '${rights.map((right) => '{"name": "$right"}').join(",")}], "parts": ['
    '${part("a.jpg", -2)},'
    '["ImageGroup", {"representative": 0, "images": ['
    '${member("b.jpg", 0)}, ${member("c.jpg", -2)}]}],'
    '${part("d.jpg", -1)}'
    ']}]';

/// The same album with nothing in the trash.
String zooWithoutTrash() =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "rights": ['
    '${editRights.map((right) => '{"name": "$right"}').join(",")}], "parts": ['
    '${part("a.jpg", 0)}, ${part("d.jpg", -1)}]}]';

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// A server answering [album] (asked anew on every GET), the given role, and
/// the given answers to a PUT and to a purge.
VAlbumClient server({
  required List<http.Request> requests,
  String Function()? album,
  String role = "admin",
  http.Response Function()? put,
  http.Response Function()? purge,
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
          return json(authOf(role));
        }
        if (request.method == "PUT") {
          return put?.call() ?? json('["CreateResult", {"path": "Zoo"}]');
        }
        if (query["action"] == "purge") {
          return purge?.call() ?? json('{"outcomes": []}');
        }
        return json((album ?? zooWithTrash)());
      }),
    );

Future<void> pump(
  WidgetTester tester,
  VAlbumClient client, {
  VAlbumRoute route = const ListingOrAlbumRoute(["Zoo"]),
  OfflineState? offlineState,
}) async {
  await tester.pumpWidget(localizedApp(VAlbumApp(
    client: client,
    offlineState: offlineState,
    initialRoute: route,
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "dev-9",
      userName: "carol",
      loaded: true,
    ),
  )));
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

/// The file names of the tiles on the trash page, in order.
List<String> tileNames(WidgetTester tester) => [
      for (var element in find
          .byWidgetPredicate((widget) =>
              widget.key is ValueKey<String> &&
              (widget.key as ValueKey<String>).value.startsWith("trash-tile-"))
          .evaluate())
        (element.widget.key as ValueKey<String>)
            .value
            .substring("trash-tile-".length),
    ];

Finder restoreOf(String name) => find.descendant(
      of: find.byKey(ValueKey("trash-tile-$name")),
      matching: find.byKey(const Key("trash-restore")),
    );

List<http.Request> writesIn(List<http.Request> requests) =>
    [for (var request in requests) if (request.method != "GET") request];

void main() {
  setUp(() {
    PaintingBinding.instance.imageCache
      ..clear()
      ..clearLiveImages();
  });

  group('the trashed photographs', () {
    test('are the members rated -2, one by one, and nothing else', () {
      var album = Resource.fromString(zooWithTrash()) as AlbumInfo;
      expect([for (var image in trashedImages(album)) image.name],
          ["a.jpg", "c.jpg"]);
      expect(hasTrashedImages(album), isTrue);
      expect(
        hasTrashedImages(Resource.fromString(zooWithoutTrash()) as AlbumInfo),
        isFalse,
      );
    });

    test('the route reads and spells the reserved segment', () {
      var route = parseRoute(Uri.parse("/Zoo/trash/"));
      expect(route, const TrashRoute(["Zoo"]));
      expect(route.path, "/Zoo/trash/");
      expect(route.up, const ListingOrAlbumRoute(["Zoo"]));
    });
  });

  group('"Show trash" in the album menu', () {
    testWidgets('is offered to an editor where the album holds trash',
        (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: [], role: "edit"));
        await openMenu(tester);

        expect(find.byKey(const Key("show-trash")), findsOneWidget);
        expect(find.text("Show trash"), findsOneWidget);

        await tester.tap(find.byKey(const Key("show-trash")));
        await tester.pumpAndSettle();
        expect(find.byType(TrashContent), findsOneWidget);
      });
    });

    testWidgets('is not offered where nothing is in the trash',
        (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: [], album: zooWithoutTrash));
        await openMenu(tester);

        expect(find.byKey(const Key("show-trash")), findsNothing);
      });
    });

    testWidgets('is not offered to a caller without edit', (tester) async {
      await withFakeImageHttp(() async {
        await pump(
          tester,
          server(
            requests: [],
            role: "contribute",
            album: () => zooWithTrash(
                rights: const ["view", "download", "contribute"]),
          ),
        );
        await openMenu(tester);

        expect(find.byKey(const Key("show-trash")), findsNothing);
      });
    });
  });

  group('the trash page', () {
    testWidgets('shows exactly the trashed photographs, members one by one',
        (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: []),
            route: const TrashRoute(["Zoo"]));

        expect(tileNames(tester), ["a.jpg", "c.jpg"]);
        expect(find.text("Trash bin"), findsOneWidget);
      });
    });

    testWidgets('the way back leads to the album', (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: []));
        await openMenu(tester);
        await tester.tap(find.byKey(const Key("show-trash")));
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key("trash-up")));
        await tester.pumpAndSettle();
        expect(find.byType(TrashContent), findsNothing);
      });
    });

    testWidgets('a tap shows the photograph large, in a dialog',
        (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: []),
            route: const TrashRoute(["Zoo"]));

        await tester.tap(find.byKey(const ValueKey("trash-tile-c.jpg")));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key("trash-photo")), findsOneWidget);
        expect(find.byKey(const Key("trash-photo-name")), findsOneWidget);
        expect(find.byType(TrashContent), findsOneWidget);
      });
    });
  });

  group('Restore', () {
    testWidgets('writes the album at once with the photograph unrated',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: requests),
            route: const TrashRoute(["Zoo"]));

        await tester.tap(restoreOf("c.jpg"));
        await tester.pumpAndSettle();

        var writes = writesIn(requests);
        expect(writes, hasLength(1));
        expect(writes.single.method, "PUT");
        expect(writes.single.url.path, endsWith("/Zoo/"));
        var sent = Resource.fromString(writes.single.body) as AlbumInfo;
        var group = sent.parts[1] as ImageGroup;
        expect(group.images[1].name, "c.jpg");
        expect(group.images[1].rating, 0);
        expect((sent.parts[0] as ImagePart).rating, -2,
            reason: "only the restored photograph changes");
        expect(tileNames(tester), ["a.jpg"]);
      });
    });

    testWidgets('a refusal takes the change back and says the reason',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pump(
          tester,
          server(
            requests: requests,
            put: () => json(
                '["ErrorInfo", {"message": "You may not change this album."}]',
                403),
          ),
          route: const TrashRoute(["Zoo"]),
        );

        await tester.tap(restoreOf("a.jpg"));
        await tester.pumpAndSettle();

        expect(find.text("You may not change this album."), findsOneWidget);
        expect(tileNames(tester), ["a.jpg", "c.jpg"]);
      });
    });

    testWidgets('is refused while the app is offline, and posts nothing',
        (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: requests),
            route: const TrashRoute(["Zoo"]), offlineState: state);
        state.goneOffline(null);
        await tester.pumpAndSettle();

        await tester.tap(restoreOf("a.jpg"));
        await tester.pumpAndSettle();

        expect(find.text(testL10n.offlineRefusal), findsOneWidget);
        expect(writesIn(requests), isEmpty);
        expect(tileNames(tester), ["a.jpg", "c.jpg"]);
      });
    });

    testWidgets('restoring the last one says the trash is empty',
        (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: []),
            route: const TrashRoute(["Zoo"]));

        await tester.tap(restoreOf("a.jpg"));
        await tester.pumpAndSettle();
        await tester.tap(restoreOf("c.jpg"));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key("trash-empty")), findsOneWidget);
        await tester.tap(find.byKey(const Key("trash-back")));
        await tester.pumpAndSettle();
        expect(find.byType(TrashContent), findsNothing);
      });
    });
  });

  group('Purge', () {
    testWidgets('is offered to an administrator only', (tester) async {
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: [], role: "edit"),
            route: const TrashRoute(["Zoo"]));
        expect(find.byKey(const Key("trash-purge")), findsNothing);
      });
    });

    testWidgets('asks first, posts the purge and reads the count out',
        (tester) async {
      var requests = <http.Request>[];
      var purged = false;
      await withFakeImageHttp(() async {
        await pump(
          tester,
          server(
            requests: requests,
            album: () => purged ? zooWithoutTrash() : zooWithTrash(),
            purge: () {
              purged = true;
              return json('{"outcomes": ['
                  '{"name": "a.jpg", "newName": "", "message": "Deleted."},'
                  '{"name": "c.jpg", "newName": "", "message": "Deleted."}]}');
            },
          ),
          route: const TrashRoute(["Zoo"]),
        );

        await tester.tap(find.byKey(const Key("trash-purge")));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("trash-purge-dialog")), findsOneWidget);
        expect(
          find.text(
              "The photographs are deleted from disk. This cannot be undone."),
          findsOneWidget,
        );
        expect(find.text("Delete permanently"), findsOneWidget);

        await tester.tap(find.byKey(const Key("trash-purge-confirm")));
        await tester.pumpAndSettle();

        var writes = writesIn(requests);
        expect(writes, hasLength(1));
        expect(writes.single.method, "POST");
        expect(writes.single.url.path, endsWith("/Zoo/"));
        expect(writes.single.url.queryParameters["action"], "purge");
        expect(find.text("2 photographs were deleted from disk."),
            findsOneWidget);
        expect(find.byKey(const Key("trash-empty")), findsOneWidget);
      });
    });

    testWidgets('a cancelled question posts nothing', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: requests),
            route: const TrashRoute(["Zoo"]));

        await tester.tap(find.byKey(const Key("trash-purge")));
        await tester.pumpAndSettle();
        await tester.tap(find.text("Cancel"));
        await tester.pumpAndSettle();

        expect(writesIn(requests), isEmpty);
        expect(tileNames(tester), ["a.jpg", "c.jpg"]);
      });
    });

    testWidgets('a refusal speaks in the server\'s words', (tester) async {
      await withFakeImageHttp(() async {
        await pump(
          tester,
          server(
            requests: [],
            purge: () => json(
                '["ErrorInfo", {"message": "Only an administrator may purge '
                'the trash of an album."}]',
                403),
          ),
          route: const TrashRoute(["Zoo"]),
        );

        await tester.tap(find.byKey(const Key("trash-purge")));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("trash-purge-confirm")));
        await tester.pumpAndSettle();

        expect(
          find.text("Only an administrator may purge the trash of an album."),
          findsOneWidget,
        );
        expect(tileNames(tester), ["a.jpg", "c.jpg"]);
      });
    });

    testWidgets('is refused while the app is offline', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await withFakeImageHttp(() async {
        await pump(tester, server(requests: requests),
            route: const TrashRoute(["Zoo"]), offlineState: state);
        state.goneOffline(null);
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key("trash-purge")));
        await tester.pumpAndSettle();

        expect(find.text(testL10n.offlineRefusal), findsOneWidget);
        expect(find.byKey(const Key("trash-purge-dialog")), findsNothing);
        expect(writesIn(requests), isEmpty);
      });
    });
  });
}
