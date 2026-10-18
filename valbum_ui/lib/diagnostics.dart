/// The diagnostics log (issues #58, #184): the problems the app ran into,
/// kept in memory so that a user can copy them into a bug report.
///
/// **Failures only** (issue #184). A successful request, a routine event and
/// every "answered 200" are not written down: a log that holds every GET
/// buries the one line that matters, and the author's verdict on such a log
/// was that it holds "nothing of any interest at all". What *is* written is a
/// problem, and each entry stands on its own — a headline saying what was
/// attempted and that it failed, and one line per fact under it: the request,
/// what the server answered (status, `Content-Type`, `Content-Length`, the
/// sentence of its `ErrorInfo`), and what the platform said, with its code and
/// its *complete* text (a `SocketException` carries the OS message and its
/// errno, which is what issue #58 was about).
///
/// One exception (issue #299): every camera-roll sync run that had photos to
/// look at leaves one terse timing record — photos seen, hashes remembered
/// and computed, bytes read, checks, uploads, time per phase — see
/// `SyncRunStats` of `camera_roll.dart`. A sync that "feels slow" on a phone
/// is otherwise a story nobody can check; a run that found nothing new
/// writes nothing.
///
/// The entries are bug-report text and stay English whatever the device's
/// language, like the header of [DiagnosticsLog.copyText]: whoever reads a
/// pasted log reads it next to the source code.
///
/// **Every engine of the app writes into the same log.** A camera-roll run on
/// Android is carried out in the engine WorkManager starts (issue #300), an
/// isolate of its own that shares no memory with the app on the screen. So
/// the log is kept in a [DiagnosticsStore] as well — on a device a small file
/// of the application support directory, see `diagnostics_file.dart` — which
/// every engine appends to and the app reads back: at start, when a run
/// elsewhere ends, and when the diagnostics section is opened. In a browser
/// there is one engine and no file, and the log stays in memory.
///
/// Nothing secret is ever written here: no request bodies at all (the pairing
/// request carries the code), never the value of an `Authorization` header
/// (only whether there was one), and every token in a URL path is masked, see
/// [maskUrl].
library;

import 'dart:async';
import 'dart:collection';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

import 'urls.dart';

/// The version of `pubspec.yaml`, a placeholder no release carries: what
/// [appVersionOf] falls back on where the build says nothing (a test).
const String pubspecVersion = "1.0.0+1";

/// The version of the app as the build says it, see [appVersionOf].
final String appVersion = appVersionOf(
  release: const String.fromEnvironment("VALBUM_VERSION"),
  name: const String.fromEnvironment("FLUTTER_BUILD_NAME"),
  number: const String.fromEnvironment("FLUTTER_BUILD_NUMBER"),
);

/// The version a build was made as: the build name and number the Flutter
/// tool defines (`--build-name`/`--build-number`, which the release workflow
/// passes for the APK: `2.15.1+215`); the release version alone where the
/// tool's name is another (`VALBUM_VERSION` of a web release, whose build
/// name the tool takes from `pubspec.yaml`); [pubspecVersion] where the build
/// defines nothing at all (a test).
///
/// Not a hand-kept constant any more: the 2.15.1 APK said "App: 1.0.0+1".
String appVersionOf({
  String release = "",
  String name = "",
  String number = "",
}) {
  if (release.isNotEmpty && release != name) {
    return release;
  }
  if (name.isNotEmpty) {
    return number.isEmpty ? name : "$name+$number";
  }
  return pubspecVersion;
}

/// How many entries a [DiagnosticsLog] keeps.
const int diagnosticsCapacity = 300;

/// What an empty log says, in the box and in the copied text.
const String noProblemsRecorded = "No problems recorded.";

/// How far apart two failures of the same cause may be and still be one
/// entry, see [DiagnosticsLog.add] and issue #184.
const Duration diagnosticsMergeWindow = Duration(seconds: 60);

/// How many of the affected items a merged entry names before "and n more".
const int diagnosticsNamedItems = 3;

