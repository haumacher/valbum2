/// Tests of the deep links (issue #24): the app is driven by the
/// [VAlbumRoute] in its location, and every navigation changes that route.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The album the fixture is served as, a folder name with spaces.
const String albumFolder = "2005-08-24 Blumen und Fliegen";
const List<String> albumPath = [albumFolder];

/// The router of the pumped app, holding the route currently shown.
VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

/// The route the app reports for the view it shows.
VAlbumRoute routeOf(WidgetTester tester) => routerOf(tester).route;

/// The tile of the album showing the image with the given file name.
Finder tile(String name) => find.byWidgetPredicate(
      (widget) =>
          widget is Image &&
          widget.image is ThumbnailImage &&
          (widget.image as ThumbnailImage).url.contains(name),
    );

/// The URL of the single image the viewer shows.
String shownUrl(WidgetTester tester) {
  var image = tester.widget<Image>(find.byType(Image).first);
  return (image.image as NetworkImage).url;
}

Future<void> pumpApp(
  WidgetTester tester,
  VAlbumClient client, {
  VAlbumRoute? at,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: client, initialRoute: at));
    await tester.pumpAndSettle();
  });
}

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) async {
  await withFakeImageHttp(() async {
    await tester.sendKeyEvent(key);
    await tester.pumpAndSettle();
  });
}

