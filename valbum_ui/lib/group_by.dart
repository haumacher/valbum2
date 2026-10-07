/// "Group by…": an album's headings made from its photos, issue #238.
///
/// A structuring help, not a live view: the headings are computed here, put
/// into the edit buffer by the album view, and from then on are ordinary
/// headings (#158) — saved by Save, thrown away by Cancel, edited and deleted
/// like any other. The server stores them as it stores every heading.
///
/// The rules, all of them in [planGrouping] and pure, so that they are
/// checked without a screen:
///
///  * **Travel order, not buckets.** The photos keep the order they are
///    stored in (the date order, after "Sort by date"); a heading is put where
///    the key changes from one photo to the next, so a return to a town later
///    on is a section of its own.
///  * **A photo without the key never starts a section.** No position, no
///    place of that kind nearby, no recording time: it stays in the section
///    of the photos before it, and a leading run of them joins the first
///    section after it.
///  * **Day and town on one level** is Day › Town flattened: one heading per
///    town visit of a day, "Mon, 14 Jul 2026 – Florence", or the day alone
///    where nothing of that day names a town.
///  * **Scope.** With a selection, every contiguous stretch of it is grouped
///    on its own and nothing outside it is touched; without one, the album.
///  * **Existing headings** in the scope are replaced, or — "keep sections,
///    add subsections" — the sections stay and each of them is grouped on its
///    own, its subsections replaced.
///
/// A group ([ImageGroup]) counts as its representative, a video as a photo.
library;

import 'album_date.dart';
import 'album_edit.dart';
import 'album_layout.dart' show ToImage;
import 'resource.dart';

/// What a heading level is grouped by.
enum GroupKey {
  /// The calendar day of the recording time, in the device's zone (as the
  /// inbox cuts its days, see [localDayOf]).
  day,

  /// The town ([PlaceKind.place]).
  town,

  /// The part of town ([PlaceKind.district]).
  district,

  /// The region, the first administrative level ([PlaceKind.adm1]).
  region,

  /// The country ([PlaceKind.country]).
  country,

  /// The named feature close by ([PlaceKind.feature]): a park, a castle.
  feature,

  /// Day and town on one level: Day › Town flattened, see the library doc.
  dayAndTown,
}

/// What happens to the headings already in the scope.
enum GroupMode {
  /// They are removed, the new ones written instead.
  replace,

  /// The sections ([headingSection]) stay, the subsections in the scope are
  /// removed and each section is grouped on its own into subsections.
  addSubsections,
}

/// The place kind a place key is read from, `null` for the day keys.
PlaceKind? placeKindOf(GroupKey key) => switch (key) {
      GroupKey.town => PlaceKind.place,
      GroupKey.district => PlaceKind.district,
      GroupKey.region => PlaceKind.adm1,
      GroupKey.country => PlaceKind.country,
      GroupKey.feature => PlaceKind.feature,
      GroupKey.day || GroupKey.dayAndTown => null,
    };

/// A heading [planGrouping] would write, with the number of photos it heads.
class PlannedHeading {
  /// The heading as it goes into the album.
  final Heading heading;

  /// The photos under it as the album shows them: up to the next heading of
  /// the same or a higher level, the photos the rating filter hides left out.
  final int count;

  const PlannedHeading(this.heading, this.count);

  /// [headingSection] or [headingSubsection].
  int get level => heading.level;

  /// What the heading reads.
  String get text => heading.text;

  @override
  String toString() => "${"#" * level} $text ($count)";
}

/// What the scope of a grouping holds, before anything is grouped: what the
/// dialog offers depends on it.
class GroupScope {
  /// The stretches of the album's parts grouped, each `[start, end)`.
  final List<(int, int)> stretches;

  /// Whether the scope is a selection (otherwise it is the whole album).
  final bool selected;

  /// The photos in the scope (a group is one).
  final int images;

  /// The headings in the scope, of either level.
  final int headings;

  /// The sections ([headingSection]) in the scope.
  final int sections;

  const GroupScope(
    this.stretches, {
    required this.selected,
    required this.images,
    required this.headings,
    required this.sections,
  });
}

/// The result of [planGrouping]: the album's new parts and what changed.
class GroupingPlan {
  /// The parts of the album after the grouping: a new list, holding the
  /// parts that were there (by identity) and the new headings.
  final List<AlbumPart> parts;

