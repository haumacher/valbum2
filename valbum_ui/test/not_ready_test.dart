/// Tests of issue #256 in the app: a picture the server has not made yet is
/// answered `202`; its tile shows the neutral placeholder — no error, no
/// log entry — and asks nothing again by itself until the user reloads.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/not_ready.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache;

const String dataUrl = "http://server/valbum/data";

const String albumJson =
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", '
    '"rights": [{"name": "download"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]]}]';

/// The server: the album at the root, its one thumbnail answered `202` while
/// [ready] is false.
class Server {
  final List<http.Request> requests = [];
  bool ready = false;

  VAlbumClient client({DiagnosticsLog? log}) => VAlbumClient(
        dataUrl: dataUrl,
        log: log,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return ready
                ? http.Response.bytes(transparentPixelPng, 200,
                    headers: {"content-type": "image/png"})
                : http.Response(
                    '["ErrorInfo", {"message": "This picture is being '
                    'prepared; please reload shortly."}]',
                    202,
                    headers: {
                        "content-type": "application/json; charset=utf-8",
                        "retry-after": "10",
                      });
          }
          if (Uri.decodeFull(request.url.path) == "/valbum/data/") {
            return http.Response(albumJson, 200,
                headers: {"content-type": "application/json; charset=utf-8"});
          }
          return http.Response("No such resource", 404);
        }),
      );

  int get thumbnails => requests.where(isThumbnailRequest).length;
}

void main() {
  setUp(() {
    withEmptyImageCache();
    forgetThumbnailBytes();
  });

  testWidgets(
      "a picture not made yet is a placeholder, asked again only by a reload",
      (tester) async {
    var server = Server();
    var log = DiagnosticsLog();
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: server.client(log: log)));
      await tester.pumpAndSettle();
    });

    var placeholder = find.byKey(const Key("thumbnail-not-ready"));
    expect(placeholder, findsOneWidget);
    expect(tester.takeException(), isNull, reason: "no failure");
    expect(find.byIcon(Icons.broken_image), findsNothing);
    expect(find.byType(ErrorWidget), findsNothing);
    expect(log.entries, isEmpty, reason: "nothing written down");
    expect(server.thumbnails, 1);
    expect(hasNotReadyImages, isTrue);

    // Time passes, the album is built again: nothing is asked again.
    await withFakeImageHttp(() async {
      await tester.pump(const Duration(minutes: 5));
      await tester.pumpAndSettle();
      tester.binding.handleMetricsChanged();
      await tester.pumpAndSettle();
    });
    expect(server.thumbnails, 1, reason: "no retry, no back-off");
    expect(placeholder, findsOneWidget);

    // The background made it; the user reloads.
    server.ready = true;
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.reload).last);
      await tester.pumpAndSettle();
    });
    expect(server.thumbnails, 2, reason: "asked again by the reload");
    expect(placeholder, findsNothing);
    expect(find.byType(Image), findsOneWidget);
    expect(tester.takeException(), isNull);
    expect(hasNotReadyImages, isFalse);
  });

  test("a 202 of a picture is no failure of the client", () async {
    var server = Server();
    var log = DiagnosticsLog();
    var client = server.client(log: log);
    await expectLater(
        client.thumbnailBytes("$dataUrl/a.jpg"), throwsA(isA<ImageNotReady>()));
    expect(isImageNotReady(const ImageNotReady("$dataUrl/a.jpg?type=tn")), isTrue);
    expect(
        isImageNotReady(NetworkImageLoadException(
            statusCode: 202, uri: Uri.parse("$dataUrl/a.heic?type=display"))),
        isTrue);
    expect(
        isImageNotReady(NetworkImageLoadException(
            statusCode: 500, uri: Uri.parse("$dataUrl/a.heic?type=display"))),
        isFalse);
    expect(log.entries, isEmpty);
  });
}
