/// Probe of #303: the app's log and a background run's log over one file.
/// What the app wrote itself is not shown twice after a reload, a failure
/// repeated in both engines is one entry, and clearing in the app clears
/// what the background wrote.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/diagnostics_file.dart';

void main() {
  late Directory dir;
  setUp(() async => dir = await Directory.systemTemp.createTemp("diag-probe"));
  tearDown(() async => dir.delete(recursive: true));

  test("two engines, one file", () async {
    var t = DateTime.utc(2026, 10, 18, 12);
    var app = DiagnosticsLog(
        now: () => t, store: FileDiagnosticsStore.inDirectory(dir));
    var background = DiagnosticsLog(
        now: () => t.add(const Duration(seconds: 5)),
        store: FileDiagnosticsStore.inDirectory(dir));

    app.add("Own failure", const ["fact"], "own-cause");
    await app.flush();
    await app.reload();
    await app.reload();
    expect(app.entries.map((e) => e.headline), ["Own failure"],
        reason: "a reload never doubles what the app wrote itself");

    background.add("Sync failed", const ["NPE"], "sync-cause");
    background.add("Sync failed", const ["NPE"], "sync-cause");
    await background.flush();
    await app.reload();
    expect(app.entries.map((e) => e.headline), ["Own failure", "Sync failed"]);
    expect(app.entries.last.count, 2, reason: "merged across the file");

    app.clear();
    await app.flush();
    var fresh = DiagnosticsLog(store: FileDiagnosticsStore.inDirectory(dir));
    await fresh.reload();
    expect(fresh.entries, isEmpty, reason: "cleared for every engine");
  });
}
