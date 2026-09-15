import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Issue #78: an album fed from a phone and a camera is gathered device by
// device. `ImagePart.camera` is the label the server derived from the EXIF
// make and model; an empty label matches nothing.

const String canon = "Canon EOS 5D";
const String phone = "SAMSUNG SM-G991B";

/// An image of the given name, taken at [date] by [camera].
ImagePart image(
  String name,
  int date, {
  String camera = "",
  ImageKind kind = ImageKind.image,
}) =>
    ImagePart(
      name: name,
      date: date,
      camera: camera,
      kind: kind,
      width: 2048,
      height: 1536,
    );

List<String> namesOf(Iterable<AlbumPart> parts) =>
    [for (var part in parts) (part as ImagePart).name]..sort();

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The tool with the given icon on the tile of [name].
Finder tool(String name, IconData icon) =>
    find.descendant(of: tile(name), matching: find.byIcon(icon));

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Adds a tile to the selection with a ctrl-click.
Future<void> ctrlTapTile(WidgetTester tester, String name) async {
  await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
  await tapTile(tester, name);
  await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
  await tester.pumpAndSettle();
}

/// Loads the given album and enters the edit mode by a long press on the tile
/// of [select], which is the tile selected afterwards.
///
/// The long press is what selects: a plain tap on the only selected tile
/// clears the selection again, see [AlbumContentState.handleTap].
Future<void> pumpEditMode(
  WidgetTester tester,
  String albumJson, {
  required String select,
}) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson)));
  await tester.pumpAndSettle();
  await tester.longPress(tile(select));
  await tester.pumpAndSettle();
}

/// An album of two cameras, one of the phone's images inside a group and one
/// video that says nothing about where it came from.
String twoCameras() => AlbumInfo(
      path: "",
      title: "Zwei Kameras",
      parts: [
        image("cam1.jpg", 1000, camera: canon),
        image("phone1.jpg", 2000, camera: phone),
        ImageGroup(
          images: [
            image("cam2.jpg", 3000, camera: canon),
            image("phone2.jpg", 3100, camera: phone),
          ],
          representative: 0,
        ),
        image("clip.mp4", 4000, kind: ImageKind.video),
      ],
    ).toString();

