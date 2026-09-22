/// Probe of issue #145 composed with #125's merges and the viewer's paging:
/// a face tagged under a merged-away id names the survivor, and paging
/// between a photograph with a named face and one with only a suggestion
/// draws the region exactly where a confirmed person is.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/viewer_harness.dart';
import 'viewer_face_hover_test.dart'
    show faceOf, hover, peopleClient, regionOf;

const String mergedRegister =
    '{"people": [{"id": "p-anna", "name": "Anna", "user": "", '
    '"aliases": [{"id": "p-old"}]}]}';

void main() {
  testWidgets(
      "a face under a merged-away id names the survivor, and paging draws "
      "regions only where somebody is confirmed", (tester) async {
    var a = viewerImagePart("a.jpg");
    a.faces = [
      faceOf(0, x: 0.1, y: 0.2, w: 0.2, h: 0.3, person: "p-old", confirmed: true),
    ];
    var b = viewerImagePart("b.jpg");
    b.faces = [
      faceOf(0, x: 0.3, y: 0.3, w: 0.2, h: 0.3, person: "p-anna"),
    ];
    var peopleRequests = <String>[];
    var client = peopleClient(peopleRequests, body: mergedRegister);

    viewerAlbum([a, b], rights: const ["view"]);
    fakeImageRequests();
    await pumpViewerHarness(tester, a, client: client);
    await tester.pumpAndSettle();

    expect(regionOf(0), findsOneWidget);
    await hover(tester, tester.getCenter(regionOf(0)));
    expect(find.text("Anna"), findsOneWidget,
        reason: "the alias resolves to the survivor's name");

    // Page to b.jpg: a suggestion only, so no region at all.
    await withFakeImageHttp(() async {
      await tester.fling(regionOf(0), const Offset(-600, 0), 1200);
      await tester.pumpAndSettle();
    });
    expect(shownPictureUrl(tester), contains("b.jpg"));
    expect(find.byKey(const Key("face-hover-0")), findsNothing);

    // And back: the region is there again, the register was asked once.
    await withFakeImageHttp(() async {
      await tester.fling(find.byType(ImageView), const Offset(600, 0), 1200);
      await tester.pumpAndSettle();
    });
    expect(regionOf(0), findsOneWidget);
    expect(peopleRequests, hasLength(1));
  });
}
