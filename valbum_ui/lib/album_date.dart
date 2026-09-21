/// When an album happened: the date the app shows, where that date comes
/// from, and the order a listing is read in, see issue #48.
///
/// This library is pure — it knows nothing about widgets or the transport —
/// so the two rules it holds can be unit-tested on their own:
///
/// * [describeDateSource] answers where the date on the screen comes from, so
///   the album properties can say it instead of showing a date out of nowhere.
/// * [sortedFolders] is the order the server's `BY_DATE` comparator produces
///   (`ResourceCache.BY_DATE`): the newest first, the undated behind them by
///   name. The app applies it to what it was given, so a listing from an older
///   server or from the offline cache reads like a current one.
///
/// Two dates travel on the wire and only one is ever stored: [AlbumInfo.date]
/// is the explicit date the author set, [AlbumInfo.effectiveDate] and
/// [FolderInfo.effectiveDate] are derived by the server on every read. The app
/// therefore never *sets* an effective date; it only reads one.
library;

import 'package:intl/intl.dart';

import 'resource.dart';

/// Where the date an album is shown and sorted under comes from.
enum DateSource {
  /// Nothing says when the album happened.
  none,

  /// The author set the date, see [AlbumInfo.date].
  explicit,

  /// The album's folder name begins with the date.
  folderName,

  /// The date of the earliest photo of the album.
  photos,
}

/// Where the effective date of [album] comes from.
///
/// The server derives [AlbumInfo.effectiveDate] in this order: the explicit
/// [AlbumInfo.date], else the leading date of the album's folder name, else
/// the earliest image date (see `AlbumDate.ofAlbum`). The app cannot see the
/// image dates of a folder it has not opened, but it can see the two cheap
/// sources — and what is neither of them is the third.
///
/// [folderName] is the name of the folder the album lives in. The server does
/// not fill [Resource.path] of a loaded album, so the view passes the last
/// segment of the path it is showing; the album's own path is the fallback.
DateSource describeDateSource(AlbumInfo album, {String? folderName}) {
  if (album.date != 0) {
    return DateSource.explicit;
  }
  if (album.effectiveDate == 0) {
    return DateSource.none;
  }
  var fromName = folderNameDate(folderName ?? folderNameOf(album.path));
  if (fromName != null &&
      fromName.millisecondsSinceEpoch == album.effectiveDate) {
    return DateSource.folderName;
  }
  return DateSource.photos;
}

/// The name of the folder a resource path addresses, the last segment.
///
/// A resource path may name the album with more than one segment (or with
/// none, at the root); the date is written on the folder itself.
String folderNameOf(String path) {
  var segments = path.split("/").where((part) => part.isNotEmpty).toList();
  return segments.isEmpty ? "" : segments.last;
}

/// The separators a date in a folder name may use, as `AlbumDate.SEP` does.
const String _sep = r"[-_.\s]";

/// The date at the start of a folder name: `YYYY`, `YYYY-MM` or
/// `YYYY-MM-DD`, anchored at the start and stopped before a further digit, so
/// that `20200524 Trip` is not read as the year 2020.
final RegExp _leadingDate =
    RegExp("^(\\d{4})(?:$_sep(\\d{2})(?:$_sep(\\d{2}))?)?(?![0-9])");

/// The date at the start of [folderName], `null` if it does not start with
/// one.
///
/// A bare year is January 1st, a year and month the first of that month, both
/// at local midnight — exactly what the server's `AlbumDate.ofFolderName`
/// answers, so that a date read here compares with the effective date the
/// server derived from the same name. A date that is no date (`2020-13`,
/// `2020-02-31`) falls back to the part of it that is one.
DateTime? folderNameDate(String folderName) {
  var match = _leadingDate.matchAsPrefix(folderName);
  if (match == null) {
    return null;
  }
  var year = int.parse(match.group(1)!);
  var month = match.group(2) == null ? 0 : int.parse(match.group(2)!);
  var day = match.group(3) == null ? 0 : int.parse(match.group(3)!);

  if (month < 1 || month > 12) {
    return DateTime(year);
  }
  if (day < 1 || day > _daysInMonth(year, month)) {
    return DateTime(year, month);
  }
  return DateTime(year, month, day);
}

