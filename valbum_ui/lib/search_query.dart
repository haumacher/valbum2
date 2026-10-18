/// The criteria of the search view of issue #227 and the stored query they
/// make, see [SearchQuery] in `model.proto`.
///
/// Pure: nothing here knows a widget or the server, so the mapping between
/// what the user chose and what is stored can be tested on its own.
///
/// The criteria are rows that combine with AND ("all of these") or, where
/// [SearchCriteria.anyOf] says so, with OR ("any of these", issue #242): every
/// person chosen is a row of its own ("Anne and Philipp"), the date range is
/// one, the places chosen are one (the photograph lies in any of them), the
/// labels one (it carries any of them), the lowest rating, the media kind, the
/// text in the description or the album's title, and the camera one each.
///
/// Every row can be excluded (issue #242): it is stored as a [SearchNot] of
/// itself. An excluded person, place or label is a row of its own ("Anne
/// without Philipp" is `And(Person(anne), Not(Person(philipp)))`); an excluded
/// date range, rating, text or camera is the one row of its kind negated. An
/// excluded media kind is never written: "not videos" is "photos only".
///
/// A query that says something else — a mix of AND and OR nested deeper than
/// these rows, a folder — is stored faithfully but cannot be edited on this
/// screen, see [SearchCriteria.fromQuery].
///
/// What the photographs found are grouped by ([SearchCriteria.groupBy], issue
/// #244) travels in the query as well, but is no criterion: it changes the
/// headings the result is shown with, never what is found.
///
/// The AI search of issue #257 ([SearchCriteria.byContent]) takes the text as
/// a description of what the photographs show, a [SearchContent] in place of
/// the [SearchText]: the server ranks by it, the nearest first, and every
/// other row narrows what is ranked. A content criterion ranks, so it is
/// never excluded and never one of "any of these": in AI mode the rows
/// combine with AND and the text is not negated, while what was chosen for
/// the conventional search is kept for switching back.
///
/// "More like this" ([SearchCriteria.similar], issue #257 package 5) takes a
/// photograph as the query, a [SearchSimilar]: it ranks like the AI search, so
/// the rows combine with AND around it, and a query ranks once — while a photo
/// is the query, the text is the conventional [SearchText] again, which
/// narrows (and may be excluded), never a second [SearchContent].
///
/// A query that ranks may state its own cut ([SearchCriteria.minSimilarity],
/// issue #302): the lowest similarity a photo found has, on the criterion
/// that ranks. Without one, the space's cut holds
/// ([SearchOptions.contentThreshold], [SearchOptions.similarThreshold]). A
/// query stating one is written as version 2 ([searchQueryVersion]), which a
/// build that does not know the cut refuses rather than answering its own.
library;

import 'package:intl/intl.dart';
import 'package:jsontool/jsontool.dart';

import 'l10n/app_localizations.dart';
import 'resource.dart';

/// The newest version of the stored query this app reads and writes, see
/// [SearchQuery.version]: a query whose ranking criterion states its own cut
/// (issue #302).
const int searchQueryVersion = 2;

/// The version of every query that states no cut of its own: what a build
/// before issue #302 reads.
const int searchQueryFirstVersion = 1;

/// [value], a similarity between 0 and 1, as the language shown writes it
/// with two decimals ("0,62", "0.62"), see issue #302.
String formatSimilarity(String locale, double value) =>
    NumberFormat.decimalPatternDigits(locale: locale, decimalDigits: 2)
        .format(value);

/// What a criterion ranks by, see [SearchCriteria.rankKind].
enum SearchRankKind {
  /// Nothing: the result is by date.
  none,

  /// A text describing what the photos show ([SearchContent]).
  text,

  /// A photo ("More like this", [SearchSimilar]).
  photo,
}

/// Which media a search looks for.
enum SearchMediaKind {
  /// Photographs and videos alike.
  all,

  /// Still pictures only.
  photos,

  /// Videos only.
  videos,
}

/// The rows of the search view that are one criterion each, and can be
/// excluded as a whole, see [SearchCriteria.excluded] (issue #242).
enum SearchKind {
  /// The date range, both of its ends.
  date,

  /// The lowest rating.
  rating,

  /// The text looked for.
  text,

  /// The camera.
  camera,
}

/// What the user chose in the search view, see the library comment.
class SearchCriteria {
  /// The ids of the persons who must be in the photograph, each a row.
  final List<String> persons;

