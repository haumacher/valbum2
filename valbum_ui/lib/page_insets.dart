import 'package:flutter/widgets.dart';

/// The padding of a page body that scrolls: [base], the page's own padding,
/// plus what the system keeps covered at the edges of the screen (issue #171).
///
/// Android 15 draws every app edge to edge, so the navigation bar lies over
/// the bottom of the window — or over its side, in landscape — and only what
/// the app keeps clear stays reachable. A [ListView] adds the
/// [MediaQuery.paddingOf] on its own only while it is given *no* padding, and
/// a [SingleChildScrollView] never does; a page that sets its own padding
/// therefore asks here, so that its last control scrolls clear of the bar.
///
/// All four sides are added. Under an [AppBar] the [Scaffold] has already
/// taken the top inset out of its body's [MediaQuery], so nothing is doubled
/// there; a page without an app bar (the invitation) gets the status bar kept
/// clear too. On the web and on a desktop the padding is zero and the page
/// looks exactly as it did.
EdgeInsets pagePadding(
  BuildContext context, [
  EdgeInsets base = EdgeInsets.zero,
]) =>
    base + MediaQuery.paddingOf(context);
