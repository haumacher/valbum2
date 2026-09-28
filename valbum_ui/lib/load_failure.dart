/// The page of a view that could not be loaded, in the reader's terms (issue
/// #176).
///
/// Every level of the app — the listing, an album, an inbox, the viewer, a
/// group, the persons page and the trash page — is a `VAlbumView` loading the
/// listing or album its route lives in, and every one of them shows a failed
/// load through [LoadFailureView]. The page names what the reader asked for —
/// the album or folder the route's path names — and the server they asked, as
/// they know it (the app base, `http://host/valbum/`), and quotes the server's
/// own reason where it gave one. The address of the JSON API is an internal
/// affair of the app: it stands in the expandable [detailsKey] line and in the
/// diagnostics log, never in the headline, because a reader who typed
/// `…/valbum/` does not recognise `…/data/valbum/?type=json` as theirs.
///
/// Only an *answer* of the server gets that headline. Where no answer came —
/// the server could not be reached and nothing is cached, or what answered is
/// no album server at all — the sentence the client composed says it best and
/// is shown as before, see [VAlbumException.status].
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'routes.dart';

/// The key of the "Go to the start page" button.
const Key goToStartKey = Key("load-failure-home");

/// The key of the expandable line carrying the technical address.
const Key detailsKey = Key("load-failure-details");

/// The key of the headline.
const Key headlineKey = Key("load-failure-headline");

/// What a failed load of [route] says as its headline.
///
/// [server] is the app base the reader knows the server by. An album-only
/// level (the viewer, a group, the persons and the trash page) names an
/// album; a listing-or-album route cannot know which of the two its path
/// named, so it says both; the root is the start page.
String loadFailureHeadline(
  AppLocalizations l10n,
  VAlbumRoute route,
  Object? error,
  String server,
) {
  var refusal = error is VAlbumException ? error : null;
  if (refusal?.status == null) {
    return l10n.loadingFailed("${error ?? l10n.noDataLoaded}");
  }
  var notFound = refusal!.status == 404;
  var path = route.albumPath;
  if (path.isEmpty) {
    return notFound
        ? l10n.loadNotFoundStart(server)
        : l10n.loadFailedStart(server);
  }
  var name = path.last;
  if (route is ListingOrAlbumRoute) {
    return notFound
        ? l10n.loadNotFoundEntry(server, name)
        : l10n.loadFailedEntry(server, name);
  }
  return notFound
      ? l10n.loadNotFoundAlbum(server, name)
      : l10n.loadFailedAlbum(server, name);
}

/// The page of a failed load, see the library comment.
class LoadFailureView extends StatelessWidget {
  /// The view that could not be loaded.
  final VAlbumRoute route;

  /// What went wrong: normally a [VAlbumException].
  final Object? error;

  /// The app base the reader knows the server by, see `appBaseOf`.
  final String server;

  /// Shows the root listing; `null` where the root is what failed.
  final VoidCallback? onHome;

  /// Loads the view again.
  final VoidCallback onRetry;

  /// Opens the server settings.
  final VoidCallback onSettings;

  const LoadFailureView({
    super.key,
    required this.route,
    required this.error,
    required this.server,
    required this.onHome,
    required this.onRetry,
    required this.onSettings,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var refusal = error is VAlbumException ? error as VAlbumException : null;
    var answered = refusal?.status != null;
    var reason = answered ? refusal!.reason : null;
    var url = answered ? refusal!.url : null;
    var home = route.albumPath.isEmpty ? null : onHome;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.appTitle)),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  loadFailureHeadline(l10n, route, error, server),
                  key: headlineKey,
                  textAlign: TextAlign.center,
                  style:
                      answered ? Theme.of(context).textTheme.titleMedium : null,
                ),
                if (reason != null) ...[
                  const SizedBox(height: 8),
                  Text(reason, textAlign: TextAlign.center),
                ],
                const SizedBox(height: 16),
                Wrap(
                  alignment: WrapAlignment.center,
                  spacing: 12,
                  runSpacing: 8,
                  children: [
                    if (home != null)
                      FilledButton.icon(
                        key: goToStartKey,
                        onPressed: home,
                        icon: const Icon(Icons.home),
                        label: Text(l10n.goToStartPage),
                      ),
                    OutlinedButton.icon(
                      onPressed: onSettings,
                      icon: const Icon(Icons.settings),
                      label: Text(l10n.serverSettingsAction),
                    ),
                  ],
                ),
                if (url != null) ...[
                  const SizedBox(height: 16),
                  ExpansionTile(
                    key: detailsKey,
                    title: Text(l10n.loadFailureDetails),
                    childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                    expandedAlignment: Alignment.centerLeft,
                    children: [
                      SelectableText(
                        l10n.loadFailureTechnical(url, refusal!.status!),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: onRetry,
        tooltip: l10n.reload,
        child: const Icon(Icons.update),
      ),
    );
  }
}
