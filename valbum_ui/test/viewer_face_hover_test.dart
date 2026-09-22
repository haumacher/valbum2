/// The viewer names the person under the mouse (issue #145).
///
/// A face that was *confirmed* as somebody carries a hover region over the
/// picture: the mouse resting on it is answered with the person's name, and
/// the box is faintly outlined while it is there. A suggestion of issue #127
/// is not a decision and names nobody, a face nobody decided about names
/// nobody, and a share link is told nothing at all — who somebody is, is
/// bookkeeping among the members of a space (issue #96).
///
/// The region is a hover region and nothing else: the tap, the drag, the pinch
/// and the long press of the viewer go through it as if it were not there.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/viewer_harness.dart';

/// The register the server answers, one person.
const String annaRegister =
    '{"people": [{"id": "p-anna", "name": "Anna", "user": "", '
    '"aliases": []}]}';

/// One face of a photograph.
FaceInfo faceOf(
  int index, {
  required double x,
  required double y,
  required double w,
  required double h,
  String person = "",
  bool confirmed = false,
}) =>
    FaceInfo(
      index: index,
      x: x,
      y: y,
      w: w,
      h: h,
      person: person,
      confirmed: confirmed,
      state: confirmed ? FaceState.confirmed : FaceState.undecided,
    );

/// The photograph the tests look at: 2000 x 1000, three boxes.
///
/// Face `0` is Anna and is confirmed — the one region that is drawn. Face `1`
/// is the suggestion of issue #127 for the same person, face `2` is a box
/// nobody said anything about.
ImagePart facesImage({
  Orientation orientation = Orientation.identity,
  String knownPerson = "p-anna",
}) {
  var image = viewerImagePart("a.jpg");
  image.orientation = orientation;
  image.faces = [
    faceOf(0,
        x: 0.1, y: 0.2, w: 0.2, h: 0.3, person: knownPerson, confirmed: true),
    faceOf(1, x: 0.5, y: 0.2, w: 0.2, h: 0.3, person: knownPerson),
    faceOf(2, x: 0.8, y: 0.6, w: 0.1, h: 0.2),
  ];
  return image;
}