  /// The ids of the persons who must not be in the photograph, each a row
  /// (issue #242).
  final List<String> excludedPersons;

  /// The first day, in the device's zone; `null` for no lower bound.
  final DateTime? from;

  /// The last day (inclusive), in the device's zone; `null` for no upper
  /// bound.
  final DateTime? to;

  /// The GeoNames ids of the places of which the photograph must lie in one.
  final List<int> places;

  /// The GeoNames ids of the places the photograph must not lie in, each a
  /// row (issue #242).
  final List<int> excludedPlaces;

  /// The labels of which the photograph must carry one.
  final List<String> labels;

  /// The labels the photograph must not carry, each a row (issue #242).
  final List<String> excludedLabels;

  /// The lowest rating; `null` for every rating.
  final int? minRating;

  /// The media looked for.
  final SearchMediaKind media;

  /// The text looked for in the description and the album's title; empty for
  /// none.
  final String text;

  /// The camera the photograph was taken with; `null` for any.
  final String? camera;

  /// The keys the photographs found are grouped by, the first making the
  /// sections, the second the subsections; empty for the result by date, see
  /// [SearchQuery.groupBy] and `search_grouping.dart` (issue #244).
  ///
  /// No criterion: [isEmpty] does not look at it.
  final List<SearchGroup> groupBy;

  /// The rows of one criterion each that are excluded (issue #242); a kind
  /// not chosen is never excluded, see [copyWith].
  final Set<SearchKind> excluded;

  /// Whether any of the rows is enough ("any of these", an OR at the top),
  /// rather than all of them (issue #242).
  final bool anyOf;

  /// Whether the text describes what the photographs show — the AI search of
  /// issue #257, a [SearchContent] ranking the result by similarity — rather
  /// than words of their descriptions and album titles ([SearchText]).
  ///
  /// In this mode [anyOf] and an exclusion of the text are kept but not
  /// stated, see the library comment.
  final bool byContent;

  /// The photograph "More like this" compares with (issue #257): its path
  /// relative to the space root, `/`-separated, as [PhotoRef.path] says it;
  /// `null` for none. Empty where the server answered a saved search without
  /// it — the caller may not see the photo — which is kept as it is.
  final String? similar;

  /// The content hash of [similar] as the server answered it (it fills it in
  /// when it stores a saved search), handed back unchanged; empty for none.
  final String similarHash;

  /// The lowest similarity a photo found has (issue #302), stated on the
  /// criterion that ranks; `0` for the cut of the space. Kept only while the
  /// criteria rank by the same kind ([rankKind]): a text and a photo are
  /// compared on different scales, so a change of the kind takes it away.
  final double minSimilarity;

  const SearchCriteria({
    this.persons = const [],
    this.excludedPersons = const [],
    this.from,
    this.to,
    this.places = const [],
    this.excludedPlaces = const [],
    this.labels = const [],
    this.excludedLabels = const [],
    this.minRating,
    this.media = SearchMediaKind.all,
    this.text = "",
    this.camera,
    this.groupBy = const [],
    this.excluded = const {},
    this.anyOf = false,
    this.byContent = false,
    this.similar,
    this.similarHash = "",
    this.minSimilarity = 0,
  });

  /// No criterion at all: the search view asks for one before it searches.
  bool get isEmpty =>
      persons.isEmpty &&
      excludedPersons.isEmpty &&
      from == null &&
      to == null &&
      places.isEmpty &&
      excludedPlaces.isEmpty &&
      labels.isEmpty &&
      excludedLabels.isEmpty &&
      minRating == null &&
      media == SearchMediaKind.all &&
      text.trim().isEmpty &&
      camera == null &&
      similar == null;

  /// Whether the row of [kind] is chosen at all.
  bool has(SearchKind kind) => switch (kind) {
        SearchKind.date => from != null || to != null,
        SearchKind.rating => minRating != null,
        SearchKind.text => text.trim().isNotEmpty,
        SearchKind.camera => camera != null,
      };

  /// Whether the row of [kind] is chosen and excluded; the text of an AI
  /// search never is, see [byContent].
  bool excludes(SearchKind kind) =>
      excluded.contains(kind) && has(kind) && !(textRanks && kind == SearchKind.text);

