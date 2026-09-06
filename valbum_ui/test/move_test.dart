/// Tests of "Move to…" (issue #47): the folder picker, the request it posts,
/// the reload after a move and the report of what the server refused.
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

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The requests that asked the server for a resource.
List<String> jsonGets(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          pathOf(request),
    ];

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The result of a move in which every name moved plainly.
String movedAll(List<String> names) =>
    '{"outcomes":[${names.map((name) => '{"name":"$name",'
        '"newName":"$name","message":""}').join(",")}]}';

/// The tree the tests browse: an album `Inbox` with three tiles, an album
/// `2020 Trip` and a folder `2021` holding `Summer`.
http.Response treeAnswer(http.Request request) {
  switch (pathOf(request)) {
    case "/valbum/data/":
      return json(fixture("listing-move.json"));
    case "/valbum/data/2021/":
      return json(fixture("listing-move-2021.json"));
    case "/valbum/data/2020 Trip/":
      return json(fixture("album-target.json"));
    case "/valbum/data/Inbox/":
      return json(fixture("album-move.json"));
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

/// A client answering [handler], recording every request it is given.
VAlbumClient recordingClient(
  http.Response Function(http.Request request) handler,
  List<http.Request> requests,
) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Long-presses a tile beside its toolbars, adding it to the selection.
Future<void> longPressTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.longPressAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Shows the album `Inbox` in the edit mode, with `a.jpg` selected.
Future<void> pumpAlbumEditMode(
  WidgetTester tester,
  VAlbumClient client, {
  OfflineState? offlineState,
}) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    offlineState: offlineState,
    initialRoute: const ListingOrAlbumRoute(["Inbox"]),
  ));
  await tester.pumpAndSettle();
  // The long press of the view mode enters the edit mode with that tile
  // selected.
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// Opens the folder picker from the album's app bar.
Future<void> openPicker(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("move-to")));
  await tester.pumpAndSettle();
}

/// Descends into the folder of the given name in the open picker.
Future<void> enterFolder(WidgetTester tester, String name) async {
  await tester.tap(find.byKey(Key("picker-folder-$name")));
  await tester.pumpAndSettle();
}

/// Confirms the picker with the folder it currently shows.
Future<void> confirmPicker(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("picker-confirm")));
  await tester.pumpAndSettle();
}

