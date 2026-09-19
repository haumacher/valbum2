/// Leaving an album that is being edited (issue #99).
///
/// Ascending out of an album ends its edit mode — through one guard in the
/// router, whichever way out was taken: the way up of the app bar, the home
/// button, a listing tile above, the system's (and the browser's) back
/// button, a deep link. An edit without unsaved changes is simply dropped, so
/// the album is in the view mode when it is next visited. An edit with
/// unsaved changes asks: Save, Discard, Stay.
///
/// Descending *into* the album — a photo, the alternatives of a group — is
/// not leaving it: the edit goes on and is there on the way back (issue #93).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The stored order of the `Inbox` album fixture.
const List<String> storedOrder = ["a.jpg", "b.jpg", "Group(g1.jpg,g2.jpg)"];

/// The order after `a.jpg` was dropped behind `b.jpg`.
const List<String> movedOrder = ["b.jpg", "a.jpg", "Group(g1.jpg,g2.jpg)"];

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The tree the tests walk: a root listing with the album `Inbox` in it.
http.Response treeAnswer(http.Request request) {
  switch (pathOf(request)) {
    case "/valbum/data/":
      return json(fixture("listing-move.json"));
    case "/valbum/data/Inbox/":
      return json(fixture("album-move.json"));
    case "/valbum/data/2021/":
      return json(fixture("listing-move-2021.json"));
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

/// A client serving that tree, answering a PUT with [saveStatus].
VAlbumClient treeClient(List<http.Request> requests, {int saveStatus = 200}) =>
    VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return request.method == "PUT"
            ? http.Response("", saveStatus)
            : treeAnswer(request);
      })),
    );

List<http.Request> putsIn(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "PUT") request
    ];

/// The router of the app.
VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

Finder get leaveDialog => find.byKey(const Key("leave-dialog"));

/// Shows `Inbox` in the edit mode, with the first tile selected.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: const ListingOrAlbumRoute(["Inbox"]),
  ));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// Enters the edit mode and makes an unsaved change to the album.
Future<void> pumpDirtyEdit(WidgetTester tester, VAlbumClient client) async {
  await pumpEditMode(tester, client);
  await dragBehind(tester, "a.jpg", "b.jpg");
  expect(partNames(albumOf(tester)), movedOrder);
  expect(albumStateOf(tester).dirty, isTrue);
}

/// Taps the way up, which sits at the left of the edit mode's app bar.
Future<void> tapWayUp(WidgetTester tester) async {
  await tester.tap(find.byTooltip("Up"));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the way up out of a dirty edit asks, and Save writes and leaves',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      await tapWayUp(tester);
      expect(leaveDialog, findsOneWidget);
      expect(find.text("Save the changes to this album?"), findsOneWidget);
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]),
          reason: "nothing moves while the question is open");

      await tester.tap(find.byKey(const Key("save-and-leave")));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const ListingOrAlbumRoute([]));
      expect(find.byType(AlbumContent, skipOffstage: false), findsNothing);
    });
    expect(putsIn(requests), hasLength(1));
  });

  testWidgets('Discard leaves without sending anything', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      await tapWayUp(tester);
      await tester.tap(find.byKey(const Key("discard-and-leave")));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const ListingOrAlbumRoute([]));

      // Coming back shows the album as the server has it, in the view mode.
      routerOf(tester).go(const ListingOrAlbumRoute(["Inbox"]));
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).editMode, isFalse);
      expect(albumStateOf(tester).dirty, isFalse);
      expect(partNames(albumOf(tester)), storedOrder);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('Stay keeps the album and the edit', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      await tapWayUp(tester);
      await tester.tap(find.byKey(const Key("stay")));
      await tester.pumpAndSettle();

      expect(leaveDialog, findsNothing);
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));
      expect(albumStateOf(tester).editMode, isTrue);
      expect(albumStateOf(tester).dirty, isTrue);
      expect(partNames(albumOf(tester)), movedOrder);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('the system back button asks the very same question',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(leaveDialog, findsOneWidget);

      await tester.tap(find.byKey(const Key("discard-and-leave")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute([]));
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('a jump to another folder and the home button ask as well',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      // Another folder: the guard is the router's, not the button's.
      routerOf(tester).go(const ListingOrAlbumRoute(["2021"]));
      await tester.pumpAndSettle();
      expect(leaveDialog, findsOneWidget);
      await tester.tap(find.byKey(const Key("stay")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));

      // And the way home.
      routerOf(tester).go(ListingOrAlbumRoute.root);
      await tester.pumpAndSettle();
      expect(leaveDialog, findsOneWidget);
      await tester.tap(find.byKey(const Key("discard-and-leave")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute([]));
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('a deep link out of a dirty album asks too', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      await routerOf(tester).setNewRoutePath(const ListingOrAlbumRoute([]));
      await tester.pumpAndSettle();
      expect(leaveDialog, findsOneWidget);

      await tester.tap(find.byKey(const Key("stay")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));
      expect(albumStateOf(tester).editMode, isTrue);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('leaving a clean edit ends it without a word', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, treeClient(requests));
      expect(albumStateOf(tester).editMode, isTrue);

      await tapWayUp(tester);
      expect(leaveDialog, findsNothing);
      expect(routeOf(tester), const ListingOrAlbumRoute([]));

      // The session is gone: the album opens in the view mode.
      routerOf(tester).go(const ListingOrAlbumRoute(["Inbox"]));
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).editMode, isFalse);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('descending into a photo is not leaving the album',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests));

      // The viewer of one of the album's own photos: one level down, not out.
      routerOf(tester).go(const ImageRoute(["Inbox"], "b.jpg"));
      await tester.pumpAndSettle();

      expect(leaveDialog, findsNothing);
      expect(find.byType(ImageView), findsOneWidget);
      expect(routeOf(tester), const ImageRoute(["Inbox"], "b.jpg"));

      // Back on the album, the edit is exactly where it was left.
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));
      expect(albumStateOf(tester).editMode, isTrue);
      expect(albumStateOf(tester).dirty, isTrue);
      expect(partNames(albumOf(tester)), movedOrder);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('a refused save keeps the album, the edit and the message',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpDirtyEdit(tester, treeClient(requests, saveStatus: 403));

      await tapWayUp(tester);
      await tester.tap(find.byKey(const Key("save-and-leave")));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]),
          reason: "nothing is left behind that the server refused");
      expect(albumStateOf(tester).editMode, isTrue);
      expect(albumStateOf(tester).dirty, isTrue);
      expect(partNames(albumOf(tester)), movedOrder);
      expect(find.textContaining("Speichern fehlgeschlagen"), findsOneWidget);
    });
    expect(putsIn(requests), hasLength(1));
  });
}
