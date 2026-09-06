import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Finder tile(String name) => find.byKey(ValueKey(name));

AlbumInfo album(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent)).widget.album;

List<String> names(AlbumInfo album) => [
      for (var part in album.parts)
        part is Heading
            ? "H"
            : part is ImagePart
                ? part.name
                : "G",
    ];

Future<void> dragOnto(WidgetTester tester, Finder from, Finder target,
    {required bool left}) async {
  var start = tester.getRect(from);
  var box = tester.getRect(target);
  var gesture =
      await tester.startGesture(Offset(start.left + 8, start.center.dy));
  await gesture.moveBy(const Offset(40, 0));
  await tester.pump();
  await gesture.moveTo(
      Offset(box.left + box.width * (left ? 0.25 : 0.75), box.center.dy));
  await tester.pump();
  await gesture.up();
  await tester.pumpAndSettle();
}

void main() {
  test('a move keeps hidden and grouped parts in their relative places', () {
    var heading = Heading(text: "H");
    var a = ImagePart(name: "a.jpg", width: 2000, height: 1000);
    var group = ImageGroup(representative: 0, images: [
      ImagePart(name: "g1.jpg", width: 2000, height: 1000),
      ImagePart(name: "g2.jpg", width: 2000, height: 1000),
    ]);
    var video = ImagePart(
        name: "v.mp4", kind: ImageKind.video, rating: -1, width: 1, height: 1);
    var c = ImagePart(name: "c.jpg", width: 2000, height: 1000);
    var info = AlbumInfo(parts: [heading, a, group, video, c]);
    AlbumInitializer().init(info);

    // The rating filter hides the video: displayed [H, a, G, c]. Moving c
    // behind the displayed a lands directly behind a in the store, in front
    // of the group and the hidden video, which keep their relative order.
    expect(movePart(info, c, a), isTrue);
    expect(names(info), ["H", "a.jpg", "c.jpg", "G", "v.mp4"]);
    expect(c.next, same(group));
    expect(group.previous, same(c));
    expect(group.images[0].next, same(group.images[1]),
        reason: "the chain inside the group is untouched");

    // The group moves as one part, to the very front, before the heading.
    expect(movePart(info, group, null), isTrue);
    expect(names(info), ["G", "H", "a.jpg", "c.jpg", "v.mp4"]);

    // The hidden video can be moved too (by the model, e.g. after widening
    // the filter): behind the group it follows the group directly.
    expect(movePart(info, video, group), isTrue);
    expect(names(info), ["G", "v.mp4", "H", "a.jpg", "c.jpg"]);

    // JSON round trip keeps the order.
    var reread = Resource.read(JsonReader.fromString(info.toString()))
        as AlbumInfo;
    expect(names(reread), names(info));
  });

  testWidgets('a moved group can be dissolved in place and saved',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (request) => http.Response(
        request.method == "PUT" ? "" : fixture("album.json"),
        200,
        headers: {"content-type": "application/json"},
      ),
      requests: requests,
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(names(album(tester)),
          ["H", "landscape.jpg", "portrait.jpg", "G"]);

      // The group goes to the front of the images, right behind the heading:
      // the left half of whichever image tile is displayed first.
      var first = [tile("landscape.jpg"), tile("portrait.jpg")]
          .reduce((x, y) =>
              tester.getRect(x).left <= tester.getRect(y).left &&
                      tester.getRect(x).top <= tester.getRect(y).top
                  ? x
                  : y);
      await dragOnto(tester, tile("group-a.jpg"), first, left: true);
      expect(names(album(tester)),
          ["H", "G", "landscape.jpg", "portrait.jpg"]);

      // Dissolving the moved group puts its members where it stood.
      var groupTile = tile("group-a.jpg");
      if (find
          .descendant(of: groupTile, matching: find.byIcon(Icons.check_box))
          .evaluate()
          .isEmpty) {
        var box = tester.getRect(groupTile);
        await tester.tapAt(Offset(box.left + 8, box.center.dy));
        await tester.pumpAndSettle();
      }
      await tester.tap(find.byTooltip("Gruppierung aufheben"));
      await tester.pumpAndSettle();
      expect(names(album(tester)),
          ["H", "group-a.jpg", "group-b.jpg", "landscape.jpg", "portrait.jpg"]);

      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();
    });

    var puts = requests.where((r) => r.method == "PUT").toList();
    expect(puts, hasLength(1));
    var saved =
        Resource.read(JsonReader.fromString(puts.single.body)) as AlbumInfo;
    expect(names(saved),
        ["H", "group-a.jpg", "group-b.jpg", "landscape.jpg", "portrait.jpg"]);
  });
}
