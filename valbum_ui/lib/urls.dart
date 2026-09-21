/// Derivation of the server URL the app talks to.
///
/// ## Where a server lives
///
/// A single-space server serves the app at its context path and its API in the
/// `data` folder below it:
///
///  * app `https://host/valbum/`, API `https://host/valbum/data`,
///  * a share link `https://host/valbum/s/<token>/`,
///  * an invitation `https://host/valbum/i/<token>/`.
///
/// A multi-space server (Phase 6, issue #85) puts everything of one space
/// below the space's own segment, and nothing else changes:
///
///  * app `https://host/valbum/alice/`, API `https://host/valbum/alice/data`,
///  * a share link `https://host/valbum/alice/s/<token>/`,
///  * an invitation `https://host/valbum/alice/i/<token>/`.
///
/// Nothing here has to *know* which of the two it is looking at, and that is
/// the point: every derivation in this library is relative to the app base —
/// the directory the app was served from — so a space is simply one more
/// segment of that base, exactly like a context path of several segments
/// (`https://host/photos/deep/alice/`). See [sessionUrl] for the one rule
/// that reads a path rather than deriving from it.
library;

import 'l10n/app_localizations.dart';

/// The data URL used on platforms that cannot derive it from their own origin.
///
/// A settings screen replacing this default is planned (ROADMAP phase 2).
const String defaultDataUrl = "http://localhost:9090/valbum/data";

/// Derives the URL of the JSON API from the location the app was loaded from.
///
/// On the web, the Flutter app is served by the album server itself as static
/// files below its context path (`<origin><contextPath>/`), while the API lives
/// at `<origin><contextPath>/data`. Therefore the data URL is derived from
/// [base] (normally `Uri.base`, which on the web is the document location) by
/// replacing the last path segment with `data`:
///
///  * `http://host:8080/valbum/` becomes `http://host:8080/valbum/data`,
///  * `https://host/valbum/index.html` becomes `https://host/valbum/data`.
///
/// The app installs no routing, so the document location always *is* the
/// application base and the directory part of [base] is the context path. If
/// deep links are added later (ROADMAP issue #24), the location no longer
/// reflects the `<base href="...">` that the Flutter web build writes into
/// `web/index.html`; pass that value as [basePath] to keep the derivation
/// correct (`basePath` wins over the directory part of [base]).
///
/// On all other platforms there is no origin to derive anything from, so
/// [fallback] is returned.
String deriveDataUrl(
  Uri base, {
  required bool isWeb,
  String fallback = defaultDataUrl,
  String? basePath,
}) {
  if (!isWeb) {
    return fallback;
  }

  var directory = basePath ?? _directoryOf(base.path);
  if (!directory.endsWith("/")) {
    directory = "$directory/";
  }
  if (!directory.startsWith("/")) {
    directory = "/$directory";
  }

  // Note: Uri.replace() treats null as "keep", so a new Uri is built to drop
  // any query and fragment the document location may carry.
  return Uri(
    scheme: base.scheme,
    userInfo: base.userInfo,
    host: base.host,
    port: base.hasPort ? base.port : null,
    path: "${directory}data",
  ).toString();
}

/// The path up to and including the last `/`, e.g. `/valbum/` for
/// `/valbum/index.html`.
String _directoryOf(String path) {
  var slash = path.lastIndexOf("/");
  return slash < 0 ? "/" : path.substring(0, slash + 1);
}

/// Derives the URL of the JSON API from the server URL a user entered.
///
/// The user names the *app base* of the album server — the location the web
/// app is served from, e.g. `http://nas.local:8080/valbum/` — because that is
/// what a browser shows them. The API lives in the `data` folder below it, so
/// the derivation is the one [deriveDataUrl] performs on the document
/// location, applied to a typed URL:
///
///  * `http://h:8080/valbum` becomes `http://h:8080/valbum/data`,
///  * `http://h:8080/valbum/` becomes the same,
///  * `http://h:8080/valbum/index.html` becomes the same (a last segment
///    carrying a file extension is the index page, not a folder),
///  * `https://h/` becomes `https://h/data`.
///
/// Any query and fragment of [serverUrl] is dropped. Surrounding whitespace is
/// ignored. Throws a [FormatException] if [serverUrl] is not an absolute URL
/// with a host; [serverUrlError] reports the same condition as a message.
String dataUrlOf(String serverUrl) {
  var uri = Uri.parse(serverUrl.trim());
  if (!uri.hasScheme || uri.host.isEmpty) {
    throw FormatException(
      "Not an absolute server URL (expected e.g. 'http://host:8080/valbum/').",
      serverUrl,
    );
  }
  return deriveDataUrl(uri, isWeb: true, basePath: _folderOf(uri.path));
}

