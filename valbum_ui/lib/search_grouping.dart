/// The headings of a search grouped by key, issue #244: "Group by" of the
/// search view and of a saved search ([SearchQuery.groupBy]).
///
/// Presentation alone: the server answers the photographs found by date and
/// stores the keys with the query; the headings are derived here on every
/// open and never stored, edited or dragged.
///
/// The rules, all of them in [groupSearchParts] and pure, so that they are
/// checked without a screen:
///
///  * **Buckets, not travel order.** Unlike the album's "Group by…" (#238,
///    `group_by.dart`), every distinct value of the key is one section, all
///    photos of that value in it, wherever they stand in the result: a search
///    over years is read by its keys, not as a journey.
///  * **Sorted by the key.** The album by its path, the year and the month
///    chronologically, the country, region, town and camera alphabetically, as
///    the server named them in the caller's language. Inside a section the
///    photos keep the order of the result (by date).
///  * **The photos without the key** — no date for the year or the month, no
///    place of that kind, no camera — are one section of their own, always
///    the last of its level ("Without a date").
///  * **Two keys at most.** The second makes the subsections of every
///    section, by the same rules; more keys are not honoured.
///  * A group ([ImageGroup]) counts as its representative.
library;

import 'package:intl/intl.dart';

import 'album_date.dart';
import 'album_edit.dart' show headingSection;
import 'album_layout.dart' show ToImage;
import 'collections.dart' show sourceFolderOf;
import 'l10n/app_localizations.dart';
import 'resource.dart';

/// The album of every heading of a grouping by album, see [albumOfHeading].
final Expando<List<String>> _headingAlbums = Expando("searchHeadingAlbum");

/// The folder (relative to the space root) the photos under [heading] lie
/// in, where [heading] is one of a grouping by album and the server named
/// where they lie ([ImagePart.ref], to a member alone); `null` otherwise.
/// A tap on such a heading opens that album (issue #289).
List<String>? albumOfHeading(Heading heading) => _headingAlbums[heading];

/// The number of keys honoured: the sections and the subsections.
const int maxSearchGroups = 2;

/// The words the headings of a grouping read.
class SearchGroupTexts {
  /// The last section of a grouping by year or month.
  final String withoutDate;

  /// The last section of a grouping by a place.
  final String withoutPlace;

  /// The last section of a grouping by camera.
  final String withoutCamera;

  /// A month as its heading reads it, "July 2021".
  final String Function(DateTime month) monthText;

  const SearchGroupTexts({
    required this.withoutDate,
    required this.withoutPlace,
    required this.withoutCamera,
    required this.monthText,
  });

  /// The words of [l10n], the month in its language.
  factory SearchGroupTexts.of(AppLocalizations l10n) {
    DateFormat? format;
    try {
      format = DateFormat.yMMMM(l10n.localeName);
    } catch (_) {
      // The month names of a language are loaded with the app's
      // localizations; outside of it, the month in digits.
      format = null;
    }
    return SearchGroupTexts(
      withoutDate: l10n.searchWithoutDate,
      withoutPlace: l10n.searchWithoutPlace,
      withoutCamera: l10n.searchWithoutCamera,
      monthText: format?.format ??
          (month) => "${month.year}-${month.month.toString().padLeft(2, "0")}",
    );
  }
}

/// The place kind a place key is read from, `null` for the other keys.
PlaceKind? searchPlaceKindOf(SearchGroup key) => switch (key) {
      SearchGroup.country => PlaceKind.country,
      SearchGroup.region => PlaceKind.adm1,
      SearchGroup.town => PlaceKind.place,
      _ => null,
    };

