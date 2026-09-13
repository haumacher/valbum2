/// The diagnostics log (issue #58): what the app did on the network, kept in
/// memory so that a user can copy it and paste it into a bug report.
///
/// A name that does not resolve on one network and resolves in the browser on
/// the very same phone is not something guesswork settles: what is needed is
/// the OS error, and which of the two lookups Dart performs (IPv4 and IPv6 are
/// asked separately) answered what. The app therefore keeps a bounded log of
/// every request it makes — method, URL, and the status or the *complete*
/// transport error — plus the steps of the connection test, and hands it over
/// as one block of text.
///
/// Nothing secret is ever written here: no request bodies at all (the pairing
/// request carries the secret), never the value of an `Authorization` header
/// (only whether there was one), and every token in a URL path is masked, see
/// [maskUrl].
library;

import 'dart:collection';

import 'package:flutter/foundation.dart';

import 'urls.dart';

/// The version of the app, as `pubspec.yaml` spells it.
///
/// A constant rather than a `package_info_plus` lookup: the plugin would be a
/// dependency and a platform channel for one line of a bug report, and the
/// version is in the build anyway. Keep it in step with the `version:` of
/// `pubspec.yaml`.
const String appVersion = "1.0.0+1";

/// How many entries a [DiagnosticsLog] keeps.
const int diagnosticsCapacity = 300;

/// One line of the [DiagnosticsLog]: when, and what.
@immutable
class DiagnosticsEntry {
  /// When the entry was written.
  final DateTime at;

  /// What happened, already free of anything secret.
  final String message;

  const DiagnosticsEntry(this.at, this.message);

  /// The entry as it is shown and copied: `hh:mm:ss.mmm  message`.
  @override
  String toString() => "${_two(at.hour)}:${_two(at.minute)}:${_two(at.second)}"
      ".${_three(at.millisecond)}  $message";

  static String _two(int value) => value.toString().padLeft(2, "0");

  static String _three(int value) => value.toString().padLeft(3, "0");
}

/// What the app did on the network, the last [capacity] entries of it.
///
/// One instance per app, handed to every [VAlbumClient] the app builds (the
/// way the offline state and the cache are), so that every request lands in
/// the same log whichever server it went to. A [ChangeNotifier], so that the
/// diagnostics section of the settings screen shows what arrives while it is
/// open.
class DiagnosticsLog extends ChangeNotifier {
  /// How many entries are kept; the oldest are dropped beyond it.
  final int capacity;

  /// The clock, so that a test can pin the timestamps.
  final DateTime Function() now;

  final Queue<DiagnosticsEntry> _entries = Queue<DiagnosticsEntry>();

  DiagnosticsLog({
    this.capacity = diagnosticsCapacity,
    DateTime Function()? now,
  }) : now = now ?? DateTime.now;

  /// What is in the log, oldest first.
  List<DiagnosticsEntry> get entries => List.unmodifiable(_entries);

  /// Whether nothing has been logged yet.
  bool get isEmpty => _entries.isEmpty;

  /// Adds [message] as the newest entry, dropping the oldest beyond
  /// [capacity].
  void add(String message) {
    _entries.addLast(DiagnosticsEntry(now(), message));
    while (_entries.length > capacity) {
      _entries.removeFirst();
    }
    notifyListeners();
  }

  /// Logs a request the server answered, whatever it answered.
  ///
  /// The status is the point: a `401` is the server speaking, and the log has
  /// to tell that apart from a request that never arrived, see [failed].
  void answered(
    String method,
    String url, {
    required int status,
    bool bearer = false,
  }) =>
      add("$method ${maskUrl(url)} ${_bearer(bearer)}-> $status");

  /// Logs a request that never got an answer, with the *complete* text of the
  /// failure.
  ///
  /// `error.toString()`, not a headline: a `SocketException` carries the OS
  /// message and its errno, and that is the one thing issue #58 is about.
  void failed(
    String method,
    String url, {
    required Object error,
    bool bearer = false,
  }) =>
      add("$method ${maskUrl(url)} ${_bearer(bearer)}!! $error");

  static String _bearer(bool bearer) => bearer ? "(bearer) " : "";

  /// Forgets everything logged so far.
  void clear() {
    if (_entries.isEmpty) {
      return;
    }
    _entries.clear();
    notifyListeners();
  }

  /// The whole log with its header, as it goes to the clipboard.
  ///
  /// [platform] is what the machine says about itself (see
  /// `platformDescription` of `platform.dart`), [serverUrl] the server the
  /// device is configured for — the header exists so that a pasted log says
  /// *where* it was taken, not only what happened.
  String copyText({
    String? serverUrl,
    String platform = "unknown",
    String version = appVersion,
    DateTime? at,
  }) {
    var stamp = at ?? now();
    var buffer = StringBuffer()
      ..writeln("VAlbum diagnostics")
      ..writeln("App: $version")
      ..writeln("Platform: $platform")
      ..writeln(
        "Server: ${serverUrl == null || serverUrl.isEmpty ? "(none)" : maskUrl(serverUrl)}",
      )
      ..writeln("Copied: ${stamp.toIso8601String()}")
      ..writeln("Entries: ${_entries.length} (of $capacity)")
      ..writeln();
    if (_entries.isEmpty) {
      buffer.writeln("(nothing logged yet)");
    }
    for (var entry in _entries) {
      buffer.writeln(entry.toString());
    }
    return buffer.toString();
  }
}

/// A token as a log shows it: enough to recognise it, not enough to use.
///
/// The same rule the settings screen shows a pasted invitation with, see
/// `maskedToken` there — a log is pasted into a bug report, and a share link
/// or an invitation in it would be a working key for whoever reads it.
String maskToken(String token) {
  if (token.length <= 4) {
    return "•" * token.length;
  }
  return "${token.substring(0, 2)}${"•" * (token.length - 4)}"
      "${token.substring(token.length - 2)}";
}

/// The URL as the log writes it: every token in the path masked.
///
/// A share link (`…/s/<token>/`) and an invitation (`…/i/<token>/`) carry
/// their secret in the *path*, so a URL is never logged as it is. Everything
/// else is kept verbatim — the host and the path are what a lookup failure is
/// about.
String maskUrl(String url) {
  Uri uri;
  try {
    uri = Uri.parse(url);
  } catch (_) {
    return url;
  }
  // Split on the slashes rather than `pathSegments`: the empty segments a
  // leading or trailing slash produces must come back exactly where they
  // were, or the log would show a URL the app never asked for.
  var segments = uri.path.split("/");
  var masked = false;
  for (var i = 0; i < segments.length - 1; i++) {
    var kind = SessionKind.ofSegment(segments[i]);
    if (kind != null && segments[i + 1].isNotEmpty) {
      segments[i + 1] = maskToken(segments[i + 1]);
      masked = true;
    }
  }
  if (!masked) {
    return url;
  }
  return uri
      .replace(path: segments.join("/"))
      .toString()
      // `Uri` percent-encodes the mask; the log shows the dots.
      .replaceAll("%E2%80%A2", "•");
}