int _daysInMonth(int year, int month) =>
    DateTime(year, month + 1).difference(DateTime(year, month)).inDays;

/// The folders of a listing in the order they are shown: the newest first,
/// the undated behind them by name.
///
/// A copy, never the list it was given: the listing the app holds is what the
/// server said, and the order is how it is read out.
List<FolderInfo> sortedFolders(List<FolderInfo> folders) {
  var result = List.of(folders);
  result.sort((a, b) {
    if (a.effectiveDate != b.effectiveDate) {
      // Newest first; an undated folder (0) therefore falls to the end.
      return b.effectiveDate.compareTo(a.effectiveDate);
    }
    return a.name.toLowerCase().compareTo(b.name.toLowerCase());
  });
  return result;
}

/// The date of an album as the app writes it out for a reader, `null` when
/// nothing says when the album happened, see issue #107.
///
/// [effectiveDate] is what the server derived (`AlbumInfo.effectiveDate`,
/// `FolderInfo.effectiveDate`), `0` for none. The form is the locale's own
/// medium date, so the listing tile and the album's own heading say the date
/// the way the rest of the app does — and never the folder-name spelling,
/// which is a naming convention and not a thing to read.
String? albumDateLabel(int effectiveDate) => effectiveDate == 0
    ? null
    : DateFormat.yMMMd()
        .format(DateTime.fromMillisecondsSinceEpoch(effectiveDate));

/// Whether a listing entry is one a date is shown for, see issue #133.
///
/// Only an album happened on a day. A folder of folders carries an
/// [FolderInfo.effectiveDate] all the same — it is what the listing is sorted
/// by, and a folder named `2026` sorts with the year it names — but that is a
/// sort key, not a date: showing it read "2026 - Jan 1 2026".
///
/// [FolderKind.album] is the value an entry from a server that does not know
/// the field yet carries, so such a listing shows its dates exactly as before.
bool folderHasDate(FolderInfo folder) =>
    folder.kind == FolderKind.album && folder.effectiveDate != 0;

/// The spelling of a date in a folder name, the naming convention `yyyy-MM-dd`.
final DateFormat folderDateFormat = DateFormat("yyyy-MM-dd");

/// The folder name an album with [title] taken on [date] is created under —
/// and, since issue #130, the name its folder is renamed to whenever its
/// properties are written.
///
/// `yyyy-MM-dd title` by the naming convention, and the title alone when
/// [date] is `null` — an album without a date is no less an album, it is only
/// one the placement rules leave where it was made, see issue #119. The title
/// is trimmed: a stray blank at either end is a slip of the keyboard, never a
/// name.
///
/// The server composes the very same name (`FolderNames.albumFolderName`), and
/// the two are pinned against each other by the shared table
/// `image-server/src/test/fixtures/folder-names.json`, so a rule changed on
/// one side and not on the other fails the tests of both.
String albumFolderName(DateTime? date, String title) {
  var name = title.trim();
  if (date == null) {
    return name;
  }
  var day = folderDateFormat.format(date);
  return name.isEmpty ? day : "$day $name";
}

/// The name of the folder a folder of folders with [title] carries, see
/// issue #130.
///
/// Its title alone: a folder of folders has no date of its own, only the
/// dates of what lies in it.
String listingFolderName(String title) => title.trim();

/// Whether [name] may be the name of a folder on disk.
///
/// A name that is a path (`a/b`), a name that is a navigation step (`.`,
/// `..`) and a name that hides the folder (`.thing`, which is what the server
/// itself uses for `.valbum` and `.vacache`) are none. The twin of the
/// server's `FolderNames.isLegal`, pinned by the same shared table: the app
/// says so before the round trip, and the server refuses it with a `400`
/// either way.
bool isLegalFolderName(String name) {
  if (name.trim().isEmpty) {
    return false;
  }
  if (name == "." || name == ".." || name.startsWith(".")) {
    return false;
  }
  return !name.contains("/") && !name.contains(r"\") && !name.contains("\u0000");
}