Future<void> tap(WidgetTester tester, Finder finder) async {
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

/// Hands the app the given location, as the browser does on back/forward.
Future<void> goTo(WidgetTester tester, String location) async {
  await withFakeImageHttp(() async {
    await routerOf(tester).setNewRoutePath(parseRoute(Uri.parse(location)));
    routerOf(tester).notifyListeners();
    await tester.pumpAndSettle();
  });
}

/// An album of [count] landscape images, as the server sends it.
String landscapeAlbum(int count) {
  var images = [
    for (var i = 0; i < count; i++)
      '["ImagePart", {"kind": "IMAGE", "name": "img-$i.jpg", '
          '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]',
  ];
  return '["AlbumInfo", {"path": "", "title": "Wide", '
      '"parts": [${images.join(",")}]}]';
}

/// The number of rows the album layout produced.
int layoutRows(WidgetTester tester) => find
    .descendant(
      of: find.byType(SingleChildScrollView),
      matching: find.byType(Row),
    )
    .evaluate()
    .length;

void main() {
  group('a route opens the view it names', () {
    testWidgets('an image route shows the viewer of that image',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("album.json"), requests: requests);

      await pumpApp(
        tester,
        client,
        at: const ImageRoute(albumPath, "portrait.jpg"),
      );

      expect(find.byType(ImageView), findsOneWidget);
      expect(shownUrl(tester), endsWith("/portrait.jpg"));

      // The enclosing album was loaded to resolve the image name.
      expect(
        requests.single.url.toString(),
        "http://server/valbum/data/2005-08-24%20Blumen%20und%20Fliegen/?type=json",
      );
    });

    testWidgets('an image of a group is shown as its group', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ImageRoute(albumPath, "group-a.jpg"),
      );

      expect(shownUrl(tester), endsWith("/group-a.jpg"));
      // The way down into the group is offered, so the group is shown.
      expect(find.byIcon(Icons.expand_more), findsOneWidget);
    });

    testWidgets('an alternatives route shows the group view', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const AlternativesRoute(albumPath, "group-a.jpg"),
      );

      expect(find.byType(GroupView), findsOneWidget);
      expect(
          find.byKey(const ValueKey("group-tile-group-a.jpg")), findsOneWidget);
      expect(
          find.byKey(const ValueKey("group-tile-group-b.jpg")), findsOneWidget);
    });

    testWidgets('a member route shows the viewer inside the group',
        (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const MemberRoute(albumPath, "group-a.jpg", "group-b.jpg"),
      );

      expect(shownUrl(tester), endsWith("/group-b.jpg"));
      // No way further down from the detail view.
      expect(find.byIcon(Icons.expand_more), findsNothing);
    });

    testWidgets('an unknown image name is refused', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ImageRoute(albumPath, "nowhere.jpg"),
      );

      expect(find.text("No such image: nowhere.jpg"), findsOneWidget);
    });

    testWidgets('an unknown group member is refused', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const MemberRoute(albumPath, "group-a.jpg", "nowhere.jpg"),
      );

      expect(find.text("No such image: nowhere.jpg"), findsOneWidget);
    });
  });

  group('navigating changes the route', () {
    testWidgets('opening a folder descends into it', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("listing.json"), requests: requests);

      await pumpApp(tester, client);
      expect(routeOf(tester), ListingOrAlbumRoute.root);

      await tap(tester, find.text('Schlosspark Karlsruhe'));

      expect(
        routeOf(tester),
        const ListingOrAlbumRoute(["2002-03-03 Schlosspark Karlsruhe"]),
      );
      expect(
        routerOf(tester).currentConfiguration.path,
        "/2002-03-03%20Schlosspark%20Karlsruhe/",
      );
      expect(
        requests.last.url.toString(),
        "http://server/valbum/data/2002-03-03%20Schlosspark%20Karlsruhe/?type=json",
      );
    });

    testWidgets('opening an image of the album names it in the route',
        (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ListingOrAlbumRoute(albumPath),
      );

      await tap(tester, tile("landscape.jpg"));

      expect(routeOf(tester), const ImageRoute(albumPath, "landscape.jpg"));
      expect(
        routerOf(tester).currentConfiguration.path,
        "/2005-08-24%20Blumen%20und%20Fliegen/landscape.jpg",
      );
    });

    testWidgets('the next image is another route', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ImageRoute(albumPath, "landscape.jpg"),
      );

      await press(tester, LogicalKeyboardKey.arrowRight);

      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));
      expect(shownUrl(tester), endsWith("/portrait.jpg"));
    });

    testWidgets('up from the image returns to the album', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("album.json"), requests: requests);

      await pumpApp(
        tester,
        client,
        at: const ImageRoute(albumPath, "landscape.jpg"),
      );

      await press(tester, LogicalKeyboardKey.arrowUp);

      expect(routeOf(tester), const ListingOrAlbumRoute(albumPath));
      expect(find.byType(ImageView), findsNothing);
      expect(find.byType(AlbumContent), findsOneWidget);
      // The album was not fetched again for the way back.
      expect(requests, hasLength(1));
    });

    testWidgets('the group chevron opens the alternatives route',
        (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ImageRoute(albumPath, "group-a.jpg"),
      );

      await tap(tester, find.byIcon(Icons.expand_more));
      expect(
          routeOf(tester), const AlternativesRoute(albumPath, "group-a.jpg"));

      // A member of the group opens in detail mode ...
      await tap(tester, find.byKey(const ValueKey("group-tile-group-b.jpg")));
      expect(
        routeOf(tester),
        const MemberRoute(albumPath, "group-a.jpg", "group-b.jpg"),
      );

      // ... and up leads back the same way.
      await press(tester, LogicalKeyboardKey.arrowUp);
      expect(
          routeOf(tester), const AlternativesRoute(albumPath, "group-a.jpg"));
      await tap(tester, find.byIcon(Icons.arrow_back));
      expect(routeOf(tester), const ImageRoute(albumPath, "group-a.jpg"));
    });

    testWidgets('the system back button goes up', (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const MemberRoute(albumPath, "group-a.jpg", "group-b.jpg"),
      );

      var router = routerOf(tester);
      expect(await router.popRoute(), isTrue);
      expect(router.route, const AlternativesRoute(albumPath, "group-a.jpg"));

      // At the root there is nothing left to pop, the app may close.
      await router.setNewRoutePath(ListingOrAlbumRoute.root);
      expect(await router.popRoute(), isFalse);
    });
  });

  group('the location drives the app', () {
    testWidgets('a new location (browser back/forward) re-renders the view',
        (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ImageRoute(albumPath, "landscape.jpg"),
      );
      expect(find.byType(ImageView), findsOneWidget);

      await goTo(tester, "/$albumFolder/");
      expect(find.byType(AlbumContent), findsOneWidget);

      await goTo(tester, "/$albumFolder/group-a.jpg/alternatives/");
      expect(find.byType(GroupView), findsOneWidget);
    });
  });

  group('the page state of an album', () {
    testWidgets('a narrower page lays the album out in more rows',
        (tester) async {
      var client = clientReturning(landscapeAlbum(8));

      tester.view.physicalSize = const Size(1600, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await pumpApp(tester, client, at: const ListingOrAlbumRoute(albumPath));
      var wide = layoutRows(tester);

      tester.view.physicalSize = const Size(400, 900);
      await withFakeImageHttp(() async => tester.pumpAndSettle());
      var narrow = layoutRows(tester);

      expect(wide, greaterThan(0));
      expect(narrow, greaterThan(wide));
    });

    testWidgets('an album offers the way up, and no home', (tester) async {
      // A full album shows no app bar (the photos come first), so its way back
      // is the floating up button over them; without it the album was a dead
      // end, see issue #35. The home is one more step up, on the listing.
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ListingOrAlbumRoute(albumPath),
      );

      expect(find.byType(AppBar), findsNothing, reason: "the album is bare");
      expect(find.byTooltip("Up"), findsOneWidget);
      expect(find.byTooltip("Home"), findsNothing);

      await tap(tester, find.byTooltip("Up"));
      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });

    testWidgets('the up button of a nested album loads its parent',
        (tester) async {
      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ListingOrAlbumRoute([albumFolder, "sub"]),
      );

      await tap(tester, find.byTooltip("Up"));

      expect(routeOf(tester), const ListingOrAlbumRoute(albumPath));
    });

    testWidgets('the deep link survives the wait for the stored settings',
        (tester) async {
      // The app shows a splash while the stored server URL is read. That
      // screen must not touch the browser location, and the route the app was
      // opened with must still be the one the router starts at, see issue #35.
      var client = clientReturning(fixture("album.json"));
      var settings = ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => client.dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(
            client: client,
            settings: settings,
            initialRoute: const ListingOrAlbumRoute(albumPath),
          ),
        );
        expect(
          find.byType(CircularProgressIndicator),
          findsOneWidget,
          reason: "the splash while the settings are read",
        );
        await tester.pumpAndSettle();
      });

      expect(routeOf(tester), const ListingOrAlbumRoute(albumPath));
      expect(find.text("Schlosspark Karlsruhe"), findsOneWidget);
    });

    testWidgets('the scroll offset is restored on the way back',
        (tester) async {
      tester.view.physicalSize = const Size(400, 400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await pumpApp(
        tester,
        clientReturning(fixture("album.json")),
        at: const ListingOrAlbumRoute(albumPath),
      );

      var scrollable = find.byType(Scrollable);
      var position = tester.state<ScrollableState>(scrollable).position;
      expect(position.maxScrollExtent, greaterThan(0));
      position.jumpTo(50);
      await tester.pump();

      // Open an image and return to the album.
      await goTo(tester, "/$albumFolder/landscape.jpg");
      expect(find.byType(ImageView), findsOneWidget);
      await goTo(tester, "/$albumFolder/");

      expect(
        tester.state<ScrollableState>(find.byType(Scrollable)).position.pixels,
        50,
      );
    });
  });
}
