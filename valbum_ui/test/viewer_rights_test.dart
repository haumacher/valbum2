/// What the viewer opens, and what it says when it cannot (issue #95).
///
/// The viewer used to ask for the original always and paint the server's
/// refusal over every picture a `view`-only caller looked at — a lecture about
/// a right that was never offered. Since #95 it asks for what the caller may
/// have: the original with `download`, the preview rendition (`?type=tn`)
/// without. The only thing left to say is a genuine failure, and that is said
/// once, in a message, over the thumbnail that is already there.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/viewer_harness.dart';
import 'util/l10n.dart';

/// The requests the app's own transport made — the thumbnails among them.
List<http.Request> requests = [];

/// A client recording everything it is asked for.
VAlbumClient recordingClient() {
  requests = [];
  return recordingViewerClient(requests);
}

/// The paths of the thumbnail requests made so far.
List<String> get thumbnailPaths => thumbnailPathsOf(requests);

void main() {
  setUp(withEmptyImageCache);

  testWidgets('a view-only caller is shown the preview, and no refusal',
      (tester) async {
    var images = [viewerImagePart("a.jpg")];
    viewerAlbum(images, rights: const ["view"]);
    var originals = <String>[];
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingClient(),
      share: viewerShareSession(),
    );

    // The picture is the preview rendition the `view` right allows.
    expect(shownPicture(tester), isA<ThumbnailImage>());
    expect(shownPictureUrl(tester), "$viewerBaseUrl/a.jpg?type=tn");
    expect(thumbnailPaths, contains("/valbum/data/album/a.jpg"));
    // The original is never asked for, so it is never refused: no 403 round
    // trip, no flicker, and nothing said about a right nobody was offered.
    expect(originals, isEmpty);
    expect(find.byKey(const Key("image-refusal")), findsNothing);
    expect(find.byKey(const Key("image-failed")), findsNothing);
  });

  testWidgets('a caller holding download is shown the original', (tester) async {
    var images = [viewerImagePart("a.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    var originals = <String>[];
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingClient(),
      caller: viewerMember("bob"),
    );

    expect(shownPicture(tester), isA<NetworkImage>());
    expect(shownPictureUrl(tester), "$viewerBaseUrl/a.jpg");
    expect(originals, contains("$viewerBaseUrl/a.jpg"));
    expect(find.byKey(const Key("image-failed")), findsNothing);
  });

  testWidgets('a picture the server cannot deliver is said once, over the '
      'thumbnail', (tester) async {
    var images = [viewerImagePart("a.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);

    // The server has the picture and will not hand it over: a 500, not a
    // refused right.
    fakeImageRequests(failing: (url) => url.path.endsWith("a.jpg"));

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingClient(),
      caller: viewerMember("bob"),
    );

    // One message, and it goes away by itself; nothing painted over the
    // picture for good.
    expect(find.byKey(const Key("image-failed")), findsOneWidget);
    expect(find.text(testL10n.pictureFailedMessage), findsOneWidget);
    expect(find.byKey(const Key("image-refusal")), findsNothing);
    // And what the album already showed is still on the screen.
    expect(find.byKey(const Key("image-thumbnail")), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const Key("image-thumbnail"))).width,
      greaterThan(0),
    );
  });

  testWidgets('a video is shown by its player, whatever the caller may do',
      (tester) async {
    var images = [viewerImagePart("clip.mp4", kind: ImageKind.video)];
    viewerAlbum(images, rights: const ["view"]);
    var originals = <String>[];
    fakeImageRequests(requested: originals);

    await pumpViewerHarness(
      tester,
      images[0],
      client: recordingClient(),
      share: viewerShareSession(),
    );

    // The player, as before: `?type=video` needs `view` like the thumbnail,
    // so nothing of #95 applies to it.
    expect(find.byType(VideoView), findsOneWidget);
    expect(find.byKey(const Key("image-picture")), findsNothing);
    expect(find.byKey(const Key("image-refusal")), findsNothing);
  });
}
