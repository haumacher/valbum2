/// The criteria of the search view of issue #227 and the stored query they
/// make, see [SearchQuery] in `model.proto`.
///
/// Pure: nothing here knows a widget or the server, so the mapping between
/// what the user chose and what is stored can be tested on its own.
///
/// The criteria combine with AND across kinds: every person chosen must be in
/// the photograph ("Anne and Philipp"), it is taken in the date range, in any
/// of the places chosen, carries any of the labels chosen, is rated at least
/// the minimum, is of the media kind chosen, holds the text in its
/// description or its album's title, and was taken with the camera chosen. A
/// query that says something else — an OR across kinds, a NOT, a folder — is
/// stored faithfully but cannot be edited on this screen, see
/// [SearchCriteria.fromQuery].
library;

import 'resource.dart';

/// The version of the stored query this app writes, see
/// [SearchQuery.version].
const int searchQueryVersion = 1;

/// Which media a search looks for.
enum SearchMediaKind {
  /// Photographs and videos alike.
  all,

  /// Still pictures only.
  photos,

  /// Videos only.
  videos,
}

/// What the user chose in the search view, see the library comment.
class SearchCriteria {
  /// The ids of the persons who must all be in the photograph.
  final List<String> persons;

  /// The first day, in the device's zone; `null` for no lower bound.
  final DateTime? from;

  /// The last day (inclusive), in the device's zone; `null` for no upper
  /// bound.
  final DateTime? to;

  /// The GeoNames ids of the places of which the photograph must lie in one.
  final List<int> places;

  /// The labels of which the photograph must carry one.
  final List<String> labels;

  /// The lowest rating; `null` for every rating.
  final int? minRating;

  /// The media looked for.
  final SearchMediaKind media;

  /// The text looked for in the description and the album's title; empty for
  /// none.
  final String text;

  /// The camera the photograph was taken with; `null` for any.
  final String? camera;

  const SearchCriteria({
    this.persons = const [],
    this.from,
    this.to,
    this.places = const [],
    this.labels = const [],
    this.minRating,
    this.media = SearchMediaKind.all,
    this.text = "",
    this.camera,
  });

  /// No criterion at all: the search view asks for one before it searches.
  bool get isEmpty =>
      persons.isEmpty &&
      from == null &&
      to == null &&
      places.isEmpty &&
      labels.isEmpty &&
      minRating == null &&
      media == SearchMediaKind.all &&
      text.trim().isEmpty &&
      camera == null;

  /// These criteria with the given changes; a `null` argument keeps a value,
  /// the `clear…` flags take one away.
  SearchCriteria copyWith({
    List<String>? persons,
    DateTime? from,
    bool clearFrom = false,
    DateTime? to,
    bool clearTo = false,
    List<int>? places,
    List<String>? labels,
    int? minRating,
    bool clearMinRating = false,
    SearchMediaKind? media,
    String? text,
    String? camera,
    bool clearCamera = false,
  }) => SearchCriteria(
    persons: persons ?? this.persons,
    from: clearFrom ? null : from ?? this.from,
    to: clearTo ? null : to ?? this.to,
    places: places ?? this.places,
    labels: labels ?? this.labels,
    minRating: clearMinRating ? null : minRating ?? this.minRating,
    media: media ?? this.media,
    text: text ?? this.text,
    camera: clearCamera ? null : camera ?? this.camera,
  );

  /// The stored form, see the library comment: one AND over the kinds, a
  /// place and a label each an OR of the ones chosen.
  SearchQuery toQuery() {
    var criteria = <SearchCriterion>[
      for (var person in persons) SearchPerson(person: person),
      if (from != null || to != null)
        SearchDate(
          from: from == null ? 0 : _startOf(from!).millisecondsSinceEpoch,
          to: to == null
              ? 0
              : _startOf(to!)
                    .add(const Duration(days: 1))
                    .millisecondsSinceEpoch,
        ),
      if (places.isNotEmpty)
        SearchOr(
          criteria: [for (var place in places) SearchPlace(geonameId: place)],
        ),
      if (labels.isNotEmpty)
        SearchOr(
          criteria: [for (var label in labels) SearchLabel(label: label)],
        ),
      if (minRating != null) SearchRating(min: minRating!),
      if (media != SearchMediaKind.all)
        SearchMedia(video: media == SearchMediaKind.videos),
      if (text.trim().isNotEmpty) SearchText(text: text.trim()),
      if (camera != null) SearchCamera(camera: camera!),
    ];
    return SearchQuery(
      version: searchQueryVersion,
      root: SearchAnd(criteria: criteria),
    );
  }

