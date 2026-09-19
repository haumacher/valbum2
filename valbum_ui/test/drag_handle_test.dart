/// Reordering by the drag handle of the edit mode (issue #94).
///
/// A finger's first movement is rarely straight: the vertical component
/// reaches the touch slop first and the scroll view wins the gesture arena, so
/// the sideways pull of issue #37 all but never lifted a tile on a phone — and
/// nothing on the screen said the gesture existed at all. The handle lifts the
/// tile immediately and in any direction; the tile itself keeps the sideways
/// pull the mouse makes naturally.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The stored order of the album fixture, before anything was moved.
const List<String> storedOrder = [
  "Heading(Am Morgen)",
  "landscape.jpg",
  "portrait.jpg",
  "Group(group-a.jpg,group-b.jpg)",
];

/// The album fixture, with every PUT accepted.
VAlbumClient editableAlbum() => clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("", 200)
          : http.Response(fixture("album.json"), 200),
    );

/// A point on the tile itself, beside its toolbars.
Offset gripOf(WidgetTester tester, String name) {
  var box = tester.getRect(tile(name));
  return Offset(box.left + 8, box.center.dy);
}

/// Long-presses the tile of [name]: it enters the edit mode, and inside it
/// adds the tile to the selection (issue #41).
Future<void> pressTile(WidgetTester tester, String name) async {
  await tester.longPressAt(gripOf(tester, name));
  await tester.pumpAndSettle();
}

/// Loads the album and enters the edit mode by a long press on the landscape.
Future<void> pumpEditMode(WidgetTester tester) async {
  await tester.pumpWidget(VAlbumApp(client: editableAlbum()));
  await tester.pumpAndSettle();
  await pressTile(tester, "landscape.jpg");
}

/// An album of twelve landscape images, taller than a small window.
final String tallAlbum = AlbumInfo(
  path: "",
  title: "Lang",
  parts: [
    for (var index = 0; index < 12; index++)
      ImagePart(
        name: "${String.fromCharCode(0x61 + index)}.jpg",
        width: 2048,
        height: 1536,
      ),
  ],
).toString();

/// The drag handle of the tile showing [name].
Finder handle(String name) => find.descendant(
      of: tile(name),
      matching: find.byKey(const Key("drag-handle")),
    );

/// Carries the tile [from] onto the right half of the tile [target] by its
/// handle, with a *diagonal, mostly vertical* gesture — what a finger makes.
Future<void> carryByHandle(
  WidgetTester tester,
  String from,
  String target,
) async {
  var grip = tester.getCenter(handle(from));
  var box = tester.getRect(tile(target));

  var gesture = await tester.startGesture(grip);
  // More down than sideways: the scroll view would win this one on the tile.
  await gesture.moveBy(const Offset(12, 30));
  await tester.pump();
  await gesture.moveTo(Offset(box.left + box.width * 0.75, box.center.dy));
  await tester.pump();
  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('every tile of the edit mode shows a handle', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester);

      expect(handle("landscape.jpg"), findsOneWidget);
      expect(handle("portrait.jpg"), findsOneWidget);

      // Large enough for a finger, and it says what it is for.
      expect(tester.getSize(handle("landscape.jpg")).shortestSide,
          greaterThanOrEqualTo(40));
      expect(
        tester
            .widget<Tooltip>(find.ancestor(
              of: handle("landscape.jpg"),
              matching: find.byType(Tooltip),
            ))
            .message,
        "Drag to reorder",
      );
    });
  });

  testWidgets('a diagonal touch drag on the handle reorders the album',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester);
      expect(partNames(albumOf(tester)), storedOrder);

      await carryByHandle(tester, "landscape.jpg", "portrait.jpg");

      expect(partNames(albumOf(tester)), [
        "Heading(Am Morgen)",
        "portrait.jpg",
        "landscape.jpg",
        "Group(group-a.jpg,group-b.jpg)",
      ]);
      expect(albumStateOf(tester).dirty, isTrue);
    });
  });

  testWidgets('the same gesture beside the handle moves nothing',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester);

      // The same diagonal pull, but on the tile itself: the scroll view keeps
      // it, and the album's order is untouched.
      var start = tester.getRect(tile("landscape.jpg"));
      var box = tester.getRect(tile("portrait.jpg"));
      var gesture = await tester.startGesture(
        Offset(start.left + 8, start.bottom - 8),
      );
      await gesture.moveBy(const Offset(12, 30));
      await tester.pump();
      await gesture.moveTo(Offset(box.left + box.width * 0.75, box.center.dy));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();

      expect(partNames(albumOf(tester)), storedOrder);
      expect(albumStateOf(tester).dirty, isFalse);
    });
  });

  testWidgets('the sideways pull on the tile still lifts it', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester);

      await dragBehind(tester, "landscape.jpg", "portrait.jpg");

      expect(partNames(albumOf(tester)), [
        "Heading(Am Morgen)",
        "portrait.jpg",
        "landscape.jpg",
        "Group(group-a.jpg,group-b.jpg)",
      ]);
    });
  });

  testWidgets('the album scrolls under a tile carried by its handle',
      (tester) async {
    // A tall album in a small window, so there is something to scroll.
    tester.view.physicalSize = const Size(400, 500);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: clientReturning(tallAlbum)));
      await tester.pumpAndSettle();
      await pressTile(tester, "a.jpg");

      var view = tester.getRect(find.descendant(
        of: find.byType(AlbumContent),
        matching: find.byType(Scrollable),
      ));
      var position = tester.state<ScrollableState>(find.descendant(
        of: find.byType(AlbumContent),
        matching: find.byType(Scrollable),
      ));
      expect(position.position.maxScrollExtent, greaterThan(0));

      var gesture = await tester.startGesture(
        tester.getCenter(handle("a.jpg")),
      );
      await gesture.moveBy(const Offset(12, 30));
      await tester.pump();
      // Resting in the band along the bottom edge scrolls the album, see
      // issue #42 — the drag of a handle is watched like any other.
      await gesture.moveTo(Offset(view.center.dx, view.bottom - 4));
      for (var frame = 0; frame < 10; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }

      expect(position.position.pixels, greaterThan(0));

      await gesture.up();
      await tester.pumpAndSettle();
    });
  });

  testWidgets('a selection is carried by the handle of any of its tiles',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester);
      // The long press entering the edit mode selected the landscape; the
      // long press on the portrait adds it, which is the gesture of the
      // multi-selection (issue #41).
      await pressTile(tester, "portrait.jpg");
      expect(
        {for (var part in albumStateOf(tester).selection) partName(part)},
        {"landscape.jpg", "portrait.jpg"},
      );

      await carryByHandle(
        tester,
        "portrait.jpg",
        "group-a.jpg",
      );

      // Both of them moved behind the group, in their stored order.
      expect(partNames(albumOf(tester)), [
        "Heading(Am Morgen)",
        "Group(group-a.jpg,group-b.jpg)",
        "landscape.jpg",
        "portrait.jpg",
      ]);
    });
  });
}
