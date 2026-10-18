/// The one way a page binds keys (issue #306): every key handler of the app
/// goes through [guardKeys] or [PageShortcuts], and no shortcut takes a key
/// while a text field has the focus — kept by `key_guard_scan_test.dart`.
///
/// A key a page reports as handled never reaches the text input: on the
/// desktop and on the web the engine gives a key to the text field only when
/// no handler of the focus tree took it. A shortcut of the album (`-`) took
/// a German `ß` that way out of the search field the album's grid is shown
/// below.
///
/// And a printable shortcut is bound to the *character* a key types
/// ([typedKey], [CharacterActivator]), never to its [LogicalKeyboardKey]:
/// Flutter names a key that types something outside ASCII after the US key
/// at its place, so a German `ß` arrives as [LogicalKeyboardKey.minus] and
/// `ä` as [LogicalKeyboardKey.quoteSingle].
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Whether the keyboard focus is in a text field right now: then every key
/// is the text field's, and no page takes any of them.
///
/// Asked from focus listeners as well, while a closing dialog or page is
/// taken out of the tree: a focus whose widget is gone is no text field.
bool get typingIntoText {
  var context = FocusManager.instance.primaryFocus?.context;
  if (context == null || !context.mounted) {
    return false;
  }
  return context.widget is EditableText ||
      context.findAncestorWidgetOfExactType<EditableText>() != null;
}

/// [handler] as the key handler of a page's [Focus]: asked for no key while
/// a text field has the focus ([typingIntoText]), which keeps its letters,
/// its arrows, its `Enter` and its `Escape`.
FocusOnKeyEventCallback guardKeys(FocusOnKeyEventCallback handler) =>
    (node, event) =>
        typingIntoText ? KeyEventResult.ignored : handler(node, event);

/// Whether [event] types [character] — the key of a printable shortcut,
/// whatever key of whichever layout types it — or, where given, is the
/// [numpad] key of it, which types the same.
bool typedKey(KeyEvent event, String character, {LogicalKeyboardKey? numpad}) =>
    event.character == character ||
    (numpad != null && event.logicalKey == numpad);

/// [CallbackShortcuts] through [guardKeys]: the [bindings] act for a key
/// pressed anywhere below, except in a text field. A printable key is bound
/// by its [CharacterActivator].
class PageShortcuts extends StatelessWidget {
  final Map<ShortcutActivator, VoidCallback> bindings;

  final Widget child;

  const PageShortcuts({super.key, required this.bindings, required this.child});

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    var keyboard = HardwareKeyboard.instance;
    for (var entry in bindings.entries) {
      if (entry.key.accepts(event, keyboard)) {
        entry.value();
        return KeyEventResult.handled;
      }
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) => Focus(
        canRequestFocus: false,
        skipTraversal: true,
        onKeyEvent: guardKeys(_onKey),
        child: child,
      );
}
