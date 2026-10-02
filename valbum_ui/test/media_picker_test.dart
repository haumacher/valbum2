/// The system picker of an upload offers videos too (issue #197).
library;

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart' show XFile;
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/media_picker.dart';

import 'upload_test.dart' show uploadAnswer;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

void main() {
  late MediaPicker original;
  setUp(() => original = mediaPicker);
  tearDown(() => mediaPicker = original);

  testWidgets('a picked video is uploaded beside a photo, names and lengths',
      (WidgetTester tester) async {
    var video = Uint8List.fromList(List.generate(3000, (i) => i % 251));
    var photo = Uint8List.fromList(List.generate(700, (i) => i % 13));
    mediaPicker = (_) async => [
          XFile.fromData(video, name: "clip.mov", path: "clip.mov", length: video.length),
          XFile.fromData(photo, name: "a.jpg", path: "a.jpg", length: photo.length),
        ];
    var sent = <UploadFile>[];
    var bodies = <List<int>>[];
    var client = clientHandling((request) {
      if (request.method == "POST") {
        return http.Response('{"present":[]}', 200);
      }
      if (request.method == "PUT") {
        bodies.add(request.bodyBytes);
        return http.Response(
          uploadAnswer([
            ["clip.mov", "clip.mov", "h1", "stored"],
            ["a.jpg", "a.jpg", "h2", "stored"],
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
      // What `uploadFromFiles` hands on, measured where it is made.
      sent.addAll(await uploadFilesOf(await mediaPicker(testL10n)));
      await state.uploadFromFiles();
      await tester.pumpAndSettle();
    });

    expect(sent.map((f) => f.name), ["clip.mov", "a.jpg"]);
    expect(sent.map((f) => f.length), [3000, 700]);
    // Off the web there is no Blob; the contents are read through openRead.
    expect(sent.every((f) => f.blob == null), isTrue);

    expect(bodies, hasLength(1));
    var body = String.fromCharCodes(bodies.single);
    expect(body, contains('filename="clip.mov"'));
    expect(body, contains('filename="a.jpg"'));
    expect(bodies.single.length, greaterThan(video.length + photo.length));
    expect(find.text("2 uploaded, 0 already present."), findsOneWidget);
  });

  test('the desktop dialog filters by the server types first, then nothing',
      () {
    var groups = desktopTypeGroups(testL10n);
    expect(groups, hasLength(2));
    expect(groups.first.label, "Photos and videos");
    expect(groups.first.extensions, containsAll(uploadExtensions));
    for (var video in ["mp4", "mov", "m4v", "3gp"]) {
      expect(uploadExtensions, contains(video));
    }
    expect(groups.last.label, "All files");
    expect(groups.last.allowsAny, isTrue);
  });
}

