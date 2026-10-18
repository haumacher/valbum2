/// The app bar of every page of the app (issue #305): an [AppBar] that ends
/// with the status indicator, so that what runs and what failed is one tap
/// away on every page — "on every page", and a new page cannot forget it.
///
/// `test/page_app_bar_scan_test.dart` keeps view code from building a plain
/// [AppBar]; the modal tools that have no place for it are named there, each
/// with its reason.
library;

import 'package:flutter/material.dart';

import 'status.dart';

/// An [AppBar] whose actions end with the [StatusIndicator] (before a
/// trailing menu), see the library comment. A page that wants the indicator
/// elsewhere among its actions puts it there itself; it is then not added a
/// second time.
class PageAppBar extends StatelessWidget implements PreferredSizeWidget {
  final Widget? leading;

  final bool automaticallyImplyLeading;

  final Widget? title;

  final List<Widget> actions;

  final bool? centerTitle;

  final Color? backgroundColor;

  final Color? foregroundColor;

  final PreferredSizeWidget? bottom;

  const PageAppBar({
    super.key,
    this.leading,
    this.automaticallyImplyLeading = true,
    this.title,
    this.actions = const [],
    this.centerTitle,
    this.backgroundColor,
    this.foregroundColor,
    this.bottom,
  });

  @override
  Size get preferredSize =>
      Size.fromHeight(kToolbarHeight + (bottom?.preferredSize.height ?? 0));

  @override
  Widget build(BuildContext context) => AppBar(
        leading: leading,
        automaticallyImplyLeading: automaticallyImplyLeading,
        title: title,
        centerTitle: centerTitle,
        backgroundColor: backgroundColor,
        foregroundColor: foregroundColor,
        bottom: bottom,
        actions: withIndicator(actions),
      );

  /// The given actions with the [StatusIndicator]: last, or before a
  /// trailing menu, which stays the last thing of the row (issues #99,
  /// #100); unchanged where the page placed it itself.
  static List<Widget> withIndicator(List<Widget> actions) {
    if (actions.any((action) => action is StatusIndicator)) {
      return actions;
    }
    if (actions.isNotEmpty && actions.last is PopupMenuButton) {
      return [
        ...actions.sublist(0, actions.length - 1),
        const StatusIndicator(),
        actions.last,
      ];
    }
    return [...actions, const StatusIndicator()];
  }
}
