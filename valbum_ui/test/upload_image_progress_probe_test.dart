/// Probe for the image-counting upload progress (issue #70), composed with the
/// dedupe of issue #29 (photos the server already holds) and a narrow phone.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/upload_progress.dart';
import 'util/l10n.dart';

UploadFile distinctFile(String name, int size, int fill) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.fromIterable([
        for (var offset = 0; offset < size; offset += 1024)
          List<int>.filled(offset + 1024 > size ? size - offset : 1024, fill),
      ]),
    );

List<String> namesIn(String body) => [
      for (var match in RegExp(r'filename="([^"]+)"').allMatches(body))
        match.group(1)!,
    ];

/// A server that already holds the first [presentCount] of the hashes it is
/// asked about, and drains every PUT slowly before answering.
class DedupingServer {
  final int presentCount;
  int puts = 0;
  DedupingServer(this.presentCount);

  http.Client get transport => MockClient.streaming((request, body) async {
        var text = utf8.decode(await body.fold<List<int>>([], (a, b) => a..addAll(b)), allowMalformed: true);
        if (request.method == "POST") {
          var hashes = {
            for (var m in RegExp(r'"hash":"([^"]+)"').allMatches(text)) m.group(1)!
          }.toList();
          var present = hashes.take(presentCount).toList();
          return http.StreamedResponse(
            http.ByteStream.fromBytes(utf8.encode(
              '{"present":[${present.asMap().entries.map((e) => '{"hash":"${e.value}","name":"old-${e.key}.jpg"}').join(",")}]}',
            )),
            200,
          );
        }
        puts++;
        await Future<void>.delayed(const Duration(milliseconds: 2));
        var names = namesIn(text);
        return http.StreamedResponse(
          http.ByteStream.fromBytes(utf8.encode(
            '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n","hash":"h-$n","status":"stored"}').join(",")}]}',
          )),
          200,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      });

  VAlbumClient get valbum =>
      VAlbumClient(dataUrl: "http://server/valbum/data", httpClient: transport);
}

void main() {
  test('counts only the images that are new to the server', () async {
    var server = DedupingServer(4);
    var reports = <UploadProgress>[];
    var files = [for (var i = 0; i < 12; i++) distinctFile("img-$i.jpg", 4096, i)];

    var summary = await server.valbum.uploadNew(
      const ["album"],
      files,
      batching: const UploadBatching(maxFiles: 4, maxBytes: 1 << 30),
      onProgress: reports.add,
    );

    expect(summary.stored, 8);
    expect(summary.present, 4);
    var transfer = reports.where((r) => r.phase == UploadPhase.transferring).toList();
    expect(transfer, isNotEmpty);
    expect(transfer.map((r) => r.imagesTotal).toSet(), {8},
        reason: "the total is what will arrive, not what was picked");
    expect(transfer.last.imagesDone, 8);
    expect(transfer.last.fraction, 1.0);
    expect(transfer.last.line, "8 von 8 Bildern");
    expect(transfer.where((r) => r.fraction >= 1.0).length, 1,
        reason: "1.0 exactly once, when the last batch has answered");
    for (var i = 1; i < transfer.length; i++) {
      expect(transfer[i].fraction, greaterThanOrEqualTo(transfer[i - 1].fraction));
      expect(transfer[i].imagesDone, greaterThanOrEqualTo(transfer[i - 1].imagesDone));
    }
    for (var r in reports) {
      expect(r.fraction.isNaN, isFalse, reason: r.toString());
      expect(r.percent, inInclusiveRange(0, 100));
    }
  });

  test('an upload of nothing new reports no NaN and no wheel', () async {
    var server = DedupingServer(3);
    var reports = <UploadProgress>[];
    var files = [for (var i = 0; i < 3; i++) distinctFile("img-$i.jpg", 2048, i)];

    var summary = await server.valbum.uploadNew(const ["album"], files, onProgress: reports.add);

    expect(summary.stored, 0);
    expect(summary.present, 3);
    expect(server.puts, 0);
    for (var r in reports) {
      expect(r.fraction.isNaN, isFalse, reason: r.toString());
      expect(r.percent, inInclusiveRange(0, 100), reason: r.toString());
      expect(r.line, isNot(contains("NaN")));
    }
  });

  test('a single large batch moves the wheel before its answer', () async {
    var server = DedupingServer(0);
    var reports = <UploadProgress>[];
    var files = [for (var i = 0; i < 3; i++) distinctFile("big-$i.jpg", 64 * 1024, i)];

    await server.valbum.uploadNew(const ["album"], files, onProgress: reports.add);

    var beforeAnswer = reports
        .where((r) => r.phase == UploadPhase.transferring && r.imagesDone == 0)
        .map((r) => r.fraction)
        .toSet();
    expect(beforeAnswer.length, greaterThanOrEqualTo(3), reason: "the wheel moves within the batch");
    expect(beforeAnswer.every((f) => f <= uploadProgressCeiling), isTrue);
  });

  testWidgets('a narrow phone shows a large count without overflow', (tester) async {
    await tester.binding.setSurfaceSize(const Size(320, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var progress = ValueNotifier(const UploadProgress(
      phase: UploadPhase.transferring,
      imagesDone: 1234,
      imagesTotal: 5678,
      fraction: 0.2173,
    ));
    var cancelled = 0;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Scaffold(
        body: UploadProgressDialog(progress: progress, onCancel: () => cancelled++),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text("1234 von 5678 Bildern"), findsOneWidget);
    expect(find.text("22 %"), findsOneWidget);
    var wheel = tester.widget<CircularProgressIndicator>(find.byKey(uploadProgressWheelKey));
    expect(wheel.value, closeTo(0.2173, 1e-9));

    progress.value = const UploadProgress(phase: UploadPhase.waiting, imagesDone: 5678, imagesTotal: 5678, fraction: 0.99);
    // An indeterminate wheel animates forever: one frame, not a settle.
    await tester.pump();
    expect(tester.widget<CircularProgressIndicator>(find.byKey(uploadProgressWheelKey)).value, isNull);
    expect(find.text(uploadWaitingMessage), findsOneWidget);

    await tester.tap(find.byKey(uploadProgressCancelKey));
    await tester.pump();
    expect(cancelled, 1);
    expect(find.byKey(uploadProgressDialogKey), findsOneWidget, reason: "cancel does not close the dialog");
    expect(tester.takeException(), isNull);
  });
}
