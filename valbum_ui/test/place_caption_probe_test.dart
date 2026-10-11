/// Review probe of the place as caption composed with what already exists:
/// a description of blanks, the read-only properties dialog of a visitor, and
/// a picture overruling its album inside the viewer.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/resource.dart';

import 'image_location_test.dart' show dialogOn;
import 'image_place_test.dart' show schlossLine;
import 'place_caption_test.dart' show albumOf, placed, pumpViewer;
import 'util/l10n.dart';

void main() {
  testWidgets('a description of blanks is none: the place stands in',
      (tester) async {
    var image = placed("a.jpg", comment: " \n ");
    albumOf([image]);
    await pumpViewer(tester, image);
    expect(find.byKey(const Key("image-place-caption")), findsOneWidget);
    expect(find.byKey(const Key("image-comment")), findsNothing);
  });

  testWidgets('a picture saying shown overrules its hiding album in the viewer',
      (tester) async {
    var image = placed("a.jpg", placeCaption: PlaceCaption.shown);
    albumOf([image], placeCaption: PlaceCaption.hidden);
    await pumpViewer(tester, image);
    expect(find.byKey(const Key("image-place-caption")), findsOneWidget);
    expect(find.textContaining(schlossLine, findRichText: true), findsOneWidget);
  });

  testWidgets('a hiding album hides the place of a picture saying nothing',
      (tester) async {
    var image = placed("a.jpg");
    albumOf([image], placeCaption: PlaceCaption.hidden);
    await pumpViewer(tester, image);
    expect(find.byKey(const Key("image-place-caption")), findsNothing);
    expect(find.byKey(const Key("image-caption")), findsNothing);
  });

  testWidgets('the read-only dialog shows the place line but no choice',
      (tester) async {
    var image = placed("a.jpg");
    await tester.pumpWidget(_readOnly(image));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("property-place")), findsOneWidget);
    expect(find.byKey(const Key("property-place-map")), findsOneWidget);
    expect(find.byKey(const Key("image-place-caption-choice")), findsNothing);
    expect(find.text(testL10n.displayLocation), findsNothing);
  });

  testWidgets('the editable dialog of a picture without a place has no choice',
      (tester) async {
    var image = placed("a.jpg", places: PlaceInfo(pending: "Not yet."));
    await tester.pumpWidget(dialogOn(image));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("property-place-pending")), findsOneWidget);
    expect(find.byKey(const Key("image-place-caption-choice")), findsNothing);
    expect(captionOf(image, null), isNull);
  });
}

Widget _readOnly(ImagePart image) => MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ImagePropertiesDialog(image, editable: false),
    );
