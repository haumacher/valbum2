/// "Group by" of a search, issue #244: the headings of a search result by
/// key, as a pure function — exact outputs for the rules of
/// `search_grouping.dart`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_model.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_grouping.dart';

final PlaceTag germany =
    PlaceTag(geonameId: 2921044, name: "Germany", kind: PlaceKind.country);
final PlaceTag austria =
    PlaceTag(geonameId: 2782113, name: "Österreich", kind: PlaceKind.country);
final PlaceTag italy =
    PlaceTag(geonameId: 3175395, name: "Italy", kind: PlaceKind.country);
final PlaceTag bavaria =
    PlaceTag(geonameId: 2951839, name: "Bavaria", kind: PlaceKind.adm1);
final PlaceTag munich =
    PlaceTag(geonameId: 2867714, name: "Munich", kind: PlaceKind.place);
final PlaceTag berlin =
    PlaceTag(geonameId: 2950159, name: "Berlin", kind: PlaceKind.place);
final PlaceTag vienna =
    PlaceTag(geonameId: 2761369, name: "Vienna", kind: PlaceKind.place);
final PlaceTag rome =
    PlaceTag(geonameId: 3169070, name: "Rome", kind: PlaceKind.place);

/// A photo found below the folder searched, named by its path, taken at
/// noon UTC of the given day (`null`: no date).
ImagePart photo(
  String name, {
  DateTime? day,
  List<PlaceTag> places = const [],
  String camera = "",
}) =>
    ImagePart(
      name: name,
      date: day == null
          ? 0
          : DateTime.utc(day.year, day.month, day.day, 12)
              .millisecondsSinceEpoch,
      width: 2048,
      height: 1536,
      camera: camera,
      places: places.isEmpty ? null : PlaceInfo(tags: places),
    );

final texts = SearchGroupTexts(
  withoutDate: "Without a date",
  withoutPlace: "Without a place",
  withoutCamera: "Without a camera",
  monthText: DateFormat.yMMMM().format,
);

/// UTC: the days are the days the fixtures name.
DateTime utc(int millis) =>
    DateTime.fromMillisecondsSinceEpoch(millis, isUtc: true);

List<AlbumPart> grouped(List<AlbumPart> parts, List<SearchGroup> keys) =>
    groupSearchParts(parts, keys, texts: texts, localTime: utc);

String nameOf(AlbumPart part) => switch (part) {
      Heading(level: 2) => "## ${part.text}",
      Heading() => "# ${part.text}",
      ImageGroup() => "group ${part.images[part.representative].name}",
      ImagePart() => part.name,
      _ => "$part",
    };

List<String> names(List<AlbumPart> parts) => parts.map(nameOf).toList();

