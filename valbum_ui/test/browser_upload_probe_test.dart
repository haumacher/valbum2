/// Probe of issue #170, run in a real browser:
/// `flutter test --platform chrome test/browser_upload_probe_test.dart`.
///
/// The whole flow on the browser's path — hash, check, batches — composed:
/// what the server already holds is never sent, and every batch is a form of
/// its own carrying exactly its files.
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

  test('sends only what is new, one form per batch', () async {
    var a = randomBytes(5000, 11);
    var b = randomBytes(7000, 12);
    var c = randomBytes(9000, 13);
    var hashB = await sha256Of(Stream.value(b));
    var client = VAlbumClient(dataUrl: dataUrl);
    await answer({
      "POST": {
        "status": 200,
        "body": '{"present":[{"hash":"$hashB","name":"2019/Old/b.jpg"}]}',
      },
      "PUT": {"status": 200, "body": ''},
    });

    var summary = await client.uploadNew(
      ["Inbox"],
      [blobFile("a.jpg", a), blobFile("b.jpg", b), blobFile("c.jpg", c)],
      batching: const UploadBatching(maxFiles: 1),
    );

    var check = await received();
    expect(check["query"], "action=check");
    var sent = <String, Uint8List>{};
    for (var round = 0; round < 2; round++) {
      var put = await received();
      expect(put["method"], "PUT");
      var parts =
          partsOf(put["contentType"] as String, base64.decode(put["body"] as String));
      expect(parts, hasLength(1), reason: "a batch of one file");
      var name = RegExp(r'filename="([^"]+)"').firstMatch(parts.single.disposition)!.group(1)!;
      sent[name] = parts.single.bytes;
    }
    expect(sent.keys, unorderedEquals(["a.jpg", "c.jpg"]));
    expect(sent["a.jpg"], a);
    expect(sent["c.jpg"], c);
    expect(summary.stored, 2);
    expect(summary.present, 1);
    expect(summary.presentIn, contains("2019/Old/b.jpg"));
  });
}
