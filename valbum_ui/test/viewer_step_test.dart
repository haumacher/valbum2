/// Stepping to the next image changes the picture, not the page (issue #105).
///
/// Every image is a route of its own, and the router used to key the viewer's
/// page by the image: a step therefore exchanged the topmost `MaterialPage`
/// and the `Navigator` ran the platform's page transition between two
/// viewers — the fade (the zoom on Android, the slide on iOS) that made the
/// picture flash instead of changing in place.
///
/// Since #105 the viewer's page is keyed by the *level* and the album, so the
/// mounted [ImageView] is handed the new image as a widget argument: no route
/// is pushed, replaced or removed, the [ImageViewState] survives the step —
/// which is what keeps the system bars of #60 still and lets the prefetched
/// neighbour of #101 be painted at once — and only the location moves on.
/// Descending into an image and ascending out of it are other levels and keep
/// their transitions.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The album the fixture is served as, a folder name with spaces.
const String albumFolder = "2005-08-24 Blumen und Fliegen";
const List<String> albumPath = [albumFolder];

/// What a navigation did to the page stack, in order.
///
/// A page-based route carries its `Page` as its settings, and that page's key
/// names the level it shows; anything else is imperative (a dialog).
class RouteLog extends NavigatorObserver {
  final List<String> pushed = [];
  final List<String> popped = [];
  final List<String> replaced = [];
  final List<String> removed = [];

  /// Whether the stack was left alone.
  bool get quiet =>
      pushed.isEmpty && popped.isEmpty && replaced.isEmpty && removed.isEmpty;

  /// Forgets everything seen so far.
  void clear() {
    pushed.clear();
    popped.clear();
    replaced.clear();
    removed.clear();
  }

  static String _name(Route<dynamic>? route) {
    var settings = route?.settings;
    return settings is Page ? "${settings.key}" : "$settings";
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      pushed.add(_name(route));

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      popped.add(_name(route));

  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      removed.add(_name(route));

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) =>
      replaced.add("${_name(oldRoute)} -> ${_name(newRoute)}");

  @override
  String toString() => "pushed: $pushed, popped: $popped, "
      "replaced: $replaced, removed: $removed";
}

/// The router of the pumped app, holding the route currently shown.
VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

/// The route the app reports for the view it shows.
VAlbumRoute routeOf(WidgetTester tester) => routerOf(tester).route;

/// The state of the viewer on screen.
ImageViewState viewerOf(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

/// The image the viewer was last built with.
String shownName(WidgetTester tester) => viewerOf(tester).part.name;

/// The tile of the album showing the image with the given file name.
Finder tile(String name) => find.byWidgetPredicate(
      (widget) =>
          widget is Image &&
          thumbnailOf(widget.image)?.url.contains(name) == true,
    );

/// Pumps the app at the given route, watched by [log].
Future<void> pumpApp(
  WidgetTester tester,
  RouteLog log, {
  VAlbumRoute? at,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      VAlbumApp(
        client: clientReturning(fixture("album.json")),
        initialRoute: at,
        navigatorObservers: [log],
      ),
    );
    await tester.pumpAndSettle();
  });
}

/// Runs [act] on the pumped app and lets everything settle.
Future<void> act(WidgetTester tester, Future<void> Function() act) async {
  await withFakeImageHttp(() async {
    await act();
    await tester.pumpAndSettle();
  });
}

/// Drags the picture by [total], slowly enough that only the distance counts.
Future<void> swipe(WidgetTester tester, Offset total) => act(tester, () async {
      var gesture = await tester.startGesture(
        tester.getCenter(find.byType(ImageView)),
      );
      var time = Duration.zero;
      for (var step = 0; step < 6; step++) {
        time += const Duration(milliseconds: 300);
        await gesture.moveBy(total / 6, timeStamp: time);
        await tester.pump();
      }
      await gesture.up();
    });

