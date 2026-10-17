/// Typing to narrow a list and walking it with the keyboard: one behaviour
/// for every chooser, see issue #261.
///
/// The person chooser of issue #150 had it first; the folder picker, the
/// search view's choosers of issue #243 and the recipient chooser share it
/// since, so that a list of things to choose from is driven the same way
/// wherever it stands:
///
///  * [FilterField] — the field above the list. `Escape` in a filled field
///    clears it; in an empty one it is the dialog's own (it closes). It takes
///    the focus when its dialog opens on a desktop and in a desktop browser,
///    never on a phone, whose keyboard would cover the list it narrows
///    ([filterAutofocus]).
///  * [PickHighlight] — which entry the keyboard stands on: none when the
///    list is shown, moved by the arrows and stopping at either end (it never
///    wraps round: a highlight that jumped from the last entry to the first
///    would pick something far from where the reader is looking), and
///    **reset by every change of the filter**, because a highlight left on an
///    entry the filter removed would pick something nobody can see.
///  * [PickKeys] — the arrows and `Enter` while the typing goes on, the idiom
///    of Flutter's own autocomplete: a single-line field answers neither.
///  * [CrampedScroll] — the dialog content around field and list, which
///    scrolls as a whole where a phone's keyboard leaves too little room.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' as widgets show Action;

/// Whether a filter field takes the focus as its dialog opens: on a desktop
/// and in a desktop browser, not on a phone, where the keyboard it pops up
/// would cover the list it narrows.
bool get filterAutofocus =>
    defaultTargetPlatform != TargetPlatform.android &&
    defaultTargetPlatform != TargetPlatform.iOS;

/// Which entry of a filtered list the keyboard stands on, see the library
/// comment.
///
/// The list itself is the chooser's: it hands over the entries as they stand
/// on the screen with every build ([shown]), and asks [isHighlighted] for
/// each row it draws. [idOf] tells two entries apart across builds.
class PickHighlight<T> extends ChangeNotifier {
  /// What identifies an entry across builds.
  final Object Function(T entry) idOf;

  PickHighlight({required this.idOf});

  List<T> _shown = const [];

  int _index = -1;

  /// Where each row is drawn, so that the highlighted one can be scrolled to.
  final Map<Object, GlobalKey> _rows = {};

  /// The entries as they stand on the screen, in the order they are drawn.
  List<T> get shown => _shown;

  /// Hands over the entries drawn, during a build: nothing is rebuilt here.
  set shown(List<T> entries) {
    _shown = entries;
    if (_index >= entries.length) {
      _index = -1;
    }
  }

  /// The position of the highlighted entry in [shown], `-1` for none.
  int get index => _index;

  /// The entry the keyboard stands on, `null` for none.
  T? get current =>
      _index >= 0 && _index < _shown.length ? _shown[_index] : null;

  /// Whether [entry] is the one the keyboard stands on.
  bool isHighlighted(T entry) {
    var highlighted = current;
    return highlighted != null && idOf(highlighted) == idOf(entry);
  }

  /// Leaves the highlight nowhere: what every change of the filter does.
  void reset() {
    if (_index != -1) {
      _index = -1;
      notifyListeners();
    }
  }

  /// Moves the highlight by [step], never past either end: from nowhere the
  /// first step down lands on the first entry, the first step up on the last.
  void move(int step) {
    if (_shown.isEmpty) {
      return;
    }
    _index = _index < 0
        ? (step > 0 ? 0 : _shown.length - 1)
        : (_index + step).clamp(0, _shown.length - 1);
    notifyListeners();
    // After the frame that marks it: the row may not be laid out before.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      var highlighted = current;
      if (highlighted == null) {
        return;
      }
      var row = _rows[idOf(highlighted)]?.currentContext;
      if (row != null && row.mounted) {
        Scrollable.ensureVisible(row,
            alignment: 0.5, duration: const Duration(milliseconds: 100));
      }
    });
  }

  /// Wraps the row drawn for [entry], so that it can be scrolled to when it
  /// is highlighted.
  Widget row(T entry, Widget child) => KeyedSubtree(
        key: _rows.putIfAbsent(idOf(entry), () => GlobalKey()),
        child: child,
      );

  /// The colour a highlighted [ListTile] is marked with (its
  /// `selectedTileColor`): the tile paints it on the Material it stands on.
  static Color color(BuildContext context) =>
      Theme.of(context).colorScheme.primary.withValues(alpha: 0.12);
}

/// Moves the highlight one entry down.
class _NextEntry extends Intent {
  const _NextEntry();
}

/// Moves the highlight one entry up.
class _PreviousEntry extends Intent {
  const _PreviousEntry();
}

