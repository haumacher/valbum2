/// Where a photo was taken, as a place, issue #234.
///
/// The server names the places a photo's position lies in (GeoNames entries
/// from the country to a feature close by) in `ImagePart.places`, in the
/// language the app asks for; the properties dialog shows them as one line
/// and keeps the coordinates and the map one tap away. While the server has no
/// tags yet it says why, and the dialog shows that sentence instead.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/locales.dart';
import 'package:valbum_ui/resource.dart';

import 'image_location_test.dart' show atHome, dialogOn;
import 'util/l10n.dart';

PlaceTag tag(PlaceKind kind, String name, [int id = 1]) =>
    PlaceTag(geonameId: id, name: name, kind: kind, country: "DE");

/// The tags the server answers at the Karlsruhe palace.
List<PlaceTag> get schloss => [
      tag(PlaceKind.country, "Germany", 2921044),
      tag(PlaceKind.adm1, "Baden-Württemberg", 2953481),
      tag(PlaceKind.adm2, "Karlsruhe Region", 3214104),
      tag(PlaceKind.adm3, "Stadtkreis Karlsruhe", 3220721),
      tag(PlaceKind.adm4, "Karlsruhe", 6555606),
      tag(PlaceKind.place, "Karlsruhe", 2892794),
      tag(PlaceKind.district, "Innenstadt", 8643496),
      tag(PlaceKind.feature, "Karlsruhe Schloss", 6955705),
    ];

const String schlossLine =
    "Karlsruhe Schloss · Innenstadt, Karlsruhe · Baden-Württemberg, Germany";

/// An image at [atHome] with what the server said about its place.
ImagePart imageWith(PlaceInfo? places) => ImagePart(
      name: "a.jpg",
      kind: ImageKind.image,
      width: 2048,
      height: 1536,
      location: atHome,
      places: places,
    );

void main() {
  group('the place line', () {
    test('goes from the feature to the country, in three groups', () {
      expect(placeLineText(schloss), schlossLine);
    });

    test('says a name once', () {
      expect(
        placeLineText([
          tag(PlaceKind.country, "Germany"),
          tag(PlaceKind.adm1, "Berlin"),
          tag(PlaceKind.place, "Berlin"),
          tag(PlaceKind.district, "Mitte"),
        ]),
        "Mitte, Berlin · Germany",
      );
    });

    test('names the most local division where there is no town', () {
      expect(
        placeLineText([
          tag(PlaceKind.country, "Germany"),
          tag(PlaceKind.adm1, "Bayern"),
          tag(PlaceKind.adm2, "Oberbayern"),
          tag(PlaceKind.adm3, "Landkreis Miesbach"),
          tag(PlaceKind.feature, "Wallberg"),
        ]),
        "Wallberg · Landkreis Miesbach · Bayern, Germany",
      );
    });

    test('is the country alone where nothing else is known', () {
      expect(placeLineText([tag(PlaceKind.country, "Deutschland")]),
          "Deutschland");
    });
  });

  group('the properties dialog', () {
    testWidgets('shows the place, the coordinates one tap away',
        (tester) async {
      await tester.pumpWidget(dialogOn(imageWith(PlaceInfo(tags: schloss))));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-place")), findsOneWidget);
      expect(find.text(testL10n.propertyPlace(schlossLine)), findsOneWidget);
      expect(find.byKey(const Key("property-location")), findsNothing);
      expect(find.byKey(const Key("property-location-map")), findsNothing);

      await tester.tap(find.byTooltip(testL10n.showCoordinates));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("property-location")), findsOneWidget);
      expect(find.text("Location: 48.123456, 8.654321"), findsOneWidget);
      expect(find.byKey(const Key("property-location-map")), findsOneWidget);

      await tester.tap(find.byTooltip(testL10n.hideCoordinates));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("property-location")), findsNothing);
    });

    testWidgets('shows why there is no place yet', (tester) async {
      const sentence = "Place names for China are being loaded.";
      await tester
          .pumpWidget(dialogOn(imageWith(PlaceInfo(pending: sentence))));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-place-pending")), findsOneWidget);
      expect(find.text(sentence), findsOneWidget);
      expect(find.byKey(const Key("property-place-text")), findsNothing);

      await tester.tap(find.byKey(const Key("property-place-expand")));
      await tester.pumpAndSettle();
      expect(find.text("Location: 48.123456, 8.654321"), findsOneWidget);
    });

    testWidgets('shows the coordinates where the server names no place',
        (tester) async {
      for (var places in [null, PlaceInfo()]) {
        await tester.pumpWidget(dialogOn(imageWith(places)));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("property-place")), findsNothing);
        expect(find.byKey(const Key("property-location")), findsOneWidget);
      }
    });

    testWidgets('names no place without a position', (tester) async {
      var image = imageWith(PlaceInfo(tags: schloss))..location = null;
      await tester.pumpWidget(dialogOn(image));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("property-place")), findsNothing);
    });
  });

  group('the language of the place names', () {
    /// The `Accept-Language` of a request the client makes.
    Future<String?> sent() async {
      String? header;
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async {
          header = request.headers[acceptLanguageHeader];
          return http.Response('["ListingInfo",{"title":"x"}]', 200,
              headers: {"content-type": "application/json"});
        }),
      );
      await client.loadResource([]);
      return header;
    }

    testWidgets('every request carries the language the app speaks',
        (tester) async {
      tester.platformDispatcher.localesTestValue = const [Locale("de", "AT")];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      expect(appLocale, const Locale("de"));
      expect(await tester.runAsync(sent), "de");

      tester.platformDispatcher.localesTestValue = const [Locale("en", "GB")];
      expect(await tester.runAsync(sent), "en");
    });

    testWidgets('a language the app does not speak asks for its English',
        (tester) async {
      tester.platformDispatcher.localesTestValue = const [Locale("fr")];
      addTearDown(tester.platformDispatcher.clearLocalesTestValue);
      expect(await tester.runAsync(sent), "en");
    });
  });
}