/// The *cause* of a failure: what kind of thing was attempted, what the
/// server answered (or that it did not), and what the platform said — and
/// nothing about *which* file it was (issue #184).
///
/// The one definition two entries are merged by, see [DiagnosticsLog.add]:
/// sixty thumbnails of an album that fail because the server is away are one
/// problem, and sixty entries of it would push the one meaningful entry out
/// of the log. [attempt] names the kind of attempt with a word of its own per
/// writer (`request GET type=tn`, `video`, `picture`), so a video failure is
/// never merged into a storm of thumbnails.
String failureCause({
  required String attempt,
  String? answer,
  String? platformError,
}) =>
    "$attempt | ${answer ?? "no answer"} | ${platformError ?? "no error"}";

/// One problem of the [DiagnosticsLog]: when, what failed, and the facts —
/// and, where the same cause failed again and again, how often and for what.
class DiagnosticsEntry {
  /// When the entry was written: the first occurrence.
  final DateTime at;

  /// What was attempted and that it failed, in one line.
  final String headline;

  /// One line per fact: the request, the server's answer, the platform's
  /// error, … — already free of anything secret. The facts of the first
  /// occurrence; a repetition adds only its time and its item.
  final List<String> facts;

  /// What this entry is merged by, see [failureCause]; `null` for an entry
  /// that is never merged.
  final String? cause;

  /// How far after its latest occurrence a failure of the same [cause] is
  /// still merged into this entry, see [DiagnosticsLog.record].
  final Duration window;

  /// How often the failure happened.
  int _count = 1;

  /// When it happened last.
  DateTime _lastAt;

  /// The distinct items it happened for (a file name, an address), in order.
  final List<String> _items = [];

  DiagnosticsEntry(
    this.at,
    this.headline, [
    this.facts = const [],
    this.cause,
    String? item,
    this.window = diagnosticsMergeWindow,
  ]) : _lastAt = at {
    if (item != null) {
      _items.add(item);
    }
  }

  /// The entry as one record of a [DiagnosticsStore]: plain values that a
  /// JSON line holds.
  Map<String, Object?> toRecord() => {
        "v": 1,
        "at": at.microsecondsSinceEpoch,
        if (at.isUtc) "utc": true,
        "h": headline,
        if (facts.isNotEmpty) "f": facts,
        if (cause != null) "c": cause,
        if (window != diagnosticsMergeWindow) "w": window.inMilliseconds,
        if (_count != 1) "n": _count,
        if (_lastAt != at) "last": _lastAt.microsecondsSinceEpoch,
        if (_items.isNotEmpty) "i": _items,
      };

  /// The entry [record] holds, see [toRecord]; `null` for anything that is
  /// not one — a line torn by a crash, or one of a later version.
  static DiagnosticsEntry? fromRecord(Object? record) {
    if (record is! Map || record["v"] != 1) {
      return null;
    }
    var at = record["at"];
    var headline = record["h"];
    if (at is! int || headline is! String) {
      return null;
    }
    var utc = record["utc"] == true;
    DateTime time(int micros) =>
        DateTime.fromMicrosecondsSinceEpoch(micros, isUtc: utc);
    var facts = record["f"];
    var cause = record["c"];
    var window = record["w"];
    var count = record["n"];
    var last = record["last"];
    var items = record["i"];
    var entry = DiagnosticsEntry(
      time(at),
      headline,
      List.unmodifiable([
        if (facts is List)
          for (var fact in facts) "$fact"
      ]),
      cause is String ? cause : null,
      null,
      window is int ? Duration(milliseconds: window) : diagnosticsMergeWindow,
    );
    if (count is int && count > 1) {
      entry._count = count;
    }
    if (last is int) {
      entry._lastAt = time(last);
    }
    if (items is List) {
      entry._items.addAll([for (var item in items) "$item"]);
    }
    return entry;
  }

  /// How often the failure happened, 1 for a single one.
  int get count => _count;

  /// When the failure happened last.
  DateTime get lastAt => _lastAt;

  /// The distinct items the failure happened for.
  List<String> get items => List.unmodifiable(_items);

  /// Counts the occurrences of [other], a later failure of the same cause.
  void _absorb(DiagnosticsEntry other) {
    _count += other._count;
    if (other._lastAt.isAfter(_lastAt)) {
      _lastAt = other._lastAt;
    }
    for (var item in other._items) {
      if (!_items.contains(item)) {
        _items.add(item);
      }
    }
  }

