import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The insert cursor of a drag in progress.
Finder get insertCursor => find.byKey(const Key("insert-cursor"));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

/// The scroll position of the album, the one the edge scrolling drives.
ScrollPosition scrollPosition(WidgetTester tester) =>
    albumState(tester).albumScrollPosition!;

/// The viewport of the album's scroll view, in global coordinates.
Rect viewport(WidgetTester tester) => tester.getRect(
      find.descendant(
        of: find.byType(AlbumContent),
        matching: find.byType(Scrollable),
      ),
    );

/// A column of the viewport that lies on the tiles, not in the gap between
/// them: the middle falls exactly between the two columns of the test album.
double bandX(Rect view) => view.left + view.width / 4;

/// The edge band of the given viewport, see [dragScrollZone].
double zoneOf(Rect viewport) =>
    dragScrollZone < viewport.height * dragScrollZoneFraction
        ? dragScrollZone
        : viewport.height * dragScrollZoneFraction;

ImagePart landscape(String name) =>
    ImagePart(name: name, width: 2048, height: 1536);

/// An album of [count] images, as JSON.
String albumJson(int count) => AlbumInfo(
      path: "",
      title: "Schlosspark Karlsruhe",
      subTitle: "March 3, 2002",
      parts: [
        for (var i = 0; i < count; i++) landscape("${nameOf(i)}.jpg"),
      ],
    ).toString();

String nameOf(int i) => String.fromCharCode("a".codeUnitAt(0) + i);

