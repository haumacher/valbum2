/// Probe of issue #149: paging on and on paints every picture sharp at once.
///
/// The prefetch of issue #101 and the pin of issue #148 promise that the
/// *next* picture is painted in the very first frame of the page it belongs
/// to, however far one pages. Until now no test could say so beyond the first
/// page: the picture prefetched while standing on the second page holds all of
/// its bytes but has no decoded frame, because a decode is answered over the
/// real event queue and a widget test runs under `FakeAsync` — so it read as
/// "never sharp" whatever the test settled. [pageViewer] lets that decode
/// happen and then turns the page with a single pump, which is what the
/// assertions here stand on.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/viewer_harness.dart';
import 'viewer_pin_test.dart' show sharpPictureShown, withTinyImageCache;

late List<http.Request> requests;
late List<String> originals;

/// How often the original of [name] was fetched.
int originalsOf(String name) =>
    originals.where((url) => url.endsWith(name)).length;

void main() {
  setUp(() {
    withEmptyImageCache();
    withTinyImageCache();
    requests = [];
    originals = [];
  });

  testWidgets('every page forward paints its picture in the first frame',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
      viewerImagePart("d.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    expect(sharpPictureShown(tester), isTrue, reason: "the picture opened on");

    for (var name in const ["b.jpg", "c.jpg", "d.jpg"]) {
      await pageViewer(tester, LogicalKeyboardKey.arrowRight);

      expect(shownPictureUrl(tester), "$viewerBaseUrl/$name");
      expect(sharpPictureShown(tester), isTrue,
          reason: "$name must paint in the first frame, "
              "not after a second download");
    }

    // Each original travelled exactly once, however often it was a neighbour.
    for (var name in const ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]) {
      expect(originalsOf(name), 1, reason: "$name: $originals");
    }
  });

  testWidgets('paging over a video paints the photograph behind it at once',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("v.mp4", kind: ImageKind.video),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));

    // The video has no picture of its own, and none is ever asked for.
    await pageViewer(tester, LogicalKeyboardKey.arrowRight);
    expect(find.byType(VideoView), findsOneWidget);
    expect(find.byKey(const Key("image-picture")), findsNothing);
    expect(originalsOf("v.mp4"), 0,
        reason: "a video has no original picture to prefetch: $originals");

    // The photograph behind it was prefetched while the video stood, and it
    // is painted in the first frame of its own page.
    await pageViewer(tester, LogicalKeyboardKey.arrowRight);
    expect(shownPictureUrl(tester), "$viewerBaseUrl/c.jpg");
    expect(sharpPictureShown(tester), isTrue,
        reason: "the picture behind the video must paint at once");
    expect(originalsOf("a.jpg"), 1, reason: originals.toString());
    expect(originalsOf("c.jpg"), 1, reason: originals.toString());
  });

  testWidgets('a caller without download pages on renditions just as fast',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view"]);
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));

    for (var name in const ["b.jpg", "c.jpg"]) {
      await pageViewer(tester, LogicalKeyboardKey.arrowRight);
      expect(shownPictureUrl(tester), contains(name));
      expect(sharpPictureShown(tester), isTrue, reason: name);
    }
    expect(originals, isEmpty, reason: "no original is ever asked for");
    // The rendition of each picture travelled once, see issue #120.
    for (var name in const ["a.jpg", "b.jpg", "c.jpg"]) {
      var paths = thumbnailPathsOf(requests);
      expect(paths.where((path) => path.endsWith("/$name")).length, 1,
          reason: "$name: $paths");
    }
  });
}
