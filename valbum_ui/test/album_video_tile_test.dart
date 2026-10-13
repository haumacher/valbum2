import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'album_return_test.dart' show albumStateOf, countingClient, settle;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';

/// An album with a photograph and a video, for the teaser of issue #271.
const String teaserAlbum = '''
["AlbumInfo", {"path": "", "title": "T", "subTitle": "",
 "parts": [
   ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}],
   ["ImagePart", {"kind": "VIDEO", "name": "b.mp4", "date": 2, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}]
 ]}]''';

/// Where the tests of a teaser run: the test binding reports a phone, which
/// has no teaser at all.
final TargetPlatformVariant hovering =
    TargetPlatformVariant.only(TargetPlatform.linux);

/// Shows [teaserAlbum] where a pointer hovers, with a video surface that
/// takes the pointer for itself as the web's platform view does, and rests
/// a mouse on the video tile until its teaser plays.
Future<(FakeVideoPlayerPlatform, TestGesture)> playTeaser(
    WidgetTester tester) async {
  var platform = FakeVideoPlayerPlatform()..surfaceTakesPointer = true;
  VideoPlayerPlatform.instance = platform;

  await settle(
    tester,
    () => tester.pumpWidget(
      VAlbumApp(client: countingClient(teaserAlbum, <String>[])),
    ),
  );
  var mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
  addTearDown(mouse.removePointer);
  await mouse.addPointer(location: Offset.zero);
  await mouse.moveTo(tester.getCenter(find.byKey(const ValueKey("b.mp4"))));
  for (var i = 0; i < 5; i++) {
    await tester.pump();
  }
  expect(find.byKey(const Key("video-teaser")), findsOneWidget,
      reason: "the teaser plays");
  return (platform, mouse);
}

void main() {
  testWidgets('video tiles carry a play mark, image tiles do not',
      (tester) async {
    const album = '''
["AlbumInfo", {"path": "", "title": "T", "subTitle": "",
 "parts": [
   ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}],
   ["ImagePart", {"kind": "VIDEO", "name": "b.mp4", "date": 2, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}],
   ["ImagePart", {"kind": "QUICKTIME", "name": "c.mov", "date": 3, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}]
 ]}]''';
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: clientReturning(album)));
      await tester.pumpAndSettle();
    });
    expect(find.byType(Image), findsNWidgets(3));
    expect(find.byKey(const Key("video-indicator")), findsNWidgets(2));
  });

  testWidgets('a video tile carries its teaser, an image tile does not',
      (tester) async {
    // The composition of issue #75: every video tile is one that *could* play
    // its teaser, and it knows which teaser is its own. Whether it really
    // plays one is the platform's word, see `video_teaser_test.dart`.
    const album = '''
["AlbumInfo", {"path": "", "title": "T", "subTitle": "",
 "parts": [
   ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}],
   ["ImagePart", {"kind": "VIDEO", "name": "b.mp4", "date": 2, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}]
 ]}]''';
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: clientReturning(album)));
      await tester.pumpAndSettle();
    });

    var teasers = tester.widgetList<VideoTeaser>(find.byType(VideoTeaser));
    expect(teasers, hasLength(1), reason: "one video, one teaser");
    expect(
      teasers.single.teaserUrl,
      "http://server/valbum/data/b.mp4?type=teaser",
    );
    expect(teasers.single.probeTeaser, isNotNull);
  });

  testWidgets('a click on a playing teaser opens the viewer, as on the poster',
      (tester) async {
    // Issue #271: on the web the teaser is a platform view, which took the
    // click for itself, and the viewer never opened.
    var (platform, mouse) = await playTeaser(tester);
    var teaser = platform.lastPlayerId;

    await settle(tester, () async {
      await mouse.down(tester.getCenter(find.byKey(const ValueKey("b.mp4"))));
      await mouse.up();
    });

    expect(platform.surfaceTaps, 0, reason: "the tile takes the click");
    expect(find.byType(ImageView), findsOneWidget, reason: "the viewer");
    expect(find.byType(VideoView), findsOneWidget);
    // The teaser is released: its player is gone, nothing of it is left on
    // the album beneath the viewer.
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    expect(platform.disposedIds, contains(teaser));
    expect(
      find.byKey(const Key("video-teaser"), skipOffstage: false),
      findsNothing,
    );
  }, variant: hovering);

  testWidgets('a long press on a playing teaser is the long press of the tile',
      (tester) async {
    var (platform, mouse) = await playTeaser(tester);
    var teaser = platform.lastPlayerId;
    var album = albumStateOf(tester);
    expect(album.selectMode || album.editMode, isFalse);

    await settle(tester, () async {
      await mouse.down(tester.getCenter(find.byKey(const ValueKey("b.mp4"))));
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 100));
      await mouse.up();
    });

    expect(album.selectMode || album.editMode, isTrue,
        reason: "the tile's long press, not the teaser's");
    expect(find.byType(ImageView), findsNothing);
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    expect(platform.disposedIds, contains(teaser),
        reason: "no teaser while the album is worked on");
  }, variant: hovering);
}
