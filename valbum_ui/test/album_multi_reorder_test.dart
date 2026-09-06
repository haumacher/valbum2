import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart' show AlbumLayout;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The insert cursor of a drag in progress.
Finder get insertCursor => find.byKey(const Key("insert-cursor"));

/// The badge of the drag feedback saying how many parts are carried.
Finder get feedbackCount => find.byKey(const Key("drag-feedback-count"));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

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

/// A landscape image of the given name.
ImagePart landscape(String name) => img(name, 2048, 1536);

/// A portrait image of the given name.
ImagePart portrait(String name) => img(name, 1536, 2048);

/// The album the tests below are laid out with, as JSON.
///
/// Five images, portrait and landscape mixed, so that the row layout shows
/// them in another order than they are stored in (see the unit tests).
String albumJson() => AlbumInfo(
      path: "",
      title: "Schlosspark Karlsruhe",
      subTitle: "March 3, 2002",
      parts: [
        landscape("a.jpg"),
        portrait("b.jpg"),
        landscape("c.jpg"),
        portrait("d.jpg"),
        landscape("e.jpg"),
      ],
    ).toString();

/// Loads [albumJson] and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson())));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// A point on the tile itself, beside its toolbars.
Offset grip(WidgetTester tester, Finder of) {
  var box = tester.getRect(of);
  return Offset(box.left + 8, box.center.dy);
}

/// The point in the given half of [target] an insert cursor is asked for.
Offset dropPoint(WidgetTester tester, Finder target, {required bool left}) {
  var box = tester.getRect(target);
  return Offset(box.left + box.width * (left ? 0.25 : 0.75), box.center.dy);
}

/// Selects exactly the parts with the given names, by long presses on their
/// tiles (the gesture that toggles the selection in the edit mode).
Future<void> selectOnly(WidgetTester tester, List<String> names) async {
  var state = albumState(tester);
  var wanted = names.toSet();
  var selected = {
    for (var part in state.selection) partName(part),
  };
  for (var name in {...selected, ...wanted}) {
    if (selected.contains(name) == wanted.contains(name)) {
      continue;
    }
    await tester.longPressAt(grip(tester, tile(name)));
    await tester.pumpAndSettle();
  }
  expect(
    {for (var part in albumState(tester).selection) partName(part)},
    wanted,
  );
}

/// The names of the currently selected parts.
Set<String> selectionOf(WidgetTester tester) =>
    {for (var part in albumState(tester).selection) partName(part)};

