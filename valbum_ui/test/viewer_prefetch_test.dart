/// What the viewer fetches before anybody asks for it (issue #101).
///
/// Paging used to leave the screen empty for as long as the next original took
/// to arrive — seconds on a large photo, and the flicker made comparing the
/// shots of a group impossible. The viewer now fetches the pictures of its two
/// neighbours as soon as it shows an image, with the *same* provider it would
/// display them with, and it keeps the thumbnail underneath until the picture
/// has decoded.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/resource.dart';

import 'util/viewer_harness.dart';

/// The requests the app's own transport made — the thumbnails among them.
late List<http.Request> requests;

/// How often the given name was asked for as a thumbnail.
int thumbnailsOf(String name) =>
    thumbnailPathsOf(requests).where((path) => path.endsWith(name)).length;

/// How often the given name was asked for as an original.
int originalsOf(List<String> originals, String name) =>
    originals.where((url) => url.endsWith(name)).length;

/// Pages to the next image with the arrow key.
Future<void> pageOn(WidgetTester tester) => settleViewer(
      tester,
      () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight),
    );

void main() {
  setUp(() {
    withEmptyImageCache();
    requests = [];
  });

  testWidgets('a view-only caller prefetches the previews of both neighbours',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
      viewerImagePart("d.jpg"),
    ];
    viewerAlbum(images, rights: const ["view"]);

    await pumpViewerHarness(
      tester,
      images[1],
      client: recordingViewerClient(requests),
      share: viewerShareSession(),
    );

    // The picture shown, and the two neighbours — nobody touched anything.
    expect(thumbnailsOf("a.jpg"), 1);
    expect(thumbnailsOf("b.jpg"), 1);
    expect(thumbnailsOf("c.jpg"), 1);
    // Two neighbours, not the whole album: the memory of a phone.
    expect(thumbnailsOf("d.jpg"), 0);

    await pageOn(tester);

    // C is displayed from the cache — the prefetch and the display name the
    // same provider, so this is a hit and not a second download — and the new
    // neighbour is fetched.
    expect(shownPictureUrl(tester), "$viewerBaseUrl/c.jpg?type=tn");
    expect(thumbnailsOf("c.jpg"), 1, reason: "the prefetch is what is shown");
    expect(thumbnailsOf("d.jpg"), 1);
  });

  testWidgets('a caller holding download prefetches the originals',
      (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
      viewerImagePart("d.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    var originals = <String>[];
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(
      tester,
      images[1],
      client: recordingViewerClient(requests),
      caller: viewerMember("bob"),
    );

    expect(originalsOf(originals, "a.jpg"), 1);
    expect(originalsOf(originals, "b.jpg"), 1);
    expect(originalsOf(originals, "c.jpg"), 1);
    expect(originalsOf(originals, "d.jpg"), 0);

    await pageOn(tester);

    expect(shownPictureUrl(tester), "$viewerBaseUrl/c.jpg");
    expect(originalsOf(originals, "c.jpg"), 1, reason: "a cache hit");
    expect(originalsOf(originals, "d.jpg"), 1);
  });

  testWidgets('inside a group the neighbours are the group\'s own shots',
      (tester) async {
    var shots = [
      viewerImagePart("g0.jpg"),
      viewerImagePart("g1.jpg"),
      viewerImagePart("g2.jpg"),
    ];
    var group = ImageGroup(images: shots);
    viewerAlbum(
      [viewerImagePart("before.jpg"), group, viewerImagePart("after.jpg")],
      rights: const ["view"],
    );

    await pumpViewerHarness(
      tester,
      shots[1],
      client: recordingViewerClient(requests),
      share: viewerShareSession(),
    );

    // Comparing two shots of one scene is what the group view is for, so the
    // chain the prefetch follows is the group's, not the album's.
    expect(thumbnailsOf("g0.jpg"), 1);
    expect(thumbnailsOf("g1.jpg"), 1);
    expect(thumbnailsOf("g2.jpg"), 1);
    expect(thumbnailsOf("before.jpg"), 0);
    expect(thumbnailsOf("after.jpg"), 0);
  });

  testWidgets('a video neighbour is not prefetched', (tester) async {
    var images = [
      viewerImagePart("a.jpg"),
      viewerImagePart("clip.mp4", kind: ImageKind.video),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view"]);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(requests),
      share: viewerShareSession(),
    );

    // What the viewer shows of a video is the player, whose poster is the
    // thumbnail the album has anyway: there is no picture to prefetch.
    expect(thumbnailsOf("a.jpg"), 1);
    expect(thumbnailsOf("clip.mp4"), 0);
  });

  testWidgets('a picture still on its way is covered by its own thumbnail',
      (tester) async {
    var images = [
      viewerImagePart("b.jpg"),
      viewerImagePart("c.jpg"),
    ];
    viewerAlbum(images, rights: const ["view", "download"]);
    // The original of C never arrives, so what is on the screen after paging
    // is whatever the viewer put under it.
    fakeImageRequests(pending: (url) => url.path.endsWith("c.jpg"));

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingViewerClient(requests),
      caller: viewerMember("bob"),
    );

    await pageOn(tester);

    // C's thumbnail, drawn where its picture will be, and no empty box.
    expect(shownPictureUrl(tester), "$viewerBaseUrl/c.jpg");
    var thumbnail = find.byKey(const Key("image-thumbnail"));
    expect(thumbnail, findsOneWidget);
    expect(thumbnailsOf("c.jpg"), 1);
    expect(tester.getSize(thumbnail).width, greaterThan(0));
    expect(tester.getSize(thumbnail).height, greaterThan(0));
  });
}
