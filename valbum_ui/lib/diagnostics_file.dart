/// The [DiagnosticsStore] of a device: JSON lines in the application support
/// directory, shared by every engine of the app.
///
/// Why a file: a camera-roll run on Android is carried out in the engine
/// WorkManager starts (issue #300), an isolate that shares no memory with the
/// app on the screen — and that runs while the app is closed, so there may be
/// nobody to send an entry to. A file is what both can reach, and what the app
/// finds the next time it starts.
///
/// Three files, and a lock:
///
///  * [diagnosticsJournalName] — every recorded failure appended as one line,
///    [DiagnosticsEntry.toRecord] as JSON;
///  * [diagnosticsBaseName] — the entries the journal was compacted into: once
///    the journal grows beyond [diagnosticsCompactBytes], its records and the
///    base are merged and cut to the capacity of the log, written to a second
///    file that is renamed over the base, and the journal is removed;
///  * [diagnosticsLockName] — created exclusively (`O_EXCL`) around every
///    append and every compaction, so that two isolates — or two processes —
///    never write at once and no append can fall between the read and the
///    removal of a compaction. A lock older than [diagnosticsStaleLock] was
///    left by an engine that died while holding it, and is broken.
///
/// Every line starts with a line break: a line a crash cut short is followed
/// by a line of its own, and a line that does not read back as a record is
/// skipped. Reading never fails on the contents of a file.
///
/// Imported only through `platform_io.dart`; the web build keeps its log in
/// memory.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:path_provider/path_provider.dart';

import 'diagnostics.dart';

/// The file every engine appends to.
const String diagnosticsJournalName = "valbum-diagnostics.jsonl";

/// The file the journal is compacted into.
const String diagnosticsBaseName = "valbum-diagnostics.base.jsonl";

/// The file that is renamed over [diagnosticsBaseName].
const String diagnosticsNextName = "valbum-diagnostics.base.next";

/// The lock of the files.
const String diagnosticsLockName = "valbum-diagnostics.lock";

/// The size of the journal beyond which it is compacted into the base.
const int diagnosticsCompactBytes = 128 * 1024;

/// How old a lock is when its holder is taken to be gone.
const Duration diagnosticsStaleLock = Duration(seconds: 10);

/// How long a writer waits for the lock before it writes anyway.
const Duration diagnosticsLockPatience = Duration(seconds: 3);

/// The [DiagnosticsStore] in a directory of the device, see the library
/// comment.
class FileDiagnosticsStore implements DiagnosticsStore {
  final Future<Directory> Function() _directory;

  /// How many entries the base keeps, see [DiagnosticsLog.capacity].
  final int capacity;

  /// The journal size that starts a compaction.
  final int compactBytes;

  final Random _random = Random();

  FileDiagnosticsStore._(
    this._directory, {
    this.capacity = diagnosticsCapacity,
    this.compactBytes = diagnosticsCompactBytes,
  });

  /// The store in [directory], used by the tests.
  factory FileDiagnosticsStore.inDirectory(
    Directory directory, {
    int capacity = diagnosticsCapacity,
    int compactBytes = diagnosticsCompactBytes,
  }) =>
      FileDiagnosticsStore._(
        () async => directory,
        capacity: capacity,
        compactBytes: compactBytes,
      );

  /// The store of the app, under the application support directory — the
  /// directory of the hash cache, which is the same for every engine.
  factory FileDiagnosticsStore.underSupportDirectory() =>
      FileDiagnosticsStore._(getApplicationSupportDirectory);

  Future<File> _file(String name) async =>
      File("${(await _directory()).path}${Platform.pathSeparator}$name");

  /// Read under the lock as well: a compaction between the base and the
  /// journal would otherwise show its records twice, or not at all.
  @override
  Future<List<Map<String, Object?>>> read() async {
    var result = <Map<String, Object?>>[];
    await _locked(() async => result = await _read());
    return result;
  }