/// [parts] grouped by [keys], see the library doc: the photos re-ordered into
/// sections ([headingSection]) by the first key and, where there is a second,
/// subsections ([headingSubsection]) by it, each headed by a new [Heading].
///
/// [parts] are the result as the server answered it, by date, or a grouping
/// of it: where it holds headings they are dropped and the photos put back
/// into the order of the result ([resultOrder]), so that grouping a grouped
/// list again — by the same keys, by others or by none — answers what
/// grouping the result would. Without a key and without a heading, the
/// photos as they are. [parts] is never changed; the photos in the answer are
/// the very same objects.
List<AlbumPart> groupSearchParts(
  List<AlbumPart> parts,
  List<SearchGroup> keys, {
  required SearchGroupTexts texts,
  LocalTime localTime = deviceLocalTime,
}) {
  var images = [
    for (var part in parts)
      if (part is! Heading) part,
  ];
  if (images.length != parts.length) {
    images = resultOrder(images);
  }
  var honoured = keys.take(maxSearchGroups).toList();
  if (honoured.isEmpty) {
    return images;
  }
  return _Grouper(texts, localTime).group(images, honoured, headingSection);
}

/// [images] in the order the server answers a search in (`PhotoSearch.ORDER`):
/// by date, the photos without one last, then by name — the path below the
/// folder searched. A group counts as its representative.
List<AlbumPart> resultOrder(List<AlbumPart> images) {
  int dateOf(AlbumPart part) =>
      part is AbstractImage ? ToImage.toImage(part).date : 0;
  String nameOf(AlbumPart part) =>
      part is AbstractImage ? ToImage.toImage(part).name : "";
  var indexed = images.indexed.toList();
  indexed.sort((a, b) {
    var da = dateOf(a.$2);
    var db = dateOf(b.$2);
    if (da != db) {
      if (da == 0) {
        return 1;
      }
      if (db == 0) {
        return -1;
      }
      return da.compareTo(db);
    }
    var byName = nameOf(a.$2).compareTo(nameOf(b.$2));
    // Stable where nothing else decides.
    return byName != 0 ? byName : a.$1.compareTo(b.$1);
  });
  return [for (var (_, part) in indexed) part];
}

/// The value of a key a photo has: what it is compared by ([id]), what it is
/// sorted by ([order]) and what its heading reads ([text]).
class _Value {
  final Object id;
  final Comparable<Object> order;
  final String text;

  const _Value(this.id, this.order, this.text);
}

/// A sort key of several parts, compared one after the other.
class _Order implements Comparable<Object> {
  final List<Comparable<Object>> parts;

  const _Order(this.parts);

  @override
  int compareTo(Object other) {
    var those = (other as _Order).parts;
    for (var i = 0; i < parts.length && i < those.length; i++) {
      var result = parts[i].compareTo(those[i]);
      if (result != 0) {
        return result;
      }
    }
    return parts.length.compareTo(those.length);
  }
}

class _Grouper {
  final SearchGroupTexts texts;
  final LocalTime localTime;

  _Grouper(this.texts, this.localTime);

  /// [images] by `keys.first` at [level], the rest of [keys] one level below.
  List<AlbumPart> group(
    List<AlbumPart> images,
    List<SearchGroup> keys,
    int level,
  ) {
    var key = keys.first;
    var rest = keys.sublist(1);
    var buckets = <Object, (_Value, List<AlbumPart>)>{};
    var without = <AlbumPart>[];
    for (var part in images) {
      var value = valueOf(part, key);
      if (value == null) {
        without.add(part);
      } else {
        buckets.putIfAbsent(value.id, () => (value, []));
        buckets[value.id]!.$2.add(part);
      }
    }
    var sorted = buckets.values.toList()
      ..sort((a, b) => a.$1.order.compareTo(b.$1.order));

    List<AlbumPart> section(String text, List<AlbumPart> members) {
      var heading = Heading(text: text, level: level);
      if (key == SearchGroup.album && members.isNotEmpty) {
        var first = members.first;
        if (first is AbstractImage) {
          // Where the server said where the photos lie: to a member alone.
          var folder = sourceFolderOf(ToImage.toImage(first));
          if (folder != null) {
            _headingAlbums[heading] = folder;
          }
        }
      }
      return [
        heading,
        ...rest.isEmpty ? members : group(members, rest, level + 1),
      ];
    }

    return [
      for (var (value, members) in sorted) ...section(value.text, members),
      if (without.isNotEmpty) ...section(withoutText(key), without),
    ];
  }

