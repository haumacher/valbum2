/// The crop editor of a photograph (issue #212), driven by the pointer: a
/// rectangle drawn anew, moved and resized under each constraint, the
/// portrait/landscape toggle, the rectangle kept inside the picture, and the
/// ways out — a tap inside, the Apply button and `Enter` apply, Cancel and
/// `Escape` leave without a change, Reset takes the crop away.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/crop_editor.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';
import 'util/l10n.dart';

/// What the editor answered, and whether it answered at all.
class Outcome {
  bool closed = false;
  CropEditorResult? result;
}

VAlbumClient editorClient() => VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails(
        (request) async => http.Response("No such resource", 404),
      )),
    );

/// A 2000 x 1000 photograph (2:1), cropped where [crop] is given.
ImagePart photo({Crop? crop, Orientation orientation = Orientation.identity}) =>
    ImagePart(
      name: "a.jpg",
      width: 2000,
      height: 1000,
      orientation: orientation,
      crop: crop,
    );

Future<Outcome> pumpEditor(
  WidgetTester tester,
  ImagePart image, {
  Size size = const Size(1000, 800),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  var outcome = Outcome();
  await tester.pumpWidget(localizedApp(Builder(
    builder: (context) => Scaffold(
      body: TextButton(
        child: const Text("open"),
        onPressed: () async {
          outcome.result = await openCropEditor(
            context,
            client: editorClient(),
            imageUrl: "http://server/valbum/data/album/a.jpg",
            image: image,
          );
          outcome.closed = true;
        },
      ),
    ),
  )));
  await tester.tap(find.text("open"));
  await tester.pumpAndSettle();
  expect(find.byKey(const Key("crop-editor")), findsOneWidget);
  return outcome;
}

/// The picture on the screen.
Rect pictureRect(WidgetTester tester) =>
    tester.getRect(find.byKey(const Key("crop-area")));

/// The global position of a normalised point of the picture.
Offset at(WidgetTester tester, double x, double y) {
  var rect = pictureRect(tester);
  return Offset(rect.left + x * rect.width, rect.top + y * rect.height);
}

/// The rectangle the editor holds now.
Rect current(WidgetTester tester) =>
    tester.state<CropEditorState>(find.byType(CropEditor)).rect;

/// Drags with the mouse from one normalised point to another, in steps.
Future<void> drag(
  WidgetTester tester,
  Offset from,
  Offset to, {
  PointerDeviceKind kind = PointerDeviceKind.mouse,
}) async {
  var start = at(tester, from.dx, from.dy);
  var end = at(tester, to.dx, to.dy);
  var gesture = await tester.startGesture(start, kind: kind);
  for (var i = 1; i <= 5; i++) {
    await gesture.moveTo(Offset.lerp(start, end, i / 5)!);
    await tester.pump();
  }
  await gesture.up();
  await tester.pumpAndSettle();
}

void expectRect(Rect actual, Rect expected, {double tolerance = 0.01}) {
  expect(actual.left, closeTo(expected.left, tolerance), reason: "left");
  expect(actual.top, closeTo(expected.top, tolerance), reason: "top");
  expect(actual.width, closeTo(expected.width, tolerance), reason: "width");
  expect(actual.height, closeTo(expected.height, tolerance), reason: "height");
}

/// The ratio of the rectangle in pixels of the 2:1 picture.
double pixelRatio(Rect rect) => rect.width * 2 / rect.height;

void main() {
  testWidgets("opens on the whole picture, at the image ratio", (tester) async {
    await pumpEditor(tester, photo());
    expectRect(current(tester), const Rect.fromLTWH(0, 0, 1, 1));
    var chip =
        tester.widget<ChoiceChip>(find.byKey(const Key("crop-aspect-image")));
    expect(chip.selected, isTrue);
    expect(find.text(testL10n.cropNote), findsOneWidget);
    expect(find.byKey(const Key("crop-reset")), findsNothing,
        reason: "nothing to take away");
    // The picture is shown fitted at its own aspect.
    var rect = pictureRect(tester);
    expect(rect.width / rect.height, closeTo(2, 0.01));
  });

  testWidgets("a drag on a whole frame draws a new one at the image ratio",
      (tester) async {
    await pumpEditor(tester, photo());
    await drag(tester, const Offset(0.1, 0.1), const Offset(0.5, 0.9));
    var rect = current(tester);
    expect(rect.left, closeTo(0.1, 0.01));
    expect(rect.top, closeTo(0.1, 0.01));
    expect(pixelRatio(rect), closeTo(2, 1e-6), reason: "the picture's ratio");
  });

  testWidgets("drawn freely, moved and kept inside", (tester) async {
    await pumpEditor(tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    // Outside the frame: a new rectangle from the press point.
    await drag(tester, const Offset(0.1, 0.1), const Offset(0.4, 0.5));
    expectRect(current(tester), const Rect.fromLTRB(0.1, 0.1, 0.4, 0.5));
    // Inside: moved.
    await drag(tester, const Offset(0.25, 0.3), const Offset(0.35, 0.4));
    expectRect(current(tester), const Rect.fromLTRB(0.2, 0.2, 0.5, 0.6));
    // Far beyond the picture: stopped at its edge.
    await drag(tester, const Offset(0.3, 0.4), const Offset(1.5, 1.5));
    expectRect(current(tester), const Rect.fromLTRB(0.7, 0.6, 1, 1));
  });

  testWidgets("a corner resizes under 1:1, an edge freely", (tester) async {
    await pumpEditor(tester, photo(crop: Crop(x: 0.2, y: 0.2, w: 0.2, h: 0.4)));
    await tester.tap(find.byKey(const Key("crop-aspect-square")));
    await tester.pumpAndSettle();
    var square = current(tester);
    expect(pixelRatio(square), closeTo(1, 1e-6));
    var centre = square.center;

    await drag(tester, square.bottomRight, const Offset(0.6, 0.9));
    var resized = current(tester);
    expect(resized.left, closeTo(square.left, 1e-6), reason: "anchored");
    expect(resized.top, closeTo(square.top, 1e-6), reason: "anchored");
    expect(pixelRatio(resized), closeTo(1, 1e-6));
    expect(resized.width, greaterThan(square.width));
    expect(centre, isNot(resized.center));

    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    var before = current(tester);
    await drag(tester, before.centerRight, Offset(0.95, before.center.dy));
    var stretched = current(tester);
    expect(stretched.left, closeTo(before.left, 1e-6));
    expect(stretched.right, closeTo(0.95, 0.01));
    expect(stretched.height, closeTo(before.height, 1e-6));
  });

  testWidgets("a finger takes a handle from further away", (tester) async {
    await pumpEditor(tester, photo(crop: Crop(x: 0.2, y: 0.2, w: 0.4, h: 0.4)));
    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    var picture = pictureRect(tester);
    // 20 logical pixels beside the corner: outside a mouse's reach, inside a
    // finger's.
    var corner = at(tester, 0.6, 0.6) + const Offset(14, 14);
    var target = at(tester, 0.8, 0.8) + const Offset(14, 14);
    var gesture =
        await tester.startGesture(corner, kind: PointerDeviceKind.touch);
    for (var i = 1; i <= 5; i++) {
      await gesture.moveTo(Offset.lerp(corner, target, i / 5)!);
      await tester.pump();
    }
    await gesture.up();
    await tester.pumpAndSettle();
    var rect = current(tester);
    expect(rect.left, closeTo(0.2, 1e-6));
    expect(rect.right, closeTo(0.8, 14 / picture.width + 0.01));
  });

  testWidgets("the toggle turns a fixed ratio", (tester) async {
    await pumpEditor(tester, photo(crop: Crop(x: 0.2, y: 0.2, w: 0.4, h: 0.4)));
    var toggle = find.byKey(const Key("crop-orientation"));
    await tester.tap(find.byKey(const Key("crop-aspect-fourThree")));
    await tester.pumpAndSettle();
    expect(pixelRatio(current(tester)), closeTo(4 / 3, 1e-6));
    expect(find.text("4:3"), findsOneWidget);
    await tester.tap(toggle);
    await tester.pumpAndSettle();
    expect(pixelRatio(current(tester)), closeTo(3 / 4, 1e-6));
    expect(find.text("3:4"), findsOneWidget);

    await tester.tap(find.byKey(const Key("crop-aspect-sixteenNine")));
    await tester.pumpAndSettle();
    expect(pixelRatio(current(tester)), closeTo(9 / 16, 1e-6));
    await tester.tap(find.byKey(const Key("crop-aspect-threeTwo")));
    await tester.pumpAndSettle();
    expect(pixelRatio(current(tester)), closeTo(2 / 3, 1e-6));
    await tester.tap(find.byKey(const Key("crop-aspect-fiveFour")));
    await tester.pumpAndSettle();
    expect(pixelRatio(current(tester)), closeTo(4 / 5, 1e-6));

    // Free and 1:1 have no orientation to turn.
    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    expect(tester.widget<IconButton>(toggle).onPressed, isNull);
    // Everything stays inside the picture.
    var rect = current(tester);
    expect(rect.left, greaterThanOrEqualTo(0));
    expect(rect.right, lessThanOrEqualTo(1 + 1e-9));
    expect(rect.bottom, lessThanOrEqualTo(1 + 1e-9));
  });

  testWidgets("a tap inside the frame applies", (tester) async {
    var outcome = await pumpEditor(
        tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    await drag(tester, const Offset(0.1, 0.2), const Offset(0.5, 0.6));
    // A tap outside does nothing.
    await tester.tapAt(at(tester, 0.8, 0.1));
    await tester.pumpAndSettle();
    expect(outcome.closed, isFalse);
    await tester.tapAt(at(tester, 0.3, 0.4));
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    var crop = outcome.result!.crop!;
    expect(crop.x, closeTo(0.1, 0.01));
    expect(crop.y, closeTo(0.2, 0.01));
    expect(crop.w, closeTo(0.4, 0.01));
    expect(crop.h, closeTo(0.4, 0.01));
  });

  testWidgets("the Apply button and Enter apply", (tester) async {
    var outcome = await pumpEditor(
        tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await tester.tap(find.byKey(const Key("crop-apply")));
    await tester.pumpAndSettle();
    expect(outcome.result!.crop!.x, closeTo(0.6, 1e-9));

    outcome = await pumpEditor(
        tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    expect(outcome.result!.crop!.w, closeTo(0.3, 1e-9));
  });

  testWidgets("a frame of the whole picture is applied as no crop",
      (tester) async {
    var outcome = await pumpEditor(tester, photo());
    await tester.tap(find.byKey(const Key("crop-apply")));
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    expect(outcome.result, isNotNull);
    expect(outcome.result!.crop, isNull);
  });

  testWidgets("Cancel and Escape leave without a change", (tester) async {
    var outcome = await pumpEditor(
        tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await drag(tester, const Offset(0.1, 0.1), const Offset(0.3, 0.3));
    await tester.tap(find.byKey(const Key("crop-cancel")));
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    expect(outcome.result, isNull);

    outcome = await pumpEditor(tester, photo());
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    expect(outcome.result, isNull);
  });

  testWidgets("Reset takes the crop away", (tester) async {
    var outcome = await pumpEditor(
        tester, photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)));
    await tester.tap(find.byKey(const Key("crop-reset")));
    await tester.pumpAndSettle();
    expect(outcome.closed, isTrue);
    expect(outcome.result, isNotNull);
    expect(outcome.result!.crop, isNull);
  });

  testWidgets("a turned photograph is edited upright", (tester) async {
    // 2000 x 1000 turned a quarter: shown 1000 x 2000.
    await pumpEditor(tester, photo(orientation: Orientation.rotL));
    var rect = pictureRect(tester);
    expect(rect.height / rect.width, closeTo(2, 0.01));
    await drag(tester, const Offset(0.1, 0.1), const Offset(0.9, 0.3));
    // Under the image ratio (1:2, portrait) the frame is portrait.
    var drawn = current(tester);
    expect(drawn.width * rect.width / (drawn.height * rect.height),
        closeTo(0.5, 1e-6));
  });

  testWidgets("works at phone size", (tester) async {
    var outcome = await pumpEditor(
      tester,
      photo(crop: Crop(x: 0.6, y: 0.6, w: 0.3, h: 0.3)),
      size: const Size(390, 760),
    );
    expect(tester.takeException(), isNull);
    var picture = pictureRect(tester);
    expect(picture.width, lessThanOrEqualTo(390));
    await tester.tap(find.byKey(const Key("crop-aspect-free")));
    await tester.pumpAndSettle();
    await drag(tester, const Offset(0.1, 0.1), const Offset(0.4, 0.5),
        kind: PointerDeviceKind.touch);
    expectRect(current(tester), const Rect.fromLTRB(0.1, 0.1, 0.4, 0.5));
    await tester.tap(find.byKey(const Key("crop-apply")));
    await tester.pumpAndSettle();
    expect(outcome.result!.crop!.x, closeTo(0.1, 0.01));
  });
}