/// Picks what the highlight stands on.
class _PickEntry extends Intent {
  const _PickEntry();
}

/// The arrows and `Enter` of a chooser, see the library comment: placed
/// around the whole dialog, so that they act while the field has the focus.
class PickKeys<T> extends StatelessWidget {
  final PickHighlight<T> highlight;

  /// What `Enter` does, given the entry highlighted (`null` for none).
  final void Function(T? highlighted) onEnter;

  final Widget child;

  const PickKeys({
    super.key,
    required this.highlight,
    required this.onEnter,
    required this.child,
  });

  @override
  Widget build(BuildContext context) => Shortcuts(
        shortcuts: const <ShortcutActivator, Intent>{
          SingleActivator(LogicalKeyboardKey.arrowDown): _NextEntry(),
          SingleActivator(LogicalKeyboardKey.arrowUp): _PreviousEntry(),
          SingleActivator(LogicalKeyboardKey.enter): _PickEntry(),
          SingleActivator(LogicalKeyboardKey.numpadEnter): _PickEntry(),
        },
        child: Actions(
          actions: <Type, widgets.Action<Intent>>{
            _NextEntry: CallbackAction<_NextEntry>(
              onInvoke: (_) {
                highlight.move(1);
                return null;
              },
            ),
            _PreviousEntry: CallbackAction<_PreviousEntry>(
              onInvoke: (_) {
                highlight.move(-1);
                return null;
              },
            ),
            _PickEntry: CallbackAction<_PickEntry>(
              onInvoke: (_) {
                onEnter(highlight.current);
                return null;
              },
            ),
          },
          child: child,
        ),
      );
}

/// The field a list is narrowed with, see the library comment.
class FilterField extends StatelessWidget {
  final TextEditingController controller;

  /// What the field is called.
  final String label;

  /// The highlight of the list it narrows, reset by every change; `null`
  /// where the list is not walked by the keyboard.
  final PickHighlight<Object?>? highlight;

  /// Called with the new text on every change, `Escape` clearing it included.
  final ValueChanged<String>? onChanged;

  /// Called when the text is submitted by the keyboard's own action.
  final ValueChanged<String>? onSubmitted;

  /// Whether the field takes the focus as it is shown, see [filterAutofocus].
  final bool? autofocus;

  final bool enabled;

  /// Whether the label is a hint inside the field rather than a label above
  /// the text: for a field that stands among other fields of a form.
  final bool asHint;

  /// The key of the text field itself, which tests type into.
  final Key? fieldKey;

  const FilterField({
    super.key,
    required this.controller,
    required this.label,
    this.highlight,
    this.onChanged,
    this.onSubmitted,
    this.autofocus,
    this.enabled = true,
    this.asHint = false,
    this.fieldKey,
  });

  void _changed(String text) {
    highlight?.reset();
    onChanged?.call(text);
  }

  /// `Escape` in a filled field clears it; in an empty one it leaves the
  /// dialog as ever.
  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is KeyDownEvent &&
        event.logicalKey == LogicalKeyboardKey.escape &&
        controller.text.isNotEmpty) {
      controller.clear();
      _changed("");
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) => Focus(
        onKeyEvent: _onKey,
        canRequestFocus: false,
        skipTraversal: true,
        child: TextField(
          key: fieldKey,
          controller: controller,
          enabled: enabled,
          autofocus: autofocus ?? filterAutofocus,
          decoration: InputDecoration(
            isDense: true,
            prefixIcon: const Icon(Icons.search),
            labelText: asHint ? null : label,
            hintText: asHint ? label : null,
          ),
          onChanged: _changed,
          onSubmitted: onSubmitted,
        ),
      );
}

/// The content of a chooser around its [FilterField]: with room, the list
/// scrolls by itself below the field; where a phone's keyboard leaves too
/// little for that, the whole [child] scrolls and the list is laid out in
/// full (issue #178).
///
/// Both ways are the same widgets, only differently constrained: had the
/// cramped way wrapped the content in a scroll view of its own, the keyboard
/// coming up would rebuild the field below it, the field would lose the
/// cursor, the keyboard would go down, the room come back — and the keyboard
/// would flicker up and down without a letter reaching the field.
class CrampedScroll extends StatelessWidget {
  /// Whether the list scrolls by itself, see [roomy].
  final bool roomy;

  /// The height of the room [child] has, from the dialog's `LayoutBuilder`.
  final double maxHeight;

  final Widget child;

  const CrampedScroll({
    super.key,
    required this.roomy,
    required this.maxHeight,
    required this.child,
  });

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
        physics: roomy ? const NeverScrollableScrollPhysics() : null,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: roomy ? maxHeight : double.infinity,
          ),
          child: child,
        ),
      );
}
