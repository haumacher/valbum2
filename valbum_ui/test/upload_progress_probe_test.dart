/// Probe of issue #59: the pulled-body progress, the phase messages and the
/// cancellation composed with the rest of `uploadNew` — the de-duplication
/// check, an older server, a refusal, and the diagnostics log.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

UploadFile sizedFile(String name, int size, int fill) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.fromIterable([
        for (var offset = 0; offset < size; offset += 1024)
          List<int>.filled(offset + 1024 > size ? size - offset : 1024, fill),
      ]),
    );

String storedAnswer(List<String> names) =>
    '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
        '"hash":"h-$n","status":"stored"}').join(",")}]}';

String messagesOf(DiagnosticsLog log) =>
    log.entries.map((entry) => entry.message).join("\n");

/// A server: answers the de-duplication check with [checkStatus] and, when
/// it is 200, reports every asked hash as present when [allPresent]; drains a
/// PUT body chunk by chunk with a small delay and answers it with [putStatus].
http.Client server({
  required int checkStatus,
  bool allPresent = false,
  int putStatus = 200,
  String putBody = "",
  void Function(int consumed)? onConsumed,
}) =>
    MockClient.streaming((request, bodyStream) async {
      if (request.url.query == "action=check") {
        var body = await utf8.decodeStream(bodyStream);
        if (checkStatus != 200) {
          return http.StreamedResponse(Stream.value(utf8.encode("")), 404);
        }
        var asked = (jsonDecode(body)["hashes"] as List)
            .map((e) => e["hash"] as String);
        var present = allPresent
            ? asked.map((h) => '{"hash":"$h","name":"old-$h"}').join(",")
            : "";
        return http.StreamedResponse(
          Stream.value(utf8.encode('{"present":[$present]}')),
          200,
        );
      }
      if (request.method == "PUT") {
        var consumed = 0;
        await for (var chunk in bodyStream) {
          await Future<void>.delayed(const Duration(milliseconds: 2));
          consumed += chunk.length;
          onConsumed?.call(consumed);
        }
        return http.StreamedResponse(
          Stream.value(utf8.encode(putBody)),
          putStatus,
        );
      }
      return http.StreamedResponse(Stream.value(utf8.encode("")), 404);
    });

void main() {
  const path = ["album"];
  final files = [sizedFile("a.jpg", 5000, 1), sizedFile("b.jpg", 7000, 2)];

  test('nothing to send: no PUT, one full report, the phases say so',
      () async {
    var log = DiagnosticsLog();
    var progress = <UploadProgress>[];
    var client = VAlbumClient(
      dataUrl: "http://pi/valbum/data",
      token: "t",
      log: log,
      httpClient: server(checkStatus: 200, allPresent: true),
    );

    var summary = await client.uploadNew(
      path,
      files,
      onProgress: progress.add,
    );

    expect(summary.stored, 0);
    expect(summary.present, 2);
    // The lines the person reads, in order: the preparing counts images, and
    // there is nothing to transfer, so the wheel is simply full (issue #70).
    expect([for (var report in progress) report.line], [
      "Wird vorbereitet: 1 von 2...",
      "Wird vorbereitet: 2 von 2...",
      uploadAskingMessage,
      "0 von 0 Bildern",
    ]);
    expect(progress.last.fraction, 1);
    var text = messagesOf(log);
    expect(text, contains("POST http://pi/valbum/data/album/?action=check (bearer) -> 200"));
    expect(text, isNot(contains("PUT")));
  });

  test('an older server: everything is sent, progress follows the drain',
      () async {
    var log = DiagnosticsLog();
    var progress = <double>[];
    var phases = <UploadPhase>[];
    var consumed = 0;
    var progressAtConsumption = <int, int>{};
    var client = VAlbumClient(
      dataUrl: "http://pi/valbum/data",
      log: log,
      httpClient: server(
        checkStatus: 404,
        putBody: storedAnswer(["a.jpg", "b.jpg"]),
        onConsumed: (c) => consumed = c,
      ),
    );

    var summary = await client.uploadNew(
      path,
      files,
      onProgress: (report) {
        phases.add(report.phase);
        if (report.phase == UploadPhase.transferring ||
            report.phase == UploadPhase.waiting) {
          progress.add(report.fraction);
          progressAtConsumption[report.percent] = consumed;
        }
      },
    );

    expect(summary.stored, 2);
    // The last thing said is the count of the images that arrived, not a word
    // about batches or bytes (issue #70).
    expect(progress.last, 1.0);
    // Monotone, ends at 1.0, and 1.0 is reported exactly once.
    for (var i = 1; i < progress.length; i++) {
      expect(progress[i], greaterThanOrEqualTo(progress[i - 1]));
    }
    expect(progress.where((p) => p == 1.0), hasLength(1));
    // The one wait the person is told about is the last body being out, see
    // issue #59.
    expect(phases, contains(UploadPhase.waiting));
    // The client never runs far ahead of the server: when it said 50 %, the
    // server had received a substantial part already (the pull is what is
    // counted, with one chunk of slack at most).
    var half = progressAtConsumption.keys.where((p) => p >= 50).reduce(
          (a, b) => a < b ? a : b,
        );
    expect(progressAtConsumption[half]!, greaterThan(0));
    var text = messagesOf(log);
    expect(text, contains("?action=check -> 404"));
    expect(text, contains("PUT http://pi/valbum/data/album/ -> 200"));
  });

  test('a refused PUT is the server speaking: message, log, online', () async {
    var log = DiagnosticsLog();
    var state = OfflineState()..goneOffline(null);
    var client = VAlbumClient(
      dataUrl: "http://pi/valbum/data",
      token: "t",
      log: log,
      offlineState: state,
      httpClient: server(
        checkStatus: 404,
        putStatus: 401,
        putBody: '["ErrorInfo",{"message":"Sign in first."}]',
      ),
    );

    await expectLater(
      client.uploadNew(path, files),
      throwsA(isA<VAlbumException>()
          .having((e) => e.message, "message", contains("Sign in first."))),
    );
    expect(state.offline, isFalse);
    expect(messagesOf(log), contains("PUT http://pi/valbum/data/album/ (bearer) -> 401"));
  });

  test('cancelled before the first byte: refused, never a result', () async {
    var handle = UploadHandle()..cancel();
    var client = VAlbumClient(
      dataUrl: "http://pi/valbum/data",
      httpClient: server(
        checkStatus: 404,
        putBody: storedAnswer(["a.jpg", "b.jpg"]),
      ),
    );

    await expectLater(
      client.uploadNew(path, files, handle: handle),
      throwsA(isA<VAlbumException>()
          .having((e) => e.message, "message", uploadCancelledMessage)),
    );
  });

  test('cancelled halfway through a slow drain: refused, never a result',
      () async {
    var handle = UploadHandle();
    var client = VAlbumClient(
      dataUrl: "http://pi/valbum/data",
      httpClient: server(
        checkStatus: 404,
        putBody: storedAnswer(["a.jpg", "b.jpg"]),
        onConsumed: (c) {
          if (c > 4000) {
            handle.cancel();
          }
        },
      ),
    );

    await expectLater(
      client.uploadNew(path, files, handle: handle),
      throwsA(isA<VAlbumException>()
          .having((e) => e.message, "message", uploadCancelledMessage)),
    );
  });
}
