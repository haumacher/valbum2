/// The spacing of every dialog of the app, in one place.
///
/// Flutter's dialogs keep 40 pixels from the screen's edges and 24 inside
/// their frame, for the title, the content and the buttons alike. On a phone
/// that left a narrow column of wrapped lines inside a wide frame. Only the
/// outer margin can be set by the theme; the inner padding of an
/// `AlertDialog` is taken from Flutter's defaults at every construction. So
/// the app constructs its dialogs only through the widgets here —
/// [AppAlertDialog], [AppSimpleDialog] with [AppDialogOption], [AppDialog] —
/// which take every number from [DialogSpacing], and `appTheme` takes the
/// outer margin from there too. Changing a number of [DialogSpacing] changes
/// every dialog.
///
/// The widgets wrap Flutter's (rather than extend them), so a dialog is still
/// an `AlertDialog`, a `SimpleDialog`, a `Dialog` to whoever looks for one.
/// `test/dialog_style_test.dart` keeps Flutter's own constructors out of the
/// rest of `lib/`.
library;

import 'package:flutter/material.dart';

/// The numbers of the dialogs' spacing, see the library comment.
///
/// Compact: 12 pixels from the screen's sides, 16 inside the frame on every
/// side, 12 between the title and the content, 16 between the content and the
/// buttons.
abstract final class DialogSpacing {
  /// The padding inside the frame, on every side.
  static const double edge = 16;

  /// The margin between a dialog and the screen's edges (and the keyboard).
  static const EdgeInsets inset =
      EdgeInsets.symmetric(horizontal: 12, vertical: 24);

  /// Between the title and the content below it.
  static const double titleGap = 12;

  /// Between the content and the button bar below it.
  static const double actionsGap = 16;

  /// Between the content and a button bar that a divider sets apart from it
  /// (a list that scrolls, see `NewsMenu`): the line already separates them.
  static const double dividedActionsGap = 8;

  /// Between two buttons of the bar, beside or under one another.
  static const double buttonSpacing = 8;

  /// The padding of the button bar: the frame's on three sides, the gap above
  /// it belongs to what stands above.
  static const EdgeInsets actions = EdgeInsets.fromLTRB(edge, 0, edge, edge);

  /// The padding of a form built on a plain frame, see `FormDialogFrame`.
  static const EdgeInsets frame = EdgeInsets.all(edge);

  /// The padding of a form whose content needs every line of the screen —
  /// the album properties with their crop editor, see issue #136.
  static const EdgeInsets denseFrame =
      EdgeInsets.symmetric(horizontal: edge, vertical: 8);

  /// [actionsGap] of [denseFrame].
  static const double denseActionsGap = 8;

  /// The padding around a picture shown as large as the screen allows: the
  /// picture is the content, the frame a thin border around it.
  static const EdgeInsets picture = EdgeInsets.all(12);

  /// The padding of a choice of an [AppSimpleDialog], aligned with the title
  /// and with a `ListTile`'s 16 pixels.
  static const EdgeInsets option =
      EdgeInsets.symmetric(horizontal: edge, vertical: 8);

  /// The padding of the title of an `AlertDialog`: [edge] on three sides; to
  /// the content [titleGap], which the content's padding holds; where there
  /// is no content, the gap to the buttons, or the frame's.
  static EdgeInsets title({required bool content, required bool actions}) =>
      EdgeInsets.fromLTRB(
          edge, edge, edge, content ? 0 : (actions ? actionsGap : edge));

  /// The padding of the content of an `AlertDialog`, see [title].
  static EdgeInsets content({required bool title, required bool actions}) =>
      EdgeInsets.fromLTRB(
          edge, title ? titleGap : edge, edge, actions ? actionsGap : edge);
}

/// An [AlertDialog] with the spacing of [DialogSpacing].
///
/// [contentPadding] overrides the content's padding where a dialog means it —
/// `EdgeInsets.zero` for a list or a picture that reaches the frame.
class AppAlertDialog extends StatelessWidget {
  final Widget? title;
  final Widget? content;
  final List<Widget>? actions;
  final EdgeInsetsGeometry? contentPadding;
  final bool scrollable;

  const AppAlertDialog({
    super.key,
    this.title,
    this.content,
    this.actions,
    this.contentPadding,
    this.scrollable = false,
  });

  @override
  Widget build(BuildContext context) {
    var hasActions = actions != null && actions!.isNotEmpty;
    return AlertDialog(
      insetPadding: DialogSpacing.inset,
      titlePadding:
          DialogSpacing.title(content: content != null, actions: hasActions),
      contentPadding: contentPadding ??
          DialogSpacing.content(title: title != null, actions: hasActions),
      actionsPadding: DialogSpacing.actions,
      buttonPadding:
          const EdgeInsets.symmetric(horizontal: DialogSpacing.buttonSpacing),
      actionsOverflowButtonSpacing: DialogSpacing.buttonSpacing,
      scrollable: scrollable,
      title: title,
      content: content,
      actions: actions,
    );
  }
}

/// A [SimpleDialog] with the spacing of [DialogSpacing]; its choices are
/// [AppDialogOption]s.
class AppSimpleDialog extends StatelessWidget {
  final Widget? title;
  final List<Widget>? children;

  const AppSimpleDialog({super.key, this.title, this.children});

  @override
  Widget build(BuildContext context) => SimpleDialog(
        insetPadding: DialogSpacing.inset,
        titlePadding: const EdgeInsets.fromLTRB(
            DialogSpacing.edge, DialogSpacing.edge, DialogSpacing.edge, 0),
        contentPadding: EdgeInsets.fromLTRB(
            0,
            title == null ? DialogSpacing.edge : DialogSpacing.titleGap,
            0,
            DialogSpacing.edge),
        title: title,
        children: children,
      );
}

/// A choice of an [AppSimpleDialog], a [SimpleDialogOption] with the padding
/// of [DialogSpacing.option].
class AppDialogOption extends StatelessWidget {
  final VoidCallback? onPressed;
  final Widget? child;

  const AppDialogOption({super.key, this.onPressed, this.child});

  @override
  Widget build(BuildContext context) => SimpleDialogOption(
        padding: DialogSpacing.option,
        onPressed: onPressed,
        child: child,
      );
}

/// A plain [Dialog], the frame of a dialog that lays out its own content
/// (`FormDialogFrame`, `NewsMenu`, a photograph), with the margin of
/// [DialogSpacing.inset]. Its content takes its padding from [DialogSpacing];
/// its buttons stand in a [DialogActions].
class AppDialog extends StatelessWidget {
  final Widget? child;

  const AppDialog({super.key, this.child});

  @override
  Widget build(BuildContext context) =>
      Dialog(insetPadding: DialogSpacing.inset, child: child);
}

/// The button bar of an [AppDialog], laid out as an [AppAlertDialog] lays out
/// its own: at the end of the line, under one another where one line is too
/// narrow, [gap] above it and the frame's padding on the other three sides.
class DialogActions extends StatelessWidget {
  final List<Widget> children;
  final double gap;
  final EdgeInsets padding;

  const DialogActions({
    super.key,
    required this.children,
    this.gap = DialogSpacing.actionsGap,
    this.padding = DialogSpacing.actions,
  });

  @override
  Widget build(BuildContext context) => Padding(
        padding: padding.copyWith(top: padding.top + gap),
        child: OverflowBar(
          alignment: MainAxisAlignment.end,
          overflowAlignment: OverflowBarAlignment.end,
          spacing: DialogSpacing.buttonSpacing,
          overflowSpacing: DialogSpacing.buttonSpacing,
          children: children,
        ),
      );
}
