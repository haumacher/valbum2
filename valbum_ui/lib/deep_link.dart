/// Opening a place of the album in the app (issue #275): the link that names
/// it, the app receiving that link, and the web app's offer to follow it.
///
/// ## The link
///
/// `valbum://open?server=<server>&path=<route>`, nothing else:
///
///  * `server` is the **app base** of the album server — what a browser shows
///    and what a device stores, `https://foto.example.de/valbum/`, see
///    [ServerLocation.serverUrl]. Read leniently: the page URL
///    (`…/valbum`, `…/valbum/index.html`), the data URL (`…/valbum/data`) and
///    the overview of a contact (`…/valbum/shared/`) name the same server.
///    A share link or an invitation is refused — they carry a token, and a
///    token never travels in this link. Only `http` and `https` are taken,
///    and nothing with credentials in it.
///  * `path` is the location of a route below the app base, exactly as
///    [routeToUri] writes it (`/2005-08-24%20Blumen/IMG_0417.JPG`); a raw,
///    unencoded path with spaces and umlauts is read as well. Missing, it is
///    the root listing.
///
/// Every other parameter is ignored — a `token=` included.
///
/// ## Why a scheme of its own
///
/// Android App Links (and iOS Universal Links) need the domains declared in
/// the app at build time, and every VAlbum server has its own. So the Android
/// app registers the scheme `valbum` (`AndroidManifest.xml`), and Flutter's
/// own deep linking (on by default) hands the link to the app: as the initial
/// route on a cold start, through `pushRouteInformation` while it runs. No
/// plugin is needed for that; [AppLinkReceiver] takes the link before the
/// router would read it as a location.
///
/// ## The offer in the browser
///
/// A browser on Android cannot know whether the app is installed. Chrome's
/// `intent:` link asks the system: installed, the app opens at the place;
/// not installed, the browser goes to the fallback, the store listing of the
/// app ([intentLinkFor]). [OpenInAppBanner] carries it, shown where
/// [offersOpenInApp] says so.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/link.dart';

import 'contact_session.dart';
import 'dialog_style.dart';
import 'l10n/app_localizations.dart';
import 'platform.dart';
import 'routes.dart';
import 'selectable.dart';
import 'urls.dart';

/// The scheme the Android app registers.
const String appLinkScheme = "valbum";

/// The one action of the scheme, its host: `valbum://open?…`.
const String appLinkAction = "open";

/// The parameter naming the server, see the library comment.
const String appLinkServerParameter = "server";

/// The parameter naming the place, see the library comment.
const String appLinkPathParameter = "path";

/// The application id of the Android app, what Chrome looks the app up by
/// (`android/app/build.gradle.kts`).
const String androidApplicationId = "de.haumacher.valbum";

/// Where a browser goes when the app is not installed: the releases of the
/// project, which carry the APK, until the app is listed in the Play Store.
const String appInstallUrl = "https://github.com/haumacher/valbum2/releases";

/// A place of an album server, read off a `valbum://open` link.
@immutable
class AppLink {
  /// The app base of the server, with a trailing slash, see
  /// [ServerLocation.serverUrl].
  final String serverUrl;

  /// The route the link opens.
  final VAlbumRoute route;

  const AppLink({required this.serverUrl, required this.route});

  /// The URL of the JSON API of [serverUrl].
  String get dataUrl => dataUrlOf(serverUrl);

  /// The server as a person names it: its host, and its port where it has
  /// one.
  String get host {
    var uri = Uri.parse(serverUrl);
    return uri.hasPort ? "${uri.host}:${uri.port}" : uri.host;
  }

  @override
  bool operator ==(Object other) =>
      other is AppLink && other.serverUrl == serverUrl && other.route == route;

  @override
  int get hashCode => Object.hash(serverUrl, route);

  @override
  String toString() => "AppLink($serverUrl,${route.path})";
}

/// Whether [uri] is a link of the app's own scheme, valid or not.
bool isAppLink(Uri uri) => uri.scheme.toLowerCase() == appLinkScheme;