/// A client answering `?type=people` and every thumbnail, counting the former.
VAlbumClient peopleClient(
  List<String> peopleRequests, {
  String body = annaRegister,
  int status = 200,
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        if (request.url.query.contains("type=people")) {
          peopleRequests.add(request.url.toString());
          return http.Response(
            body,
            status,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          );
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// The finder of the hover region of the face of the given index.
Finder regionOf(int index) => find.byKey(Key("face-hover-$index"));

/// Moves a mouse onto [target] and lets the tooltip's wait pass.
Future<TestGesture> hover(WidgetTester tester, Offset target) async {
  var mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
  await mouse.addPointer(location: Offset.zero);
  addTearDown(mouse.removePointer);
  await tester.pump();
  await mouse.moveTo(target);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
  await tester.pumpAndSettle();
  return mouse;
}

/// Pumps the viewer of [image] and lets the register arrive.
Future<void> pumpFaces(
  WidgetTester tester,
  ImagePart image, {
  required VAlbumClient client,
  ShareSession? share,
}) async {
  viewerAlbum([image], rights: const ["view"]);
  fakeImageRequests();
  await pumpViewerHarness(tester, image, client: client, share: share);
  await tester.pumpAndSettle();
}

void main() {
  setUp(withEmptyImageCache);

  testWidgets('names the person of a confirmed face under the mouse',
      (tester) async {
    var asked = <String>[];
    await pumpFaces(tester, facesImage(), client: peopleClient(asked));

    // Exactly one region: the confirmation. The suggestion of face 1 is what a
    // recogniser believes and says nothing here, face 2 names nobody at all.
    expect(regionOf(0), findsOneWidget);
    expect(regionOf(1), findsNothing);
    expect(regionOf(2), findsNothing);
    expect(asked, hasLength(1));

    // 2000 x 1000 in a page of 800 x 600 is fitted at 0.4 and centred
    // vertically: the picture covers (0, 100) to (800, 500). The box
    // (0.1, 0.2, 0.2, 0.3) of it is therefore (80, 180) to (240, 300).
    var picture = tester.getRect(find.byKey(const Key("image-picture")));
    expect(picture, const Rect.fromLTWH(0, 100, 800, 400));
    var region = tester.getRect(regionOf(0));
    expect(region, const Rect.fromLTWH(80, 180, 160, 120));
    expect(picture.contains(region.topLeft), isTrue);
    expect(picture.contains(region.bottomRight - const Offset(1, 1)), isTrue);

    // Nothing is said before the mouse asks.
    expect(find.text("Anna"), findsNothing);

    var mouse = await hover(tester, region.center);
    expect(find.text("Anna"), findsOneWidget);

    // And the name goes away with the mouse.
    await mouse.moveTo(const Offset(700, 550));
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text("Anna"), findsNothing);
  });

  testWidgets('calls them what they are called on a photograph',
      (tester) async {
    // Issue #146: the canonical name identifies, the nickname is what stands
    // under a face — and this is a face.
    await pumpFaces(
      tester,
      facesImage(),
      client: peopleClient(
        <String>[],
        body: '{"people": [{"id": "p-anna", "name": "Berta Müller", '
            '"nickname": "Tante Berta", "user": "", "aliases": []}]}',
      ),
    );

    await hover(tester, tester.getRect(regionOf(0)).center);
    expect(find.text("Tante Berta"), findsOneWidget);
    expect(find.text("Berta Müller"), findsNothing);
  });

  testWidgets('outlines the box only while it is hovered', (tester) async {
    var asked = <String>[];
    await pumpFaces(tester, facesImage(), client: peopleClient(asked));

    Finder outline() => find.descendant(
          of: regionOf(0),
          matching: find.byType(DecoratedBox),
        );
    expect(outline(), findsNothing);

    var region = tester.getRect(regionOf(0));
    var mouse = await hover(tester, region.center);
    expect(outline(), findsOneWidget);

    await mouse.moveTo(const Offset(700, 550));
    await tester.pump();
    await tester.pumpAndSettle();
    expect(outline(), findsNothing);
  });

  testWidgets('turns the box exactly as it turns the picture', (tester) async {
    var asked = <String>[];
    await pumpFaces(
      tester,
      facesImage(orientation: Orientation.rotR),
      client: peopleClient(asked),
    );

    // `rotR` maps the raw point (x, y) to (rawHeight - y, x), so the 2000 x
    // 1000 file occupies 1000 x 2000 and is fitted at 0.3 into the page,
    // centred horizontally: the picture covers (250, 0) to (550, 600).
    expect(
      tester.getRect(find.byKey(const Key("image-picture"))),
      const Rect.fromLTWH(250, 0, 300, 600),
    );

    // The raw box (200, 200) to (600, 500) turns into (1000 - 500, 200) to
    // (1000 - 200, 600) = (500, 200) to (800, 600), which at 0.3 with the
    // horizontal offset of 250 is (400, 60) to (490, 180).
    expect(
      tester.getRect(regionOf(0)),
      const Rect.fromLTWH(400, 60, 90, 120),
    );
  });

  testWidgets('shows nothing at all inside a share session', (tester) async {
    var asked = <String>[];
    await pumpFaces(
      tester,
      facesImage(),
      client: peopleClient(asked),
      share: viewerShareSession(),
    );

    expect(regionOf(0), findsNothing);
    // The register is space-level bookkeeping and is never even asked for.
    expect(asked, isEmpty);
  });

  testWidgets('says nothing where the register is refused', (tester) async {
    var asked = <String>[];
    var client = peopleClient(asked, body: "PEOPLE_REFUSED", status: 403);

    var first = facesImage();
    await pumpFaces(tester, first, client: client);
    expect(regionOf(0), findsNothing);
    expect(find.textContaining("REFUSED"), findsNothing);

    // A second viewer of the same client asks no second time: the refusal is
    // an answer and is remembered like one.
    Navigator.of(tester.element(find.byType(ImageView))).pop();
    await tester.pumpAndSettle();
    await pumpFaces(tester, facesImage(), client: client);
    expect(regionOf(0), findsNothing);
    expect(asked, hasLength(1));
  });

  testWidgets('names nobody the register does not know', (tester) async {
    var asked = <String>[];
    await pumpFaces(
      tester,
      facesImage(knownPerson: "p-stranger"),
      client: peopleClient(asked),
    );

    expect(asked, hasLength(1));
    expect(regionOf(0), findsNothing);
  });

  testWidgets('lets the gestures of the viewer through', (tester) async {
    var asked = <String>[];
    var image = facesImage();
    // A neighbour to page to, so that a drag over a face has somewhere to go.
    var next = viewerImagePart("b.jpg");
    viewerAlbum([image, next], rights: const ["view"]);
    fakeImageRequests();
    await pumpViewerHarness(tester, image, client: peopleClient(asked));
    await tester.pumpAndSettle();

    var region = tester.getRect(regionOf(0));

    // A long press on the region is the viewer's own long press, as it is
    // anywhere else on the picture (issue #80): this caller may not write a
    // description, and is told exactly that — the region took nothing away.
    await tester.longPressAt(region.center);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("image-not-editable")), findsOneWidget);
    // A touch has no hover, so it never names anybody: the tooltip is
    // triggered by the mouse entering the region and by nothing else.
    expect(find.text("Anna"), findsNothing);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();

    // A drag starting on the region pages the viewer, as it does beside it.
    await tester.fling(regionOf(0), const Offset(-600, 0), 1200);
    await tester.pumpAndSettle();
    expect(shownPictureUrl(tester), contains("b.jpg"));
  });

  testWidgets('keeps the region on the face while the picture is zoomed',
      (tester) async {
    var asked = <String>[];
    await pumpFaces(tester, facesImage(), client: peopleClient(asked));

    var before = tester.getRect(regionOf(0));
    var picture = tester.getRect(find.byKey(const Key("image-picture")));
    var relative = Offset(
      (before.center.dx - picture.left) / picture.width,
      (before.center.dy - picture.top) / picture.height,
    );

    // A click zooms to a pixel-by-pixel display around the clicked spot.
    await tester.tapAt(before.center);
    await tester.pumpAndSettle();

    var zoomed = tester.getRect(regionOf(0));
    var zoomedPicture = tester.getRect(find.byKey(const Key("image-picture")));
    expect(zoomedPicture.width, greaterThan(picture.width));
    expect(zoomed.width, greaterThan(before.width));
    expect(
      (zoomed.center.dx - zoomedPicture.left) / zoomedPicture.width,
      closeTo(relative.dx, 0.001),
    );
    expect(
      (zoomed.center.dy - zoomedPicture.top) / zoomedPicture.height,
      closeTo(relative.dy, 0.001),
    );
  });
}
