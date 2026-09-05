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
String? serverUrlError(String serverUrl) {
  if (serverUrl.trim().isEmpty) {
    return "Enter the URL of the album server, e.g. "
        "'http://nas.local:8080/valbum/'.";
  }
  try {
    dataUrlOf(serverUrl);
    return null;
  } on FormatException catch (error) {
    return error.message;
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
