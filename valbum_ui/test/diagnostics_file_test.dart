/// The diagnostics log every engine of the app writes into (issue #300 left a
/// background run's failures in an isolate nobody looks at): the file store,
/// two writers at once, a torn file, the compaction, and the merge semantics
/// that survive being read back.
library;

import 'dart:io';
import 'dart:isolate';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/diagnostics_file.dart';

/// A directory of its own for one test, removed afterwards.
Directory scratch() {
  var directory = Directory.systemTemp.createTempSync("valbum-diagnostics-");
  addTearDown(() => directory.deleteSync(recursive: true));
  return directory;
}

/// Appends [count] entries headlined `<prefix> <n>` through a store of its
/// own on [path] — the work of one engine.
Future<void> writeEntries(
  String path,
  String prefix,
  int count, {
  int compactBytes = diagnosticsCompactBytes,
}) async {
  var log = DiagnosticsLog(
    capacity: 10000,
    store: FileDiagnosticsStore.inDirectory(
      Directory(path),
      capacity: 10000,
      compactBytes: compactBytes,
    ),
  );
  for (var n = 0; n < count; n++) {
    log.add("$prefix $n", ["Fact of $prefix $n"]);
    if (n % 7 == 0) {
      // Let the other engine in between.
      await log.flush();
    }
  }
  await log.flush();
}

