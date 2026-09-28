/// The upload of a browser (issue #170), run in a real browser:
/// `flutter test --platform chrome test/browser_upload_test.dart`.
///
/// The hash of a `Blob` — Web Crypto and the sliced fallback alike — must be
/// the very string `sha256Of` answers, and the `FormData` request must be what
/// the server has always been sent: `PUT`, `multipart/form-data`, one part per
/// file under a field and a file name of the file's own name, the bearer in
/// `Authorization`. The server is a real HTTP server, spawned as hybrid code
/// in the VM beside the browser, which hands every request it receives back
/// to the test.
@TestOn('browser')
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/browser_upload.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/locales.dart';

/// The SHA-256 of nothing.
const String emptyDigest =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/// The SHA-256 of the ASCII bytes "abc".
const String abcDigest =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

/// The server: answers `OPTIONS` for CORS, answers every other request with
/// the answer the test configured last for its method, and reports each such
/// request back — method, query, the two headers the test looks at, the body.
const String serverCode = r'''
import 'dart:convert';
import 'dart:io';
import 'package:stream_channel/stream_channel.dart';

Future<void> hybridMain(StreamChannel channel) async {
  var answers = <String, dynamic>{};
  var server = await HttpServer.bind('127.0.0.1', 0);
  channel.stream.listen((message) {
    answers = Map<String, dynamic>.from(message as Map);
    channel.sink.add('configured');
  });
  server.listen((request) async {
    var response = request.response;
    response.headers.set('Access-Control-Allow-Origin', '*');
    response.headers.set('Access-Control-Allow-Methods', 'GET, POST, PUT');
    response.headers.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    if (request.method == 'OPTIONS') {
      await response.close();
      return;
    }
    var body = <int>[];
    try {
      await for (var chunk in request) {
        body.addAll(chunk);
      }
    } catch (_) {
      return; // an aborted upload
    }
    channel.sink.add({
      'method': request.method,
      'query': request.uri.query,
      'authorization': request.headers.value('authorization'),
      'contentType': request.headers.value('content-type'),
      'body': base64.encode(body),
    });
    var answer = answers[request.method] as Map? ?? {'status': 500, 'body': ''};
    response.statusCode = answer['status'] as int;
    response.headers.contentType = ContentType.json;
    response.write(answer['body']);
    await response.close();
  });
  channel.sink.add(server.port);
}
''';

/// A multipart part as the server received it.
class ReceivedPart {
  final String disposition;
  final Uint8List bytes;

  ReceivedPart(this.disposition, this.bytes);
}

/// The parts of a `multipart/form-data` [body] with the boundary of
/// [contentType].
List<ReceivedPart> partsOf(String contentType, Uint8List body) {
  var boundary = RegExp(r'boundary=("?)([^";]+)\1').firstMatch(contentType)!.group(2)!;
  // Latin-1 maps every byte to one character and back, so the split keeps the
  // bytes of every part exactly as they were.
  var text = latin1.decode(body);
  var sections = text.split("--$boundary");
  var parts = <ReceivedPart>[];
  for (var section in sections.skip(1)) {
    if (section.startsWith("--")) {
      break;
    }
    var headerEnd = section.indexOf("\r\n\r\n");
    var headers = section.substring(2, headerEnd);
    var disposition = headers
        .split("\r\n")
        .firstWhere((line) => line.toLowerCase().startsWith("content-disposition"));
    var content = section.substring(headerEnd + 4, section.length - 2);
    parts.add(ReceivedPart(disposition, latin1.encode(content)));
  }
  return parts;
}

/// Random bytes, the same for every run.
Uint8List randomBytes(int length, int seed) {
  var random = Random(seed);
  return Uint8List.fromList([for (var i = 0; i < length; i++) random.nextInt(256)]);
}

/// A file of [bytes] carrying its `Blob`, as `uploadFromFiles` makes it on the
/// web.
UploadFile blobFile(String name, Uint8List bytes) => UploadFile(
      name: name,
      length: bytes.length,
      openRead: () => Stream.value(bytes),
      blob: blobOf(bytes, type: "image/jpeg"),
    );

