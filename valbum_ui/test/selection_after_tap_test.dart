/// The one decision of a click on a tile, issue #160: [selectionAfterTap],
/// which the album's edit mode and the inbox both take their clicks from.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_edit.dart';

/// A part of the table below: an object compared by identity, as the parts of
/// an album are.
class P {
  final String name;
  P(this.name);
  @override
  String toString() => name;
}

/// A part equal to every other of the same name, which a selection must still
/// tell apart.
class Same {
  final String name;
  Same(this.name);
  @override
  bool operator ==(Object other) => other is Same && other.name == name;
  @override
  int get hashCode => name.hashCode;
}

void main() {
  var a = P("a"), b = P("b"), c = P("c"), d = P("d"), e = P("e");
  var ordered = [a, b, c, d, e];

  Set<P> run({
    required P tapped,
    required Set<P> current,
    P? anchor,
    bool shift = false,
    bool ctrl = false,
  }) =>
      selectionAfterTap<P>(
        ordered: ordered,
        tapped: tapped,
        current: current,
        anchor: anchor,
        shift: shift,
        ctrl: ctrl,
      ).selection;

  // (what, tapped, current, anchor, shift, ctrl, expected)
  var table = <(String, P, Set<P>, P?, bool, bool, Set<P>)>[
    ("a plain click replaces", c, {a, b}, a, false, false, {c}),
    (
      "a plain click on one of several keeps only it",
      a,
      {a, b},
      b,
      false,
      false,
      {a}
    ),
    ("a plain click on the only selected clears", c, {c}, c, false, false, {}),
    (
      "a plain click on nothing selected selects",
      c,
      {},
      null,
      false,
      false,
      {c}
    ),
    ("ctrl adds", c, {a}, a, false, true, {a, c}),
    ("ctrl removes", a, {a, c}, c, false, true, {c}),
    ("ctrl on the only selected clears", a, {a}, a, false, true, {}),
    ("shift runs forward from the anchor", d, {b}, b, true, false, {b, c, d}),
    ("shift runs backward from the anchor", a, {c}, c, true, false, {a, b, c}),
    (
      "shift keeps what was selected elsewhere",
      e,
      {a, c},
      c,
      true,
      false,
      {a, c, d, e}
    ),
    (
      "shift deselects from an anchor clicked off",
      d,
      {a, c, d},
      b,
      true,
      false,
      {a}
    ),
    (
      "shift without an anchor is a plain click",
      d,
      {a},
      null,
      true,
      false,
      {d}
    ),
    (
      "shift with an anchor no longer shown is a plain click",
      d,
      {a},
      P("gone"),
      true,
      false,
      {d}
    ),
    ("shift wins over ctrl", d, {b}, b, true, true, {b, c, d}),
  ];

  for (var (what, tapped, current, anchor, shift, ctrl, expected) in table) {
    test(what, () {
      var before = Set.of(current);
      expect(
        run(
          tapped: tapped,
          current: current,
          anchor: anchor,
          shift: shift,
          ctrl: ctrl,
        ),
        expected,
      );
      // The current selection is never changed in place.
      expect(current, before);
    });
  }

  test('every click makes the tapped part the next anchor', () {
    for (var (shift, ctrl) in [
      (false, false),
      (false, true),
      (true, false),
    ]) {
      var outcome = selectionAfterTap<P>(
        ordered: ordered,
        tapped: d,
        current: {b},
        anchor: b,
        shift: shift,
        ctrl: ctrl,
      );
      expect(outcome.anchor, same(d));
    }
  });

  test('compares by identity, not by equality', () {
    // Two distinct parts that happen to be equal are two tiles.
    var one = Same("x"), twin = Same("x");
    var outcome = selectionAfterTap<Same>(
      ordered: [one, twin],
      tapped: twin,
      current: Set.identity()..add(one),
      anchor: one,
      shift: true,
    );
    expect(outcome.selection, hasLength(2));
  });
}
