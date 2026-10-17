/// Issue #286: on a folder page, an album tile tells how many photographs it
/// holds in a small bubble at its bottom right, once the mouse has rested on
/// it for a moment. A collection's count is not in the listing: it is asked of
/// the collection then, once for the life of the listing view.
library;

import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'folder_picture_test.dart' show pumpAt;
import 'move_test.dart' show dataUrl, json, pathOf;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart' show servingThumbnails;

/// The folder `F`: the album `A` of 42 photographs, the folder of folders
/// `G`, the album `Z` that shows the caller nothing, and the collection
/// `Best of`, which the listing does not count.
const String folderF = '["ListingInfo", {"path": "F", "title": "F", '
    '"folders": ['
    '{"name": "A", "title": "Holiday", "kind": "ALBUM", "imageCount": 42, '
    '"indexPicture": {"image": "a.jpg", "scale": 1, "tx": 0, "ty": 0}}, '
    '{"name": "G", "title": "Years", "kind": "FOLDER", "imageCount": 7}, '
    '{"name": "Z", "title": "Empty", "kind": "ALBUM", "imageCount": 0, '
    '"indexPicture": {"image": "z.jpg", "scale": 1, "tx": 0, "ty": 0}}, '
    '{"name": "Best of", "title": "Best of", "kind": "COLLECTION", '
    '"indexPicture": {"image": "b.jpg", "scale": 1, "tx": 0, "ty": 0}}'
    ']}]';

/// Where the collection's count is asked.
const String collectionPath = "/valbum/data/F/Best of/";

/// The server, answering the count of the collection with [count].
http.Response Function(http.Request) serverCounting(http.Response count) =>
    (request) => switch (pathOf(request)) {
          "/valbum/data/F/" => json(folderF),
          collectionPath
              when request.url.queryParameters["type"] == "image-count" =>
            count,
          _ => json('["ListingInfo", {"path": "", "title": "Library", '
              '"folders": [{"name": "F", "title": "F", "kind": "FOLDER"}]}]'),
        };

final http.Response Function(http.Request) server =
    serverCounting(json('{"count": 7}'));

/// The count requests among [requests].
List<String> countRequests(List<http.Request> requests) => [
      for (var request in requests)
        if (request.url.queryParameters["type"] == "image-count")
          request.url.toString(),
    ];

/// The tile of the entry named [name].
Finder tileOf(String name) => find.byKey(ValueKey<String>("tile-$name"));

/// The square picture of the tile of [name].
Finder pictureOf(String name) =>
    find.descendant(of: tileOf(name), matching: find.byType(ClipRect)).first;

/// The picture of the tile of [name], or its folder icon.
Finder pictureArea(String name) => find
    .descendant(
      of: tileOf(name),
      matching: find
          .byWidgetPredicate((widget) => widget is ClipRect || widget is Icon),
    )
    .first;

final Finder bubble = find.byKey(const Key("image-count-bubble"));

Finder bubbleSaying(String count) =>
    find.descendant(of: bubble, matching: find.text(count));

Future<TestGesture> mouse(WidgetTester tester) async {
  var gesture = await tester.createGesture(kind: PointerDeviceKind.mouse);
  await gesture.addPointer(location: Offset.zero);
  addTearDown(gesture.removePointer);
  return gesture;
}

/// Rests the mouse on the picture of [name] for the dwell and lets an answer
/// arrive.
Future<void> dwellOn(
    WidgetTester tester, TestGesture gesture, String name) async {
  await gesture.moveTo(tester.getCenter(pictureArea(name)));
  await tester.pump();
  await tester.pump(tileCountDwell);
  await tester.pumpAndSettle();
}

