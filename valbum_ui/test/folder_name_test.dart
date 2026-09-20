/// The Dart twin of the server's `FolderNames`, pinned by the table both
/// toolchains read (issue #130).
///
/// The table is not a copy: it is the very file `TestFolderNames` reads,
/// `image-server/src/test/fixtures/folder-names.json`, so a composition
/// changed on one side and not on the other fails here.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_date.dart';

/// The shared table, relative to `valbum_ui/` — the directory a Flutter test
/// runs in, see `util/fixtures.dart`.
const String tablePath = "../image-server/src/test/fixtures/folder-names.json";

void main() {
  var table = jsonDecode(File(tablePath).readAsStringSync())
      as Map<String, dynamic>;
  var compose = table["compose"] as List<dynamic>;
  var legal = table["legal"] as List<dynamic>;

  group("The shared table of folder names", () {
    test("is not empty", () {
      expect(compose.length, greaterThan(10));
      expect(legal.length, greaterThan(10));
    });

    for (var row in compose) {
      var entry = row as Map<String, dynamic>;
      var date = entry["date"] as String?;
      var title = entry["title"] as String;
      var name = entry["name"] as String;

      test("'$title' on ${date ?? "no day"} is called '$name'", () {
        expect(
          albumFolderName(date == null ? null : DateTime.parse(date), title),
          name,
          reason: "the Java rule and the Dart one must agree on '$title'",
        );
      });
    }

    for (var row in legal) {
      var entry = row as Map<String, dynamic>;
      var name = entry["name"] as String;
      var allowed = entry["legal"] as bool;

      test("'$name' ${allowed ? "may" : "may not"} name a folder", () {
        expect(isLegalFolderName(name), allowed);
      });
    }
  });

  group("A folder name", () {
    test("is the title alone for a folder of folders", () {
      expect(listingFolderName("  Reisen "), "Reisen");
    });

    test("is composed in the local zone, as the server composes it", () {
      // Late in the evening: a date read as UTC would name the next day.
      expect(
        albumFolderName(DateTime(2002, 3, 4, 23, 30), "Schlosspark"),
        "2002-03-04 Schlosspark",
      );
    });
  });
}
