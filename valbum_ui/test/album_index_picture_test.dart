import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/routes.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String albumFolder = "2002-03-03 Schlosspark Karlsruhe";

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

Finder tool(String name, String tooltip) =>
    find.descendant(of: tile(name), matching: find.byTooltip(tooltip));

Finder badge(String name) => find.descendant(
      of: tile(name),
      matching: find.byKey(const Key("album-index-picture")),
    );

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

/// Selects the tile of [name] by a tap beside its toolbars, unless it is
/// selected already (a tap on the only selected tile would clear the
/// selection).
Future<void> select(WidgetTester tester, String name) async {
  var checked =
      find.descendant(of: tile(name), matching: find.byIcon(Icons.check_box));
  if (checked.evaluate().isNotEmpty) {
    return;
  }
  var box = tester.getRect(tile(name));
  await settle(tester, () => tester.tapAt(Offset(box.left + 8, box.center.dy)));
}

/// Serves the listing fixture at the root and the album fixture below it,
/// accepting every PUT.
VAlbumClient rootAndAlbum(List<http.Request> requests) => clientHandling(
      (request) {
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        var path = Uri.decodeComponent(request.url.path);
        var body = path.endsWith("/data/")
            ? fixture("listing.json")
            : fixture("album.json");
        return http.Response(body, 200,
            headers: {"content-type": "application/json"});
      },
      requests: requests,
    );

bool listingLoad(http.Request r) =>
    r.method == "GET" &&
    r.url.path.endsWith("/data/") &&
    r.url.queryParameters["type"] == "json";

void main() {
  group('the index picture helper', () {
    test('frames a landscape image as the server does', () {
      var info = indexPictureOf(
        ImagePart(name: "l.jpg", width: 2048, height: 1536),
      );
      expect(info.image, "l.jpg");
      expect(info.scale, closeTo(4 / 3, 1e-9));
      expect(info.tx, 0);
      expect(info.ty, 0);
    });

    test('shifts a portrait image to its middle as the server does', () {
      // The server's own output for this shape, see listing_sub.json.
      var info = indexPictureOf(
        ImagePart(name: "p.jpg", width: 1536, height: 2048),
      );
      expect(info.scale, closeTo(4 / 3, 1e-9));
      expect(info.ty, closeTo(37.5, 1e-9));
    });

    test('survives an image without dimensions', () {
      var info = indexPictureOf(ImagePart(name: "x.jpg"));
      expect(info.image, "x.jpg");
      expect(info.scale, 1);
    });
  });

  testWidgets(
      'the album picture is chosen on a tile in the edit mode, saved, and the '
      'listing above shows it anew', (tester) async {
    var requests = <http.Request>[];
    var client = rootAndAlbum(requests);

    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
    expect(find.text("Schlosspark Karlsruhe"), findsOneWidget);
    await tap(tester, find.text("Schlosspark Karlsruhe"));
    var listingLoads = requests.where(listingLoad).length;
    expect(listingLoads, 1);

    // Edit mode: no image is marked as the album's yet.
    await settle(tester, () => tester.longPress(find.byType(Image).first));
    expect(find.byKey(const Key("album-index-picture")), findsNothing);
    await select(tester, "portrait.jpg");

    // The portrait image becomes the album's picture, the tile says so, and
    // the choice moves when another image is chosen.
    await tap(tester, tool("portrait.jpg", "Als Albumbild verwenden"));
    expect(badge("portrait.jpg"), findsOneWidget);
    await select(tester, "group-a.jpg");
    await tap(tester, tool("group-a.jpg", "Als Albumbild verwenden"));
    expect(badge("portrait.jpg"), findsNothing);
    expect(badge("group-a.jpg"), findsOneWidget,
        reason: "a group is represented by its representative");
    await select(tester, "portrait.jpg");
    await tap(tester, tool("portrait.jpg", "Als Albumbild verwenden"));

    // Saved with the album.
    await tap(tester, find.byIcon(Icons.save));
    var puts = requests.where((r) => r.method == "PUT").toList();
    expect(puts, hasLength(1));
    expect(
      Uri.decodeComponent(puts.single.url.path),
      endsWith("/$albumFolder/"),
    );
    var saved =
        Resource.read(JsonReader.fromString(puts.single.body)) as AlbumInfo;
    expect(saved.indexPicture?.image, "portrait.jpg");
    expect(saved.indexPicture?.scale, closeTo(4 / 3, 1e-9));
    expect(saved.indexPicture?.ty, closeTo(37.5, 1e-9));

    // The way up fetches the listing again instead of showing the cached one.
    await tap(tester, find.byTooltip("Up"));
    expect(find.text("Test-album"), findsOneWidget);
    expect(requests.where(listingLoad).length, listingLoads + 1);
  });

  testWidgets('a failed save keeps the choice for another try',
      (tester) async {
    var client = clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("no", 500)
          : http.Response(fixture("album.json"), 200,
              headers: {"content-type": "application/json"}),
    );
    await settle(
      tester,
      () => tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute([albumFolder]),
      )),
    );
    await settle(tester, () => tester.longPress(find.byType(Image).first));
    await select(tester, "landscape.jpg");
    await tap(tester, tool("landscape.jpg", "Als Albumbild verwenden"));
    await tap(tester, find.byIcon(Icons.save));

    expect(find.byIcon(Icons.save), findsOneWidget, reason: "still editing");
    expect(badge("landscape.jpg"), findsOneWidget);
  });
}
