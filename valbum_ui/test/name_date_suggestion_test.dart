/// "Use the time in the file name" in the Correct-time dialog, the repairing
/// half of issue #102.
///
/// The server reads a date out of the file name since #102, but only when it
/// first sees a file: an album dated before the rule existed keeps the write
/// times it was given, which are the times of a *copy*. One tap in the dialog
/// of issue #77 now dates every selected image by its own name — each of them
/// by its own, because the write time is a different lie for every file.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// An image of the given name taken at the given date.
ImagePart image(String name, DateTime date) => ImagePart(
      name: name,
      date: date.millisecondsSinceEpoch,
      kind: ImageKind.image,
      width: 2048,
      height: 1536,
    );

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

/// Taps a tile with the given modifier key held down.
Future<void> tapTileWith(
  WidgetTester tester,
  String name,
  LogicalKeyboardKey modifier,
) async {
  await tester.sendKeyDownEvent(modifier);
  await tapTile(tester, name);
  await tester.sendKeyUpEvent(modifier);
  await tester.pumpAndSettle();
}

/// Loads the given album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// A client answering the given album and accepting every write.
VAlbumClient serving(AlbumInfo content) => clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("", 200)
          : http.Response(content.toString(), 200),
    );

/// The date of the image called [name], as the album holds it now.
DateTime dateOf(WidgetTester tester, String name) {
  for (var part in album(tester).parts) {
    if (part is ImagePart && part.name == name) {
      return DateTime.fromMillisecondsSinceEpoch(part.date);
    }
  }
  throw StateError("No image '$name' in the album.");
}

/// Opens the Correct-time dialog on the tile of [name].
Future<void> openDialog(WidgetTester tester, String name) async {
  await tester.tap(tool(name, Icons.more_time));
  await tester.pumpAndSettle();
}

/// The write time an uploaded copy was given: long after the recording.
final DateTime copied = DateTime(2025, 9, 1, 8, 30);

/// What the names of the album below say.
final DateTime recorded = DateTime(2024, 3, 15, 14, 22, 33);

Finder get suggestion => find.byKey(const Key("use-name-date"));

void main() {
  testWidgets('the button offers the very time the one file name says',
      (tester) async {
    var content = AlbumInfo(
      path: "",
      title: "Ferien",
      parts: [image("VID_20240315_142233.mp4", copied)],
    );

    await withFakeImageHttp(() async {
      await pumpEditMode(tester, serving(content));
      await openDialog(tester, "VID_20240315_142233.mp4");

      expect(suggestion, findsOneWidget);
      expect(
        find.text("Use the time in the file name: 2024-03-15 14:22:33"),
        findsOneWidget,
      );

      await tester.tap(suggestion);
      await tester.pumpAndSettle();

      expect(dateOf(tester, "VID_20240315_142233.mp4"), recorded);
      expect(albumState(tester).dirty, isTrue);
    });
  });

  testWidgets('the button is not offered where the name says nothing',
      (tester) async {
    var content = AlbumInfo(
      path: "",
      title: "Ferien",
      parts: [image("IMG_1234.jpg", copied)],
    );

    await withFakeImageHttp(() async {
      await pumpEditMode(tester, serving(content));
      await openDialog(tester, "IMG_1234.jpg");

      expect(suggestion, findsNothing);
    });
  });

  testWidgets('nor where the image already carries what the name says',
      (tester) async {
    var content = AlbumInfo(
      path: "",
      title: "Ferien",
      parts: [image("VID_20240315_142233.mp4", recorded)],
    );

    await withFakeImageHttp(() async {
      await pumpEditMode(tester, serving(content));
      await openDialog(tester, "VID_20240315_142233.mp4");

      expect(suggestion, findsNothing);
    });
  });

  testWidgets('a selection of mixed names applies to those that carry a date',
      (tester) async {
    var content = AlbumInfo(
      path: "",
      title: "Ferien",
      parts: [
        image("VID_20240315_142233.mp4", copied),
        image(
            "PXL_20240316_090000123.mp4",
            copied.add(const Duration(
              minutes: 1,
            ))),
        image("IMG_1234.jpg", copied.add(const Duration(minutes: 2))),
      ],
    );

    await withFakeImageHttp(() async {
      await pumpEditMode(tester, serving(content));
      // The long press selected the first tile; add the other two.
      await tapTileWith(
        tester,
        "PXL_20240316_090000123.mp4",
        LogicalKeyboardKey.controlLeft,
      );
      await tapTileWith(
        tester,
        "IMG_1234.jpg",
        LogicalKeyboardKey.controlLeft,
      );
      expect(albumState(tester).selection, hasLength(3));

      await openDialog(tester, "IMG_1234.jpg");
      expect(
        tester.widget<Text>(find.byKey(const Key("adjust-count"))).data,
        "Applies to 3 images",
      );
      // Two of the three names carry a date, and the button says so instead
      // of naming a time that would only be right for one of them.
      expect(
        find.text("Use the time in the file name (2 images)"),
        findsOneWidget,
      );

      var untouched = dateOf(tester, "IMG_1234.jpg");
      await tester.tap(suggestion);
      await tester.pumpAndSettle();

      expect(dateOf(tester, "VID_20240315_142233.mp4"), recorded);
      expect(
        dateOf(tester, "PXL_20240316_090000123.mp4"),
        DateTime(2024, 3, 16, 9, 0, 0),
      );
      // The one whose name says nothing is left exactly as it was.
      expect(dateOf(tester, "IMG_1234.jpg"), untouched);
      expect(albumState(tester).dirty, isTrue);

      // And each of them was refiled where its own new date belongs.
      expect(
        [for (var part in album(tester).parts) (part as ImagePart).name],
        [
          "VID_20240315_142233.mp4",
          "PXL_20240316_090000123.mp4",
          "IMG_1234.jpg",
        ],
      );
    });
  });
}