  /// The headings written, in album order.
  final List<PlannedHeading> headings;

  /// The headings that were in the scope and are removed.
  final int replaced;

  const GroupingPlan(this.parts, this.headings, this.replaced);

  /// Whether applying the plan writes anything at all.
  bool get isEmpty => headings.isEmpty;
}

/// Whether [part] is one of the selected images: the part itself is
/// selected, or — for a group — its representative is.
bool _isSelected(AlbumPart part, Set<AlbumPart> selection) {
  if (part is! AbstractImage) {
    return false;
  }
  return selection.contains(part) ||
      (part is ImageGroup && selection.contains(ToImage.toImage(part)));
}

/// The scope a grouping of [parts] with the given [selection] acts on.
///
/// Without a selected image, the whole album. Otherwise the selected images
/// in stretches: two selected images belong to one stretch where nothing
/// but headings and images the rating filter ([minRating]) hides stand
/// between them — what the reader sees as one run. The headings directly in
/// front of a stretch belong to it (they head what is selected); those
/// behind it do not (they head what follows).
GroupScope groupScope(
  List<AlbumPart> parts, {
  Set<AlbumPart> selection = const {},
  int minRating = minMinRating,
}) {
  var chosen = Set<AlbumPart>.identity()..addAll(selection);
  var stretches = <(int, int)>[];
  var anySelected = parts.any((part) => _isSelected(part, chosen));
  if (!anySelected) {
    stretches.add((0, parts.length));
  } else {
    int? start;
    int? last;
    for (var index = 0; index < parts.length; index++) {
      var part = parts[index];
      if (_isSelected(part, chosen)) {
        if (start == null) {
          start = index;
          // The headings right in front of it head it.
          while (start! > 0 && parts[start - 1] is Heading) {
            start--;
          }
        }
        last = index;
      } else if (part is AbstractImage &&
          isVisiblePart(part, minRating) &&
          start != null) {
        stretches.add((start, last! + 1));
        start = null;
      }
    }
    if (start != null) {
      stretches.add((start, last! + 1));
    }
  }

  var images = 0;
  var headings = 0;
  var sections = 0;
  for (var (start, end) in stretches) {
    for (var index = start; index < end; index++) {
      var part = parts[index];
      if (part is Heading) {
        headings++;
        if (headingLevel(part) == headingSection) {
          sections++;
        }
      } else if (part is AbstractImage && isVisiblePart(part, minRating)) {
        images++;
      }
    }
  }
  return GroupScope(
    stretches,
    selected: anySelected,
    images: images,
    headings: headings,
    sections: sections,
  );
}

