import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Issue #76: "Sort by date" orders the parts of an album that grew by
// uploads from several devices. The headings partition the album into
// sections, each section is sorted on its own, and nothing is written back
// unless something changed.

/// An image of the given name taken at the given date.
ImagePart image(String name, int date) =>
    ImagePart(name: name, date: date, width: 2048, height: 1536);

/// A short description of the stored order of the album's parts.
List<String> partNames(AlbumInfo album) =>
    [for (var part in album.parts) partName(part)];

String partName(AlbumPart part) {
  if (part is Heading) return "Heading(${part.text})";
  if (part is ImagePart) return part.name;
  var group = part as ImageGroup;
  return "Group(${group.images.map((i) => i.name).join(",")})";
}

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The names of the parts in the order their tiles are shown.
List<String> displayNames(WidgetTester tester) =>
    [for (var part in albumState(tester).displayOrder) partName(part)];

/// Loads the given album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// Opens the album menu and chooses "Sort by date".
Future<void> sortByDate(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert));
  await tester.pumpAndSettle();
  await tester.tap(find.text("Sort by date"));
  await tester.pumpAndSettle();
}

void main() {
  group('sorting the album by date', () {
    test('sorts each section on its own, the headings staying put', () {
      var info = AlbumInfo(parts: [
        image("c.jpg", 300),
        image("a.jpg", 100),
        Heading(text: "Am Mittag"),
        image("f.jpg", 600),
        image("d.jpg", 400),
        image("e.jpg", 500),
      ]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      expect(partNames(info), [
        "a.jpg",
        "c.jpg",
        "Heading(Am Mittag)",
        "d.jpg",
        "e.jpg",
        "f.jpg",
      ]);

      // A late image of the first section stays in the first section: the
      // chapters an album was given are not undone by a sort.
      expect(info.parts.first, isA<ImagePart>());
      expect((info.parts[1] as ImagePart).date, 300);
    });

    test('sorts an album without headings as a whole', () {
      var info = AlbumInfo(parts: [
        image("b.jpg", 200),
        image("c.jpg", 300),
        image("a.jpg", 100),
      ]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      expect(partNames(info), ["a.jpg", "b.jpg", "c.jpg"]);

      // The transient links follow the new order.
      var first = info.parts.first as ImagePart;
      var last = info.parts.last as ImagePart;
      expect(first.previous, isNull);
      expect(first.next, info.parts[1]);
      expect(last.next, isNull);
      expect(last.home, first);
    });

    test('sorts a group by its earliest image and its images inside', () {
      var early = image("g-early.jpg", 150);
      var late = image("g-late.jpg", 900);
      // Stored with the later image first, and the later one represents the
      // group.
      var group = ImageGroup(images: [late, early], representative: 0);
      var info = AlbumInfo(parts: [
        image("a.jpg", 100),
        image("b.jpg", 200),
        group,
      ]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      // The group is sorted by 150, the earliest date of its images, so it
      // stands between a.jpg and b.jpg.
      expect(
          partNames(info), ["a.jpg", "Group(g-early.jpg,g-late.jpg)", "b.jpg"]);
      // And it still shows the image it showed: the representative index is
      // carried along.
      expect(group.images[group.representative], late);
    });

    test('sorts a group by its earliest dated image, undated ones aside', () {
      var undated = image("g-undated.jpg", 0);
      var dated = image("g-dated.jpg", 500);
      var group = ImageGroup(images: [undated, dated], representative: 0);
      var info = AlbumInfo(parts: [image("a.jpg", 900), group]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      // 500, not 0: a group without any date would be pushed to the end by
      // one image the server could not read a date from.
      expect(
        partNames(info),
        ["Group(g-dated.jpg,g-undated.jpg)", "a.jpg"],
      );
      expect(group.images[group.representative], undated);
    });

    test('keeps undated parts at the end of their section, in order', () {
      var info = AlbumInfo(parts: [
        image("n1.jpg", 0),
        image("b.jpg", 200),
        image("n2.jpg", 0),
        image("a.jpg", 100),
        Heading(text: "Am Abend"),
        image("n3.jpg", 0),
        image("c.jpg", 300),
      ]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      expect(partNames(info), [
        "a.jpg",
        "b.jpg",
        "n1.jpg",
        "n2.jpg",
        "Heading(Am Abend)",
        "c.jpg",
        "n3.jpg",
      ]);
    });

    test('keeps the order of parts taken at the same moment', () {
      var info = AlbumInfo(parts: [
        image("second.jpg", 100),
        image("third.jpg", 100),
        image("first.jpg", 50),
        image("fourth.jpg", 100),
      ]);
      AlbumInitializer().init(info);

      expect(sortSectionsByDate(info), isTrue);
      expect(partNames(info),
          ["first.jpg", "second.jpg", "third.jpg", "fourth.jpg"]);
    });

    test('reports no change for an album that is already in order', () {
      var group = ImageGroup(
        images: [image("g1.jpg", 300), image("g2.jpg", 400)],
        representative: 1,
      );
      var info = AlbumInfo(parts: [
        image("a.jpg", 100),
        Heading(text: "Am Mittag"),
        image("b.jpg", 200),
        group,
        image("n.jpg", 0),
      ]);
      AlbumInitializer().init(info);
      var before = info.parts.toList();

      expect(sortSectionsByDate(info), isFalse);
      expect(info.parts, before);
      expect(group.representative, 1);
    });

    test('sorts an empty album and an album of headings alone', () {
      var empty = AlbumInfo(parts: []);
      AlbumInitializer().init(empty);
      expect(sortSectionsByDate(empty), isFalse);

      var headings = AlbumInfo(
        parts: [Heading(text: "A"), Heading(text: "B")],
      );
      AlbumInitializer().init(headings);
      expect(sortSectionsByDate(headings), isFalse);
      expect(partNames(headings), ["Heading(A)", "Heading(B)"]);
    });

    test('says what a part is sorted by', () {
      expect(dateOf(image("a.jpg", 100)), 100);
      expect(dateOf(image("a.jpg", 0)), 0);
      expect(dateOf(Heading(text: "H")), 0);
      expect(
        dateOf(ImageGroup(images: [image("b.jpg", 900), image("a.jpg", 100)])),
        100,
      );
      expect(dateOf(ImageGroup(images: [])), 0);
    });
  });

  group('the "Sort by date" menu entry', () {
    String unsorted() => AlbumInfo(
          path: "",
          title: "Von mehreren Geräten",
          parts: [
            image("c.jpg", 300),
            image("a.jpg", 100),
            image("b.jpg", 200),
          ],
        ).toString();

    testWidgets('reorders the album and saves the new order', (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(unsorted(), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);
        expect(displayNames(tester), ["c.jpg", "a.jpg", "b.jpg"]);

        await sortByDate(tester);

        // The album on the screen is in date order.
        expect(partNames(album(tester)), ["a.jpg", "b.jpg", "c.jpg"]);
        expect(displayNames(tester), ["a.jpg", "b.jpg", "c.jpg"]);
        // Nothing is written yet: the new order is reviewed and then saved.
        expect(requests.where((r) => r.method == "PUT"), isEmpty);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect([for (var part in saved.parts) (part as ImagePart).name],
          ["a.jpg", "b.jpg", "c.jpg"]);
    });

    testWidgets('says so and writes nothing for a sorted album',
        (tester) async {
      var sorted = AlbumInfo(
        path: "",
        title: "Schon sortiert",
        parts: [
          image("a.jpg", 100),
          image("b.jpg", 200),
          image("c.jpg", 300),
        ],
      ).toString();
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(sorted, 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);
        await sortByDate(tester);

        expect(find.text("Already in order"), findsOneWidget);
        expect(partNames(album(tester)), ["a.jpg", "b.jpg", "c.jpg"]);

        // The album was not marked dirty, so a save writes nothing new —
        // and there is nothing to write in the first place.
        expect(requests.where((r) => r.method == "PUT"), isEmpty);
        expect(albumState(tester).dirty, isFalse);
      });
    });

    testWidgets('is offered in the edit mode only', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: clientReturning(unsorted())),
        );
        await tester.pumpAndSettle();

        // The album is only read: the entry is not offered, as the other
        // edit actions are not.
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        expect(find.text("Sort by date"), findsNothing);
        await tester.tapAt(const Offset(4, 4));
        await tester.pumpAndSettle();

        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        expect(find.text("Sort by date"), findsOneWidget);
      });
    });
  });
}
