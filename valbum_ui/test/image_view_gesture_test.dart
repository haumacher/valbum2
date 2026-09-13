/// The paging gestures of the single image viewer, see issue #61.
///
/// A drag of the fitted image is locked to one axis and pages by distance as
/// well as by velocity; a zoomed image pans freely and never navigates.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_transform.dart';
import 'package:valbum_ui/image_view.dart';

import 'image_view_test.dart' show imagePart, linkedAlbum, pumpViewer, shownUrl;
import 'util/fake_image_http.dart';

/// The state of the viewer under test.
ImageViewState viewer(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

/// The transform of the image currently shown.
ImageTransform shownTransform(WidgetTester tester) =>
    viewer(tester).transform(tester.getSize(find.byType(ImageView)));

/// Drags the image by [total], in [steps] moves [pause] apart.
///
/// The pause between the moves decides the velocity of the drag: the default
/// is far too slow to count as a flick, so only the distance can navigate.
Future<TestGesture> dragImage(
  WidgetTester tester,
  Offset total, {
  int steps = 6,
  Duration pause = const Duration(milliseconds: 300),
  bool release = true,
}) async {
  late TestGesture gesture;
  await withFakeImageHttp(() async {
    var start = tester.getCenter(find.byType(ImageView));
    var time = Duration.zero;
    gesture = await tester.startGesture(start);
    for (var i = 0; i < steps; i++) {
      time += pause;
      await gesture.moveBy(total / steps.toDouble(), timeStamp: time);
      await tester.pump();
    }
  });
  if (release) {
    await releaseDrag(tester, gesture);
  }
  return gesture;
}

/// Lifts the finger of a drag started with `release: false`.
Future<void> releaseDrag(WidgetTester tester, TestGesture gesture) async {
  await withFakeImageHttp(() async {
    await gesture.up();
    await tester.pump();
  });
}

/// Starts a drag and moves it past the touch slop, along [direction].
///
/// The gesture recognizer only starts to report a drag once the finger passed
/// the slop, and the movement up to that point is dropped (which is what keeps
/// a drag from jumping). The tests measuring how far the image follows the
/// finger therefore start here and measure from the transform as it stands
/// once the drag has really begun.
Future<TestGesture> beginDrag(WidgetTester tester, Offset direction) async {
  late TestGesture gesture;
  await withFakeImageHttp(() async {
    gesture = await tester.startGesture(
      tester.getCenter(find.byType(ImageView)),
    );
    await gesture.moveBy(
      direction,
      timeStamp: const Duration(milliseconds: 300),
    );
    await tester.pump();
  });
  return gesture;
}

/// Moves a running drag by [delta].
Future<void> moveDrag(
  WidgetTester tester,
  TestGesture gesture,
  Offset delta, {
  Duration timeStamp = const Duration(milliseconds: 600),
}) async {
  await withFakeImageHttp(() async {
    await gesture.moveBy(delta, timeStamp: timeStamp);
    await tester.pump();
  });
}

/// Lets a running snap-back animation finish.
Future<void> settle(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('a horizontal drag of the fitted image moves it horizontally',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    var tx = shownTransform(tester);
    var centred = tx.ty;

    var gesture = await beginDrag(tester, const Offset(-60, 0));
    var base = tx.tx;

    await moveDrag(tester, gesture, const Offset(-120, 0));

    expect(tx.tx, closeTo(base - 120, 0.5));
    expect(tx.ty, centred);
  });

  testWidgets('a drag that started horizontally never moves vertically',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    var tx = shownTransform(tester);
    var centred = tx.ty;

    var gesture = await beginDrag(tester, const Offset(-60, 0));

    // The first movement of the drag decides the axis.
    await moveDrag(tester, gesture, const Offset(-60, 0));
    var base = tx.tx;

    // From here the finger drifts away diagonally; the image does not.
    await moveDrag(
      tester,
      gesture,
      const Offset(-60, -100),
      timeStamp: const Duration(milliseconds: 900),
    );

    expect(tx.tx, closeTo(base - 60, 0.5));
    expect(tx.ty, centred);
  });

  testWidgets('a drag that started vertically never moves the image',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    var tx = shownTransform(tester);

    await dragImage(tester, const Offset(-40, -240), release: false);

    expect(tx.tx, tx.fitTx);
    expect(tx.ty, tx.fitTy);
  });

  testWidgets('a slow drag beyond a third of the page pages to the next image',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    // The page is 800 wide, so a third is 267.
    await dragImage(tester, const Offset(-420, 0));
    await settle(tester);

    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");
    expect(shownTransform(tester).isInitial, isTrue);
  });

  testWidgets('a slow drag beyond a third to the right shows the previous',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    await dragImage(tester, const Offset(420, 0));
    await settle(tester);

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('a short slow drag snaps back and does not navigate',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    var tx = shownTransform(tester);

    // A fifth of the page width, and far too slow to be a flick.
    await dragImage(tester, const Offset(-160, 0));
    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");

    await settle(tester);

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
    expect(tx.tx, tx.fitTx);
    expect(tx.isInitial, isTrue);
  });

  testWidgets('a fast flick still pages, however short', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    await withFakeImageHttp(() async {
      await tester.fling(
        find.byType(ImageView),
        const Offset(-100, 0),
        1000,
      );
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");

    // And back again.
    await withFakeImageHttp(() async {
      await tester.fling(find.byType(ImageView), const Offset(100, 0), 1000);
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('a drag to the right at the first image only rubber-bands',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    var tx = shownTransform(tester);

    var gesture = await dragImage(
      tester,
      const Offset(400, 0),
      release: false,
    );

    // The band approaches a fifth of the page width and never passes it.
    var moved = tx.tx - tx.fitTx;
    expect(moved, greaterThan(0));
    expect(moved, lessThan(800 / 5));

    await releaseDrag(tester, gesture);
    await settle(tester);

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
    expect(tx.tx, tx.fitTx);
  });

  testWidgets('a flick to the right at the first image does not navigate',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    await withFakeImageHttp(() async {
      await tester.fling(find.byType(ImageView), const Offset(300, 0), 1500);
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('a drag to the left at the last image only rubber-bands',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);

    var tx = shownTransform(tester);

    await dragImage(tester, const Offset(-400, 0));

    var moved = tx.fitTx - tx.tx;
    expect(moved, greaterThan(0));
    expect(moved, lessThan(800 / 5));

    await settle(tester);

    expect(shownUrl(tester), "http://server/valbum/data/album/b.jpg");
    expect(tx.tx, tx.fitTx);
  });

  testWidgets('a zoomed image pans freely and never navigates', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    // A click zooms to the pixel-by-pixel display.
    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(400, 300));
      await tester.pumpAndSettle();
    });

    var tx = shownTransform(tester);
    expect(tx.scale, 1.0);

    var gesture = await beginDrag(tester, const Offset(-60, -60));
    var before = Offset(tx.tx, tx.ty);

    await moveDrag(tester, gesture, const Offset(-300, -120));
    await releaseDrag(tester, gesture);
    await settle(tester);

    // Both axes followed the finger, and the drag stayed on the image.
    expect(tx.tx, closeTo(before.dx - 300, 0.5));
    expect(tx.ty, closeTo(before.dy - 120, 0.5));
    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('a two-finger gesture never navigates', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    await withFakeImageHttp(() async {
      var centre = tester.getCenter(find.byType(ImageView));
      var first = await tester.startGesture(
        centre - const Offset(60, 0),
        pointer: 1,
      );
      var second = await tester.startGesture(
        centre + const Offset(60, 0),
        pointer: 2,
      );
      var time = Duration.zero;
      for (var i = 0; i < 5; i++) {
        time += const Duration(milliseconds: 16);
        await first.moveBy(const Offset(-60, 0), timeStamp: time);
        await second.moveBy(const Offset(-60, 0), timeStamp: time);
        await tester.pump();
      }
      await first.up();
      await second.up();
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });

  testWidgets('a fast flick of a zoomed image does not navigate',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);

    await withFakeImageHttp(() async {
      await tester.tapAt(const Offset(400, 300));
      await tester.pumpAndSettle();
      await tester.fling(find.byType(ImageView), const Offset(-300, 0), 1500);
      await tester.pumpAndSettle();
    });

    expect(shownUrl(tester), "http://server/valbum/data/album/a.jpg");
  });
}
