/// Probe of issues #60 and #61: the paging gesture composed with a change of
/// mind, with zooming in and out, with the album's end, and with iOS.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';

import 'image_view_gesture_test.dart'
    show beginDrag, moveDrag, releaseDrag, settle, shownTransform;
import 'image_view_test.dart' show imagePart, linkedAlbum, pumpViewer, shownUrl;
import 'util/fake_image_http.dart';

void main() {
  testWidgets('a far drag taken back by a flick the other way stays put',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);
    var tx = shownTransform(tester);
    var width = tester.getSize(find.byType(ImageView)).width;

    // Slowly far to the left, well past the third that would page forwards...
    var gesture = await beginDrag(tester, const Offset(-40, 0));
    await moveDrag(tester, gesture, Offset(-width / 2, 0));
    // ...then a fast flick back to the right, the finger saying "no".
    await withFakeImageHttp(() async {
      // Several fast samples, as a real finger produces them: the velocity is
      // estimated from the last hundred milliseconds of the gesture.
      for (var i = 1; i <= 4; i++) {
        await gesture.moveBy(
          const Offset(20, 0),
          timeStamp: Duration(milliseconds: 600 + 10 * i),
        );
        await tester.pump();
      }
      await gesture.up(timeStamp: const Duration(milliseconds: 650));
      await tester.pump();
    });
    await settle(tester);

    expect(shownUrl(tester), endsWith("/b.jpg"),
        reason: "neither forwards by distance nor backwards by the flick");
    expect(tx.isInitial, isTrue, reason: "snapped back");
  });

  testWidgets('zooming out again restores the axis lock', (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[1]);
    var tx = shownTransform(tester);
    var centre = tester.getCenter(find.byType(ImageView));

    // Zoom in: the image pans freely.
    await withFakeImageHttp(() async {
      await tester.tapAt(centre);
      await tester.pump();
    });
    expect(tx.scale, greaterThan(tx.fitScale));
    var zoomedTx = tx.tx;
    var zoomedTy = tx.ty;
    var pan = await beginDrag(tester, const Offset(-30, -30));
    await moveDrag(tester, pan, const Offset(-50, -50));
    expect(tx.tx, lessThan(zoomedTx));
    expect(tx.ty, lessThan(zoomedTy));
    await releaseDrag(tester, pan);
    expect(shownUrl(tester), endsWith("/b.jpg"));

    // Zoom out: the next drag is locked to its axis again.
    await withFakeImageHttp(() async {
      await tester.tapAt(centre);
      await tester.pump();
    });
    expect(tx.scale, closeTo(tx.fitScale, 0.0001));
    var centred = tx.ty;
    var drag = await beginDrag(tester, const Offset(-40, 0));
    await moveDrag(tester, drag, const Offset(-60, -80));
    expect(tx.ty, centred);
    await releaseDrag(tester, drag);
    await settle(tester);
  });

  testWidgets('a diagonal drag at the album end rubber-bands one axis only',
      (tester) async {
    var images = [imagePart("a.jpg"), imagePart("b.jpg"), imagePart("c.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[2]);
    var tx = shownTransform(tester);
    var width = tester.getSize(find.byType(ImageView)).width;
    var centred = tx.ty;
    var gesture = await beginDrag(tester, const Offset(-40, 0));
    var base = tx.tx;
    await moveDrag(tester, gesture, Offset(-width, 40));
    expect(base - tx.tx, greaterThan(0));
    expect(base - tx.tx, lessThan(width / 5), reason: "rubber band");
    expect(tx.ty, centred);
    await releaseDrag(tester, gesture);
    await settle(tester);
    expect(shownUrl(tester), endsWith("/c.jpg"));
    expect(tx.isInitial, isTrue);
  });

  testWidgets('iOS has system bars too', (tester) async {
    var modes = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == "SystemChrome.setEnabledSystemUIMode") {
          modes.add("${call.arguments}");
        }
        return null;
      },
    );
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

    var images = [imagePart("a.jpg")];
    linkedAlbum(images);
    await pumpViewer(tester, images[0]);
    expect(modes, [contains("immersiveSticky")]);
    // Before the test ends: the binding checks that no debug variable is left
    // changed, and a tear-down runs after that check.
    debugDefaultTargetPlatformOverride = null;
  });
}
