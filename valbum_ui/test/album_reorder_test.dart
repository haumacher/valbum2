import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart' show AlbumLayout;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The tile of the image with the given file name, or the row of a heading.
Finder tile(String name) => find.byKey(ValueKey(name));
Finder headingTile(Heading heading) => find.byKey(ValueKey(heading));

/// The insert cursor of a drag in progress.
Finder get insertCursor => find.byKey(const Key("insert-cursor"));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// A short description of the stored order of the album's parts.
List<String> partNames(AlbumInfo album) => [
      for (var part in album.parts) partName(part),
    ];

String partName(AlbumPart part) {
  if (part is Heading) return "Heading(${part.text})";
  if (part is ImagePart) return part.name;
  var group = part as ImageGroup;
  return "Group(${group.images.map((i) => i.name).join(",")})";
}

ImagePart img(String name, int width, int height) =>
    ImagePart(name: name, width: width, height: height);

/// Loads the album fixture and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// The point a drag of the tile [from] starts at: beside the tile's toolbars,
/// so that the pointer lands on the tile itself.
Offset dragStart(WidgetTester tester, Finder from) {
  var box = tester.getRect(from);
  return Offset(box.left + 8, box.center.dy);
}

/// The point in the given half of [target] an insert cursor is asked for.
Offset dropPoint(WidgetTester tester, Finder target, {required bool left}) {
  var box = tester.getRect(target);
  return Offset(box.left + box.width * (left ? 0.25 : 0.75), box.center.dy);
}

