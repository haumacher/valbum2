/// Naming the faces of a photograph in the viewer itself (issue #147).
///
/// The edit-persons mode: every face of the picture is marked, a name stands
/// over the ones that have one, tapping a box decides about that face, and a
/// rectangle drawn on the picture marks a face the detector missed. Every
/// decision is posted at once — there is no buffer and no Save here, the
/// doctrine of the inbox and of issue #121's properties.
library;

import 'dart:convert';

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

/// A client answering the register and every tagging, recording both.
VAlbumClient facesClient(
  List<Map<String, dynamic>> posted, {
  String people = register,
  String answer = confirmedAlbum,
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
          posted.add(jsonDecode(request.body) as Map<String, dynamic>);
          if (tagStatus >= 300) {
            return http.Response(tagBody, tagStatus);
          }
          return http.Response(
            answer,
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

    testWidgets('is not offered without the edit right', (tester) async {
      await pumpEditor(
        tester,
        client: facesClient([]),
        rights: const ["view", "download"],
      );
      expect(find.byKey(const Key("viewer-menu")), findsNothing);
    });

    testWidgets('is not offered where the space looks for no face',
        (tester) async {
      await pumpEditor(tester, client: facesClient([]), faces: false);
      expect(find.byKey(const Key("viewer-menu")), findsNothing);
    });

    testWidgets('is never offered in a share session', (tester) async {
      await pumpEditor(
        tester,
        client: facesClient([]),
        share: viewerShareSession(rights: const ["view", "edit"]),
      );
      expect(find.byKey(const Key("viewer-menu")), findsNothing);
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
    /// Draws a rectangle on the picture and names it [person].
    Future<void> draw(
      WidgetTester tester,
      Offset from,
      Offset to, {
      String person = "p-bob",
    }) async {
      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();
      var gesture = await tester.startGesture(from);
      await gesture.moveTo(Offset(from.dx + 4, from.dy + 4));
      await tester.pump();
      await gesture.moveTo(to);
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(Key("persons-pick-$person")));
      await tester.pumpAndSettle();
    }

    testWidgets('sends the rectangle as a fraction of the picture',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      // The picture covers (0, 100) to (800, 500): (400, 260) to (560, 380) is
      // (0.5, 0.4, 0.2, 0.3) of it.
      await draw(tester, const Offset(400, 260), const Offset(560, 380));

      var only = assignmentsOf(posted).first as Map<String, dynamic>;
      expect(only["x"], closeTo(0.5, 0.01));
      expect(only["y"], closeTo(0.4, 0.01));
      expect(only["w"], closeTo(0.2, 0.01));
      expect(only["h"], closeTo(0.3, 0.01));
      expect(only["person"], "p-bob");
      expect(only["state"], "CONFIRMED");
    });

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

        await draw(tester, turned.$2, turned.$3);

        var only = assignmentsOf(posted).first as Map<String, dynamic>;
        expect(only["x"], closeTo(0.5, 0.01));
        expect(only["y"], closeTo(0.4, 0.01));
        expect(only["w"], closeTo(0.2, 0.01));
        expect(only["h"], closeTo(0.3, 0.01));
      });
    }

    testWidgets('a cancelled chooser posts nothing', (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();
      var gesture = await tester.startGesture(const Offset(400, 260));
      await gesture.moveTo(const Offset(560, 380));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("persons-chooser-cancel")));
      await tester.pumpAndSettle();

      expect(posted, isEmpty);
    });

    testWidgets('a rectangle too small to see is a tap that slipped',
        (tester) async {
      var posted = <Map<String, dynamic>>[];
      await pumpEditor(tester, client: facesClient(posted));
      await enterMode(tester);

      await tester.tap(find.byKey(const Key("viewer-mark-face")));
      await tester.pumpAndSettle();
      var gesture = await tester.startGesture(const Offset(400, 260));
      await gesture.moveTo(const Offset(403, 262));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("persons-chooser")), findsNothing);
      expect(posted, isEmpty);
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
