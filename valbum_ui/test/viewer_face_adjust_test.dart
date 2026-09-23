/// Moving and resizing a face box in the viewer (issue #157).
///
/// In the edit-persons mode a face box is dragged by its inside to move it
/// and by one of its four corner handles to resize it — at once by a mouse,
/// after a long press by a finger. Letting go posts one `adjust-faces` with
/// the face's index and its new box in the frame the faces are answered in;
/// a drag too short is a tap and opens the face's sheet as before.
library;

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';
import 'viewer_edit_persons_test.dart'
    show albumWithFaces, enterMode, faceJson, facesImage, pumpEditor, register;

/// A client answering the register and every adjustment, recording them.
VAlbumClient adjustClient(
  List<Map<String, dynamic>> posted, {
  String? answer,
  int status = 200,
  String body = "",
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        if (request.url.query.contains("type=people")) {
          return http.Response(register, 200,
              headers: {"content-type": "application/json; charset=utf-8"});
        }
        if (request.url.query.contains("action=adjust-faces")) {
          var sent = jsonDecode(request.body) as Map<String, dynamic>;
          posted.add(sent);
          if (status >= 300) {
            return http.Response(body, status);
          }
          var only =
              (sent["faces"] as List<dynamic>).first as Map<String, dynamic>;
          return http.Response(
            answer ??
                albumWithFaces([
                  faceJson(
                      0,
                      [
                        (only["x"] as num).toDouble(),
                        (only["y"] as num).toDouble(),
                        (only["w"] as num).toDouble(),
                        (only["h"] as num).toDouble(),
                      ],
                      person: "p-anna",
                      confirmed: true,
                      state: "CONFIRMED"),
                ]),
            200,
            headers: {"content-type": "application/json; charset=utf-8"},
          );
        }
        if (request.url.query.contains("action=tag-faces")) {
          fail("An adjustment is never posted as a tagging.");
        }
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(transparentPixelPng, 200,
              headers: {"content-type": "image/png"});
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// The one posted assignment.
Map<String, dynamic> postedAssignment(List<Map<String, dynamic>> posted) {
  expect(posted, hasLength(1));
  var faces = posted.first["faces"] as List<dynamic>;
  expect(faces, hasLength(1));
  return faces.first as Map<String, dynamic>;
}

/// The box of the given assignment.
List<double> boxOf(Map<String, dynamic> assignment) => [
      (assignment["x"] as num).toDouble(),
      (assignment["y"] as num).toDouble(),
      (assignment["w"] as num).toDouble(),
      (assignment["h"] as num).toDouble(),
    ];

/// Drags from [from] by [by] with the given pointer, in small steps.
Future<void> dragBy(
  WidgetTester tester,
  Offset from,
  Offset by, {
  PointerDeviceKind kind = PointerDeviceKind.mouse,
  bool hold = false,
}) async {
  var gesture = await tester.startGesture(from, kind: kind);
  if (hold) {
    // The long press that tells a finger's drag of a box from a swipe.
    await tester.pump(const Duration(milliseconds: 700));
  }
  for (var n = 1; n <= 10; n++) {
    await gesture.moveTo(from + by * (n / 10));
    await tester.pump();
  }
  await gesture.up();
  await tester.pumpAndSettle();
}

Rect boxRect(WidgetTester tester, int index) =>
    tester.getRect(find.byKey(Key("face-box-$index")));

void main() {
  setUp(withEmptyImageCache);

  // 2000 x 1000 in a page of 800 x 600 is fitted at 0.4 and centred
  // vertically: the picture covers (0, 100) to (800, 500), and face 0,
  // (0.1, 0.2, 0.2, 0.3), is (80, 180) to (240, 300).
  const faceZero = Rect.fromLTWH(80, 180, 160, 120);

  group('by a mouse', () {
    testWidgets('dragging the inside moves the box', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(40, 20));

      var assignment = postedAssignment(posted);
      expect(assignment["image"], "a.jpg");
      expect(assignment["face"], 0);
      // A confirmation travels along with its person.
      expect(assignment["person"], "p-anna");
      expect(assignment["state"], "CONFIRMED");
      var box = boxOf(assignment);
      expect(box[0], closeTo(0.15, 0.001));
      expect(box[1], closeTo(0.25, 0.001));
      expect(box[2], closeTo(0.2, 0.001));
      expect(box[3], closeTo(0.3, 0.001));
      // The answered part replaces the shown one: the box stands where the
      // server has it now.
      expect(
        boxRect(tester, 0),
        rectMoreOrLessEquals(faceZero.shift(const Offset(40, 20)),
            epsilon: 0.01),
      );
    });

    testWidgets('dragging a corner resizes the box', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);
      expect(
          find.byKey(const Key("face-handle-0-bottom-right")), findsOneWidget);

      await dragBy(tester, faceZero.bottomRight, const Offset(40, 40));

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0.1, 0.001));
      expect(box[1], closeTo(0.2, 0.001));
      expect(box[2], closeTo(0.25, 0.001));
      expect(box[3], closeTo(0.4, 0.001));
    });

    testWidgets('the top left corner moves alone', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.topLeft, const Offset(-40, -40));

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0.05, 0.001));
      expect(box[1], closeTo(0.1, 0.001));
      expect(box[2], closeTo(0.25, 0.001));
      expect(box[3], closeTo(0.4, 0.001));
    });

    testWidgets('a face nobody decided about is sent undecided',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      // Face 1 is a suggestion of issue #127, which is no decision: (0.5,
      // 0.2, 0.2, 0.3) is (400, 180) to (560, 300).
      await dragBy(tester, const Offset(480, 240), const Offset(-20, 0));

      var assignment = postedAssignment(posted);
      expect(assignment["face"], 1);
      expect(assignment["person"], "");
      expect(assignment["state"], "UNDECIDED");
      expect(boxOf(assignment)[0], closeTo(0.475, 0.001));
    });

    testWidgets('on a picture turned a quarter the box is turned back',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: adjustClient(posted),
        image: facesImage(orientation: Orientation.rotR),
      );
      await enterMode(tester);
      // As the viewer tests pin it: face 0 is (400, 60) to (490, 180) there.
      const turned = Rect.fromLTWH(400, 60, 90, 120);
      expect(boxRect(tester, 0), rectMoreOrLessEquals(turned, epsilon: 0.01));

      // Right on the screen is up in the picture as shown (y), down on the
      // screen is right in it (x), at 0.3.
      await dragBy(tester, turned.center, const Offset(30, 60));

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0.2, 0.001));
      expect(box[1], closeTo(0.1, 0.001));
      expect(box[2], closeTo(0.2, 0.001));
      expect(box[3], closeTo(0.3, 0.001));
    });

    testWidgets('and resized there by a corner', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: adjustClient(posted),
        image: facesImage(orientation: Orientation.rotR),
      );
      await enterMode(tester);
      const turned = Rect.fromLTWH(400, 60, 90, 120);

      // The bottom right corner of the screen is the bottom left one of the
      // picture as shown: 30 more to the right is 0.05 less y and 0.05 more
      // height, 60 further down is 0.1 more width.
      await dragBy(tester, turned.bottomRight, const Offset(30, 60));

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0.1, 0.001));
      expect(box[1], closeTo(0.1, 0.001));
      expect(box[2], closeTo(0.3, 0.001));
      expect(box[3], closeTo(0.4, 0.001));
    });

    testWidgets('a drag too short is a tap', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(3, 1));

      expect(posted, isEmpty);
      expect(find.byKey(const Key("face-decision")), findsOneWidget);
    });

    testWidgets('a box stays inside the picture', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(-200, -200));

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0, 0.001));
      expect(box[1], closeTo(0, 0.001));
      expect(box[2], closeTo(0.2, 0.001));
      expect(box[3], closeTo(0.3, 0.001));
    });
  });

  group('by a finger', () {
    testWidgets('a long press, then a drag, moves the box', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(40, 20),
          kind: PointerDeviceKind.touch, hold: true);

      var box = boxOf(postedAssignment(posted));
      expect(box[0], closeTo(0.15, 0.001));
      expect(box[1], closeTo(0.25, 0.001));
    });

    testWidgets('a swipe without the long press moves nothing', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(40, 20),
          kind: PointerDeviceKind.touch);

      expect(posted, isEmpty);
      expect(boxRect(tester, 0), rectMoreOrLessEquals(faceZero, epsilon: 0.01));
    });

    testWidgets('a tap still opens the sheet', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);

      await tester.tap(find.byKey(const Key("face-box-0")));
      await tester.pumpAndSettle();

      expect(posted, isEmpty);
      expect(find.byKey(const Key("face-decision")), findsOneWidget);
    });
  });

  group('what is refused', () {
    testWidgets('nothing is posted while the app is offline', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted), offline: true);
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(40, 20));

      expect(posted, isEmpty);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(boxRect(tester, 0), rectMoreOrLessEquals(faceZero, epsilon: 0.01));
    });

    testWidgets('a refusal is said and the box snaps back', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(
        tester,
        client: adjustClient(
          posted,
          status: 400,
          body: '["ErrorInfo", {"message": "The marked face is not a place '
              'on \'a.jpg\'."}]',
        ),
      );
      await enterMode(tester);

      await dragBy(tester, faceZero.center, const Offset(40, 20));

      expect(posted, hasLength(1));
      expect(find.byKey(const Key("face-tag-failed")), findsOneWidget);
      expect(find.text("The marked face is not a place on 'a.jpg'."),
          findsOneWidget);
      expect(boxRect(tester, 0), rectMoreOrLessEquals(faceZero, epsilon: 0.01));
    });
  });

  group('the marking tool', () {
    testWidgets('takes the drags while it is on, and shows no handles',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: adjustClient(posted));
      await enterMode(tester);
      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("face-handle-0-top-left")), findsNothing);
    });
  });

  group('speaks the language of the device', () {
    testWidgets('the handles are described in German', (tester) async {
      var semantics = tester.ensureSemantics();
      await pumpEditor(tester,
          client: adjustClient([]), locale: const Locale("de"));
      await enterMode(tester);

      var de = l10nOf(const Locale("de"));
      expect(find.bySemanticsLabel(de.viewerAdjustFaceHint), findsWidgets);
      expect(
          find.bySemanticsLabel(testL10n.viewerAdjustFaceHint), findsNothing);
      semantics.dispose();
    });
  });

  group('adjustedRect', () {
    const bounds = Rect.fromLTWH(0, 0, 100, 100);
    const start = Rect.fromLTWH(20, 20, 30, 30);

    test('a corner never crosses the opposite one', () {
      var rect = adjustedRect(
          start, FaceCorner.bottomRight, const Offset(-100, -100), bounds);
      expect(rect.left, 20);
      expect(rect.top, 20);
      expect(rect.width, 8);
      expect(rect.height, 8);
    });

    test('a corner stops at the edge of the picture', () {
      var rect = adjustedRect(
          start, FaceCorner.topLeft, const Offset(-50, -50), bounds);
      expect(rect, const Rect.fromLTRB(0, 0, 50, 50));
    });

    test('a move keeps the size', () {
      var rect = adjustedRect(start, null, const Offset(100, 0), bounds);
      expect(rect, const Rect.fromLTWH(70, 20, 30, 30));
    });
  });
}