void main() {
  group('a log over a file', () {
    test('shows what another engine wrote, once it reloads', () async {
      var directory = scratch();
      var app = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );
      var background = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );

      app.add("Could not load the album listing", ["Request: GET a"]);
      background.add("Camera-roll sync did not finish: no access", [
        "Platform error: code error, message getClass()",
      ]);
      await background.flush();
      await app.reload();

      expect(app.entries.map((entry) => entry.headline), [
        "Could not load the album listing",
        "Camera-roll sync did not finish: no access",
      ]);
      expect(app.entries[1].facts,
          ["Platform error: code error, message getClass()"]);
    });

    test('an app started later reads what was written before', () async {
      var directory = scratch();
      var background = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
        now: () => DateTime.utc(2026, 10, 18, 7, 1, 2, 345),
      );
      background.add("The background task 'trigger' failed: boom", ["x"]);
      await background.flush();

      var app = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );
      expect(app.entries, isEmpty);
      await app.reload();

      expect(app.entries.single.headline,
          "The background task 'trigger' failed: boom");
      expect(app.entries.single.at, DateTime.utc(2026, 10, 18, 7, 1, 2, 345));
      expect(app.entries.single.toString(), startsWith("07:01:02.345  "));
    });

    test('keeps what it recorded while it reads', () async {
      var directory = scratch();
      var store = FileDiagnosticsStore.inDirectory(directory);
      var other = DiagnosticsLog(store: store);
      other.add("written elsewhere");
      await other.flush();
      var log =
          DiagnosticsLog(store: FileDiagnosticsStore.inDirectory(directory));

      var reading = log.reload();
      log.add("recorded meanwhile");
      await reading;
      await log.flush();

      expect(log.entries.map((entry) => entry.headline),
          ["written elsewhere", "recorded meanwhile"]);
      await log.reload();
      expect(log.entries.map((entry) => entry.headline),
          ["written elsewhere", "recorded meanwhile"]);
    });

    test('merges a storm of one cause across two engines into one entry',
        () async {
      var directory = scratch();
      var time = DateTime.utc(2026, 10, 18, 9);
      DiagnosticsLog engine() => DiagnosticsLog(
            store: FileDiagnosticsStore.inDirectory(directory),
            now: () => time,
          );
      var app = engine();
      var background = engine();

      app.record("Camera-roll sync did not finish: offline",
          cause: "sync | offline", window: const Duration(minutes: 35));
      time = time.add(const Duration(minutes: 30));
      background.record("Camera-roll sync did not finish: offline",
          cause: "sync | offline", window: const Duration(minutes: 35));
      time = time.add(const Duration(minutes: 30));
      app.record("Camera-roll sync did not finish: offline",
          cause: "sync | offline", window: const Duration(minutes: 35));
      await background.flush();
      await app.flush();
      await app.reload();

      expect(app.entries, hasLength(1));
      expect(app.entries.single.count, 3);
      expect(app.entries.single.repetition.first,
          "Repeated: 3 times, 09:00:00–10:00:00");
    });

    test('a window of its own merges failures minutes apart', () {
      var time = DateTime.utc(2026, 10, 18, 9);
      var log = DiagnosticsLog(now: () => time);

      log.record("a", cause: "c", window: const Duration(minutes: 35));
      time = time.add(const Duration(minutes: 30));
      log.record("a", cause: "c", window: const Duration(minutes: 35));
      time = time.add(const Duration(minutes: 36));
      log.record("a", cause: "c", window: const Duration(minutes: 35));

      expect(log.entries.map((entry) => entry.count), [2, 1]);
    });

    test('clear forgets the file as well', () async {
      var directory = scratch();
      var log = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );
      log.add("one");
      log.clear();
      log.add("two");
      await log.flush();

      var other = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );
      await other.reload();
      expect(other.entries.map((entry) => entry.headline), ["two"]);
    });

    test('keeps the last entries of its capacity through a compaction',
        () async {
      var directory = scratch();
      var store = FileDiagnosticsStore.inDirectory(
        directory,
        capacity: 20,
        compactBytes: 1024,
      );
      var log = DiagnosticsLog(capacity: 20, store: store);
      for (var n = 0; n < 100; n++) {
        log.add("entry $n", ["a fact that makes the line longer, $n"]);
      }
      await log.flush();

      expect(
          File("${directory.path}/$diagnosticsBaseName").existsSync(), isTrue);
      expect(File("${directory.path}/$diagnosticsJournalName").lengthSync(),
          lessThan(2048));
      var read = DiagnosticsLog(capacity: 20, store: store);
      await read.reload();
      expect(read.entries.map((entry) => entry.headline),
          [for (var n = 80; n < 100; n++) "entry $n"]);
      expect(
          File("${directory.path}/$diagnosticsLockName").existsSync(), isFalse);
    });
  });

  group('two engines writing at once', () {
    test('lose and tear nothing, compactions included', () async {
      var directory = scratch();
      var path = directory.path;

      // One writer in an isolate of its own, as the background engine is, the
      // other here; a small journal makes both compact while the other
      // appends.
      await Future.wait([
        Isolate.run(
            () => writeEntries(path, "background", 150, compactBytes: 2048)),
        writeEntries(path, "app", 150, compactBytes: 2048),
      ]);

      var log = DiagnosticsLog(
        capacity: 10000,
        store: FileDiagnosticsStore.inDirectory(directory, capacity: 10000),
      );
      await log.reload();
      var headlines = log.entries.map((entry) => entry.headline).toSet();
      expect(headlines, hasLength(300));
      for (var n = 0; n < 150; n++) {
        expect(headlines, contains("background $n"));
        expect(headlines, contains("app $n"));
      }
      for (var entry in log.entries) {
        expect(entry.facts, ["Fact of ${entry.headline}"]);
      }
      // The order of each engine is kept.
      var app = [
        for (var entry in log.entries)
          if (entry.headline.startsWith("app ")) entry.headline
      ];
      expect(app, [for (var n = 0; n < 150; n++) "app $n"]);
    });

    test('a lock left by a dead engine is broken', () async {
      var directory = scratch();
      var lock = File("${directory.path}/$diagnosticsLockName")
        ..writeAsStringSync("");
      lock.setLastModifiedSync(
          DateTime.now().subtract(const Duration(minutes: 1)));
      var log = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );

      log.add("after a crash");
      await log.flush();
      await log.reload();

      expect(log.entries.map((entry) => entry.headline), ["after a crash"]);
      expect(lock.existsSync(), isFalse);
    });
  });

  group('a damaged file', () {
    test('is read without failing, skipping what is no record', () async {
      var directory = scratch();
      File("${directory.path}/$diagnosticsBaseName").writeAsStringSync(
        'garbage\n{"v":1,"at":"not a number","h":"x"}\n[1,2]\n'
        '{"v":2,"at":1,"h":"from a later version"}\n',
      );
      // A line a crash cut short, with no line break after it.
      File("${directory.path}/$diagnosticsJournalName")
          .writeAsStringSync('\n{"v":1,"at":12345,"h":"torn');
      var log = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );

      log.add("written after the tear");
      await log.flush();
      await log.reload();

      expect(log.entries.map((entry) => entry.headline),
          ["written after the tear"]);
      expect(log.entries.single.facts, isEmpty);
    });

    test('is parsed line by line', () {
      expect(
        parseDiagnosticsLines('\n{"v":1,"at":1,"h":"a"}\n{"v":1,"at":2,"h"\n'
            '\n{"v":1,"at":3,"h":"c"}'),
        [
          {"v": 1, "at": 1, "h": "a"},
          {"v": 1, "at": 3, "h": "c"},
        ],
      );
    });

    test('a store that fails leaves the log in memory, saying so', () async {
      var log = DiagnosticsLog(store: _FailingStore());

      log.add("first");
      await log.flush();
      log.add("second");
      await log.reload();

      expect(log.store, isNull);
      expect(log.entries.map((entry) => entry.headline), [
        "first",
        "Could not keep the diagnostics log on the device: it is kept in "
            "memory from now on",
        "second",
      ]);
      expect(log.entries[1].facts.first,
          startsWith("Platform error: FileSystemException: disk full"));
    });
  });

  group('a record', () {
    test('round-trips an entry with its repetitions', () {
      var time = DateTime(2026, 10, 18, 9, 30);
      var log = DiagnosticsLog(now: () => time);
      log.record("headline",
          facts: ["one", "two"],
          cause: "cause",
          item: "a.jpg",
          window: const Duration(minutes: 35));
      time = time.add(const Duration(minutes: 1));
      log.record("headline", cause: "cause", item: "b.jpg");

      var entry = log.entries.single;
      var back = DiagnosticsEntry.fromRecord(entry.toRecord())!;

      expect(back.toString(), entry.toString());
      expect(back.count, 2);
      expect(back.items, ["a.jpg", "b.jpg"]);
      expect(back.window, const Duration(minutes: 35));
      expect(back.cause, "cause");
      expect(back.at.isUtc, isFalse);
    });
  });
}

class _FailingStore implements DiagnosticsStore {
  @override
  Future<void> append(Map<String, Object?> record) async =>
      throw const FileSystemException("disk full");

  @override
  Future<void> clear() async {}

  @override
  Future<List<Map<String, Object?>>> read() async => [];
}
