/// "Group by…", issue #238: the headings made from an album's photos, as a
/// pure function — exact outputs for the rules of `group_by.dart`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';
import 'package:valbum_ui/group_by.dart';
import 'package:valbum_ui/resource.dart';

// The places of a trip through Tuscany, and of Rome, by GeoNames id.
final PlaceTag italy =
    PlaceTag(geonameId: 3175395, name: "Italy", kind: PlaceKind.country);
final PlaceTag tuscany =
    PlaceTag(geonameId: 3165361, name: "Tuscany", kind: PlaceKind.adm1);
final PlaceTag lazio =
    PlaceTag(geonameId: 3174976, name: "Lazio", kind: PlaceKind.adm1);
final PlaceTag florence =
    PlaceTag(geonameId: 3176959, name: "Florence", kind: PlaceKind.place);
final PlaceTag siena =
    PlaceTag(geonameId: 3166548, name: "Siena", kind: PlaceKind.place);
final PlaceTag pisa =
    PlaceTag(geonameId: 3170647, name: "Pisa", kind: PlaceKind.place);
final PlaceTag rome =
    PlaceTag(geonameId: 3169070, name: "Rome", kind: PlaceKind.place);
final PlaceTag trastevere =
    PlaceTag(geonameId: 6543210, name: "Trastevere", kind: PlaceKind.district);
final PlaceTag monti =
    PlaceTag(geonameId: 6543211, name: "Monti", kind: PlaceKind.district);

/// A photo taken on the given day of July 2026 at the given UTC hour, in the
/// given places (none: no position).
ImagePart photo(String name, int day, List<PlaceTag> places, {int hour = 12}) =>
    ImagePart(
      name: name,
      date: DateTime.utc(2026, 7, day, hour).millisecondsSinceEpoch,
      width: 2048,
      height: 1536,
      places: places.isEmpty ? null : PlaceInfo(tags: places),
    );

/// In Florence, Tuscany, Italy.
List<PlaceTag> inTown(PlaceTag town) => [italy, tuscany, town];

/// Days as `yyyy-mm-dd`, so that an expectation reads the day it means.
String dayText(DateTime day) =>
    "${day.year}-${day.month.toString().padLeft(2, "0")}-"
    "${day.day.toString().padLeft(2, "0")}";

/// The wall clock of a zone [hours] east of UTC, whatever zone the test runs
/// in.
DateTime Function(int) zone(int hours) =>
    (millis) => DateTime.fromMillisecondsSinceEpoch(millis, isUtc: true)
        .add(Duration(hours: hours));

/// UTC itself: the days are the days the fixtures name.
final utc = zone(0);

String nameOf(AlbumPart part) => switch (part) {
      Heading(level: headingSubsection) => "## ${part.text}",
      Heading() => "# ${part.text}",
      ImagePart() => part.name,
      ImageGroup() => "Group(${part.images.map((i) => i.name).join(",")})",
      _ => "?",
    };

List<String> names(List<AlbumPart> parts) => [for (var p in parts) nameOf(p)];

List<String> preview(GroupingPlan plan) =>
    [for (var heading in plan.headings) "$heading"];

GroupingPlan plan(
  List<AlbumPart> parts, {
  GroupMode mode = GroupMode.replace,
  GroupKey? sectionKey = GroupKey.day,
  GroupKey? subsectionKey,
  Set<AlbumPart> selection = const {},
  DateTime Function(int)? localTime,
}) =>
    planGrouping(
      parts,
      mode: mode,
      sectionKey: sectionKey,
      subsectionKey: subsectionKey,
      selection: selection,
      dayText: dayText,
      localTime: localTime ?? utc,
    );

