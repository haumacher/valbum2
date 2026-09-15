/// Tests of what [VAlbumClient.uploadNew] reports (issue #70): images the
/// server has confirmed, and a wheel value that may not lie.
///
/// The batches are transport and are never reported, but they are what the
/// count steps by: an image is on the server when the batch carrying it has
/// *answered*, not when its bytes left the device. The wheel may run ahead
/// within the batch that is in flight — otherwise a single large batch would
/// show nothing at all — but never further than that batch, and never to the
/// end before the last answer is in. That last rule is the lesson of issue
/// #59: the dialog is closed by the code, not by the value.
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';

/// An upload of [size] bytes, handed out in 1 KiB chunks so that the transport
/// can drain it slowly.
UploadFile sizedFile(String name, int size) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.fromIterable([
        for (var offset = 0; offset < size; offset += 1024)
          List<int>.filled(offset + 1024 > size ? size - offset : 1024, 7),
      ]),
    );

/// Twelve images, four to a batch below.
List<UploadFile> twelveImages({int size = 8192}) =>
    [for (var i = 0; i < 12; i++) sizedFile("img-$i.jpg", size)];

/// Four images to a batch, so that twelve of them are three batches.
const UploadBatching fourToABatch =
    UploadBatching(maxFiles: 4, maxBytes: 1 << 30);

/// The names a multipart body carries.
List<String> namesIn(String body) => [
      for (var match
          in RegExp(r'filename="([^"]+)"').allMatches(body))
        match.group(1)!,
    ];

/// A server that drains every PUT body chunk by chunk before it answers, and
/// counts the answers it has given.
class SlowBatchServer {
  /// How many PUTs have been answered.
  int answered = 0;

  /// The PUT number that fails instead of answering, `null` for none.
  final int? failOn;

  SlowBatchServer({this.failOn});

  int _puts = 0;

  http.Client get transport => MockClient.streaming((request, body) async {
        if (request.method == "POST") {
          await body.drain<void>();
          return http.StreamedResponse(
            http.ByteStream.fromBytes(utf8.encode('{"present":[]}')),
            200,
          );
        }
        _puts++;
        var names = <String>[];
        await for (var chunk in body) {
          names.addAll(namesIn(utf8.decode(chunk, allowMalformed: true)));
          await Future<void>.delayed(const Duration(milliseconds: 1));
        }
        if (_puts == failOn) {
          throw http.ClientException("Broken pipe", request.url);
        }
        answered++;
        return http.StreamedResponse(
          http.ByteStream.fromBytes(utf8.encode(
            '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
                '"hash":"h-$n","status":"stored"}').join(",")}]}',
          )),
          200,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      });

  VAlbumClient get valbum => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport,
      );
}

void main() {
  test('counts the images of a batch when that batch has answered', () async {
    var server = SlowBatchServer();
    var reports = <UploadProgress>[];
    var answeredAt = <int>[];

    var summary = await server.valbum.uploadNew(
      const ["album"],
      twelveImages(),
      batching: fourToABatch,
      onProgress: (report) {
        if (report.phase == UploadPhase.preparing ||
            report.phase == UploadPhase.asking) {
          return;
        }
        reports.add(report);
        answeredAt.add(server.answered);
      },
    );

    expect(summary.stored, 12);
    expect(server.answered, 3, reason: "three batches of four");

    // The count steps exactly at the answers: 0, 4, 8, 12.
    var counts = <int>[];
    for (var report in reports) {
      if (counts.isEmpty || counts.last != report.imagesDone) {
        counts.add(report.imagesDone);
      }
    }
    expect(counts, [0, 4, 8, 12]);

    // And it never counts an image whose batch has not answered.
    for (var i = 0; i < reports.length; i++) {
      expect(
        reports[i].imagesDone,
        4 * answeredAt[i],
        reason: "an image is on the server when its batch has answered",
      );
      expect(reports[i].imagesTotal, 12);
    }
  });

  test('the wheel is monotone, bounded by the batch, and full only at the end',
      () async {
    var server = SlowBatchServer();
    var reports = <UploadProgress>[];

    await server.valbum.uploadNew(
      const ["album"],
      twelveImages(),
      batching: fourToABatch,
      onProgress: (report) {
        if (report.phase == UploadPhase.preparing ||
            report.phase == UploadPhase.asking) {
          return;
        }
        reports.add(report);
      },
    );

    var fractions = [for (var report in reports) report.fraction];
    expect(fractions, isNotEmpty);
    for (var i = 1; i < fractions.length; i++) {
      expect(
        fractions[i],
        greaterThanOrEqualTo(fractions[i - 1]),
        reason: "progress never runs backwards",
      );
    }

    // The wheel may run ahead inside the batch that is in flight — that is
    // what keeps a single large batch from showing nothing — but never past
    // it: at most the four images of that batch.
    for (var report in reports) {
      expect(
        report.fraction,
        lessThanOrEqualTo((report.imagesDone + 4) / 12),
        reason: "never ahead of what the batch will confirm",
      );
      expect(report.fraction, greaterThanOrEqualTo(0));
    }
    // Something moved between the batches, or the wheel would be as useless
    // as the spinning one that was reported.
    expect(fractions.where((f) => f > 0 && f < 1).length, greaterThan(2));

    // Full exactly once, at the very end — the last answer is what fills it.
    expect(fractions.last, 1.0);
    expect(fractions.where((f) => f >= 1.0), hasLength(1));
    for (var i = 0; i < reports.length - 1; i++) {
      expect(
        reports[i].fraction,
        lessThan(1.0),
        reason: "an answer was still outstanding",
      );
    }
  });

  test('a lost connection counts the images the dialog last showed', () async {
    // The second PUT never answers: four images are on the server, eight are
    // not, and both halves are said in the same words as before (issue #64).
    var server = SlowBatchServer(failOn: 2);
    var reports = <UploadProgress>[];

    await expectLater(
      server.valbum.uploadNew(
        const ["album"],
        twelveImages(),
        batching: fourToABatch,
        onProgress: (report) {
          if (report.phase == UploadPhase.transferring ||
              report.phase == UploadPhase.waiting) {
            reports.add(report);
          }
        },
      ),
      throwsA(
        isA<UploadInterrupted>()
            .having((e) => e.message, "message", contains("4 von 12"))
            .having((e) => e.message, "message", contains("übrigen 8"))
            .having((e) => e.message, "message", contains("Verbindung"))
            .having((e) => e.summary.onServer, "on the server", 4)
            .having((e) => e.summary.remaining, "remaining", 8),
      ),
    );

    expect(server.answered, 1);
    expect(
      reports.last.imagesDone,
      4,
      reason: "the failure speaks of the number the dialog showed",
    );
    expect(reports.last.fraction, lessThan(1.0));
  });

  test('the line of every report is about images, never about batches',
      () async {
    var server = SlowBatchServer();
    var lines = <String>[];

    await server.valbum.uploadNew(
      const ["album"],
      twelveImages(),
      batching: fourToABatch,
      onProgress: (report) => lines.add(report.line),
    );

    expect(lines.first, "Wird vorbereitet: 1 von 12...");
    expect(lines, contains(uploadAskingMessage));
    expect(lines, contains("4 von 12 Bildern"));
    expect(lines, contains(uploadWaitingMessage));
    expect(lines.last, "12 von 12 Bildern");
    for (var line in lines) {
      expect(line, isNot(contains("Paket")));
    }
  });
}
