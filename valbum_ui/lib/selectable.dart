/// Text that can be selected and copied almost everywhere (issue #250).
///
/// Selection is the default, not something a widget opts into: every page the
/// router shows, every dialog and every bottom sheet is one
/// [SelectableArea], and each [Text] inside it can be selected with the
/// mouse or a long press and copied with the keyboard or the context menu — a
/// title, a date, a name, an address, and above all an error message one
/// wants to paste into a bug report.
///
/// What is *not* selectable is what the fingers already own: the thumbnail
/// grids (a tap opens, a long press selects, a drag reorders), the viewer's
/// picture (pan, zoom, swipe), the face boxes, the crop editor, the faces
/// carried between persons. Each such surface is wrapped in a
/// [SelectionExcluded], and a pointer that goes down on it never meets the
/// area's own gesture recognisers — not merely its text left out, see
/// [SelectableArea] for why that matters. A panel such a surface draws over
/// itself gives its text back to the page with a [SelectionIncluded].
///
/// The entry points, so that no page or dialog can forget the area:
///
///  * the pages: the router's pages, the startup screen and the server
///    settings are wrapped where their routes are built (`app.dart`,
///    `settings.dart`, `photo_picker_view.dart`);
///  * the dialogs: [showAppDialog] instead of `showDialog`, and
///    `showFormDialog` (`form_dialog.dart`), which wraps its builder alike;
///  * the bottom sheets: [showAppModalBottomSheet] instead of
///    `showModalBottomSheet`;
///  * the snack bars and banners: they are laid out by the page's [Scaffold],
///    inside the page's area.
///
/// `test/selectable_text_test.dart` keeps `showDialog` and friends out of the
/// rest of the app.
library;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import 'key_guard.dart';

/// A region whose text can be selected, see the library comment.
///
/// A [SelectionArea] with three adjustments, each for something the plain one
/// would break:
///
///  * **It takes no pointer that lands on a [SelectionExcluded].** A
///    [SelectionArea] competes for every pointer that goes down inside it,
///    text or not: a mouse held down for a tenth of a second is a "tap down"
///    to it, it takes a drag past two pixels where a long-press drag allows
///    eighteen, and a held double tap beats a long press. For a pointer on an
///    excluded surface the hit test is routed around the area's gesture
///    detector, so the grid, the viewer and the drag and drop see exactly the
///    gestures they saw before the area was there.
///  * **It takes no pointer that hits nothing inside it.** A dialog's route
///    fills the screen; the area around the dialog would otherwise take the
///    tap meant for the barrier, and a dialog could no longer be closed by a
///    tap beside it.
///  * **It does not take the keyboard focus from the page.** A selection
///    needs the focus for its keys (`Ctrl+C`, `Ctrl+A`), and the plain area
///    takes it on every press. Where something inside the area already holds
///    the focus — the album's and the listing's paging keys
///    ([KeyboardScroll]), the viewer's arrows — the keys reach the area
///    through that holder anyway, so the focus stays where it is and the
///    arrows keep scrolling and paging. Only a text field is left: its own
///    copy would otherwise answer `Ctrl+C`.
///
/// An area inside another one adds nothing: the outer one already covers its
/// text, and one region per page lets a selection run across the whole page.
class SelectableArea extends StatefulWidget {
  final Widget child;

  const SelectableArea({super.key, required this.child});

  /// Whether [context] lies inside a [SelectableArea].
  static bool isInside(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_AreaScope>() != null;

  @override
  State<SelectableArea> createState() => _SelectableAreaState();
}

class _SelectableAreaState extends State<SelectableArea> {
  final GlobalKey<SelectionAreaState> _area = GlobalKey<SelectionAreaState>();
  final GlobalKey _inner = GlobalKey();
  final _KeepingFocusNode _focus = _KeepingFocusNode();

  @override
  void initState() {
    super.initState();
    FocusManager.instance.addListener(_focusMoved);
  }

  @override
  void dispose() {
    FocusManager.instance.removeListener(_focusMoved);
    _focus.dispose();
    super.dispose();
  }