  /// Whether the text describes what the photographs show ([SearchContent]):
  /// the AI search, unless a photo is the query, see [similar].
  bool get textRanks => byContent && similar == null;

  /// Whether these criteria rank by what the photographs show: the AI search
  /// with a text ([byContent]), or "More like this" ([similar]).
  bool get ranks => similar != null || (byContent && text.trim().isNotEmpty);

  /// What these criteria rank by, see [ranks].
  SearchRankKind get rankKind => similar != null
      ? SearchRankKind.photo
      : textRanks && text.trim().isNotEmpty
          ? SearchRankKind.text
          : SearchRankKind.none;

  /// The cut these criteria state, `0` for none: [minSimilarity] where they
  /// rank, nothing where they do not.
  double get statedMinSimilarity =>
      rankKind == SearchRankKind.none ? 0 : minSimilarity;

  /// Whether the search is one by what the photos show at all — the AI
  /// search, or "More like this", which is one as well: its rows are one
  /// AND, and the switch of the search view shows it on.
  bool get byWhatTheyShow => byContent || similar != null;

  /// These criteria with the given changes; a `null` argument keeps a value,
  /// the `clear…` flags take one away. An exclusion of a kind no longer
  /// chosen goes with it.
  SearchCriteria copyWith({
    List<String>? persons,
    List<String>? excludedPersons,
    DateTime? from,
    bool clearFrom = false,
    DateTime? to,
    bool clearTo = false,
    List<int>? places,
    List<int>? excludedPlaces,
    List<String>? labels,
    List<String>? excludedLabels,
    int? minRating,
    bool clearMinRating = false,
    SearchMediaKind? media,
    String? text,
    String? camera,
    bool clearCamera = false,
    List<SearchGroup>? groupBy,
    Set<SearchKind>? excluded,
    bool? anyOf,
    bool? byContent,
    String? similar,
    String? similarHash,
    bool clearSimilar = false,
    double? minSimilarity,
  }) {
    var changed = SearchCriteria(
      persons: persons ?? this.persons,
      excludedPersons: excludedPersons ?? this.excludedPersons,
      from: clearFrom ? null : from ?? this.from,
      to: clearTo ? null : to ?? this.to,
      places: places ?? this.places,
      excludedPlaces: excludedPlaces ?? this.excludedPlaces,
      labels: labels ?? this.labels,
      excludedLabels: excludedLabels ?? this.excludedLabels,
      minRating: clearMinRating ? null : minRating ?? this.minRating,
      media: media ?? this.media,
      text: text ?? this.text,
      camera: clearCamera ? null : camera ?? this.camera,
      groupBy: groupBy ?? this.groupBy,
      excluded: excluded ?? this.excluded,
      anyOf: anyOf ?? this.anyOf,
      byContent: byContent ?? this.byContent,
      similar: clearSimilar ? null : similar ?? this.similar,
      similarHash: clearSimilar
          ? ""
          : similarHash ?? (similar == null ? this.similarHash : ""),
      minSimilarity: minSimilarity ?? this.minSimilarity,
    );
    if (minSimilarity == null &&
        changed.minSimilarity != 0 &&
        changed.rankKind != rankKind) {
      // Another scale: the cut of the space holds again.
      changed = changed._withMinSimilarity(0);
    }
    var kept = {
      for (var kind in changed.excluded)
        if (changed.has(kind)) kind,
    };
    return kept.length == changed.excluded.length
        ? changed
        : changed._withExcluded(kept);
  }

  SearchCriteria _withMinSimilarity(double value) => SearchCriteria(
        persons: persons,
        excludedPersons: excludedPersons,
        from: from,
        to: to,
        places: places,
        excludedPlaces: excludedPlaces,
        labels: labels,
        excludedLabels: excludedLabels,
        minRating: minRating,
        media: media,
        text: text,
        camera: camera,
        groupBy: groupBy,
        excluded: excluded,
        anyOf: anyOf,
        byContent: byContent,
        similar: similar,
        similarHash: similarHash,
        minSimilarity: value,
      );

  SearchCriteria _withExcluded(Set<SearchKind> value) => SearchCriteria(
        persons: persons,
        excludedPersons: excludedPersons,
        from: from,
        to: to,
        places: places,
        excludedPlaces: excludedPlaces,
        labels: labels,
        excludedLabels: excludedLabels,
        minRating: minRating,
        media: media,
        text: text,
        camera: camera,
        groupBy: groupBy,
        excluded: value,
        anyOf: anyOf,
        byContent: byContent,
        similar: similar,
        similarHash: similarHash,
        minSimilarity: minSimilarity,
      );

