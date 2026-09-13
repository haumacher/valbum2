/// A share link opened as a session, see issue #51.
///
/// A share link is not a pairing and not an account: the server serves this
/// very app below `<context>/s/<token>/`, and the token in that path is the
/// bearer of every request the session makes. Three things follow, and this
/// library is where they are said once:
///
///  * the token is **never** written to the settings store — a link names its
///    own server, so neither the stored server URL nor the stored device token
///    is consulted while a session runs, and nothing of the session outlives
///    the page;
///  * there is no edit mode and no way into the server settings: a visitor
///    holds `view` (and at most `download` and `contribute`), so what they may
///    not do is not offered rather than offered and refused, exactly as with
///    the rights of issue #49;
///  * the session is confirmed by the server, not by the URL: `?type=auth`
///    answers an [AuthInfo.share] for a link caller and nothing for anybody
///    else, see [ShareSession.of].
///
/// On the web alone. On the other platforms a link URL is opened in a browser:
/// the app there talks to the server a device is paired with, and a session
/// that may not store its token has nothing to pair. [sessionUrl] returns
/// `null` off the web for that reason.
library;

import 'package:flutter/material.dart';

import 'resource.dart';
import 'rights.dart';
import 'urls.dart';

/// The link session the app is running in: the token it speaks with, and what
/// the server said the link is.
@immutable
class ShareSession {
  /// Where the app was loaded from, see [SessionUrl].
  final SessionUrl url;

  /// What the server said about the link, see [AuthInfo.share].
  final ShareInfo info;

  /// Whether the link allows contributions, see [AuthInfo.writeAllowed].
  final bool writeAllowed;

  const ShareSession({
    required this.url,
    required this.info,
    required this.writeAllowed,
  });

  /// The token every request of this session carries.
  String get token => url.token;

  /// How the link is named on the screen.
  ///
  /// The label the author gave it, and where they gave it none the name of
  /// the album it opens — the last segment of the canonical
  /// `~<owner>/<path>` of [ShareInfo.path]. A link to the root of a space has
  /// neither, and is named by what it is.
  String get label {
    var given = info.label.trim();
    if (given.isNotEmpty) {
      return given;
    }
    var target = targetName;
    return target.isEmpty ? "Shared album" : target;
  }

  /// The name of the album the link opens, empty if the link opens a whole
  /// space.
  String get targetName {
    var segments = splitCanonicalPath(info.path);
    return segments.isEmpty ? "" : segments.last;
  }

  /// What the link allows, with the implications of issue #49 applied.
  Rights get rights => Rights.ofNames([for (var r in info.rights) r.name]);

  /// The link session of the enclosing app, `null` in an ordinary session.
  ///
  /// This is the one question every view asks: an app that answers `null`
  /// here is the app as it always was.
  static ShareSession? of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ShareSessionScope>()?.session;

  /// The link session, asked without becoming dependent on it.
  ///
  /// For the places that ask in `initState`, where a dependency may not be
  /// registered: whether this app is a link session is decided before the
  /// first view is built and never changes again.
  static ShareSession? peek(BuildContext context) =>
      context.getInheritedWidgetOfExactType<ShareSessionScope>()?.session;
}

/// The segments of a canonical `~<owner>/<path>`, the owner segment included.
List<String> splitCanonicalPath(String path) => [
      for (var segment in path.split("/"))
        if (segment.isNotEmpty) segment,
    ];

/// Publishes the [ShareSession] to the widget tree, see [ShareSession.of].
class ShareSessionScope extends InheritedWidget {
  /// The session, `null` in an ordinary run of the app.
  final ShareSession? session;

  const ShareSessionScope({
    super.key,
    required this.session,
    required super.child,
  });

  @override
  bool updateShouldNotify(ShareSessionScope oldWidget) =>
      session != oldWidget.session;
}

/// Whether the app is running inside a share link.
bool inShareSession(BuildContext context) => ShareSession.of(context) != null;

/// How a rating of the album filter's scale is named, see `album_edit.dart`.
///
/// The names the tile toolbar gives the levels, so that a link's rating floor
/// is spelled in the vocabulary the album already uses.
const Map<int, String> ratingNames = {
  2: "Sehr gut",
  1: "Gut",
  0: "Ohne Bewertung",
  -1: "Schlecht",
  -2: "Papierkorb",
};

/// The rating floor of a share link, in one phrase.
String ratingFloorLabel(int minRating) => minRating <= -2
    ? "every photo"
    : "${ratingNames[minRating] ?? "$minRating"} and better";

/// A page of a link session that says one thing and offers one way on.
///
/// Deliberately bare: a visitor of a link has no server settings to open, no
/// device to sign in and nothing to reload their way out of, so the page
/// carries an icon, the server's own sentence and at most one button. The
/// wording is the server's — it is the one that knows whether a link expired
/// or was withdrawn, and whether a path lies outside what the link opens.
class SharePlainPage extends StatelessWidget {
  /// The icon above the message.
  final IconData icon;

  /// The server's own sentence.
  final String message;

  /// The one way on, `null` where there is none.
  final Widget? action;

  const SharePlainPage({
    super.key,
    required this.icon,
    required this.message,
    this.action,
  });

  @override
  Widget build(BuildContext context) => Scaffold(
        key: const Key("share-plain-page"),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: 48),
                  const SizedBox(height: 16),
                  Text(
                    message,
                    key: const Key("share-plain-message"),
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (action != null) ...[
                    const SizedBox(height: 24),
                    action!,
                  ],
                ],
              ),
            ),
          ),
        ),
      );
}

/// The page of a link that expired or was withdrawn: the server's `410`.
///
/// Nothing but the sentence: there is no remedy the visitor holds — the link
/// is gone, and only the person who made it can make another one.
class ShareGoneScreen extends StatelessWidget {
  /// The server's reason, which tells expired from withdrawn.
  final String message;

  const ShareGoneScreen({super.key, required this.message});

  @override
  Widget build(BuildContext context) => SharePlainPage(
        key: const Key("share-gone"),
        icon: Icons.link_off,
        message: message,
      );
}

/// The page of a path outside what the link opens: the server's `404`.
///
/// Reached by a deep link edited by hand, or by a bookmark from another
/// session. The one way on is the link's own root, which always works.
class ShareConfinedScreen extends StatelessWidget {
  /// The server's reason.
  final String message;

  /// Goes to the root of the shared subtree.
  final VoidCallback onHome;

  const ShareConfinedScreen({
    super.key,
    required this.message,
    required this.onHome,
  });

  @override
  Widget build(BuildContext context) => SharePlainPage(
        key: const Key("share-confined"),
        icon: Icons.search_off,
        message: message,
        action: FilledButton.icon(
          key: const Key("share-home"),
          onPressed: onHome,
          icon: const Icon(Icons.home),
          label: const Text("Back to the shared album"),
        ),
      );
}