/// The headings [parts] get by the given keys, and the parts that result.
///
/// [mode] [GroupMode.replace] groups by [sectionKey] into sections and,
/// where [subsectionKey] is given, each section by it into subsections;
/// [GroupMode.addSubsections] keeps the sections there are and groups each
/// by [subsectionKey] alone ([sectionKey] is not used).
///
/// [selection] and [minRating] decide the scope, see [groupScope]. A photo
/// the rating filter hides is carried along where it stands and never starts
/// a section, nor is it counted. A stretch (or, adding subsections, a
/// section) none of whose photos has the key is left exactly as it is.
///
/// [dayText] writes a day out (the editor's locale), [localTime] is the
/// device's zone (see [localDayOf]). [parts] is never changed.
GroupingPlan planGrouping(
  List<AlbumPart> parts, {
  GroupMode mode = GroupMode.replace,
  GroupKey? sectionKey = GroupKey.day,
  GroupKey? subsectionKey,
  Set<AlbumPart> selection = const {},
  int minRating = minMinRating,
  required String Function(DateTime day) dayText,
  LocalTime localTime = deviceLocalTime,
}) {
  var scope = groupScope(parts, selection: selection, minRating: minRating);
  var grouper = _Grouper(minRating, dayText, localTime);

  var result = <AlbumPart>[];
  var planned = <Heading>[];
  var replaced = 0;
  var done = 0;
  for (var (start, end) in scope.stretches) {
    result.addAll(parts.sublist(done, start));
    done = end;
    var stretch = parts.sublist(start, end);
    var before = planned.length;

    if (mode == GroupMode.replace) {
      var images = [
        for (var part in stretch)
          if (part is! Heading) part
      ];
      var written = sectionKey == null
          ? <PlannedHeading>[]
          : grouper.group(images, sectionKey, subsectionKey);
      if (written.isEmpty) {
        // Nothing here has the key: the stretch stays as it was.
        result.addAll(stretch);
        continue;
      }
      replaced += stretch.length - images.length;
      result.addAll(grouper.merge(images));
      planned.addAll(written.map((h) => h.heading));
      planned.addAll(_restoreAfter(parts, end, result, minRating));
      continue;
    }

    // Adding subsections: the stretch falls into chunks at its sections, and
    // each chunk is grouped on its own.
    var chunk = <AlbumPart>[];
    void flush() {
      if (chunk.isEmpty) {
        return;
      }
      var head = chunk.first is Heading &&
              headingLevel(chunk.first as Heading) == headingSection
          ? chunk.first as Heading
          : null;
      var body = head == null ? chunk : chunk.sublist(1);
      var images = [
        for (var part in body)
          if (part is! Heading) part
      ];
      var written = subsectionKey == null
          ? <PlannedHeading>[]
          : grouper.group(images, subsectionKey, null,
              level: headingSubsection);
      if (written.isEmpty) {
        result.addAll(chunk);
      } else {
        replaced += body.length - images.length;
        if (head != null) {
          result.add(head);
        }
        result.addAll(grouper.merge(images));
        planned.addAll(written.map((h) => h.heading));
      }
      chunk = [];
    }

    for (var part in stretch) {
      if (part is Heading && headingLevel(part) == headingSection) {
        flush();
      }
      chunk.add(part);
    }
    flush();
    if (planned.length > before) {
      planned.addAll(_restoreAfter(parts, end, result, minRating));
    }
  }
  result.addAll(parts.sublist(done));
  return GroupingPlan(
    result,
    [
      for (var heading in planned)
        PlannedHeading(heading, _countUnder(result, heading, minRating)),
    ],
    replaced,
  );
}

/// The section and subsection in effect at [index] of [parts]: the last
/// section before it, and the last subsection between that one and it.
(Heading?, Heading?) _inEffect(List<AlbumPart> parts, int index) {
  Heading? subsection;
  for (var i = index - 1; i >= 0; i--) {
    var part = parts[i];
    if (part is! Heading) {
      continue;
    }
    if (headingLevel(part) == headingSection) {
      return (part, subsection);
    }
    subsection ??= part;
  }
  return (null, subsection);
}

/// Keeps the photos behind a grouped stretch ending at [end] of [parts] in
/// the section they were in: where photos of that section follow before the
/// next heading, and [result] (the parts written so far, the stretch's
/// included) would put them under another heading, a copy of the heading
/// that was in effect — the section, and its subsection where there was one
/// — goes right behind the stretch. Answers the copies, already added.
List<Heading> _restoreAfter(
  List<AlbumPart> parts,
  int end,
  List<AlbumPart> result,
  int minRating,
) {
  var follows = false;
  for (var i = end; i < parts.length && parts[i] is! Heading; i++) {
    if (isVisiblePart(parts[i], minRating)) {
      follows = true;
      break;
    }
  }
  if (!follows) {
    return const [];
  }
  var (section, subsection) = _inEffect(parts, end);
  var (newSection, newSubsection) = _inEffect(result, result.length);
  if (identical(section, newSection) && identical(subsection, newSubsection)) {
    return const [];
  }
  Heading copy(Heading heading) =>
      Heading(text: heading.text, level: headingLevel(heading));
  var copies = <Heading>[
    // The section where it is no longer the one in effect, or where a new
    // subsection would otherwise reach on into it.
    if (section != null &&
        (!identical(section, newSection) ||
            subsection == null && newSubsection != null))
      copy(section),
    if (subsection != null) copy(subsection),
  ];
  result.addAll(copies);
  return copies;
}

/// The photos shown under [heading] in [parts]: up to the next heading of
/// the same or a higher level, the hidden ones left out.
int _countUnder(List<AlbumPart> parts, Heading heading, int minRating) {
  var start = parts.indexWhere((part) => identical(part, heading));
  var level = headingLevel(heading);
  var count = 0;
  for (var i = start + 1; i < parts.length; i++) {
    var part = parts[i];
    if (part is Heading) {
      if (headingLevel(part) <= level) {
        break;
      }
    } else if (part is AbstractImage && isVisiblePart(part, minRating)) {
      count++;
    }
  }
  return count;
}