  Future<List<Map<String, Object?>>> _read() async {
    var result = <Map<String, Object?>>[];
    for (var name in [diagnosticsBaseName, diagnosticsJournalName]) {
      var file = await _file(name);
      String text;
      try {
        text = await file.readAsString();
      } on PathNotFoundException {
        continue;
      } on FileSystemException {
        if (!await file.exists()) {
          // Removed meanwhile by an engine that did not get the lock in
          // time: the next reload shows what it holds.
          continue;
        }
        rethrow;
      } on FormatException {
        // Not UTF-8: a file damaged beyond its lines. Read what can be read.
        text = utf8.decode(await file.readAsBytes(), allowMalformed: true);
      }
      result.addAll(parseDiagnosticsLines(text));
    }
    return result;
  }

  @override
  Future<void> append(Map<String, Object?> record) async {
    var journal = await _file(diagnosticsJournalName);
    await _locked(() async {
      await journal.parent.create(recursive: true);
      await journal.writeAsString(
        "\n${jsonEncode(record)}",
        mode: FileMode.append,
        flush: true,
      );
      if (await journal.length() > compactBytes) {
        await _compact(journal);
      }
    });
  }

  @override
  Future<void> clear() async {
    await _locked(() async {
      for (var name in [
        diagnosticsJournalName,
        diagnosticsBaseName,
        diagnosticsNextName,
      ]) {
        var file = await _file(name);
        if (await file.exists()) {
          await file.delete();
        }
      }
    });
  }

  /// Merges the journal into the base, see the library comment; the lock is
  /// held.
  Future<void> _compact(File journal) async {
    var base = await _file(diagnosticsBaseName);
    var records = await _read();
    var entries = replayDiagnostics(records, capacity);
    var next = await _file(diagnosticsNextName);
    await next.writeAsString(
      [for (var entry in entries) "\n${jsonEncode(entry.toRecord())}"].join(),
      flush: true,
    );
    await next.rename(base.path);
    await journal.delete();
  }

  /// Runs [action] holding the lock file, see the library comment.
  ///
  /// A lock that cannot be had within [diagnosticsLockPatience] — a file
  /// system that refuses to create it, a holder that does not let go — does
  /// not hold the entry back: it is written anyway, and the worst that can
  /// come of it is the rare torn line a reader skips.
  Future<void> _locked(Future<void> Function() action) async {
    var lock = await _file(diagnosticsLockName);
    var held = false;
    var deadline = DateTime.now().add(diagnosticsLockPatience);
    while (!held) {
      try {
        await lock.parent.create(recursive: true);
        await lock.create(exclusive: true);
        held = true;
      } on FileSystemException {
        if (await _stale(lock)) {
          try {
            await lock.delete();
          } on FileSystemException {
            // Another engine broke it first.
          }
          continue;
        }
        if (!DateTime.now().isBefore(deadline)) {
          break;
        }
        await Future<void>.delayed(
          Duration(milliseconds: 5 + _random.nextInt(20)),
        );
      }
    }
    try {
      await action();
    } finally {
      if (held) {
        try {
          await lock.delete();
        } on FileSystemException {
          // Broken as stale by another engine meanwhile; it is gone either
          // way.
        }
      }
    }
  }

  static Future<bool> _stale(File lock) async {
    try {
      var modified = await lock.lastModified();
      return DateTime.now().difference(modified) > diagnosticsStaleLock;
    } on FileSystemException {
      // Gone between the attempt and the look: the next attempt may have it.
      return false;
    }
  }
}

/// The records of the JSON lines [text]; a line that is no record — torn by
/// a crash, or empty — is skipped.
List<Map<String, Object?>> parseDiagnosticsLines(String text) {
  var result = <Map<String, Object?>>[];
  for (var line in const LineSplitter().convert(text)) {
    if (line.trim().isEmpty) {
      continue;
    }
    Object? decoded;
    try {
      decoded = jsonDecode(line);
    } on FormatException {
      continue;
    }
    if (decoded is Map<String, Object?>) {
      result.add(decoded);
    }
  }
  return result;
}