  /// Clears the selection when a text field inside the area takes the focus
  /// (a [SelectableText], a form field): two selections at once, each with
  /// its own handles, would leave it open which one `Ctrl+C` copies.
  ///
  /// The focus is inside this area exactly when the area's own node has it:
  /// an area inside another one builds no node, see [SelectableArea].
  void _focusMoved() {
    // Notified while areas leave the tree too: only a mounted one clears.
    if (mounted && _focus.hasFocus && typingIntoText) {
      _area.currentState?.selectableRegion.clearSelection();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (SelectableArea.isInside(context)) {
      return widget.child;
    }
    return _AreaScope(
      child: _SelectionHitFilter(
        inner: _inner,
        child: SelectionArea(
          key: _area,
          focusNode: _focus,
          child: _AreaContent(
            key: _inner,
            // Selected text shows on the black pages and on the light ones
            // alike, see [selectionHighlight].
            child: DefaultSelectionStyle.merge(
              selectionColor: selectionHighlight,
              child: _OffstageGuard(child: widget.child),
            ),
          ),
        ),
      ),
    );
  }
}

/// Keeps the text of a page that has never been on the screen out of the
/// selection.
///
/// A deep link builds the levels beneath the page it names — the album below
/// the viewer — and they stay mounted without ever being laid out. The
/// selection asks every text for its place on the screen, and a text on a
/// page never laid out has none: the selection fails with an assertion. Such
/// a page is told by its tickers being off (an offstage page of the
/// [Navigator]); its text becomes selectable the first time it is shown, and
/// stays so — a page that was laid out once keeps its sizes.
///
/// Switched once and in one direction only, and while the page is shown: a
/// text changes its widgets with the selection (a [Text] in a selection is a
/// container of its own), and a page that is not laid out cannot rebuild a
/// list that builds its children while it is laid out.
class _OffstageGuard extends StatefulWidget {
  final Widget child;

  const _OffstageGuard({required this.child});

  @override
  State<_OffstageGuard> createState() => _OffstageGuardState();
}

class _OffstageGuardState extends State<_OffstageGuard> {
  final StaticSelectionContainerDelegate _delegate =
      StaticSelectionContainerDelegate();

  /// Whether the page has been shown, see [_OffstageGuard].
  bool _shown = false;

  @override
  void dispose() {
    _delegate.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _shown = _shown || TickerMode.valuesOf(context).enabled;
    if (!_shown) {
      return SelectionContainer.disabled(child: widget.child);
    }
    // The same widget either way; the registrar is named, since a container
    // switched on does not look for it by itself.
    return SelectionContainer(
      registrar: SelectionContainer.maybeOf(context),
      delegate: _delegate,
      child: widget.child,
    );
  }
}

/// Marks the inside of a [SelectableArea].
class _AreaScope extends InheritedWidget {
  const _AreaScope({required super.child});

  @override
  bool updateShouldNotify(_AreaScope oldWidget) => false;
}

/// The focus of a [SelectableArea], which does not take the focus from
/// something inside the area that already holds it, see [SelectableArea].
class _KeepingFocusNode extends FocusNode {
  _KeepingFocusNode() : super(debugLabel: "SelectableArea");

  @override
  void requestFocus([FocusNode? node]) {
    if (node == null && hasFocus && !typingIntoText) {
      // The holder inside passes the selection's keys up to the area.
      return;
    }
    super.requestFocus(node);
  }

  @override
  void unfocus({UnfocusDisposition disposition = UnfocusDisposition.scope}) {
    // On the web a tap outside the area unfocuses it — a tap on an excluded
    // surface counts as outside, and must not take the focus from what holds
    // it inside the area. That call is the only one passed over: everything
    // else, above all the framework taking back a request still pending when
    // the area leaves the tree (a dialog closed right after a click into it),
    // goes through.
    if (disposition == UnfocusDisposition.scope &&
        hasFocus &&
        !hasPrimaryFocus) {
      return;
    }
    super.unfocus(disposition: disposition);
  }
}

/// A surface whose gestures are its own: its text is not selected, and a
/// pointer going down on it never reaches the gesture recognisers of the
/// [SelectableArea] around it, see there.
///
/// Around a child that is one of several keyed siblings (a tile in a row),
/// [SelectionExcluded.keyed] keeps the siblings told apart by their keys.
class SelectionExcluded extends StatelessWidget {
  final Widget child;