void main() {
  group('the move subject', () {
    test('counts images and names an entry', () {
      expect(const ImageSubject(1).asked, "1 image");
      expect(const ImageSubject(3).asked, "3 images");
      expect(const ImageSubject(3).moved(2), "2 images");
      expect(const EntrySubject("2020 Trip").asked, "'2020 Trip'");
      expect(const EntrySubject("2020 Trip").moved(1), "'2020 Trip'");
    });

    test('names the root of the space as the app calls it', () {
      expect(targetLabel(const []), "the top level");
      expect(targetLabel(const ["2021", "Summer"]), "'2021/Summer'");
      expect(targetPath(const []), "");
      expect(targetPath(const ["2021", "Summer"]), "2021/Summer");
    });
  });

  group('moving a selection out of an album', () {
    testWidgets('posts the picked target and reloads the album',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(movedAll(["a.jpg", "b.jpg"]))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        // A second tile joins the selection.
        await longPressTile(tester, "b.jpg");

        await openPicker(tester);

        // The picker opens at the parent of the album: the root's folders.
        expect(
            find.byKey(const Key("picker-folder-2020 Trip")), findsOneWidget);
        expect(find.byKey(const Key("picker-folder-2021")), findsOneWidget);
        expect(find.text("Move 2 images to the top level"), findsOneWidget);

        // An album is a leaf, and a target for images.
        await enterFolder(tester, "2020 Trip");
        expect(find.byKey(const Key("picker-leaf")), findsOneWidget);
        expect(find.text("Move 2 images to '2020 Trip'"), findsOneWidget);

        await confirmPicker(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(
        Uri.decodeFull(post.url.toString()),
        "$dataUrl/Inbox/?action=move",
      );
      expect(
        post.body,
        '{"target":"2020 Trip","names":[{"name":"a.jpg"},{"name":"b.jpg"}]}',
      );

      // The album was fetched again, and the outcome is on the screen.
      expect(
        jsonGets(requests.sublist(requests.indexOf(post))),
        contains("/valbum/data/Inbox/"),
      );
      expect(find.text("Moved 2 images to '2020 Trip'."), findsOneWidget);
    });

    testWidgets('names a group by its representative', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(movedAll(["g1.jpg"]))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        // Only the group is selected.
        await tapTile(tester, "g1.jpg");

        await openPicker(tester);
        expect(find.text("Move 1 image to the top level"), findsOneWidget);
        await enterFolder(tester, "2021");
        await confirmPicker(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(post.body, '{"target":"2021","names":[{"name":"g1.jpg"}]}');
      expect(find.text("Moved 1 image to '2021'."), findsOneWidget);
    });

    testWidgets('shows every refusal of the server, and still reloads',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json('{"outcomes":['
                '{"name":"a.jpg","newName":"a.jpg","message":""},'
                '{"name":"b.jpg","newName":"","message":'
                '"already exists in the target folder."}]}')
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        await longPressTile(tester, "b.jpg");
        await openPicker(tester);
        await enterFolder(tester, "2021");
        await confirmPicker(tester);

        // A dialog, not a snack bar: a refusal must not scroll past.
        expect(find.byKey(const Key("move-outcome")), findsOneWidget);
        expect(find.text("Moved 1 image to '2021'."), findsOneWidget);
        expect(
          find.text("'b.jpg': already exists in the target folder."),
          findsOneWidget,
        );

        await tester.tap(find.text("OK"));
        await tester.pumpAndSettle();
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(
        jsonGets(requests.sublist(requests.indexOf(post))),
        contains("/valbum/data/Inbox/"),
      );
    });

    testWidgets('shows the reason the server refuses the whole request',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? http.Response(
                '["ErrorInfo", {"message": "Sign in before changing anything."}]',
                401,
              )
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        await openPicker(tester);
        await enterFolder(tester, "2021");
        await confirmPicker(tester);

        expect(
          find.text("Sign in before changing anything."),
          findsOneWidget,
        );
        // Nothing moved, so the album is still the one on the screen.
        expect(tile("a.jpg"), findsOneWidget);
      });
    });

    testWidgets('refuses to leave unsaved changes behind', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        // One edit, not saved.
        await tester.tap(find.byTooltip("Nach rechts drehen").first);
        await tester.pumpAndSettle();

        await openPicker(tester);

        expect(find.text("Save or discard your changes first"), findsOneWidget);
        expect(find.byKey(const Key("folder-picker")), findsNothing);
      });

      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });

    testWidgets('descends and comes back up; the root posts an empty target',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(movedAll(["a.jpg"]))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        await openPicker(tester);

        await enterFolder(tester, "2021");
        expect(find.byKey(const Key("picker-folder-Summer")), findsOneWidget);
        expect(find.text("2021"), findsWidgets);

        await tester.tap(find.byKey(const Key("picker-up")));
        await tester.pumpAndSettle();

        expect(find.text("Top level"), findsOneWidget);
        expect(find.byKey(const Key("picker-folder-2021")), findsOneWidget);

        await confirmPicker(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(post.body, '{"target":"","names":[{"name":"a.jpg"}]}');
      expect(find.text("Moved 1 image to the top level."), findsOneWidget);
    });

    testWidgets('shows a loading failure inside the picker', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => pathOf(request) == "/valbum/data/2021/"
            ? http.Response(
                '["ErrorInfo", {"message": "This folder is not yours."}]',
                403,
              )
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client);
        await openPicker(tester);
        await enterFolder(tester, "2021");

        expect(find.text("This folder is not yours."), findsOneWidget);
        // The picker stays open, and the way back up is still there.
        expect(find.byKey(const Key("picker-up")), findsOneWidget);
      });
    });

    testWidgets('is refused while the app is offline', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, client, offlineState: state);

        state.goneOffline(null);
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key("move-to")));
        await tester.pumpAndSettle();

        expect(find.text(offlineRefusal), findsOneWidget);
        expect(find.byKey(const Key("folder-picker")), findsNothing);
      });

      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  group('moving an entry of a listing', () {
    testWidgets('posts the tile name to the listing and reloads it',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(movedAll(["2020 Trip"]))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        // A listing tile has one action: the long press reaches it.
        await tester.longPress(
          find
              .ancestor(
                of: find.text("2020 Trip"),
                matching: find.byType(GestureDetector),
              )
              .first,
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text("Move to…"));
        await tester.pumpAndSettle();

        await enterFolder(tester, "2021");
        expect(find.text("Move '2020 Trip' to '2021'"), findsOneWidget);
        await confirmPicker(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(Uri.decodeFull(post.url.toString()), "$dataUrl/?action=move");
      expect(post.body, '{"target":"2021","names":[{"name":"2020 Trip"}]}');

      // The listing was fetched again after the move.
      expect(
        jsonGets(requests.sublist(requests.indexOf(post))),
        contains("/valbum/data/"),
      );
      expect(find.text("Moved '2020 Trip' to '2021'."), findsOneWidget);
    });
  });
}