/// The reason [serverUrl] cannot be used, or `null` if it can.
///
/// A share link is a reason of its own: it names no server to talk to, so it
/// is refused with [shareLinkRefusal] rather than turned into a data URL that
/// answers nothing. An invitation link *is* usable — it names a server and
/// signs the device in, see [serverLocationOf].
///
/// Every reason is a localized sentence of this app's own. An address that
/// cannot be read is refused with `serverUrlInvalid`, never with the message
/// of the [FormatException] behind it — that one is English whatever the app
/// speaks, and where `Uri.parse` threw it, it is English *and* a parser's
/// wording ("Invalid empty scheme"). [serverLocationOf] goes on throwing what
/// it likes; what it throws is for a caller, not for a reader.
String? serverUrlError(AppLocalizations l10n, String serverUrl) {
  if (serverUrl.trim().isEmpty) {
    return l10n.serverUrlEmpty;
  }
  try {
    if (serverLocationOf(serverUrl).isShare) {
      return shareLinkRefusal(l10n);
    }
    return null;
  } on FormatException {
    // The parser's own sentence is for whoever *catches* it; what is shown
    // here is this app's sentence, in this app's language (issue #108).
    return l10n.serverUrlInvalid;
  }
}

/// The folder part of the path of a server URL, with a trailing slash.
///
/// A path already ending in `/` is a folder; a last segment carrying a file
/// extension (`index.html`) is stripped; anything else is the folder itself
/// (`/valbum` is the folder `/valbum/`).
String _folderOf(String path) {
  if (path.isEmpty || path == "/") {
    return "/";
  }
  if (path.endsWith("/")) {
    return path;
  }
  var last = path.substring(path.lastIndexOf("/") + 1);
  return last.contains(".") ? _directoryOf(path) : "$path/";
}

/// The path segment below which the server serves the app for a share link,
/// see issue #51 (`<base>/s/<token>/`, where `<base>` is the context path and,
/// on a multi-space server, the space below it).
const String shareUrlSegment = "s";

/// The path segment below which the server serves the app for an invitation,
/// see issue #52 (`<base>/i/<token>/`, see [shareUrlSegment]).
const String invitationUrlSegment = "i";

/// What a token in the app base opens: a share link, or an invitation.
///
/// The two are one mechanism with two ends. Both are a token the server put
/// into the *path* the app is served under, both are read off the app base and
/// both are sent as the bearer of the first request; what differs is what the
/// server answers to `?type=auth` and therefore what the app does next — a
/// share link becomes a session that browses one album, an invitation becomes
/// a sign-in that ends in a device token of one's own.
enum SessionKind {
  /// A share link, `<context>/s/<token>/`, see `share_session.dart`.
  share(shareUrlSegment),

  /// An invitation, `<context>/i/<token>/`, see `invitation.dart`.
  invitation(invitationUrlSegment);

  /// The path segment the server serves this kind under.
  final String segment;

  const SessionKind(this.segment);

  /// The kind served under [segment], `null` for any other segment.
  static SessionKind? ofSegment(String segment) {
    for (var kind in values) {
      if (kind.segment == segment) {
        return kind;
      }
    }
    return null;
  }
}

/// A token session recognised in the location the web app was loaded at.
///
/// A share link and an invitation are both a *session*, not a device setting:
/// the server serves the very same web app below `<context>/s/<token>/` and
/// `<context>/i/<token>/`, and the token in that path is the bearer of what
/// the session does. The app therefore never stores it — see
/// `share_session.dart` and `invitation.dart`.
class SessionUrl {
  /// What this session is, see [SessionKind].
  final SessionKind kind;

  /// The session token, the segment after `/s/` or `/i/`.
  final String token;

  /// The URL of the JSON API of the server serving this session, without a
  /// trailing slash.
  final String dataUrl;

  /// The app base of the session, `<context>/<segment>/<token>/`.
  ///
  /// The route base stays inside a share link, so a deep link
  /// `<context>/s/<token>/<album>/` parses and is written back in that form.
  /// An invitation has no routes at all: it ends at [appBase] once the device
  /// is signed in.
  final String basePath;

  const SessionUrl({
    required this.kind,
    required this.token,
    required this.dataUrl,
    required this.basePath,
  });