  /// The lines a repeated entry adds under its facts: how often, when, and
  /// for what.
  List<String> get repetition {
    if (_count == 1) {
      return const [];
    }
    var named = _items.take(diagnosticsNamedItems).join(", ");
    var more = _items.length - diagnosticsNamedItems;
    return [
      "Repeated: $_count times, ${_clock(at)}–${_clock(_lastAt)}",
      if (_items.isNotEmpty)
        "Affected: $named${more > 0 ? " and $more more" : ""}",
    ];
  }

  /// The entry without its time: the headline, and each fact indented under
  /// it.
  String get message {
    var lines = [...facts, ...repetition];
    return lines.isEmpty
        ? headline
        : [headline, for (var line in lines) "  $line"].join("\n");
  }

  /// The entry as it is shown and copied: `hh:mm:ss.mmm  headline`, the facts
  /// indented under the headline.
  @override
  String toString() {
    var stamp = "${_clock(at)}.${_three(at.millisecond)}  ";
    var indent = " " * stamp.length;
    return [
      "$stamp$headline",
      for (var line in [...facts, ...repetition]) "$indent$line",
    ].join("\n");
  }

  static String _clock(DateTime time) =>
      "${_two(time.hour)}:${_two(time.minute)}:${_two(time.second)}";

  static String _two(int value) => value.toString().padLeft(2, "0");

  static String _three(int value) => value.toString().padLeft(3, "0");
}

/// Where a [DiagnosticsLog] keeps its entries beyond the memory of one
/// isolate, see the library comment.
///
/// A store holds *records* ([DiagnosticsEntry.toRecord]): every failure is
/// appended as a record of its own, and reading answers them in order, to be
/// merged exactly as [DiagnosticsLog.record] merges them, see
/// [replayDiagnostics]. Several engines may append at once; a store must
/// neither lose nor tear a record then, and a record it cannot read back is
/// skipped, never a reason to fail.
abstract class DiagnosticsStore {
  /// Every record kept, oldest first.
  Future<List<Map<String, Object?>>> read();

  /// Keeps [record] after the others.
  Future<void> append(Map<String, Object?> record);

  /// Forgets every record.
  Future<void> clear();
}

/// A [DiagnosticsStore] in memory, shared by every log handed the same
/// instance — a test's stand-in for the file of a device.
class MemoryDiagnosticsStore implements DiagnosticsStore {
  final List<Map<String, Object?>> records = [];

  @override
  Future<List<Map<String, Object?>>> read() async => [...records];

  @override
  Future<void> append(Map<String, Object?> record) async => records.add(record);

  @override
  Future<void> clear() async => records.clear();
}

/// The entries [records] make, merged as [DiagnosticsLog.record] merges
/// them, the last [capacity] of them; a record that is none is skipped.
///
/// In the order they happened, not in the order they were written: two
/// engines append to one store, and the one that failed first may write
/// last. Records of the same moment keep their order.
List<DiagnosticsEntry> replayDiagnostics(
  Iterable<Object?> records,
  int capacity,
) {
  var read = records
      .map(DiagnosticsEntry.fromRecord)
      .whereType<DiagnosticsEntry>()
      .toList();
  var order = [for (var index = 0; index < read.length; index++) index]
    ..sort((a, b) {
      var byTime = read[a].at.compareTo(read[b].at);
      return byTime != 0 ? byTime : a.compareTo(b);
    });
  var entries = Queue<DiagnosticsEntry>();
  for (var index in order) {
    _mergeEntry(entries, read[index], capacity);
  }
  return entries.toList();
}

/// Adds [incoming] to [entries], or merges it into the newest entry of its
/// cause whose latest occurrence lies at most [DiagnosticsEntry.window] of
/// [incoming] before it; the oldest entries are dropped beyond [capacity].
void _mergeEntry(
  Queue<DiagnosticsEntry> entries,
  DiagnosticsEntry incoming,
  int capacity,
) {
  var cause = incoming.cause;
  if (cause != null) {
    for (var entry in entries.toList().reversed) {
      if (entry.cause == cause &&
          incoming.at.difference(entry.lastAt) <= incoming.window) {
        entry._absorb(incoming);
        return;
      }
    }
  }
  entries.addLast(incoming);
  while (entries.length > capacity) {
    entries.removeFirst();
  }
}

