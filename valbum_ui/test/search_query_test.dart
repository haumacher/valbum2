/// The criteria of the search view and the stored query they make (issue
/// #227): one AND across kinds, every person chosen required, the places and
/// the labels each an OR, the last day of a span included.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/routes.dart';
import 'package:valbum_ui/search_query.dart';

void main() {
  group('the stored query', () {
    test('every kind chosen is one condition of an AND', () {
      var query = SearchCriteria(
        persons: const ["anne", "philipp"],
        from: DateTime(2020, 1, 1),
        to: DateTime(2026, 12, 31),
        places: const [2953481, 2892794],
        labels: const ["Holiday"],
        minRating: 1,
        media: SearchMediaKind.photos,
        text: "  lake ",
        camera: "Pixel 7",
      ).toQuery();

      expect(query.version, searchQueryVersion);
      var root = query.root as SearchAnd;
      expect(root.criteria.map((c) => c.runtimeType).toList(), [
        SearchPerson,
        SearchPerson,
        SearchDate,
        SearchOr,
        SearchOr,
        SearchRating,
        SearchMedia,
        SearchText,
        SearchCamera,
      ]);
      var date = root.criteria[2] as SearchDate;
      expect(date.from, DateTime(2020, 1, 1).millisecondsSinceEpoch);
      expect(date.to, DateTime(2027, 1, 1).millisecondsSinceEpoch,
          reason: "The last day is included: the span ends where it ends.");
      expect((root.criteria[7] as SearchText).text, "lake");
      expect((root.criteria[6] as SearchMedia).video, isFalse);
    });

    test('nothing chosen is an empty AND, which finds everything', () {
      var criteria = const SearchCriteria();
      expect(criteria.isEmpty, isTrue);
      expect((criteria.toQuery().root as SearchAnd).criteria, isEmpty);
    });

    test('read back, the criteria are what was chosen', () {
      var chosen = SearchCriteria(
        persons: const ["anne"],
        to: DateTime(2024, 6, 30),
        places: const [2953481],
        labels: const ["Holiday", "Garden"],
        minRating: 0,
        media: SearchMediaKind.videos,
        camera: "Canon EOS 5D",
      );
      var stored = SearchQuery.read(JsonReader.fromString(_content(chosen.toQuery())));
      expect(SearchCriteria.fromQuery(stored), chosen);
    });

    test('a query this screen cannot show is not edited here', () {
      expect(SearchCriteria.fromQuery(null), isNull);
      expect(
          SearchCriteria.fromQuery(
              SearchQuery(version: searchQueryVersion + 1, root: SearchAnd())),
          isNull,
          reason: "A newer version.");
      expect(
          SearchCriteria.fromQuery(SearchQuery(
              version: 1,
              root: SearchAnd(criteria: [
                SearchNot(criterion: SearchPerson(person: "anne")),
              ]))),
          isNull,
          reason: "A NOT.");
      expect(
          SearchCriteria.fromQuery(SearchQuery(
              version: 1,
              root: SearchAnd(criteria: [
                SearchOr(criteria: [
                  SearchPerson(person: "anne"),
                  SearchLabel(label: "Holiday"),
                ]),
              ]))),
          isNull,
          reason: "An OR across kinds.");
      expect(
          SearchCriteria.fromQuery(SearchQuery(
              version: 1,
              root: SearchAnd(criteria: [SearchFolder(path: "Trips")]))),
          isNull,
          reason: "A folder.");
      expect(SearchCriteria.fromQuery(SearchQuery(version: 1)), isNull,
          reason: "An absent root is what a newer criterion reads as.");
    });
  });

  group('the title a saved search is offered under', () {
    test('names what was looked for', () {
      var criteria = SearchCriteria(
        persons: const ["a", "p"],
        from: DateTime(2020, 1, 1),
        to: DateTime(2026, 12, 31),
      );
      expect(
        criteria.title(
          fallback: "Search",
          personNames: const {"a": "Anne", "p": "Philipp"},
        ),
        "Anne & Philipp, 2020–2026",
      );
      expect(
        SearchCriteria(from: DateTime(2024, 6, 2)).title(fallback: "Search"),
        "2024-06-02–",
      );
      expect(const SearchCriteria().title(fallback: "Search"), "Search");
    });
  });

  group('the route of the search view', () {
    test('a folder\'s search view and a photo opened from it', () {
      expect(
          parseRoute(Uri.parse("/2021/.search/")), const SearchRoute(["2021"]));
      expect(parseRoute(Uri.parse("/.search/")), const SearchRoute([]));
      var image =
          const ImageRoute(["2021"], "Trip/IMG_1.jpg", fromSearch: true);
      expect(image.path, "/2021/.search/Trip%2FIMG_1.jpg");
      expect(parseRoute(Uri.parse(image.path)), image);
      expect(image.up, const SearchRoute(["2021"]));
      expect(
          const SearchRoute(["2021"]).up, const ListingOrAlbumRoute(["2021"]));
    });
  });
}

/// The query as the app sends it and the server stores it: the object alone.
String _content(SearchQuery query) {
  var body = StringBuffer();
  query.writeContent(jsonStringWriter(body));
  return body.toString();
}
