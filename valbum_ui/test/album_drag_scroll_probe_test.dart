/// Probe of the edge scrolling during a drag (issue #42), composed with the
/// block drag of a multi-selection (issue #41) and a pointer carried out of
/// the album altogether.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Finder tile(String name) => find.byKey(ValueKey(name));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

ScrollPosition scrollPosition(WidgetTester tester) =>
    albumState(tester).albumScrollPosition!;

Rect viewport(WidgetTester tester) => tester.getRect(
      find.descendant(
        of: find.byType(AlbumContent),
        matching: find.byType(Scrollable),
      ),
    );

List<String> names(WidgetTester tester) => [
      for (var part in albumState(tester).widget.album.parts)
        (part as ImagePart).name,
    ];

Set<String> selection(WidgetTester tester) => {
      for (var part in albumState(tester).selection) (part as ImagePart).name,
    };

String nameOf(int i) => "${String.fromCharCode("a".codeUnitAt(0) + i)}.jpg";

String albumJson(int count) => AlbumInfo(
      path: "",
      title: "Schlosspark Karlsruhe",
      parts: [
        for (var i = 0; i < count; i++)
          ImagePart(name: nameOf(i), width: 2048, height: 1536),
      ],
    ).toString();

void useSurface(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}

Offset grip(WidgetTester tester, Finder of) {
  var box = tester.getRect(of);
  return Offset(box.left + 8, box.center.dy);
}

Future<void> hold(WidgetTester tester, int frames) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  testWidgets(
      'a block carried to the bottom edge is dropped behind a tile that was '
      'out of sight when it was picked up', (tester) async {
    useSurface(tester, const Size(600, 400));
    await withFakeImageHttp(() async {
      var count = 12;
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(albumJson(count))),
      );
      await tester.pumpAndSettle();
      // Enter the edit mode with a.jpg selected, then add b.jpg.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.longPressAt(grip(tester, tile("b.jpg")));
      await tester.pumpAndSettle();
      expect(selection(tester), {"a.jpg", "b.jpg"});

      var last = nameOf(count - 1);
      expect(tile(last).hitTestable(), findsNothing,
          reason: "the last tile must start out of sight");

      var view = viewport(tester);
      var gesture = await tester.startGesture(grip(tester, tile("b.jpg")));
      await gesture.moveBy(const Offset(40, 0));
      await tester.pump();
      await gesture.moveTo(Offset(view.left + view.width / 4, view.bottom - 2));
      await tester.pump();
      expect(albumState(tester).dragScrolling, isTrue);

      // Long enough to reach the end of the album at full speed.
      await hold(tester, 300);
      var position = scrollPosition(tester);
      expect(position.pixels, position.maxScrollExtent);
      expect(albumState(tester).dragScrolling, isFalse,
          reason: "the scroller stops at the end of the range");

      // Now the last tile is in view: drop the block behind it.
      var target = tester.getRect(tile(last));
      await gesture.moveTo(Offset(target.left + target.width * 0.75,
          target.center.dy));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();

      expect(names(tester), [
        for (var i = 2; i < count; i++) nameOf(i),
        "a.jpg",
        "b.jpg",
      ]);
      expect(selection(tester), {"a.jpg", "b.jpg"});
      expect(albumState(tester).dragScrolling, isFalse);
    });
  });

  testWidgets('a pointer carried below the album scrolls at full speed',
      (tester) async {
    useSurface(tester, const Size(600, 400));
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson(12))));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      var view = viewport(tester);
      var gesture = await tester.startGesture(grip(tester, tile("a.jpg")));
      await gesture.moveBy(const Offset(40, 0));
      await tester.pump();
      await gesture.moveTo(Offset(view.center.dx, view.bottom + 40));
      await tester.pump();

      var before = scrollPosition(tester).pixels;
      await hold(tester, 10);
      var scrolled = scrollPosition(tester).pixels - before;
      // Ten frames of 16 ms at the maximum speed, the first tick measuring
      // nothing yet.
      expect(scrolled, closeTo(dragScrollMaxSpeed * 0.016 * 9, 30));
      expect(scrolled, lessThan(scrollPosition(tester).maxScrollExtent));

      await gesture.up();
      await tester.pumpAndSettle();
      expect(albumState(tester).dragScrolling, isFalse);
    });
  });
}
