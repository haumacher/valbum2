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
    show faceOf, facesImage, hover, peopleClient, pumpFaces, regionOf;

const String mergedRegister =
    '{"people": [{"id": "p-anna", "name": "Anna", "user": "", '
    '"aliases": [{"id": "p-old"}]}]}';

void main() {
  mainNameOutsideTheBox();
  testWidgets(
      "a face under a merged-away id names the survivor, and paging draws "
      "regions only where somebody is confirmed", (tester) async {
    var a = viewerImagePart("a.jpg");
    a.faces = [
      faceOf(0,
          x: 0.1, y: 0.2, w: 0.2, h: 0.3, person: "p-old", confirmed: true),
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

/// Issue #162: the name stands outside the box it names — a [Tooltip] is
/// placed a fixed distance from the centre of its child, which on a face box
/// taller than that distance put the name over the face in every case.
void mainNameOutsideTheBox() {
  testWidgets('the name stands outside the face box, not over the face',
      (tester) async {
    await pumpFaces(tester, facesImage(), client: peopleClient([]));
    var region = tester.getRect(regionOf(0));
    expect(region.height, greaterThan(48),
        reason: "a box the fixed offset of a Tooltip would land inside of");

    await hover(tester, region.center);

    var name = tester.getRect(find.text("Anna"));
    expect(name.overlaps(region), isFalse,
        reason: "the name must leave the face visible");
    expect(name.bottom, lessThanOrEqualTo(region.top),
        reason: "above the box, where the room is");
    expect(region.top - name.bottom, lessThan(40),
        reason: "and close to it, not somewhere on the page");
  });
}