  /// The heading of the photos without [key].
  String withoutText(SearchGroup key) => switch (key) {
        SearchGroup.year || SearchGroup.month => texts.withoutDate,
        SearchGroup.camera => texts.withoutCamera,
        SearchGroup.country ||
        SearchGroup.region ||
        SearchGroup.town =>
          texts.withoutPlace,
        // Every photo lies in an album.
        SearchGroup.album => "",
      };

  /// The value of [key] of [part], `null` where the photo does not have it.
  _Value? valueOf(AlbumPart part, SearchGroup key) {
    if (part is! AbstractImage) {
      return null;
    }
    var image = ToImage.toImage(part);
    switch (key) {
      case SearchGroup.album:
        var folder = folderOf(image);
        var path = folder.join("/");
        return _Value(
          path,
          _Order([for (var segment in folder) _Text(segment)]),
          folder.isEmpty ? "/" : path,
        );
      case SearchGroup.year:
        var day = localDayOf(image.date, localTime: localTime);
        return day == null
            ? null
            : _Value(day.year, _Number(day.year), "${day.year}");
      case SearchGroup.month:
        var day = localDayOf(image.date, localTime: localTime);
        if (day == null) {
          return null;
        }
        var month = day.year * 12 + day.month - 1;
        return _Value(
          month,
          _Number(month),
          texts.monthText(DateTime(day.year, day.month)),
        );
      case SearchGroup.camera:
        var camera = image.camera.trim();
        return camera.isEmpty ? null : _Value(camera, _Text(camera), camera);
      case SearchGroup.country:
      case SearchGroup.region:
      case SearchGroup.town:
        var kind = searchPlaceKindOf(key);
        for (var tag in image.places?.tags ?? const <PlaceTag>[]) {
          if (tag.kind == kind && tag.name.isNotEmpty) {
            // A search answers its photographs slim (issue #243): a place
            // tag carries its name and kind alone, the id 0. There the name
            // is the place; where there is an id, two towns of one name stay
            // apart.
            return _Value(
              tag.geonameId != 0 ? tag.geonameId : (kind, tag.name),
              _Order([_Text(tag.name), _Number(tag.geonameId)]),
              tag.name,
            );
          }
        }
        return null;
    }
  }

  /// The folder [image] lies in: where the server named it ([ImagePart.ref]),
  /// otherwise the path of its name below the folder searched.
  static List<String> folderOf(ImagePart image) {
    var folder = sourceFolderOf(image);
    if (folder != null) {
      return folder;
    }
    var segments = image.name.split("/");
    return segments.sublist(0, segments.length - 1);
  }
}

/// A number as a sort key.
class _Number implements Comparable<Object> {
  final int value;

  const _Number(this.value);

  @override
  int compareTo(Object other) => value.compareTo((other as _Number).value);
}

/// A name as a sort key: alphabetical, ignoring case and the accents of the
/// Latin letters ("Österreich" beside "Oman", not after "Zypern"), the exact
/// spelling deciding a tie.
class _Text implements Comparable<Object> {
  final String value;

  final String folded;

  _Text(this.value) : folded = _fold(value);

  @override
  int compareTo(Object other) {
    var that = other as _Text;
    var result = folded.compareTo(that.folded);
    return result != 0 ? result : value.compareTo(that.value);
  }

  static String _fold(String text) {
    var buffer = StringBuffer();
    for (var rune in text.toLowerCase().runes) {
      var char = String.fromCharCode(rune);
      buffer.write(_plain[char] ?? char);
    }
    return buffer.toString();
  }

  static const Map<String, String> _plain = {
    "à": "a", "á": "a", "â": "a", "ã": "a", "ä": "a", "å": "a", "æ": "ae",
    "ç": "c", "č": "c", "ć": "c", "è": "e", "é": "e", "ê": "e", "ë": "e",
    "ì": "i", "í": "i", "î": "i", "ï": "i", "ñ": "n", "ò": "o", "ó": "o",
    "ô": "o", "õ": "o", "ö": "o", "ø": "o", "œ": "oe", "ß": "ss", "š": "s",
    "ù": "u", "ú": "u", "û": "u", "ü": "u", "ý": "y", "ÿ": "y", "ž": "z",
  };
}
