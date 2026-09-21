/// Tests of the wakelock an explicit upload holds (issue #63).
///
/// A phone that locks its screen in the middle of an upload backgrounds the
/// app, and Doze closes the socket under the transfer. The upload therefore
/// keeps the device awake for as long as it runs — and lets go of it again on
/// every way out, because an app that leaves the screen on after a failed
/// upload is a bug of its own.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/wakelock.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// An upload of [size] bytes under the given name.
UploadFile sizedFile(String name, int size) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.value(List<int>.filled(size, 3)),
    );

/// The body a server answers an upload of the given names with.
String storedAnswer(List<String> names) =>
    '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
        '"hash":"h-$n","status":"stored"}').join(",")}]}';

/// A client serving the fixture album, answering a PUT through [onPut].
VAlbumClient albumClient({
  required http.Response Function() onPut,
  List<String>? puts,
}) =>
    clientHandling((request) {
      if (request.method == "PUT") {
        puts?.add(request.url.toString());
        return onPut();
      }
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      return http.Response(fixture("album.json"), 200);
    });

void main() {
  group('an upload keeps the device awake', () {
    testWidgets('from before the first request until the dialog is gone',
        (WidgetTester tester) async {
      var wakelock = RecordingWakelock();
      var awakeAtPut = <bool>[];
      var client = albumClient(
        onPut: () {
          awakeAtPut.add(wakelock.awake);
          return http.Response(storedAnswer(const ["a.jpg"]), 200);
        },
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, wakelock: wakelock),
        );
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([sizedFile("a.jpg", 2048)]);
        await tester.pumpAndSettle();
      });

      expect(awakeAtPut, [true], reason: "awake before the body went out");
      expect(wakelock.requests, [true, false]);
      expect(wakelock.awake, isFalse, reason: "the screen may lock again");
      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets('and lets go of it when the upload fails',
        (WidgetTester tester) async {
      var wakelock = RecordingWakelock();
      var client = albumClient(
        onPut: () => http.Response('{"message":"Pair this device."}', 401),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, wakelock: wakelock),
        );
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([sizedFile("a.jpg", 2048)]);
        await tester.pumpAndSettle();
      });

      expect(wakelock.requests, [true, false]);
      expect(find.textContaining("Upload failed"), findsOneWidget);
    });

    testWidgets('and holds it for as long as the transfer runs',
        (WidgetTester tester) async {
      var wakelock = RecordingWakelock();
      var answer = Completer<http.Response>();
      // A transport whose PUT answers only when the test says so: the upload
      // is still running while the wakelock is asserted.
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockPut(answer),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, wakelock: wakelock),
        );
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        var upload = state.uploadPicked([sizedFile("a.jpg", 2048)]);
        await tester.pump();
        await tester.pump();

        expect(wakelock.awake, isTrue, reason: "still transferring");
        expect(wakelock.requests, [true]);

        answer.complete(http.Response(storedAnswer(const ["a.jpg"]), 200));
        await tester.pump();
        await upload;
        await tester.pumpAndSettle();
      });

      expect(wakelock.requests, [true, false]);
    });
  });
}

/// A transport answering a PUT only when the given future completes.
class MockPut extends http.BaseClient {
  final Completer<http.Response> answer;

  MockPut(this.answer);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    await request.finalize().drain<void>();
    http.Response response;
    if (request.method == "PUT") {
      response = await answer.future;
    } else if (request.method == "POST") {
      response = http.Response('{"present":[]}', 200);
    } else {
      response = http.Response(fixture("album.json"), 200);
    }
    return http.StreamedResponse(
      http.ByteStream.fromBytes(response.bodyBytes),
      response.statusCode,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );
  }
}
