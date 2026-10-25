/// The app bar of every page of the app (issues #305, #308, #309, #310): an
/// [AppBar] that reads the same on every page.
///
///  * **The way up is the leading control**, at the left, in every mode of
///    every page — the [WayBackButton]. It is never one of the actions at the
///    right: [PageAppBar] refuses it there (an assertion), and
///    `test/page_app_bar_scan_test.dart` keeps the back arrow out of every
///    other place of the view code (#308).
///  * **The title starts at the left**, beside the way up, never centred; a
///    title with a second line is a [PageTitle], whose first line sits where
///    every other page's title does (#308).
///  * **The pages of photographs are black** — the folder listings, the
///    albums in every mode, the search, the inbox, a group — so their bar is
///    black with light text and icons: [PageAppBar.black], the one place that
///    look is made, including the menus opened from the bar (#310). Every
///    other page follows the system's light or dark theme (`appTheme`).
///  * **What runs and what arrived stand on the top-level page only** (#309,
///    reversing the "on every page" of #305): [topLevelStatus] is the one
///    place the status indicator and the bell are put into a bar, and the
///    root listing (or an album at the root) is the one page asking for it.
///    A bar adds neither by itself. There is no home button anywhere (#309):
///    the start page is reached by going up.
///
/// `test/page_app_bar_scan_test.dart` keeps view code from building a plain
/// [AppBar], from drawing a back arrow of its own and from placing the
/// indicator or the bell anywhere but here; the modal tools that have a bar
/// of their own are named there, each with its reason.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app.dart' show appTheme;
import 'l10n/app_localizations.dart';
import 'news.dart';
import 'status.dart';

/// The way up (or back) of a page: the arrow at the left of its bar, see the
/// library comment. Its tooltip is "Up" unless the page says otherwise.
class WayBackButton extends StatelessWidget {
  /// Leaves the page.
  final VoidCallback? onPressed;

  /// What the arrow says on hover, "Up" where `null`.
  final String? tooltip;

  const WayBackButton({super.key, required this.onPressed, this.tooltip});

  @override
  Widget build(BuildContext context) => IconButton(
        icon: const Icon(Icons.arrow_back),
        tooltip: tooltip ?? AppLocalizations.of(context)!.up,
        onPressed: onPressed,
      );
}

/// A title of two lines: the [title], and under it the smaller [subtitle],
/// both left-aligned and as high as they need — so that the first line sits
/// where a one-line title sits on every other page (issue #308). Takes the
/// bar's text colour, black bar or not.
class PageTitle extends StatelessWidget {
  final String title;

  /// The second line; none where empty.
  final String subtitle;

  /// The key of the second line, for whoever looks for it.
  final Key? subtitleKey;

  const PageTitle(
    this.title, {
    super.key,
    this.subtitle = "",
    this.subtitleKey,
  });

  @override
  Widget build(BuildContext context) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
          if (subtitle.isNotEmpty)
            Text(
              subtitle,
              key: subtitleKey,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12),
            ),
        ],
      );
}

/// The status indicator and the bell — on the top-level page alone, see the
/// library comment: nothing where [topLevel] is false. Each of them shows
/// itself only to whoever it has something for.
List<Widget> topLevelStatus({required bool topLevel}) => [
      if (topLevel) ...[
        // What runs and what failed (issue #305).
        const StatusIndicator(),
        // What arrived from others (issue #268).
        const NewsBellButton(),
      ],
    ];

/// The [AppBar] of a page, see the library comment.
class PageAppBar extends StatelessWidget implements PreferredSizeWidget {
  /// The way up, a [WayBackButton] — or the way out of a mode (the select
  /// mode's close button); `null` where there is nothing to go up to.
  final Widget? leading;

  final bool automaticallyImplyLeading;

  final Widget? title;

  /// What the page offers at the right, the three-dots menu last. Never the
  /// way up, see [leading].
  final List<Widget> actions;

  final PreferredSizeWidget? bottom;

  /// Whether the bar is black with light text, the bar of a page of
  /// photographs (issue #310); otherwise it follows the system's theme.
  final bool black;

  const PageAppBar({
    super.key,
    this.leading,
    this.automaticallyImplyLeading = true,
    this.title,
    this.actions = const [],
    this.bottom,
  }) : black = false;

  /// The bar of a page on black: black, with light text and icons, and the
  /// menus opened from it dark as well (issue #310).
  const PageAppBar.black({
    super.key,
    this.leading,
    this.automaticallyImplyLeading = true,
    this.title,
    this.actions = const [],
    this.bottom,
  }) : black = true;

  @override
  Size get preferredSize =>
      Size.fromHeight(kToolbarHeight + (bottom?.preferredSize.height ?? 0));

  /// The theme under a black bar: the app's dark theme, whose bar is black.
  /// The menus, tooltips and badges of the bar take it as well, so that a
  /// menu opened from the bar of a light app is dark like the page under it.
  static final ThemeData blackTheme = _blackTheme();

  static ThemeData _blackTheme() {
    var dark = appTheme(brightness: Brightness.dark);
    return dark.copyWith(
      appBarTheme: dark.appBarTheme.copyWith(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        systemOverlayStyle: SystemUiOverlayStyle.light,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    assert(
      !actions.any((action) => action is WayBackButton),
      "the way up is the leading control, never an action (issue #308)",
    );
    var bar = AppBar(
      leading: leading,
      automaticallyImplyLeading: automaticallyImplyLeading,
      title: title,
      // At the left on every platform, iOS included (issue #308).
      centerTitle: false,
      backgroundColor: black ? Colors.black : null,
      foregroundColor: black ? Colors.white : null,
      surfaceTintColor: black ? Colors.transparent : null,
      bottom: bottom,
      actions: actions,
    );
    return black ? Theme(data: blackTheme, child: bar) : bar;
  }
}