  /// The criteria [query] states, `null` where it says something this screen
  /// cannot show: a newer version, an OR across kinds, a NOT, a folder, or a
  /// kind twice where it is chosen once.
  static SearchCriteria? fromQuery(SearchQuery? query) {
    if (query == null || query.version > searchQueryVersion) {
      return null;
    }
    var root = query.root;
    if (root is! SearchAnd) {
      return null;
    }
    var persons = <String>[];
    DateTime? from;
    DateTime? to;
    var dated = false;
    var places = <int>[];
    var labels = <String>[];
    int? minRating;
    var media = SearchMediaKind.all;
    var text = "";
    String? camera;
    for (var criterion in root.criteria) {
      switch (criterion) {
        case SearchPerson(person: var person):
          persons.add(person);
        case SearchDate(from: var start, to: var end):
          if (dated) {
            return null;
          }
          dated = true;
          from = start == 0 ? null : _dayOf(start);
          to = end == 0 ? null : _dayOf(end).subtract(const Duration(days: 1));
        case SearchPlace(geonameId: var id):
          if (places.isNotEmpty) {
            return null;
          }
          places.add(id);
        case SearchLabel(label: var label):
          if (labels.isNotEmpty) {
            return null;
          }
          labels.add(label);
        case SearchOr(criteria: var alternatives):
          if (alternatives.isEmpty) {
            return null;
          }
          if (alternatives.every((c) => c is SearchPlace) && places.isEmpty) {
            places.addAll(
              alternatives.map((c) => (c as SearchPlace).geonameId),
            );
          } else if (alternatives.every((c) => c is SearchLabel) &&
              labels.isEmpty) {
            labels.addAll(alternatives.map((c) => (c as SearchLabel).label));
          } else {
            return null;
          }
        case SearchRating(min: var min):
          if (minRating != null) {
            return null;
          }
          minRating = min;
        case SearchMedia(video: var video):
          if (media != SearchMediaKind.all) {
            return null;
          }
          media = video ? SearchMediaKind.videos : SearchMediaKind.photos;
        case SearchText(text: var value):
          if (text.isNotEmpty) {
            return null;
          }
          text = value;
        case SearchCamera(camera: var value):
          if (camera != null) {
            return null;
          }
          camera = value;
        default:
          return null;
      }
    }
    return SearchCriteria(
      persons: persons,
      from: from,
      to: to,
      places: places,
      labels: labels,
      minRating: minRating,
      media: media,
      text: text,
      camera: camera,
    );
  }

  /// The title a saved search is offered under: what was looked for, in a
  /// few words ("Anne & Philipp, 2020–2026"); [fallback] where nothing can be
  /// named.
  String title({
    required String fallback,
    Map<String, String> personNames = const {},
    Map<int, String> placeNames = const {},
  }) {
    var parts = <String>[
      if (persons.isNotEmpty)
        persons.map((id) => personNames[id] ?? id).join(" & "),
      if (from != null || to != null) _span(from, to),
      if (places.isNotEmpty)
        places.map((id) => placeNames[id] ?? "$id").join(", "),
      if (labels.isNotEmpty) labels.join(", "),
      if (camera != null) camera!,
      if (text.trim().isNotEmpty) "“${text.trim()}”",
    ];
    var result = parts.join(", ");
    return result.isEmpty ? fallback : result;
  }

  static String _span(DateTime? from, DateTime? to) {
    String day(DateTime value) =>
        "${value.year}-${_two(value.month)}-${_two(value.day)}";
    bool wholeYears =
        (from == null || (from.month == 1 && from.day == 1)) &&
        (to == null || (to.month == 12 && to.day == 31));
    if (wholeYears) {
      var start = from?.year.toString() ?? "";
      var end = to?.year.toString() ?? "";
      return start == end ? start : "$start–$end";
    }
    return "${from == null ? "" : day(from)}–${to == null ? "" : day(to)}";
  }

  static String _two(int value) => value.toString().padLeft(2, "0");

  static DateTime _startOf(DateTime day) =>
      DateTime(day.year, day.month, day.day);

  static DateTime _dayOf(int millis) {
    var time = DateTime.fromMillisecondsSinceEpoch(millis);
    return DateTime(time.year, time.month, time.day);
  }

  @override
  bool operator ==(Object other) =>
      other is SearchCriteria &&
      _listEquals(persons, other.persons) &&
      from == other.from &&
      to == other.to &&
      _listEquals(places, other.places) &&
      _listEquals(labels, other.labels) &&
      minRating == other.minRating &&
      media == other.media &&
      text == other.text &&
      camera == other.camera;

  @override
  int get hashCode => Object.hash(
    Object.hashAll(persons),
    from,
    to,
    Object.hashAll(places),
    Object.hashAll(labels),
    minRating,
    media,
    text,
    camera,
  );

  static bool _listEquals<T>(List<T> a, List<T> b) {
    if (a.length != b.length) {
      return false;
    }
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    return true;
  }
}

/// Whether [album] is a saved search, see issue #227.
bool isSearch(AlbumInfo album) => album.kind == AlbumKind.search;

/// The folder (relative to the space root) a saved search at [path] looks
/// below: the folder it lies in, see issue #227.
List<String> searchScopeOf(List<String> path) =>
    path.isEmpty ? path : path.sublist(0, path.length - 1);
