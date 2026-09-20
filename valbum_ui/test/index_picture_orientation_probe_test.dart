/// Probe for #115: two tiles side by side, one crop stored with ROT_R and one
/// crop from before the field, with the same crop numbers.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'index_picture_orientation_test.dart'
    show settle, pictureOf, cropOf, normalised, expectClose;
import 'util/fixtures.dart';

const String listing = '["ListingInfo",{"path":"","title":"Probe","folders":['
    '{"name":"R","title":"Turned","effectiveDate":0,"indexPicture":'
    '{"image":"clip.mp4","scale":1.5,"tx":10.0,"ty":20.0,"orientation":"ROT_R"}},'
    '{"name":"O","title":"Old","effectiveDate":0,"indexPicture":'
    '{"image":"old.jpg","scale":1.5,"tx":10.0,"ty":20.0}}'
    ']}]';

int turnsAround(WidgetTester tester, Finder picture) => tester
    .widgetList<RotatedBox>(
        find.ancestor(of: picture, matching: find.byType(RotatedBox)))
    .fold(0, (sum, box) => sum + box.quarterTurns);

void main() {
  testWidgets('a turned crop and an old crop with the same numbers get the '
      'same crop transform, and only the turned one is turned', (tester) async {
    await settle(tester,
        () => tester.pumpWidget(VAlbumApp(client: clientReturning(listing))));
    var turned = pictureOf("clip.mp4");
    var old = pictureOf("old.jpg");
    expect(turned, findsOneWidget);
    expect(old, findsOneWidget);
    expect(turnsAround(tester, turned), 1, reason: "ROT_R is one clockwise");
    expect(turnsAround(tester, old), 0, reason: "no field, no turn");
    var size = tester.getSize(find.byKey(const Key("folder-picture-R")).evaluate().isEmpty
        ? find.ancestor(of: turned, matching: find.byType(ClipRect)).first
        : find.byKey(const Key("folder-picture-R"))).width;
    expectClose(normalised(cropOf(tester, turned), size),
        normalised(cropOf(tester, old), size),
        reason: "the crop numbers mean the same in both frames");
  });
}