  /// These criteria with the row of [kind] excluded or not.
  SearchCriteria toggled(SearchKind kind) => copyWith(
        excluded: excluded.contains(kind)
            ? ({...excluded}..remove(kind))
            : {...excluded, kind},
      );

  /// Whether these criteria find what [other] finds: they differ in the
  /// grouping at most, which is re-derived from a result already in.
  bool findsAs(SearchCriteria other) =>
      copyWith(groupBy: other.groupBy) == other;

  /// The stored form, see the library comment: one AND (or, for [anyOf], one
  /// OR) over the rows, the places and the labels chosen each an OR of the
  /// ones chosen, an excluded row a NOT of itself. In AI mode ([byContent])
  /// the text is a [SearchContent], and the rows are one AND; "More like
  /// this" ([similar]) is a [SearchSimilar] first, the rows one AND, the text
  /// a [SearchText].
  SearchQuery toQuery() {
    SearchCriterion row(SearchKind kind, SearchCriterion criterion) =>
        excludes(kind) ? SearchNot(criterion: criterion) : criterion;
    var cut = statedMinSimilarity;
    var criteria = <SearchCriterion>[
      if (similar != null)
        SearchSimilar(
          ref: PhotoRef(hash: similarHash, path: similar!),
          minSimilarity: cut,
        ),
      for (var person in persons) SearchPerson(person: person),
      for (var person in excludedPersons)
        SearchNot(criterion: SearchPerson(person: person)),
      if (from != null || to != null)
        row(
          SearchKind.date,
          SearchDate(
            from: from == null ? 0 : _startOf(from!).millisecondsSinceEpoch,
            to: to == null
                ? 0
                : _startOf(to!)
                    .add(const Duration(days: 1))
                    .millisecondsSinceEpoch,
          ),
        ),
      if (places.isNotEmpty)
        SearchOr(
          criteria: [for (var place in places) SearchPlace(geonameId: place)],
        ),
      for (var place in excludedPlaces)
        SearchNot(criterion: SearchPlace(geonameId: place)),
      if (labels.isNotEmpty)
        SearchOr(
          criteria: [for (var label in labels) SearchLabel(label: label)],
        ),
      for (var label in excludedLabels)
        SearchNot(criterion: SearchLabel(label: label)),
      if (minRating != null)
        row(SearchKind.rating, SearchRating(min: minRating!)),
      if (media != SearchMediaKind.all)
        SearchMedia(video: media == SearchMediaKind.videos),
      if (text.trim().isNotEmpty)
        textRanks
            ? SearchContent(text: text.trim(), minSimilarity: cut)
            : row(SearchKind.text, SearchText(text: text.trim())),
      if (camera != null) row(SearchKind.camera, SearchCamera(camera: camera!)),
    ];
    return SearchQuery(
      version: cut != 0 ? searchQueryVersion : searchQueryFirstVersion,
      root: anyOf && !byWhatTheyShow
          ? SearchOr(criteria: criteria)
          : SearchAnd(criteria: criteria),
      groupBy: [...groupBy],
    );
  }

