/// The place as the caption of a photo without a description.
///
/// A picture's caption is its description where it has one; else, where its
/// place is shown as caption ("Display locations" of the album, "Display
/// location" of the picture) and the server named it, the place line. One
/// rule ([captionOf]) for every display: the caption under the viewer's
/// picture, with the map marker behind a place, and the hover tooltip of the
/// album's tiles.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/form_dialog.dart';
import 'package:valbum_ui/image_properties.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'image_description_test.dart'
    show RecordingServer, albumPath, albumUrl, right;
import 'image_location_test.dart' show atHome;
import 'image_place_test.dart' show schloss, schlossLine;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String mapTemplate =
    "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}";

/// A photo taken at the Karlsruhe palace, the place named by the server.
ImagePart placed(
  String name, {
  String comment = "",
  PlaceCaption placeCaption = PlaceCaption.unset,
  PlaceInfo? places,
  GeoLocation? location,
}) =>
    ImagePart(
      name: name,
      kind: ImageKind.image,
      width: 2000,
      height: 1000,
      comment: comment,
      placeCaption: placeCaption,
      location: location ?? atHome,
      places: places ?? PlaceInfo(tags: schloss),
    );

/// Links [parts] into an album, as loading one does.
AlbumInfo albumOf(
  List<ImagePart> parts, {
  PlaceCaption placeCaption = PlaceCaption.unset,
  List<RightName> rights = const [],
}) {
  var album =
      AlbumInfo(parts: parts, placeCaption: placeCaption, rights: rights);
  for (var part in parts) {
    part.owner = album;
  }
  return album;
}

/// The URLs launched while the test runs, see `image_place_test.dart`.
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

/// Pumps the viewer on [image] inside the space whose map is [mapTemplate].
Future<void> pumpViewer(
  WidgetTester tester,
  ImagePart image, {
  RecordingServer? server,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(CallerScope(
      caller: const CallerInfo(mapUrl: mapTemplate),
      child: MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: ImageView(
          client: (server ?? RecordingServer()).client,
          baseUrl: albumUrl,
          image: image,
          onShowImage: (next) {},
          editPath: albumPath,
        ),
      ),
    ));
    await tester.pumpAndSettle();
  });
}

/// An album answer for the app: a described photo, a placed one, a placed one
/// that hides its place, and a bare one.
String albumJson({String placeCaption = "UNSET"}) {
  const location = '"location": {"latitude": 48.123456, "longitude": 8.654321}';
  const places = '"places": {"tags": ['
      '{"name": "Germany", "kind": "COUNTRY"}, '
      '{"name": "Karlsruhe", "kind": "PLACE"}]}';
  String part(String name, String extra) =>
      '["ImagePart", {"kind": "IMAGE", "name": "$name", '
      '"date": 1015113600000, "width": 2048, "height": 1536, '
      '"orientation": "IDENTITY", "rating": 0$extra}]';
  return '["AlbumInfo", {"path": "2021/Trip", "title": "Trip", '
      '"placeCaption": "$placeCaption", '
      '"rights": [{"name": "edit"}], "parts": ['
      '${part("described.jpg", ', "comment": "At the lake", $location, $places')}, '
      '${part("placed.jpg", ', $location, $places')}, '
      '${part("hidden.jpg", ', "placeCaption": "HIDDEN", $location, $places')}, '
      '${part("bare.jpg", "")}'
      ']}]';
}

/// Pumps the app on the album of [albumJson], answering a PUT with [onPut].
Future<void> pumpAlbum(
  WidgetTester tester,
  List<http.Request> requests, {
  String Function()? body,
}) async {
  await tester.pumpWidget(VAlbumApp(
    client: VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (request.method == "PUT") {
          return http.Response('["CreateResult", {"path": "2021/Trip"}]', 200,
              headers: const {"content-type": "application/json"});
        }
        if (request.url.path == "/valbum/data/2021/Trip/") {
          return http.Response((body ?? albumJson)(), 200,
              headers: const {"content-type": "application/json"});
        }
        return http.Response(
            '["ListingInfo", {"path": "2021", "title": "2021", '
            '"rights": [{"name": "edit"}], "folders": []}]',
            200,
            headers: const {"content-type": "application/json"});
      })),
    ),
    initialRoute: const ListingOrAlbumRoute(["2021", "Trip"]),
  ));
  await tester.pumpAndSettle();
}

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The caption tooltip on the tile of [name].
Finder tileCaption(String name) => find.descendant(
    of: tile(name), matching: find.byKey(const Key("tile-caption")));

AlbumInfo shownAlbum(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent)).widget.album;

ImagePart partNamed(AlbumInfo album, String name) =>
    album.parts.whereType<ImagePart>().singleWhere((p) => p.name == name);

