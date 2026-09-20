/// One image properties dialog for the tile and for the viewer, issue #122.
///
/// The tile's tool showed the file name, the recording time and the camera;
/// the viewer's description button showed none of them — two call sites
/// composing one dialog by hand, which is why they had drifted. They open the
/// same dialog now, so the same image says the same thing wherever it is
/// asked.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The folder the album lives in.
const List<String> albumPath = ["Zoo"];

/// The album the tests open: one image that knows when and by what it was
/// taken, and one that knows neither.
String albumJson() => AlbumInfo(
      path: "",
      title: "Zoo",
      parts: [
        ImagePart(
          name: "a.jpg",
          date: 1015113600000,
          camera: "Canon EOS 70D",
          comment: "Am Meer",
          kind: ImageKind.image,
          width: 2048,
          height: 1536,
        ),
        ImagePart(
          name: "where.jpg",
          date: 1015113600000,
          camera: "Canon EOS 70D",
          comment: "Am Meer",
          kind: ImageKind.image,
          width: 2048,
          height: 1536,
          location: GeoLocation(latitude: 48.123456, longitude: 8.654321),
        ),
        ImagePart(
          name: "plain.jpg",
          comment: "Nichts bekannt",
          kind: ImageKind.image,
          width: 2048,
          height: 1536,
        ),
      ],
    ).toString();

Finder tile(String name) => find.byKey(ValueKey(name));

/// The lines of the details block of the open dialog, in the order they read.
List<String> detailLines(WidgetTester tester) => [
      for (var widget in tester.widgetList<SelectableText>(
        find.descendant(
          of: find.byKey(const Key("properties-details")),
          matching: find.byType(SelectableText),
        ),
      ))
        widget.data!,
    ];

/// The text the dialog's one field holds.
String fieldText(WidgetTester tester) =>
    tester.widget<TextField>(find.byType(TextField)).controller!.text;

/// Pumps the app on the viewer of [name].
Future<void> pumpViewerOn(WidgetTester tester, String name) async {
  await tester.pumpWidget(VAlbumApp(
    client: clientReturning(albumJson()),
    initialRoute: ImageRoute(albumPath, name),
  ));
  await tester.pumpAndSettle();
}

/// Hands the app another route, as a deep link does.
Future<void> goTo(WidgetTester tester, VAlbumRoute route) async {
  var delegate = tester
      .widget<MaterialApp>(find.byType(MaterialApp))
      .routerDelegate! as VAlbumRouterDelegate;
  await delegate.setNewRoutePath(route);
  delegate.notifyListeners();
  await tester.pumpAndSettle();
}

/// Opens the properties from the viewer currently shown.
Future<void> openFromViewer(WidgetTester tester) async {
  expect(find.byType(ImageView), findsOneWidget);
  // The long press of the viewer opens the properties of the picture shown.
  await tester.longPress(
    find.byKey(const Key("image-picture")),
    warnIfMissed: false,
  );
  await tester.pumpAndSettle();
}

/// Opens the properties of [name] from its tile, in the album's edit mode.
Future<void> openFromTile(WidgetTester tester, String name) async {
  await goTo(tester, const ListingOrAlbumRoute(albumPath));
  await tester.longPress(tile(name));
  await tester.pumpAndSettle();
  await tester.tap(
    find.descendant(of: tile(name), matching: find.byIcon(Icons.notes)),
  );
  await tester.pumpAndSettle();
}

/// Closes the open dialog without changing anything.
Future<void> cancel(WidgetTester tester) async {
  await tester.tap(find.text("Abbrechen"));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the tile and the viewer show the same lines', (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "a.jpg");
      await openFromViewer(tester);
      expect(find.text("Image properties"), findsOneWidget);
      var fromViewer = detailLines(tester);
      await cancel(tester);

      await openFromTile(tester, "a.jpg");
      expect(find.text("Image properties"), findsOneWidget);
      var fromTile = detailLines(tester);

      expect(fromViewer, fromTile);
      expect(fromTile.first, "File: a.jpg");
      expect(fromTile[1], startsWith("Taken: "));
      expect(fromTile[2], "Camera: Canon EOS 70D");
    });
  });

  testWidgets('each line is addressed by its own key', (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "a.jpg");
      await openFromViewer(tester);

      expect(find.byKey(const Key("property-file")), findsOneWidget);
      expect(find.byKey(const Key("property-time")), findsOneWidget);
      expect(find.byKey(const Key("property-camera")), findsOneWidget);
    });
  });

  testWidgets('the location line is in the tile and in the viewer alike',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "where.jpg");
      await openFromViewer(tester);
      var fromViewer = detailLines(tester);
      expect(find.byKey(const Key("property-location")), findsOneWidget);
      await cancel(tester);

      await openFromTile(tester, "where.jpg");
      var fromTile = detailLines(tester);
      expect(find.byKey(const Key("property-location")), findsOneWidget);

      expect(fromViewer, fromTile);
      // Where it was taken is the last thing said about the file, after what
      // took it, see issue #112.
      expect(fromTile.last, "Location: 48.123456, 8.654321");
    });
  });

  testWidgets('an image that says nowhere has no location line',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "a.jpg");
      await openFromViewer(tester);

      expect(find.byKey(const Key("property-location")), findsNothing);
      expect(detailLines(tester).last, "Camera: Canon EOS 70D");
    });
  });

  testWidgets('a part that knows neither time nor camera says neither',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "plain.jpg");
      await openFromViewer(tester);

      expect(detailLines(tester), ["File: plain.jpg"]);
      expect(find.byKey(const Key("property-time")), findsNothing);
      expect(find.byKey(const Key("property-camera")), findsNothing);
      await cancel(tester);

      await openFromTile(tester, "plain.jpg");

      expect(detailLines(tester), ["File: plain.jpg"]);
      expect(find.byKey(const Key("property-time")), findsNothing);
      expect(find.byKey(const Key("property-camera")), findsNothing);
    });
  });

  testWidgets('the description of the image is the field of both',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpViewerOn(tester, "a.jpg");
      await openFromViewer(tester);
      expect(fieldText(tester), "Am Meer");
      await cancel(tester);

      await openFromTile(tester, "a.jpg");
      expect(fieldText(tester), "Am Meer");
    });
  });
}
