/// A video picked in a browser goes the way of issue #170 (issue #197), run
/// in a real browser:
/// `flutter test --platform chrome test/browser_media_picker_test.dart`.
///
/// `image_picker_for_web`'s `getMedia` hands every picked `File` out as
/// `XFile(URL.createObjectURL(file), name: file.name, length: file.size)` and
/// resizes nothing that is not an image or where no size is asked for — so
/// the test makes exactly that `XFile` of a `File` and pins that
/// [uploadFilesOf] takes its `Blob` back, that the browser hashes it like
/// `sha256Of`, and that the upload sends the video from the `Blob` without
/// ever reading it through Dart.
@TestOn('browser')
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker/image_picker.dart' show XFile;
import 'package:valbum_ui/browser_upload.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/media_picker.dart';

import 'browser_upload_test.dart' show serverCode, partsOf;

/// A picked file as `image_picker_for_web` makes it: an object URL of a `File`.
XFile pickedFile(String name, Uint8List bytes, String type) {
  var options = JSObject()..["type"] = type.toJS;
  var file = (globalContext["File"] as JSFunction)
      .callAsConstructor<JSObject>([bytes.toJS].toJS, name.toJS, options);
  var url = (globalContext["URL"] as JSObject)
      .callMethod<JSString>("createObjectURL".toJS, file)
      .toDart;
  return XFile(url, name: name, length: bytes.length, mimeType: type);
}

Uint8List randomBytes(int length, int seed) {
  var random = Random(seed);
  return Uint8List.fromList(
      [for (var i = 0; i < length; i++) random.nextInt(256)]);
}

void main() {
  test('a picked video and photo carry their Blobs, hashed like sha256Of',
      () async {
    var video = randomBytes(400000, 197);
    var photo = randomBytes(5000, 198);

    var uploads = await uploadFilesOf([
      pickedFile("clip.mov", video, "video/quicktime"),
      pickedFile("a.jpg", photo, "image/jpeg"),
    ]);

    expect(uploads.map((u) => u.name), ["clip.mov", "a.jpg"]);
    expect(uploads.map((u) => u.length), [video.length, photo.length]);
    expect(uploads.every((u) => u.blob != null), isTrue);
    expect(await sha256OfBlob(uploads[0].blob!),
        await sha256Of(Stream.value(video)));
    expect(await sha256OfBlob(uploads[1].blob!),
        await sha256Of(Stream.value(photo)));
  });

  test('the picked video is sent from its Blob, never read through Dart',
      () async {
    var channel = spawnHybridCode(serverCode);
    var messages = StreamIterator(channel.stream);
    Future<dynamic> next() async {
      expect(await messages.moveNext(), isTrue);
      return messages.current;
    }

    var port = await next() as int;
    channel.sink.add({
      "POST": {"status": 200, "body": '{"present":[]}'},
      "PUT": {"status": 200, "body": ""},
    });
    expect(await next(), "configured");

    var video = randomBytes(300000, 199);
    var picked =
        await uploadFilesOf([pickedFile("clip.mov", video, "video/quicktime")]);
    // Reading through Dart would fail the upload: the Blob must carry it.
    var uploads = [
      for (var file in picked)
        UploadFile(
          name: file.name,
          length: file.length,
          openRead: () => throw StateError("read through Dart"),
          blob: file.blob,
        ),
    ];

    var client = VAlbumClient(dataUrl: "http://127.0.0.1:$port/valbum/data");
    var summary = await client.uploadNew(["Album"], uploads);

    var check = Map<String, dynamic>.from(await next() as Map);
    expect(check["query"], "action=check");
    var put = Map<String, dynamic>.from(await next() as Map);
    expect(put["method"], "PUT");
    var parts = partsOf(
        put["contentType"] as String, base64.decode(put["body"] as String));
    expect(parts, hasLength(1));
    expect(parts.single.disposition, contains('filename="clip.mov"'));
    expect(parts.single.bytes, video);
    expect(summary.stored, 1);
    await messages.cancel();
  });
}
