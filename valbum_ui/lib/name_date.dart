/// The recording time a file name carries, the Dart twin of the server's
/// `ImageData.nameDate` (issue #102).
///
/// A video re-encoded by a messenger or exported by an app has no container
/// time, and a JPEG without EXIF data says nothing either; both then land on
/// the file's modification time, which is the time of the *copy*. The name
/// usually still says when the recording happened — `VID_20240315_142233.mp4`,
/// `PXL_20240315_142233123.mp4`, `WhatsApp Video 2024-03-15 at 14.22.33.mp4`.
///
/// The server reads that when it first sees a file. This function is the same
/// rule in the app, so that the "Correct time" dialog of issue #77 can offer
/// the name's time for an album whose parts were dated before the rule
/// existed — *without* a protocol field and without a round trip. The two
/// implementations are pinned by one shared fixture table,
/// `image-server/src/test/fixtures/name-dates.json`, read by both toolchains'
/// tests.
library;

/// The earliest year a date in a file name is taken for a recording time.
///
/// The same bound the server uses (`ImageData.EARLIEST_RECORDING`), and for
/// the same reason: a run of digits that reads as 1970 or 1904 is a counter or
/// an unset field, not a recording.
const int earliestNameYear = 1990;

/// The date forms a file name is searched for, see [nameDate].
///
/// One alternation, tried in this order at every position of the name, so that
/// the longest and most specific form wins where several would match at the
/// same place:
///
///  1. `YYYYMMDD`, an optional `_`, `-` or blank, and `HHMMSS` followed by any
///     further digits — the sub-second digits of `PXL_20240315_142233123.mp4`
///     and the shape of `VID_`, `IMG_`, `Screenshot_` and a bare
///     `20240315_142233.mp4`;
///  2. `YYYY-MM-DD`, a separator of `" at "`, `_`, a blank or `T`, and a time
///     whose two separators are the same character (`.`, `-`, `:` or nothing);
///  3. a bare `YYYY-MM-DD` or `YYYYMMDD` not followed by a further digit —
///     that day at midnight.
///
/// Every form that begins with an undelimited run of digits is guarded by
/// `(?<!\d)`: a date starts where a digit run starts, so a sixteen-digit
/// counter cannot be read as a date by chopping four digits off its front.
///
/// Named groups rather than the server's numbered ones: the second form needs
/// a back-reference to its own separator, and `\k<sep>` says what it means
/// where `\11` would have to be counted out.
final RegExp _nameDate = RegExp(
  // YYYYMMDD [sep] HHMMSS [further digits]
  r"(?<!\d)(?<y1>\d{4})(?<m1>\d{2})(?<d1>\d{2})[_\- ]?"
  r"(?<h1>\d{2})(?<i1>\d{2})(?<s1>\d{2})\d*"
  // YYYY-MM-DD [sep] HH[.:-]MM[.:-]SS [further digits]
  r"|(?<!\d)(?<y2>\d{4})-(?<m2>\d{2})-(?<d2>\d{2})(?: at |[_ T])"
  r"(?<h2>\d{2})(?<sep>[.:\-]?)(?<i2>\d{2})\k<sep>(?<s2>\d{2})\d*"
  // A bare day, in either spelling.
  r"|(?<!\d)(?<y3>\d{4})-(?<m3>\d{2})-(?<d3>\d{2})(?!\d)"
  r"|(?<!\d)(?<y4>\d{4})(?<m4>\d{2})(?<d4>\d{2})(?!\d)",
);

/// The recording time the given file name says, `null` when it says none.
///
/// The first match of the grammar above that is a *real* date wins, the search
/// going on one character further where it is not: a name may well carry a
/// number in front of the date, and a run of digits that is no date must not
/// stop the one that is.
///
/// A date is real when its year lies between [earliestNameYear] and the year
/// after the current one — nothing in an album was recorded before that, and
/// nothing was recorded the year after next — and when the day exists and the
/// time is a time of day.
///
/// The answer is a **local** [DateTime], exactly as the server reads the name
/// in its own default zone: the name carries no zone, so anything else would
/// only move the error around.
DateTime? nameDate(String fileName) {
  var from = 0;
  while (from <= fileName.length) {
    RegExpMatch? match;
    // The equivalent of the server's `matcher.find(from)`: `allMatches` is
    // lazy and searches the *whole* string from the given index, so the
    // lookbehind still sees what stands in front of the match.
    for (var candidate in _nameDate.allMatches(fileName, from)) {
      match = candidate;
      break;
    }
    if (match == null) {
      return null;
    }
    var date = _dateOf(match);
    if (date != null) {
      return date;
    }
    from = match.start + 1;
  }
  return null;
}

/// The date of the alternative that matched, `null` when those digits are no
/// date.
DateTime? _dateOf(RegExpMatch match) {
  if (match.namedGroup("y1") != null) {
    return _at(match, "y1", "m1", "d1", "h1", "i1", "s1");
  }
  if (match.namedGroup("y2") != null) {
    return _at(match, "y2", "m2", "d2", "h2", "i2", "s2");
  }
  if (match.namedGroup("y3") != null) {
    return _at(match, "y3", "m3", "d3", null, null, null);
  }
  return _at(match, "y4", "m4", "d4", null, null, null);
}

/// The date the given groups spell, `null` when it is none.
///
/// `DateTime` does not refuse an impossible date, it rolls it over — the 30th
/// of February becomes the 1st of March — so the answer is checked back
/// against the digits it was built from, which is how a rolled-over date is
/// told apart from a real one.
DateTime? _at(
  RegExpMatch match,
  String year,
  String month,
  String day,
  String? hour,
  String? minute,
  String? second,
) {
  var y = _number(match, year);
  if (y < earliestNameYear || y > DateTime.now().year + 1) {
    return null;
  }
  var mo = _number(match, month);
  var d = _number(match, day);
  var h = _number(match, hour);
  var mi = _number(match, minute);
  var s = _number(match, second);
  var result = DateTime(y, mo, d, h, mi, s);
  if (result.year != y ||
      result.month != mo ||
      result.day != d ||
      result.hour != h ||
      result.minute != mi ||
      result.second != s) {
    // A month, a day of a month, or a time of day that does not exist: these
    // digits are no date, and the search goes on.
    return null;
  }
  return result;
}

/// The number the given group holds, zero for a group the form has not got.
int _number(RegExpMatch match, String? group) =>
    group == null ? 0 : int.parse(match.namedGroup(group)!);
