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

    test('the grouping travels in the query, and is no criterion (#244)', () {
      var chosen = const SearchCriteria(
        persons: ["anne"],
        groupBy: [SearchGroup.year, SearchGroup.country],
      );
      var query = chosen.toQuery();
      expect(query.groupBy, [SearchGroup.year, SearchGroup.country]);
      expect((query.root as SearchAnd).criteria, hasLength(1));
      var stored = SearchQuery.read(JsonReader.fromString(_content(query)));
      expect(SearchCriteria.fromQuery(stored), chosen);
      expect(chosen, isNot(chosen.copyWith(groupBy: const [])));
      expect(chosen.findsAs(chosen.copyWith(groupBy: const [])), isTrue);
      expect(chosen.findsAs(chosen.copyWith(camera: "Pixel 7")), isFalse);
      expect(const SearchCriteria(groupBy: [SearchGroup.year]).isEmpty, isTrue,
          reason: "A grouping alone searches for nothing.");
      expect(
          SearchCriteria.fromQuery(
              SearchQuery(version: 1, root: SearchAnd()))!.groupBy,
          isEmpty,
          reason: "A query without a grouping is the result by date.");
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
                SearchNot(
                    criterion: SearchNot(criterion: SearchPerson(person: "a"))),
              ]))),
          isNull,
          reason: "A NOT of a NOT.");
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

  group('exclusion and "any of these" (#242)', () {
    SearchCriteria? readBack(SearchCriteria chosen) => SearchCriteria.fromQuery(
        SearchQuery.read(JsonReader.fromString(_content(chosen.toQuery()))));

    test('"Anne without Philipp" is a person and a NOT of a person', () {
      var chosen = const SearchCriteria(
          persons: ["anne"], excludedPersons: ["philipp"]);
      var root = chosen.toQuery().root as SearchAnd;
      expect(root.criteria, hasLength(2));
      expect((root.criteria[0] as SearchPerson).person, "anne");
      var not = root.criteria[1] as SearchNot;
      expect((not.criterion as SearchPerson).person, "philipp");
      expect(readBack(chosen), chosen);
    });

    test('"Rome or Florence, but not 2019" is an OR of places and a NOT of '
        'the year', () {
      var chosen = SearchCriteria(
        places: const [3169070, 3176959],
        from: DateTime(2019, 1, 1),
        to: DateTime(2019, 12, 31),
        excluded: const {SearchKind.date},
      );
      var root = chosen.toQuery().root as SearchAnd;
      expect(root.criteria.map((c) => c.runtimeType).toList(),
          [SearchNot, SearchOr]);
      var date = (root.criteria[0] as SearchNot).criterion as SearchDate;
      expect(date.from, DateTime(2019, 1, 1).millisecondsSinceEpoch);
      expect(date.to, DateTime(2020, 1, 1).millisecondsSinceEpoch);
      expect(
          (root.criteria[1] as SearchOr)
              .criteria
              .map((c) => (c as SearchPlace).geonameId),
          [3169070, 3176959]);
      expect(readBack(chosen), chosen);
    });

    test('an excluded person among included ones is a row of its own', () {
      var chosen = const SearchCriteria(
          persons: ["anne", "clara"], excludedPersons: ["philipp"]);
      expect(readBack(chosen), chosen);
      // Stored in another order, the rows are the same.
      var interleaved = SearchQuery(
          version: 1,
          root: SearchAnd(criteria: [
            SearchPerson(person: "anne"),
            SearchNot(criterion: SearchPerson(person: "philipp")),
            SearchPerson(person: "clara"),
          ]));
      expect(SearchCriteria.fromQuery(interleaved), chosen);
    });

    test('every row can be excluded, and reads back so', () {
      var chosen = SearchCriteria(
        persons: const ["anne"],
        excludedPlaces: const [3169070],
        labels: const ["Holiday"],
        excludedLabels: const ["Work", "School"],
        minRating: 1,
        text: "lake",
        camera: "Pixel 7",
        to: DateTime(2020, 6, 30),
        excluded: const {
          SearchKind.rating,
          SearchKind.text,
          SearchKind.camera,
        },
      );
      var root = chosen.toQuery().root as SearchAnd;
      expect(root.criteria.whereType<SearchNot>(), hasLength(6));
      expect(readBack(chosen), chosen);
    });

    test('"any of these" is an OR over the rows, persons each a row', () {
      var chosen = const SearchCriteria(
        persons: ["anne", "philipp"],
        places: [3169070],
        camera: "Pixel 7",
        excludedLabels: ["Work"],
        anyOf: true,
      );
      var root = chosen.toQuery().root as SearchOr;
      expect(root.criteria.map((c) => c.runtimeType).toList(), [
        SearchPerson,
        SearchPerson,
        SearchOr,
        SearchNot,
        SearchCamera,
      ]);
      expect(readBack(chosen), chosen);
      expect(readBack(chosen.copyWith(anyOf: false))!.anyOf, isFalse);
      expect(chosen.findsAs(chosen.copyWith(anyOf: false)), isFalse);
    });

    test('a mix nested deeper than the rows is still not edited here', () {
      SearchCriteria? read(SearchCriterion root) =>
          SearchCriteria.fromQuery(SearchQuery(version: 1, root: root));
      expect(
          read(SearchAnd(criteria: [
            SearchOr(criteria: [
              SearchPerson(person: "anne"),
              SearchPlace(geonameId: 3169070),
            ]),
            SearchRating(min: 1),
          ])),
          isNull,
          reason: "And(Or(Person, Place), Rating).");
      expect(
          read(SearchOr(criteria: [
            SearchAnd(criteria: [SearchPerson(person: "anne")]),
          ])),
          isNull,
          reason: "An AND below an OR.");
      expect(
          read(SearchAnd(criteria: [
            SearchNot(
                criterion: SearchOr(criteria: [
              SearchPlace(geonameId: 1),
              SearchPlace(geonameId: 2),
            ])),
          ])),
          isNull,
          reason: "A NOT of an OR.");
      expect(read(SearchAnd(criteria: [SearchNot()])), isNull,
          reason: "A NOT of nothing.");
      expect(
          read(SearchAnd(criteria: [
            SearchNot(criterion: SearchRating(min: 1)),
            SearchRating(min: 0),
          ])),
          isNull,
          reason: "A rating twice.");
    });

    test('"not videos" reads as photos only', () {
      var read = SearchCriteria.fromQuery(SearchQuery(
          version: 1,
          root: SearchAnd(criteria: [
            SearchNot(criterion: SearchMedia(video: true)),
          ])));
      expect(read, const SearchCriteria(media: SearchMediaKind.photos));
    });

    test('an exclusion goes with the row it excludes', () {
      var chosen = const SearchCriteria(camera: "Pixel 7")
          .toggled(SearchKind.camera);
      expect(chosen.excludes(SearchKind.camera), isTrue);
      expect(chosen.toggled(SearchKind.camera).excluded, isEmpty);
      var cleared = chosen.copyWith(clearCamera: true);
      expect(cleared.excluded, isEmpty);
      expect(cleared, const SearchCriteria());
      expect(const SearchCriteria().toggled(SearchKind.text).excluded, isEmpty,
          reason: "Nothing chosen is nothing to exclude.");
      expect(const SearchCriteria(excludedPersons: ["p"]).isEmpty, isFalse);
      expect(const SearchCriteria(anyOf: true).isEmpty, isTrue);
    });

    test('the title says "not" and "or" in the language shown', () {
      const names = {"anne": "Anne", "philipp": "Philipp"};
      const places = {3169070: "Rome", 3176959: "Florence"};
      expect(
          const SearchCriteria(persons: ["anne"], excludedPersons: ["philipp"])
              .title(fallback: "Search", personNames: names),
          "Anne, not Philipp");
      expect(
          SearchCriteria(
            places: const [3169070, 3176959],
            from: DateTime(2019, 1, 1),
            to: DateTime(2019, 12, 31),
            excluded: const {SearchKind.date},
          ).title(fallback: "Search", placeNames: places),
          "Rome or Florence, not 2019");
      expect(
          const SearchCriteria(
            persons: ["anne", "philipp"],
            places: [3169070],
            anyOf: true,
          ).title(fallback: "Search", personNames: names, placeNames: places),
          "Anne or Philipp or Rome");
      expect(
          const SearchCriteria(persons: ["anne"], excludedPersons: ["philipp"])
              .title(
                  fallback: "Suche",
                  personNames: names,
                  not: "nicht",
                  or: "oder"),
          "Anne, nicht Philipp");
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