/// Shrinks the surface to [size] logical pixels for the running test.
void useSurface(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

/// Loads an album of [count] images and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, int count) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson(count))));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// A point on the tile itself, beside its toolbars.
Offset grip(WidgetTester tester, Finder of) {
  var box = tester.getRect(of);
  return Offset(box.left + 8, box.center.dy);
}

/// Picks the tile up and carries it to [to], leaving the pointer down.
///
/// The tile is armed by a sideways pull, as [ReorderablePart] asks for.
Future<TestGesture> carry(
  WidgetTester tester,
  Finder from,
  Offset to,
) async {
  var gesture = await tester.startGesture(grip(tester, from));
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(to);
  await tester.pump();
  return gesture;
}

/// Holds the pointer still for [frames] frames of 16 ms.
Future<void> hold(WidgetTester tester, {int frames = 10}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

/// The name of the image whose tile lies under the given point.
String? tileAt(WidgetTester tester, Offset point, int count) {
  for (var i = 0; i < count; i++) {
    var name = "${nameOf(i)}.jpg";
    if (tester.getRect(tile(name)).contains(point)) {
      return name;
    }
  }
  return null;
}

/// A point inside the bottom band of the viewport that lies on a tile.
///
/// The bottom edge itself may fall into the gap between two rows, and only a
/// tile draws an insert cursor.
Offset onTileNearBottom(WidgetTester tester, Rect view, int count) {
  var zone = zoneOf(view);
  for (var up = 2.0; up < zone; up += 4) {
    var point = Offset(bandX(view), view.bottom - up);
    if (tileAt(tester, point, count) != null) {
      return point;
    }
  }
  fail("No tile in the bottom band of the album.");
}

void main() {
  group('the album scrolling under a carried tile', () {
    testWidgets('a tile held at the bottom edge scrolls the album down',
        (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);
        expect(position.maxScrollExtent, greaterThan(0));
        expect(position.pixels, 0);

        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - 2),
        );

        await hold(tester, frames: 5);
        var afterFive = position.pixels;
        expect(afterFive, greaterThan(0));

        await hold(tester, frames: 5);
        expect(position.pixels, greaterThan(afterFive));

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });

    testWidgets('the pointer moved out of the band stops the scrolling',
        (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);

        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - 2),
        );
        await hold(tester);
        expect(position.pixels, greaterThan(0));
        expect(albumState(tester).dragScrolling, isTrue);

        await gesture.moveTo(view.center);
        await tester.pump();
        await hold(tester);
        expect(albumState(tester).dragScrolling, isFalse);

        var stopped = position.pixels;
        await hold(tester);
        expect(position.pixels, stopped);

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });

    testWidgets('the closer to the edge the faster the album scrolls',
        (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 20);

        var view = viewport(tester);
        var zone = zoneOf(view);
        var position = scrollPosition(tester);

        // Both speeds are measured within one drag: only the place of the
        // pointer differs, the album and the frames held are the same.
        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - zone + 4),
        );
        var before = position.pixels;
        await hold(tester, frames: 6);
        var atTheBoundary = position.pixels - before;

        await gesture.moveTo(Offset(bandX(view), view.bottom - 1));
        await tester.pump();
        before = position.pixels;
        await hold(tester, frames: 6);
        var atTheEdge = position.pixels - before;

        expect(atTheBoundary, greaterThan(0));
        expect(atTheEdge, greaterThan(atTheBoundary * 2));
        expect(position.pixels, lessThan(position.maxScrollExtent));

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });

    testWidgets('a tile held at the top edge scrolls back and stops at the top',
        (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);
        position.jumpTo(position.maxScrollExtent);
        await tester.pump();
        expect(position.pixels, greaterThan(0));

        var gesture = await carry(
          tester,
          tile("l.jpg"),
          Offset(bandX(view), view.top + 2),
        );

        await hold(tester, frames: 3);
        expect(position.pixels, lessThan(position.maxScrollExtent));

        // Held until it runs into the start of the range.
        for (var i = 0; i < 400 && albumState(tester).dragScrolling; i++) {
          await tester.pump(const Duration(milliseconds: 16));
        }
        expect(position.pixels, position.minScrollExtent);
        expect(albumState(tester).dragScrolling, isFalse);

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });

    testWidgets('the drop in the band stops the scrolling', (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);

        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - 2),
        );
        await hold(tester);
        expect(albumState(tester).dragScrolling, isTrue);

        await gesture.up();
        await tester.pumpAndSettle();

        expect(albumState(tester).dragScrolling, isFalse);
        var dropped = position.pixels;
        await hold(tester);
        expect(position.pixels, dropped);
      });
    });

    testWidgets('the cancelled drag stops the scrolling', (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);

        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - 2),
        );
        await hold(tester);
        expect(albumState(tester).dragScrolling, isTrue);

        await gesture.cancel();
        await tester.pumpAndSettle();

        expect(albumState(tester).dragScrolling, isFalse);
        var cancelled = position.pixels;
        await hold(tester);
        expect(position.pixels, cancelled);
      });
    });

    testWidgets('an album that fits its viewport does not scroll',
        (tester) async {
      useSurface(tester, const Size(600, 800));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 2);

        var view = viewport(tester);
        var position = scrollPosition(tester);
        expect(position.maxScrollExtent, 0);

        var gesture = await carry(
          tester,
          tile("a.jpg"),
          Offset(bandX(view), view.bottom - 2),
        );
        await hold(tester);

        expect(position.pixels, 0);
        expect(albumState(tester).dragScrolling, isFalse);

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });

    testWidgets(
        'the insert cursor follows the album scrolling under the '
        'resting pointer', (tester) async {
      useSurface(tester, const Size(600, 400));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, 12);

        var view = viewport(tester);
        var position = scrollPosition(tester);
        var point = onTileNearBottom(tester, view, 12);

        var gesture = await carry(tester, tile("a.jpg"), point);
        await tester.pump();

        var before = tileAt(tester, point, 12);
        expect(before, isNotNull);
        expect(
          find.descendant(of: tile(before!), matching: insertCursor),
          findsOneWidget,
        );

        await hold(tester, frames: 20);
        expect(position.pixels, greaterThan(0));

        var after = tileAt(tester, point, 12);
        expect(after, isNotNull);
        expect(after, isNot(before));
        expect(insertCursor, findsOneWidget);
        expect(
          find.descendant(of: tile(after!), matching: insertCursor),
          findsOneWidget,
        );

        await gesture.up();
        await tester.pumpAndSettle();
      });
    });
  });
}
