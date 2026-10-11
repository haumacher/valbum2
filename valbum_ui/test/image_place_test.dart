/// Where a photo was taken, as a place, issue #234.
///
/// The server names the places a photo's position lies in (GeoNames entries
/// from the country to a feature close by) in `ImagePart.places`, in the
/// language the app asks for; the properties dialog shows them as one line,
/// followed by the marker opening the position on the map. While the server
/// has no tags yet it says why, and the dialog shows that sentence instead.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/caller.dart' show defaultMapUrl;
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
    /// The URLs launched while the test runs, through the method channel the
    /// url_launcher plugin speaks in a test, where no platform plugin is
    /// registered.
    List<String> launched(WidgetTester tester) {
      var urls = <String>[];
      var messenger = tester.binding.defaultBinaryMessenger;
      const channel = MethodChannel("plugins.flutter.io/url_launcher");
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == "launch") {
          urls.add((call.arguments as Map)["url"] as String);
        }
        return true;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      return urls;
    }

    testWidgets('shows the place and the map, not the coordinates',
        (tester) async {
      await tester.pumpWidget(dialogOn(imageWith(PlaceInfo(tags: schloss))));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-place")), findsOneWidget);
      expect(find.text(testL10n.propertyPlace(schlossLine)), findsOneWidget);
      var map = find.byKey(const Key("property-place-map"));
      expect(map, findsOneWidget);
      expect(
        find.descendant(of: map, matching: find.byIcon(Icons.location_on)),
        findsOneWidget,
      );
      expect(find.byTooltip(testL10n.showOnMap), findsOneWidget);
      expect(find.byKey(const Key("property-place-expand")), findsNothing);
      expect(find.byKey(const Key("property-location")), findsNothing);
      expect(find.byKey(const Key("property-location-map")), findsNothing);
      expect(find.textContaining("48.123456"), findsNothing);
      expect(find.text(mapLocationText(testL10n, atHome)), findsNothing);
    });

    testWidgets('opens the position on the map', (tester) async {
      var urls = launched(tester);
      await tester.pumpWidget(dialogOn(
        imageWith(PlaceInfo(tags: schloss)),
        mapUrl: "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}",
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key("property-place-map")));
      await tester.pumpAndSettle();
      expect(urls, [
        mapUrlFor(
            "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}", atHome),
      ]);
      expect(urls.single,
          "https://www.openstreetmap.org/?mlat=48.123456&mlon=8.654321");
    });

    testWidgets('shows why there is no place yet, and the map', (tester) async {
      const sentence = "Place names for China are being loaded.";
      var urls = launched(tester);
      await tester
          .pumpWidget(dialogOn(imageWith(PlaceInfo(pending: sentence))));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("property-place-pending")), findsOneWidget);
      expect(find.text(sentence), findsOneWidget);
      expect(find.byKey(const Key("property-place-text")), findsNothing);
      expect(find.byKey(const Key("property-place-expand")), findsNothing);
      expect(find.byKey(const Key("property-location")), findsNothing);

      var map = find.byKey(const Key("property-place-map"));
      expect(
        find.descendant(of: map, matching: find.byIcon(Icons.location_on)),
        findsOneWidget,
      );
      await tester.tap(map);
      await tester.pumpAndSettle();
      expect(urls, [mapUrlFor(defaultMapUrl, atHome)]);
    });

    testWidgets('shows the coordinates where the server names no place',
        (tester) async {
      for (var places in [null, PlaceInfo()]) {
        await tester.pumpWidget(dialogOn(imageWith(places)));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("property-place")), findsNothing);
        expect(find.byKey(const Key("property-place-map")), findsNothing);
        expect(find.byKey(const Key("property-location")), findsOneWidget);
        expect(find.text(mapLocationText(testL10n, atHome)), findsOneWidget);
        expect(
          find.descendant(
            of: find.byKey(const Key("property-location-map")),
            matching: find.byIcon(Icons.location_on),
          ),
          findsOneWidget,
        );
      }
    });

    testWidgets('names no place without a position', (tester) async {
      var image = imageWith(PlaceInfo(tags: schloss))..location = null;
      await tester.pumpWidget(dialogOn(image));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("property-place")), findsNothing);
      expect(find.byKey(const Key("property-place-map")), findsNothing);
      expect(find.byKey(const Key("property-location")), findsNothing);
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