  /// The criteria [query] states, `null` where it says something this screen
  /// cannot show: a newer version, a mix of AND and OR below the top, a NOT
  /// of anything but a row, a folder, or a kind twice where it is chosen
  /// once.
  static SearchCriteria? fromQuery(SearchQuery? query) {
    if (query == null || query.version > searchQueryVersion) {
      return null;
    }
    var root = query.root;
    List<SearchCriterion> rows;
    bool anyOf;
    switch (root) {
      case SearchAnd(criteria: var criteria):
        rows = criteria;
        anyOf = false;
      case SearchOr(criteria: var criteria):
        rows = criteria;
        anyOf = true;
      case SearchContent content:
        // The content search alone, as the server takes it too.
        rows = [content];
        anyOf = false;
      case SearchSimilar similar:
        rows = [similar];
        anyOf = false;
      default:
        return null;
    }
    var persons = <String>[];
    var excludedPersons = <String>[];
    DateTime? from;
    DateTime? to;
    var dated = false;
    var places = <int>[];
    var excludedPlaces = <int>[];
    var labels = <String>[];
    var excludedLabels = <String>[];
    int? minRating;
    var media = SearchMediaKind.all;
    var text = "";
    var byContent = false;
    String? similar;
    var similarHash = "";
    double minSimilarity = 0;
    String? camera;
    var excluded = <SearchKind>{};

    // Reads the row of one criterion each; the kind read, `null` where it
    // is none or was read before.
    SearchKind? single(SearchCriterion criterion) {
      switch (criterion) {
        case SearchDate(from: var start, to: var end):
          if (dated) {
            return null;
          }
          dated = true;
          from = start == 0 ? null : _dayOf(start);
          to = end == 0 ? null : _dayOf(end).subtract(const Duration(days: 1));
          return SearchKind.date;
        case SearchRating(min: var min):
          if (minRating != null) {
            return null;
          }
          minRating = min;
          return SearchKind.rating;
        case SearchText(text: var value):
          if (text.isNotEmpty) {
            return null;
          }
          text = value;
          return SearchKind.text;
        case SearchCamera(camera: var value):
          if (camera != null) {
            return null;
          }
          camera = value;
          return SearchKind.camera;
        default:
          return null;
      }
    }

    for (var criterion in rows) {
      switch (criterion) {
        case SearchContent(text: var value, minSimilarity: var cut):
          // Ranks: once, and never one of "any of these".
          if (anyOf ||
              text.isNotEmpty ||
              value.trim().isEmpty ||
              similar != null) {
            return null;
          }
          text = value;
          byContent = true;
          minSimilarity = cut;
        case SearchSimilar(ref: var ref, minSimilarity: var cut):
          // Ranks as well: once, never one of "any of these", never next to
          // a text that ranks.
          if (anyOf || similar != null || byContent) {
            return null;
          }
          similar = ref?.path ?? "";
          similarHash = ref?.hash ?? "";
          minSimilarity = cut;
        case SearchPerson(person: var person):
          persons.add(person);
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
        case SearchMedia(video: var video):
          if (media != SearchMediaKind.all) {
            return null;
          }
          media = video ? SearchMediaKind.videos : SearchMediaKind.photos;
        case SearchNot(criterion: var negated):
          switch (negated) {
            case SearchPerson(person: var person):
              excludedPersons.add(person);
            case SearchPlace(geonameId: var id):
              excludedPlaces.add(id);
            case SearchLabel(label: var label):
              excludedLabels.add(label);
            case SearchMedia(video: var video):
              // "Not videos" is "photos only", and the other way round.
              if (media != SearchMediaKind.all) {
                return null;
              }
              media = video ? SearchMediaKind.photos : SearchMediaKind.videos;
            case var other?:
              var kind = single(other);
              if (kind == null) {
                return null;
              }
              excluded.add(kind);
            case null:
              return null;
          }
        default:
          if (single(criterion) == null) {
            return null;
          }
      }
    }
    return SearchCriteria(
      persons: persons,
      excludedPersons: excludedPersons,
      from: from,
      to: to,
      places: places,
      excludedPlaces: excludedPlaces,
      labels: labels,
      excludedLabels: excludedLabels,
      minRating: minRating,
      media: media,
      text: text,
      camera: camera,
      groupBy: [...query.groupBy],
      excluded: excluded,
      anyOf: anyOf,
      byContent: byContent,
      similar: similar,
      similarHash: similarHash,
      minSimilarity: minSimilarity,
    );
  }

  /// The stored form ([toQuery]) as the location of the search view carries
  /// it (issue #243): the JSON a saved search stores, which [fromAddress]
  /// reads back to these criteria.
  String toAddress() => queryText(toQuery());

  /// The criteria the location of the search view carries ([toAddress]),
  /// `null` where it is no query or says something this screen cannot show,
  /// see [fromQuery].
  static SearchCriteria? fromAddress(String text) {
    try {
      return fromQuery(SearchQuery.fromString(text));
    } catch (_) {
      return null;
    }
  }