/// Takes the mouse off every tile.
Future<void> leave(WidgetTester tester, TestGesture gesture) async {
  await gesture.moveTo(const Offset(1, 1));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a pointer passing over a tile shows and asks nothing',
      (tester) async {
    var requests = <http.Request>[];
    await pumpAt(tester, server, route: const ["F"], requests: requests);
    var gesture = await mouse(tester);

    for (var name in ["A", "Best of"]) {
      await gesture.moveTo(tester.getCenter(pictureOf(name)));
      await tester.pump();
      await tester.pump(tileCountDwell - const Duration(milliseconds: 100));
      expect(bubble, findsNothing, reason: name);
      await leave(tester, gesture);
      await tester.pump(tileCountDwell * 2);
      expect(bubble, findsNothing, reason: name);
    }
    expect(countRequests(requests), isEmpty);
  });

  testWidgets(
      'resting on an album shows the count of the listing, '
      'leaving it hides it', (tester) async {
    var semantics = tester.ensureSemantics();
    var requests = <http.Request>[];
    await pumpAt(tester, server, route: const ["F"], requests: requests);
    expect(bubble, findsNothing);

    var gesture = await mouse(tester);
    await dwellOn(tester, gesture, "A");

    expect(bubble, findsOneWidget);
    expect(bubbleSaying("42"), findsOneWidget);
    expect(find.descendant(of: tileOf("A"), matching: bubble), findsOneWidget);
    expect(countRequests(requests), isEmpty,
        reason: "The listing said it already.");
    // At the bottom right of the picture, clear of the star's top right.
    var picture = tester.getRect(pictureOf("A"));
    var at = tester.getRect(bubble);
    expect(at.bottom, lessThanOrEqualTo(picture.bottom));
    expect(at.right, lessThanOrEqualTo(picture.right));
    expect(at.center.dx, greaterThan(picture.center.dx));
    expect(at.center.dy, greaterThan(picture.center.dy));
    expect(find.bySemanticsLabel(RegExp(r"\b42 images\b")), findsOneWidget);
    expect(find.byTooltip("42 images"), findsOneWidget);

    await leave(tester, gesture);
    expect(bubble, findsNothing);
    semantics.dispose();
  });

  testWidgets('a folder of folders and an album showing nothing show none',
      (tester) async {
    var requests = <http.Request>[];
    await pumpAt(tester, server, route: const ["F"], requests: requests);
    var gesture = await mouse(tester);

    await dwellOn(tester, gesture, "G");
    expect(bubble, findsNothing);

    await dwellOn(tester, gesture, "Z");
    expect(bubble, findsNothing);
    expect(countRequests(requests), isEmpty);
  });

  testWidgets('resting on a collection asks its count once', (tester) async {
    var requests = <http.Request>[];
    await pumpAt(tester, server, route: const ["F"], requests: requests);
    var gesture = await mouse(tester);

    await dwellOn(tester, gesture, "Best of");
    expect(bubbleSaying("7"), findsOneWidget);
    expect(find.descendant(of: tileOf("Best of"), matching: bubble),
        findsOneWidget);
    expect(countRequests(requests),
        ["http://server/valbum/data/F/Best%20of/?type=image-count"]);

    await leave(tester, gesture);
    expect(bubble, findsNothing);
    await dwellOn(tester, gesture, "Best of");
    expect(bubbleSaying("7"), findsOneWidget);
    expect(countRequests(requests), hasLength(1),
        reason: "The answer is kept for the life of the view.");
  });

  testWidgets('a refused count shows nothing and is not asked again',
      (tester) async {
    var requests = <http.Request>[];
    await pumpAt(
      tester,
      serverCounting(http.Response(
        '["ErrorInfo", {"message": "Not for you."}]',
        403,
        headers: {"content-type": "application/json; charset=utf-8"},
      )),
      route: const ["F"],
      requests: requests,
    );
    var gesture = await mouse(tester);

    await dwellOn(tester, gesture, "Best of");
    expect(bubble, findsNothing);
    expect(countRequests(requests), hasLength(1));

    await leave(tester, gesture);
    await dwellOn(tester, gesture, "Best of");
    expect(bubble, findsNothing);
    expect(countRequests(requests), hasLength(1), reason: "Never retried.");
  });

  testWidgets(
      'an answer arriving after the pointer left is shown on the next visit '
      'without asking again', (tester) async {
    var answer = Completer<http.Response>();
    var requests = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (request.url.queryParameters["type"] == "image-count") {
          return answer.future;
        }
        return server(request);
      })),
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute(["F"]),
      ));
      await tester.pumpAndSettle();
    });
    var gesture = await mouse(tester);

    await gesture.moveTo(tester.getCenter(pictureOf("Best of")));
    await tester.pump();
    await tester.pump(tileCountDwell);
    expect(countRequests(requests), hasLength(1));
    await leave(tester, gesture);

    answer.complete(json('{"count": 5}'));
    await tester.pumpAndSettle();
    expect(bubble, findsNothing, reason: "Nobody is looking any more.");

    await dwellOn(tester, gesture, "Best of");
    expect(bubbleSaying("5"), findsOneWidget);
    expect(countRequests(requests), hasLength(1));
  });

  testWidgets('without a mouse nothing is shown', (tester) async {
    var requests = <http.Request>[];
    await pumpAt(tester, server, route: const ["F"], requests: requests);
    expect(bubble, findsNothing);

    // A finger resting on a tile is no hover.
    for (var name in ["A", "Best of"]) {
      var touch = await tester.startGesture(tester.getCenter(pictureOf(name)));
      await tester.pump(const Duration(milliseconds: 100));
      expect(bubble, findsNothing);
      await touch.cancel();
      // No dwell was started by the touch.
      await tester.pump(tileCountDwell * 2);
      await tester.pumpAndSettle();
      expect(bubble, findsNothing);
    }
    expect(countRequests(requests), isEmpty);
  });
}
