/// Probe for #116/#120: a rotated video reached by stepping in place (#105),
/// and the underlay hit when the viewer is opened from a group's alternatives.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'album_return_test.dart' show countingClient, settle, tap;
import 'util/fake_video_player.dart';
import 'viewer_underlay_test.dart' show underlayOf, tileProviderOf, fetchesOf;

const String album = '''
["AlbumInfo", {"path": "", "title": "Probe", "subTitle": "", "parts": [
  ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}],
  ["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", "date": 2, "width": 1920, "height": 1080, "orientation": "ROT_L", "rating": 0}],
  ["ImageGroup", {"representative": 0, "images": [
    {"kind": "IMAGE", "name": "g1.jpg", "date": 3, "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0},
    {"kind": "IMAGE", "name": "g2.jpg", "date": 4, "width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}
  ]}]
]}]
''';

ImageViewState viewerOf(WidgetTester tester) =>
    tester.state<ImageViewState>(find.byType(ImageView));

int turnsAround(WidgetTester tester, Finder inner) => tester
    .widgetList<RotatedBox>(
        find.ancestor(of: inner, matching: find.byType(RotatedBox)))
    .fold(0, (sum, box) => sum + box.quarterTurns);

void main() {
  setUp(() {
    VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    forgetDecodedThumbnailHeights();
  });

  testWidgets('stepping in place onto a rotated video turns it, and back '
      'onto the image turns nothing', (tester) async {
    var thumbnails = <String>[];
    await settle(
        tester,
        () => tester.pumpWidget(VAlbumApp(
              client: countingClient(album, thumbnails),
              initialRoute: const ImageRoute([], "a.jpg"),
            )));
    var viewer = viewerOf(tester);
    await settle(tester, () async => viewer.showNext());
    expect(find.byType(VideoView), findsOneWidget);
    expect(identical(viewerOf(tester), viewer), isTrue, reason: "#105");
    var poster = find.descendant(
        of: find.byType(VideoView), matching: find.byType(Image));
    expect(poster, findsWidgets);
    expect(turnsAround(tester, poster.first), 3, reason: "ROT_L");
    var slot = tester.getSize(find.byType(VideoView));
    expect(slot.height, greaterThan(slot.width), reason: "a turned 16:9");

    await settle(tester, () async => viewerOf(tester).showPrevious());
    expect(find.byType(VideoView), findsNothing);
    expect(
        find.ancestor(
            of: find.byKey(const Key("image-picture")),
            matching: find.byType(RotatedBox)),
        findsNothing);
  });

  testWidgets('opened from the alternatives of a group, the underlay is the '
      'member tile\'s own picture', (tester) async {
    var thumbnails = <String>[];
    await settle(tester,
        () => tester.pumpWidget(VAlbumApp(client: countingClient(album, thumbnails))));
    await tap(tester, find.byKey(const ValueKey("g1.jpg")));
    expect(find.byType(ImageView), findsOneWidget);
    await tap(tester, find.byIcon(Icons.expand_more));
    expect(find.byType(GroupView), findsOneWidget);
    expect(fetchesOf(thumbnails, "g2.jpg"), 1, reason: "the member tile");
    var tile = tileProviderOf(tester, "g2.jpg");
    var before = thumbnails.length;
    await tap(tester, find.byKey(const Key("group-tile-g2.jpg")));
    expect(find.byType(ImageView), findsOneWidget);
    expect(viewerOf(tester).part.name, "g2.jpg");
    expect(underlayOf(tester), tile);
    expect(thumbnails.length, before,
        reason: "the descent fetched ${thumbnails.sublist(before)}");
  });
}
