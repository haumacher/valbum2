/// Where a photo was taken, in the image properties dialog, issue #112.
///
/// The server reads the EXIF GPS tags of the original into
/// `ImagePart.location` and names the map of the space in `?type=auth`; here
/// the line is composed from both — the coordinates as a number every map
/// reads, and a tap opening them on that map.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:valbum_ui/caller.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/resource.dart';
import 'util/l10n.dart';

/// A place in the Black Forest, as the acceptance of issue #112 spells it.
GeoLocation get atHome => GeoLocation(latitude: 48.123456, longitude: 8.654321);

/// A place south of the equator and west of Greenwich.
GeoLocation get capeTown =>
    GeoLocation(latitude: -33.918861, longitude: 18.423300);

/// An image that knows where it was taken.
ImagePart imageAt(GeoLocation? location) => ImagePart(
      name: "a.jpg",
      kind: ImageKind.image,
      width: 2048,
      height: 1536,
      location: location,
    );

/// The dialog on [image], with [mapUrl] as the map of the space.
Widget dialogOn(ImagePart image, {String? mapUrl}) => CallerScope(
      caller: mapUrl == null ? null : CallerInfo(mapUrl: mapUrl),
      child: MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ImagePropertiesDialog(image)),
    );

void main() {
  group('the coordinates', () {
    test('are six decimals with a dot', () {
      expect(mapCoordinate(48.123456), "48.123456");
      expect(mapCoordinate(-33.918861), "-33.918861");
      expect(mapCoordinate(0), "0.000000");
      expect(mapCoordinate(8.6543214999), "8.654321");
    });

    test('keep the dot under a German locale', () {
      // The number is read by a map, not by a person alone: "48,123456" is a
      // different number to every map there is, see [mapCoordinate].
      Intl.withLocale("de", () {
        expect(mapCoordinate(48.123456), "48.123456");
        expect(mapLocationText(testL10n, atHome), "Location: 48.123456, 8.654321");
        expect(
          mapUrlFor("https://www.google.com/maps?q={lat},{lon}", atHome),
          "https://www.google.com/maps?q=48.123456,8.654321",
        );
      });
    });

    test('read as the line spells them', () {
      expect(mapLocationText(testL10n, atHome), "Location: 48.123456, 8.654321");
      expect(mapLocationText(testL10n, capeTown), "Location: -33.918861, 18.423300");
    });
  });

  group('the map URL', () {
    test('is the template with the numbers substituted', () {
      expect(
        mapUrlFor("https://www.google.com/maps?q={lat},{lon}", atHome),
        "https://www.google.com/maps?q=48.123456,8.654321",
      );
      expect(
        mapUrlFor("https://maps.apple.com/?ll={lat},{lon}", capeTown),
        "https://maps.apple.com/?ll=-33.918861,18.423300",
      );
    });

    test('substitutes a placeholder as often as the template names it', () {
      expect(
        mapUrlFor(
          "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map=15/{lat}/{lon}",
          atHome,
        ),
        "https://www.openstreetmap.org/?mlat=48.123456&mlon=8.654321"
        "#map=15/48.123456/8.654321",
      );
    });

    test('is the default where no server named one', () {
      expect(mapUrlFor("", atHome),
          "https://www.google.com/maps?q=48.123456,8.654321");
      expect(defaultMapUrl, "https://www.google.com/maps?q={lat},{lon}");
    });
  });

  group('the caller', () {
    test('carries the map the server named', () {
      var info = CallerInfo.of(AuthInfo(
        userName: "haui",
        role: roleAdmin,
        mapUrl: "https://maps.apple.com/?ll={lat},{lon}",
      ));
      expect(info.mapUrl, "https://maps.apple.com/?ll={lat},{lon}");
    });

    test('falls back to the default where an older server named none', () {
      expect(CallerInfo.of(AuthInfo(userName: "haui")).mapUrl, defaultMapUrl);
      expect(const CallerInfo().mapUrl, defaultMapUrl);
    });

    test('is not the same caller when the map differs', () {
      expect(const CallerInfo(mapUrl: "a"), isNot(const CallerInfo()));
    });
  });

  group('the location line', () {
    testWidgets('says where the image was taken', (tester) async {
      await tester.pumpWidget(dialogOn(imageAt(atHome)));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-location")), findsOneWidget);
      expect(find.text("Location: 48.123456, 8.654321"), findsOneWidget);
    });

    testWidgets('is absent where the file said nowhere', (tester) async {
      await tester.pumpWidget(dialogOn(imageAt(null)));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-location")), findsNothing);
      expect(find.byKey(const Key("property-location-map")), findsNothing);
    });

    testWidgets('opens the map of the space', (tester) async {
      await tester.pumpWidget(dialogOn(
        imageAt(atHome),
        mapUrl: "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}",
      ));
      await tester.pumpAndSettle();

      var line = tester.widget<ImageLocationLine>(find.byType(ImageLocationLine));
      expect(
        mapUrlFor(line.mapUrl, line.location),
        "https://www.openstreetmap.org/?mlat=48.123456&mlon=8.654321",
      );
    });

    testWidgets('opens the default map where the server named none',
        (tester) async {
      await tester.pumpWidget(dialogOn(imageAt(capeTown)));
      await tester.pumpAndSettle();

      var line = tester.widget<ImageLocationLine>(find.byType(ImageLocationLine));
      expect(
        mapUrlFor(line.mapUrl, line.location),
        "https://www.google.com/maps?q=-33.918861,18.423300",
      );
    });
  });
}
