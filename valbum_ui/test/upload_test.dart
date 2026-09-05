/// Tests of idempotent uploads (issue #29): the content hash the client
/// computes, the question it asks the server before transferring anything, the
/// answer the server gives for every file, and what the user is told.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The SHA-256 of the ASCII bytes "abc", the digest of the standard test
/// vector.
const String abcDigest =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

/// An upload of the given contents under the given name.
UploadFile fileNamed(String name, List<int> contents, {String? sha256}) =>
    UploadFile(
      name: name,
      length: contents.length,
      openRead: () => Stream.value(contents),
      sha256: sha256,
    );

/// The body a server answers an upload with.
String uploadAnswer(List<List<String>> files) =>
    '{"files":[${files.map((f) => '{"name":"${f[0]}","storedAs":"${f[1]}",'
        '"hash":"${f[2]}","status":"${f[3]}"}').join(",")}]}';

/// The body a server answers an upload check with.
String checkAnswer(Map<String, String> nameByHash) =>
    '{"present":[${nameByHash.entries.map((e) => '{"hash":"${e.key}","name":"${e.value}"}').join(",")}]}';

void main() {
  group('sha256Of', () {
    test('hashes a known byte sequence', () async {
      expect(await sha256Of(Stream.value("abc".codeUnits)), abcDigest);
    });

    test('hashes a chunked stream like an unchunked one', () async {
      var chunked = Stream.fromIterable([
        "a".codeUnits,
        "b".codeUnits,
        "c".codeUnits,
      ]);

      expect(await sha256Of(chunked), abcDigest);
    });

    test('hashes empty contents', () async {
      expect(
        await sha256Of(const Stream<List<int>>.empty()),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      );
    });
  });

  group('uploadFiles', () {
    test('parses the answer of the server', () async {
      var client = clientReturning(
        uploadAnswer([
          ["a.jpg", "a.jpg", "h1", "stored"],
          ["b.jpg", "b-2.jpg", "h2", "stored"],
          ["c.jpg", "old.jpg", "h3", "present"],
        ]),
      );

      var result = await client.uploadFiles(
        "http://server/valbum/data/album/",
        [
          fileNamed("a.jpg", [1]),
          fileNamed("b.jpg", [2]),
          fileNamed("c.jpg", [3]),
        ],
      );

      expect(result.files.map((f) => f.storedAs), [
        "a.jpg",
        "b-2.jpg",
        "old.jpg",
      ]);
      expect(result.files.map((f) => f.status), [
        uploadStored,
        uploadStored,
        uploadPresent,
      ]);
    });

    test('takes an empty body for "everything stored"', () async {
      var result = await clientReturning("").uploadFiles(
        "http://server/valbum/data/album/",
        [
          fileNamed("a.jpg", [1], sha256: "h1"),
          fileNamed("b.jpg", [2]),
        ],
      );

      expect(result.files.map((f) => f.name), ["a.jpg", "b.jpg"]);
      expect(result.files.map((f) => f.storedAs), ["a.jpg", "b.jpg"]);
      expect(result.files.map((f) => f.status), [uploadStored, uploadStored]);
      expect(result.files.first.hash, "h1");
    });

    test('takes a body it cannot read for "everything stored"', () async {
      var result = await clientReturning("<html>gateway</html>").uploadFiles(
        "http://server/valbum/data/album/",
        [
          fileNamed("a.jpg", [1]),
        ],
      );

      expect(result.files.single.status, uploadStored);
    });
  });

  group('checkUploads', () {
    test('sends the asked hashes and parses the answer', () async {
      var requests = <http.Request>[];
      var client = clientReturning(
        checkAnswer({"h1": "a.jpg"}),
        requests: requests,
      );

      var result = await client.checkUploads(["album"], ["h1", "h2"]);

      expect(requests.single.method, "POST");
      expect(
        requests.single.url.toString(),
        "http://server/valbum/data/album/?action=check",
      );
      expect(requests.single.body, '{"hashes":[{"hash":"h1"},{"hash":"h2"}]}');
      expect(result.present.map((p) => p.hash), ["h1"]);
      expect(result.present.single.name, "a.jpg");
    });

    test('surfaces the reason of a refusal', () {
      expect(
        () => clientReturning(
          '["ErrorInfo",{"message":"Pair this device."}]',
          status: 401,
        ).checkUploads(const [], const ["h1"]),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', "Pair this device."),
        ),
      );
    });
  });

  group('uploadNew', () {
    test('transfers only what the album does not hold yet', () async {
      var requests = <http.Request>[];
      var hashOfA = await sha256Of(Stream.value("a".codeUnits));
      var hashOfB = await sha256Of(Stream.value("b".codeUnits));
      var client = clientHandling(
        (request) => request.url.query.contains("check")
            ? http.Response(checkAnswer({hashOfA: "a.jpg"}), 200)
            : http.Response(
                uploadAnswer([
                  ["b.jpg", "b.jpg", hashOfB, "stored"],
                ]),
                200,
              ),
        requests: requests,
      );

      var summary = await client.uploadNew(["album"], [
        fileNamed("a.jpg", "a".codeUnits),
        fileNamed("b.jpg", "b".codeUnits),
      ]);

      expect(summary.stored, 1);
      expect(summary.present, 1);
      expect(summary.total, 2);

      // The hashes are computed by the client, and only the unknown file is
      // sent.
      expect(requests.first.body, contains(hashOfA));
      expect(requests.first.body, contains(hashOfB));
      expect(requests.last.body, contains('filename="b.jpg"'));
      expect(requests.last.body, isNot(contains('filename="a.jpg"')));
    });

    test('transfers nothing at all when the album holds everything', () async {
      var requests = <http.Request>[];
      var hashOfA = await sha256Of(Stream.value("a".codeUnits));
      var client = clientReturning(
        checkAnswer({hashOfA: "a.jpg"}),
        requests: requests,
      );

      var summary = await client.uploadNew(const [], [
        fileNamed("a.jpg", "a".codeUnits),
      ]);

      expect(summary.stored, 0);
      expect(summary.present, 1);
      expect(requests, hasLength(1), reason: "Only the question was asked.");
    });

    test('counts a duplicate the server discovers itself', () async {
      var client = clientHandling(
        (request) => request.url.query.contains("check")
            ? http.Response(checkAnswer({}), 200)
            : http.Response(
                uploadAnswer([
                  ["a.jpg", "a.jpg", "h1", "stored"],
                  ["copy.jpg", "a.jpg", "h1", "present"],
                ]),
                200,
              ),
      );

      var summary = await client.uploadNew(const [], [
        fileNamed("a.jpg", "a".codeUnits),
        fileNamed("copy.jpg", "a".codeUnits),
      ]);

      expect(summary.stored, 1);
      expect(summary.present, 1);
    });

    test('uploads everything when the server cannot answer the question',
        () async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.url.query.contains("check")
            ? http.Response("Method not allowed", 405)
            : http.Response("", 200),
        requests: requests,
      );

      var summary = await client.uploadNew(const [], [
        fileNamed("a.jpg", "a".codeUnits),
      ]);

      expect(summary.stored, 1, reason: "The upload itself is idempotent.");
      expect(summary.present, 0);
      expect(requests.last.body, contains('filename="a.jpg"'));
    });

    test('reuses a hash that was computed before', () async {
      var requests = <http.Request>[];
      var client = clientReturning(checkAnswer({}), requests: requests);

      await client.uploadNew(const [], [
        fileNamed("a.jpg", "a".codeUnits, sha256: "already-known"),
      ]);

      expect(requests.first.body, contains("already-known"));
    });

    test('says both counts', () {
      expect(
        const UploadSummary(stored: 3, present: 2).message,
        "3 hochgeladen, 2 bereits vorhanden.",
      );
      expect(
        const UploadSummary(stored: 0, present: 0).message,
        "0 hochgeladen, 0 bereits vorhanden.",
      );
    });
  });

  group('the upload flow of the app', () {
    testWidgets('skips what is present and names both counts',
        (WidgetTester tester) async {
      var hashOfA = await sha256Of(Stream.value("a".codeUnits));
      var hashOfB = await sha256Of(Stream.value("b".codeUnits));
      var uploaded = <String>[];
      var client = clientHandling((request) {
        if (request.method == "POST") {
          return http.Response(checkAnswer({hashOfA: "IMG_0417.JPG"}), 200);
        }
        if (request.method == "PUT") {
          uploaded.add(request.body);
          return http.Response(
            uploadAnswer([
              ["b.jpg", "b.jpg", hashOfB, "stored"],
            ]),
            200,
          );
        }
        return http.Response(fixture("album.json"), 200);
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([
          fileNamed("a.jpg", "a".codeUnits),
          fileNamed("b.jpg", "b".codeUnits),
        ]);
        await tester.pumpAndSettle();
      });

      expect(uploaded, hasLength(1));
      expect(uploaded.single, contains('filename="b.jpg"'));
      expect(uploaded.single, isNot(contains('filename="a.jpg"')));
      expect(find.text("1 hochgeladen, 1 bereits vorhanden."), findsOneWidget);
    });

    testWidgets('shows the reason of a refused upload',
        (WidgetTester tester) async {
      var client = clientHandling((request) {
        if (request.method == "GET") {
          return http.Response(fixture("album.json"), 200);
        }
        return http.Response(
          '["ErrorInfo",{"message":"Pair this device."}]',
          401,
        );
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([fileNamed("a.jpg", "a".codeUnits)]);
        await tester.pumpAndSettle();
      });

      expect(find.textContaining("Pair this device."), findsOneWidget);
    });
  });
}
