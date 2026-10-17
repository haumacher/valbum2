/// Issue #286: on a folder page, an album tile tells how many photographs it
/// holds in a small bubble at its bottom right, while the mouse rests on it.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'folder_picture_test.dart' show pumpAt;
import 'move_test.dart' show json, pathOf;

/// The folder `F`: the album `A` of 42 photographs, the folder of folders
/// `G`, and the album `Z` the listing gives no count for.
const String folderF = '["ListingInfo", {"path": "F", "title": "F", '
    '"folders": ['
    '{"name": "A", "title": "Holiday", "kind": "ALBUM", "imageCount": 42, '
    '"indexPicture": {"image": "a.jpg", "scale": 1, "tx": 0, "ty": 0}}, '
    '{"name": "G", "title": "Years", "kind": "FOLDER", "imageCount": 7}, '
    '{"name": "Z", "title": "Empty", "kind": "ALBUM", "imageCount": 0, '
    '"indexPicture": {"image": "z.jpg", "scale": 1, "tx": 0, "ty": 0}}'
    ']}]';

http.Response server(http.Request request) => switch (pathOf(request)) {
      "/valbum/data/F/" => json(folderF),
      _ => json('["ListingInfo", {"path": "", "title": "Library", '
          '"folders": [{"name": "F", "title": "F", "kind": "FOLDER"}]}]'),
    };

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

Future<TestGesture> mouse(WidgetTester tester) async {
  var gesture = await tester.createGesture(kind: PointerDeviceKind.mouse);
  await gesture.addPointer(location: Offset.zero);
  addTearDown(gesture.removePointer);
  return gesture;
}

void main() {
  testWidgets('hovering an album shows its count, leaving it hides it',
      (tester) async {
    var semantics = tester.ensureSemantics();
    await pumpAt(tester, server, route: const ["F"]);
    expect(bubble, findsNothing);

    var gesture = await mouse(tester);
    await gesture.moveTo(tester.getCenter(pictureOf("A")));
    await tester.pumpAndSettle();

    expect(bubble, findsOneWidget);
    expect(
        find.descendant(of: bubble, matching: find.text("42")), findsOneWidget);
    expect(find.descendant(of: tileOf("A"), matching: bubble), findsOneWidget);
    // At the bottom right of the picture, clear of the star's top right.
    var picture = tester.getRect(pictureOf("A"));
    var at = tester.getRect(bubble);
    expect(at.bottom, lessThanOrEqualTo(picture.bottom));
    expect(at.right, lessThanOrEqualTo(picture.right));
    expect(at.center.dx, greaterThan(picture.center.dx));
    expect(at.center.dy, greaterThan(picture.center.dy));
    expect(find.bySemanticsLabel(RegExp(r"\b42 images\b")), findsOneWidget);
    expect(find.byTooltip("42 images"), findsOneWidget);

    await gesture.moveTo(const Offset(1, 1));
    await tester.pumpAndSettle();
    expect(bubble, findsNothing);
    semantics.dispose();
  });

  testWidgets('a folder of folders and an album without a count show none',
      (tester) async {
    await pumpAt(tester, server, route: const ["F"]);
    var gesture = await mouse(tester);

    await gesture.moveTo(tester.getCenter(pictureArea("G")));
    await tester.pumpAndSettle();
    expect(bubble, findsNothing);

    await gesture.moveTo(tester.getCenter(pictureOf("Z")));
    await tester.pumpAndSettle();
    expect(bubble, findsNothing);
  });

  testWidgets('without a mouse nothing is shown', (tester) async {
    await pumpAt(tester, server, route: const ["F"]);
    expect(bubble, findsNothing);

    // A finger resting on the album is no hover.
    var touch = await tester.startGesture(tester.getCenter(pictureOf("A")));
    await tester.pump(const Duration(milliseconds: 100));
    expect(bubble, findsNothing);
    await touch.cancel();
    await tester.pumpAndSettle();
    expect(bubble, findsNothing);
  });
}
