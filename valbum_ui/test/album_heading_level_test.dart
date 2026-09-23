import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

// Issue #158: two heading levels in an album — a section and a subsection —
// each selecting the images below it in the edit mode, the way the inbox's
// derived month and day headings do (#136).

ImagePart image(String name) =>
    ImagePart(name: name, date: 1000, width: 2048, height: 1536);

Heading h1(String text) => Heading(text: text, level: 1);

Heading h2(String text) => Heading(text: text, level: 2);

String nameOf(AlbumPart part) => switch (part) {
      Heading heading => "#${heading.text}",
      ImagePart image => image.name,
      ImageGroup group => "Group(${group.images.first.name})",
      _ => "?",
    };

List<String> under(List<AlbumPart> parts, Heading heading) =>
    [for (var part in imagesUnder(parts, heading)) nameOf(part)];

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The album of the widget tests:
///
/// ```
/// # A        a.jpg
/// ## B       b.jpg c.jpg
/// ## C       d.jpg
/// # D        e.jpg
/// ```
String nestedAlbum() => AlbumInfo(
      path: "",
      title: "Nested",
      parts: [
        h1("A"),
        image("a.jpg"),
        h2("B"),
        image("b.jpg"),
        image("c.jpg"),
        h2("C"),
        image("d.jpg"),
        h1("D"),
        image("e.jpg"),
      ],
    ).toString();

Heading headingOf(WidgetTester tester, String text) => album(tester)
    .parts
    .whereType<Heading>()
    .firstWhere((heading) => heading.text == text);

/// The names of the selected images, in the album's order.
List<String> selected(WidgetTester tester) => [
      for (var part in album(tester).parts)
        if (albumState(tester).selection.contains(part)) nameOf(part),
    ];

/// Whether the check box of the given heading is lit.
bool lit(WidgetTester tester, Heading heading) {
  var box = find.descendant(
    of: find.byKey(ValueKey(heading)),
    matching: find.byIcon(Icons.check_box),
  );
  var blank = find.descendant(
    of: find.byKey(ValueKey(heading)),
    matching: find.byIcon(Icons.check_box_outline_blank),
  );
  expect(box.evaluate().length + blank.evaluate().length, 1);
  return box.evaluate().isNotEmpty;
}

