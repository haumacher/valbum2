/// Tests of the batched upload (issue #63): many photos go up in several
/// requests, and what one of them delivered stays delivered.
///
/// The bug this pins down was reported from the Android app: an upload of a
/// hundred photos survives only as long as the screen stays on. A locked screen
/// backgrounds the app, Doze suspends its network and the single streamed
/// multipart body of many megabytes breaks in the middle — everything is lost,
/// and the next unlock shows a red bar with the socket's own words on it.
///
/// Bounded batches make the loss bounded: the tests below count the requests,
/// watch the progress run across them, and read the sentence a partial failure
/// is reported with.
library;

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// An upload of [size] bytes under the given name, with a hash of its own, so
/// that nothing is taken for a duplicate.
UploadFile sized(String name, int size) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.value(List<int>.filled(size, 7)),
      sha256: "hash-of-$name",
    );

/// [count] files of [size] bytes each, named `img-0.jpg` and upwards.
List<UploadFile> files(int count, {int size = 16}) =>
    [for (var i = 0; i < count; i++) sized("img-$i.jpg", size)];

/// The names the multipart body of the given request carries.
List<String> namesIn(String body) => [
      for (var match in RegExp('filename="([^"]+)"').allMatches(body))
        match.group(1)!,
    ];

/// The body a server answers an upload of the given names with.
String storedAnswer(List<String> names) =>
    '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
        '"hash":"h-$n","status":"stored"}').join(",")}]}';

/// A server that records every PUT and answers "stored" for what it was sent.
///
/// [failOn] is the number of the PUT (1-based) that fails, `0` for a server
/// that answers everything.
class BatchServer {
  /// The names of every PUT, in order: one entry per request.
  final List<List<String>> puts = [];

  /// The number of the PUT that fails, `0` for none.
  final int failOn;

  /// What the failing PUT does: a lost connection by default.
  final Object Function()? failure;

  /// What the album already holds, by hash.
  final Map<String, String> present;

  BatchServer({this.failOn = 0, this.failure, this.present = const {}});

  http.Client get client => MockClient((request) async {
        if (request.method == "POST") {
          return http.Response(
            '{"present":[${present.entries.map((e) => '{"hash":"${e.key}","name":"${e.value}"}').join(",")}]}',
            200,
          );
        }
        var names = namesIn(utf8.decode(request.bodyBytes));
        puts.add(names);
        if (puts.length == failOn) {
          throw failure?.call() ??
              http.ClientException("Connection reset by peer", request.url);
        }
        return http.Response(
          storedAnswer(names),
          200,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      });

  VAlbumClient get valbum => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: client,
      );
}

