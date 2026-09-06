/// Probe for the move of issue #47, composed with what existed before it: the
/// offline copy (issue #31) must be the album as the server has it after the
/// move, and the server's whole-request refusal (the source picked as its own
/// target) must reach the user in the server's words.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'move_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

void main() {
  testWidgets('the offline copy is the album after the move', (tester) async {
    var cache = MemoryOfflineCache();
    var requests = <http.Request>[];
    var moved = false;
    var client = VAlbumClient(
      dataUrl: dataUrl,
      cache: cache,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (request.method == "POST") {
          moved = true;
          return json(movedAll(["a.jpg"]));
        }
        if (moved && pathOf(request) == "/valbum/data/Inbox/") {
          return json(fixture("album-target.json"));
        }
        return treeAnswer(request);
      })),
    );

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        cache: cache,
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      var before = await cache.getResource(dataUrl, const ["Inbox"]);
      expect(before!.text, fixture("album-move.json"));

      await openPicker(tester);
      await enterFolder(tester, "2020 Trip");
      await confirmPicker(tester);
    });

    expect(find.text("Moved 1 image to '2020 Trip'."), findsOneWidget);
    var after = await cache.getResource(dataUrl, const ["Inbox"]);
    expect(after!.text, fixture("album-target.json"),
        reason: "The copy browsed offline must be the album after the move.");
  });

  testWidgets('picking the album itself as the target shows the server\'s word',
      (tester) async {
    const sameFolder =
        "The source and the target folder are the same; nothing would move.";
    var requests = <http.Request>[];
    var client = recordingClient(
      (request) => request.method == "POST"
          ? http.Response('["ErrorInfo",{"message":"$sameFolder"}]', 400)
          : treeAnswer(request),
      requests,
    );

    await withFakeImageHttp(() async {
      await pumpAlbumEditMode(tester, client);
      await openPicker(tester);
      await enterFolder(tester, "Inbox");
      expect(find.text("Move 1 image to 'Inbox'"), findsOneWidget);
      await confirmPicker(tester);
    });

    expect(find.text(sameFolder), findsOneWidget);
    var post = requests.singleWhere((r) => r.method == "POST");
    expect(post.body, '{"target":"Inbox","names":[{"name":"a.jpg"}]}');
    // Nothing moved, so nothing was reloaded and the edit mode is still on.
    expect(jsonGets(requests.sublist(requests.indexOf(post) + 1)), isEmpty);
    expect(find.byKey(const Key("move-to")), findsOneWidget);
  });
}