/// Drags the tile [from] onto the given half of [target], or, if [target] is
/// `null`, out of the album and drops it there (a cancelled drag).
///
/// [onCursor] runs while the pointer rests at the drop point, before the
/// button is released.
Future<void> dragOnto(
  WidgetTester tester,
  Finder from,
  Finder? target, {
  bool left = false,
  Future<void> Function()? onCursor,
}) async {
  var gesture = await tester.startGesture(grip(tester, from));
  // Sideways, past the touch slop: this is what picks the tile up.
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(
    target == null
        // The app bar: no drop target of the album.
        ? const Offset(400, 4)
        : dropPoint(tester, target, left: left),
  );
  await tester.pump();

  if (onCursor != null) {
    await onCursor();
  }

  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  group('the move of several album parts', () {
    late ImagePart a, b, c, d, e;
    late AlbumInfo info;

    setUp(() {
      a = landscape("A");
      b = portrait("B");
      c = landscape("C");
      d = portrait("D");
      e = landscape("E");
      info = AlbumInfo(parts: [a, b, c, d, e]);
      AlbumInitializer().init(info);
    });

    test('the layout shows the images in another order than they are stored',
        () {
      var shown = AlbumLayout(800, 250, [a, b, c, d, e])
          .getAllImages()
          .map((i) => (i as ImagePart).name)
          .toList();
      expect(shown.toSet(), {"A", "B", "C", "D", "E"});
      expect(shown, isNot(["A", "B", "C", "D", "E"]),
          reason: "the row layout pulls a portrait image forward");
    });

    test('a selection is moved as one block, in its stored order', () {
      // B and D are selected and D is the tile picked up; the block is put
      // down behind E all the same, and B keeps its place in front of D.
      expect(moveParts(info, [d, b], e), isTrue);
      expect(partNames(info), ["A", "C", "E", "B", "D"]);

      // The chain follows the new order.
      expect(a.next, c);
      expect(e.next, b);
      expect(b.next, d);
      expect(d.next, isNull);
      expect(b.previous, e);
      expect(d.home, a);
      expect(d.end, d);
    });

    test('a block put down where it already is changes nothing', () {
      // At the very beginning, where A and B already are.
      expect(moveParts(info, [a, b], null), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);

      // Directly behind B, where C and D already are.
      expect(moveParts(info, [c, d], b), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);
    });

    test('a cursor inside the block moves the block to where it is', () {
      // The cursor behind C, which is carried itself: the block cannot be
      // put down behind a part on its way with it, so the nearest part that
      // stays - A - takes its place, and B and C land behind A, where they
      // already are.
      expect(moveParts(info, [b, c], c), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);
      expect(info.parts, hasLength(5));
      for (var part in [a, b, c, d, e]) {
        expect(info.parts.where((p) => identical(p, part)), hasLength(1));
      }

      // The same for a block starting at the very first part: there is no
      // part before it that stays, so the block stays at the beginning.
      expect(moveParts(info, [a, b], b), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);

      // A cursor inside a block whose parts do not stand together does move
      // it: behind the cursor at D, C is the last part that stays in front
      // of it, so B and D are pulled together behind C.
      expect(moveParts(info, [b, d], d), isTrue);
      expect(partNames(info), ["A", "C", "B", "D", "E"]);
    });

    test('a block containing a heading moves as a whole', () {
      var heading = Heading(text: "H");
      var withHeading = AlbumInfo(parts: [a, heading, b, c, d]);
      AlbumInitializer().init(withHeading);

      expect(moveParts(withHeading, [heading, b], d), isTrue);
      expect(partNames(withHeading),
          ["A", "C", "D", "Heading(H)", "B"]);
      // A heading is no link of the image chain.
      expect(a.next, c);
      expect(c.next, d);
      expect(d.next, b);
    });

    test('a block containing a part of another album is not moved', () {
      var stranger = landscape("X");
      expect(moveParts(info, [b, stranger], e), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);

      // Neither is a block put down behind a stranger.
      expect(moveParts(info, [b, d], stranger), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);

      // An empty block is no move either.
      expect(moveParts(info, [], e), isFalse);
      expect(partNames(info), ["A", "B", "C", "D", "E"]);
    });

    test('the reordered album survives a round trip through JSON', () {
      expect(moveParts(info, [d, b], e), isTrue);

      var reread =
          Resource.read(JsonReader.fromString(info.toString())) as AlbumInfo;
      expect(partNames(reread), ["A", "C", "E", "B", "D"]);
    });
  });

  group('the drag and drop of a selection', () {
    testWidgets('the whole selection is dropped behind the cursor',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester);
        await selectOnly(tester, ["b.jpg", "d.jpg"]);

        // D is the tile picked up, B travels with it.
        await dragOnto(
          tester,
          tile("d.jpg"),
          tile("e.jpg"),
          onCursor: () async {
            expect(feedbackCount, findsOneWidget);
            expect(tester.widget<Text>(feedbackCount).data, "2 Teile");
            expect(
              find.descendant(of: tile("e.jpg"), matching: insertCursor),
              findsOneWidget,
            );
          },
        );

        expect(partNames(album(tester)),
            ["a.jpg", "c.jpg", "e.jpg", "b.jpg", "d.jpg"]);
        // The moved block is what the user is now looking at.
        expect(selectionOf(tester), {"b.jpg", "d.jpg"});
        expect(albumState(tester).lastClicked, isNotNull);
        expect(partName(albumState(tester).lastClicked!), "d.jpg");
        expect(feedbackCount, findsNothing);
      });
    });

    testWidgets('an unselected tile is dragged on its own', (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester);
        await selectOnly(tester, ["b.jpg", "d.jpg"]);

        await dragOnto(
          tester,
          tile("a.jpg"),
          tile("e.jpg"),
          onCursor: () async {
            // No block, no count badge - and picking an unselected tile up
            // leaves the selection alone.
            expect(feedbackCount, findsNothing);
            expect(selectionOf(tester), {"b.jpg", "d.jpg"});
          },
        );

        // Only A moved.
        expect(partNames(album(tester)),
            ["b.jpg", "c.jpg", "d.jpg", "e.jpg", "a.jpg"]);
        // The dropped part is selected, as a single move has always done.
        expect(selectionOf(tester), {"a.jpg"});
      });
    });

    testWidgets('a carried tile is no drop target', (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester);
        await selectOnly(tester, ["b.jpg", "d.jpg"]);

        var gesture = await tester.startGesture(grip(tester, tile("d.jpg")));
        await gesture.moveBy(const Offset(40, 0));
        await tester.pump();

        // B is carried along: no cursor over it.
        await gesture.moveTo(dropPoint(tester, tile("b.jpg"), left: false));
        await tester.pump();
        expect(insertCursor, findsNothing);

        // D is the tile picked up: no cursor over it either.
        await gesture.moveTo(dropPoint(tester, tile("d.jpg"), left: false));
        await tester.pump();
        expect(insertCursor, findsNothing);

        // A stays where it is, so it takes a drop.
        await gesture.moveTo(dropPoint(tester, tile("a.jpg"), left: false));
        await tester.pump();
        expect(
          find.descendant(of: tile("a.jpg"), matching: insertCursor),
          findsOneWidget,
        );

        await gesture.up();
        await tester.pumpAndSettle();
        expect(partNames(album(tester)),
            ["a.jpg", "b.jpg", "d.jpg", "c.jpg", "e.jpg"]);
      });
    });

    testWidgets('a cancelled drag leaves everything as it was',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester);
        await selectOnly(tester, ["b.jpg", "d.jpg"]);

        var before = partNames(album(tester));
        var state = albumState(tester);

        await dragOnto(
          tester,
          tile("d.jpg"),
          null,
          onCursor: () async {
            // The carried tiles are dimmed while they are on their way.
            expect(state.isCarried(album(tester).parts[1]), isTrue);
            expect(state.isCarried(album(tester).parts[3]), isTrue);
            expect(state.isCarried(album(tester).parts[0]), isFalse);
          },
        );

        expect(partNames(album(tester)), before);
        expect(selectionOf(tester), {"b.jpg", "d.jpg"});
        expect(insertCursor, findsNothing);
        for (var part in album(tester).parts) {
          expect(albumState(tester).isCarried(part), isFalse,
              reason: "the cancelled drag carries nothing any more");
        }
      });
    });
  });
}
