import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

Finder tile(String name) => find.byKey(ValueKey(name));

Finder tool(String name, String tooltip) =>
    find.descendant(of: tile(name), matching: find.byTooltip(tooltip));

final Finder editor = find.byKey(const Key("index-picture-editor"));

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

/// Opens the album properties from the album's menu, where they live since
/// issue #121 — the tune icon of the edit toolbar is gone.
Future<void> openPropertiesMenu(WidgetTester tester) async {
  await tap(tester, find.byIcon(Icons.more_vert).last);
  await tap(tester, find.byKey(const Key("album-properties")));
}

AlbumInfo album(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent)).widget.album;

/// Enters the edit mode with the landscape image chosen as the album's
/// picture, and opens the album properties.
Future<void> openProperties(WidgetTester tester, {bool choose = true}) async {
  await settle(tester, () => tester.longPress(find.byType(Image).first));
  if (choose) {
    var box = tester.getRect(tile("landscape.jpg"));
    if (find
        .descendant(
            of: tile("landscape.jpg"), matching: find.byIcon(Icons.check_box))
        .evaluate()
        .isEmpty) {
      await settle(
        tester,
        () => tester.tapAt(Offset(box.left + 8, box.center.dy)),
      );
    }
    await tap(tester, tool("landscape.jpg", testL10n.useAsAlbumPicture));
  }
  await openPropertiesMenu(tester);
}

