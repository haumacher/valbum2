import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// The tool with the given icon on the heading [heading].
Finder headingTool(Heading heading, IconData icon) => find.descendant(
      of: find.byKey(ValueKey(heading)),
      matching: find.byIcon(icon),
    );

/// Loads the given album fixture and enters the edit mode.
Future<void> pumpEditMode(WidgetTester tester, String name) async {
  await tester.pumpWidget(VAlbumApp(client: clientReturning(fixture(name))));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

Future<void> tapTool(WidgetTester tester, Finder finder) async {
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('shows the heading tools in the edit mode only', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(fixture("album.json"))),
      );
      await tester.pumpAndSettle();

      var heading = album(tester).parts.first as Heading;
      expect(find.text("Am Morgen"), findsOneWidget);
      expect(headingTool(heading, Icons.edit), findsNothing);
      expect(headingTool(heading, Icons.delete_outline), findsNothing);

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      expect(headingTool(heading, Icons.edit), findsOneWidget);
      expect(headingTool(heading, Icons.delete_outline), findsOneWidget);
    });
  });

  testWidgets('edits the text of a heading, refusing an empty one',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, "album.json");
      var heading = album(tester).parts.first as Heading;

      await tapTool(tester, headingTool(heading, Icons.edit));
      // The dialog is prefilled with the current text.
      expect(find.text("Überschrift bearbeiten"), findsOneWidget);
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller?.text,
        "Am Morgen",
      );

      // An empty text is refused.
      await tester.enterText(find.byType(TextField), "   ");
      await tapTool(tester, find.text("Übernehmen"));
      expect(heading.text, "Am Morgen");

      await tapTool(tester, headingTool(heading, Icons.edit));
      await tester.enterText(find.byType(TextField), "Am Abend");
      await tapTool(tester, find.text("Übernehmen"));

      expect(heading.text, "Am Abend");
      expect(find.text("Am Abend"), findsOneWidget);
      expect(find.text("Am Morgen"), findsNothing);
    });
  });

  testWidgets('deletes a heading', (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, "album.json");
      var heading = album(tester).parts.first as Heading;

      await tapTool(tester, headingTool(heading, Icons.delete_outline));

      expect(album(tester).parts, hasLength(3));
      expect(album(tester).parts.whereType<Heading>(), isEmpty);
      expect(find.text("Am Morgen"), findsNothing);
    });
  });

  testWidgets('never lays images of both sides of a heading into one row',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(fixture("album-heading.json"))),
      );
      await tester.pumpAndSettle();

      // Four images of the same shape: without the heading they would share
      // one row.
      expect(find.byType(Image), findsNWidgets(4));
      var boxes = [
        for (var i = 0; i < 4; i++) tester.getRect(find.byType(Image).at(i)),
      ];

      // The two before and the two behind the heading form a row each.
      expect(boxes[0].top, boxes[1].top);
      expect(boxes[2].top, boxes[3].top);
      expect(boxes[1].bottom, lessThanOrEqualTo(boxes[2].top));
      expect(find.text("Am Mittag"), findsOneWidget);
    });
  });
}
