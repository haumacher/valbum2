import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

// Issue #71: a heading inserted "before this tile" is anchored on what is
// *displayed* after the tile, not on the tile's stored index. The row layout
// buffers landscape images to pair one with a portrait image into a double
// row, so within a block of rows the displayed order is a permutation of the
// stored order — and a heading splits the stored sequence, which puts
// everything stored before it in front of it.

ImagePart img(String name, int width, int height) =>
    ImagePart(name: name, width: width, height: height);

/// A landscape image of the given name.
ImagePart landscape(String name) => img(name, 2048, 1536);

/// A portrait image of the given name.
ImagePart portrait(String name) => img(name, 1536, 2048);

/// A short description of the stored order of the album's parts.
List<String> partNames(AlbumInfo album) => [
      for (var part in album.parts) partName(part),
    ];

String partName(AlbumPart part) =>
    part is Heading ? "Heading(${part.text})" : (part as ImagePart).name;

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The row of the given heading.
Finder headingTile(Heading heading) => find.byKey(ValueKey(heading));

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The names of the parts in the order their tiles are shown.
List<String> displayNames(WidgetTester tester) =>
    [for (var part in albumState(tester).displayOrder) partName(part)];

/// The tool with the given icon on the tile of [name].
Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Loads the given album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, String albumJson) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson)));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