/// The problems the app ran into, the last [capacity] of them.
///
/// One instance per engine: the app's is handed to every [VAlbumClient] the
/// app builds (the way the offline state and the cache are), so that every
/// failure lands in the same log whichever server it came from; a background
/// run builds one of its own over the same [store], see the library comment.
/// A [ChangeNotifier], so that the diagnostics section of the settings screen
/// shows what arrives while it is open.
class DiagnosticsLog extends ChangeNotifier {
  /// How many entries are kept; the oldest are dropped beyond it.
  final int capacity;

  /// The clock, so that a test can pin the timestamps.
  final DateTime Function() now;

  /// Where the entries are kept beyond this isolate, `null` for a log in
  /// memory only.
  ///
  /// A store that fails is dropped: the log goes on in memory and says so
  /// in an entry of its own.
  DiagnosticsStore? _store;

  final Queue<DiagnosticsEntry> _entries = Queue<DiagnosticsEntry>();

  /// The last of the writes to [_store], which run one after the other;
  /// `null` while none is pending.
  ///
  /// Not a completed future while there is nothing to wait for: a future
  /// completes in the zone it was made in, and a log made in one zone and
  /// flushed in another — a test's fake clock and its real one — would wait
  /// for a zone that never runs.
  Future<void>? _writes;

  /// For every [reload] that has not read yet, the entries recorded since it
  /// was asked for: their writes come after its read, so its answer cannot
  /// hold them.
  final List<List<DiagnosticsEntry>> _arrivals = [];

  DiagnosticsLog({
    this.capacity = diagnosticsCapacity,
    DateTime Function()? now,
    DiagnosticsStore? store,
  })  : now = now ?? DateTime.now,
        _store = store;

  /// Where the entries are kept beyond this isolate, see [_store].
  DiagnosticsStore? get store => _store;

  /// What is in the log, oldest first.
  List<DiagnosticsEntry> get entries => List.unmodifiable(_entries);

  /// Whether no problem has been recorded.
  bool get isEmpty => _entries.isEmpty;

  /// Records a problem: [headline] says what failed, [facts] why, one line
  /// each. The oldest entry is dropped beyond [capacity].
  ///
  /// Only for a failure — nothing that went well is ever written here, see
  /// the library comment.
  ///
  /// A failure with a [cause] (see [failureCause]) that an entry of the last
  /// [diagnosticsMergeWindow] already has is **merged into it** (issue #184):
  /// that entry keeps its first headline and facts and counts one more, for
  /// [item] — the file or address that differs. An album of sixty photos
  /// opened while the server is away is one entry, "Repeated: 60 times", and
  /// not sixty that push everything else out. The window is measured from
  /// the entry's latest occurrence, so a storm that goes on stays one entry,
  /// and an entry of another cause in between (a video that failed while the
  /// thumbnails did) keeps its own place.
  void add(
    String headline, [
    List<String> facts = const [],
    String? cause,
    String? item,
  ]) =>
      record(headline, facts: facts, cause: cause, item: item);

  /// [add], with a merge [window] of its own.
  ///
  /// A failure that comes back only every few minutes — a camera-roll run
  /// retried with its back-off, a background run every quarter of an hour —
  /// is still one problem: its entry merges every repetition that comes at
  /// most [window] after the last one, see [failureCause].
  void record(
    String headline, {
    List<String> facts = const [],
    String? cause,
    String? item,
    Duration window = diagnosticsMergeWindow,
  }) {
    var entry = DiagnosticsEntry(
      now(),
      headline,
      List.unmodifiable(facts),
      cause,
      item,
      window,
    );
    // The record is taken before the merge: the store keeps the occurrence,
    // and merges it again whenever it is read.
    var record = entry.toRecord();
    for (var arriving in _arrivals) {
      arriving.add(DiagnosticsEntry.fromRecord(record)!);
    }
    _mergeEntry(_entries, entry, capacity);
    notifyListeners();
    var store = _store;
    if (store != null) {
      _write(() => store.append(record));
    }
  }