void main() {
  group('sha256OfBlob', () {
    for (var webCrypto in [true, false]) {
      var how = webCrypto ? "Web Crypto" : "the sliced fallback";

      test('hashes nothing like sha256Of, through $how', () async {
        var blob = blobOf(Uint8List(0));
        expect(await sha256OfBlob(blob, useWebCrypto: webCrypto), emptyDigest);
        expect(await sha256Of(const Stream<List<int>>.empty()), emptyDigest);
      });

      test('hashes "abc" like sha256Of, through $how', () async {
        var bytes = Uint8List.fromList(ascii.encode("abc"));
        expect(await sha256OfBlob(blobOf(bytes), useWebCrypto: webCrypto), abcDigest);
        expect(await sha256Of(Stream.value(bytes)), abcDigest);
      });

      test('hashes contents over several slices like sha256Of, through $how',
          () async {
        // Not a multiple of the slice size, so the last slice is a short one.
        var bytes = randomBytes(3 * hashSliceBytes + 12345, 170);
        expect(
          await sha256OfBlob(blobOf(bytes), useWebCrypto: webCrypto),
          await sha256Of(Stream.value(bytes)),
        );
      });
    }
  });

  group('the upload of a browser', () {
    late StreamIterator<dynamic> messages;
    late StreamSink<dynamic> toServer;
    late String dataUrl;

    Future<dynamic> next() async {
      expect(await messages.moveNext(), isTrue);
      return messages.current;
    }

    setUpAll(() async {
      var channel = spawnHybridCode(serverCode);
      toServer = channel.sink;
      messages = StreamIterator(channel.stream);
      var port = await next() as int;
      dataUrl = "http://127.0.0.1:$port/valbum/data";
    });

    Future<void> answer(Map<String, Map<String, Object>> answers) async {
      toServer.add(answers);
      expect(await next(), "configured");
    }

    Future<Map<String, dynamic>> received() async =>
        Map<String, dynamic>.from(await next() as Map);

    test('sends the Blobs as the form the server reads, bearer and all',
        () async {
      var first = randomBytes(200000, 1);
      var second = randomBytes(1000, 2);
      var client = VAlbumClient(dataUrl: dataUrl, token: "secret-token");
      await answer({
        "POST": {"status": 200, "body": '{"present":[]}'},
        "PUT": {
          "status": 200,
          "body": '{"files":['
              '{"name":"IMG_1.jpg","storedAs":"IMG_1.jpg","hash":"h1","status":"stored"},'
              '{"name":"Ä b.jpg","storedAs":"Ä b.jpg","hash":"h2","status":"present"}]}',
        },
      });

      var progress = <UploadProgress>[];
      var summary = await client.uploadNew(
        ["2020", "Trip"],
        [blobFile("IMG_1.jpg", first), blobFile("Ä b.jpg", second)],
        onProgress: progress.add,
      );

      var check = await received();
      expect(check["method"], "POST");
      expect(check["query"], "action=check");
      var question = utf8.decode(base64.decode(check["body"] as String));
      expect(question, contains(await sha256Of(Stream.value(first))));
      expect(question, contains(await sha256Of(Stream.value(second))));

      var put = await received();
      expect(put["method"], "PUT");
      expect(put["authorization"], "Bearer secret-token");
      var contentType = put["contentType"] as String;
      expect(contentType, startsWith("multipart/form-data"));
      var parts = partsOf(contentType, base64.decode(put["body"] as String));
      expect(parts, hasLength(2));
      expect(parts[0].disposition,
          'Content-Disposition: form-data; name="IMG_1.jpg"; filename="IMG_1.jpg"');
      expect(parts[0].bytes, first);
      // The browser writes a name in UTF-8, as `MultipartRequest` did.
      expect(latin1.encode(parts[1].disposition),
          utf8.encode('Content-Disposition: form-data; name="Ä b.jpg"; filename="Ä b.jpg"'));
      expect(parts[1].bytes, second);

      expect(summary.stored, 1);
      expect(summary.present, 1);
      var transfer = [
        for (var step in progress)
          if (step.phase != UploadPhase.preparing && step.phase != UploadPhase.asking)
            step.fraction
      ];
      expect(transfer, isNotEmpty);
      expect(transfer.last, 1.0);
      for (var i = 1; i < transfer.length; i++) {
        expect(transfer[i], greaterThanOrEqualTo(transfer[i - 1]));
      }
    });

    test('reports the bytes of the body as they are sent', () async {
      var bytes = randomBytes(300000, 3);
      await answer({
        "PUT": {"status": 200, "body": ""},
      });
      var sent = <List<int>>[];
      var answered = await sendBlobForm(
        Uri.parse("$dataUrl/A/"),
        [(name: "a.jpg", blob: blobOf(bytes))],
        onProgress: (done, total) => sent.add([done, total]),
      );
      await received();

      expect(answered.status, 200);
      expect(sent, isNotEmpty);
      expect(sent.last[0], sent.last[1]);
      expect(sent.last[1], greaterThan(bytes.length));
    });

    test('takes an empty answer for everything stored (issue #29)', () async {
      var bytes = randomBytes(100, 4);
      var file = blobFile("a.jpg", bytes).withHash("abc123");
      await answer({
        "PUT": {"status": 200, "body": ""},
      });
      var client = VAlbumClient(dataUrl: dataUrl);
      var result = await client.uploadFiles("$dataUrl/A/", [file]);
      var put = await received();

      expect(put["authorization"], isNull);
      expect(result.files.single.name, "a.jpg");
      expect(result.files.single.status, uploadStored);
      expect(result.files.single.hash, "abc123");
    });

    test('says the server\'s own reason for a refusal', () async {
      await answer({
        "PUT": {
          "status": 403,
          "body": '["ErrorInfo",{"message":"Not yours to add to."}]',
        },
      });
      var client = VAlbumClient(dataUrl: dataUrl);
      await expectLater(
        client.uploadFiles("$dataUrl/A/", [blobFile("a.jpg", randomBytes(10, 5))]),
        throwsA(isA<VAlbumException>()
            .having((e) => e.message, "message", "Not yours to add to.")
            .having((e) => e.status, "status", 403)),
      );
      await received();
    });

    test('aborts a transfer cancelled halfway, and says so', () async {
      await answer({
        "PUT": {"status": 200, "body": ""},
      });
      var handle = UploadHandle();
      var client = VAlbumClient(dataUrl: dataUrl);
      var percents = <int>[];
      await expectLater(
        client.uploadFiles(
          "$dataUrl/A/",
          [blobFile("big.jpg", randomBytes(8 * 1024 * 1024, 7))],
          handle: handle,
          onProgress: (percent) {
            percents.add(percent);
            handle.cancel();
          },
        ),
        throwsA(isA<VAlbumException>().having(
            (e) => e.message, "message", uploadCancelledMessage(platformMessages))),
      );
      expect(percents, isNotEmpty);
    });

    test('is refused with the cancellation message once cancelled', () async {
      var handle = UploadHandle()..cancel();
      var client = VAlbumClient(dataUrl: dataUrl);
      await expectLater(
        client.uploadFiles(
          "$dataUrl/A/",
          [blobFile("a.jpg", randomBytes(10, 6))],
          handle: handle,
        ),
        throwsA(isA<VAlbumException>().having(
            (e) => e.message, "message", uploadCancelledMessage(platformMessages))),
      );
    });
  });
}
