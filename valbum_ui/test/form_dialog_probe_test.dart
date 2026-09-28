// Probe of issue #178: on a phone with the keyboard open the album
// properties hide the crop editor, and saving there keeps the album
// picture's crop exactly as it was — also a crop panned just before the
// keyboard came up.
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'form_dialog_test.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

void main() {
  ThumbnailInfo crop() => ThumbnailInfo(
        image: "a.jpg",
        scale: 2.5,
        tx: 12.25,
        ty: -7.5,
        orientation: Orientation.rotL,
      );

  AlbumPropertiesDialog dialog() => AlbumPropertiesDialog(
        AlbumProperties(title: "Trip", subTitle: "", indexPicture: crop()),
        client: clientReturning("{}"),
        baseUrl: "http://server/valbum/data/Trip",
      );

  void expectCrop(ThumbnailInfo? actual, ThumbnailInfo expected) {
    expect(actual, isNotNull);
    expect(actual!.image, expected.image);
    expect(actual.scale, expected.scale);
    expect(actual.tx, expected.tx);
    expect(actual.ty, expected.ty);
    expect(actual.orientation, expected.orientation);
  }

  testWidgets('a title edited with the keyboard up keeps the crop',
      (tester) async {
    phoneWithKeyboard(tester);
    await withFakeImageHttp(() async {
      var opened = await openForm(tester, (_) => dialog());
      expect(find.byKey(const Key("index-picture-editor")), findsNothing);
      await tester.enterText(find.byType(TextField).first, "Trip 2026");
      await reachAndTap(tester, find.text(en.apply));
      var answer = opened.answer as AlbumProperties;
      expect(answer.title, "Trip 2026");
      expectCrop(answer.indexPicture, crop());
    });
  });

  testWidgets('a crop panned before the keyboard came up survives it',
      (tester) async {
    phoneWithKeyboard(tester, keyboard: 0);
    await withFakeImageHttp(() async {
      var opened = await openForm(tester, (_) => dialog());
      var editor = find.byKey(const Key("index-picture-editor"));
      expect(editor, findsOneWidget);
      await tester.drag(editor, const Offset(20, 0));
      await tester.pumpAndSettle();

      // The keyboard comes up: the editor steps aside.
      tester.view.viewInsets = const FakeViewPadding(bottom: 900);
      await tester.pumpAndSettle();
      expect(editor, findsNothing);
      await tester.enterText(find.byType(TextField).first, "Trip 2026");

      // And goes down again before saving: the editor comes back with the
      // panned crop, and that is what is saved.
      tester.view.viewInsets = FakeViewPadding.zero;
      await tester.pumpAndSettle();
      await reachAndTap(tester, find.text(en.apply));
      var answer = opened.answer as AlbumProperties;
      expect(answer.title, "Trip 2026");
      expect(answer.indexPicture!.tx, isNot(crop().tx),
          reason: "the pan before the keyboard is kept");
    });
  });
}
