import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Probe review for issue #22: a video composed with the viewer's chrome and
// keyboard navigation to and from a neighbouring image.
void main() {
  testWidgets('video keeps comment and neighbours; keys move to image and back',
      (tester) async {
    var video = ImagePart(
      name: "clip.mov",
      kind: ImageKind.quicktime,
      width: 1920,
      height: 1080,
      comment: "Erste Zeile\n\nZweite Zeile",
    );
    var photo = ImagePart(name: "p.jpg", width: 2000, height: 1000);
    var album = AlbumInfo(parts: [video, photo]);
    for (var i in [video, photo]) {
      i.owner = album;
      i.home = video;
      i.end = photo;
    }
    video.next = photo;
    photo.previous = video;

    AbstractImage current = video;
    late StateSetter rebuild;
    await withFakeImageHttp(() async {
      await tester.pumpWidget(MaterialApp(
        home: StatefulBuilder(builder: (context, setState) {
          rebuild = setState;
          return ImageView(
            client: clientReturning("{}"),
            baseUrl: "http://server/valbum/data/album",
            image: current,
            onShowImage: (next) => rebuild(() => current = next),
          );
        }),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(VideoView), findsOneWidget);
      expect(find.text("Erste Zeile"), findsOneWidget);
      expect(find.text("Zweite Zeile"), findsOneWidget);
      expect(find.byIcon(Icons.chevron_right), findsOneWidget);
      expect(find.byIcon(Icons.chevron_left), findsNothing);

      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pumpAndSettle();
      expect(current, same(photo));
      expect(find.byType(VideoView), findsNothing);

      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pumpAndSettle();
      expect(current, same(video));
      expect(find.byType(VideoView), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