Future<void> tapHeading(WidgetTester tester, String text) async {
  await tester.tap(find.text(text));
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

void main() {
  group('imagesUnder', () {
    test('a section reaches to the next section, its subsections included', () {
      var a = h1("A"), b = h2("B"), c = h2("C"), d = h1("D");
      var parts = <AlbumPart>[
        image("0.jpg"),
        a,
        image("a.jpg"),
        b,
        image("b.jpg"),
        c,
        image("c.jpg"),
        d,
        image("d.jpg"),
      ];
      expect(under(parts, a), ["a.jpg", "b.jpg", "c.jpg"]);
      expect(under(parts, b), ["b.jpg"]);
      expect(under(parts, c), ["c.jpg"]);
      expect(under(parts, d), ["d.jpg"]);
    });

    test('a subsection ends at a section right after it', () {
      var b = h2("B"), a = h1("A");
      var parts = <AlbumPart>[b, image("b.jpg"), a, image("a.jpg")];
      expect(under(parts, b), ["b.jpg"]);
      expect(under(parts, a), ["a.jpg"]);
    });

    test('a heading at the end has nothing under it', () {
      var end = h1("End");
      expect(under([image("a.jpg"), end], end), isEmpty);
      var sub = h2("Sub");
      expect(under([h1("A"), image("a.jpg"), sub], sub), isEmpty);
    });

    test('a group counts as one image', () {
      var a = h1("A");
      var group = ImageGroup(images: [image("g1.jpg"), image("g2.jpg")]);
      expect(under([a, group, image("x.jpg")], a), ["Group(g1.jpg)", "x.jpg"]);
    });

    test('a heading without a level is a section', () {
      var old = Heading(text: "Old");
      expect(old.level, 0);
      expect(headingLevel(old), headingSection);
      expect(headingLevel(Heading(level: 7)), headingSection);
      expect(headingLevel(h2("Sub")), headingSubsection);
      var parts = <AlbumPart>[
        old,
        image("a.jpg"),
        h1("New"),
        image("b.jpg"),
      ];
      expect(under(parts, old), ["a.jpg"]);
    });

    test('a heading that is not among the parts has nothing under it', () {
      expect(under([image("a.jpg")], h1("Stray")), isEmpty);
    });
  });

  testWidgets('draws a section and a subsection in two sizes, left-aligned',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester
          .pumpWidget(VAlbumApp(client: clientReturning(nestedAlbum())));
      await tester.pumpAndSettle();

      double size(String text) =>
          tester.widget<Text>(find.text(text)).style!.fontSize!;
      expect(size("A"), 22);
      expect(size("B"), 17);

      var width = tester.getSize(find.byType(AlbumContent)).width;
      // Left-aligned in the view mode: the text starts at the gutter.
      expect(tester.getTopLeft(find.text("A")).dx, 16);
      expect(tester.getTopLeft(find.text("B")).dx, 16);
      expect(tester.getCenter(find.text("A")).dx, lessThan(width / 4));
      // No check box outside the edit mode.
      expect(find.byIcon(Icons.check_box_outline_blank), findsNothing);

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      // In the edit mode the check box stands at the gutter, the text after it.
      var box = find.descendant(
        of: find.byKey(ValueKey(headingOf(tester, "A"))),
        matching: find.byType(Icon),
      );
      expect(tester.getTopLeft(box.first).dx, 16);
      expect(tester.getTopLeft(find.text("A")).dx, greaterThan(16));
      expect(size("A"), 22);
      expect(size("B"), 17);
    });
  });

  testWidgets('a heading selects what stands under it, a second tap deselects',
      (tester) async {
    // Tall enough for every heading of the album to be built at once.
    tester.view.physicalSize = const Size(1000, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await withFakeImageHttp(() async {
      await tester
          .pumpWidget(VAlbumApp(client: clientReturning(nestedAlbum())));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(selected(tester), ["a.jpg"]);
      var a = headingOf(tester, "A");
      var b = headingOf(tester, "B");
      var d = headingOf(tester, "D");
      expect(lit(tester, a), isFalse);

      // A section: its own image and those of its subsections, adding to
      // what was selected.
      await tapHeading(tester, "A");
      expect(selected(tester), ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]);
      expect(lit(tester, a), isTrue);
      expect(lit(tester, b), isTrue);
      expect(lit(tester, d), isFalse);

      // The second tap takes exactly those back out.
      await tapHeading(tester, "A");
      expect(selected(tester), isEmpty);
      expect(lit(tester, a), isFalse);

      // A subsection: up to the next subsection.
      await tapHeading(tester, "B");
      expect(selected(tester), ["b.jpg", "c.jpg"]);
      expect(lit(tester, b), isTrue);
      expect(lit(tester, a), isFalse);

      // A subsection's images stay selected when the next one is added.
      await tapHeading(tester, "C");
      expect(selected(tester), ["b.jpg", "c.jpg", "d.jpg"]);

      // The last section, up to the end of the album.
      await tapHeading(tester, "D");
      expect(selected(tester), ["b.jpg", "c.jpg", "d.jpg", "e.jpg"]);

      // A partly selected section is completed first, not emptied.
      await tapHeading(tester, "A");
      expect(selected(tester), ["a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"]);
      await tapHeading(tester, "B");
      expect(selected(tester), ["a.jpg", "d.jpg", "e.jpg"]);

      // The check box selects as the text does.
      await tester.tap(find.descendant(
        of: find.byKey(ValueKey(b)),
        matching: find.byIcon(Icons.check_box_outline_blank),
      ));
      await tester.pumpAndSettle();
      expect(selected(tester), ["a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"]);

      // Selecting is no edit: nothing to save.
      expect(albumState(tester).dirty, isFalse);
    });
  });

  testWidgets(
      '"Add heading…" lays out a section in an empty album and saves it',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("", 200)
          : http.Response(AlbumInfo(path: "", title: "Empty").toString(), 200),
      requests: requests,
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("add-heading")));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), "Tag 2");
      await tester.tap(find.byKey(const Key("heading-level-2")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      expect(albumState(tester).editMode, isTrue);
      expect(find.text("Tag 2"), findsOneWidget);
      expect(tester.widget<Text>(find.text("Tag 2")).style!.fontSize, 17);
      expect(requests.where((r) => r.method == "PUT"), isEmpty);

      // A second one, a section, goes behind it.
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("add-heading")));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), "Mai");
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
    });

    var put = requests.where((r) => r.method == "PUT").single;
    var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    var headings = saved.parts.cast<Heading>();
    expect([for (var h in headings) (h.text, h.level)],
        [("Tag 2", 2), ("Mai", 1)]);
  });

  testWidgets('"Add heading…" needs the edit mode where a tile opens it',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester
          .pumpWidget(VAlbumApp(client: clientReturning(nestedAlbum())));
      await tester.pumpAndSettle();
      await openMenu(tester);
      expect(find.byKey(const Key("add-heading")), findsNothing);
      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await openMenu(tester);
      expect(find.byKey(const Key("add-heading")), findsOneWidget);
    });
  });

  testWidgets('a cancelled "Add heading…" leaves the album as it was',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
          client: clientReturning(AlbumInfo(path: "", title: "E").toString())));
      await tester.pumpAndSettle();
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("add-heading")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();

      expect(albumState(tester).editMode, isFalse);
      expect(album(tester).parts, isEmpty);
    });
  });

  testWidgets('the heading dialog changes the level of a heading',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
          client: clientReturning(AlbumInfo(
        path: "",
        title: "Old",
        parts: [Heading(text: "Before"), image("a.jpg")],
      ).toString())));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      var heading = headingOf(tester, "Before");

      await tester.tap(find.descendant(
        of: find.byKey(ValueKey(heading)),
        matching: find.byIcon(Icons.edit),
      ));
      await tester.pumpAndSettle();
      // A heading of an older sidecar is offered as the section it is.
      expect(
          tester
              .widget<ChoiceChip>(find.byKey(const Key("heading-level-1")))
              .selected,
          isTrue);
      expect(
          tester
              .widget<ChoiceChip>(find.byKey(const Key("heading-level-2")))
              .selected,
          isFalse);

      // Applied unchanged, it keeps the level it was read with.
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
      expect(heading.level, 0);

      await tester.tap(find.descendant(
        of: find.byKey(ValueKey(heading)),
        matching: find.byIcon(Icons.edit),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("heading-level-2")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      expect(heading.level, 2);
      expect(tester.widget<Text>(find.text("Before")).style!.fontSize, 17);
      expect(albumState(tester).dirty, isTrue);
    });
  });

  testWidgets('"Insert heading" on a tile offers the level', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
          client: clientReturning(AlbumInfo(
        path: "",
        title: "Tiles",
        parts: [image("a.jpg"), image("b.jpg")],
      ).toString())));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip(testL10n.insertHeading).last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), "Nachmittag");
      await tester.tap(find.byKey(const Key("heading-level-2")));
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      var heading = album(tester).parts.whereType<Heading>().single;
      expect(heading.text, "Nachmittag");
      expect(heading.level, 2);
    });
  });
}