  /// Queues [write] of the store behind the ones before it.
  Future<void> _write(Future<void> Function() write) {
    var before = _writes;
    Future<void> run() async {
      if (before != null) {
        await before;
      }
      if (_store == null) {
        return;
      }
      try {
        await write();
      } catch (error) {
        _storeFailed(error);
      }
    }

    late Future<void> queued;
    queued = run().whenComplete(() {
      if (identical(_writes, queued)) {
        _writes = null;
      }
    });
    return _writes = queued;
  }

  /// Drops a store that failed, and says so in memory.
  void _storeFailed(Object error) {
    _store = null;
    _mergeEntry(
      _entries,
      DiagnosticsEntry(
        now(),
        "Could not keep the diagnostics log on the device: "
        "it is kept in memory from now on",
        [
          platformErrorFact(error),
          "Engine: the entries of other engines (a background run) are not "
              "shown here until the app is started again",
        ],
      ),
      capacity,
    );
    notifyListeners();
  }

  /// Reads what every engine wrote into [store] — a background run included
  /// — and shows it; a log without a store keeps what it has.
  ///
  /// Asked when the app starts, when a run elsewhere ends and when the
  /// diagnostics section is opened. What this log recorded meanwhile is kept.
  Future<void> reload() {
    if (_store == null) {
      return flush();
    }
    var arriving = <DiagnosticsEntry>[];
    _arrivals.add(arriving);
    return _write(() async {
      var store = _store!;
      try {
        var records = await store.read();
        var entries = replayDiagnostics(records, capacity);
        _entries
          ..clear()
          ..addAll(entries);
        for (var entry in arriving) {
          _mergeEntry(_entries, entry, capacity);
        }
        notifyListeners();
      } finally {
        _arrivals.remove(arriving);
      }
    }).whenComplete(() => _arrivals.remove(arriving));
  }

  /// Completes once every entry recorded so far is in [store] — the last
  /// thing a background run does before its isolate is torn down.
  Future<void> flush() => _writes ?? Future<void>.value();

  /// Records a request the server refused — a status of 400 or more.
  ///
  /// A `2xx` and a `3xx` are the server doing its job and are never written
  /// down; a refusal is written with everything the answer said about itself,
  /// the sentence of its `ErrorInfo` included, because "403" alone does not
  /// say which right was missing.
  void refused(
    String method,
    String url, {
    required int status,
    String? reason,
    String? contentType,
    int? contentLength,
    String? message,
    bool bearer = false,
  }) =>
      add(
        "Could not ${requestPurpose(method, url)}: the server answered "
        "${statusText(status, reason)}",
        [
          requestFact(method, url, bearer: bearer),
          answerFact(
            status,
            reason: reason,
            contentType: contentType,
            contentLength: contentLength,
          ),
          if (message != null && message.isNotEmpty) "Server said: $message",
        ],
        failureCause(
          attempt: "request ${requestKind(method, url)}",
          answer: "$status ${message ?? ""}",
        ),
        requestItem(url),
      );

  /// Records a request that never got an answer, with the *complete* text of
  /// the failure.
  ///
  /// `error.toString()`, not a headline: a `SocketException` carries the OS
  /// message and its errno, and that is the one thing issue #58 is about.
  void failed(
    String method,
    String url, {
    required Object error,
    bool bearer = false,
  }) =>
      add(
        "Could not ${requestPurpose(method, url)}: no answer from the server",
        [
          requestFact(method, url, bearer: bearer),
          platformErrorFact(error),
        ],
        failureCause(
          attempt: "request ${requestKind(method, url)}",
          platformError: errorCause(error, url),
        ),
        requestItem(url),
      );

  /// Forgets everything recorded so far, in [store] as well.
  void clear() {
    var store = _store;
    if (store != null) {
      _write(store.clear);
    }
    if (_entries.isEmpty) {
      return;
    }
    _entries.clear();
    notifyListeners();
  }

