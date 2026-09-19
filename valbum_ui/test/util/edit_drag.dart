/// Driving the album's edit mode from a widget test: entering it, dragging a
/// tile onto another and reading the stored order back.
///
/// The same moves `album_reorder_test.dart` makes, gathered here so that the
/// tests of the cancel and the leave guard (issue #99) make an album dirty
/// exactly the way a user does.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The album view on the screen, whether it is on top or beneath a viewer.
AlbumContentState albumStateOf(WidgetTester tester) => tester
    .state<AlbumContentState>(find.byType(AlbumContent, skipOffstage: false));

/// The album shown, with the edits made to it so far.
AlbumInfo albumOf(WidgetTester tester) => albumStateOf(tester).widget.album;

/// A short description of the stored order of the album's parts.
List<String> partNames(AlbumInfo album) => [
      for (var part in album.parts) partName(part),
    ];

String partName(AlbumPart part) {
  if (part is Heading) return "Heading(${part.text})";
  if (part is ImagePart) return part.name;
  var group = part as ImageGroup;
  return "Group(${group.images.map((i) => i.name).join(",")})";
}

/// Drags the tile [from] onto the right half of the tile [target], which puts
/// the dragged part behind it.
Future<void> dragBehind(
  WidgetTester tester,
  String from,
  String target,
) async {
  var start = tester.getRect(tile(from));
  var box = tester.getRect(tile(target));

  var gesture = await tester.startGesture(
    Offset(start.left + 8, start.center.dy),
  );
  // Sideways, past the touch slop: this is what picks the tile up.
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(Offset(box.left + box.width * 0.75, box.center.dy));
  await tester.pump();
  await gesture.up();
  await tester.pumpAndSettle();
}
