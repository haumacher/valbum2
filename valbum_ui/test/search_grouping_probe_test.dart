/// Review probe of the search grouping composed with the slim answer a
/// search gives (issue #243): places carrying name and kind alone, partial
/// photographs, a group among them, and a photo without a date under two
/// keys.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/resource.dart';

import 'search_grouping_test.dart' show grouped, nameOf, photo;

/// A place as the slim answer sends it: no id, no country code.
PlaceTag slim(String name, PlaceKind kind) => PlaceTag(name: name, kind: kind);

ImagePart found(String name, {DateTime? day, List<PlaceTag> places = const []}) =>
    photo(name, day: day, places: places)..partial = true;

void main() {
  test('trimmed places of two photos with the same town fall into one bucket',
      () {
    var parts = [
      found("2024/Zoo/a.jpg",
          day: DateTime(2024, 5, 1),
          places: [slim("Germany", PlaceKind.country), slim("Karlsruhe", PlaceKind.place)]),
      found("2023/Trip/b.jpg",
          day: DateTime(2023, 7, 1),
          places: [slim("Germany", PlaceKind.country), slim("Berlin", PlaceKind.place)]),
      found("2022/Home/c.jpg",
          day: DateTime(2022, 1, 1),
          places: [slim("Germany", PlaceKind.country), slim("Karlsruhe", PlaceKind.place)]),
      found("2021/Rome/d.jpg",
          day: DateTime(2021, 1, 1),
          places: [slim("Italy", PlaceKind.country), slim("Rome", PlaceKind.place)]),
    ];
    expect(grouped(parts, [SearchGroup.country, SearchGroup.town]).map(nameOf), [
      "# Germany",
      "## Berlin",
      "2023/Trip/b.jpg",
      "## Karlsruhe",
      "2024/Zoo/a.jpg",
      "2022/Home/c.jpg",
      "# Italy",
      "## Rome",
      "2021/Rome/d.jpg",
    ]);
    for (var part in grouped(parts, [SearchGroup.town])) {
      if (part is ImagePart) {
        expect(part.partial, isTrue, reason: "grouping never completes a part");
      }
    }
  });

  test('a photo without a date goes last under year, then its town below', () {
    var parts = [
      found("a.jpg", day: DateTime(2024, 5, 1), places: [slim("Karlsruhe", PlaceKind.place)]),
      found("b.jpg", places: [slim("Berlin", PlaceKind.place)]),
      found("c.jpg", day: DateTime(2024, 6, 1)),
      ImageGroup(representative: 1, images: [
        found("d1.jpg", day: DateTime(2020, 1, 1), places: [slim("Rome", PlaceKind.place)]),
        found("d2.jpg", day: DateTime(2020, 1, 2), places: [slim("Vienna", PlaceKind.place)]),
      ]),
    ];
    expect(grouped(parts, [SearchGroup.year, SearchGroup.town]).map(nameOf), [
      "# 2020",
      "## Vienna",
      "group d2.jpg",
      "# 2024",
      "## Karlsruhe",
      "a.jpg",
      "## Without a place",
      "c.jpg",
      "# Without a date",
      "## Berlin",
      "b.jpg",
    ]);
  });

  test('the headings a grouping made are not photos to the count or names', () {
    var parts = [
      found("a.jpg", day: DateTime(2024, 5, 1)),
      found("b.jpg", day: DateTime(2023, 5, 1)),
    ];
    var result = grouped(parts, [SearchGroup.year]);
    expect(result.whereType<Heading>().length, 2);
    expect(result.whereType<ImagePart>().map((p) => p.name), ["b.jpg", "a.jpg"]);
    // Grouping the grouped result by nothing gives the server's order back.
    expect(grouped(result, []).map(nameOf), ["b.jpg", "a.jpg"]);
  });
}
