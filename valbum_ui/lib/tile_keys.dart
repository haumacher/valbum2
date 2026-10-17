/// Walking a grid of tiles with the keyboard (issue #295): one behaviour for
/// every grid of the app — the album in all its modes, a collection, a saved
/// and an unsaved search, the view of an entry of the bell, and the folder
/// listing — the grid's counterpart of the choosers' [PickHighlight] and
/// [PickKeys] (`filter_list.dart`).
///
///  * [TileHighlight] — which tile the keyboard stands on: none when the grid
///    is shown, put on a tile by the first arrow (the first tile on the
///    screen), moved by the arrows and stopping at the grid's edges — `Left`
///    and `Right` in reading order (the order the grid draws its tiles in),
///    `Up` and `Down` to the nearest tile of the row drawn above or below,
///    as the rows are laid out. A click on a tile moves it there without
///    showing it, so that the next arrow goes on from where the reader is.
///    It is drawn as a ring over the tile ([HighlightedTile]) and scrolled
///    into view.
///  * [tileKeyEvent] — the keys, asked by the page's own key handler (the
///    one of its [KeyboardScroll]) before the page scrolls: the arrows,
///    `Enter` (the grid's [TileKeyActions.onEnter]: open, or toggle in a
///    mode with a selection), `Space` (toggle, where there is a selection),
///    the context-menu key and `Shift+F10` (the tile's menu, at the tile) and
///    `Ctrl+A`/`Cmd+A` (select all, where there is a selection).
///
/// The keys are the page's alone: while a text field has the focus (the
/// search field, a filter) every key is the field's, and while a control of
/// the page has it (a chip, a button) the arrows are its traversal and
/// `Enter` its own — the rule [KeyboardScroll] keeps for its arrows.
library;

import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'filter_list.dart' show PickHighlight, PickKeys;
import 'keyboard_scroll.dart';

/// A step of the highlight, see [TileHighlight.move].
enum TileStep { left, right, up, down }

/// Where a tile lies in its grid: in any coordinates of the grid's own, as
/// long as they are the same for every tile — the album gives its layout's
/// pixels, the listing its cells.
@immutable
class TileSpot<T> {
  final T entry;
  final Rect rect;

  const TileSpot(this.entry, this.rect);
}

/// Which tile of a grid the keyboard stands on, see the library comment.
///
/// The grid hands over its tiles as they are laid out, in reading order,
/// with every build ([spots]), and wraps the widget of each tile in
/// [HighlightedTile]. [idOf] tells two entries apart across builds.
class TileHighlight<T> extends ChangeNotifier {
  /// What identifies an entry across builds.
  final Object Function(T entry) idOf;

  TileHighlight({required this.idOf});

  Object? _current;

  bool _visible = false;

  /// Where each tile is drawn, so that the highlighted one can be scrolled
  /// to and its menu opened at it.
  final Map<Object, BuildContext> _tiles = {};

  /// The tiles as the grid lays them out, in reading order: handed over
  /// during a build, nothing is rebuilt for it.
  List<TileSpot<T>> spots = const [];

  int get _index {
    var current = _current;
    if (current == null) {
      return -1;
    }
    return spots.indexWhere((spot) => idOf(spot.entry) == current);
  }

  /// The entry the keyboard stands on, `null` for none — and none where the
  /// tile is gone from the grid (a filter hid it, it was deleted).
  T? get current {
    var index = _index;
    return index < 0 ? null : spots[index].entry;
  }

  /// Whether the highlight is drawn: once the keyboard moved it.
  bool get visible => _visible && current != null;

  /// Whether the tile of [entry] is drawn highlighted.
  bool isHighlighted(T entry) => visible && idOf(entry) == _current;

  /// Puts the highlight on [entry] without showing it: a click on a tile,
  /// from which the next arrow goes on.
  void pointedAt(T entry) {
    var id = idOf(entry);
    if (_current == id) {
      return;
    }
    var shown = _visible;
    _current = id;
    _visible = false;
    if (shown) {
      notifyListeners();
    }
  }

  /// Takes the highlight away.
  void reset() {
    if (_current == null) {
      return;
    }
    _current = null;
    _visible = false;
    notifyListeners();
  }