/// Drags the part shown by [from] onto the left or right half of [target].
///
/// The drag is armed by a sideways pull (the album scrolls vertically, so only
/// a horizontal drag picks a tile up, see `ReorderablePart`) and then carried
/// to the drop point. [onCursor] is run while the pointer rests over the drop
/// point, before the button is released.
Future<void> dragOnto(
  WidgetTester tester,
  Finder from,
  Finder target, {
  required bool left,
  Future<void> Function()? onCursor,
}) async {
  var start = dragStart(tester, from);
  var target0 = dropPoint(tester, target, left: left);

  var gesture = await tester.startGesture(start);
  // Sideways, past the touch slop: this is what picks the tile up.
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(target0);
  await tester.pump();

  if (onCursor != null) {
    await onCursor();
  }

  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  group('the move of an album part', () {
    // An album whose layout does not show the images in the order they are
    // stored in: the row algorithm pulls the portrait image in front of the
    // landscape image it follows, which is what issue #37 is about.
    late Heading heading;
    late ImagePart a, b, c;
    late AlbumInfo info;

    setUp(() {
      heading = Heading(text: "H");
      a = img("A", 2048, 1536); // landscape
      b = img("B", 1536, 2048); // portrait
      c = img("C", 2048, 1536); // landscape
      info = AlbumInfo(parts: [heading, a, b, c]);
      AlbumInitializer().init(info);
    });

    test('the layout shows B before A', () {
      var layout = AlbumLayout(800, 250, [a, b, c]);
      expect(
        layout.getAllImages().map((i) => (i as ImagePart).name),
        ["B", "A", "C"],
      );
      // So the displayed order of the album is [H, B, A, C], while it is
      // stored as [H, A, B, C].
    });

    test('moving C behind the displayed B leaves the stored order alone', () {
      // The cursor behind B stands between B and A in the display; in the
      // stored order that is the place directly behind B, which is where C
      // already sits ([H, A, B, C]).
      expect(movePart(info, c, b), isFalse);
      expect(partNames(info), ["Heading(H)", "A", "B", "C"]);
    });

    test('moving A behind the displayed C stores it last', () {
      // A is taken out ([H, B, C]) and put back directly behind C.
      expect(movePart(info, a, c), isTrue);
      expect(partNames(info), ["Heading(H)", "B", "C", "A"]);

      // The transient links follow the new order.
      expect(b.previous, isNull);
      expect(b.next, c);
      expect(c.next, a);
      expect(a.previous, c);
      expect(a.next, isNull);
      expect(a.home, b);
      expect(a.end, a);
    });

    test('a cursor at the very beginning stores the part first', () {
      expect(movePart(info, c, null), isTrue);
      expect(partNames(info), ["C", "Heading(H)", "A", "B"]);

      // The first part is the first image of the chain now.
      expect(c.previous, isNull);
      expect(c.next, a);
      expect(a.home, c);
    });

    test('a heading is moved like any other part', () {
      expect(movePart(info, heading, a), isTrue);
      expect(partNames(info), ["A", "Heading(H)", "B", "C"]);
      // The images keep their chain, a heading is no link in it.
      expect(a.next, b);
      expect(b.previous, a);
    });

    test('moving a part onto itself changes nothing', () {
      expect(movePart(info, a, a), isFalse);
      expect(partNames(info), ["Heading(H)", "A", "B", "C"]);
    });

    test('moving a part behind where it already is changes nothing', () {
      // B directly follows A in the stored order.
      expect(movePart(info, b, a), isFalse);
      expect(partNames(info), ["Heading(H)", "A", "B", "C"]);
    });

    test('a part of another album is not moved', () {
      var stranger = img("X", 100, 100);
      expect(movePart(info, stranger, a), isFalse);
      expect(movePart(info, a, stranger), isFalse);
      expect(partNames(info), ["Heading(H)", "A", "B", "C"]);
    });

    test('the reordered album survives a round trip through JSON', () {
      expect(movePart(info, a, c), isTrue);

      var reread =
          Resource.read(JsonReader.fromString(info.toString())) as AlbumInfo;
      expect(partNames(reread), ["Heading(H)", "B", "C", "A"]);
    });
  });

  group('the drag and drop of the edit mode', () {
    // The fixture album is stored as [Heading, landscape, portrait, group]
    // and displayed as [Heading, portrait, landscape, group] - the layout
    // pulls the portrait image in front of the landscape one.
    testWidgets('the album is displayed in another order than it is stored',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        expect(partNames(album(tester)), [
          "Heading(Am Morgen)",
          "landscape.jpg",
          "portrait.jpg",
          "Group(group-a.jpg,group-b.jpg)",
        ]);
        expect(
          albumState(tester).displayOrder.map(partName),
          [
            "Heading(Am Morgen)",
            "portrait.jpg",
            "landscape.jpg",
            "Group(group-a.jpg,group-b.jpg)",
          ],
        );
      });
    });

    testWidgets('a drop behind the displayed predecessor is saved',
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

        // The landscape image is dropped behind the portrait one - which is
        // where it is *displayed* already, but not where it is stored.
        await dragOnto(
          tester,
          tile("landscape.jpg"),
          tile("portrait.jpg"),
          left: false,
          onCursor: () async {
            // The cursor stands on the right half of the target tile.
            expect(
              find.descendant(
                of: tile("portrait.jpg"),
                matching: insertCursor,
              ),
              findsOneWidget,
            );
            expect(insertCursor, findsOneWidget);
          },
        );

        // The cursor is gone once the part was dropped.
        expect(insertCursor, findsNothing);

        expect(partNames(album(tester)), [
          "Heading(Am Morgen)",
          "portrait.jpg",
          "landscape.jpg",
          "Group(group-a.jpg,group-b.jpg)",
        ]);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect(partNames(saved), [
        "Heading(Am Morgen)",
        "portrait.jpg",
        "landscape.jpg",
        "Group(group-a.jpg,group-b.jpg)",
      ]);
    });

    testWidgets('a drop on the right half inserts behind that tile',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        // The group is dropped behind the landscape image.
        await dragOnto(
          tester,
          tile("group-a.jpg"),
          tile("landscape.jpg"),
          left: false,
        );

        expect(partNames(album(tester)), [
          "Heading(Am Morgen)",
          "landscape.jpg",
          "Group(group-a.jpg,group-b.jpg)",
          "portrait.jpg",
        ]);
      });
    });

    testWidgets('a drop on the left half inserts before that tile',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        // The group is dropped before the portrait image, whose displayed
        // predecessor is the heading: the group lands directly behind it.
        await dragOnto(
          tester,
          tile("group-a.jpg"),
          tile("portrait.jpg"),
          left: true,
        );

        expect(partNames(album(tester)), [
          "Heading(Am Morgen)",
          "Group(group-a.jpg,group-b.jpg)",
          "landscape.jpg",
          "portrait.jpg",
        ]);
      });
    });

    testWidgets('a drop before the first displayed part comes first',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        var heading = album(tester).parts.first as Heading;
        await dragOnto(
          tester,
          tile("landscape.jpg"),
          headingTile(heading),
          left: true,
        );

        expect(partNames(album(tester)), [
          "landscape.jpg",
          "Heading(Am Morgen)",
          "portrait.jpg",
          "Group(group-a.jpg,group-b.jpg)",
        ]);
      });
    });

    testWidgets('a drop where the part already is changes nothing',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        var before = partNames(album(tester));

        // Onto itself: not even a cursor is shown.
        await dragOnto(
          tester,
          tile("landscape.jpg"),
          tile("landscape.jpg"),
          left: true,
          onCursor: () async => expect(insertCursor, findsNothing),
        );
        expect(partNames(album(tester)), before);

        // Onto the place it already occupies: the cursor before the portrait
        // image stands directly behind the heading, where the landscape image
        // is stored already.
        await dragOnto(
          tester,
          tile("landscape.jpg"),
          tile("portrait.jpg"),
          left: true,
          onCursor: () async => expect(insertCursor, findsOneWidget),
        );
        expect(partNames(album(tester)), before);
      });
    });

    testWidgets('the reordered album reads back in the new order',
        (tester) async {
      var client = clientReturning(fixture("album.json"));

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        await dragOnto(
          tester,
          tile("landscape.jpg"),
          tile("group-a.jpg"),
          left: false,
        );

        var order = partNames(album(tester));
        expect(order, [
          "Heading(Am Morgen)",
          "portrait.jpg",
          "Group(group-a.jpg,group-b.jpg)",
          "landscape.jpg",
        ]);

        var json = album(tester).toString();
        var reread = Resource.read(JsonReader.fromString(json)) as AlbumInfo;
        expect(partNames(reread), order);
      });
    });
  });
}
