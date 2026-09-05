import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

ImagePart image(String name, {int date = 0, int rating = 0}) => ImagePart(
      name: name,
      date: date,
      rating: rating,
      width: 2000,
      height: 1000,
    );

/// An album of the given parts with its transient links initialized.
AlbumInfo albumOf(List<AlbumPart> parts) {
  var album = AlbumInfo(title: "Album", parts: parts);
  AlbumInitializer().init(album);
  return album;
}

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

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

/// Loads the album fixture and enters the edit mode with [select] selected.
Future<void> pumpEditMode(
  WidgetTester tester,
  VAlbumClient client, {
  String select = "landscape.jpg",
}) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
  await tapTile(tester, select);
}

void main() {
  group('grouping the selection', () {
    test('sorts the group by date and keeps the representative', () {
      var a = image("a.jpg", date: 300);
      var b = image("b.jpg", date: 100);
      var c = image("c.jpg", date: 200);
      var d = image("d.jpg", date: 400);
      var album = albumOf([a, b, c, d]);

      var group = groupSelection(album, {a, b, c}, c)!;

      // The group takes the place of the representative, the other selected
      // parts are gone.
      expect(album.parts, [group, d]);
      // The images are sorted by date, the representative points at [c].
      expect(group.images, [b, c, a]);
      expect(group.representative, 1);

      // The album links treat the group as one part.
      expect(group.previous, isNull);
      expect(group.next, same(d));
      expect(d.previous, same(group));
      expect(group.home, same(group));
      expect(d.end, same(d));

      // The members know their group and their album, and are linked among
      // themselves.
      for (var member in group.images) {
        expect(member.group, same(group));
        expect(member.owner, same(album));
      }
      expect(b.previous, isNull);
      expect(b.next, same(c));
      expect(c.next, same(a));
      expect(a.next, isNull);
      expect(a.home, same(b));
      expect(b.end, same(a));
    });

    test('flattens a selected group into the new one', () {
      var a = image("a.jpg", date: 100);
      var b = image("b.jpg", date: 400);
      var c = image("c.jpg", date: 200);
      var inner = ImageGroup(images: [b, c], representative: 1);
      var d = image("d.jpg", date: 300);
      var album = albumOf([a, inner, d]);

      var group = groupSelection(album, {inner, d}, d)!;

      expect(album.parts, [a, group]);
      expect(group.images, [c, d, b]);
      expect(group.representative, 1);
      expect(c.group, same(group));
    });

    test('keeps the album unchanged without a usable selection', () {
      var a = image("a.jpg");
      var b = image("b.jpg");
      var album = albumOf([a, b]);

      // A single part is no group.
      expect(groupSelection(album, {a}, a), isNull);
      // The representative has to be selected.
      expect(groupSelection(album, {a, b}, image("x.jpg")), isNull);
      // A heading contributes no image.
      expect(groupSelection(album, {a, Heading(text: "H")}, a), isNull);
      expect(album.parts, [a, b]);
    });

    test('ungroups back to the stored order at the group position', () {
      var a = image("a.jpg");
      var b = image("b.jpg");
      var c = image("c.jpg");
      var d = image("d.jpg");
      var group = ImageGroup(images: [c, b], representative: 0);
      var album = albumOf([a, group, d]);

      var members = ungroup(album, group);

      expect(members, [c, b]);
      expect(album.parts, [a, c, b, d]);
      expect(a.next, same(c));
      expect(c.next, same(b));
      expect(b.next, same(d));
      expect(c.group, isNull);
      expect(c.home, same(a));
      expect(b.end, same(d));

      // A group that is not part of the album is not touched.
      expect(ungroup(album, ImageGroup(images: [image("x.jpg")])), isEmpty);
      expect(album.parts, hasLength(4));
    });

    test('survives a JSON round trip', () {
      var a = image("a.jpg", date: 300);
      var b = image("b.jpg", date: 100);
      var c = image("c.jpg", date: 200);
      var album = albumOf([a, b, c, Heading(text: "H")]);

      groupSelection(album, {a, b, c}, a);

      var reread =
          Resource.read(JsonReader.fromString(album.toString())) as AlbumInfo;
      var group = reread.parts.first as ImageGroup;
      expect(group.representative, 2);
      expect(group.images.map((i) => i.name), ["b.jpg", "c.jpg", "a.jpg"]);
      expect(reread.parts[1], isA<Heading>());
    });
  });

  group('the group tool of the tile editor', () {
    testWidgets('groups the selected tiles', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        // Heading, landscape, portrait, group.
        expect(album(tester).parts, hasLength(4));

        await tapTileWith(
            tester, "portrait.jpg", LogicalKeyboardKey.controlLeft);
        await tapTool(tester, "landscape.jpg", Icons.join_left);

        var parts = album(tester).parts;
        expect(parts, hasLength(3));
        var group = parts[1] as ImageGroup;
        expect(
            group.images.map((i) => i.name), ["landscape.jpg", "portrait.jpg"]);
        expect(group.representative, 0);
        // The selection is cleared, the tile shows the representative.
        expect(albumState(tester).selection, isEmpty);
        expect(tile("landscape.jpg"), findsOneWidget);
        expect(tile("portrait.jpg"), findsNothing);
      });
    });

    testWidgets('ungroups a selected group', (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client, select: "group-a.jpg");

        await tapTool(tester, "group-a.jpg", Icons.call_split);

        var parts = album(tester).parts;
        // Heading, landscape, portrait, group-a, group-b.
        expect(parts, hasLength(5));
        expect((parts[3] as ImagePart).name, "group-a.jpg");
        expect((parts[4] as ImagePart).name, "group-b.jpg");
        // The members stay selected.
        expect(albumState(tester).selection, {parts[3], parts[4]});
        expect(tile("group-b.jpg"), findsOneWidget);
      });
    });

    testWidgets('saves the group to the server', (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(fixture("album.json"), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        await tapTileWith(
            tester, "portrait.jpg", LogicalKeyboardKey.controlLeft);
        await tapTool(tester, "landscape.jpg", Icons.join_left);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;

      // Heading, [new group], [the group of the fixture].
      expect(saved.parts, hasLength(3));
      var group = saved.parts[1] as ImageGroup;
      expect(group.representative, 0);
      expect(
        group.images.map((i) => i.name),
        ["landscape.jpg", "portrait.jpg"],
      );
    });
  });
}
