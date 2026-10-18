/// The rule of `key_guard.dart` (issue #306), kept by reading the source as
/// `action_guard_scan_test.dart` keeps its own: every key handler of the app
/// goes through `guardKeys` or `PageShortcuts`, so that no shortcut takes a
/// key out of a focused text field.
///
///  * a `Focus` (or `FocusNode`) takes its `onKeyEvent:` as
///    `guardKeys(...)`, and no handler is set on a node afterwards;
///  * no `Shortcuts`, `CallbackShortcuts`, `KeyboardListener` or handler of
///    `HardwareKeyboard` binds keys around the guard — `PageShortcuts` does.
library;

import 'package:flutter_test/flutter_test.dart';

import 'action_guard_scan_test.dart' show blankComments, nameOf, viewFiles;

/// The key handlers that are a text field's own, not a page's, by file: each
/// with its reason. They are counted, so a second one in such a file is seen.
const Map<String, Map<String, String>> fieldKeys = {
  "filter_list.dart": {
    "onKeyEvent:": "the filter field's own Escape, which clears its text",
    "Shortcuts(": "the arrows and Enter of a chooser, around its dialog: they "
        "walk the list the field narrows while the field has the focus",
  },
};

/// The ways of binding a key that bypass the guard.
final RegExp rawBinding = RegExp(
  r"\b(Shortcuts|CallbackShortcuts|KeyboardListener|RawKeyboardListener)\s*\(|"
  r"\b(addHandler)\s*\(|"
  r"\b(onKeyEvent)\s*=[^=>]|"
  r"\b(onKey)\s*:",
);

/// A named argument `onKeyEvent:`; what follows it must be `guardKeys(`.
final RegExp keyArgument = RegExp(r"\bonKeyEvent\s*:\s*");

/// The places of [source] binding keys around the guard, as `line: what`.
List<String> unguardedKeys(String source) {
  var code = blankComments(source);
  String lineOf(int offset) =>
      "${"\n".allMatches(code.substring(0, offset)).length + 1}";
  return [
    for (var match in rawBinding.allMatches(code))
      "${lineOf(match.start)}: ${_what(match)}",
    for (var match in keyArgument.allMatches(code))
      if (!code.startsWith(RegExp(r"guardKeys\s*\("), match.end))
        "${lineOf(match.start)}: onKeyEvent:",
  ];
}

String _what(RegExpMatch match) {
  for (var group = 1; group <= match.groupCount; group++) {
    var name = match.group(group);
    if (name != null) {
      return switch (name) {
        "onKeyEvent" => "onKeyEvent=",
        "onKey" => "onKey:",
        _ => "$name(",
      };
    }
  }
  return match.group(0)!;
}

void main() {
  test('every key handler of the app goes through the guard', () {
    var offenders = <String>[];
    for (var file in viewFiles()) {
      var name = nameOf(file);
      var allowed = fieldKeys[name] ?? const {};
      var places = unguardedKeys(file.readAsStringSync());
      var counts = <String, int>{};
      for (var place in places) {
        var what = place.substring(place.indexOf(": ") + 2);
        counts[what] = (counts[what] ?? 0) + 1;
        if (!allowed.containsKey(what) || counts[what]! > 1) {
          offenders.add("${file.path}:$place");
        }
      }
      for (var what in allowed.keys) {
        expect(counts[what], 1,
            reason: "$name still has the one $what of fieldKeys");
      }
    }
    expect(offenders, isEmpty,
        reason: "bind a page's keys with guardKeys or PageShortcuts "
            "(key_guard.dart), or name a text field's own handler in "
            "fieldKeys with the reason");
  });

  test('the key scan sees a handler around the guard', () {
    expect(unguardedKeys("""
Widget f() => Focus(
  onKeyEvent: (node, event) => KeyEventResult.handled,
  child: x,
);
"""), ["2: onKeyEvent:"]);
    expect(unguardedKeys("Widget f() => Focus(onKeyEvent: onKey, child: x);"),
        ["1: onKeyEvent:"]);
    expect(
        unguardedKeys(
            "Widget f() => Focus(onKeyEvent: guardKeys(onKey), child: x);"),
        isEmpty);
    expect(
        unguardedKeys(
            "Widget f() => CallbackShortcuts(bindings: b, child: x);"),
        ["1: CallbackShortcuts("]);
    expect(unguardedKeys("Widget f() => Shortcuts(shortcuts: s, child: x);"),
        ["1: Shortcuts("]);
    expect(unguardedKeys("Widget f() => PageShortcuts(bindings: b, child: x);"),
        isEmpty);
    expect(
        unguardedKeys("void f() => HardwareKeyboard.instance.addHandler(h);"),
        ["1: addHandler("]);
    expect(
        unguardedKeys("void f() { node.onKeyEvent = h; }"), ["1: onKeyEvent="]);
    expect(unguardedKeys("Widget f() => KeyboardListener(onKeyEvent: h);"),
        ["1: KeyboardListener(", "1: onKeyEvent:"]);
    // A field of that name, a comment and a string are none.
    expect(unguardedKeys("""
/// Asked first ([onKeyEvent]), see CallbackShortcuts(...).
final FocusOnKeyEventCallback? onKeyEvent;
var s = "onKeyEvent: x";
"""), isEmpty);
  });
}