void main() {
  group('the anchor of an inserted heading', () {
    test('is the stored index where the display is the stored order', () {
      var a = landscape("A");
      var b = portrait("B");
      var c = landscape("C");
      var info = AlbumInfo(parts: [a, b, c]);
      AlbumInitializer().init(info);

      // Nothing was permuted, so "before B" is B's stored index, as before.
      expect(insertHeadingBeforeDisplayed(info, b, [a, b, c], "H"), 1);
      expect(partNames(info), ["A", "Heading(H)", "B", "C"]);
    });

    test('is the smallest stored index displayed at or after the tile', () {
      // Stored [L1, L2, P], displayed [P, L1, L2]: the portrait image is
      // pulled in front of the landscape images buffered before it.
      var l1 = landscape("L1");
      var l2 = landscape("L2");
      var p = portrait("P");
      var display = <AlbumPart>[p, l1, l2];

      // Before the displayed first tile P: L1 and L2 are displayed after it,
      // and L1 is stored first — the heading goes in front of all three.
      var info = AlbumInfo(parts: [l1, l2, p]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, p, display, "H"), 0);
      expect(partNames(info), ["Heading(H)", "L1", "L2", "P"]);

      // Before L1: only L2 is displayed after it, and L1 itself is stored
      // first — the heading goes to index 0 as well, but for the plain
      // reason that this is L1's own stored index.
      info = AlbumInfo(parts: [l1, l2, p]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, l1, display, "H"), 0);
      expect(partNames(info), ["Heading(H)", "L1", "L2", "P"]);

      // Before L2, the displayed last tile: nothing is displayed after it, so
      // the anchor is its stored index 1.
      info = AlbumInfo(parts: [l1, l2, p]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, l2, display, "H"), 1);
      expect(partNames(info), ["L1", "Heading(H)", "L2", "P"]);
    });

    test('lands before a part the layout moved behind the tile', () {
      // Stored [A, P, X, B], displayed [A, X, P, B]: P is stored before X but
      // displayed behind it.
      var a = landscape("A");
      var p = portrait("P");
      var x = landscape("X");
      var b = landscape("B");
      List<AlbumPart> display() => [a, x, p, b];

      // Before X: P and B are displayed after it, P is stored at 1 — the
      // heading lands before the moved P, which is the author's rule.
      var info = AlbumInfo(parts: [a, p, x, b]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, x, display(), "H"), 1);
      expect(partNames(info), ["A", "Heading(H)", "P", "X", "B"]);

      // Before B, the displayed last tile: nothing follows it, its stored
      // index 3 is the anchor.
      info = AlbumInfo(parts: [a, p, x, b]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, b, display(), "H"), 3);
      expect(partNames(info), ["A", "P", "X", "Heading(H)", "B"]);

      // Before A, the displayed first tile: everything follows it, and A is
      // stored first anyway.
      info = AlbumInfo(parts: [a, p, x, b]);
      AlbumInitializer().init(info);
      expect(insertHeadingBeforeDisplayed(info, a, display(), "H"), 0);
      expect(partNames(info), ["Heading(H)", "A", "P", "X", "B"]);
    });

    test('is the stored index if the display order lacks the tile', () {
      var a = landscape("A");
      var p = portrait("P");
      var x = landscape("X");
      var info = AlbumInfo(parts: [a, p, x]);
      AlbumInitializer().init(info);

      // The rating filter may hide the very tile — or the album was never
      // displayed at all: the stored index is all there is to go by.
      expect(insertHeadingBeforeDisplayed(info, x, [a, p], "H"), 2);
      expect(partNames(info), ["A", "P", "Heading(H)", "X"]);
    });

    test('is the stored index if the display order holds a foreign part', () {
      var a = landscape("A");
      var p = portrait("P");
      var x = landscape("X");
      var stranger = landscape("S");
      var info = AlbumInfo(parts: [a, p, x]);
      AlbumInitializer().init(info);

      // Displayed after X is a part the album does not hold: rather than
      // anchoring on a guess, the plain stored index is used.
      expect(
          insertHeadingBeforeDisplayed(info, x, [a, x, stranger, p], "H"), 2);
      expect(partNames(info), ["A", "P", "Heading(H)", "X"]);
    });

    test('refuses a part that is not in the album', () {
      var info = AlbumInfo(parts: [landscape("A")]);
      AlbumInitializer().init(info);
      var stranger = landscape("S");

      expect(insertHeadingBeforeDisplayed(info, stranger, [stranger], "H"), -1);
      expect(partNames(info), ["A"]);
    });

    test('stacks two insertions before the same tile in their order', () {
      // Stored [L1, L2, P], displayed [P, L1, L2].
      var l1 = landscape("L1");
      var l2 = landscape("L2");
      var p = portrait("P");
      var info = AlbumInfo(parts: [l1, l2, p]);
      AlbumInitializer().init(info);

      expect(insertHeadingBeforeDisplayed(info, p, [p, l1, l2], "First"), 0);
      expect(partNames(info), ["Heading(First)", "L1", "L2", "P"]);

      // The view rebuilds and displays the heading before the block of
      // images, which is laid out as before: [First, P, L1, L2].
      var first = info.parts.first as Heading;
      // The second heading is inserted before the same tile P. The first
      // heading is displayed *before* P, so it is not among the parts
      // displayed after the cursor: the second heading lands between it and
      // the images it introduces, not in front of it.
      expect(
        insertHeadingBeforeDisplayed(info, p, [first, p, l1, l2], "Second"),
        1,
      );
      expect(
        partNames(info),
        ["Heading(First)", "Heading(Second)", "L1", "L2", "P"],
      );
    });
  });

  group('the heading tool of a tile', () {
    // The album of the report: the layout pulls the portrait image in front
    // of the landscape image stored before it, so the first tile shown is
    // not the first part stored.
    String albumJson() => AlbumInfo(
          path: "",
          title: "Permuted",
          parts: [
            landscape("L1.jpg"),
            portrait("P.jpg"),
            landscape("L2.jpg"),
          ],
        ).toString();

    testWidgets('puts the heading before everything shown after the tile',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, albumJson());

        // The layout permutes: P is stored second but displayed first.
        expect(partNames(album(tester)), ["L1.jpg", "P.jpg", "L2.jpg"]);
        expect(displayNames(tester), ["P.jpg", "L1.jpg", "L2.jpg"]);
        var shownAfterCursor = ["L1.jpg", "L2.jpg"];

        // Insert a heading before the first tile shown.
        await tester.tap(tool("P.jpg", Icons.title));
        await tester.pumpAndSettle();
        expect(find.text(testL10n.insertHeading), findsOneWidget);
        await tester.enterText(find.byType(TextField), "Am Morgen");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();

        // Stored first, before the landscape image that used to jump in
        // front of it (issue #71).
        var parts = album(tester).parts;
        expect(parts.first, isA<Heading>());
        expect(partNames(album(tester)),
            ["Heading(Am Morgen)", "L1.jpg", "P.jpg", "L2.jpg"]);

        // Displayed first, and every tile that was shown after the cursor is
        // shown after it.
        expect(displayNames(tester),
            ["Heading(Am Morgen)", "P.jpg", "L1.jpg", "L2.jpg"]);
        var heading = parts.first as Heading;
        var headingBox = tester.getRect(headingTile(heading));
        for (var name in ["P.jpg", ...shownAfterCursor]) {
          expect(tester.getRect(tile(name)).top,
              greaterThanOrEqualTo(headingBox.bottom),
              reason: "$name is shown after the heading");
        }
      });
    });

    testWidgets('leaves the plain case at the stored index', (tester) async {
      var plain = AlbumInfo(
        path: "",
        title: "Plain",
        parts: [
          landscape("L1.jpg"),
          landscape("L2.jpg"),
          landscape("L3.jpg"),
        ],
      ).toString();

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, plain);
        expect(displayNames(tester), ["L1.jpg", "L2.jpg", "L3.jpg"]);

        // The heading tool acts on the selected tile.
        await tapTile(tester, "L2.jpg");
        await tester.tap(tool("L2.jpg", Icons.title));
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField), "Am Mittag");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();

        expect(partNames(album(tester)),
            ["L1.jpg", "Heading(Am Mittag)", "L2.jpg", "L3.jpg"]);
      });
    });
  });
}
