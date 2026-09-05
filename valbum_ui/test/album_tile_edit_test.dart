import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The tool with the given icon on the tile of [name].
Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

/// The colour of the rating button with the given icon on the landscape tile.
Color? ratingColor(WidgetTester tester, IconData icon) => tester
    .widget<IconButton>(
      find.ancestor(
        of: tool("landscape.jpg", icon),
        matching: find.byType(IconButton),
      ),
    )
    .color;

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

Future<void> tapTool(WidgetTester tester, String name, IconData icon) async {
  await tester.tap(tool(name, icon));
  await tester.pumpAndSettle();
}

/// Taps a tile with the given modifier key held down.
Future<void> tapTileWith(
  WidgetTester tester,
  String name,
  LogicalKeyboardKey modifier,
) async {
  await tester.sendKeyDownEvent(modifier);
  await tapTile(tester, name);
  await tester.sendKeyUpEvent(modifier);
  await tester.pumpAndSettle();
}

/// Loads the album fixture, enters the edit mode by a long press and selects
/// the tile of [select].
Future<void> pumpEditMode(
  WidgetTester tester,
  VAlbumClient client, {
  String select = "landscape.jpg",
}) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  // The long press enters the edit mode and selects the tile pressed.
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
  await tapTile(tester, select);
}

void main() {
  group('the tile editing helpers', () {
    test('toggle a rating and mark the active button', () {
      expect(toggleRating(0, 2), 2);
      expect(toggleRating(2, 2), 0);
      expect(toggleRating(2, -1), -1);

      expect(isActiveRating(2, 2), isTrue);
      expect(isActiveRating(0, 2), isFalse);
      expect(isActiveRating(-2, -2), isTrue);
      expect(isActiveRating(-1, -1), isTrue);
      expect(isActiveRating(-1, -2), isFalse);
    });

    test('insert a heading before a part', () {
      var image = ImagePart(name: "b.jpg");
      var info = AlbumInfo(parts: [ImagePart(name: "a.jpg"), image]);

      expect(insertHeadingBefore(info, image, "Zwei"), 1);
      expect(info.parts.map((p) => p.runtimeType.toString()),
          ["ImagePart", "Heading", "ImagePart"]);
      expect((info.parts[1] as Heading).text, "Zwei");

      // A part that is not in the album is not touched.
      expect(insertHeadingBefore(info, ImagePart(name: "x.jpg"), "X"), -1);
      expect(info.parts, hasLength(3));
    });

    test('walk the image range in both directions', () {
      var a = ImagePart(name: "a.jpg");
      var b = ImagePart(name: "b.jpg");
      var c = ImagePart(name: "c.jpg");
      var heading = Heading(text: "H");
      var parts = <AlbumPart>[a, heading, b, c];

      // Excluding the anchor, including the target, headings skipped.
      expect(imageRange(parts, a, c), [b, c]);
      expect(imageRange(parts, c, a), [b, a]);
      expect(imageRange(parts, a, a), isEmpty);
    });
  });

  group('the tile editor', () {
    testWidgets('rotates without disturbing the layout', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        var landscape = album(tester).parts[1] as ImagePart;
        expect(landscape.name, "landscape.jpg");
        expect(landscape.orientation, Orientation.identity);

        var size = tester.getSize(tile("landscape.jpg"));

        await tapTool(tester, "landscape.jpg", Icons.rotate_right);
        expect(landscape.orientation, Orientation.rotR);
        // The rotated image is scaled into the tile it already has.
        expect(tester.getSize(tile("landscape.jpg")), size);

        await tapTool(tester, "landscape.jpg", Icons.rotate_left);
        expect(landscape.orientation, Orientation.identity);
        expect(tester.getSize(tile("landscape.jpg")), size);

        await tapTool(tester, "landscape.jpg", Icons.swap_vert);
        expect(landscape.orientation, Orientation.flipV);
        expect(tester.getSize(tile("landscape.jpg")), size);
      });
    });

    testWidgets('rates an image and resets the rating', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        var landscape = album(tester).parts[1] as ImagePart;

        await tapTool(tester, "landscape.jpg", Icons.star);
        expect(landscape.rating, 2);
        expect(ratingColor(tester, Icons.star), Colors.amberAccent);

        // Pressing the active button resets the rating.
        await tapTool(tester, "landscape.jpg", Icons.star);
        expect(landscape.rating, 0);
        expect(ratingColor(tester, Icons.star), Colors.white);

        // A rating below the filter threshold hides the tile right away.
        await tapTool(tester, "landscape.jpg", Icons.remove);
        expect(landscape.rating, -1);
        expect(tile("landscape.jpg"), findsNothing);

        // Widening the filter brings it back.
        await tester.tap(find.byIcon(Icons.add_circle_outline));
        await tester.pumpAndSettle();
        expect(tile("landscape.jpg"), findsOneWidget);
        expect(ratingColor(tester, Icons.remove), Colors.amberAccent);

        await tapTool(tester, "landscape.jpg", Icons.delete);
        expect(landscape.rating, -2);
      });
    });

    testWidgets('edits the comment of an image', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        await tapTool(tester, "landscape.jpg", Icons.notes);
        expect(find.text("Bildeigenschaften"), findsOneWidget);
        await tester.enterText(
          find.byType(TextField),
          "Ein Baum\nim Schlosspark",
        );
        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        expect(
          (album(tester).parts[1] as ImagePart).comment,
          "Ein Baum\nim Schlosspark",
        );
      });
    });

    testWidgets('inserts a heading before the image', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        // Select a single tile in the middle of the album.
        await tapTile(tester, "portrait.jpg");
        expect(albumState(tester).selection, hasLength(1));

        await tapTool(tester, "portrait.jpg", Icons.title);
        expect(find.text("Überschrift einfügen"), findsOneWidget);
        await tester.enterText(find.byType(TextField), "Am Mittag");
        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        var parts = album(tester).parts;
        // Heading, landscape, [new heading], portrait, group.
        expect(parts[2], isA<Heading>());
        expect((parts[2] as Heading).text, "Am Mittag");
        expect((parts[3] as ImagePart).name, "portrait.jpg");
        expect(find.text("Am Mittag"), findsOneWidget);
      });
    });

    testWidgets('selects with plain, ctrl and shift clicks', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        var state = albumState(tester);
        var parts = album(tester).parts;
        var landscape = parts[1];
        var portrait = parts[2];
        var group = parts[3];

        // The long press entered the edit mode, the click selected the tile.
        expect(state.selection, {landscape});

        // A plain click selects exactly one tile.
        await tapTile(tester, "portrait.jpg");
        expect(state.selection, {portrait});

        // Clicking the only selected tile clears the selection.
        await tapTile(tester, "portrait.jpg");
        expect(state.selection, isEmpty);

        // Ctrl toggles.
        await tapTileWith(
            tester, "portrait.jpg", LogicalKeyboardKey.controlLeft);
        await tapTileWith(
            tester, "landscape.jpg", LogicalKeyboardKey.controlLeft);
        expect(state.selection, {portrait, landscape});
        await tapTileWith(
            tester, "portrait.jpg", LogicalKeyboardKey.controlLeft);
        expect(state.selection, {landscape});

        // Shift extends the selection from the tile clicked last to the
        // images in between.
        await tapTile(tester, "portrait.jpg");
        await tapTile(tester, "landscape.jpg");
        expect(state.selection, {landscape});
        await tapTileWith(tester, "group-a.jpg", LogicalKeyboardKey.shiftLeft);
        expect(state.selection, {landscape, portrait, group});
      });
    });

    testWidgets('refuses to group until #19', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        // A single selection offers the heading tool, not the group tool.
        expect(tool("landscape.jpg", Icons.join_left), findsNothing);
        expect(tool("landscape.jpg", Icons.title), findsOneWidget);

        await tapTileWith(
            tester, "portrait.jpg", LogicalKeyboardKey.controlLeft);
        expect(albumState(tester).selection, hasLength(2));
        expect(tool("landscape.jpg", Icons.title), findsNothing);

        await tapTool(tester, "landscape.jpg", Icons.join_left);
        expect(find.text("Gruppieren kommt in #19"), findsOneWidget);
        // Nothing was changed.
        expect(album(tester).parts, hasLength(4));
      });
    });

    testWidgets('saves orientation, rating, comment and heading',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(fixture("album.json"), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        await tapTool(tester, "landscape.jpg", Icons.rotate_right);
        await tapTool(tester, "landscape.jpg", Icons.star);

        await tapTool(tester, "landscape.jpg", Icons.notes);
        await tester.enterText(find.byType(TextField), "Ein Baum");
        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        await tapTool(tester, "landscape.jpg", Icons.title);
        await tester.enterText(find.byType(TextField), "Am Mittag");
        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;

      // Heading, [new heading], landscape, portrait, group.
      expect(saved.parts, hasLength(5));
      expect((saved.parts[1] as Heading).text, "Am Mittag");

      var landscape = saved.parts[2] as ImagePart;
      expect(landscape.name, "landscape.jpg");
      expect(landscape.orientation, Orientation.rotR);
      expect(landscape.rating, 2);
      expect(landscape.comment, "Ein Baum");
    });
  });
}
