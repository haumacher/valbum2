/// Review probe of the place line as a single row: a long place name in the
/// properties dialog at phone width, with and without the server's own map
/// template, lays out without overflowing, keeps the marker, and has nothing
/// left to unfold.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/resource.dart';

import 'image_location_test.dart' show dialogOn;
import 'image_place_test.dart' show imageWith, schloss, schlossLine, tag;
import 'util/l10n.dart';

void main() {
  testWidgets('a long place line at phone width is one row with the marker',
      (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var long = [
      ...schloss,
      tag(PlaceKind.feature,
          "Schloss Karlsruhe, Botanischer Garten am Schlossgarten", 99),
    ];
    await tester.pumpWidget(dialogOn(
      imageWith(PlaceInfo(tags: long)),
      mapUrl: "https://maps.example.org/?q={lat},{lon}",
    ));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull, reason: "no overflow");
    expect(find.byKey(const Key("property-place")), findsOneWidget);
    expect(find.byKey(const Key("property-place-map")), findsOneWidget);
    expect(find.byIcon(mapMarkerIcon), findsOneWidget);
    expect(find.byKey(const Key("property-place-expand")), findsNothing);
    expect(find.byKey(const Key("property-location")), findsNothing);
    expect(find.textContaining("48."), findsNothing,
        reason: "no coordinates anywhere in the dialog");
    expect(find.byTooltip(testL10n.showOnMap), findsOneWidget);
    // The marker sits at the right end of the row, after the text.
    var text = tester.getRect(find.byKey(const Key("property-place-text")));
    var marker = tester.getRect(find.byKey(const Key("property-place-map")));
    expect(marker.left, greaterThanOrEqualTo(text.right));
    expect(marker.right, lessThanOrEqualTo(320));
  });

  test('the three-group line is unchanged by the layout', () {
    expect(placeLineText(schloss), schlossLine);
  });
}
