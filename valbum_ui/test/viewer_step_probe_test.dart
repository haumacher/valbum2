/// Probe for issue #105: stepping in place composed with the rating filter,
/// a video neighbour, and deep links within and across albums.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String albumFolder = "2005-08-24 Blumen und Fliegen";
const List<String> albumPath = [albumFolder];
const List<String> otherPath = ["2006-01-01 Winter"];

/// Three images and a video; the second image is rated below the default
/// filter, so a step from the first must land on the third.
const String probeAlbum = '''
["AlbumInfo", {
  "path": "",
  "title": "Probe",
  "parts": [
    ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}],
    ["ImagePart", {"kind": "IMAGE", "name": "hidden.jpg", "date": 2, "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": -1}],
    ["ImagePart", {"kind": "IMAGE", "name": "c.jpg", "date": 3, "width": 1536, "height": 2048, "orientation": "IDENTITY", "rating": 0}],
    ["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", "date": 4, "width": 1920, "height": 1080, "orientation": "IDENTITY", "rating": 0}]
  ]
}]
''';

class RouteLog extends NavigatorObserver {
  final List<String> events = [];
  static String _name(Route<dynamic>? route) {
    var settings = route?.settings;
    return settings is Page ? "${settings.key}" : "$settings";
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      events.add("push ${_name(route)}");
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      events.add("pop ${_name(route)}");
  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      events.add("remove ${_name(route)}");
  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) =>
      events.add("replace ${_name(oldRoute)} -> ${_name(newRoute)}");
}

VAlbumRouterDelegate routerOf(WidgetTester tester) =>
    tester.widget<MaterialApp>(find.byType(MaterialApp)).routerDelegate!
        as VAlbumRouterDelegate;

ImageViewState viewerOf(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

Future<RouteLog> pumpAt(WidgetTester tester, VAlbumRoute at) async {
  var log = RouteLog();
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: clientReturning(probeAlbum),
      initialRoute: at,
      navigatorObservers: [log],
    ));
    await tester.pumpAndSettle();
  });
  log.events.clear();
  return log;
}

/// A deep link as the platform delivers it: the browser's history moved.
Future<void> deepLink(WidgetTester tester, String location) async {
  var message = const JSONMethodCodec().encodeMethodCall(
    MethodCall('pushRouteInformation', {'location': location, 'state': null}),
  );
  await tester.binding.defaultBinaryMessenger
      .handlePlatformMessage('flutter/navigation', message, (_) {});
}

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

void main() {
  testWidgets('a step over a rating-hidden image is one quiet step',
      (tester) async {
    var log = await pumpAt(tester, const ImageRoute(albumPath, "a.jpg"));
    var viewer = viewerOf(tester);
    await settle(tester, () async => viewer.showNext());
    expect(log.events, isEmpty, reason: "${log.events}");
    expect(identical(viewerOf(tester), viewer), isTrue);
    expect(viewerOf(tester).part.name, "c.jpg");
    expect(routerOf(tester).route, const ImageRoute(albumPath, "c.jpg"));
  });

  testWidgets('stepping onto a video and back keeps the page', (tester) async {
    var log = await pumpAt(tester, const ImageRoute(albumPath, "c.jpg"));
    var viewer = viewerOf(tester);
    await settle(tester, () async => viewer.showNext());
    expect(log.events, isEmpty, reason: "${log.events}");
    expect(viewerOf(tester).part.name, "clip.mp4");
    expect(identical(viewerOf(tester), viewer), isTrue);
    await settle(tester, () async => viewerOf(tester).showPrevious());
    expect(log.events, isEmpty, reason: "${log.events}");
    expect(viewerOf(tester).part.name, "c.jpg");
  });

  testWidgets('a deep link within the album steps in place', (tester) async {
    var log = await pumpAt(tester, const ImageRoute(albumPath, "a.jpg"));
    var viewer = viewerOf(tester);
    await settle(tester,
        () => deepLink(tester, "/2005-08-24%20Blumen%20und%20Fliegen/c.jpg"));
    expect(log.events, isEmpty, reason: "${log.events}");
    expect(identical(viewerOf(tester), viewer), isTrue);
    expect(viewerOf(tester).part.name, "c.jpg");
  });

  testWidgets('a deep link into another album exchanges the pages',
      (tester) async {
    var log = await pumpAt(tester, const ImageRoute(albumPath, "a.jpg"));
    var viewer = viewerOf(tester);
    await settle(tester, () => deepLink(tester, "/2006-01-01%20Winter/c.jpg"));
    expect(log.events, isNotEmpty, reason: "another album is another page");
    expect(identical(viewerOf(tester), viewer), isFalse);
    expect(viewerOf(tester).part.name, "c.jpg");
    expect(routerOf(tester).route, const ImageRoute(otherPath, "c.jpg"));
    // And from there, stepping is quiet again.
    log.events.clear();
    var fresh = viewerOf(tester);
    await settle(tester, () async => fresh.showPrevious());
    expect(log.events, isEmpty, reason: "${log.events}");
    expect(viewerOf(tester).part.name, "a.jpg");
  });
}
