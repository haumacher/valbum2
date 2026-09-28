/// The one reading of an upload's answer (issue #170), shared by the streamed
/// upload of every platform and the `FormData` upload of a browser: the
/// cancellation, the server's refusal in its own words, the answer per file,
/// and the empty answer of a server built before issue #29.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/locales.dart';

UploadFile fileNamed(String name, {String? sha256}) => UploadFile(
      name: name,
      length: 3,
      openRead: () => Stream.value([1, 2, 3]),
      sha256: sha256,
    );

void main() {
  const url = "http://localhost:9090/valbum/data/A/";

  test('reads the answer per file', () {
    var result = VAlbumClient.uploadAnswer(
      200,
      '{"files":[{"name":"a.jpg","storedAs":"a-2.jpg","hash":"h","status":"stored"},'
          '{"name":"b.jpg","storedAs":"b.jpg","hash":"g","status":"present"}]}',
      url,
      [fileNamed("a.jpg"), fileNamed("b.jpg")],
    );

    expect([for (var f in result.files) f.storedAs], ["a-2.jpg", "b.jpg"]);
    expect([for (var f in result.files) f.status], [uploadStored, uploadPresent]);
  });

  test('takes an empty answer for everything stored (issue #29)', () {
    var result = VAlbumClient.uploadAnswer(
      200,
      "  ",
      url,
      [fileNamed("a.jpg", sha256: "abc")],
    );

    expect(result.files.single.name, "a.jpg");
    expect(result.files.single.storedAs, "a.jpg");
    expect(result.files.single.hash, "abc");
    expect(result.files.single.status, uploadStored);
  });

  test('says the server\'s reason for a refusal, with its status', () {
    expect(
      () => VAlbumClient.uploadAnswer(
        403,
        '["ErrorInfo",{"message":"Not yours to add to."}]',
        url,
        [fileNamed("a.jpg")],
      ),
      throwsA(isA<VAlbumException>()
          .having((e) => e.message, "message", "Not yours to add to.")
          .having((e) => e.status, "status", 403)),
    );
  });

  test('names the status where the refusal gives no reason', () {
    expect(
      () => VAlbumClient.uploadAnswer(500, "", url, [fileNamed("a.jpg")]),
      throwsA(isA<VAlbumException>()
          .having((e) => e.status, "status", 500)
          .having((e) => e.message, "message", contains("500"))),
    );
  });

  test('refuses a cancelled upload even with a success in hand', () {
    expect(
      () => VAlbumClient.uploadAnswer(
        200,
        "",
        url,
        [fileNamed("a.jpg")],
        handle: UploadHandle()..cancel(),
      ),
      throwsA(isA<VAlbumException>().having(
          (e) => e.message, "message", uploadCancelledMessage(platformMessages))),
    );
  });

  test('carries no Blob off the web, and keeps it through withHash', () {
    var plain = fileNamed("a.jpg");
    expect(plain.blob, isNull);
    expect(plain.withHash("h").blob, isNull);

    var marker = Object();
    var carried = UploadFile(
      name: "b.jpg",
      length: 0,
      openRead: () => const Stream.empty(),
      blob: marker,
    ).withHash("h");
    expect(carried.blob, same(marker));
    expect(carried.sha256, "h");
  });
}
