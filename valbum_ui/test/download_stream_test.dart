/// A download is written as it arrives, never held whole (issue #209).
///
/// The desktop's save dialog comes first, then the answer is streamed into
/// the chosen file chunk by chunk ([streamIntoFile], which the phone's saver
/// writes its temporary file with as well). These tests feed the response
/// through a [StreamController], one chunk at a time, and look at the file
/// on disk before the next chunk is let through: what was received is there
/// already, so nothing waits in memory for the end of the transfer.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/downloads.dart';
import 'package:valbum_ui/platform_io.dart';

const String dataUrl = "http://server/valbum/data";

/// One chunk of the large body: 64 KiB of a byte that names its position.
Uint8List chunk(int index) =>
    Uint8List(64 * 1024)..fillRange(0, 64 * 1024, index % 251);

/// A server whose answers to downloads are fed by [body], recording requests.
class StreamingServer {
  final StreamController<List<int>> body = StreamController<List<int>>();
  final List<http.BaseRequest> requests = [];
  final List<String> posted = [];
  bool cancelled = false;
  int status = 200;
  int? length;

  StreamingServer() {
    body.onCancel = () => cancelled = true;
  }

  VAlbumClient client() => VAlbumClient(
        dataUrl: dataUrl,
        token: "tok-1",
        httpClient: MockClient.streaming((request, bodyStream) async {
          requests.add(request);
          posted.add(await bodyStream.bytesToString());
          if (status != 200) {
            return http.StreamedResponse(
              Stream.value(utf8.encode(
                  '["ErrorInfo", {"message": "You may not download this."}]')),
              status,
              headers: {"content-type": "application/json"},
            );
          }
          return http.StreamedResponse(
            body.stream,
            200,
            contentLength: length,
            headers: {"content-type": "application/zip"},
          );
        }),
      );
}

/// Waits (in real time) until [condition] holds.
Future<void> until(bool Function() condition) async {
  for (var n = 0; n < 500 && !condition(); n++) {
    await Future<void>.delayed(const Duration(milliseconds: 2));
  }
  expect(condition(), isTrue);
}

void main() {
  late Directory directory;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp("valbum-download-test");
  });

  tearDown(() async {
    await directory.delete(recursive: true);
  });

  test('the desktop writes every chunk before the next one arrives', () async {
    var server = StreamingServer()..length = 64 * 64 * 1024;
    var target = File("${directory.path}/Zoo.zip");
    var asked = <String>[];
    var saver = FileDialogDownloadSaver(chooseLocation: (name) async {
      asked.add(name);
      return target.path;
    });
    var progress = DownloadProgress();

    var saving = downloadOriginals(
      server.client(),
      ["2024", "Zoo"],
      ["a.jpg", "b.mp4"],
      archiveName: "Zoo.zip",
      saver: saver,
      progress: progress,
    );

    // The dialog first, the request after it.
    await until(() => server.requests.isNotEmpty);
    expect(asked, ["Zoo.zip"]);
    var request = server.requests.single;
    expect(request.method, "POST");
    expect(request.url.toString(), "$dataUrl/2024/Zoo/?action=zip");
    expect(request.headers["Authorization"], "Bearer tok-1");
    expect(
      [
        for (var name
            in (jsonDecode(server.posted.single) as Map)["names"] as List)
          (name as Map)["name"],
      ],
      ["a.jpg", "b.mp4"],
    );

    // Four megabytes in 64 chunks, each one on disk before the next is sent.
    var written = 0;
    for (var index = 0; index < 64; index++) {
      var next = chunk(index);
      server.body.add(next);
      written += next.length;
      await until(() => progress.received == written);
      expect(target.lengthSync(), written,
          reason: "chunk $index is written as it arrives");
    }
    expect(progress.total, 64 * 64 * 1024);
    await server.body.close();

    var result = await saving;
    expect(result.name, "Zoo.zip");
    expect(result.count, 2);
    var bytes = target.readAsBytesSync();
    expect(bytes.length, 64 * 64 * 1024);
    expect(bytes[0], 0);
    expect(bytes[63 * 64 * 1024], 63);
  });

  test('a dialog closed without a choice fetches nothing', () async {
    var server = StreamingServer();
    var saver = FileDialogDownloadSaver(chooseLocation: (_) async => null);

    var result = await downloadOriginals(
      server.client(),
      ["Zoo"],
      ["a.jpg", "b.jpg"],
      archiveName: "Zoo.zip",
      saver: saver,
    );

    expect(result.cancelled, isTrue);
    expect(server.requests, isEmpty);
  });

  test('a transfer that breaks off leaves no partial file', () async {
    var server = StreamingServer();
    var target = File("${directory.path}/a.jpg");
    var saver =
        FileDialogDownloadSaver(chooseLocation: (_) async => target.path);
    var progress = DownloadProgress();

    var saving = downloadOne(server.client(), "$dataUrl/Zoo/a.jpg",
        saver: saver, progress: progress);
    await until(() => server.requests.isNotEmpty);
    server.body.add(chunk(1));
    await until(() => progress.received > 0);
    expect(target.existsSync(), isTrue);
    server.body.addError(const SocketException("Connection reset by peer"));

    await expectLater(saving, throwsA(isA<SocketException>()));
    expect(target.existsSync(), isFalse);
  });

  test('a cancel stops the transfer and deletes the file', () async {
    var server = StreamingServer();
    var target = File("${directory.path}/a.jpg");
    var saver =
        FileDialogDownloadSaver(chooseLocation: (_) async => target.path);
    var progress = DownloadProgress();

    var saving = downloadOne(server.client(), "$dataUrl/Zoo/a.jpg",
        saver: saver, progress: progress);
    await until(() => server.requests.isNotEmpty);
    server.body.add(chunk(1));
    await until(() => progress.received > 0);
    progress.cancel();
    server.body.add(chunk(2));

    await expectLater(saving, throwsA(isA<DownloadCancelled>()));
    expect(target.existsSync(), isFalse);
    expect(server.cancelled, isTrue,
        reason: "the response is no longer listened to");
  });

  test('a refusal says the server\'s sentence and writes nothing', () async {
    var server = StreamingServer()..status = 403;
    var target = File("${directory.path}/a.jpg");
    var saver =
        FileDialogDownloadSaver(chooseLocation: (_) async => target.path);

    await expectLater(
      downloadOne(server.client(), "$dataUrl/Zoo/a.jpg", saver: saver),
      throwsA(isA<VAlbumException>()
          .having((e) => e.message, "message", "You may not download this.")),
    );
    expect(target.existsSync(), isFalse);
  });

  test('a phone\'s temporary file is written the same way', () async {
    var body = StreamController<List<int>>();
    var target = File("${directory.path}/b.mp4");
    var progress = DownloadProgress()..startFile(1, 3);

    var writing = streamIntoFile(
      DownloadStream(
          content: body.stream, contentType: "video/mp4", length: 3 * 65536),
      target,
      progress: progress,
    );
    for (var index = 0; index < 3; index++) {
      body.add(chunk(index));
      await until(() => progress.received == (index + 1) * 65536);
      expect(target.lengthSync(), (index + 1) * 65536);
    }
    await body.close();
    await writing;

    expect(progress.file, 1);
    expect(progress.files, 3);
    expect(target.lengthSync(), 3 * 65536);
  });
}