void main() {
  group('Day › Town', () {
    test('a three-day trip, back in the first town on day 3', () {
      var parts = <AlbumPart>[
        photo("f1", 14, inTown(florence), hour: 9),
        photo("f2", 14, inTown(florence), hour: 10),
        photo("s1", 14, inTown(siena), hour: 16),
        photo("s2", 15, inTown(siena), hour: 9),
        photo("p1", 15, inTown(pisa), hour: 15),
        photo("p2", 16, inTown(pisa), hour: 8),
        photo("f3", 16, inTown(florence), hour: 14),
        photo("f4", 16, inTown(florence), hour: 15),
      ];
      var result = plan(parts, subsectionKey: GroupKey.town);

      expect(names(result.parts), [
        "# 2026-07-14",
        "## Florence",
        "f1",
        "f2",
        "## Siena",
        "s1",
        "# 2026-07-15",
        "## Siena",
        "s2",
        "## Pisa",
        "p1",
        "# 2026-07-16",
        "## Pisa",
        "p2",
        // Florence again: a section of its own, never merged with day 1.
        "## Florence",
        "f3",
        "f4",
      ]);
      expect(preview(result), [
        "# 2026-07-14 (3)",
        "## Florence (2)",
        "## Siena (1)",
        "# 2026-07-15 (2)",
        "## Siena (1)",
        "## Pisa (1)",
        "# 2026-07-16 (3)",
        "## Pisa (1)",
        "## Florence (2)",
      ]);
      expect(result.replaced, 0);
      // The photos are the very parts that were there, in their order.
      var images = result.parts.whereType<ImagePart>().toList();
      for (var i = 0; i < images.length; i++) {
        expect(identical(images[i], parts[i]), isTrue);
      }
      // And the input is not changed.
      expect(parts.whereType<Heading>(), isEmpty);
    });

    test('a return to the same town by the same key is one section', () {
      var parts = <AlbumPart>[
        photo("f1", 14, inTown(florence)),
        // Another tag object, the same GeoNames id.
        photo("f2", 14, [
          PlaceTag(geonameId: 3176959, name: "Firenze", kind: PlaceKind.place)
        ]),
      ];
      expect(names(plan(parts, sectionKey: GroupKey.town).parts),
          ["# Florence", "f1", "f2"]);
    });
  });

  group('photos without the key', () {
    test('in the middle and at the start join their neighbours', () {
      var parts = <AlbumPart>[
        photo("n0", 14, []),
        photo("f1", 14, inTown(florence)),
        photo("n1", 14, []),
        photo("n2", 14, []),
        photo("s1", 14, inTown(siena)),
        photo("n3", 14, []),
      ];
      var result = plan(parts, sectionKey: GroupKey.town);

      expect(names(result.parts), [
        "# Florence",
        "n0",
        "f1",
        "n1",
        "n2",
        "# Siena",
        "s1",
        "n3",
      ]);
      expect(preview(result), ["# Florence (4)", "# Siena (2)"]);
    });

    test('at the start of a day join the first town of that day', () {
      var parts = <AlbumPart>[
        photo("f1", 14, inTown(florence)),
        photo("n1", 15, [], hour: 8),
        photo("s1", 15, inTown(siena)),
      ];
      expect(names(plan(parts, subsectionKey: GroupKey.town).parts), [
        "# 2026-07-14",
        "## Florence",
        "f1",
        "# 2026-07-15",
        "## Siena",
        "n1",
        "s1",
      ]);
    });

    test('an undated photo never starts a day', () {
      var undated = ImagePart(name: "u", width: 2048, height: 1536);
      var parts = <AlbumPart>[
        photo("a", 14, []),
        undated,
        photo("b", 15, []),
      ];
      expect(names(plan(parts).parts),
          ["# 2026-07-14", "a", "u", "# 2026-07-15", "b"]);
    });
  });

  group('Day and town on one level', () {
    test('one heading per town of each day, the day alone without one', () {
      var parts = <AlbumPart>[
        photo("f1", 14, inTown(florence), hour: 9),
        photo("n1", 14, [], hour: 10),
        photo("s1", 14, inTown(siena), hour: 16),
        // A morning without position, then Siena again on the next day.
        photo("n2", 15, [], hour: 7),
        photo("s2", 15, inTown(siena), hour: 9),
        // A day in the countryside: no town at all.
        photo("x1", 16, [italy, tuscany]),
      ];
      var result = plan(parts, sectionKey: GroupKey.dayAndTown);

      expect(names(result.parts), [
        "# 2026-07-14 – Florence",
        "f1",
        "n1",
        "# 2026-07-14 – Siena",
        "s1",
        "# 2026-07-15 – Siena",
        "n2",
        "s2",
        "# 2026-07-16",
        "x1",
      ]);
      expect(preview(result), [
        "# 2026-07-14 – Florence (2)",
        "# 2026-07-14 – Siena (1)",
        "# 2026-07-15 – Siena (2)",
        "# 2026-07-16 (1)",
      ]);
    });
  });

  group('keep the sections, add subsections', () {
    test('groups each section on its own and replaces its subsections', () {
      var parts = <AlbumPart>[
        Heading(text: "Rome", level: headingSection),
        Heading(text: "Old", level: headingSubsection),
        photo("t1", 14, [italy, lazio, rome, trastevere]),
        photo("t2", 14, [italy, lazio, rome, trastevere]),
        photo("m1", 14, [italy, lazio, rome, monti]),
        Heading(text: "Rome again", level: headingSection),
        photo("m2", 15, [italy, lazio, rome, monti]),
        photo("n1", 15, []),
        photo("t3", 15, [italy, lazio, rome, trastevere]),
      ];
      var result = plan(
        parts,
        mode: GroupMode.addSubsections,
        subsectionKey: GroupKey.district,
      );

      expect(names(result.parts), [
        "# Rome",
        "## Trastevere",
        "t1",
        "t2",
        "## Monti",
        "m1",
        "# Rome again",
        // Monti again, but in another section: a subsection of its own.
        "## Monti",
        "m2",
        "n1",
        "## Trastevere",
        "t3",
      ]);
      expect(preview(result), [
        "## Trastevere (2)",
        "## Monti (1)",
        "## Monti (2)",
        "## Trastevere (1)",
      ]);
      // "Old" is gone; the two sections are the very headings there were.
      expect(result.replaced, 1);
      expect(identical(result.parts.first, parts.first), isTrue);
      expect(identical(result.parts[6], parts[5]), isTrue);
    });

    test('leaves a section none of whose photos has the key as it is', () {
      var old = Heading(text: "Indoors", level: headingSubsection);
      var parts = <AlbumPart>[
        Heading(text: "Day 1", level: headingSection),
        old,
        photo("n1", 14, []),
        Heading(text: "Day 2", level: headingSection),
        photo("t1", 15, [italy, lazio, rome, trastevere]),
      ];
      var result = plan(
        parts,
        mode: GroupMode.addSubsections,
        subsectionKey: GroupKey.district,
      );
      expect(names(result.parts), [
        "# Day 1",
        "## Indoors",
        "n1",
        "# Day 2",
        "## Trastevere",
        "t1",
      ]);
      expect(result.replaced, 0);
    });
  });

  group('a selection', () {
    test('groups two separate stretches, the photos between untouched', () {
      var outside = Heading(text: "Outside", level: headingSection);
      var parts = <AlbumPart>[
        photo("a1", 14, inTown(florence)),
        photo("a2", 14, inTown(siena)),
        photo("a3", 14, inTown(pisa)),
        outside,
        photo("x1", 15, inTown(pisa)),
        photo("x2", 15, inTown(siena)),
        photo("b1", 16, inTown(florence)),
        photo("b2", 16, inTown(florence)),
        photo("b3", 16, inTown(siena)),
      ];
      var selection = Set<AlbumPart>.identity()
        ..addAll([parts[1], parts[2], parts[6], parts[7]]);
      var result = plan(parts, sectionKey: GroupKey.town, selection: selection);

      expect(names(result.parts), [
        "a1",
        "# Siena",
        "a2",
        "# Pisa",
        "a3",
        "# Outside",
        "x1",
        "x2",
        "# Florence",
        "b1",
        "b2",
        // b3 was under "Outside" and stays there.
        "# Outside",
        "b3",
      ]);
      expect(preview(result), [
        "# Siena (1)",
        "# Pisa (1)",
        "# Florence (2)",
        "# Outside (1)",
      ]);
      expect(identical(result.parts[5], outside), isTrue);
    });

    test('takes the headings right in front of it along', () {
      var parts = <AlbumPart>[
        photo("a1", 14, inTown(florence)),
        Heading(text: "Old day", level: headingSection),
        photo("b1", 15, inTown(siena)),
        photo("b2", 15, inTown(pisa)),
        Heading(text: "After", level: headingSection),
        photo("c1", 16, inTown(pisa)),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[2], parts[3]]);
      var scope = groupScope(parts, selection: selection);
      expect(scope.stretches, [(1, 4)]);
      expect(scope.selected, isTrue);
      expect(scope.images, 2);
      expect(scope.sections, 1);

      var result =
          plan(parts, subsectionKey: GroupKey.town, selection: selection);
      expect(names(result.parts), [
        "a1",
        "# 2026-07-15",
        "## Siena",
        "b1",
        "## Pisa",
        "b2",
        "# After",
        "c1",
      ]);
      expect(result.replaced, 1);
    });

    test('ending inside a subsection restores the section and subsection', () {
      var parts = <AlbumPart>[
        Heading(text: "Tuscany", level: headingSection),
        photo("a", 14, inTown(florence)),
        Heading(text: "Old towns", level: headingSubsection),
        photo("b", 14, inTown(florence)),
        photo("c", 14, inTown(siena)),
        photo("d", 14, inTown(pisa)),
        Heading(text: "Coast", level: headingSubsection),
        photo("e", 15, inTown(pisa)),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[3], parts[4]]);
      var result =
          plan(parts, subsectionKey: GroupKey.town, selection: selection);
      expect(names(result.parts), [
        "# Tuscany",
        "a",
        "# 2026-07-14",
        "## Florence",
        "b",
        "## Siena",
        "c",
        // d keeps the pair it was under.
        "# Tuscany",
        "## Old towns",
        "d",
        "## Coast",
        "e",
      ]);
      expect(preview(result).sublist(3), ["# Tuscany (2)", "## Old towns (1)"]);
    });

    test('adding subsections, ending inside a subsection restores it', () {
      var parts = <AlbumPart>[
        Heading(text: "Rome", level: headingSection),
        Heading(text: "Walk", level: headingSubsection),
        photo("t1", 14, [italy, lazio, rome, trastevere]),
        photo("m1", 14, [italy, lazio, rome, monti]),
        photo("x", 14, [italy, lazio, rome, monti]),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[2], parts[3]]);
      var result = plan(parts,
          mode: GroupMode.addSubsections,
          subsectionKey: GroupKey.district,
          selection: selection);
      // The section is still in effect: the subsection alone comes back.
      expect(names(result.parts), [
        "# Rome",
        "## Trastevere",
        "t1",
        "## Monti",
        "m1",
        "## Walk",
        "x",
      ]);
    });

    test('ending inside a section without subsection restores the section', () {
      var parts = <AlbumPart>[
        Heading(text: "Rome", level: headingSection),
        photo("t1", 14, [italy, lazio, rome, trastevere]),
        photo("m1", 14, [italy, lazio, rome, monti]),
        photo("x", 14, [italy, lazio, rome, monti]),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[1], parts[2]]);
      var result = plan(parts,
          mode: GroupMode.addSubsections,
          subsectionKey: GroupKey.district,
          selection: selection);
      // A subsection is ended by a heading only: the section once more.
      expect(names(result.parts), [
        "# Rome",
        "## Trastevere",
        "t1",
        "## Monti",
        "m1",
        "# Rome",
        "x",
      ]);
    });

    test('ending exactly at the end of its section adds nothing', () {
      var parts = <AlbumPart>[
        Heading(text: "Tuscany", level: headingSection),
        photo("a", 14, inTown(florence)),
        photo("b", 14, inTown(siena)),
        Heading(text: "Coast", level: headingSection),
        photo("c", 15, inTown(pisa)),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[1], parts[2]]);
      var result = plan(parts, sectionKey: GroupKey.town, selection: selection);
      expect(names(result.parts),
          ["# Florence", "a", "# Siena", "b", "# Coast", "c"]);
      expect(preview(result), ["# Florence (1)", "# Siena (1)"]);

      // Nor where only hidden photos follow before the album ends.
      var hidden = photo("h", 15, inTown(pisa))..rating = -2;
      var tail = <AlbumPart>[
        Heading(text: "Tuscany", level: headingSection),
        photo("a", 14, inTown(florence)),
        photo("b", 14, inTown(siena)),
        hidden,
      ];
      var both = Set<AlbumPart>.identity()..addAll([tail[1], tail[2]]);
      expect(
          names(plan(tail, sectionKey: GroupKey.town, selection: both).parts),
          ["# Florence", "a", "# Siena", "b", "h"]);
    });

    test('with no keyed photo gets no heading and changes nothing', () {
      var parts = <AlbumPart>[
        Heading(text: "Kept", level: headingSection),
        photo("n1", 14, []),
        photo("n2", 14, []),
        photo("f1", 14, inTown(florence)),
      ];
      var selection = Set<AlbumPart>.identity()..addAll([parts[1], parts[2]]);
      var result = plan(parts, sectionKey: GroupKey.town, selection: selection);

      expect(result.isEmpty, isTrue);
      expect(result.headings, isEmpty);
      expect(result.replaced, 0);
      expect(names(result.parts), ["# Kept", "n1", "n2", "f1"]);
    });

    test('a group counts by its representative', () {
      var group = ImageGroup(
        images: [
          photo("g-florence", 14, inTown(florence)),
          photo("g-siena", 14, inTown(siena)),
        ],
        representative: 1,
      );
      var parts = <AlbumPart>[photo("f1", 14, inTown(florence)), group];
      // Selected by its representative alone, as a camera selection does.
      var selection = Set<AlbumPart>.identity()
        ..addAll([parts[0], group.images[1]]);
      var result = plan(parts, sectionKey: GroupKey.town, selection: selection);
      expect(names(result.parts),
          ["# Florence", "f1", "# Siena", "Group(g-florence,g-siena)"]);
    });
  });

  group('replace', () {
    test('removes the headings in the scope and says how many', () {
      var parts = <AlbumPart>[
        Heading(text: "Old 1", level: headingSection),
        photo("f1", 14, inTown(florence)),
        Heading(text: "Old 2", level: headingSubsection),
        photo("s1", 14, inTown(siena)),
        Heading(text: "Old 3", level: headingSection),
        photo("s2", 15, inTown(siena)),
      ];
      var scope = groupScope(parts);
      expect(scope.selected, isFalse);
      expect(scope.headings, 3);
      expect(scope.sections, 2);

      var result = plan(parts, sectionKey: GroupKey.town);
      expect(names(result.parts), ["# Florence", "f1", "# Siena", "s1", "s2"]);
      expect(result.replaced, 3);
      expect(preview(result), ["# Florence (1)", "# Siena (2)"]);
    });

    test('carries a trashed photo along without counting it', () {
      var trashed = photo("t", 14, inTown(siena))..rating = -2;
      var parts = <AlbumPart>[
        photo("f1", 14, inTown(florence)),
        trashed,
        photo("f2", 14, inTown(florence)),
      ];
      var result = plan(parts, sectionKey: GroupKey.town);
      expect(names(result.parts), ["# Florence", "f1", "t", "f2"]);
      expect(preview(result), ["# Florence (2)"]);
    });

    test('a video counts like a photo', () {
      var video = photo("v.mp4", 14, inTown(siena))..kind = ImageKind.video;
      var result = plan([photo("f1", 14, inTown(florence)), video],
          sectionKey: GroupKey.town);
      expect(names(result.parts), ["# Florence", "f1", "# Siena", "v.mp4"]);
    });
  });

  group('the place keys', () {
    test('read the tag of their kind', () {
      var parts = <AlbumPart>[
        photo("t1", 14, [
          italy,
          lazio,
          rome,
          trastevere,
          PlaceTag(
              geonameId: 1, name: "Villa Farnesina", kind: PlaceKind.feature),
        ]),
      ];
      String headingOf(GroupKey key) =>
          plan(parts, sectionKey: key).headings.single.text;
      expect(headingOf(GroupKey.town), "Rome");
      expect(headingOf(GroupKey.district), "Trastevere");
      expect(headingOf(GroupKey.region), "Lazio");
      expect(headingOf(GroupKey.country), "Italy");
      expect(headingOf(GroupKey.feature), "Villa Farnesina");
    });
  });

  group('the local day (issue #183)', () {
    test('is cut in the device\'s zone, not in UTC', () {
      // Rome in summer is UTC+2: 22:30 UTC on the 13th is half past midnight
      // on the 14th there, and 23:30 UTC on the 14th is the 15th.
      var parts = <AlbumPart>[
        photo("late", 13, inTown(rome), hour: 22),
        photo("morning", 14, inTown(rome), hour: 6),
        photo("night", 14, inTown(rome), hour: 23),
      ];
      var result = plan(parts, localTime: zone(2));
      expect(names(result.parts),
          ["# 2026-07-14", "late", "morning", "# 2026-07-15", "night"]);

      // The same photos in UTC are cut at other days.
      expect(names(plan(parts, localTime: utc).parts),
          ["# 2026-07-13", "late", "# 2026-07-14", "morning", "night"]);
    });
  });
}
