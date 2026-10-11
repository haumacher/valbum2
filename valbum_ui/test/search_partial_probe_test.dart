/// Review probe of the slim search answer composed with what reads it: the
/// place line, the caption rule and the properties dialog must read a
/// trimmed place list exactly as the full one.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/resource.dart';

import 'image_location_test.dart' show atHome, dialogOn;
import 'image_place_test.dart' show schloss, schlossLine;

/// The tags of [schloss] as a search answers them: the line's kinds only,
/// name and kind alone.
List<PlaceTag> trimmed() => [
      for (var tag in schloss)
        if (const {
          PlaceKind.feature,
          PlaceKind.district,
          PlaceKind.place,
          PlaceKind.adm1,
          PlaceKind.country,
        }.contains(tag.kind))
          PlaceTag(name: tag.name, kind: tag.kind),
    ];

ImagePart partialPart() => ImagePart(
      name: "2024/Zoo/a.jpg",
      kind: ImageKind.image,
      width: 2048,
      height: 1536,
      location: atHome,
      places: PlaceInfo(tags: trimmed()),
      partial: true,
    );

void main() {
  test('the trimmed places read as the full ones', () {
    expect(placeLineText(trimmed()), schlossLine);
    var part = partialPart();
    expect(hasNamedPlace(part), isTrue);
    expect(captionOf(part, null)?.text, schlossLine);
    expect(captionOf(part, null)?.isPlace, isTrue);
  });

  test('a partial part survives the wire as partial', () {
    var json = partialPart().toString();
    var again = Resource.fromString(json) as ImagePart;
    expect(again.partial, isTrue);
    expect(again.places!.tags.length, trimmed().length);
    expect(again.faces, isEmpty);
  });

  testWidgets('the properties dialog of a partial photo shows the place line '
      'and the caption choice', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(dialogOn(partialPart()));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("property-place-text")), findsOneWidget);
    expect(find.textContaining(schlossLine), findsOneWidget);
    expect(find.byKey(const Key("property-place-map")), findsOneWidget);
    expect(find.byKey(const Key("image-place-caption-choice")), findsOneWidget);
  });
}
