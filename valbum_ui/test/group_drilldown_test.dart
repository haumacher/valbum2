/// Tests of what creating a group opens, and of what leaving a group closes
/// (issue #79).
///
/// Which of several shots of one scene is the best cannot be seen on tiles, so
/// making a group drills straight into it: the viewer opens full-screen on the
/// image the group was made from, the group's own images are its next and
/// previous, and "make this the representative" is offered where the shots are
/// compared. Leaving a group — from a member or from the alternatives view —
/// lands on the album in one step; the alternatives view is still reached from
/// the album's tile, it is simply not a stop on the way out any more.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// An album of four plain images, so that three of them can be grouped.
const String fourImages = '''
["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": [
  ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}],
  ["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 2, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}],
  ["ImagePart", {"kind": "IMAGE", "name": "c.jpg", "date": 3, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}],
  ["ImagePart", {"kind": "IMAGE", "name": "d.jpg", "date": 4, "width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]
]}]''';

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// A tool of that tile.
Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

/// The route the app is at.
VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

/// The image the viewer shows.
AbstractImage shownImage(WidgetTester tester) =>
    tester.widget<ImageView>(find.byType(ImageView)).image;

/// The album the viewer's image belongs to — the one in the editing buffer.
AlbumInfo albumBehind(WidgetTester tester) => shownImage(tester).owner!;

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) =>
    settle(tester, () => tester.sendKeyEvent(key));

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await settle(tester, () => tester.tapAt(Offset(box.left + 8, box.center.dy)));
}

/// Adds the tile of [name] to the selection.
Future<void> addToSelection(WidgetTester tester, String name) async {
  await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
  await tapTile(tester, name);
  await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
  await tester.pumpAndSettle();
}

/// Pumps the app on the four-image album, in the edit mode, with [from]
/// selected and two more images added to the selection.
Future<void> pumpThreeSelected(
  WidgetTester tester,
  VAlbumClient client, {
  String from = "b.jpg",
}) async {
  await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
  await settle(tester, () => tester.longPress(tile(from)));
  await addToSelection(tester, "c.jpg");
  await addToSelection(tester, "d.jpg");
}

void main() {
  testWidgets('creating a group opens it on the image it was made from',
      (tester) async {
    await pumpThreeSelected(tester, clientReturning(fourImages));

    await tap(tester, tool("b.jpg", Icons.join_left));

    // The viewer, not the album: the choice is made where the shots are seen.
    expect(find.byType(ImageView), findsOneWidget);
    expect(find.byType(AlbumContent), findsNothing);
    expect(shownImage(tester).thumbnailName, "b.jpg");
    expect(routeOf(tester), const MemberRoute([], "b.jpg", "b.jpg"));

    // The group exists in the editing buffer only: four parts became two.
    var album = albumBehind(tester);
    expect(album.parts, hasLength(2));
    var group = album.parts.whereType<ImageGroup>().single;
    expect(group.images.map((image) => image.name), ["b.jpg", "c.jpg", "d.jpg"]);
    // The image it was made from is the representative until another is
    // chosen.
    expect(group.images[group.representative].name, "b.jpg");
  });

  testWidgets('next and previous move within the new group', (tester) async {
    await pumpThreeSelected(tester, clientReturning(fourImages));
    await tap(tester, tool("b.jpg", Icons.join_left));

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownImage(tester).thumbnailName, "c.jpg");
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownImage(tester).thumbnailName, "d.jpg");
    // The group is the whole world here: `a.jpg` is in the album, not in it.
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownImage(tester).thumbnailName, "d.jpg");
    await press(tester, LogicalKeyboardKey.arrowLeft);
    expect(shownImage(tester).thumbnailName, "c.jpg");
    await press(tester, LogicalKeyboardKey.home);
    expect(shownImage(tester).thumbnailName, "b.jpg");
  });

  testWidgets('the representative is chosen there and saved with the album',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (request) => http.Response(
        request.method == "PUT" ? "" : fourImages,
        200,
        headers: {"content-type": "application/json"},
      ),
      requests: requests,
    );
    await pumpThreeSelected(tester, client);
    await tap(tester, tool("b.jpg", Icons.join_left));

    // The created-from image is the representative already ...
    expect(find.byTooltip(testL10n.groupPictureIsThis), findsOneWidget);
    // ... until another of the shots is chosen.
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(find.byTooltip(testL10n.useAsGroupPicture), findsOneWidget);
    await tap(tester, find.byTooltip(testL10n.useAsGroupPicture));
    expect(find.byTooltip(testL10n.groupPictureIsThis), findsOneWidget);

    // Nothing was written yet: this is the album's editing buffer.
    expect(requests.where((r) => r.method == "PUT"), isEmpty);

    // Up lands on the album, still in the edit mode, and saving stores it.
    await press(tester, LogicalKeyboardKey.arrowUp);
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(find.byIcon(Icons.save), findsOneWidget);
    await tap(tester, find.byIcon(Icons.save));

    var put = requests.where((r) => r.method == "PUT").single;
    var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    var group = saved.parts.whereType<ImageGroup>().single;
    expect(group.images.map((image) => image.name), ["b.jpg", "c.jpg", "d.jpg"]);
    expect(group.images[group.representative].name, "c.jpg");
  });

  testWidgets('up from the new group lands on the album in one step',
      (tester) async {
    await pumpThreeSelected(tester, clientReturning(fourImages));
    await tap(tester, tool("b.jpg", Icons.join_left));

    await press(tester, LogicalKeyboardKey.arrowUp);

    expect(routeOf(tester), const ListingOrAlbumRoute([]));
    expect(find.byType(AlbumContent), findsOneWidget);
    // Two tiles: the group, and the image that was not part of it.
    expect(tile("b.jpg"), findsOneWidget);
    expect(tile("a.jpg"), findsOneWidget);
    expect(tile("c.jpg"), findsNothing);
  });

  testWidgets('a deep link to a member opens it, and its up is the album',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(
          client: clientReturning(fixture("album.json")),
          initialRoute: const MemberRoute(
            ["2005-08-24 Blumen und Fliegen"],
            "group-a.jpg",
            "group-b.jpg",
          ),
        ),
      );
      await tester.pumpAndSettle();
    });

    expect(find.byType(ImageView), findsOneWidget);
    expect(shownImage(tester).thumbnailName, "group-b.jpg");

    await press(tester, LogicalKeyboardKey.arrowUp);

    expect(
      routeOf(tester),
      const ListingOrAlbumRoute(["2005-08-24 Blumen und Fliegen"]),
    );
    expect(find.byType(AlbumContent), findsOneWidget);
  });

  testWidgets('the alternatives view is still reached from the album tile',
      (tester) async {
    await settle(
      tester,
      () => tester.pumpWidget(
        VAlbumApp(client: clientReturning(fixture("album.json"))),
      ),
    );

    // The group's tile opens the viewer on the group, which offers the way
    // down — nothing of that changed with issue #79.
    await tap(tester, find.byType(Image).at(2));
    expect(find.byIcon(Icons.expand_more), findsOneWidget);
    await tap(tester, find.byIcon(Icons.expand_more));
    expect(find.byType(GroupView), findsOneWidget);

    // Only the way out is shorter: straight to the album.
    await tap(tester, find.byIcon(Icons.arrow_back));
    expect(find.byType(AlbumContent), findsOneWidget);
  });
}