  /// Moves the highlight one [step], never past the grid's edge; from
  /// nowhere the first step lands on the first tile on the screen.
  void move(TileStep step) {
    if (spots.isEmpty) {
      return;
    }
    var index = _index;
    int target;
    if (index < 0) {
      target = _firstShown();
    } else if (!_visible) {
      // A click put it here unseen: the first key shows where it stands.
      target = index;
    } else {
      target = switch (step) {
        TileStep.left => max(0, index - 1),
        TileStep.right => min(spots.length - 1, index + 1),
        TileStep.up => _vertical(index, up: true),
        TileStep.down => _vertical(index, up: false),
      };
    }
    _current = idOf(spots[target].entry);
    _visible = true;
    notifyListeners();
    var forward = step == TileStep.right || step == TileStep.down;
    // After the frame that marks it: the tile may not be laid out before.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      var tile = contextOf(current);
      if (tile != null) {
        Scrollable.ensureVisible(
          tile,
          duration: const Duration(milliseconds: 100),
          alignmentPolicy: forward
              ? ScrollPositionAlignmentPolicy.keepVisibleAtEnd
              : ScrollPositionAlignmentPolicy.keepVisibleAtStart,
        );
      }
    });
  }

  /// The tile in the row drawn above ([up]) or below the tile at [index]
  /// that stands nearest to it: of the tiles beyond its edge the closest
  /// row, and in it the tile whose middle is nearest; [index] itself where
  /// there is none.
  int _vertical(int index, {required bool up}) {
    var from = spots[index].rect;
    const slack = 0.5;
    int? best;
    double bestGap = double.infinity;
    double bestShift = double.infinity;
    for (var (n, spot) in spots.indexed) {
      var rect = spot.rect;
      var gap = up ? from.top - rect.bottom : rect.top - from.bottom;
      if (gap < -slack) {
        continue;
      }
      var shift = (rect.center.dx - from.center.dx).abs();
      if (gap < bestGap - slack ||
          ((gap - bestGap).abs() <= slack && shift < bestShift)) {
        best = n;
        bestGap = gap;
        bestShift = shift;
      }
    }
    return best ?? index;
  }

  /// The first tile, in reading order, that can be seen on the screen; the
  /// first tile where none is built.
  int _firstShown() {
    for (var (n, spot) in spots.indexed) {
      var tile = _tiles[idOf(spot.entry)];
      if (tile == null || !tile.mounted) {
        continue;
      }
      var box = tile.findRenderObject();
      var scrollable = Scrollable.maybeOf(tile);
      var viewport = scrollable?.context.findRenderObject();
      if (box is! RenderBox ||
          !box.attached ||
          !box.hasSize ||
          viewport is! RenderBox ||
          !viewport.hasSize) {
        continue;
      }
      var rect = box.localToGlobal(Offset.zero) & box.size;
      var shown = viewport.localToGlobal(Offset.zero) & viewport.size;
      if (rect.overlaps(shown) && rect.bottom > shown.top + 1) {
        return n;
      }
    }
    return 0;
  }

  /// The context the tile of [entry] is drawn in, `null` where it is not
  /// built.
  BuildContext? contextOf(T? entry) {
    if (entry == null) {
      return null;
    }
    var tile = _tiles[idOf(entry)];
    return tile != null && tile.mounted ? tile : null;
  }

  /// Where the menu of the tile of [entry] opens: at its middle, in global
  /// coordinates; `null` where the tile is not built.
  Offset? menuPosition(T entry) {
    var box = contextOf(entry)?.findRenderObject();
    if (box is! RenderBox || !box.hasSize) {
      return null;
    }
    return box.localToGlobal(box.size.center(Offset.zero));
  }

  void _register(Object id, BuildContext context) => _tiles[id] = context;

  void _unregister(Object id, BuildContext context) {
    if (identical(_tiles[id], context)) {
      _tiles.remove(id);
    }
  }

  /// The colour of the ring a highlighted tile is drawn with.
  static const Color ring = Colors.lightBlueAccent;
}

/// The widget of one tile of a grid walked by a [TileHighlight]: draws the
/// ring while it is highlighted, and moves the highlight here (unseen) when
/// the tile is clicked or touched.
class HighlightedTile<T> extends StatefulWidget {
  final TileHighlight<T> highlight;
  final T entry;
  final Widget child;

  const HighlightedTile({
    super.key,
    required this.highlight,
    required this.entry,
    required this.child,
  });

  /// The key of the ring, which tests look for.
  static const Key ringKey = Key("tile-highlight");

  @override
  State<HighlightedTile<T>> createState() => _HighlightedTileState<T>();
}

class _HighlightedTileState<T> extends State<HighlightedTile<T>> {
  late Object _id = widget.highlight.idOf(widget.entry);

  @override
  void initState() {
    super.initState();
    widget.highlight._register(_id, context);
  }

  @override
  void didUpdateWidget(HighlightedTile<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    var id = widget.highlight.idOf(widget.entry);
    if (!identical(oldWidget.highlight, widget.highlight) || id != _id) {
      oldWidget.highlight._unregister(_id, context);
      _id = id;
      widget.highlight._register(_id, context);
    }
  }

