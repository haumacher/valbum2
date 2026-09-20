/// The file name in the image properties (issue #103).
///
/// An image *is* its file in the folder, and until now the name was nowhere
/// to be seen but in the URL. The properties dialog names it first, above the
/// recording time and the camera, for a photo and a video alike; a group is
/// named by its representative, which is the image the tile shows. The lines
/// are selectable, so a name can be copied out of the dialog.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

ImagePart image(String name, int date, {ImageKind kind = ImageKind.image}) =>
    ImagePart(
      name: name,
      date: date,
      kind: kind,
      width: 2048,
      height: 1536,
    );

/// An album with a photo, a video and a group of two shots.
String albumJson() => AlbumInfo(
      path: "",
      title: "Namen",
      parts: [
        image("a.jpg", 1015113600000),
        image("clip.mp4", 1015113610000, kind: ImageKind.video),
        ImageGroup(
          representative: 0,
          images: [
            image("g1.jpg", 1015113620000),
            image("g2.jpg", 1015113630000),
          ],
        ),
      ],
    ).toString();

Finder tile(String name) => find.byKey(ValueKey(name));

/// The "properties" tool on the tile of [name].
Finder propertiesTool(String name) =>
    find.descendant(of: tile(name), matching: find.byIcon(Icons.notes));

/// Loads the album, enters the edit mode and opens the properties of [name].
Future<void> openProperties(WidgetTester tester, String name) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(albumJson())));
  await tester.pumpAndSettle();
  await tester.longPress(tile(name));
  await tester.pumpAndSettle();
  await tester.tap(propertiesTool(name));
  await tester.pumpAndSettle();
}

/// The detail lines of the open properties dialog, in the order they read.
List<String> detailLines(WidgetTester tester) => [
      for (var widget in tester.widgetList<SelectableText>(
        find.descendant(
          of: find.byKey(const Key("properties-details")),
          matching: find.byType(SelectableText),
        ),
      ))
        widget.data!,
    ];

void main() {
  testWidgets('the properties of a photo name its file first', (tester) async {
    await withFakeImageHttp(() async {
      await openProperties(tester, "a.jpg");

      expect(find.text("Image properties"), findsOneWidget);
      var lines = detailLines(tester);
      expect(lines.first, "File: a.jpg");
      expect(lines[1], startsWith("Taken: "));
    });
  });

  testWidgets('a video is named the same way', (tester) async {
    await withFakeImageHttp(() async {
      await openProperties(tester, "clip.mp4");

      expect(detailLines(tester).first, "File: clip.mp4");
    });
  });

  testWidgets('a group is named by its representative', (tester) async {
    await withFakeImageHttp(() async {
      await openProperties(tester, "g1.jpg");

      expect(detailLines(tester).first, "File: g1.jpg");
    });
  });

  testWidgets('the lines can be selected, so a name can be copied',
      (tester) async {
    await withFakeImageHttp(() async {
      await openProperties(tester, "a.jpg");

      expect(
        find.descendant(
          of: find.byKey(const Key("properties-details")),
          matching: find.byType(SelectableText),
        ),
        findsWidgets,
      );
      expect(find.text("File: a.jpg"), findsOneWidget);
    });
  });
}