  /// Whether this is a share link, see [SessionKind.share].
  bool get isShare => kind == SessionKind.share;

  /// Whether this is an invitation, see [SessionKind.invitation].
  bool get isInvitation => kind == SessionKind.invitation;

  /// The app base of the server serving this session, `<context>/`.
  ///
  /// Where an accepted invitation leaves for: the ordinary app of this very
  /// server, without the token in the path, see `invitation.dart`.
  String get appBase => appBaseOf(dataUrl);

  @override
  String toString() => "SessionUrl(${kind.name} $token at $dataUrl)";
}

/// The session the app was loaded into, `null` for an ordinary start.
///
/// The input is the **app base** — the `<base href>` the server rebased for
/// the session, as [appBasePath] reads it off the document location — not the
/// document location itself: inside a share link the location is the album
/// being looked at, and only the base still says which token this is. Where
/// [basePath] is omitted the directory part of [base] is used, which is the
/// app base of a start page.
///
/// ## The rule
///
/// **The last two segments decide, and nothing else does.** A base ending in
/// `/s/<segment>/` or `/i/<segment>/` is a session; the segment is the token,
/// and *everything before those two segments* is the base the session belongs
/// to — the context path, plus the space on a multi-space server (issue #85).
/// Any number of leading segments is allowed, zero included:
///
///  * `/valbum/s/abc/` at `http://h:8080` yields the token `abc` and
///    `http://h:8080/valbum/data`,
///  * `/valbum/alice/s/abc/` yields `http://h:8080/valbum/alice/data`, the
///    space `alice` being part of the base like any other segment,
///  * `/photos/deep/alice/i/abc/` yields `http://h:8080/photos/deep/alice/data`,
///  * `/i/abc/` yields `http://h:8080/data` — a server at its root.
///
/// The app does not count segments and cannot: how deep a context path is, and
/// whether the last segment before `/s/` is a space or part of the context, is
/// the server's business, and both answers lead to the same data URL and the
/// same app base. What [SessionUrl.appBase] answers — `http://h:8080/valbum/`
/// or `http://h:8080/valbum/alice/` — is therefore always the plain app of the
/// very server the session came from, which is where an accepted invitation
/// returns to, see `platform_web.dart`.
///
/// An empty segment (`/valbum/s/`) is not a token and yields `null`, as does
/// every base that carries neither segment.
///
/// ## The one ambiguity, and how it is settled
///
/// `<context>/s/<token>/` on a single-space server and `<context>/<space>/…`
/// where the space is *named* `s` would be the same string. The server settles
/// it and not the app: `s` and `i` are reserved and no space may be called
/// either. So this function reads `…/s/<x>/` and `…/i/<x>/` as a session
/// always, and a non-empty `<x>` is a token whatever it looks like — tokens
/// are opaque, and a shape rule here would refuse tokens a future server hands
/// out.
SessionUrl? sessionUrl(Uri base, {String? basePath}) {
  var path = basePath ?? _directoryOf(base.path);
  var segments = [
    for (var segment in path.split("/"))
      if (segment.isNotEmpty) segment,
  ];
  if (segments.length < 2) {
    return null;
  }
  var kind = SessionKind.ofSegment(segments[segments.length - 2]);
  if (kind == null) {
    return null;
  }
  var token = segments.last;
  if (token.isEmpty) {
    return null;
  }
  var context = segments.sublist(0, segments.length - 2);
  var contextPath = context.isEmpty ? "/" : "/${context.join("/")}/";
  return SessionUrl(
    kind: kind,
    token: token,
    dataUrl: deriveDataUrl(base, isWeb: true, basePath: contextPath),
    basePath: "$contextPath${kind.segment}/$token/",
  );
}

/// The app base of the server whose JSON API lives at [dataUrl].
///
/// The inverse of [dataUrlOf]: `http://h/valbum/data` is served from
/// `http://h/valbum/`. This is the URL a device stores — never the `/i/…` form
/// an invitation arrives as, see [ServerLocation].
String appBaseOf(String dataUrl) {
  var uri = Uri.parse(dataUrl);
  var directory = _directoryOf(uri.path);
  return Uri(
    scheme: uri.scheme,
    userInfo: uri.userInfo,
    host: uri.host,
    port: uri.hasPort ? uri.port : null,
    path: directory.isEmpty ? "/" : directory,
  ).toString();
}

/// What a URL somebody entered in the server field names, see
/// [serverLocationOf].
class ServerLocation {
  /// The app base of the album server, with a trailing slash — the value a
  /// device stores, never the `/i/…` or `/s/…` form a link arrives as.
  final String serverUrl;