  @override
  void dispose() {
    widget.highlight._unregister(_id, context);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Listener(
        onPointerDown: (_) => widget.highlight.pointedAt(widget.entry),
        child: ListenableBuilder(
          listenable: widget.highlight,
          builder: (context, child) {
            var highlighted = widget.highlight.isHighlighted(widget.entry);
            return Stack(
              fit: StackFit.passthrough,
              children: [
                child!,
                if (highlighted)
                  const Positioned.fill(
                    child: IgnorePointer(
                      child: DecoratedBox(
                        key: HighlightedTile.ringKey,
                        decoration: BoxDecoration(
                          border: Border.fromBorderSide(
                            BorderSide(color: TileHighlight.ring, width: 3),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            );
          },
          child: widget.child,
        ),
      );
}

/// What the keys of [tileKeyEvent] do with the tile highlighted; a `null`
/// action leaves its key to whoever comes next.
class TileKeyActions<T> {
  /// `Enter`: what a tap on the tile does — open it, or toggle it where the
  /// grid is selecting.
  final void Function(T entry)? onEnter;

  /// `Space`: adds the tile to the selection or takes it out, where the grid
  /// has a selection.
  final void Function(T entry)? onToggle;

  /// The context-menu key and `Shift+F10`: the tile's menu at [position].
  final void Function(T entry, Offset position)? onMenu;

  /// `Ctrl+A`, `Cmd+A`: selects every tile, where the grid has a selection.
  final VoidCallback? onSelectAll;

  const TileKeyActions({
    this.onEnter,
    this.onToggle,
    this.onMenu,
    this.onSelectAll,
  });
}

/// The keys of a grid walked by [highlight], see the library comment, asked
/// by the key handler of the page's focus [page] before the page scrolls.
KeyEventResult tileKeyEvent<T>(
  TileHighlight<T> highlight,
  FocusNode page,
  KeyEvent event,
  TileKeyActions<T> actions,
) {
  if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
    return KeyEventResult.ignored;
  }
  // The page's keys alone: a field's or a control's keys stay theirs.
  if (typingIntoText || !page.hasPrimaryFocus) {
    return KeyEventResult.ignored;
  }
  var keyboard = HardwareKeyboard.instance;
  var shift = keyboard.isShiftPressed;
  var command = keyboard.isControlPressed || keyboard.isMetaPressed;
  var alt = keyboard.isAltPressed;
  var key = event.logicalKey;

  if (!shift && !command && !alt) {
    var step = switch (key) {
      LogicalKeyboardKey.arrowLeft => TileStep.left,
      LogicalKeyboardKey.arrowRight => TileStep.right,
      LogicalKeyboardKey.arrowUp => TileStep.up,
      LogicalKeyboardKey.arrowDown => TileStep.down,
      _ => null,
    };
    if (step != null) {
      if (highlight.spots.isEmpty) {
        return KeyEventResult.ignored;
      }
      highlight.move(step);
      return KeyEventResult.handled;
    }
  }

  if (command && !alt && key == LogicalKeyboardKey.keyA) {
    var selectAll = actions.onSelectAll;
    if (selectAll == null) {
      return KeyEventResult.ignored;
    }
    selectAll();
    return KeyEventResult.handled;
  }

  // What follows acts on the highlighted tile; with none shown it is not
  // the grid's key.
  var entry = highlight.visible ? highlight.current : null;
  if (entry == null) {
    return KeyEventResult.ignored;
  }
  if (!command &&
      !alt &&
      (key == LogicalKeyboardKey.contextMenu ||
          (shift && key == LogicalKeyboardKey.f10))) {
    var menu = actions.onMenu;
    var position = highlight.menuPosition(entry);
    if (menu == null || position == null) {
      return KeyEventResult.ignored;
    }
    menu(entry, position);
    return KeyEventResult.handled;
  }
  if (shift || command || alt) {
    return KeyEventResult.ignored;
  }
  if (key == LogicalKeyboardKey.enter ||
      key == LogicalKeyboardKey.numpadEnter) {
    var enter = actions.onEnter;
    if (enter == null || event is KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    enter(entry);
    return KeyEventResult.handled;
  }
  if (key == LogicalKeyboardKey.space) {
    var toggle = actions.onToggle;
    if (toggle == null) {
      return KeyEventResult.ignored;
    }
    toggle(entry);
    return KeyEventResult.handled;
  }
  return KeyEventResult.ignored;
}

/// Holds the [TileHighlight] of a grid whose page keeps no state of its own
/// (the folder listing): made with the page, given up with it.
class TileHighlightScope<T> extends StatefulWidget {
  final Object Function(T entry) idOf;
  final Widget Function(BuildContext context, TileHighlight<T> highlight)
      builder;

  const TileHighlightScope({
    super.key,
    required this.idOf,
    required this.builder,
  });

  @override
  State<TileHighlightScope<T>> createState() => _TileHighlightScopeState<T>();
}

class _TileHighlightScopeState<T> extends State<TileHighlightScope<T>> {
  late final TileHighlight<T> _highlight = TileHighlight<T>(idOf: widget.idOf);

  @override
  void dispose() {
    _highlight.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _highlight);
}