/// The place [uri] opens, `null` if it is no valid `valbum://open` link.
AppLink? parseAppLink(Uri uri) {
  if (!isAppLink(uri) || uri.host.toLowerCase() != appLinkAction) {
    return null;
  }
  Map<String, String> parameters;
  try {
    parameters = uri.queryParameters;
  } on FormatException {
    return null;
  } on ArgumentError {
    return null;
  }
  var serverUrl = appLinkServerOf(parameters[appLinkServerParameter] ?? "");
  if (serverUrl == null) {
    return null;
  }
  return AppLink(
    serverUrl: serverUrl,
    route: parseRoute(_routeLocation(parameters[appLinkPathParameter] ?? "")),
  );
}

/// The app base of the server [value] names, `null` where it names none or
/// carries a token, see the library comment.
String? appLinkServerOf(String value) {
  var text = value.trim();
  if (text.isEmpty) {
    return null;
  }
  Uri uri;
  try {
    uri = Uri.parse(text);
  } on FormatException {
    return null;
  }
  var scheme = uri.scheme.toLowerCase();
  if ((scheme != "http" && scheme != "https") ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty) {
    return null;
  }
  // The data URL names its server: `…/valbum/data` is the API of `…/valbum/`.
  // Without the trailing slash only — a folder `data/` is an app base.
  var path = uri.path;
  if (path == "/data" || path.endsWith("/data")) {
    uri = uri.replace(path: path.substring(0, path.length - "data".length));
  }
  ServerLocation location;
  try {
    location = serverLocationOf(uri.toString());
  } on FormatException {
    return null;
  }
  if (location.isInvitation || location.isShare) {
    return null;
  }
  return location.serverUrl;
}

/// The location of a route the `path` parameter names: one leading slash,
/// and an unencoded path taken as it is.
Uri _routeLocation(String path) {
  var location = "/${path.trim().replaceFirst(RegExp("^/+"), "")}";
  try {
    return Uri.parse(location);
  } on FormatException {
    // A raw `%` that is no escape (`/100% Urlaub/`).
    return Uri(path: location);
  }
}

/// The link opening [route] of the server whose JSON API lives at [dataUrl]
/// in the app.
Uri appLinkFor(String dataUrl, VAlbumRoute route) => Uri(
      scheme: appLinkScheme,
      host: appLinkAction,
      queryParameters: {
        appLinkServerParameter: appBaseOf(dataUrl),
        appLinkPathParameter: routeToUri(route).toString(),
      },
    );

/// Chrome's `intent:` form of [appLink]: the app of [package] where it is
/// installed, [fallback] where it is not.
String intentLinkFor(
  Uri appLink, {
  String package = androidApplicationId,
  String fallback = appInstallUrl,
}) {
  var link = appLink.toString();
  var rest = link.substring(link.indexOf(":") + 1);
  return "intent:$rest#Intent;scheme=${appLink.scheme};package=$package;"
      "S.browser_fallback_url=${Uri.encodeComponent(fallback)};end";
}

/// Takes the `valbum:` links the platform hands the running app (issue
/// #275) before the router would read them as a location.
///
/// Registered first — in the app's `initState`, before any router listens —
/// so it is asked first; it answers `true` for a link of the app's scheme
/// and `false` for every other location, which the router then reads.
class AppLinkReceiver with WidgetsBindingObserver {
  /// What is done with a received link.
  final void Function(Uri link) onLink;

  AppLinkReceiver(this.onLink) {
    WidgetsBinding.instance.addObserver(this);
  }

  /// Stops receiving.
  void dispose() => WidgetsBinding.instance.removeObserver(this);

  @override
  Future<bool> didPushRouteInformation(RouteInformation routeInformation) {
    if (!isAppLink(routeInformation.uri)) {
      return SynchronousFuture(false);
    }
    onLink(routeInformation.uri);
    return SynchronousFuture(true);
  }
}

/// Whether the web app offers "Open in app": in a browser on Android, outside
/// every link session — the app has member sessions only — and until this
/// browser dismissed it.
bool offersOpenInApp({
  required bool isWeb,
  required String userAgent,
  required bool linkSession,
  required bool dismissed,
}) =>
    isWeb && isAndroidAgent(userAgent) && !linkSession && !dismissed;

/// Whether [userAgent] is a browser on Android.
bool isAndroidAgent(String userAgent) =>
    userAgent.toLowerCase().contains("android");

/// The offer "Open in app" of this browser: where it runs, and whether it was
/// dismissed here (`localStorage`, every access guarded — a private window or
/// blocked site data throws, and then the dismissal holds for this page load).
class OpenInAppOffer {
  /// Whether this is the web app.
  final bool isWeb;

