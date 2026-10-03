/// Tests of HEIC/HEIF photographs and of the upload refusals that speak
/// (issue #186).
library;

import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/thumbnails.dart';

import 'upload_test.dart' show fileNamed;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String base = "http://server/valbum/data/album";

void main() {
  group('the viewer', () {
    test('shows the display rendition of a HEIC, never the original', () {
      var client = VAlbumClient(dataUrl: "http://server/valbum/data");
      for (var name in ["IMG_1.heic", "IMG_2.HEIC", "IMG_3.heif"]) {
        var picture =
            viewerPicture(client, "$base/$name", mayDownload: true);
        expect(picture, isA<NetworkImage>());
        expect((picture as NetworkImage).url, "$base/$name?type=display");
      }
      // A JPEG is still its original.
      var jpeg = viewerPicture(client, "$base/a.jpg", mayDownload: true);
      expect((jpeg as NetworkImage).url, "$base/a.jpg");
      // Without download, a HEIC is shown by its preview, as a JPEG is.
      expect(viewerPicture(client, "$base/IMG_1.heic", mayDownload: false),
          isA<ThumbnailImage>());
    });

    test('knows a HEIC by its extension in any case', () {
      expect(needsDisplayRendition("a.heic"), isTrue);
      expect(needsDisplayRendition("a.HeIf"), isTrue);
      expect(needsDisplayRendition("a.jpg"), isFalse);
      expect(needsDisplayRendition("heic"), isFalse);
    });

    test('shows an AVIF and a JPEG XL by the display rendition (#193)', () {
      var client = VAlbumClient(dataUrl: "http://server/valbum/data");
      for (var name in ["x.avif", "X.AVIF", "y.jxl", "Y.JXL"]) {
        expect(needsDisplayRendition(name), isTrue);
        var picture = viewerPicture(client, "$base/$name", mayDownload: true);
        expect((picture as NetworkImage).url, "$base/$name?type=display");
        expect(viewerPicture(client, "$base/$name", mayDownload: false),
            isA<ThumbnailImage>());
      }
    });
  });

  group('an upload', () {
    test('names every file the server did not take, in its words', () {
      var summary = UploadSummary(
        stored: 1,
        present: 0,
        refused: [
          RefusedFile(
            name: "x.bmp",
            reason: "'x.bmp' was not uploaded: its format is not supported.",
          ),
        ],
      );
      expect(
        summary.messageOf(testL10n),
        "1 uploaded, 0 already present. One file was not uploaded: "
        "'x.bmp' was not uploaded: its format is not supported.",
      );
      expect(summary.total, 2);
    });

    testWidgets('of a batch partly refused says what arrived and what did not',
        (WidgetTester tester) async {
      var client = clientHandling((request) {
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        if (request.method == "PUT") {
          return http.Response(
            '{"files":[{"name":"a.jpg","storedAs":"a.jpg","hash":"h",'
            '"status":"stored"}],"refused":[{"name":"x.bmp","reason":'
            '"\'x.bmp\' was not uploaded: its format is not supported."}]}',
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
          fileNamed("x.bmp", "x".codeUnits),
        ]);
        await tester.pumpAndSettle();
      });

      expect(
        find.textContaining("'x.bmp' was not uploaded: its format is not "
            "supported."),
        findsOneWidget,
      );
      expect(find.textContaining("1 uploaded"), findsOneWidget);
      expect(find.textContaining("Upload failed"), findsNothing);
    });

    testWidgets('refused whole shows the reason that names the file',
        (WidgetTester tester) async {
      var client = clientHandling((request) {
        if (request.method == "GET") {
          return http.Response(fixture("album.json"), 200);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        return http.Response(
          '["ErrorInfo",{"message":"\'x.bmp\' was not uploaded: its '
          'format is not supported."}]',
          415,
        );
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([fileNamed("x.bmp", "x".codeUnits)]);
        await tester.pumpAndSettle();
      });

      expect(
        find.textContaining("'x.bmp' was not uploaded: its format is not "
            "supported."),
        findsOneWidget,
      );
    });

    testWidgets('refused whole by an older server names the suspect files',
        (WidgetTester tester) async {
      var client = clientHandling((request) {
        if (request.method == "GET") {
          return http.Response(fixture("album.json"), 200);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        return http.Response("", 415);
      });

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([
          fileNamed("a.jpg", "a".codeUnits),
          fileNamed("b.heic", "b".codeUnits),
        ]);
        await tester.pumpAndSettle();
      });

      expect(
        find.textContaining("The server does not take one of these files: "
            "b.heic. Nothing of this batch was stored."),
        findsOneWidget,
      );
    });
  });
}