  /// The title a saved search is offered under: what was looked for, in a
  /// few words ("Anne & Philipp, 2020–2026", "Anne, not Philipp", "Rome or
  /// Florence, not 2019"), what is excluded last; [fallback] where nothing
  /// can be named. [not] and [or] are the words of the language shown (issue
  /// #242), [similarTo] the name of the photo of "More like this" (#257).
  String title({
    required String fallback,
    Map<String, String> personNames = const {},
    Map<int, String> placeNames = const {},
    String not = "not",
    String or = "or",
    String similarTo = "",
  }) {
    var any = anyOf && !byWhatTheyShow;
    var either = " $or ";
    String no(String part) => "$not $part";
    bool out(SearchKind? kind) => kind != null && excludes(kind);
    String person(String id) => personNames[id] ?? id;
    String place(int id) => placeNames[id] ?? "$id";
    // What is looked for first, what is excluded after it.
    var rows = <(SearchKind?, String)>[
      // The words of the language shown, where the caller gives them.
      if (similar != null && similarTo.isNotEmpty) (null, similarTo),
      if (persons.isNotEmpty)
        (null, persons.map(person).join(any ? either : " & ")),
      if (from != null || to != null) (SearchKind.date, _span(from, to)),
      if (places.isNotEmpty) (null, places.map(place).join(either)),
      if (labels.isNotEmpty) (null, labels.join(either)),
      if (camera != null) (SearchKind.camera, camera!),
      if (text.trim().isNotEmpty) (SearchKind.text, "“${text.trim()}”"),
    ];
    var parts = <String>[
      for (var (kind, part) in rows)
        if (!out(kind)) part,
      for (var id in excludedPersons) no(person(id)),
      for (var (kind, part) in rows)
        if (out(kind)) no(part),
      for (var id in excludedPlaces) no(place(id)),
      for (var label in excludedLabels) no(label),
    ];
    var result = parts.join(any ? either : ", ");
    return result.isEmpty ? fallback : result;
  }

  static String _span(DateTime? from, DateTime? to) {
    String day(DateTime value) =>
        "${value.year}-${_two(value.month)}-${_two(value.day)}";
    bool wholeYears = (from == null || (from.month == 1 && from.day == 1)) &&
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
      _listEquals(excludedPersons, other.excludedPersons) &&
      from == other.from &&
      to == other.to &&
      _listEquals(places, other.places) &&
      _listEquals(excludedPlaces, other.excludedPlaces) &&
      _listEquals(labels, other.labels) &&
      _listEquals(excludedLabels, other.excludedLabels) &&
      minRating == other.minRating &&
      media == other.media &&
      text == other.text &&
      camera == other.camera &&
      _listEquals(groupBy, other.groupBy) &&
      excluded.length == other.excluded.length &&
      excluded.containsAll(other.excluded) &&
      anyOf == other.anyOf &&
      byContent == other.byContent &&
      similar == other.similar &&
      similarHash == other.similarHash &&
      minSimilarity == other.minSimilarity;

  @override
  int get hashCode => Object.hash(
        Object.hashAll(persons),
        Object.hashAll(excludedPersons),
        from,
        to,
        Object.hashAll(places),
        Object.hashAll(excludedPlaces),
        Object.hashAll(labels),
        Object.hashAll(excludedLabels),
        minRating,
        media,
        text,
        camera,
        Object.hashAll(groupBy),
        Object.hashAllUnordered(excluded),
        anyOf,
        byContent,
        similar,
        similarHash,
        minSimilarity,
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

/// Why a search "like this photo" found nothing (issue #257), in the words of
/// the language shown: its photo is not there for the reader (gone, or
/// hidden from them — the server says both alike), or not analysed yet.
/// Empty for [SearchProblem.none].
String searchProblemText(AppLocalizations l10n, SearchProblem problem) =>
    switch (problem) {
      SearchProblem.photoNotFound => l10n.searchSimilarNotFound,
      SearchProblem.photoNotDescribed => l10n.searchSimilarNotDescribed,
      SearchProblem.none => "",
    };

/// Whether [album] is a saved search, see issue #227.
bool isSearch(AlbumInfo album) => album.kind == AlbumKind.search;

/// The folder (relative to the space root) a saved search at [path] looks
/// below: the folder it lies in, see issue #227.
List<String> searchScopeOf(List<String> path) =>
    path.isEmpty ? path : path.sublist(0, path.length - 1);

/// The JSON of [query] as a saved search stores it and the server is asked
/// it: its properties, without the type in front.
String queryText(SearchQuery query) {
  var buffer = StringBuffer();
  query.writeContent(jsonStringWriter(buffer));
  return buffer.toString();
}
