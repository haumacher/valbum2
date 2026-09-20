/// A rotated video in the viewer (issue #116).
///
/// Since issue #106 the album tile of a video is turned by the
/// [ImagePart.orientation] stored beside it — the poster the server makes is
/// upright by the *file*, and the stored turn comes on top of it. Inside the
/// viewer it was not: the poster and the playing surface were drawn unturned,
/// so a video the author had rotated in the album played on its side while its
/// tile stood upright.
///
/// Poster and player are now wrapped in the same transform, and the slot is
/// fitted to the oriented dimensions. Both or neither: a poster that is turned
/// and a player that is not would be worse than either.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player/video_player.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';

/// A landscape video carrying the given stored orientation.
ImagePart videoPart(Orientation orientation) => ImagePart(
      name: "clip.mp4",
      kind: ImageKind.video,
      width: 1920,
      height: 1080,
      orientation: orientation,
    );

/// Links the given images into an album, as loading an album does.
AlbumInfo linkedAlbum(List<AbstractImage> images) {
  var album = AlbumInfo(parts: images);
  for (var image in images) {
    image.owner = album;
    image.home = images.first;
    image.end = images.last;
  }
  return album;
}

/// Shows the viewer for the given part.
Future<void> pumpViewer(WidgetTester tester, AbstractImage image) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        home: ImageView(
          client: clientReturning("{}"),
          baseUrl: "http://server/valbum/data/album",
          image: image,
          onShowImage: (next) {},
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  });
}

/// The poster of the video: the only [Image] a video viewer shows.
Finder get poster => find.descendant(
      of: find.byType(VideoView),
      matching: find.byType(Image),
    );

/// The playing surface.
Finder get player => find.byType(VideoPlayer);

/// The [RotatedBox] wrapping [child], if there is one.
Finder rotationAround(Finder child) =>
    find.ancestor(of: child, matching: find.byType(RotatedBox));

/// Whether some [Transform] above [child] mirrors the x axis.
bool mirrored(WidgetTester tester, Finder child) => tester
    .widgetList<Transform>(
      find.ancestor(of: child, matching: find.byType(Transform)),
    )
    .any((transform) => transform.transform.getColumn(0).x == -1);

void main() {
  setUp(() => VideoPlayerPlatform.instance = FakeVideoPlayerPlatform());

  testWidgets('a rotated video turns its poster and its player together',
      (tester) async {
    var video = videoPart(Orientation.rotL);
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    // `rotL` is one quarter turn counter-clockwise, which a [RotatedBox]
    // counts as three clockwise ones.
    expect(tester.widget<RotatedBox>(rotationAround(poster)).quarterTurns, 3);
    expect(tester.widget<RotatedBox>(rotationAround(player)).quarterTurns, 3,
        reason: "the player is turned with the poster, never without it");

    // The slot is fitted to the *oriented* size: a quarter turn of a
    // 1920x1080 file stands upright.
    var box = tester.getSize(rotationAround(poster).first);
    expect(box.height, greaterThan(box.width));
    expect(box.height / box.width, closeTo(1920 / 1080, 0.01));
  });

  testWidgets('a mirrored video is mirrored, and not turned', (tester) async {
    var video = videoPart(Orientation.flipH);
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    expect(rotationAround(poster), findsNothing);
    expect(mirrored(tester, poster), isTrue);
    expect(mirrored(tester, player), isTrue);
  });

  testWidgets('an upright video is wrapped in nothing at all', (tester) async {
    var video = videoPart(Orientation.identity);
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    expect(find.byType(VideoView), findsOneWidget);
    expect(rotationAround(poster), findsNothing);
    expect(mirrored(tester, poster), isFalse);
    expect(mirrored(tester, player), isFalse);
  });
}
