import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

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
}