void main() {
  test('by album: one section per folder, by its path', () {
    var parts = [
      photo("2021 Zoo/a.jpg", day: DateTime(2019)),
      photo("2020 Trip/b.jpg", day: DateTime(2020)),
      photo("2021 Zoo/c.jpg", day: DateTime(2021)),
      photo("2020 Trip/Day 2/d.jpg", day: DateTime(2022)),
    ];
    expect(names(grouped(parts, [SearchGroup.album])), [
      "# 2020 Trip",
      "2020 Trip/b.jpg",
      "# 2020 Trip/Day 2",
      "2020 Trip/Day 2/d.jpg",
      "# 2021 Zoo",
      "2021 Zoo/a.jpg",
      "2021 Zoo/c.jpg",
    ]);
  });

  test('by album: the folder the server named, where it named one', () {
    var parts = [
      photo("x.jpg")..ref = PhotoRef(path: "Family/Garden/x.jpg"),
      photo("y.jpg"),
    ];
    expect(names(grouped(parts, [SearchGroup.album])), [
      "# /",
      "y.jpg",
      "# Family/Garden",
      "x.jpg",
    ]);
  });

  test('by year: chronological, the photos without a date last', () {
    var parts = [
      photo("a.jpg", day: DateTime(2021, 7, 1)),
      photo("b.jpg"),
      photo("c.jpg", day: DateTime(2019, 5, 1)),
      photo("d.jpg", day: DateTime(2021, 12, 31)),
    ];
    expect(names(grouped(parts, [SearchGroup.year])), [
      "# 2019",
      "c.jpg",
      "# 2021",
      "a.jpg",
      "d.jpg",
      "# Without a date",
      "b.jpg",
    ]);
  });

  test('by year: the day of the device zone decides', () {
    // Late on New Year's Eve in UTC is the new year two hours east.
    var late = ImagePart(
      name: "late.jpg",
      date: DateTime.utc(2020, 12, 31, 23).millisecondsSinceEpoch,
    );
    DateTime east(int millis) => utc(millis).add(const Duration(hours: 2));
    expect(
      names(groupSearchParts([late], [SearchGroup.year],
          texts: texts, localTime: east)),
      ["# 2021", "late.jpg"],
    );
  });

  test('by month: chronological, the month written in the language', () {
    var parts = [
      photo("a.jpg", day: DateTime(2021, 7, 2)),
      photo("b.jpg", day: DateTime(2020, 12, 1)),
      photo("c.jpg", day: DateTime(2021, 7, 1)),
      photo("d.jpg"),
    ];
    expect(names(grouped(parts, [SearchGroup.month])), [
      "# December 2020",
      "b.jpg",
      "# July 2021",
      "a.jpg",
      "c.jpg",
      "# Without a date",
      "d.jpg",
    ]);
  });

  test('by country: alphabetical, accents aside, without a place last', () {
    var parts = [
      photo("a.jpg", places: [italy, rome]),
      photo("b.jpg", places: [austria, vienna]),
      photo("c.jpg"),
      photo("d.jpg", places: [germany, bavaria, munich]),
      photo("e.jpg", places: [italy]),
    ];
    expect(names(grouped(parts, [SearchGroup.country])), [
      "# Germany",
      "d.jpg",
      "# Italy",
      "a.jpg",
      "e.jpg",
      "# Österreich",
      "b.jpg",
      "# Without a place",
      "c.jpg",
    ]);
  });

  test('by region and by town: the place of that kind', () {
    var parts = [
      photo("a.jpg", places: [germany, bavaria, munich]),
      photo("b.jpg", places: [germany, berlin]),
      photo("c.jpg", places: [austria, vienna]),
      photo("d.jpg", places: [germany, bavaria, munich]),
    ];
    expect(names(grouped(parts, [SearchGroup.town])), [
      "# Berlin",
      "b.jpg",
      "# Munich",
      "a.jpg",
      "d.jpg",
      "# Vienna",
      "c.jpg",
    ]);
    expect(names(grouped(parts, [SearchGroup.region])), [
      "# Bavaria",
      "a.jpg",
      "d.jpg",
      "# Without a place",
      "b.jpg",
      "c.jpg",
    ]);
  });

  test('slim places, as a search answers them: the name is the place', () {
    // PhotoSearch.partialCopy (issue #243): name and kind alone, id 0.
    PlaceTag slim(String name, PlaceKind kind) =>
        PlaceTag(name: name, kind: kind);
    ImagePart found(String name, String country, String town) => ImagePart(
          name: name,
          date: DateTime.utc(2021, 7, 1, 12).millisecondsSinceEpoch,
          partial: true,
          places: PlaceInfo(tags: [
            slim(country, PlaceKind.country),
            slim(town, PlaceKind.place),
          ]),
        );
    var parts = [
      found("a.jpg", "Germany", "Karlsruhe"),
      found("b.jpg", "Germany", "Berlin"),
      found("c.jpg", "Germany", "Karlsruhe"),
      found("d.jpg", "Italy", "Rome"),
    ];
    expect(names(grouped(parts, [SearchGroup.country, SearchGroup.town])), [
      "# Germany",
      "## Berlin",
      "b.jpg",
      "## Karlsruhe",
      "a.jpg",
      "c.jpg",
      "# Italy",
      "## Rome",
      "d.jpg",
    ]);
  });

  test('two towns of one name with their ids stay apart', () {
    var springfieldA =
        PlaceTag(geonameId: 1, name: "Springfield", kind: PlaceKind.place);
    var springfieldB =
        PlaceTag(geonameId: 2, name: "Springfield", kind: PlaceKind.place);
    var parts = [
      photo("a.jpg", places: [springfieldB]),
      photo("b.jpg", places: [springfieldA]),
      photo("c.jpg", places: [springfieldB]),
    ];
    expect(names(grouped(parts, [SearchGroup.town])), [
      "# Springfield",
      "b.jpg",
      "# Springfield",
      "a.jpg",
      "c.jpg",
    ]);
  });

  test('by camera: alphabetical, without a camera last', () {
    var parts = [
      photo("a.jpg", camera: "Pixel 7"),
      photo("b.jpg"),
      photo("c.jpg", camera: "Canon EOS 5D"),
      photo("d.jpg", camera: "Pixel 7"),
    ];
    expect(names(grouped(parts, [SearchGroup.camera])), [
      "# Canon EOS 5D",
      "c.jpg",
      "# Pixel 7",
      "a.jpg",
      "d.jpg",
      "# Without a camera",
      "b.jpg",
    ]);
  });

  test('two keys: sections and, inside each, subsections', () {
    var parts = [
      photo("a.jpg", day: DateTime(2021, 7, 1), places: [italy, rome]),
      photo("b.jpg", day: DateTime(2020, 5, 1), places: [germany, berlin]),
      photo("c.jpg", day: DateTime(2021, 8, 1), places: [germany, munich]),
      photo("d.jpg", day: DateTime(2021, 9, 1)),
      photo("e.jpg", day: DateTime(2021, 9, 2), places: [italy]),
    ];
    var result = grouped(parts, [SearchGroup.year, SearchGroup.country]);
    expect(names(result), [
      "# 2020",
      "## Germany",
      "b.jpg",
      "# 2021",
      "## Germany",
      "c.jpg",
      "## Italy",
      "a.jpg",
      "e.jpg",
      "## Without a place",
      "d.jpg",
    ]);
    expect(
      [
        for (var part in result)
          if (part is Heading) part.level,
      ],
      [1, 2, 1, 2, 2, 2],
    );
  });

  test('a group is bucketed by its representative', () {
    var group = ImageGroup(
      images: [
        photo("g1.jpg", day: DateTime(2019)),
        photo("g2.jpg", day: DateTime(2022)),
      ],
      representative: 1,
    );
    var parts = <AlbumPart>[
      photo("a.jpg", day: DateTime(2019)),
      group,
    ];
    expect(names(grouped(parts, [SearchGroup.year])), [
      "# 2019",
      "a.jpg",
      "# 2022",
      "group g2.jpg",
    ]);
  });

  test('without a key, the parts as they are', () {
    var parts = [
      photo("b.jpg", day: DateTime(2021)),
      photo("a.jpg", day: DateTime(2020)),
    ];
    var result = grouped(parts, const []);
    expect(result, hasLength(2));
    expect(identical(result[0], parts[0]), isTrue);
    expect(identical(result[1], parts[1]), isTrue);
  });

  test('grouping again answers the same; a third key is not honoured', () {
    // The result by date, as the server answers it.
    var parts = [
      photo("b.jpg", day: DateTime(2020), camera: "Pixel 7"),
      photo("a.jpg", day: DateTime(2021)),
      photo("c.jpg", day: DateTime(2021), camera: "Canon"),
      photo("d.jpg"),
    ];
    var once = grouped(parts, [SearchGroup.camera]);
    expect(names(once), [
      "# Canon", "c.jpg", "# Pixel 7", "b.jpg", "# Without a camera", //
      "a.jpg", "d.jpg",
    ]);
    expect(names(grouped(once, [SearchGroup.camera])), names(once));
    expect(names(grouped(once, [SearchGroup.year])),
        names(grouped(parts, [SearchGroup.year])),
        reason: "Another key groups the result, not the grouping before.");
    expect(names(grouped(once, const [])),
        ["b.jpg", "a.jpg", "c.jpg", "d.jpg"],
        reason: "No key: the result by date again.");
    expect(
      names(grouped(
          parts, [SearchGroup.year, SearchGroup.camera, SearchGroup.album])),
      names(grouped(parts, [SearchGroup.year, SearchGroup.camera])),
    );
  });

  test(
      'a saved search is grouped when it is initialized, and the viewer '
      'pages in the order of the sections', () {
    var a = photo("a.jpg", day: DateTime(2021));
    var b = photo("b.jpg", day: DateTime(2020));
    var c = photo("c.jpg", day: DateTime(2021));
    var album = AlbumInfo(
      kind: AlbumKind.search,
      query: SearchQuery(version: 1, groupBy: [SearchGroup.year]),
      // The server's order; 2021 holds a and c, and comes after b's 2020.
      parts: [a, b, c],
    );
    AlbumInitializer(searchTexts: texts).init(album);
    expect(names(album.parts), ["# 2020", "b.jpg", "# 2021", "a.jpg", "c.jpg"]);
    expect(b.next, same(a));
    expect(a.next, same(c));
    expect(a.previous, same(b));

    // Initialized again (after an edit): the same headings, once.
    AlbumInitializer(searchTexts: texts).init(album);
    expect(names(album.parts), ["# 2020", "b.jpg", "# 2021", "a.jpg", "c.jpg"]);
  });

  test('an album of another kind is never grouped', () {
    var album = AlbumInfo(
      kind: AlbumKind.album,
      query: SearchQuery(version: 1, groupBy: [SearchGroup.year]),
      parts: [photo("a.jpg", day: DateTime(2021)), Heading(text: "Mine")],
    );
    AlbumInitializer(searchTexts: texts).init(album);
    expect(names(album.parts), ["a.jpg", "# Mine"]);
  });
}
