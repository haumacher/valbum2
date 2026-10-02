/// Tests of the count of images *sent* (issue #194).
///
/// The report: the ring moved, but "images uploaded" stayed at 0 — the count
/// was of images the server had confirmed, which is whole batches, so an
/// upload of up to 25 photos showed 0 the whole time. The author's decision:
/// "does not need to be exact — the count sent is sufficient". While the bytes
/// go out, the dialog counts the photos whose bytes have fully gone, see
/// [imagesSentOf] and [UploadProgress.imagesSent]; a failure still speaks of
/// what the server confirmed, see [UploadProgress.imagesDone].
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/upload_progress.dart';

import 'util/l10n.dart';

const int mb = 1024 * 1024;

/// An upload of [size] bytes, handed out in 64 KiB chunks, with a hash of its
/// own so that nothing is taken for a duplicate.
UploadFile sizedFile(String name, int size) => UploadFile(
      name: name,
      length: size,
      sha256: "hash-of-$name",
      openRead: () async* {
        const chunk = 64 * 1024;
        for (var offset = 0; offset < size; offset += chunk) {
          yield Uint8List(offset + chunk > size ? size - offset : chunk);
        }
      },
    );

/// The names a multipart body carries.
List<String> namesIn(String body) => [
      for (var match in RegExp(r'filename="([^"]+)"').allMatches(body))
        match.group(1)!,
    ];

/// What the transport saw while a PUT body went out: the bytes the server had
/// received and the count the upload reported at that moment.
typedef Seen = ({int received, int total, int sent});

/// A server that drains every PUT body chunk by chunk and notes, at every
/// chunk, what the upload had last reported; [failOn] is the PUT (1-based)
/// that fails after its body went out.
class WatchingServer {
  final int? failOn;
  WatchingServer({this.failOn});

  /// The last report of the upload, set by the test's `onProgress`.
  UploadProgress? last;

  /// Per PUT, what was seen while its body went out.
  final List<List<Seen>> seen = [];

  http.Client get transport => MockClient.streaming((request, body) async {
        if (request.method == "POST") {
          await body.drain<void>();
          return http.StreamedResponse(
            http.ByteStream.fromBytes(utf8.encode('{"present":[]}')),
            200,
          );
        }
        var total = request.contentLength!;
        var received = 0;
        var names = <String>[];
        var trace = <Seen>[];
        seen.add(trace);
        await for (var chunk in body) {
          received += chunk.length;
          if (chunk.length < 4096) {
            names.addAll(namesIn(latin1.decode(chunk)));
          }
          trace.add((received: received, total: total, sent: last!.imagesSent));
        }
        if (seen.length == failOn) {
          throw http.ClientException("Broken pipe", request.url);
        }
        return http.StreamedResponse(
          http.ByteStream.fromBytes(utf8.encode(
            '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
                '"hash":"h-$n","status":"stored"}').join(",")}]}',
          )),
          200,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      });

  VAlbumClient get valbum =>
      VAlbumClient(dataUrl: "http://server/valbum/data", httpClient: transport);
}

/// The count sent when [share] of the body had been received.
int sentAt(List<Seen> trace, double share) =>
    trace.firstWhere((s) => s.received >= share * s.total).sent;

