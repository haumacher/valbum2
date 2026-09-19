/// The Dart twin of the server's `ImageData.nameDate`, pinned by the table
/// both toolchains read (issue #102).
///
/// The table is not a copy: it is the very file `TestNameDate` reads,
/// `image-server/src/test/fixtures/name-dates.json`, so a rule that is changed
/// on one side and not on the other fails here.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/name_date.dart';

/// The shared table, relative to `valbum_ui/` — the directory a Flutter test
/// runs in, see `util/fixtures.dart`.
const String tablePath = "../image-server/src/test/fixtures/name-dates.json";

void main() {
  group("The shared table of file-name dates", () {
    var rows = jsonDecode(File(tablePath).readAsStringSync()) as List<dynamic>;

    test("is not empty", () {
      expect(rows.length, greaterThan(20));
    });

    for (var row in rows) {
      var entry = row as Map<String, dynamic>;
      var name = entry["name"] as String;
      var expected = entry["date"] as String?;

      test("'$name' reads as ${expected ?? "no date"}", () {
        expect(
          nameDate(name),
          expected == null ? isNull : DateTime.parse(expected),
          reason: "the Java rule and the Dart one must agree on '$name'",
        );
      });
    }
  });

  group("A date in a file name", () {
    test("is read in the local zone, not in UTC", () {
      var read = nameDate("VID_20240315_142233.mp4")!;
      expect(read.isUtc, isFalse);
      expect(read.hour, 14);
    });

    test("is refused for a year the album cannot hold", () {
      expect(nameDate("${earliestNameYear - 1}0101_120000.mp4"), isNull);
      expect(nameDate("${DateTime.now().year + 2}0101_120000.mp4"), isNull);
    });

    test("is found behind a number that is none", () {
      expect(
        nameDate("99999999999999999999_20240315_142233.mp4"),
        DateTime(2024, 3, 15, 14, 22, 33),
      );
    });

    test("is nothing in an empty name", () {
      expect(nameDate(""), isNull);
    });
  });
}
