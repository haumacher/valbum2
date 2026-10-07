// Probe of issue #238: grouping a selection that ends inside an existing
// section leaves the photos after it in that section, as they were.
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/group_by.dart';
import 'package:valbum_ui/resource.dart';

import 'group_by_test.dart' as g;

void main() {
  test("the photos after a grouped selection stay in their own section", () {
    var a = g.photo("a", 14, g.inTown(g.florence), hour: 9);
    var b = g.photo("b", 14, g.inTown(g.florence), hour: 10);
    var c = g.photo("c", 14, g.inTown(g.siena), hour: 11);
    var d = g.photo("d", 14, g.inTown(g.pisa), hour: 12);
    var parts = <AlbumPart>[
      Heading(text: "Tuscany trip", level: headingSection),
      a,
      b,
      c,
      d,
    ];
    var result = g.plan(parts,
        sectionKey: GroupKey.town, selection: {b, c});
    var shown = g.names(result.parts);
    // d was under "Tuscany trip" and must still be.
    var dAt = shown.indexOf("d");
    var headingOfD = shown
        .sublist(0, dAt)
        .lastWhere((name) => name.startsWith("# "));
    expect(headingOfD, "# Tuscany trip", reason: "$shown");
    expect(shown.sublist(0, 2), ["# Tuscany trip", "a"]);
  });
}