void main() {
  group('the batches themselves', () {
    test('splits by the number of files', () {
      var batches = const UploadBatching(maxFiles: 25).split(
        files(60),
        (file) => file.length,
      );

      expect([for (var batch in batches) batch.length], [25, 25, 10]);
    });

    test('splits by the number of bytes, before the count is reached', () {
      // Four files of 40 MB: two fit under the 100 MB bound, the third opens
      // a batch of its own.
      var batches = UploadBatching.standard.split(
        [for (var i = 0; i < 4; i++) sized("big-$i.jpg", 40 * 1024 * 1024)],
        (file) => file.length,
      );

      expect([for (var batch in batches) batch.length], [2, 2]);
    });

    test('never splits a single item, however big it is', () {
      var batches = const UploadBatching(maxFiles: 25, maxBytes: 1000).split(
        [sized("huge.mp4", 5000), sized("small.jpg", 10)],
        (file) => file.length,
      );

      expect([for (var batch in batches) batch.length], [1, 1]);
    });
  });

  group('an upload of many photos', () {
    test('goes out in three requests of 25, 25 and 10', () async {
      var server = BatchServer();

      var summary = await server.valbum.uploadNew(const ["album"], files(60));

      expect(server.puts, hasLength(3), reason: "one PUT per batch");
      expect(
        [for (var put in server.puts) put.length],
        [25, 25, 10],
      );
      expect(server.puts.first.first, "img-0.jpg");
      expect(server.puts.last.last, "img-59.jpg");
      expect(summary.stored, 60);
      expect(summary.remaining, 0);
      expect(summary.complete, isTrue);
    });

    test('asks the server once for all of them, before the first byte',
        () async {
      var asked = <String>[];
      var puts = 0;
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async {
          if (request.method == "POST") {
            expect(puts, 0, reason: "asked before anything was transferred");
            asked.add(request.body);
            return http.Response('{"present":[]}', 200);
          }
          puts++;
          var names = namesIn(utf8.decode(request.bodyBytes));
          return http.Response(storedAnswer(names), 200);
        }),
      );

      await client.uploadNew(const ["album"], files(60));

      expect(asked, hasLength(1), reason: "one check for all the batches");
      expect(asked.single, contains("hash-of-img-59.jpg"));
      expect(puts, 3);
    });

    test('reports progress monotonically, reaching 1.0 exactly once',
        () async {
      var server = BatchServer();
      var progress = <double>[];

      await server.valbum.uploadNew(
        const ["album"],
        files(60, size: 4096),
        onProgress: (report) {
          if (report.phase == UploadPhase.transferring ||
              report.phase == UploadPhase.waiting) {
            progress.add(report.fraction);
          }
        },
      );

      expect(progress, isNotEmpty);
      for (var i = 1; i < progress.length; i++) {
        expect(progress[i], greaterThanOrEqualTo(progress[i - 1]));
      }
      expect(progress.where((p) => p == 1.0), hasLength(1));
      expect(progress.last, 1.0);
      expect(
        progress.where((p) => p > 0 && p < 1.0),
        isNotEmpty,
        reason: "the batches in between are visible",
      );
    });

    test('never tells the person about a batch, only about images (issue #70)',
        () async {
      var server = BatchServer();
      var lines = <String>[];

      await server.valbum.uploadNew(
        const ["album"],
        files(60),
        onProgress: (report) => lines.add(report.lineOf(testL10n)),
      );

      // Three batches went out, see the test above; not one of them is named.
      expect(server.puts, hasLength(3));
      for (var line in lines) {
        expect(line, isNot(contains("batch")));
        expect(line, isNot(contains("of 3")));
      }
      // What the person reads while the transfer runs is images, and the count
      // ends where the batches end.
      expect(lines, contains("0 of 60 images"));
      expect(lines, contains("25 of 60 images"));
      expect(lines, contains("50 of 60 images"));
      expect(lines.last, "60 of 60 images");
    });

    test('counts the images the server confirmed, batch by batch', () async {
      var server = BatchServer();
      var confirmed = <int>[];

      await server.valbum.uploadNew(
        const ["album"],
        files(60),
        onProgress: (report) {
          // The preparing phase counts the file it is hashing, not the server,
          // see [UploadProgress.imagesDone]; the transfer is what is asked
          // about here.
          if (report.phase != UploadPhase.transferring &&
              report.phase != UploadPhase.waiting) {
            return;
          }
          if (confirmed.isEmpty || confirmed.last != report.imagesDone) {
            confirmed.add(report.imagesDone);
          }
        },
      );

      // A batch counts when it has answered, never when it was sent: 25, 25
      // and the 10 that are left, see [UploadProgress.imagesDone].
      expect(confirmed, [0, 25, 50, 60]);
    });
  });

  group('a batch that fails', () {
    test('names what arrived, what did not, and that retrying is safe',
        () async {
      var server = BatchServer(failOn: 2);

      await expectLater(
        server.valbum.uploadNew(const ["album"], files(60)),
        throwsA(
          isA<UploadInterrupted>()
              .having((e) => e.message, "message", contains("25 are on the server"))
              .having((e) => e.message, "message", contains("remaining 35"))
              .having((e) => e.message, "message", contains("sent again"))
              .having((e) => e.message, "message", contains("Connection lost"))
              .having((e) => e.summary.onServer, "on the server", 25)
              .having((e) => e.summary.remaining, "remaining", 35)
              .having((e) => e.summary.total, "total", 60)
              .having((e) => e.summary.complete, "complete", isFalse),
        ),
      );
      expect(server.puts, hasLength(2), reason: "nothing after the failure");
    });

    test('keeps the raw exception for the log, never for the screen', () async {
      var server = BatchServer(failOn: 2);

      try {
        await server.valbum.uploadNew(const ["album"], files(60));
        fail("the second batch failed");
      } on UploadInterrupted catch (error) {
        expect(error.message, isNot(contains("ClientException")));
        expect(error.cause.toString(), contains("Connection reset"));
      }
    });

    test('counts the photos the album already held as being on the server',
        () async {
      // The first ten are known to the server; the remaining 50 go out in two
      // batches, and the second one fails.
      var server = BatchServer(
        failOn: 2,
        present: {
          for (var i = 0; i < 10; i++) "hash-of-img-$i.jpg": "img-$i.jpg",
        },
      );

      try {
        await server.valbum.uploadNew(const ["album"], files(60));
        fail("the second batch failed");
      } on UploadInterrupted catch (error) {
        expect(error.summary.present, 10);
        expect(error.summary.stored, 25);
        expect(error.summary.onServer, 35);
        expect(error.summary.remaining, 25);
        expect(error.summary.total, 60);
        expect(error.message, contains("35 are on the server"));
      }
    });

    test('is the server speaking where nothing arrived at all', () async {
      var server = BatchServer(
        failOn: 1,
        failure: () => const VAlbumException("Sign in first.", status: 401),
      );

      await expectLater(
        server.valbum.uploadNew(const ["album"], files(60)),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, "message", contains("Sign in first."))
              .having((e) => e is UploadInterrupted, "partial", isFalse),
        ),
      );
    });

    test('quotes a refusal of a later batch in the same plain sentence',
        () async {
      var server = BatchServer(
        failOn: 2,
        failure: () => const VAlbumException("Album ist voll.", status: 507),
      );

      try {
        await server.valbum.uploadNew(const ["album"], files(60));
        fail("the second batch was refused");
      } on UploadInterrupted catch (error) {
        expect(error.message, startsWith("Album ist voll: "));
        expect(error.message, contains("25 are on the server"));
      }
    });
  });

  group('what the album says about a broken upload', () {
    testWidgets('shows the plain sentence and fetches what arrived',
        (WidgetTester tester) async {
      var listings = 0;
      var puts = 0;
      var client = clientHandling((request) {
        if (request.method == "PUT") {
          puts++;
          if (puts == 2) {
            throw http.ClientException("Broken pipe", request.url);
          }
          return http.Response(
            storedAnswer(namesIn(request.body)),
            200,
          );
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        listings++;
        return http.Response(fixture("album.json"), 200);
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        expect(listings, 1);

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked(files(60));
        await tester.pumpAndSettle();
      });

      expect(puts, 2, reason: "nothing was sent after the failure");
      expect(
        find.textContaining("Of 60 photos, 25 are on the server"),
        findsOneWidget,
      );
      expect(find.textContaining("ClientException"), findsNothing);
      expect(
        listings,
        2,
        reason: "what did arrive is fetched again",
      );
    });

    testWidgets('says a lost connection in words even when nothing arrived',
        (WidgetTester tester) async {
      var client = clientHandling((request) {
        if (request.method == "PUT") {
          throw http.ClientException("Broken pipe", request.url);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        return http.Response(fixture("album.json"), 200);
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked(files(3));
        await tester.pumpAndSettle();
      });

      expect(
        find.textContaining("Connection lost: Of 3 photos, 0 are on the server"),
        findsOneWidget,
      );
      expect(find.textContaining("ClientException"), findsNothing);
    });
  });
}
