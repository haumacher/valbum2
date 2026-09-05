/// Derivation of the server URL the app talks to.
library;

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
