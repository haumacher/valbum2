/// Naming the faces of a photograph in the viewer itself (issue #147).
///
/// The edit-persons mode: every face of the picture is marked, a name stands
/// over the ones that have one, tapping a box decides about that face, and a
/// rectangle drawn on the picture marks a face the detector missed. Every
/// decision is posted at once — there is no buffer and no Save here, the
/// doctrine of the inbox and of issue #121's properties.
library;

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// The register the server answers: two people.
const String register = '{"people": ['
    '{"id": "p-anna", "name": "Anna", "user": "", "aliases": []}, '
    '{"id": "p-bob", "name": "Bob", "user": "", "aliases": []}]}';

/// The path of the album the viewer writes to.
const List<String> albumPath = ["album"];

/// One face of a photograph.
FaceInfo faceOf(
  int index, {
  required double x,
  required double y,
  required double w,
  required double h,
  String person = "",
  bool confirmed = false,
  FaceState? state,
}) =>
    FaceInfo(
      index: index,
      x: x,
      y: y,
      w: w,
      h: h,
      person: person,
      confirmed: confirmed,
      state: state ??
          (confirmed ? FaceState.confirmed : FaceState.undecided),
    );

/// The photograph the tests look at: 2000 x 1000, three faces.
///
/// Face `0` is Anna, confirmed. Face `1` is the suggestion of issue #127 for
/// Anna. Face `2` is a box nobody has said anything about.
ImagePart facesImage({Orientation orientation = Orientation.identity}) {
  var image = viewerImagePart("a.jpg");
  image.orientation = orientation;
  image.faces = [
    faceOf(0, x: 0.1, y: 0.2, w: 0.2, h: 0.3, person: "p-anna", confirmed: true),
    faceOf(1, x: 0.5, y: 0.2, w: 0.2, h: 0.3, person: "p-anna"),
    faceOf(2, x: 0.8, y: 0.6, w: 0.1, h: 0.2),
  ];
  return image;
}

/// What the server answers a tagging with: the album, with face `1` confirmed.
const String confirmedAlbum = '["AlbumInfo", {"path": "album", '
    '"title": "Album", "subTitle": "", "parts": [["ImagePart", '
    '{"name": "a.jpg", "width": 2000, "height": 1000, "faces": ['
    '{"index": 0, "x": 0.1, "y": 0.2, "w": 0.2, "h": 0.3, '
    '"person": "p-anna", "confirmed": true, "state": "CONFIRMED"}, '
    '{"index": 1, "x": 0.5, "y": 0.2, "w": 0.2, "h": 0.3, '
    '"person": "p-anna", "confirmed": true, "state": "CONFIRMED"}, '
    '{"index": 2, "x": 0.8, "y": 0.6, "w": 0.1, "h": 0.2}]}]]}]';

/// The album the server answers, `a.jpg` carrying the given faces (JSON).
String albumWithFaces(List<String> faces) => '["AlbumInfo", {"path": "album", '
    '"title": "Album", "subTitle": "", "parts": [["ImagePart", '
    '{"name": "a.jpg", "width": 2000, "height": 1000, "faces": ['
    '${faces.join(", ")}]}]]}]';

/// One face as the server answers it (JSON).
String faceJson(
  int index,
  List<double> box, {
  String person = "",
  bool confirmed = false,
  String state = "UNDECIDED",
  String cluster = "",
}) =>
    '{"index": $index, "x": ${box[0]}, "y": ${box[1]}, "w": ${box[2]}, '
    '"h": ${box[3]}, "person": "$person", "confirmed": $confirmed, '
    '"state": "$state", "cluster": "$cluster"}';

