import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

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
    await tap(tester, tool("landscape.jpg", "Als Albumbild verwenden"));
  }
  await tap(tester, find.byIcon(Icons.tune));
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
      expect(zoomIndexPicture(start, 0.01).scale, minIndexPictureScale);
      // A stored scale of 0 (never written by this app) counts as 1.
      expect(zoomIndexPicture(ThumbnailInfo(image: "b"), 2).scale, 2);
    });

    test('the index image is found among the parts and the group members',
        () {
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
      await settle(
          tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester);

      expect(editor, findsOneWidget);
      var before = album(tester).indexPicture!;
      expect(before.scale, closeTo(4 / 3, 1e-9));

      // Dragging 60px to the right in the 200px editor moves the stored
      // offset by 60 / (4/3 * 200/300) = 67.5.
      await settle(tester, () => tester.drag(editor, const Offset(60, 0)));
      await tap(tester, find.byTooltip("Vergrößern"));
      await tap(tester, find.byTooltip("Vergrößern"));
      await tap(tester, find.byTooltip("Verkleinern"));

      expect(album(tester).indexPicture!.tx, 0,
          reason: "nothing is applied before Übernehmen");
      await tap(tester, find.text("Übernehmen"));

      var after = album(tester).indexPicture!;
      expect(after.image, "landscape.jpg");
      expect(after.tx, closeTo(67.5, 1e-6));
      expect(after.ty, closeTo(0, 1e-6));
      expect(after.scale, closeTo(4 / 3 * 1.25, 1e-9));
    });

    testWidgets('cancel keeps the crop, reset restores the default',
        (tester) async {
      var client = clientReturning(fixture("album.json"));
      await settle(
          tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester);

      await settle(tester, () => tester.drag(editor, const Offset(0, 30)));
      await tap(tester, find.text("Abbrechen"));
      expect(album(tester).indexPicture!.ty, 0);

      await tap(tester, find.byIcon(Icons.tune));
      await settle(tester, () => tester.drag(editor, const Offset(0, 30)));
      await tap(tester, find.byTooltip("Vergrößern"));
      await tap(tester, find.byTooltip("Ausschnitt zurücksetzen"));
      await tap(tester, find.text("Übernehmen"));
      var info = album(tester).indexPicture!;
      expect(info.ty, 0);
      expect(info.scale, closeTo(4 / 3, 1e-9));
    });

    testWidgets('says so when no picture is chosen', (tester) async {
      var client = clientReturning(fixture("album.json"));
      await settle(
          tester, () => tester.pumpWidget(VAlbumApp(client: client)));
      await openProperties(tester, choose: false);

      expect(editor, findsNothing);
      expect(find.byKey(const Key("index-picture-hint")), findsOneWidget);
      // The title is still editable as before.
      await settle(
          tester, () => tester.enterText(find.byType(TextField).first, "T"));
      await tap(tester, find.text("Übernehmen"));
      expect(album(tester).title, "T");
      expect(album(tester).indexPicture, isNull);
    });
  });
}
