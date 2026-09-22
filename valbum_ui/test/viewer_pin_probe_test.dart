/// Probe of issue #148 composed with #95 and #116: a video neighbour is not
/// pinned and paging past it still works, and a caller without `download`
/// gets the rendition pinned so paging is instant for them too.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/viewer_harness.dart';
import 'viewer_pin_test.dart' show sharpPictureShown, withTinyImageCache;

late List<http.Request> requests;
late List<String> originals;

bool live(ImageProvider provider) =>
    PaintingBinding.instance.imageCache.statusForKey(provider).live;

void main() {
  setUp(() {
    withEmptyImageCache();
    withTinyImageCache();
    requests = [];
    originals = [];
  });

  testWidgets('a video is never pinned and its photograph neighbour is',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("v.mp4", kind: ImageKind.video),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);
    var client = recordingViewerClient(requests);
    // Shown: the video. Its neighbours a and c are photographs and pinned;
    // the video itself has no picture to pin.
    await pumpViewerHarness(tester, images[1],
        client: client, caller: viewerMember("bob"));
    expect(find.byType(VideoView), findsOneWidget);
    expect(originals.where((url) => url.endsWith("v.mp4")), isEmpty,
        reason: "a video has no original picture to prefetch");
    expect(
        live(viewerPicture(client, "$viewerBaseUrl/a.jpg", mayDownload: true)),
        isTrue);
    expect(
        live(viewerPicture(client, "$viewerBaseUrl/c.jpg", mayDownload: true)),
        isTrue);

    // Paging onto the pinned neighbour paints it sharp in the first frame,
    // although the tiny cache could never have kept both neighbours.
    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pump();
    });
    expect(shownPictureUrl(tester), "$viewerBaseUrl/c.jpg");
    expect(sharpPictureShown(tester), isTrue);
    expect(originals.where((url) => url.endsWith("c.jpg")).length, 1);

    // From c, the video is the only neighbour: a's pin is released.
    await withFakeImageHttp(() async {
      await tester.pumpAndSettle();
    });
    expect(
        live(viewerPicture(client, "$viewerBaseUrl/a.jpg", mayDownload: true)),
        isFalse);
  });

  testWidgets('a caller without download gets the rendition pinned',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
    ];
    viewerAlbum(images, rights: const ["view"]);
    fakeImageRequests(requested: originals);
    var client = recordingViewerClient(requests);
    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));

    expect(originals, isEmpty, reason: "no original is ever asked for");
    var rendition = viewerPicture(client, "$viewerBaseUrl/b.jpg", mayDownload: false);
    expect(live(rendition), isTrue, reason: "the neighbour's rendition is pinned");

    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pump();
    });
    expect(shownPictureUrl(tester), contains("b.jpg"));
    expect(sharpPictureShown(tester), isTrue);
  });
}