/// A client answering the register and every tagging, recording both.
///
/// [answerFor] decides the answer to a tagging by what was posted, where a
/// test needs the server to answer a marked face; [answer] is the answer
/// otherwise.
VAlbumClient facesClient(
  List<Map<String, dynamic>> posted, {
  String people = register,
  String answer = confirmedAlbum,
  String Function(Map<String, dynamic> body)? answerFor,
  int tagStatus = 200,
  String tagBody = "",
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        if (request.url.query.contains("type=people")) {
          return http.Response(
            people,
            200,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }
        if (request.url.query.contains("action=tag-faces")) {
          var body = jsonDecode(request.body) as Map<String, dynamic>;
          posted.add(body);
          if (tagStatus >= 300) {
            return http.Response(tagBody, tagStatus);
          }
          return http.Response(
            answerFor?.call(body) ?? answer,
            200,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }
        if (request.url.query.contains("action=create-person")) {
          return http.Response(
            '{"id": "p-bob", "name": "Bob", "user": "", "aliases": []}',
            200,
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

/// The assignments of the single posted request.
List<dynamic> assignmentsOf(List<Map<String, dynamic>> posted) {
  expect(posted, hasLength(1));
  return posted.first["faces"] as List<dynamic>;
}

/// Pumps the viewer over an album this caller may edit.
Future<ImagePart> pumpEditor(
  WidgetTester tester, {
  required VAlbumClient client,
  ImagePart? image,
  List<AbstractImage>? album,
  List<String> rights = const ["view", "download", "edit"],
  bool faces = true,
  ShareSession? share,
  bool offline = false,
  Locale? locale,
}) async {
  var shown = image ?? facesImage();
  viewerAlbum(album ?? [shown], rights: rights);
  fakeImageRequests();
  await pumpViewerHarness(
    tester,
    shown,
    client: client,
    caller: CallerInfo(userName: "anna", role: roleMember, faces: faces),
    share: share,
    editPath: albumPath,
    offline: offline,
    locale: locale,
  );
  await tester.pumpAndSettle();
  return shown;
}

/// Opens the viewer's menu and enters the edit-persons mode.
Future<void> enterMode(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("viewer-menu")));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("viewer-edit-persons")));
  await tester.pumpAndSettle();
}

/// Asserts that the viewer offers no edit-persons mode.
///
/// The menu itself may stand for another entry — the download of issue #164,
/// offered with `download` — so it is opened where it is there.
Future<void> expectNoEditPersons(WidgetTester tester) async {
  var menu = find.byKey(const Key("viewer-menu"));
  if (menu.evaluate().isNotEmpty) {
    await tester.tap(menu);
    await tester.pumpAndSettle();
  }
  expect(find.byKey(const Key("viewer-edit-persons")), findsNothing);
}

/// Taps the box of the face of the given index.
Future<void> tapFace(WidgetTester tester, int index) async {
  await tester.tap(find.byKey(Key("face-box-$index")));
  await tester.pumpAndSettle();
}

void main() {
  setUp(withEmptyImageCache);

  group('entering the mode', () {
    testWidgets('is offered to an editor of a space that looks for faces',
        (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      expect(find.byKey(const Key("viewer-menu")), findsOneWidget);
      await enterMode(tester);
      expect(find.byKey(const Key("viewer-edit-persons-done")), findsOneWidget);
    });

    testWidgets('its menu button is drawn like every control over the picture',
        (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      var icon = tester.widget<Icon>(find.descendant(
        of: find.byKey(const Key("viewer-menu")),
        matching: find.byType(Icon),
      ));
      // White and as large as the viewer's overlay buttons (#155), not the
      // theme's dark three dots on the black viewer.
      expect(icon.color, Colors.white);
      expect(icon.size, 32);
    });

    testWidgets('is not offered without the edit right', (tester) async {
      await pumpEditor(
        tester,
        client: facesClient([]),
        rights: const ["view", "download"],
      );
      await expectNoEditPersons(tester);
    });

    testWidgets('is not offered where the space looks for no face',
        (tester) async {
      await pumpEditor(tester, client: facesClient([]), faces: false);
      await expectNoEditPersons(tester);
    });

    testWidgets('is never offered in a share session', (tester) async {
      await pumpEditor(
        tester,
        client: facesClient([]),
        share: viewerShareSession(rights: const ["view", "edit"]),
      );
      await expectNoEditPersons(tester);
    });
  });

  group('what the mode shows', () {
    testWidgets('every face, named by what the server said', (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      // Outside the mode only the confirmation carries a hover region (#145).
      expect(find.byKey(const Key("face-hover-0")), findsOneWidget);

      await enterMode(tester);

      expect(find.byKey(const Key("face-box-0")), findsOneWidget);
      expect(find.byKey(const Key("face-box-1")), findsOneWidget);
      expect(find.byKey(const Key("face-box-2")), findsOneWidget);
      // The hover regions of issue #145 step aside: one box per face.
      expect(find.byKey(const Key("face-hover-0")), findsNothing);

      expect(find.text("Anna"), findsOneWidget);
      expect(find.text(testL10n.personsSuggestedHeading("Anna")),
          findsOneWidget);
      // A face nobody said anything about is a box and nothing else.
      expect(find.byKey(const Key("face-label-2")), findsNothing);
    });

    testWidgets('the box where the face is, turned as the picture is',
        (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      await enterMode(tester);

      // 2000 x 1000 in a page of 800 x 600 is fitted at 0.4 and centred
      // vertically: the picture covers (0, 100) to (800, 500), and the box
      // (0.1, 0.2, 0.2, 0.3) of it is (80, 180) to (240, 300).
      expect(
        tester.getRect(find.byKey(const Key("face-box-0"))),
        rectMoreOrLessEquals(const Rect.fromLTWH(80, 180, 160, 120),
            epsilon: 0.01),
      );
    });

    testWidgets('a quarter turn moves the box with the picture',
        (tester) async {
      await pumpEditor(
        tester,
        client: facesClient([]),
        image: facesImage(orientation: Orientation.rotR),
      );
      await enterMode(tester);

      // As issue #145 pins it: the raw box (200, 200) to (600, 500) turns into
      // (500, 200) to (800, 600), which at 0.3 with the offset 250 is
      // (400, 60) to (490, 180).
      expect(
        tester.getRect(find.byKey(const Key("face-box-0"))),
        rectMoreOrLessEquals(const Rect.fromLTWH(400, 60, 90, 120),
            epsilon: 0.01),
      );
    });
  });

  group('deciding about a face', () {
    testWidgets('confirming the suggestion posts one assignment',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tapFace(tester, 1);
      expect(find.byKey(const Key("face-decision")), findsOneWidget);
      await tester.tap(find.byKey(const Key("face-decision-confirm")));
      await tester.pumpAndSettle();

      expect(assignmentsOf(posted), [
        {
          "image": "a.jpg",
          "face": 1,
          "x": 0.0,
          "y": 0.0,
          "w": 0.0,
          "h": 0.0,
          "person": "p-anna",
          "state": "CONFIRMED",
        }
      ]);
      // And what the server answered is what the picture now says.
      expect(find.text("Anna"), findsNWidgets(2));
      expect(
        find.text(testL10n.personsSuggestedHeading("Anna")),
        findsNothing,
      );
    });

    testWidgets('a confirmed face is forgotten', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tapFace(tester, 0);
      await tester.tap(find.byKey(const Key("face-decision-forget")));
      await tester.pumpAndSettle();

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["face"], 0);
      expect(only["person"], "");
      expect(only["state"], "UNDECIDED");
    });

    testWidgets('a false detection is said to be one', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tapFace(tester, 2);
      // Nothing was decided about this face, so there is nothing to forget.
      expect(find.byKey(const Key("face-decision-forget")), findsNothing);
      expect(find.byKey(const Key("face-decision-confirm")), findsNothing);
      await tester.tap(find.byKey(const Key("face-decision-not-a-face")));
      await tester.pumpAndSettle();

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["face"], 2);
      expect(only["person"], "");
      expect(only["state"], "NOT_A_FACE");
    });

    testWidgets('naming an unknown face goes through the person chooser',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tapFace(tester, 2);
      await tester.tap(find.byKey(const Key("face-decision-name")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("persons-chooser")), findsOneWidget);
      await tester.tap(find.byKey(const Key("persons-pick-p-bob")));
      await tester.pumpAndSettle();

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["person"], "p-bob");
      expect(only["state"], "CONFIRMED");
    });
  });

  group('marking a face by hand', () {
    /// Switches the marking tool on.
    Future<void> markingTool(WidgetTester tester) async {
      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();
    }

    /// Draws a rectangle on the picture from [from] to [to].
    Future<void> drag(WidgetTester tester, Offset from, Offset to) async {
      var gesture = await tester.startGesture(from);
      var step = (to - from) / 10;
      for (var n = 1; n <= 10; n++) {
        await gesture.moveTo(from + step * n.toDouble());
        await tester.pump();
      }
      await gesture.up();
      await tester.pumpAndSettle();
    }

    /// The box of the first posted assignment.
    List<double> firstBox(List<Map<String, dynamic>> posted) {
      var first = (posted.first["faces"] as List<dynamic>).first
          as Map<String, dynamic>;
      return [
        (first["x"] as num).toDouble(),
        (first["y"] as num).toDouble(),
        (first["w"] as num).toDouble(),
        (first["h"] as num).toDouble(),
      ];
    }

    /// The picture covers (0, 100) to (800, 500): (400, 260) to (560, 380) is
    /// (0.5, 0.4, 0.2, 0.3) of it.
    const drawn = [0.5, 0.4, 0.2, 0.3];

    testWidgets('posts the rectangle at once, undecided, then names the face',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: facesClient(
          posted,
          // Nothing found around it: the server keeps the box as a region.
          answerFor: (_) => albumWithFaces([faceJson(3, drawn)]),
        ),
      );
      await enterMode(tester);
      await markingTool(tester);

      await drag(tester, const Offset(400, 260), const Offset(560, 380));

      expect(posted, hasLength(1));
      var marked =
          (posted.first["faces"] as List<dynamic>).first as Map<String, dynamic>;
      expect(marked["person"], "");
      expect(marked["state"], "UNDECIDED");
      var box = firstBox(posted);
      expect(box[0], closeTo(0.5, 0.01));
      expect(box[1], closeTo(0.4, 0.01));
      expect(box[2], closeTo(0.2, 0.01));
      expect(box[3], closeTo(0.3, 0.01));

      // Nobody is suggested for the face the server answered: who is it?
      expect(find.byKey(const Key("persons-chooser")), findsOneWidget);
      await tester.tap(find.byKey(const Key("persons-pick-p-bob")));
      await tester.pumpAndSettle();

      expect(posted, hasLength(2));
      expect((posted[1]["faces"] as List<dynamic>).single, {
        "image": "a.jpg",
        "face": 3,
        "x": 0.0,
        "y": 0.0,
        "w": 0.0,
        "h": 0.0,
        "person": "p-bob",
        "state": "CONFIRMED",
      });
    });

    // The same rectangle, drawn from each of its four corners (#155).
    for (var corners in [
      (const Offset(400, 260), const Offset(560, 380), "down and right"),
      (const Offset(560, 380), const Offset(400, 260), "up and left"),
      (const Offset(560, 260), const Offset(400, 380), "down and left"),
      (const Offset(400, 380), const Offset(560, 260), "up and right"),
    ]) {
      testWidgets('draws the same box dragged ${corners.$3}', (tester) async {
        var posted = <Map<String, dynamic>>[];
        await pumpEditor(tester, client: facesClient(posted));
        await enterMode(tester);
        await markingTool(tester);

        await drag(tester, corners.$1, corners.$2);

        expect(posted, hasLength(1));
        var box = firstBox(posted);
        expect(box[0], closeTo(0.5, 0.01));
        expect(box[1], closeTo(0.4, 0.01));
        expect(box[2], closeTo(0.2, 0.01));
        expect(box[3], closeTo(0.3, 0.01));
      });
    }

    // The 2000 x 1000 file turned a quarter occupies 1000 x 2000, fitted at
    // 0.3 and centred horizontally: the picture covers (250, 0) to (550, 600).
    // A box is a fraction of the *file* (issue #142), so the rectangle
    // (0.5, 0.4) to (0.7, 0.7) of it lands where the turn puts it: `rotR` maps
    // the raw point (x, y) to (1000 - y, x), `rotL` to (y, 2000 - x).
    for (var turned in [
      (Orientation.rotR, const Offset(340, 300), const Offset(430, 420)),
      (Orientation.rotL, const Offset(370, 180), const Offset(460, 300)),
    ]) {
      testWidgets('turns the rectangle back on a picture turned ${turned.$1}',
          (tester) async {
        var posted = <Map<String, dynamic>>[];
        await pumpEditor(
          tester,
          client: facesClient(posted),
          image: facesImage(orientation: turned.$1),
        );
        await enterMode(tester);
        await markingTool(tester);

        await drag(tester, turned.$2, turned.$3);

        var box = firstBox(posted);
        expect(box[0], closeTo(0.5, 0.01));
        expect(box[1], closeTo(0.4, 0.01));
        expect(box[2], closeTo(0.2, 0.01));
        expect(box[3], closeTo(0.3, 0.01));
      });
    }

    testWidgets('a face the server recognises is suggested, and no chooser',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: facesClient(
          posted,
          // The detector found a face around the box, and recognition knows
          // it: a suggestion of #127, never a decision.
          answerFor: (body) => posted.length == 1
              ? albumWithFaces([
                  faceJson(3, const [0.52, 0.38, 0.15, 0.32],
                      person: "p-anna", cluster: "c2"),
                ])
              : albumWithFaces([
                  faceJson(3, const [0.52, 0.38, 0.15, 0.32],
                      person: "p-anna",
                      confirmed: true,
                      state: "CONFIRMED",
                      cluster: "c2"),
                ]),
        ),
      );
      await enterMode(tester);
      await markingTool(tester);

      await drag(tester, const Offset(400, 260), const Offset(560, 380));

      expect(find.byKey(const Key("persons-chooser")), findsNothing);
      expect(find.byKey(const Key("face-label-3")), findsOneWidget);
      expect(find.text(testL10n.personsSuggestedHeading("Anna")),
          findsOneWidget);

      // One tap on the box, one on Confirm.
      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();
      await tapFace(tester, 3);
      await tester.tap(find.byKey(const Key("face-decision-confirm")));
      await tester.pumpAndSettle();

      expect(posted, hasLength(2));
      var confirmed =
          (posted[1]["faces"] as List<dynamic>).single as Map<String, dynamic>;
      expect(confirmed["face"], 3);
      expect(confirmed["person"], "p-anna");
      expect(confirmed["state"], "CONFIRMED");
      expect(find.text("Anna"), findsOneWidget);
    });

    testWidgets('a cancelled chooser leaves the region undecided',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: facesClient(
          posted,
          answerFor: (_) => albumWithFaces([faceJson(3, drawn)]),
        ),
      );
      await enterMode(tester);
      await markingTool(tester);

      await drag(tester, const Offset(400, 260), const Offset(560, 380));
      await tester.tap(find.byKey(const Key("persons-chooser-cancel")));
      await tester.pumpAndSettle();

      // The marking itself, and nothing more.
      expect(posted, hasLength(1));
      expect(find.byKey(const Key("face-box-3")), findsOneWidget);
    });

    testWidgets('a click beside every face marks a face there',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);
      await markingTool(tester);

      // Below every face: the click is sent as a square of 24 around it,
      // (288, 438) to (312, 462) on the page.
      await tester.tapAt(const Offset(300, 450));
      await tester.pumpAndSettle();

      expect(posted, hasLength(1));
      var marked =
          (posted.first["faces"] as List<dynamic>).first as Map<String, dynamic>;
      expect(marked["state"], "UNDECIDED");
      var box = firstBox(posted);
      expect(box[0], closeTo(288 / 800, 0.001));
      expect(box[1], closeTo((438 - 100) / 400, 0.001));
      expect(box[2], closeTo(24 / 800, 0.001));
      expect(box[3], closeTo(24 / 400, 0.001));
    });

    testWidgets('a tap on a face while the tool is on opens its sheet',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);
      await markingTool(tester);

      // Inside face 1, (400, 180) to (560, 300).
      await tester.tapAt(const Offset(480, 240));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("face-decision")), findsOneWidget);
      expect(posted, isEmpty);
      await tester.tap(find.byKey(const Key("face-decision-confirm")));
      await tester.pumpAndSettle();
      expect(
          ((posted.single["faces"] as List<dynamic>).single
              as Map<String, dynamic>)["face"],
          1);
    });

    testWidgets('a rectangle too small is said, never dropped silently',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);
      await markingTool(tester);

      // A drag, long enough to be one, and three pixels high.
      await drag(tester, const Offset(300, 440), const Offset(360, 443));

      expect(posted, isEmpty);
      expect(find.byKey(const Key("face-marking-too-small")), findsOneWidget);
      expect(find.text(testL10n.viewerMarkFaceTooSmall), findsOneWidget);
      expect(find.byKey(const Key("persons-chooser")), findsNothing);
    });

    testWidgets('a mouse drag too small is said as well', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);
      await markingTool(tester);

      var gesture = await tester.startGesture(
        const Offset(300, 440),
        kind: PointerDeviceKind.mouse,
      );
      await gesture.moveTo(const Offset(303, 443));
      await tester.pump();
      await gesture.moveTo(const Offset(305, 445));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();

      expect(posted, isEmpty);
      expect(find.byKey(const Key("face-marking-too-small")), findsOneWidget);
    });
  });

  group('a region is a region (#155)', () {
    /// The photograph with a face marked by hand and confirmed: a tag no
    /// detection matches, answered without a cluster.
    ImagePart handMarked({bool confirmed = true}) {
      var image = facesImage();
      image.faces = [
        ...image.faces,
        faceOf(3,
            x: 0.3,
            y: 0.7,
            w: 0.1,
            h: 0.2,
            person: confirmed ? "p-bob" : "",
            confirmed: confirmed),
      ];
      return image;
    }

    testWidgets('a hand-marked face offers forget and not-a-face',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted), image: handMarked());
      await enterMode(tester);

      await tapFace(tester, 3);
      expect(find.byKey(const Key("face-decision-forget")), findsOneWidget);
      expect(find.byKey(const Key("face-decision-not-a-face")), findsOneWidget);
      await tester.tap(find.byKey(const Key("face-decision-forget")));
      await tester.pumpAndSettle();

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["face"], 3);
      expect(only["state"], "UNDECIDED");
    });

    testWidgets('an undecided region is decided about like a detection',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: facesClient(posted),
        image: handMarked(confirmed: false),
      );
      await enterMode(tester);

      await tapFace(tester, 3);
      // As on face 2, the detection nobody decided about.
      expect(find.byKey(const Key("face-decision-forget")), findsNothing);
      expect(find.byKey(const Key("face-decision-name")), findsOneWidget);
      await tester.tap(find.byKey(const Key("face-decision-not-a-face")));
      await tester.pumpAndSettle();

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["face"], 3);
      expect(only["state"], "NOT_A_FACE");
    });
  });

  group('what is refused', () {
    testWidgets('nothing is posted while the app is offline', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted), offline: true);
      await enterMode(tester);

      await tapFace(tester, 1);
      await tester.tap(find.byKey(const Key("face-decision-confirm")));
      await tester.pumpAndSettle();

      expect(posted, isEmpty);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
    });

    testWidgets('a refusal is said in the words of the server',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: facesClient(
          posted,
          tagStatus: 403,
          tagBody: '["ErrorInfo", {"message": "Naming needs the edit right."}]',
        ),
      );
      await enterMode(tester);

      await tapFace(tester, 1);
      await tester.tap(find.byKey(const Key("face-decision-confirm")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("face-tag-failed")), findsOneWidget);
      expect(find.text("Naming needs the edit right."), findsOneWidget);
      // Nothing moved: the suggestion is still a suggestion.
      expect(
        find.text(testL10n.personsSuggestedHeading("Anna")),
        findsOneWidget,
      );
    });
  });

  group('living in the mode', () {
    testWidgets('paging keeps it on', (tester) async {
      var first = facesImage();
      var second = viewerImagePart("b.jpg");
      second.faces = [
        faceOf(0, x: 0.2, y: 0.2, w: 0.2, h: 0.2, person: "p-bob",
            confirmed: true),
      ];
      await pumpEditor(
        tester,
        client: facesClient([]),
        image: first,
        album: [first, second],
      );
      await enterMode(tester);

      await tester.tap(find.byTooltip(testL10n.nextImage));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("viewer-edit-persons-done")), findsOneWidget);
      expect(find.byKey(const Key("face-box-0")), findsOneWidget);
      expect(find.text("Bob"), findsOneWidget);
    });

    testWidgets('Done gives the picture back to the gestures', (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      await enterMode(tester);
      expect(find.byKey(const Key("face-box-0")), findsOneWidget);

      await tester.tap(find.byKey(const Key("viewer-edit-persons-done")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("face-box-0")), findsNothing);
      expect(find.byKey(const Key("viewer-edit-persons-done")), findsNothing);
      // And the hover regions of issue #145 are back.
      expect(find.byKey(const Key("face-hover-0")), findsOneWidget);
    });

    testWidgets('Escape leaves it too', (tester) async {
      await pumpEditor(tester, client: facesClient([]));
      await enterMode(tester);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("face-box-0")), findsNothing);
    });
  });
}