void main() {
  group('imagesSentOf', () {
    test('maps the bytes onto the files in order, overhead apportioned', () {
      var twenty = List.filled(20, mb);
      // A body of the files plus 2 % overhead.
      var total = 20 * mb * 102 ~/ 100;
      expect(imagesSentOf(twenty, 0, total), 0);
      expect(imagesSentOf(twenty, total ~/ 2, total), 10);
      expect(imagesSentOf(twenty, total ~/ 2 - 1, total), 9);
      expect(imagesSentOf(twenty, total - 1, total), 19);
      expect(imagesSentOf(twenty, total, total), 20);
      var previous = 0;
      for (var sent = 0; sent <= total; sent += total ~/ 997) {
        var count = imagesSentOf(twenty, sent, total);
        expect(count, greaterThanOrEqualTo(previous));
        expect(count, lessThanOrEqualTo(20));
        previous = count;
      }
    });

    test('a large file first holds the count until it is out', () {
      var lengths = [50 * mb, ...List.filled(10, mb)];
      var total = 60 * mb;
      expect(imagesSentOf(lengths, (total * 0.4).round(), total), 0);
      expect(imagesSentOf(lengths, 50 * mb - 1, total), 0);
      expect(imagesSentOf(lengths, 50 * mb, total), 1);
      expect(imagesSentOf(lengths, total, total), 11);
    });

    test('an empty body is all sent, nothing sent is nothing', () {
      expect(imagesSentOf(const [], 0, 0), 0);
      expect(imagesSentOf(const [3, 0, 4], 0, 0), 3);
      expect(imagesSentOf(const [3, 0, 4], 3, 7), 2,
          reason: "a file of no bytes counts with the one before it");
      expect(imagesSentOf(const [3, 4], 0, 7), 0);
    });
  });

  group('uploadNew counts what is sent', () {
    test('twenty files of 1 MB in one batch: 0, …, 20 during the transfer',
        () async {
      var server = WatchingServer();
      var sent = <int>[];
      var summary = await server.valbum.uploadNew(
        const ["album"],
        [for (var i = 0; i < 20; i++) sizedFile("img-$i.jpg", mb)],
        onProgress: (report) {
          server.last = report;
          if (report.phase == UploadPhase.transferring ||
              report.phase == UploadPhase.waiting) {
            sent.add(report.imagesSent);
          }
        },
      );

      expect(summary.stored, 20);
      expect(server.seen, hasLength(1), reason: "one batch");
      for (var i = 1; i < sent.length; i++) {
        expect(sent[i], greaterThanOrEqualTo(sent[i - 1]));
        expect(sent[i], lessThanOrEqualTo(20));
      }
      expect(sent.toSet(), containsAll([for (var i = 0; i <= 20; i++) i]),
          reason: "every count is shown on the way");
      expect(sent.last, 20);

      var trace = server.seen.single;
      expect(sentAt(trace, 0.5), inInclusiveRange(9, 11));
      // Before the server answered, the count was already that of the bytes.
      expect(trace.last.sent, 20);
    });

    test('two batches: the count goes on from the first 25', () async {
      var server = WatchingServer();
      var reports = <UploadProgress>[];
      await server.valbum.uploadNew(
        const ["album"],
        [for (var i = 0; i < 30; i++) sizedFile("img-$i.jpg", 256 * 1024)],
        onProgress: (report) {
          server.last = report;
          if (report.phase == UploadPhase.transferring ||
              report.phase == UploadPhase.waiting) {
            reports.add(report);
          }
        },
      );

      expect(server.seen, hasLength(2));
      expect(server.seen[0].last.sent, 25);
      var second = server.seen[1];
      expect(second.first.sent, 25, reason: "continues from the first batch");
      expect(sentAt(second, 0.5), inInclusiveRange(27, 28));
      expect(second.last.sent, 30);
      for (var i = 1; i < reports.length; i++) {
        expect(reports[i].imagesSent,
            greaterThanOrEqualTo(reports[i - 1].imagesSent));
        expect(
            reports[i].imagesSent, greaterThanOrEqualTo(reports[i].imagesDone));
      }
      expect(reports.last.imagesSent, 30);
      expect(reports.last.imagesDone, 30);
    });

    test('one 50 MB file before ten of 1 MB: 0 at 40 %, 1 once it is out',
        () async {
      var server = WatchingServer();
      await server.valbum.uploadNew(
        const ["album"],
        [
          sizedFile("big.mov", 50 * mb),
          for (var i = 0; i < 10; i++) sizedFile("img-$i.jpg", mb),
        ],
        onProgress: (report) => server.last = report,
      );

      var trace = server.seen.single;
      expect(sentAt(trace, 0.4), 0);
      // Once the big file's bytes (and its share of the overhead) are out.
      var afterBig = trace.firstWhere((s) => s.sent > 0);
      expect(afterBig.sent, 1);
      expect(afterBig.received, greaterThanOrEqualTo(50 * mb));
      expect(afterBig.received, lessThan(51 * mb));
      expect(trace.last.sent, 11);
    });

    test('a failing batch names what the server confirmed, not what was sent',
        () async {
      var server = WatchingServer(failOn: 2);
      var reports = <UploadProgress>[];
      await expectLater(
        server.valbum.uploadNew(
          const ["album"],
          [for (var i = 0; i < 30; i++) sizedFile("img-$i.jpg", 64 * 1024)],
          onProgress: (report) {
            server.last = report;
            reports.add(report);
          },
        ),
        throwsA(isA<UploadInterrupted>().having(
          (e) => e.message,
          "message",
          contains("Of 30 photos, 25 are on the server; the remaining 5"),
        )),
      );
      // All thirty had been sent when the second batch failed …
      expect(reports.last.imagesSent, 30);
      // … but only twenty-five were ever confirmed.
      expect(reports.last.imagesDone, 25);
    });
  });

  testWidgets('the dialog shows a non-zero count while the bytes go out',
      (tester) async {
    var progress = ValueNotifier(
      const UploadProgress(
        phase: UploadPhase.transferring,
        imagesDone: 0,
        imagesSent: 7,
        imagesTotal: 20,
        fraction: 0.37,
      ),
    );
    addTearDown(progress.dispose);
    await tester.pumpWidget(
      localizedApp(UploadProgressDialog(progress: progress, onCancel: () {})),
    );

    expect(
      tester.widget<Text>(find.byKey(uploadProgressCountKey)).data,
      "7 of 20 images sent",
    );
    expect(find.text("37 %"), findsOneWidget);
  });
}