void main() {
  group('the crop helpers', () {
    final start = ThumbnailInfo(image: "a.jpg", scale: 2, tx: 10, ty: 20);

    test('pan converts tile pixels into stored offsets', () {
      // At scale 2 in a 150px tile (half the GWT tile), 30 displayed pixels
      // are 30 / (2 * 0.5) = 30 stored pixels.
      var panned = panIndexPicture(start, 30, -15, 150);
      expect(panned.tx, closeTo(40, 1e-9));
      expect(panned.ty, closeTo(5, 1e-9));
      expect(panned.scale, 2);
      expect(panned.image, "a.jpg");
      expect(start.tx, 10, reason: "the input is not touched");
    });

    test('zoom keeps the offsets and stays within the bounds', () {
      var zoomed = zoomIndexPicture(start, 1.5);
      expect(zoomed.scale, 3);
      expect(zoomed.tx, 10);
      expect(zoomed.ty, 20);
      expect(zoomIndexPicture(start, 100).scale, maxIndexPictureScale);
      // Without the picture's size there is nothing to cover against.
      expect(zoomIndexPicture(start, 0.01).scale, minIndexPictureScale);
      // A stored scale of 0 (never written by this app) counts as 1.
      expect(zoomIndexPicture(ThumbnailInfo(image: "b"), 2).scale, 2);
    });

    group('a picture of known size covers the square (issue #154)', () {
      // A 4:3 landscape picture: it covers the square from scale 4/3 on, and
      // at scale k it may be shifted by 150·(1 − 1/k) sideways and by
      // 150·(3/4 − 1/k) up and down.
      const w = 2048, h = 1536;
      final least = ThumbnailInfo(image: "l.jpg", scale: 4 / 3);

      test('the least scale is the aspect ratio, not 1', () {
        expect(leastIndexPictureScale(least, w, h), closeTo(4 / 3, 1e-12));
        expect(zoomIndexPicture(least, 0.01, width: w, height: h).scale,
            closeTo(4 / 3, 1e-12));
        expect(leastIndexPictureScale(least, 0, 0), minIndexPictureScale);
      });

      test('a pan stops at the edge of the picture', () {
        // 60 px in the 200 px editor at scale 4/3 would be 67.5 stored
        // pixels; the bound is 150·(1 − 3/4) = 37.5, and vertically 0.
        var panned = panIndexPicture(least, 60, 30, 200, width: w, height: h);
        expect(panned.tx, closeTo(37.5, 1e-9));
        expect(panned.ty, closeTo(0, 1e-9));
        var back = panIndexPicture(least, -60, -30, 200, width: w, height: h);
        expect(back.tx, closeTo(-37.5, 1e-9));
        expect(back.ty, closeTo(0, 1e-9));
        // Unbounded where the size is not known, as before.
        expect(panIndexPicture(least, 60, 0, 200).tx, closeTo(67.5, 1e-9));
      });

      test('at scale 5/3 the bound is 60', () {
        var zoomed = ThumbnailInfo(image: "l.jpg", scale: 5 / 3);
        var panned = panIndexPicture(zoomed, 200, 0, 200, width: w, height: h);
        expect(panned.tx, closeTo(60, 1e-9));
      });

      test('a zoom-out pulls the picture back in', () {
        var far = ThumbnailInfo(image: "l.jpg", scale: 4, tx: 100, ty: -80);
        var out = zoomIndexPicture(far, 0.5, width: w, height: h);
        expect(out.scale, 2);
        expect(out.tx, closeTo(75, 1e-9));
        expect(out.ty, closeTo(-37.5, 1e-9));
      });

      test('the defaults sit on the bound and are answered unchanged', () {
        for (var image in [
          ImagePart(name: "l.jpg", width: w, height: h),
          ImagePart(name: "p.jpg", width: h, height: w),
          ImagePart(
              name: "t.jpg",
              width: w,
              height: h,
              orientation: Orientation.rotL),
        ]) {
          var crop = indexPictureOf(image);
          expect(coverIndexPicture(crop, image.width, image.height), same(crop),
              reason: image.name);
        }
      });

      test('a panorama may be zoomed as far as it needs to cover', () {
        var pano = ThumbnailInfo(image: "p.jpg", scale: 1);
        expect(greatestIndexPictureScale(pano, 1000, 100), 10);
        expect(
            zoomIndexPicture(pano, 1.25, width: 1000, height: 100).scale, 10);
      });
    });

    test('the index image is found among the parts and the group members', () {
      var member = ImagePart(name: "m.jpg");
      var info = AlbumInfo(parts: [
        ImagePart(name: "a.jpg"),
        ImageGroup(images: [ImagePart(name: "r.jpg"), member]),
      ]);
      expect(indexImageOf(info), isNull);
      info.indexPicture = ThumbnailInfo(image: "m.jpg");
      expect(indexImageOf(info), same(member));
      info.indexPicture = ThumbnailInfo(image: "gone.jpg");
      expect(indexImageOf(info), isNull);
    });
  });

  group('the crop editor', () {
    testWidgets('pans by dragging, zooms by the buttons, applies on demand',
        (tester) async {
      var client = clientReturning(fixture("album.json"));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester);

      expect(editor, findsOneWidget);
      var before = album(tester).indexPicture!;
      expect(before.scale, closeTo(4 / 3, 1e-9));

      // The picture just covers the square: it cannot be zoomed out.
      expect(
          tester
              .widget<IconButton>(find.ancestor(
                  of: find.byTooltip(testL10n.zoomOut),
                  matching: find.byType(IconButton)))
              .onPressed,
          isNull);

      // Dragging 60px to the right in the 200px editor would move the stored
      // offset by 60 / (4/3 * 200/300) = 67.5; the picture stops at its edge,
      // 150 * (1 - 3/4) = 37.5 (issue #154), and zooming in keeps it there.
      await settle(tester, () => tester.drag(editor, const Offset(60, 0)));
      await tap(tester, find.byTooltip(testL10n.zoomIn));
      await tap(tester, find.byTooltip(testL10n.zoomIn));
      await tap(tester, find.byTooltip(testL10n.zoomOut));

      expect(album(tester).indexPicture!.tx, 0,
          reason: "nothing is applied before the apply button");
      await tap(tester, find.text(testL10n.apply));

      var after = album(tester).indexPicture!;
      expect(after.image, "landscape.jpg");
      expect(after.tx, closeTo(37.5, 1e-6));
      expect(after.ty, closeTo(0, 1e-6));
      expect(after.scale, closeTo(4 / 3 * 1.25, 1e-9));
    });

    testWidgets('cancel keeps the crop, reset restores the default',
        (tester) async {
      var client = clientReturning(fixture("album.json"));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester);

      await settle(tester, () => tester.drag(editor, const Offset(0, 30)));
      await tap(tester, find.text(testL10n.cancel));
      expect(album(tester).indexPicture!.ty, 0);

      await openPropertiesMenu(tester);
      await settle(tester, () => tester.drag(editor, const Offset(0, 30)));
      await tap(tester, find.byTooltip(testL10n.zoomIn));
      await tap(tester, find.byTooltip(testL10n.resetCrop));
      await tap(tester, find.text(testL10n.apply));
      var info = album(tester).indexPicture!;
      expect(info.ty, 0);
      expect(info.scale, closeTo(4 / 3, 1e-9));
    });

    testWidgets('says so when no picture is chosen', (tester) async {
      var client = clientReturning(fixture("album.json"));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester, choose: false);

      expect(editor, findsNothing);
      expect(find.byKey(const Key("index-picture-hint")), findsOneWidget);
      // The title is still editable as before.
      await settle(
          tester, () => tester.enterText(find.byType(TextField).first, "T"));
      await tap(tester, find.text(testL10n.apply));
      expect(album(tester).title, "T");
      expect(album(tester).indexPicture, isNull);
    });
  });
}