  const SelectionExcluded({super.key, required this.child});

  /// Wraps [child], keyed after it: siblings stay told apart, and a finder
  /// looking for the child's key still finds the child alone.
  factory SelectionExcluded.keyed(Widget child) => SelectionExcluded(
        key: child.key == null ? null : _AroundKey(child.key!),
        child: child,
      );

  @override
  Widget build(BuildContext context) => _OuterRegistrar(
        // What a [SelectionIncluded] inside registers with: the area's
        // registrar, or the one an exclusion around this one handed on.
        registrar: SelectionContainer.maybeOf(context) ??
            context
                .dependOnInheritedWidgetOfExactType<_OuterRegistrar>()
                ?.registrar,
        child:
            SelectionContainer.disabled(child: _ExcludedMarker(child: child)),
      );
}

/// Text inside a gesture surface that is the page's all the same: it is
/// selected with the text around the surface, and a pointer going down on it
/// is the selection's and no gesture of the surface's (issue #280).
///
/// For a panel that a surface draws over itself — what the video view says
/// while the server prepares a video, or why it cannot play one: the panel
/// stands where the surface swipes and long-presses, but what it says is
/// read, and copied into a bug report. A swipe or a long press on the panel
/// selects; beside it, the surface pages and edits as before.
///
/// Outside a [SelectionExcluded] it adds nothing: the text is the page's
/// anyway.
class SelectionIncluded extends StatefulWidget {
  final Widget child;

  const SelectionIncluded({super.key, required this.child});

  @override
  State<SelectionIncluded> createState() => _SelectionIncludedState();
}

class _SelectionIncludedState extends State<SelectionIncluded> {
  final StaticSelectionContainerDelegate _delegate =
      StaticSelectionContainerDelegate();

  @override
  void dispose() {
    _delegate.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var outer = context.dependOnInheritedWidgetOfExactType<_OuterRegistrar>();
    var registrar = outer?.registrar;
    // Inside an exclusion there is no registrar but the area's, which the
    // exclusion handed on; the same widgets either way, see [_OffstageGuard].
    return _IncludedMarker(
      child: SelectionContainer(
        registrar: SelectionContainer.maybeOf(context) ?? registrar,
        delegate: _delegate,
        child: widget.child,
      ),
    );
  }
}

/// The registrar around a [SelectionExcluded], for a [SelectionIncluded]
/// inside it.
class _OuterRegistrar extends InheritedWidget {
  final SelectionRegistrar? registrar;

  const _OuterRegistrar({required this.registrar, required super.child});