/// A section found in a run of photos: where it starts and ends (`[start,
/// end)`) and what its heading reads.
class _Run {
  final int start;
  final int end;
  final String text;

  const _Run(this.start, this.end, this.text);
}

class _Grouper {
  final int minRating;
  final String Function(DateTime day) dayText;
  final LocalTime localTime;

  /// The heading that starts at an image, by identity.
  final Map<AlbumPart, List<Heading>> _before = Map.identity();

  _Grouper(this.minRating, this.dayText, this.localTime);

  /// The key of [part] for [key], with the text it reads; `null` where the
  /// part does not have it (or is hidden by the rating filter).
  (Object, String)? keyOf(AlbumPart part, GroupKey key) {
    if (part is! AbstractImage || !isVisiblePart(part, minRating)) {
      return null;
    }
    var image = ToImage.toImage(part);
    var kind = placeKindOf(key);
    if (kind == null) {
      var day = localDayOf(image.date, localTime: localTime);
      return day == null ? null : (day, dayText(day));
    }
    for (var tag in image.places?.tags ?? const <PlaceTag>[]) {
      if (tag.kind == kind && tag.name.isNotEmpty) {
        return (tag.geonameId, tag.name);
      }
    }
    return null;
  }

  /// The sections of [images] by the atomic [key]: a section starts where
  /// the key changes; the photos without it stay with the section before
  /// them, a leading run of them with the first one. Empty where no photo
  /// has the key.
  List<_Run> runs(List<AlbumPart> images, GroupKey key) {
    var starts = <(int, String)>[];
    Object? current;
    for (var index = 0; index < images.length; index++) {
      var found = keyOf(images[index], key);
      if (found == null || found.$1 == current) {
        continue;
      }
      starts.add((starts.isEmpty ? 0 : index, found.$2));
      current = found.$1;
    }
    return [
      for (var i = 0; i < starts.length; i++)
        _Run(
          starts[i].$1,
          i + 1 < starts.length ? starts[i + 1].$1 : images.length,
          starts[i].$2,
        ),
    ];
  }

  /// The sections of [images] by [key], the composite [GroupKey.dayAndTown]
  /// as Day › Town flattened.
  List<_Run> sections(List<AlbumPart> images, GroupKey key) {
    if (key != GroupKey.dayAndTown) {
      return runs(images, key);
    }
    var result = <_Run>[];
    for (var day in runs(images, GroupKey.day)) {
      var towns = runs(images.sublist(day.start, day.end), GroupKey.town);
      if (towns.isEmpty) {
        // A day nothing of which names a town: the day alone.
        result.add(day);
        continue;
      }
      for (var town in towns) {
        result.add(_Run(
          day.start + town.start,
          day.start + town.end,
          "${day.text} – ${town.text}",
        ));
      }
    }
    return result;
  }

  /// The headings for [images], by [key] at [level] and, where given, by
  /// [subKey] one level below; remembered for [merge].
  List<PlannedHeading> group(
    List<AlbumPart> images,
    GroupKey key,
    GroupKey? subKey, {
    int level = headingSection,
  }) {
    var result = <PlannedHeading>[];
    for (var run in sections(images, key)) {
      var heading = Heading(text: run.text, level: level);
      _before.putIfAbsent(images[run.start], () => []).add(heading);
      var inside = images.sublist(run.start, run.end);
      result.add(PlannedHeading(heading, _count(inside)));
      if (subKey != null) {
        for (var sub in sections(inside, subKey)) {
          var subheading = Heading(text: sub.text, level: headingSubsection);
          _before.putIfAbsent(inside[sub.start], () => []).add(subheading);
          result.add(PlannedHeading(
            subheading,
            _count(inside.sublist(sub.start, sub.end)),
          ));
        }
      }
    }
    return result;
  }

  /// [images] with the headings [group] put in front of them.
  List<AlbumPart> merge(List<AlbumPart> images) => [
        for (var image in images) ...[
          ...?_before[image],
          image,
        ],
      ];

  int _count(List<AlbumPart> images) => images
      .where((part) => part is AbstractImage && isVisiblePart(part, minRating))
      .length;
}