  /// The whole log with its header, as it goes to the clipboard.
  ///
  /// [platform] is what the machine says about itself (see
  /// `platformDescription` of `platform.dart`: the operating system, or the
  /// browser's user agent), [serverUrl] the server the device is configured
  /// for — the header exists so that a pasted log says *where* it was taken,
  /// not only what went wrong.
  String copyText({
    String? serverUrl,
    String platform = "unknown",
    String? version,
    DateTime? at,
  }) {
    version ??= appVersion;
    var stamp = at ?? now();
    var buffer = StringBuffer()
      ..writeln("VAlbum diagnostics")
      ..writeln("App: $version")
      ..writeln("Platform: $platform")
      ..writeln(
        "Server: ${serverUrl == null || serverUrl.isEmpty ? "(none)" : maskUrl(serverUrl)}",
      )
      ..writeln("Copied: ${stamp.toIso8601String()}")
      ..writeln("Problems: ${_entries.length} (the last $capacity are kept)")
      ..writeln();
    if (_entries.isEmpty) {
      buffer.writeln(noProblemsRecorded);
    }
    for (var entry in _entries) {
      buffer.writeln(entry.toString());
    }
    return buffer.toString();
  }
}

/// The line naming a request: `Request: GET <url> (with bearer)`.
String requestFact(String method, String url, {bool bearer = false}) =>
    "Request: $method ${maskUrl(url)}${bearer ? " (with bearer)" : ""}";

/// The line saying what the server answered: status, type and length.
String answerFact(
  int status, {
  String? reason,
  String? contentType,
  int? contentLength,
  int? totalLength,
}) {
  var parts = [
    statusText(status, reason),
    "Content-Type ${contentType == null || contentType.isEmpty ? "(none)" : contentType}",
    "Content-Length ${contentLength ?? "(none)"}",
    if (totalLength != null) "of ${byteCount(totalLength)}",
  ];
  return "Server answered: ${parts.join(", ")}";
}

/// A status with its reason phrase where there is one: `403 Forbidden`.
String statusText(int status, [String? reason]) =>
    reason == null || reason.isEmpty ? "$status" : "$status $reason";

/// A size as a bug report wants it: exact, and readable.
String byteCount(int bytes) {
  if (bytes < 1024) {
    return "$bytes bytes";
  }
  const units = ["kB", "MB", "GB"];
  var value = bytes / 1024;
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return "$bytes bytes (${value.toStringAsFixed(1)} ${units[unit]})";
}

/// The line saying what the platform reported: code and message where it has
/// them, the whole text otherwise.
String platformErrorFact(Object error) {
  if (error is PlatformException) {
    var parts = [
      "code ${error.code}",
      if (error.message != null && error.message!.isNotEmpty)
        "message ${error.message}",
      if (error.details != null) "details ${error.details}",
    ];
    return maskMediaSignatures("Platform error: ${parts.join(", ")}");
  }
  return maskMediaSignatures("Platform error: $error");
}

/// [text] with the value of every `media=` parameter masked (issue #185).
///
/// A signed media address opens one video for ten minutes to whoever holds
/// it; a platform's error may quote the address it failed on, and a log is
/// pasted into a bug report.
String maskMediaSignatures(String text) => text.replaceAllMapped(
      RegExp(r"([?&]media=)([^&#\s]+)"),
      (match) => "${match[1]}${maskToken(match[2]!)}",
    );

/// The kind of a request, without the file it names: the method and the
/// `?type=`/`?action=` of the protocol, or what a bare address is — the
/// attempt of a [failureCause].
String requestKind(String method, String url) {
  var query = Uri.tryParse(url)?.queryParameters ?? const {};
  var action = query["action"];
  if (action != null) {
    return "$method action=$action";
  }
  var type = query["type"];
  if (type != null) {
    return "$method type=$type";
  }
  return "$method ${url.split("?").first.endsWith("/") ? "folder" : "file"}";
}

/// What a merged entry lists a request as: the name of the file or folder it
/// named, else its masked address.
String requestItem(String url) {
  var segments = maskUrl(url)
      .split("?")
      .first
      .split("/")
      .where((segment) => segment.isNotEmpty)
      .toList();
  return segments.length > 2 ? _decoded(segments.last) : maskUrl(url);
}