  /// The browser's user agent, empty off the web.
  final String userAgent;

  /// Where the dismissal is remembered.
  final StorageArea storage;

  OpenInAppOffer({
    required this.isWeb,
    required this.userAgent,
    required this.storage,
  });

  /// The offer of the platform the app runs on.
  factory OpenInAppOffer.platform() => kIsWeb
      ? OpenInAppOffer(
          isWeb: true,
          userAgent: browserUserAgent(),
          storage: defaultLocalStorage(),
        )
      : OpenInAppOffer(
          isWeb: false, userAgent: "", storage: MemoryStorageArea());

  /// The key the dismissal is stored under.
  static const String dismissedKey = "valbum.openInAppDismissed";

  bool _dismissedHere = false;

  /// Whether the offer was dismissed in this browser.
  bool get dismissed {
    if (_dismissedHere) {
      return true;
    }
    try {
      return storage.get(dismissedKey) != null;
    } catch (_) {
      return false;
    }
  }

  /// Dismisses the offer for good in this browser.
  void dismiss() {
    _dismissedHere = true;
    try {
      storage.set(dismissedKey, "1");
    } catch (_) {
      // Remembered for this page load only.
    }
  }

  /// Whether the offer is shown, see [offersOpenInApp].
  bool shows({required bool linkSession}) => offersOpenInApp(
        isWeb: isWeb,
        userAgent: userAgent,
        linkSession: linkSession,
        dismissed: dismissed,
      );
}

/// The key of the banner "Open in app".
const Key openInAppBannerKey = Key("open-in-app");

/// The key of its button, the `intent:` link.
const Key openInAppButtonKey = Key("open-in-app.open");

/// The key of its dismissal.
const Key openInAppDismissKey = Key("open-in-app.dismiss");

/// The web app's offer to open the current place in the Android app.
///
/// The button is a real link (`<a href="intent:…">` on the web), so the
/// browser follows it as a navigation of its own. [routes] is listened to, so
/// that the link follows the place shown; and the banner looks again once
/// after its first frame, because it is built above the router, before the
/// router has taken the location the page was opened at.
class OpenInAppBanner extends StatefulWidget {
  /// What says that the place changed, `null` where nothing does.
  final Listenable? routes;

  /// The `valbum:` link of the place shown now.
  final Uri Function() appLink;

  /// "Dismiss".
  final VoidCallback onDismiss;

  const OpenInAppBanner({
    super.key = openInAppBannerKey,
    required this.routes,
    required this.appLink,
    required this.onDismiss,
  });

  @override
  State<OpenInAppBanner> createState() => _OpenInAppBannerState();
}

class _OpenInAppBannerState extends State<OpenInAppBanner> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        setState(() {});
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    Widget banner(BuildContext context, Widget? _) => MaterialBanner(
          content: Text(l10n.openInAppLead),
          leading: const Icon(Icons.phone_android),
          actions: [
            Link(
              uri: Uri.parse(intentLinkFor(widget.appLink())),
              target: LinkTarget.self,
              builder: (context, follow) => TextButton(
                key: openInAppButtonKey,
                onPressed: follow,
                child: Text(l10n.openInApp),
              ),
            ),
            TextButton(
              key: openInAppDismissKey,
              onPressed: widget.onDismiss,
              child: Text(l10n.dismiss),
            ),
          ],
        );
    var listened = widget.routes;
    return listened == null
        ? banner(context, null)
        : ListenableBuilder(listenable: listened, builder: banner);
  }
}

/// The key of the question "Connect to <host>?".
const Key connectToServerKey = Key("app-link.connect");

/// The key of its "Connect".
const Key connectToServerYesKey = Key("app-link.connect.yes");

/// Asks whether the app, connected to [current], connects to the server of
/// [link]; `true` for "Connect".
Future<bool> askToConnect(
  BuildContext context, {
  required AppLink link,
  required String current,
}) async {
  var l10n = AppLocalizations.of(context)!;
  var answer = await showAppDialog<bool>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: connectToServerKey,
      title: Text(l10n.appLinkConnectTitle(link.host)),
      content: Text(l10n.appLinkConnectBody(link.serverUrl, current)),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        FilledButton(
          key: connectToServerYesKey,
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.appLinkConnect),
        ),
      ],
    ),
  );
  return answer ?? false;
}
