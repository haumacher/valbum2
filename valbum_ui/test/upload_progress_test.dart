/// Tests of what the upload progress *means* (issue #59): the percentage is
/// the transfer, not the read from local storage.
///
/// The bug this pins down was reported from the Android app on a slow link:
/// uploading a hundred photos, the progress dialog ran to 100 %, closed itself
/// and left an album showing nothing — while most of the bytes were still on
/// their way. The cause was that the body was pumped into the unbounded sink
/// of an `http.StreamedRequest` as fast as the files could be read, and that
/// was what was counted.
///
/// The tests below therefore hand the client a transport that *drains* the body
/// slowly, and watch what the progress says while it does.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/diagnostics.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// An upload of [size] bytes under the given name, every file distinct.
UploadFile sizedFile(String name, int size, int fill) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.value(List<int>.filled(size, fill)),
    );

/// The body a server answers an upload of the given names with.
String storedAnswer(List<String> names) =>
    '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
        '"hash":"h-$n","status":"stored"}').join(",")}]}';

/// A transport that drains the request body chunk by chunk, waiting
/// [perChunk] in between, and only then answers.
///
/// This is what a slow link looks like from the app's side: `IOClient` pulls
/// the body with `addStream(request.finalize())`, so the bytes leave the device
/// at the pace the socket accepts them. Everything the tests here assert is
/// about *when* the client says a byte is gone.
class SlowTransport {
  /// How long a chunk takes to go out.
  final Duration perChunk;

  /// The body the server answers with once the whole body has arrived.
  final String answer;

  /// The status the server answers with.
  final int status;

  /// Whether the whole body has arrived.
  bool bodyConsumed = false;

  /// How many bytes of the body have arrived.
  int consumed = 0;

  /// Held open while the "server" thinks, if a test wants that phase.
  final Future<void> Function()? beforeAnswer;

  SlowTransport({
    this.perChunk = const Duration(milliseconds: 5),
    this.answer = "",
    this.status = 200,
    this.beforeAnswer,
  });

  /// The [http.Client] to hand to a [VAlbumClient].
  http.Client client({
    http.Response Function(http.BaseRequest request)? other,
  }) =>
      MockClient.streaming((request, bodyStream) async {
        if (request.method != "PUT") {
          var response = other == null
              ? http.Response("", 404)
              : other(request);
          await bodyStream.drain<void>();
          return http.StreamedResponse(
            http.ByteStream.fromBytes(response.bodyBytes),
            response.statusCode,
            headers: response.headers,
          );
        }
        await for (var chunk in bodyStream) {
          consumed += chunk.length;
          await Future<void>.delayed(perChunk);
        }
        bodyConsumed = true;
        await beforeAnswer?.call();
        return http.StreamedResponse(
          http.ByteStream.fromBytes(utf8.encode(answer)),
          status,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      });
}

void main() {
  group('the progress of an upload measures the transfer', () {
    test('does not report 100 before the body has left the device', () async {
      var progress = <int>[];
      // How much of the body the transport had really swallowed when the
      // client first said "100 %". That is the whole question of issue #59:
      // pumping into the unbounded sink of a `StreamedRequest` reported 100
      // after the first few milliseconds, while the transport was still on its
      // first chunk.
      int? consumedAtHundred;
      var transport = SlowTransport(
        answer: storedAnswer(const ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]),
        perChunk: const Duration(milliseconds: 2),
      );
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport.client(),
      );

      var result = await client.uploadFiles(
        "http://server/valbum/data/album/",
        [
          sizedFile("a.jpg", 4096, 1),
          sizedFile("b.jpg", 4096, 2),
          sizedFile("c.jpg", 4096, 3),
          sizedFile("d.jpg", 4096, 4),
        ],
        onProgress: (percent) {
          progress.add(percent);
          if (percent >= 100) {
            consumedAtHundred ??= transport.consumed;
          }
        },
      );

      expect(result.files, hasLength(4));
      expect(transport.bodyConsumed, isTrue);
      expect(progress.last, 100, reason: "the whole body did go out");

      var body = transport.consumed;
      expect(body, greaterThan(4 * 4096));
      expect(
        consumedAtHundred,
        isNotNull,
        reason: "100 % was reported at some point",
      );
      // A chunk or two may still be in the transport's own hands when the
      // last one is handed over — that is the socket buffer, and it is all
      // the slack there is. What must never happen again is 100 % while the
      // body has barely started to move.
      expect(
        consumedAtHundred!,
        greaterThan(body ~/ 2),
        reason: "100 % means the bytes are gone, not that they were read",
      );
    });

    test('reports a monotone percentage ending at 100', () async {
      var progress = <int>[];
      var transport = SlowTransport(
        answer: storedAnswer(const ["a.jpg", "b.jpg"]),
        perChunk: const Duration(milliseconds: 1),
      );
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport.client(),
      );

      await client.uploadFiles(
        "http://server/valbum/data/album/",
        [sizedFile("a.jpg", 4096, 1), sizedFile("b.jpg", 4096, 2)],
        onProgress: progress.add,
      );

      expect(progress, isNotEmpty);
      expect(progress.length, greaterThan(2), reason: "several chunks");
      for (var i = 1; i < progress.length; i++) {
        expect(
          progress[i],
          greaterThanOrEqualTo(progress[i - 1]),
          reason: "progress never runs backwards",
        );
      }
      expect(progress.where((p) => p == 100), hasLength(1));
      expect(progress.last, 100);
      expect(progress.first, lessThan(100));
    });