/// The platform's error as a [failureCause] compares it: the text, without
/// the address it happened for, which is the item and not the cause.
String errorCause(Object error, [String? url]) {
  if (error is http.ClientException) {
    return "ClientException: ${error.message}";
  }
  var text = error.toString();
  if (url != null && url.isNotEmpty) {
    text = text.replaceAll(url, "<url>");
  }
  return text;
}

/// What a request was for, in words, for the headline of its failure.
///
/// Read off the query the protocol puts on every address (`?type=…`,
/// `?action=…`) and the method: a headline "Could not load a thumbnail" says
/// at a glance what "GET …/a.jpg?type=tn -> 404" makes one work out.
String requestPurpose(String method, String url) {
  Uri uri;
  try {
    uri = Uri.parse(url);
  } catch (_) {
    return "send $method $url";
  }
  var query = uri.queryParameters;
  var action = query["action"];
  if (action != null) {
    return switch (action) {
      "pair" => "sign this device in",
      "check" => "ask which photos the server already has",
      "move" => "move",
      "delete" => "delete",
      "zip" => "download the selection as an archive",
      "share" => "create a share link",
      "tag-faces" || "adjust-faces" => "save a decision about a face",
      _ => "carry out '$action'",
    };
  }
  var type = query["type"];
  // The names come from the *masked* URL: the last segment of a session
  // address is its token.
  var segments = maskUrl(url)
      .split("?")
      .first
      .split("/")
      .where((segment) => segment.isNotEmpty)
      .toList();
  var name = segments.length > 2 ? _decoded(segments.last) : null;
  var of = name == null ? "" : " of '$name'";
  switch (type) {
    case "json":
      return "load the album listing";
    case "tn":
      return "load the thumbnail$of";
    case "video":
      return "load the playable version$of";
    case "teaser":
      return "load the teaser$of";
    case "face":
      return "load a face$of";
    case "auth":
      return "ask the server who this device is";
    case null:
      break;
    default:
      return "load '$type'";
  }
  var folder = url.endsWith("/") && name != null ? " '$name'" : "";
  return switch (method) {
    "PUT" => "upload to or save the folder$folder",
    "POST" => "send a request to the folder$folder",
    _ => url.endsWith("/") || name == null
        ? "load the folder$folder"
        : "load the original$of",
  };
}

/// [segment] with its percent escapes resolved, as it stands where it cannot
/// be.
String _decoded(String segment) {
  try {
    return Uri.decodeComponent(segment);
  } catch (_) {
    return segment;
  }
}

/// The album at [albumUrl] as a failure entry names it: its path below the
/// data root [dataUrl], quoted, or "the top level".
String albumLabel(String dataUrl, String albumUrl) {
  var path = albumUrl.startsWith(dataUrl)
      ? albumUrl.substring(dataUrl.length)
      : albumUrl;
  while (path.startsWith("/")) {
    path = path.substring(1);
  }
  while (path.endsWith("/")) {
    path = path.substring(0, path.length - 1);
  }
  if (path.isEmpty) {
    return "the top level";
  }
  try {
    path = Uri.decodeFull(path);
  } catch (_) {
    // Kept as it is.
  }
  return "'$path'";
}

/// The line describing a photograph or video of an album: name, extension,
/// kind, dimensions and, where known, the size of the file.
String originalFact(
  String name, {
  String? kind,
  int width = 0,
  int height = 0,
  int? size,
}) {
  var dot = name.lastIndexOf(".");
  var parts = [
    "extension ${dot < 0 ? "(none)" : name.substring(dot)}",
    if (kind != null) "kind $kind",
    if (width > 0 && height > 0) "$width×$height",
    size == null ? "size unknown" : byteCount(size),
  ];
  return "Original: $name (${parts.join(", ")})";
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

/// The URL as the log writes it: every token in the path masked, and the
/// signature of a media address (issue #185).
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
    return maskMediaSignatures(url);
  }
  return maskMediaSignatures(uri
      .replace(path: segments.join("/"))
      .toString()
      // `Uri` percent-encodes the mask; the log shows the dots.
      .replaceAll("%E2%80%A2", "•"));
}
