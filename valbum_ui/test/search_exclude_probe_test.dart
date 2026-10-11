/// Review probe of #242 composed with #244: exclusions, "any of these" and a
/// grouping travel together through the stored query and back, and the title
/// a view is proposed reads them in order.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';

void main() {
  test('exclusions, any-of and group-by round-trip together', () {
    var criteria = SearchCriteria(
      persons: const ["anne"],
      excludedPersons: const ["philipp"],
      places: const [3169070, 3176959],
      excludedLabels: const ["Work"],
      from: DateTime(2019, 1, 1),
      to: DateTime(2019, 12, 31),
      excluded: const {SearchKind.date},
      anyOf: true,
      groupBy: const [SearchGroup.year, SearchGroup.town],
    );
    var query = criteria.toQuery();
    expect(query.root, isA<SearchOr>());
    expect(query.groupBy, [SearchGroup.year, SearchGroup.town]);
    var nots = (query.root as SearchOr).criteria.whereType<SearchNot>().length;
    expect(nots, 3, reason: "a person, a label and the date are excluded");

    var again = SearchCriteria.fromQuery(query);
    expect(again, isNotNull);
    expect(again!.anyOf, isTrue);
    expect(again.excludedPersons, ["philipp"]);
    expect(again.excludedLabels, ["Work"]);
    expect(again.excludes(SearchKind.date), isTrue);
    expect(again.groupBy, [SearchGroup.year, SearchGroup.town]);
    expect(again.findsAs(criteria), isTrue);
    expect(again.toQuery().toString(), query.toString());
  });

  test('clearing an excluded kind drops its exclusion', () {
    var criteria = const SearchCriteria(
      camera: "Pixel 7",
      excluded: {SearchKind.camera},
    );
    expect(criteria.excludes(SearchKind.camera), isTrue);
    var cleared = criteria.copyWith(clearCamera: true);
    expect(cleared.excludes(SearchKind.camera), isFalse);
    expect(cleared.isEmpty, isTrue);
    expect(SearchCriteria.fromQuery(cleared.toQuery())!.excluded, isEmpty);
  });

  test('the title names the included first and the excluded after', () {
    var criteria = SearchCriteria(
      places: const [3169070, 3176959],
      from: DateTime(2019, 1, 1),
      to: DateTime(2019, 12, 31),
      excluded: const {SearchKind.date},
    );
    var title = criteria.title(
      fallback: "Search",
      personNames: const {},
      placeNames: const {3169070: "Rome", 3176959: "Florence"},
      not: "not",
      or: "or",
    );
    expect(title, startsWith("Rome or Florence"));
    expect(title, contains("not"));
    expect(title.indexOf("not"), greaterThan(title.indexOf("Florence")));
  });
}