  /// The URL of the JSON API of that server, without a trailing slash.
  final String dataUrl;

  /// The invitation token the entered URL carried, empty if it carried none.
  final String invitation;

  /// The share token the entered URL carried, empty if it carried none.
  ///
  /// A share link is no sign-in: it is refused in the server field with
  /// [shareLinkRefusal], and this is what says that it was one.
  final String share;

  const ServerLocation({
    required this.serverUrl,
    required this.dataUrl,
    this.invitation = "",
    this.share = "",
  });

  /// Whether the entered URL was an invitation link.
  bool get isInvitation => invitation.isNotEmpty;

  /// Whether the entered URL was a share link.
  bool get isShare => share.isNotEmpty;
}

/// Whether two server addresses name the same server (issue #91).
///
/// Spelling, not identity: a trailing slash and the case of the host say
/// nothing, so `http://Nas.local/valbum` and `http://nas.local/valbum/` are
/// the same address. Anything that cannot be parsed is compared as text,
/// which answers `false` for two different strings and never claims a match
/// nobody can check.
bool sameServer(String one, String other) {
  String canonical(String url) {
    try {
      var uri = Uri.parse(url.trim());
      var path = uri.path.endsWith("/") ? uri.path : "${uri.path}/";
      return Uri(
        scheme: uri.scheme.toLowerCase(),
        host: uri.host.toLowerCase(),
        port: uri.hasPort ? uri.port : null,
        path: path,
      ).toString();
    } on FormatException {
      return url.trim();
    }
  }

  return canonical(one) == canonical(other);
}

/// The sentence a share link is refused in the server field with.
///
/// A share link opens one album in a browser and signs nothing in; pasting it
/// where a server is named would store a URL that works for nobody, so it is
/// said rather than silently stripped.
String shareLinkRefusal(AppLocalizations l10n) => l10n.shareLinkRefusal;

/// What the URL [entered] in the server field names: the server, and the token
/// it carried, if any.
///
/// The sibling of [dataUrlOf] for the field a person pastes into. Somebody who
/// was invited gets `http://h/valbum/i/<token>/` by message and pastes it
/// where the app asks for a server; what is stored is the server
/// (`http://h/valbum/`) and what signs the device in is the token, which is
/// never stored, see issue #52.
///
/// A plain URL is read exactly as [dataUrlOf] reads it and carries no token. A
/// share link (`/s/<token>/`) is recognised as well, so that the screen can
/// say what it is instead of storing a server that does not exist.
///
/// A space is part of the server, not something this has to know about: both
/// `https://h/valbum/` and `https://h/valbum/alice/` are stored as they are,
/// and an invitation into a space (`https://h/valbum/alice/i/<token>/`) stores
/// `https://h/valbum/alice/`, see [sessionUrl] and issue #85.
///
/// Throws a [FormatException] if [entered] is not an absolute URL with a host.
ServerLocation serverLocationOf(String entered) {
  var uri = Uri.parse(entered.trim());
  if (!uri.hasScheme || uri.host.isEmpty) {
    throw FormatException(
      "Not an absolute server URL (expected e.g. 'http://host:8080/valbum/').",
      entered,
    );
  }
  var folder = _folderOf(uri.path);
  var session = sessionUrl(uri, basePath: folder);
  if (session == null) {
    var dataUrl = deriveDataUrl(uri, isWeb: true, basePath: folder);
    return ServerLocation(serverUrl: appBaseOf(dataUrl), dataUrl: dataUrl);
  }
  return ServerLocation(
    serverUrl: session.appBase,
    dataUrl: session.dataUrl,
    invitation: session.isInvitation ? session.token : "",
    share: session.isShare ? session.token : "",
  );
}

/// The absolute form of a server-relative [path] (`/valbum/s/tok/`) against
/// the origin of [dataUrl].
///
/// The server answers the URL of a new share link relative to itself: which
/// name it was reached under is the client's business. On the web that origin
/// is the page origin (the app was loaded from the server it talks to); on
/// every other platform it is the origin of the configured server URL — and
/// both are the origin of the data URL the client uses.
String absoluteServerUrl(String dataUrl, String path) {
  var server = Uri.parse(dataUrl);
  var target = Uri.parse(path);
  return Uri(
    scheme: server.scheme,
    userInfo: server.userInfo,
    host: server.host,
    port: server.hasPort ? server.port : null,
    path: target.path,
  ).toString();
}
