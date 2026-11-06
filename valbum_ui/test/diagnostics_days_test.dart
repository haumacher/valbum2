/// The entries of the diagnostics log over several days (issue #318): each
/// stays stamped with its time of day, and a line naming the day stands
/// before the first entry and wherever the day changes — in the copied text
/// (`2026-11-04`, the same in every language) and on the screen (in the
/// device's language).
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/l10n.dart';

/// A log with an entry at each of [times], in this order.
DiagnosticsLog logAt(List<DateTime> times) {
  var time = times.first;
  var log = DiagnosticsLog(now: () => time);
  for (var (index, at) in times.indexed) {
    time = at;
    log.add("failure $index", ["Request: GET http://server/$index"]);
  }
  return log;
}

/// Four entries on three days, the first a minute before midnight. Built
/// from their parts: the same days in every time zone.
final List<DateTime> threeDays = [
  DateTime(2026, 11, 4, 23, 59, 1, 2),
  DateTime(2026, 11, 5, 0, 0, 3, 40),
  DateTime(2026, 11, 5, 12, 30, 0, 500),
  DateTime(2026, 11, 6, 8, 15, 9, 7),
];

/// The lines that name a day.
List<String> dayLines(String text) => [
      for (var line in text.split("\n"))
        if (line.startsWith("--- ")) line
    ];

/// The time-of-day lines of the four entries, as before issue #318.
const List<String> timeLines = [
  "23:59:01.002  failure 0",
  "              Request: GET http://server/0",
  "00:00:03.040  failure 1",
  "12:30:00.500  failure 2",
  "08:15:09.007  failure 3",
];

/// Shows the log panel of [log] and answers what it shows and what it copied.
Future<({String shown, String copied})> showAndCopy(
    WidgetTester tester, DiagnosticsLog log) async {
  String? copied;
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
    SystemChannels.platform,
    (call) async {
      if (call.method == "Clipboard.setData") {
        copied = (call.arguments as Map)["text"] as String;
      }
      return null;
    },
  );
  addTearDown(
    () => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null),
  );
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: testLocalizationsDelegates,
    supportedLocales: testSupportedLocales,
    home: Scaffold(
      body: SingleChildScrollView(child: DiagnosticsPanel(log: log)),
    ),
  ));
  await tester.pumpAndSettle();
  var shown = tester
      .widget<SelectableText>(find.descendant(
          of: find.byType(DiagnosticsPanel),
          matching: find.byType(SelectableText)))
      .data!;
  await tester.tap(find.byKey(diagnosticsCopyKey));
  await tester.pumpAndSettle();
  return (shown: shown, copied: copied!);
}

void main() {
  group('the copied text', () {
    test('names each of three days once, where it begins', () {
      var text = logAt(threeDays).copyText(at: DateTime.utc(2026, 11, 6, 9));

      expect(dayLines(text), [
        "--- 2026-11-04 ---",
        "--- 2026-11-05 ---",
        "--- 2026-11-06 ---",
      ]);
      // Each before the first entry of its day.
      expect(
        text,
        contains("Problems: 4 (the last 300 are kept)\n\n"
            "--- 2026-11-04 ---\n"
            "23:59:01.002  failure 0\n"),
      );
      expect(
          text,
          contains("Request: GET http://server/0\n--- 2026-11-05 ---\n"
              "00:00:03.040  failure 1\n"));
      expect(
          text,
          contains("Request: GET http://server/2\n--- 2026-11-06 ---\n"
              "08:15:09.007  failure 3\n"));
    });

    test('names a single day once', () {
      var text = logAt([
        DateTime(2026, 11, 6, 8),
        DateTime(2026, 11, 6, 9),
        DateTime(2026, 11, 6, 23, 59, 59),
      ]).copyText();

      expect(dayLines(text), ["--- 2026-11-06 ---"]);
    });

    test('keeps the time-of-day lines as they were', () {
      var text = logAt(threeDays).copyText();
      for (var line in timeLines) {
        expect(text, contains("$line\n"));
      }
    });

    test('names no day where there is no entry', () {
      expect(dayLines(DiagnosticsLog().copyText()), isEmpty);
    });
  });

  group('the screen', () {
    testWidgets('names each of three days once, in the device\'s language',
        (tester) async {
      var (:shown, :copied) = await showAndCopy(tester, logAt(threeDays));

      expect(dayLines(shown), [
        "--- Wed, Nov 4, 2026 ---",
        "--- Thu, Nov 5, 2026 ---",
        "--- Fri, Nov 6, 2026 ---",
      ]);
      expect(
          shown,
          startsWith("--- Wed, Nov 4, 2026 ---\n"
              "23:59:01.002  failure 0\n"));
      for (var line in timeLines) {
        expect(shown, contains(line));
      }
      // The copy stays in the bug report's own form.
      expect(dayLines(copied), [
        "--- 2026-11-04 ---",
        "--- 2026-11-05 ---",
        "--- 2026-11-06 ---",
      ]);
    });

    testWidgets('names a single day once', (tester) async {
      var (:shown, :copied) = await showAndCopy(
          tester,
          logAt([
            DateTime(2026, 11, 6, 8),
            DateTime(2026, 11, 6, 20),
          ]));

      expect(dayLines(shown), ["--- Fri, Nov 6, 2026 ---"]);
      expect(dayLines(copied), ["--- 2026-11-06 ---"]);
    });
  });
}