void main() {
  group('a step within the viewer keeps its page', () {
    testWidgets('the arrow key changes the image, not the route stack',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      var viewer = viewerOf(tester);
      log.clear();

      await act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));

      // Nothing was pushed, replaced or removed: the very same page shows the
      // next image.
      expect(log.quiet, isTrue, reason: "$log");
      expect(
        identical(viewerOf(tester), viewer),
        isTrue,
        reason: "the viewer was rebuilt, not recreated",
      );
      // The mounted viewer was handed the new image ...
      expect(shownName(tester), "portrait.jpg");
      // ... and the location moved on with it.
      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));
      expect(
        routerOf(tester).currentConfiguration.path,
        "/2005-08-24%20Blumen%20und%20Fliegen/portrait.jpg",
      );
    });

    testWidgets('the chevron changes the image, not the route stack',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      var viewer = viewerOf(tester);
      log.clear();

      await act(tester, () => tester.tap(find.byIcon(Icons.chevron_right)));

      expect(log.quiet, isTrue, reason: "$log");
      expect(identical(viewerOf(tester), viewer), isTrue);
      expect(shownName(tester), "portrait.jpg");
      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));
    });

    testWidgets('showNext changes the image, not the route stack',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      var viewer = viewerOf(tester);
      log.clear();

      await act(tester, () async => viewer.showNext());

      expect(log.quiet, isTrue, reason: "$log");
      expect(identical(viewerOf(tester), viewer), isTrue);
      expect(shownName(tester), "portrait.jpg");
      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));
    });

    testWidgets('a swipe changes the image, not the route stack',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      var viewer = viewerOf(tester);
      log.clear();

      // The page is 800 wide, so this is well beyond the third that pages on.
      await swipe(tester, const Offset(-420, 0));

      expect(log.quiet, isTrue, reason: "$log");
      expect(identical(viewerOf(tester), viewer), isTrue);
      expect(shownName(tester), "portrait.jpg");
      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));
    });

    testWidgets('the new image starts over, un-zoomed', (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      var viewer = viewerOf(tester);
      var page = tester.getSize(find.byType(ImageView));
      var fitted = viewer.transform(page).scale;

      // A click zooms to the pixel-by-pixel display.
      await act(tester, () => tester.tapAt(const Offset(400, 300)));
      expect(viewer.transform(page).scale, 1.0);

      await act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));

      // The same viewer, and `didUpdateWidget` started it over: the new image
      // is fitted, as a freshly opened one is.
      expect(identical(viewerOf(tester), viewer), isTrue);
      expect(shownName(tester), "portrait.jpg");
      expect(viewer.transform(page).isInitial, isTrue);
      expect(viewer.transform(page).scale, isNot(1.0));
      expect(fitted, lessThan(1.0));
    });

    testWidgets('a step within a group keeps the member page too',
        (tester) async {
      var log = RouteLog();
      await pumpApp(
        tester,
        log,
        at: const MemberRoute(albumPath, "group-a.jpg", "group-a.jpg"),
      );

      var viewer = viewerOf(tester);
      log.clear();

      await act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));

      expect(log.quiet, isTrue, reason: "$log");
      expect(identical(viewerOf(tester), viewer), isTrue);
      expect(shownName(tester), "group-b.jpg");
      expect(
        routeOf(tester),
        const MemberRoute(albumPath, "group-a.jpg", "group-b.jpg"),
      );
    });
  });

  group('descending and ascending are still steps of their own', () {
    testWidgets('opening an image of the album pushes one page',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log, at: const ListingOrAlbumRoute(albumPath));
      log.clear();

      await act(tester, () => tester.tap(tile("landscape.jpg")));

      expect(log.pushed, hasLength(1));
      expect(log.popped, isEmpty);
      expect(find.byType(ImageView), findsOneWidget);
      expect(routeOf(tester), const ImageRoute(albumPath, "landscape.jpg"));
    });

    testWidgets('the way up from a stepped-on image pops back to the album',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      await act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
      log.clear();

      await act(tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowUp));

      // One page left the stack — the one the step had kept — and the album
      // was there beneath it all along (issue #93).
      expect(log.popped.length + log.removed.length, 1, reason: "$log");
      expect(log.pushed, isEmpty);
      expect(routeOf(tester), const ListingOrAlbumRoute(albumPath));
      expect(find.byType(ImageView), findsNothing);
      expect(find.byType(AlbumContent), findsOneWidget);
    });

    testWidgets('the system back button still leaves the stepped-on image',
        (tester) async {
      var log = RouteLog();
      await pumpApp(tester, log,
          at: const ImageRoute(albumPath, "landscape.jpg"));

      await act(
          tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
      expect(routeOf(tester), const ImageRoute(albumPath, "portrait.jpg"));

      var router = routerOf(tester);
      expect(await router.popRoute(), isTrue);
      await act(tester, () async {});

      expect(router.route, const ListingOrAlbumRoute(albumPath));
      expect(find.byType(AlbumContent), findsOneWidget);
    });
  });
}
