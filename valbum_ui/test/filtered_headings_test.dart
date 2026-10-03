/// The headings of a filtered view (issue #213), pinned by the table both
/// toolchains read.
///
/// The table is not a copy: it is the very file `TestFilteredHeadings` reads,
/// `image-server/src/test/fixtures/filtered-headings.json`. Every row lists
/// the parts of an album — `H<level> <text>` a heading, `+` a photograph the
/// view shows, `-` one it hides — and the headings the view shows.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_labels.dart';
import 'package:valbum_ui/resource.dart';

/// The shared table, relative to `valbum_ui/`.
const String tablePath =
    "../image-server/src/test/fixtures/filtered-headings.json";

void main() {
  var rows = jsonDecode(File(tablePath).readAsStringSync()) as List<dynamic>;

  test("the table holds its cases", () {
    expect(rows.length, greaterThanOrEqualTo(10));
  });

  for (var row in rows.cast<Map<String, dynamic>>()) {
    test(row["case"], () {
      var shown = <AlbumPart>[];
      for (var token in (row["parts"] as List).cast<String>()) {
        if (token == "+") {
          shown.add(ImagePart(name: "p${shown.length}.jpg"));
        } else if (token != "-") {
          var blank = token.indexOf(" ");
          shown.add(Heading(
            level: int.parse(token.substring(1, blank)),
            text: token.substring(blank + 1),
          ));
        }
      }
      expect(
        [
          for (var part in visibleHeadings(shown))
            if (part is Heading) part.text,
        ],
        (row["shown"] as List).cast<String>(),
      );
    });
  }

  test("a label view keeps the headings of the labeled photos only", () {
    ImagePart photo(String name, [List<String> labels = const []]) => ImagePart(
          name: name,
          labels: [for (var label in labels) LabelName(name: label)],
        );
    var parts = <AlbumPart>[
      Heading(text: "Day 1", level: 1),
      photo("a.jpg", ["Day"]),
      Heading(text: "Morning", level: 2),
      photo("b.jpg"),
      Heading(text: "Day 2", level: 1),
      ImageGroup(images: [photo("c.jpg"), photo("d.jpg", ["Day"])]),
    ];
    var view = labelView(parts, "Day");
    expect([for (var part in view) if (part is Heading) part.text],
        ["Day 1", "Day 2"]);
    expect(view.whereType<AbstractImage>().length, 2,
        reason: "a group is shown where one of its members carries the label");
    expect(labelView(parts, null).length, parts.length,
        reason: "no label, nothing empty: everything stays");
  });
}