void main() {
  group('the images of one camera', () {
    late ImagePart cam1, phone1, cam2, phone2, clip;
    late AlbumInfo info;

    setUp(() {
      cam1 = image("cam1.jpg", 1000, camera: canon);
      phone1 = image("phone1.jpg", 2000, camera: phone);
      cam2 = image("cam2.jpg", 3000, camera: canon);
      phone2 = image("phone2.jpg", 3100, camera: phone);
      clip = image("clip.mp4", 4000, kind: ImageKind.video);
      info = AlbumInfo(parts: [
        Heading(text: "Am Morgen"),
        cam1,
        phone1,
        ImageGroup(images: [cam2, phone2], representative: 0),
        clip,
      ]);
      AlbumInitializer().init(info);
    });

    test('are found across the album and inside groups', () {
      // The reference itself is part of the answer, and the grouped image
      // joins as the image, not as its group.
      expect(sameCamera(info, cam1), {cam1, cam2});
      expect(namesOf(sameCamera(info, cam1)), ["cam1.jpg", "cam2.jpg"]);
    });

    test('exclude every other label', () {
      expect(sameCamera(info, phone1), {phone1, phone2});
      // The two answers do not overlap: no image belongs to two cameras.
      expect(sameCamera(info, phone1).intersection(sameCamera(info, cam1)),
          isEmpty);
    });

    test('are nothing at all for an image without a label', () {
      // A video without make and model in its container, and an image of an
      // album written before the field existed: an empty label never matches
      // another empty one.
      expect(sameCamera(info, clip), isEmpty);
      expect(sameCamera(info, image("old.jpg", 5000)), isEmpty);
    });

    test('are found for a reference that is itself inside a group', () {
      expect(sameCamera(info, cam2), {cam1, cam2});
    });
  });

  group('the "Select all from this camera" tile tool', () {
    testWidgets('adds the images of that camera to the selection',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, twoCameras(), select: "cam1.jpg");
        expect(albumState(tester).selection, hasLength(1));

        await tester.tap(tool("cam1.jpg", Icons.photo_camera));
        await tester.pumpAndSettle();

        // The grouped image of the same camera joined as the image.
        expect(namesOf(albumState(tester).selection), ["cam1.jpg", "cam2.jpg"]);

        // A second camera is gathered on top: the action adds, it does not
        // replace what is selected.
        await ctrlTapTile(tester, "phone1.jpg");
        await tester.tap(tool("phone1.jpg", Icons.photo_camera));
        await tester.pumpAndSettle();
        expect(namesOf(albumState(tester).selection),
            ["cam1.jpg", "cam2.jpg", "phone1.jpg", "phone2.jpg"]);
      });
    });

    testWidgets('is not offered where the camera is unknown', (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, twoCameras(), select: "clip.mp4");

        // The video says nothing about where it came from, so there is
        // nothing to gather — the tool is not offered rather than refused.
        expect(tool("clip.mp4", Icons.photo_camera), findsNothing);
        // The other tools of the tile are there.
        expect(tool("clip.mp4", Icons.more_time), findsOneWidget);
      });
    });

    testWidgets('says so when the camera took no other image', (tester) async {
      var lonely = AlbumInfo(
        path: "",
        title: "Eine Kamera",
        parts: [
          image("only.jpg", 1000, camera: canon),
          image("phone1.jpg", 2000, camera: phone),
        ],
      ).toString();

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, lonely, select: "only.jpg");

        await tester.tap(tool("only.jpg", Icons.photo_camera));
        await tester.pumpAndSettle();

        expect(find.text("No other image from this camera"), findsOneWidget);
        expect(namesOf(albumState(tester).selection), ["only.jpg"]);
      });
    });

    testWidgets('names the camera in the image properties', (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, twoCameras(), select: "cam1.jpg");

        await tester.tap(tool("cam1.jpg", Icons.notes));
        await tester.pumpAndSettle();

        expect(find.text("Bildeigenschaften"), findsOneWidget);
        expect(find.byKey(const Key("properties-details")), findsOneWidget);
        expect(find.text("Kamera: $canon"), findsOneWidget);
        // Beneath the recording time the album sorts it by.
        expect(find.textContaining("Aufnahmezeit: "), findsOneWidget);
      });
    });

    testWidgets('says nothing about a camera it does not know', (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, twoCameras(), select: "clip.mp4");

        await tester.tap(tool("clip.mp4", Icons.notes));
        await tester.pumpAndSettle();

        expect(find.textContaining("Kamera: "), findsNothing);
        expect(find.textContaining("Aufnahmezeit: "), findsOneWidget);
      });
    });

    testWidgets('gathers a camera and then adjusts its recording time',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpEditMode(tester, twoCameras(), select: "cam1.jpg");

        // The path of issues #78 and #77 together: one image of the camera
        // whose clock was off, its images gathered, all of them corrected.
        await tester.tap(tool("cam1.jpg", Icons.photo_camera));
        await tester.pumpAndSettle();
        expect(namesOf(albumState(tester).selection), ["cam1.jpg", "cam2.jpg"]);

        await tester.tap(tool("cam1.jpg", Icons.more_time));
        await tester.pumpAndSettle();

        // Two images — the one in the album and the one inside the group.
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-count"))).data,
          "Applies to 2 images",
        );

        var reference = album(tester).parts.first as ImagePart;
        var corrected = DateTime.fromMillisecondsSinceEpoch(reference.date)
            .add(const Duration(hours: 1));
        await tester.enterText(
          find.byKey(const Key("adjust-time")),
          AdjustRecordingTimeDialogState.timeFormat.format(corrected),
        );
        await tester.pumpAndSettle();
        expect(
          tester.widget<Text>(find.byKey(const Key("adjust-offset"))).data,
          "+1 h",
        );

        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        // Both images of that camera moved by an hour, the phone's did not.
        var byName = {
          for (var part in album(tester).parts)
            if (part is ImagePart)
              part.name: part
            else
              for (var member in (part as ImageGroup).images)
                member.name: member,
        };
        expect(byName["cam1.jpg"]!.date, 1000 + 3600000);
        expect(byName["cam2.jpg"]!.date, 3000 + 3600000);
        expect(byName["phone1.jpg"]!.date, 2000);
        expect(byName["phone2.jpg"]!.date, 3100);
        expect(albumState(tester).dirty, isTrue);
      });
    });
  });
}
