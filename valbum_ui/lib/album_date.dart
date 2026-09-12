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
  var fromName =
      folderNameDate(folderName ?? folderNameOf(album.path));
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
