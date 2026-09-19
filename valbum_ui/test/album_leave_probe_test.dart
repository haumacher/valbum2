/// Probe for issue #99: the leave guard composed with the page stack of #93
/// (descending is not leaving), with a second folder as the target, with the
/// system back while the question is open, and with a second question after
/// "Stay".
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

http.Response json(String body) => http.Response(body, 200,
    headers: {"content-type": "application/json; charset=utf-8"});

VAlbumClient treeClient(List<http.Request> requests) => VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        switch (pathOf(request)) {
          case "/valbum/data/":
            return json(fixture("listing-move.json"));
          case "/valbum/data/Inbox/":
            return json(fixture("album-move.json"));
          case "/valbum/data/2021/":
            return json(fixture("listing-move-2021.json"));
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      })),
    );

int putsIn(List<http.Request> requests) =>
    requests.where((r) => r.method == "PUT").length;

VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

Finder get leaveDialog => find.byKey(const Key("leave-dialog"));

Future<void> go(WidgetTester tester, VAlbumRoute target) async {
  routerOf(tester).go(target);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'a dirty edit survives Stay, a descent and a closed question, and Discard towards another folder ends it',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: treeClient(requests),
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await dragBehind(tester, "a.jpg", "b.jpg");
      var state = albumStateOf(tester);
      expect(state.dirty, isTrue);

      // Towards another folder: the question, and Stay keeps everything.
      await go(tester, const ListingOrAlbumRoute(["2021"]));
      expect(leaveDialog, findsOneWidget);
      await tester.tap(find.byKey(const Key("stay")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));
      expect(identical(albumStateOf(tester), state), isTrue);
      expect(state.editMode, isTrue);
      expect(state.dirty, isTrue);

      // Descending into a photo is not leaving: the album stays mounted (#93)
      // and the edit goes on when we come back.
      await go(tester, const ImageRoute(["Inbox"], "a.jpg"));
      expect(leaveDialog, findsNothing);
      expect(find.byType(ImageView), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byType(ImageView), findsNothing);
      expect(identical(albumStateOf(tester), state), isTrue);
      expect(state.editMode, isTrue);
      expect(state.dirty, isTrue);

      // The system back while the question is open closes the question, not
      // the album.
      await tester.tap(find.byTooltip("Up"));
      await tester.pumpAndSettle();
      expect(leaveDialog, findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(leaveDialog, findsNothing);
      expect(routeOf(tester), const ListingOrAlbumRoute(["Inbox"]));
      expect(state.editMode, isTrue, reason: "a closed question is Stay");
      expect(state.dirty, isTrue);

      // Asked again, Discard towards the other folder ends the edit for good.
      await go(tester, const ListingOrAlbumRoute(["2021"]));
      expect(leaveDialog, findsOneWidget);
      await tester.tap(find.byKey(const Key("discard-and-leave")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["2021"]));
      expect(find.byType(ListingView), findsOneWidget);

      await go(tester, const ListingOrAlbumRoute(["Inbox"]));
      expect(albumStateOf(tester).editMode, isFalse);
      expect(albumStateOf(tester).dirty, isFalse);
      expect(partNames(albumOf(tester)), ["a.jpg", "b.jpg", "Group(g1.jpg,g2.jpg)"]);
    });
    expect(putsIn(requests), 0, reason: "nothing was ever written");
  });
}