/// Picks [option] in the "Display location" choice of the open dialog.
Future<void> choosePlaceCaption(WidgetTester tester, String option) async {
  await tester.tap(find.byKey(const Key("image-place-caption-choice")));
  await tester.pumpAndSettle();
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
  });

  group('the caption rule', () {
    String? text(ImagePart image, PlaceCaption album) =>
        captionOf(image, albumOf([image], placeCaption: album))?.text;

    test('the description wins', () {
      var caption = captionOf(placed("a.jpg", comment: "At the lake"), null);
      expect(caption?.text, "At the lake");
      expect(caption?.isPlace, isFalse);
      expect(
          text(
              placed("a.jpg",
                  comment: "At the lake", placeCaption: PlaceCaption.shown),
              PlaceCaption.shown),
          "At the lake");
    });

    test('the place where the album shows it and the picture says nothing', () {
      for (var album in [PlaceCaption.unset, PlaceCaption.shown]) {
        var caption = captionOf(placed("a.jpg"),
            albumOf([ImagePart(name: "x")], placeCaption: album));
        expect(caption?.text, schlossLine, reason: "$album");
        expect(caption?.isPlace, isTrue);
      }
      expect(captionOf(placed("a.jpg"), null)?.text, schlossLine,
          reason: "without an album, as an album that said nothing");
    });

    test('the picture saying shown shows it, even in a hiding album', () {
      expect(
          text(placed("a.jpg", placeCaption: PlaceCaption.shown),
              PlaceCaption.hidden),
          schlossLine);
      expect(
          text(placed("a.jpg", placeCaption: PlaceCaption.shown),
              PlaceCaption.unset),
          schlossLine);
    });

    test('nothing where the picture hides it, or the album and it says nothing',
        () {
      for (var album in PlaceCaption.values) {
        expect(text(placed("a.jpg", placeCaption: PlaceCaption.hidden), album),
            isNull,
            reason: "$album");
      }
      expect(text(placed("a.jpg"), PlaceCaption.hidden), isNull);
    });

    test('nothing while the places are pending or the position is missing', () {
      expect(
          text(placed("a.jpg", places: PlaceInfo(pending: "Loading.")),
              PlaceCaption.shown),
          isNull);
      expect(
          text(
              placed("a.jpg", location: GeoLocation(latitude: 0, longitude: 0)),
              PlaceCaption.shown),
          isNull);
      var unplaced = ImagePart(name: "a.jpg", places: PlaceInfo(tags: schloss));
      expect(captionOf(unplaced, null), isNull);
      expect(
          captionOf(ImagePart(name: "a.jpg", location: atHome), null), isNull);
    });
  });

  group('the viewer', () {
    testWidgets('shows the place and its map where there is no description',
        (tester) async {
      var urls = launched(tester);
      var image = placed("a.jpg");
      albumOf([image]);

      await pumpViewer(tester, image);

      var caption = find.byKey(const Key("image-place-caption"));
      expect(caption, findsOneWidget);
      expect(
          find.textContaining(schlossLine, findRichText: true), findsOneWidget);
      expect(find.byKey(const Key("image-comment")), findsNothing);
      var marker = find.byKey(const Key("image-place-caption-map"));
      expect(marker, findsOneWidget);
      expect(find.descendant(of: marker, matching: find.byIcon(mapMarkerIcon)),
          findsOneWidget);
      expect(find.byTooltip(testL10n.showOnMap), findsOneWidget);

      await tester.tap(marker);
      await tester.pumpAndSettle();
      expect(urls, [mapUrlFor(mapTemplate, atHome)]);
    });

    testWidgets('shows the description, not the place, where there is one',
        (tester) async {
      var image = placed("a.jpg", comment: "At the lake");
      albumOf([image]);

      await pumpViewer(tester, image);

      expect(find.byKey(const Key("image-comment")), findsOneWidget);
      expect(find.text("At the lake"), findsOneWidget);
      expect(find.byKey(const Key("image-place-caption")), findsNothing);
      expect(find.byKey(const Key("image-place-caption-map")), findsNothing);
    });

    testWidgets('shows nothing where the place is hidden', (tester) async {
      var image = placed("a.jpg", placeCaption: PlaceCaption.hidden);
      albumOf([image]);
      await pumpViewer(tester, image);
      expect(find.byKey(const Key("image-caption")), findsNothing);
      expect(find.byKey(const Key("image-place-caption")), findsNothing);

      var other = placed("b.jpg");
      albumOf([other], placeCaption: PlaceCaption.hidden);
      await pumpViewer(tester, other);
      expect(find.byKey(const Key("image-caption")), findsNothing,
          reason: "the album hides it and the picture says nothing");
    });

    testWidgets('writes the place caption with the description at once',
        (tester) async {
      var server = RecordingServer();
      var image = placed("a.jpg");
      albumOf([image], rights: [right("edit")]);

      await pumpViewer(tester, image, server: server);
      await withFakeImageHttp(() async {
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("image-place-caption-choice")),
            findsOneWidget);
        await tester.enterText(find.byType(TextField), "The palace");
        await choosePlaceCaption(tester, testL10n.placeCaptionHidden);
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(image.comment, "The palace");
      expect(image.placeCaption, PlaceCaption.hidden);
      var put = server.puts.single;
      expect(put.body, contains('"comment":"The palace"'));
      expect(put.body, contains('"placeCaption":"HIDDEN"'));
    });

    testWidgets('takes both back where the write is refused', (tester) async {
      var server = RecordingServer(
          putStatus: 403,
          putBody: '["ErrorInfo", {"message": "You may not change this."}]');
      var image = placed("a.jpg");
      albumOf([image], rights: [right("edit")]);

      await pumpViewer(tester, image, server: server);
      await withFakeImageHttp(() async {
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        await choosePlaceCaption(tester, testL10n.placeCaptionHidden);
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(image.placeCaption, PlaceCaption.unset);
      expect(find.text("You may not change this."), findsOneWidget);
      // The dialog comes back holding what was chosen.
      var choice = tester.widget<DropdownButtonFormField<PlaceCaption>>(
          find.byKey(const Key("image-place-caption-choice")));
      expect(choice.initialValue, PlaceCaption.hidden);
    });
  });

  group('the image properties dialog', () {
    /// Pumps a page opening the dialog on [image]; answers what it answered.
    Future<ImagePropertiesResult? Function()> open(
        WidgetTester tester, ImagePart image) async {
      ImagePropertiesResult? answer;
      await tester.pumpWidget(localizedApp(Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () async =>
                answer = await showImageProperties(context, image),
            child: const Text("open"),
          ),
        ),
      )));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();
      return () => answer;
    }

    testWidgets('offers the choice only where the place is named',
        (tester) async {
      await tester.pumpWidget(localizedApp(
          Scaffold(body: ImagePropertiesDialog(ImagePart(name: "a.jpg")))));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("image-place-caption-choice")), findsNothing);

      await tester.pumpWidget(localizedApp(Scaffold(
          body: ImagePropertiesDialog(
              placed("b.jpg", places: PlaceInfo(pending: "Loading."))))));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("image-place-caption-choice")), findsNothing);

      await tester.pumpWidget(
          localizedApp(Scaffold(body: ImagePropertiesDialog(placed("c.jpg")))));
      await tester.pumpAndSettle();
      expect(
          find.byKey(const Key("image-place-caption-choice")), findsOneWidget);
      expect(find.text(testL10n.displayLocation), findsOneWidget);
      expect(find.text(testL10n.placeCaptionAsAlbum), findsOneWidget);

      await tester.pumpWidget(localizedApp(Scaffold(
          body: ImagePropertiesDialog(placed("d.jpg"), editable: false))));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("image-place-caption-choice")), findsNothing,
          reason: "nothing is edited where the description is not");
    });

    testWidgets('answers the choice together with the description',
        (tester) async {
      var image = placed("a.jpg");
      var result = await open(tester, image);

      await tester.enterText(find.byType(TextField), "The palace");
      await choosePlaceCaption(tester, testL10n.placeCaptionHidden);
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      var answer = result();
      expect(answer?.description, "The palace");
      expect(answer?.placeCaption, PlaceCaption.hidden);
      expect(image.placeCaption, PlaceCaption.unset,
          reason: "the dialog answers, its caller applies");
    });

    testWidgets('answers the picture\'s own word where nothing was chosen',
        (tester) async {
      var result =
          await open(tester, placed("a.jpg", placeCaption: PlaceCaption.shown));
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      var answer = result();
      expect(answer?.placeCaption, PlaceCaption.shown);
      expect(answer?.description, "");
    });
  });

  group('the album tiles', () {
    testWidgets('carry the caption as a hover tooltip', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests);
      });

      Tooltip tooltipOf(String name) =>
          tester.widget<Tooltip>(tileCaption(name));
      expect(tooltipOf("described.jpg").message, "At the lake");
      expect(tooltipOf("placed.jpg").message, "Karlsruhe · Germany",
          reason: "the place, the text alone");
      expect(tooltipOf("placed.jpg").triggerMode, TooltipTriggerMode.manual);
      expect(tileCaption("hidden.jpg"), findsNothing);
      expect(tileCaption("bare.jpg"), findsNothing);
      expect(
          find.descendant(
              of: tile("placed.jpg"), matching: find.byIcon(mapMarkerIcon)),
          findsNothing);

      // The mouse brings it up.
      var mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      addTearDown(mouse.removePointer);
      await mouse.moveTo(tester.getCenter(tile("placed.jpg")));
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text("Karlsruhe · Germany"), findsOneWidget);
      await mouse.moveTo(Offset.zero);
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
    });

    testWidgets('keep the long press for the edit mode', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests);
        await tester.longPress(tile("placed.jpg"));
        await tester.pumpAndSettle();
      });
      expect(find.byIcon(Icons.save), findsOneWidget,
          reason: "the long press entered the edit mode");
      expect(find.text("Karlsruhe · Germany"), findsNothing,
          reason: "and brought up no tooltip");
      // The editing tile carries the caption as well.
      expect(tester.widget<Tooltip>(tileCaption("placed.jpg")).message,
          "Karlsruhe · Germany");
    });

    testWidgets('follow the album that hides the places', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests,
            body: () => albumJson(placeCaption: "HIDDEN"));
      });
      expect(tileCaption("placed.jpg"), findsNothing);
      expect(tester.widget<Tooltip>(tileCaption("described.jpg")).message,
          "At the lake");
    });

    testWidgets('apply the image properties into the edit buffer',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests);
        await tester.longPress(tile("placed.jpg"));
        await tester.pumpAndSettle();
        // Select the tile and open its properties.
        await tester.tap(find.descendant(
            of: tile("placed.jpg"), matching: find.byIcon(Icons.notes)));
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField), "The palace");
        await choosePlaceCaption(tester, testL10n.placeCaptionHidden);
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var image = partNamed(shownAlbum(tester), "placed.jpg");
      expect(image.comment, "The palace");
      expect(image.placeCaption, PlaceCaption.hidden);
      expect(requests.where((r) => r.method == "PUT"), isEmpty,
          reason: "written with the album when it is saved");
    });
  });

  group('the album properties', () {
    /// Pumps a page opening the dialog on [properties]; answers what it
    /// answered.
    Future<AlbumProperties? Function()> open(
        WidgetTester tester, AlbumProperties properties) async {
      AlbumProperties? answer;
      await tester.pumpWidget(localizedApp(Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () async => answer = await showFormDialog(
              context: context,
              builder: (_) => AlbumPropertiesDialog(properties),
            ),
            child: const Text("open"),
          ),
        ),
      )));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();
      return () => answer;
    }

    Switch placesSwitch(WidgetTester tester) =>
        tester.widget<Switch>(find.descendant(
            of: find.byKey(const Key("album-place-caption")),
            matching: find.byType(Switch)));

    testWidgets('show the places of an album that said nothing',
        (tester) async {
      var result =
          await open(tester, const AlbumProperties(title: "T", subTitle: ""));

      expect(find.text(testL10n.displayLocations), findsOneWidget);
      expect(placesSwitch(tester).value, isTrue);
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
      expect(result()?.placeCaption, PlaceCaption.unset,
          reason: "left as it stood, the album goes on saying nothing");
    });

    testWidgets('answer hidden where the switch is turned off', (tester) async {
      var result =
          await open(tester, const AlbumProperties(title: "T", subTitle: ""));

      await tester.tap(find.byKey(const Key("album-place-caption")));
      await tester.pumpAndSettle();
      expect(placesSwitch(tester).value, isFalse);
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
      expect(result()?.placeCaption, PlaceCaption.hidden);
    });

    testWidgets('answer shown where a hiding album is switched on',
        (tester) async {
      var result = await open(
          tester,
          const AlbumProperties(
              title: "T", subTitle: "", placeCaption: PlaceCaption.hidden));

      expect(placesSwitch(tester).value, isFalse);
      await tester.tap(find.byKey(const Key("album-place-caption")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
      expect(result()?.placeCaption, PlaceCaption.shown);
    });

    testWidgets('of the inbox have no switch', (tester) async {
      await tester.pumpWidget(localizedApp(const Scaffold(
        body: AlbumPropertiesDialog(
          AlbumProperties(title: "Inbox", subTitle: "", kind: AlbumKind.inbox),
        ),
      )));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("album-place-caption")), findsNothing);
      expect(find.text(testL10n.displayLocations), findsNothing);
    });

    testWidgets('are written at once outside an edit session', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests);
        await tester.tap(find.byIcon(Icons.more_vert).last);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("album-properties")));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("album-place-caption")));
        await tester.pumpAndSettle();
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var put = requests.singleWhere((r) => r.method == "PUT");
      expect(put.body, contains('"placeCaption":"HIDDEN"'));
      expect(find.byIcon(Icons.save), findsNothing, reason: "no edit session");
    });

    testWidgets('go into the buffer inside an edit session', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, requests);
        await tester.longPress(tile("bare.jpg"));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert).last);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("album-properties")));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("album-place-caption")));
        await tester.pumpAndSettle();
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(shownAlbum(tester).placeCaption, PlaceCaption.hidden);
      expect(requests.where((r) => r.method == "PUT"), isEmpty);
      expect(tileCaption("placed.jpg"), findsNothing,
          reason: "the tiles follow the album at once");
    });
  });
}
