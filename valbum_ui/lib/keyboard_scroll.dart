/// A scroll view that can be scrolled without a finger: a scrollbar one can
/// see and drag, and the paging keys of a desktop (issue #168).
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// How far a line key (`ArrowUp`, `ArrowDown`) scrolls, in logical pixels.
const double keyboardLineScroll = 48;

/// How much of the page a page key keeps in view: `PageDown` scrolls by the
/// viewport's height less this, so the last line seen stays on the screen.
const double keyboardPageOverlap = 48;

/// How long a key's scroll is animated.
const Duration keyboardScrollDuration = Duration(milliseconds: 150);

/// Whether the scrollbar's thumb stays visible: where a mouse is the usual
/// pointer — the desktop, and a browser on one. A phone shows it while
/// scrolling, as its platform does.
bool get scrollbarAlwaysVisible {
  switch (defaultTargetPlatform) {
    case TargetPlatform.linux:
    case TargetPlatform.macOS:
    case TargetPlatform.windows:
      return true;
    case TargetPlatform.android:
    case TargetPlatform.iOS:
    case TargetPlatform.fuchsia:
      return false;
  }
}

/// Whether the keyboard focus is in a text field right now: then every key
/// is the text field's, and a page scrolls by none of them.
bool get typingIntoText {
  var context = FocusManager.instance.primaryFocus?.context;
  if (context == null) {
    return false;
  }
  return context.widget is EditableText ||
      context.findAncestorWidgetOfExactType<EditableText>() != null;
}

/// Wraps the one vertical scroll view of a page ([child]) with a visible,
/// draggable [Scrollbar] and the keys `PageUp`/`PageDown` (a viewport less
/// [keyboardPageOverlap]), `Home`/`End` (the two ends) and
/// `ArrowUp`/`ArrowDown` (a line), issue #168.
///
/// The scroll view is driven through [controller], or, without one, through
/// the ambient [PrimaryScrollController] — the listing, the album and the
/// inbox all take theirs from there, which is how their offset is remembered
/// — and only where there is neither is a controller made here. Without
/// [controller] the one driven is put around [child] as the primary one,
/// inherited on every platform, so the scroll view attaches to exactly the
/// controller the scrollbar and the keys drive. There is never a second
/// controller for the same scroll view.
///
/// The keys need the focus, and nobody should have to click into the page to
/// give it: the page's own [Focus] is [autofocus]ed, taken back on every
/// pointer down on the page and handed back by the route when a dialog
/// closes. Keys the page itself knows are asked first ([onKeyEvent]); a key
/// typed into a text field is never taken; the arrows scroll only while the
/// page itself holds the focus, so a focused control below it keeps its
/// directional traversal.
class KeyboardScroll extends StatefulWidget {
  final ScrollController? controller;
  final bool autofocus;
  final FocusOnKeyEventCallback? onKeyEvent;
  final Widget child;

  const KeyboardScroll({
    super.key,
    this.controller,
    this.autofocus = true,
    this.onKeyEvent,
    required this.child,
  });

  @override
  State<KeyboardScroll> createState() => KeyboardScrollState();
}

class KeyboardScrollState extends State<KeyboardScroll> {
  final FocusNode _focus = FocusNode(debugLabel: "KeyboardScroll");

  /// The controller made here, where the page brought none, see
  /// [KeyboardScroll].
  ScrollController? _own;

  /// The controller the scroll view is driven through.
  ScrollController get controller =>
      widget.controller ??
      PrimaryScrollController.maybeOf(context) ??
      (_own ??= ScrollController());

  /// The focus node of the page, for the tests.
  FocusNode get focusNode => _focus;

  @override
  void dispose() {
    _focus.dispose();
    _own?.dispose();
    super.dispose();
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    var own = widget.onKeyEvent?.call(node, event) ?? KeyEventResult.ignored;
    if (own != KeyEventResult.ignored) {
      return own;
    }
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    if (typingIntoText) {
      return KeyEventResult.ignored;
    }
    var positions = controller.positions;
    if (positions.length != 1) {
      return KeyEventResult.ignored;
    }
    var position = positions.first;
    var key = event.logicalKey;
    var page = position.viewportDimension - keyboardPageOverlap;
    if (page < position.viewportDimension / 2) {
      page = position.viewportDimension / 2;
    }
    var keyboard = HardwareKeyboard.instance;
    var plain = !keyboard.isShiftPressed &&
        !keyboard.isControlPressed &&
        !keyboard.isAltPressed &&
        !keyboard.isMetaPressed;
    // The arrows only for the page itself: a focused control below it keeps
    // them for its directional focus traversal.
    var arrows = plain && _focus.hasPrimaryFocus;
    double? target;
    if (key == LogicalKeyboardKey.pageDown) {
      target = position.pixels + page;
    } else if (key == LogicalKeyboardKey.pageUp) {
      target = position.pixels - page;
    } else if (key == LogicalKeyboardKey.home) {
      target = position.minScrollExtent;
    } else if (key == LogicalKeyboardKey.end) {
      target = position.maxScrollExtent;
    } else if (arrows && key == LogicalKeyboardKey.arrowDown) {
      target = position.pixels + keyboardLineScroll;
    } else if (arrows && key == LogicalKeyboardKey.arrowUp) {
      target = position.pixels - keyboardLineScroll;
    }
    if (target == null) {
      return KeyEventResult.ignored;
    }
    _scrollTo(position, target, toEnd: key == LogicalKeyboardKey.end);
    return KeyEventResult.handled;
  }

  void _scrollTo(ScrollPosition position, double target, {bool toEnd = false}) {
    var clamped =
        target.clamp(position.minScrollExtent, position.maxScrollExtent);
    if (clamped == position.pixels) {
      return;
    }
    position
        .animateTo(clamped,
            duration: keyboardScrollDuration, curve: Curves.easeOut)
        .then((_) {
      // A list built on demand may learn on the way that it is longer than
      // it thought: `End` goes on to the end it knows now.
      if (toEnd &&
          mounted &&
          position.hasPixels &&
          position.pixels < position.maxScrollExtent) {
        position.jumpTo(position.maxScrollExtent);
      }
    });
  }

  /// Takes the focus back for the page when it is clicked or touched, unless
  /// something below it already holds it — so the keys work again after a
  /// tile was clicked, whatever held the focus before.
  void _regainFocus(PointerDownEvent event) {
    if (!_focus.hasFocus) {
      _focus.requestFocus();
    }
  }

  @override
  Widget build(BuildContext context) {
    var controller = this.controller;
    Widget scrolled = ScrollConfiguration(
      // The one scrollbar is the one below; the platform's automatic one
      // would be drawn a second time over it.
      behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
      child: widget.child,
    );
    if (widget.controller == null) {
      // The scroll view takes the controller from here — the ambient one
      // again, or the one made here — and on every platform: a route's own
      // primary controller is inherited on the mobile ones only, and the
      // scrollbar and the keys would then drive a controller the desktop's
      // scroll view never attached to.
      scrolled = PrimaryScrollController(
        controller: controller,
        automaticallyInheritForPlatforms: TargetPlatform.values.toSet(),
        child: scrolled,
      );
    }
    return Focus(
      focusNode: _focus,
      autofocus: widget.autofocus,
      onKeyEvent: _onKey,
      child: Listener(
        onPointerDown: _regainFocus,
        child: Scrollbar(
          controller: controller,
          thumbVisibility: scrollbarAlwaysVisible,
          interactive: true,
          child: scrolled,
        ),
      ),
    );
  }
}