    test('a cancelled upload never answers a result', () async {
      var handle = UploadHandle();
      var transport = SlowTransport(
        answer: storedAnswer(const ["a.jpg"]),
        perChunk: const Duration(milliseconds: 1),
      );
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport.client(),
      );

      await expectLater(
        client.uploadFiles(
          "http://server/valbum/data/album/",
          [sizedFile("a.jpg", 4096, 1), sizedFile("b.jpg", 4096, 2)],
          handle: handle,
          // Cancelled in the middle of the transfer, from the progress it
          // reports — which is what the cancel button of the dialog does.
          onProgress: (percent) {
            if (percent > 10) {
              handle.cancel();
            }
          },
        ),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', uploadCancelledMessage),
        ),
      );
      expect(
        transport.bodyConsumed,
        isFalse,
        reason: "the transfer stopped where it was cancelled",
      );
    });

    test('is logged as one answered PUT, without the body', () async {
      var log = DiagnosticsLog();
      var transport = SlowTransport(
        answer: storedAnswer(const ["a.jpg"]),
        perChunk: const Duration(milliseconds: 1),
      );
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "secret-bearer-token",
        log: log,
        httpClient: transport.client(),
      );

      await client.uploadFiles(
        "http://server/valbum/data/album/",
        [sizedFile("a.jpg", 4096, 0x53)],
      );

      var lines = [for (var entry in log.entries) entry.message];
      expect(
        lines,
        ["PUT http://server/valbum/data/album/ (bearer) -> 200"],
      );
      expect(lines.join("\n"), isNot(contains("secret-bearer-token")));
    });
  });

  group('the upload dialog of the album', () {
    testWidgets('stays up until the server has answered',
        (WidgetTester tester) async {
      var listings = 0;
      var answer = Completer<void>();
      var transport = SlowTransport(
        answer: storedAnswer(const ["a.jpg"]),
        perChunk: const Duration(milliseconds: 5),
        beforeAnswer: () => answer.future,
      );
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: transport.client(
          other: (request) {
            if (isThumbnailRequest(request)) {
              return http.Response.bytes(
                transparentPixelPng,
                200,
                headers: const {"content-type": "image/png"},
              );
            }
            if (request.method == "POST") {
              return http.Response('{"present":[]}', 200);
            }
            listings++;
            return http.Response(fixture("album.json"), 200);
          },
        ),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        expect(listings, 1);

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        var upload = state.uploadPicked([sizedFile("a.jpg", 8192, 7)]);

        // The body goes out chunk by chunk; the dialog is up all the while.
        for (var i = 0; i < 40 && !transport.bodyConsumed; i++) {
          await tester.pump(const Duration(milliseconds: 5));
        }
        expect(transport.bodyConsumed, isTrue, reason: "the body did go out");

        await tester.pump(const Duration(milliseconds: 5));
        expect(
          find.byType(AlertDialog),
          findsOneWidget,
          reason: "the server has not answered yet",
        );
        expect(find.text(uploadWaitingMessage), findsOneWidget);

        // Now the server answers.
        answer.complete();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 600));
        await upload;
        await tester.pumpAndSettle();
      });

      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text("1 hochgeladen, 0 bereits vorhanden."), findsOneWidget);
      expect(listings, 2, reason: "the album was fetched again");
    });
  });
}
