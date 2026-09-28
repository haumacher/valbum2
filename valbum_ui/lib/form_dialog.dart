/// Dialogs that collect input, see issue #178.
///
/// Two rules, each kept in one place so that no dialog can forget it:
///
/// * **Input is discarded by Cancel or Escape only.** [showFormDialog] opens a
///   dialog whose barrier does not close it: on a phone a tap meant for a
///   button that the closing keyboard has just moved lands on the barrier,
///   and the default barrier then threw the filled-in form away without a
///   word (the album that was never created of #178). Escape and the
///   system's back still cancel.
/// * **A form fits with the keyboard open.** [FormDialogFrame] keeps the
///   title and the buttons in place and scrolls what lies between them, so
///   the buttons are on the screen however little of it the keyboard leaves.
///   A `Dialog` already keeps clear of the keyboard (`viewInsets`) and of its
///   `insetPadding`; what it does not do is scroll.
///
/// A dialog that only shows something — a photograph, a confirmation without
/// input — keeps the ordinary `showDialog` and its dismissing barrier.
library;

import 'package:flutter/material.dart';

/// Shows the form dialog [builder] builds, which a tap beside it does not
/// close; Escape, the system's back and the dialog's own Cancel do.
///
/// What `showDialog` does, with one change: `barrierDismissible: false` would
/// have been the obvious way, but Flutter's modal route asks that one flag for
/// the barrier *and* for Escape, and a dialog without a focused field (a
/// picker, the permission dialog) could then not be left by Escape at all —
/// the route's focus scope, where Escape is dispatched from, lies above
/// anything a builder can wrap around its dialog. So the route stays
/// dismissible and only its barrier is made deaf, see [_FormDialogRoute].
Future<T?> showFormDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
}) {
  var navigator = Navigator.of(context, rootNavigator: true);
  return navigator.push<T>(_FormDialogRoute<T>(
    context: context,
    builder: builder,
    barrierColor: DialogTheme.of(context).barrierColor ??
        Theme.of(context).dialogTheme.barrierColor ??
        Colors.black54,
    themes: InheritedTheme.capture(from: context, to: navigator.context),
  ));
}

/// A [DialogRoute] whose barrier swallows a tap instead of closing it.
///
/// The route is dismissible, so Escape pops it as it pops every dialog (the
/// route's own dismiss action); the barrier it builds is wrapped in an
/// [AbsorbPointer], so a tap on it reaches neither the barrier's dismissal nor
/// the page under it.
class _FormDialogRoute<T> extends DialogRoute<T> {
  _FormDialogRoute({
    required super.context,
    required super.builder,
    required super.barrierColor,
    required super.themes,
  }) : super(
          barrierDismissible: true,
          traversalEdgeBehavior: TraversalEdgeBehavior.closedLoop,
        );

  @override
  Widget buildModalBarrier() =>
      AbsorbPointer(child: super.buildModalBarrier());
}

/// The frame of a form dialog built on a plain [Dialog]: a title that stays,
/// a body that scrolls, what must not scroll, and the buttons.
///
/// [fields] are what is filled in; they scroll inside the dialog when the screen
/// is short — a phone with its keyboard open. [fixed] stands below them,
/// outside the scroll view, for a widget whose own drag a scroll view would
/// take away (the crop editor of the album properties, see issue #136); it is
/// laid out only where the dialog has [fixedNeedsHeight] for its content, and
/// left out where it would not fit — it cannot shrink, and cannot scroll. The
/// [actions] stand at the end of the last line, as the `Row` they replace
/// did; an [OverflowBar] puts them under one another where one line is too
/// narrow for them instead of overflowing it.
class FormDialogFrame extends StatelessWidget {
  final Widget title;
  final List<Widget> fields;
  final List<Widget> fixed;
  final List<Widget> actions;
  final double fixedNeedsHeight;
  final EdgeInsetsGeometry padding;
  final double actionsGap;

  const FormDialogFrame({
    super.key,
    required this.title,
    required this.fields,
    required this.actions,
    this.fixed = const [],
    this.fixedNeedsHeight = 0,
    this.padding = const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    this.actionsGap = 16,
  });

  @override
  Widget build(BuildContext context) {
    var theme = Theme.of(context);
    return Dialog(
      child: Padding(
        padding: padding,
        // Decided on every layout, not once: the room changes while the
        // keyboard comes and goes, and with the turn of a phone.
        child: LayoutBuilder(
          builder: (context, constraints) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ??
                    theme.textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced
                  // twice.
                  namesRoute: theme.platform != TargetPlatform.iOS,
                  container: true,
                  child: title,
                ),
              ),
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: fields,
                  ),
                ),
              ),
              if (constraints.maxHeight >= fixedNeedsHeight) ...fixed,
              Padding(
                padding: EdgeInsets.only(top: actionsGap),
                child: OverflowBar(
                  alignment: MainAxisAlignment.end,
                  overflowAlignment: OverflowBarAlignment.end,
                  spacing: 8,
                  overflowSpacing: 8,
                  children: actions,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
