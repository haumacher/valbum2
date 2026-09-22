/// What the viewer's prefetch is worth once the image cache is full (#148).
///
/// The prefetch of issue #101 merely *cached* the neighbours, and Flutter's
/// [ImageCache] keeps a decoded picture only while it fits into
/// `maximumSizeBytes` (100 MB). A decoded 24 MP original is 96 MB, so the
/// second prefetch evicted the first and paging showed the thumbnail underlay
/// while the original was downloaded and decoded a second time — exactly what
/// the prefetch exists to prevent.
///
/// The tests here shrink the budget to one picture, which reproduces that
/// eviction with 1x1 fixtures, and pin what was prefetched.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// The requests the app's own transport made.
late List<http.Request> requests;

/// Every original the viewer asked the network for.
late List<String> originals;

/// How often the original of [name] was fetched.
int originalsOf(String name) =>
    originals.where((url) => url.endsWith(name)).length;

/// The cache budget of a single 1x1 fixture picture (RGBA).
const int onePicture = 4;

/// Shrinks the image cache to [onePicture], the eviction of a real library.
void withTinyImageCache() {
  var cache = PaintingBinding.instance.imageCache;
  var before = cache.maximumSizeBytes;
  cache.maximumSizeBytes = onePicture;
  addTearDown(() => cache.maximumSizeBytes = before);
}

/// The provider the viewer uses for the image of [name], see `viewerPicture`.
ImageProvider pictureOf(VAlbumClient client, String name) =>
    viewerPicture(client, "$viewerBaseUrl/$name", mayDownload: true);

/// Whether the picture of [name] is held live in the image cache.
bool isPinned(VAlbumClient client, String name) =>
    PaintingBinding.instance.imageCache
        .statusForKey(pictureOf(client, name))
        .live;

/// Whether the sharp picture — not the thumbnail underlay — is painted.
///
/// The `frameBuilder` of the viewer hands its child out only once the picture
/// has a frame or was loaded synchronously; until then it builds an empty box
/// and what shows through is the thumbnail beneath (issue #101).
bool sharpPictureShown(WidgetTester tester) => tester.any(find.descendant(
      of: find.byKey(const Key("image-picture")),
      matching: find.byType(RawImage),
    ));

void main() {
  setUp(() {
    withEmptyImageCache();
    withTinyImageCache();
    requests = [];
    originals = [];
  });

  testWidgets('a neighbour evicted from the cache is still painted at once',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);

    // Shown: b. Prefetched: a, then c — and with a budget of one picture the
    // second prefetch evicts the first, as it does on a real library.
    await pumpViewerHarness(
      tester,
      images[1],
      client: recordingViewerClient(requests),
      caller: viewerMember("bob"),
    );
    expect(originalsOf("a.jpg"), 1);
    expect(originalsOf("b.jpg"), 1);
    expect(originalsOf("c.jpg"), 1);

    // Page back to the evicted neighbour and look at the very first frame.
    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pump();
    });

    expect(shownPictureUrl(tester), "$viewerBaseUrl/a.jpg");
    expect(sharpPictureShown(tester), isTrue,
        reason: "the prefetched picture must paint in the first frame, "
            "not after a second download");
    expect(originalsOf("a.jpg"), 1, reason: "fetched again: $originals");
  });

  testWidgets('paging releases the pin of what is no longer a neighbour',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
      viewerImagePart("d.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));
    expect(isPinned(client, "a.jpg"), isTrue, reason: "the shown picture");
    expect(isPinned(client, "b.jpg"), isTrue, reason: "the neighbour");

    // b, then c: a is a neighbour of b and nothing of c any more.
    await settleViewer(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
    expect(isPinned(client, "a.jpg"), isTrue, reason: "still a neighbour");
    await settleViewer(
        tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));

    expect(isPinned(client, "a.jpg"), isFalse, reason: "two pages away");
    expect(isPinned(client, "b.jpg"), isTrue);
    expect(isPinned(client, "c.jpg"), isTrue);
    expect(isPinned(client, "d.jpg"), isTrue);
  });

  testWidgets('leaving the viewer releases every pin', (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: originals);
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[1],
        client: client, caller: viewerMember("bob"));
    expect(isPinned(client, "a.jpg"), isTrue);
    expect(isPinned(client, "c.jpg"), isTrue);

    await settleViewer(tester, () async {
      tester.state<NavigatorState>(find.byType(Navigator)).pop();
    });

    expect(find.byType(ImageView), findsNothing);
    for (var name in const ["a.jpg", "b.jpg", "c.jpg"]) {
      expect(isPinned(client, name), isFalse, reason: "$name is still pinned");
    }
  });

  testWidgets('a neighbour that fails releases its pin and says nothing',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(
        requested: originals, failing: (url) => url.path.endsWith("b.jpg"));
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));

    expect(isPinned(client, "b.jpg"), isFalse, reason: "a failed prefetch");
    // Nobody asked for the neighbour, so nobody is told about it (issue #95).
    expect(find.text(testL10n.pictureFailedMessage), findsNothing);
  });
}
