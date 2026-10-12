/// Probe for issue #256: in the viewer, a photograph whose display copy the
/// server has not made yet (a HEIC) is not a failure, and a reload asks for
/// it again.
///
/// The viewer has no reload of its own: the reload is the album's, reached
/// by going back, and the picture is asked again when the viewer is opened
/// once more.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache, letImagesDecode;

const String dataUrl = "http://server/valbum/data";

const String albumJson =
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", '
    '"rights": [{"name": "download"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.heic", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]]}]';

void main() {
  setUp(() {
    withEmptyImageCache();
    forgetThumbnailBytes();
  });

  testWidgets('a display copy not made yet, in the viewer, then a reload',
      (tester) async {
    var requests = <http.Request>[];
    var ready = false;
    var log = DiagnosticsLog();
    // The display copy is fetched by the platform (`Image.network`), the
    // thumbnail through the client: both are not made yet at first.
    var displays = <String>[];
    fakeImageRequests(
      requested: displays,
      notReady: (url) => !ready && url.queryParameters["type"] == "display",
    );
    int displayRequests() =>
        displays.where((url) => url.contains("type=display")).length;
    var client = VAlbumClient(
      dataUrl: dataUrl,
      log: log,
      httpClient: MockClient((request) async {
        requests.add(request);
        var type = request.url.queryParameters["type"];
        if (type == "display" || type == "tn") {
          return ready
              ? http.Response.bytes(transparentPixelPng, 200,
                  headers: {"content-type": "image/png"})
              : http.Response(
                  '["ErrorInfo", {"message": "being prepared"}]', 202,
                  headers: {
                      "content-type": "application/json; charset=utf-8",
                    });
        }
        if (Uri.decodeFull(request.url.path) == "/valbum/data/") {
          return http.Response(albumJson, 200,
              headers: {"content-type": "application/json; charset=utf-8"});
        }
        return http.Response("No such resource", 404);
      }),
    );

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("thumbnail-not-ready")));
      await tester.pumpAndSettle();
    });
    await letImagesDecode(tester);
    expect(find.byType(ImageView), findsOneWidget);
    expect(tester.takeException(), isNull);
    expect(find.byType(SnackBar), findsNothing, reason: "no failure said");
    expect(log.entries, isEmpty);
    var asked = displayRequests();
    expect(asked, greaterThanOrEqualTo(1),
        reason: "the viewer asks for the display copy of a HEIC");
    expect(find.byKey(const Key("thumbnail-not-ready")), findsOneWidget,
        reason: "the placeholder stands in beneath");
    expect(shownPicture(tester), isNull, reason: "nothing sharp yet");

    // Waiting asks nothing again.
    await withFakeImageHttp(() async {
      await tester.pump(const Duration(minutes: 5));
      await tester.pumpAndSettle();
    });
    expect(displayRequests(), asked, reason: "no retry");

    // The background made it; the user goes back, reloads the album and
    // opens the photograph again.
    ready = true;
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.reload).last);
      await tester.pumpAndSettle();
    });
    await letImagesDecode(tester);
    expect(find.byKey(const Key("thumbnail-not-ready")), findsNothing,
        reason: "the reload asked for the thumbnail again");
    await withFakeImageHttp(() async {
      await tester.tap(find.byType(Image).first);
      await tester.pumpAndSettle();
    });
    await letImagesDecode(tester);
    expect(displayRequests(), greaterThan(asked),
        reason: "a reload asks again for what was not there");
    expect(shownPicture(tester), isNotNull, reason: "the display copy shows");
    expect(tester.takeException(), isNull);
    expect(find.byType(SnackBar), findsNothing);
    expect(log.entries, isEmpty);
  });
}

/// The picture the viewer draws sharp, `null` while it has none.
Object? shownPicture(WidgetTester tester) {
  var raw = find.descendant(
    of: find.byKey(const Key("image-picture")),
    matching: find.byType(RawImage),
  );
  if (raw.evaluate().isEmpty) {
    return null;
  }
  return tester.widget<RawImage>(raw.first).image;
}