  @override
  bool updateShouldNotify(_OuterRegistrar oldWidget) =>
      oldWidget.registrar != registrar;
}

class _IncludedMarker extends SingleChildRenderObjectWidget {
  const _IncludedMarker({super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderIncluded();
}

class _RenderIncluded extends RenderProxyBox {}

/// The colour of selected text in every [SelectableArea] (issue #280).
///
/// The theme's — its primary at two fifths — is all but black itself on the
/// black of the album, the listing, the inbox, the search and the viewer: a
/// selection made there did not show, and the text seemed not selectable at
/// all. A light blue at half strength reads on the black under white text and
/// on the light pages and dialogs under dark text.
const Color selectionHighlight = Color(0x8040C4FF);

/// The key of a [SelectionExcluded] around a keyed child.
class _AroundKey extends LocalKey {
  final Key inner;

  const _AroundKey(this.inner);

  @override
  bool operator ==(Object other) =>
      other is _AroundKey && other.inner == inner;

  @override
  int get hashCode => Object.hash(_AroundKey, inner);
}

class _ExcludedMarker extends SingleChildRenderObjectWidget {
  const _ExcludedMarker({super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderExcluded();
}

class _RenderExcluded extends RenderProxyBox {}

/// The content of a [SelectableArea], below the area's gesture detector.
class _AreaContent extends SingleChildRenderObjectWidget {
  const _AreaContent({super.key, super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => RenderProxyBox();
}

/// Routes the hit test of a [SelectableArea], see there.
class _SelectionHitFilter extends SingleChildRenderObjectWidget {
  final GlobalKey inner;

  const _SelectionHitFilter({required this.inner, super.child});

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSelectionHitFilter(inner);

  @override
  void updateRenderObject(
      BuildContext context, _RenderSelectionHitFilter renderObject) {
    renderObject.inner = inner;
  }
}

class _RenderSelectionHitFilter extends RenderProxyBox {
  GlobalKey inner;

  _RenderSelectionHitFilter(this.inner);

  @override
  bool hitTest(BoxHitTestResult result, {required Offset position}) {
    var child = this.child;
    if (child == null || !size.contains(position)) {
      return false;
    }
    var probe = BoxHitTestResult();
    if (!child.hitTest(probe, position: position)) {
      // Nothing inside was hit: the pointer is for what lies below, the
      // barrier of a dialog.
      return false;
    }
    var content = inner.currentContext?.findRenderObject();
    // The innermost mark decides: text a surface gives back to the page
    // (issue #280) inside a surface, or the surface itself.
    var mark = probe.path
        .map((entry) => entry.target)
        .where(
            (target) => target is _RenderExcluded || target is _RenderIncluded)
        .firstOrNull;
    if (mark is _RenderIncluded &&
        content is RenderBox &&
        content.attached &&
        probe.path.any((entry) => entry.target is _RenderExcluded)) {
      // Into the text alone, and from there to the area's gesture detector:
      // the gestures of the surface between them never see the pointer.
      var hit = result.addWithPaintTransform(
        transform: mark.getTransformTo(this),
        position: position,
        hitTest: (result, position) => mark.hitTest(result, position: position),
      );
      if (!hit) {
        return false;
      }
      for (RenderObject? node = content.parent;
          node != null && node != this;
          node = node.parent) {
        if (node is! RenderBox) {
          continue;
        }
        result.addWithPaintTransform(
          transform: node.getTransformTo(this),
          position: position,
          hitTest: (result, position) {
            result.add(BoxHitTestEntry(node as RenderBox, position));
            return true;
          },
        );
      }
      result.add(BoxHitTestEntry(this, position));
      return true;
    }
    if (content is RenderBox && content.attached && mark is _RenderExcluded) {
      // Around the area's gesture detector, straight into its content.
      var hit = result.addWithPaintTransform(
        transform: content.getTransformTo(this),
        position: position,
        hitTest: (result, position) =>
            content.hitTest(result, position: position),
      );
      if (hit) {
        result.add(BoxHitTestEntry(this, position));
      }
      return hit;
    }
    return super.hitTest(result, position: position);
  }
}

/// [showDialog], with the dialog a [SelectableArea].
///
/// The one way this app opens a dialog that collects nothing; a form opens
/// with `showFormDialog`, which does the same.
Future<T?> showAppDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool barrierDismissible = true,
  bool useRootNavigator = true,
}) =>
    showDialog<T>(
      context: context,
      barrierDismissible: barrierDismissible,
      useRootNavigator: useRootNavigator,
      builder: (context) => SelectableArea(child: builder(context)),
    );

/// [showModalBottomSheet], with the sheet a [SelectableArea].
Future<T?> showAppModalBottomSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool showDragHandle = false,
  bool isScrollControlled = false,
  bool useSafeArea = false,
}) =>
    showModalBottomSheet<T>(
      context: context,
      showDragHandle: showDragHandle,
      isScrollControlled: isScrollControlled,
      useSafeArea: useSafeArea,
      builder: (context) => SelectableArea(child: builder(context)),
    );
