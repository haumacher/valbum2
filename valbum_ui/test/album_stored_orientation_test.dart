/// What a stored rotation looks like in an album tile and in the viewer
/// (issue #106).
///
/// A rendition the server makes — the thumbnail, the preview, the poster of a
/// video — is upright by the *file*: `PreviewCache` bakes the orientation the
/// image file carries into it and nothing else. `ImagePart.orientation` is the
/// transform stored beside the image, *in addition* to that one, so the client
/// applies it, wherever it draws a rendition.
///
/// The album tile used to apply only the delta against the orientation the
/// album was laid out with. That delta exists inside an edit session and
/// nowhere else: saving re-fetched the album, the delta became the identity,
/// and the rotation that had just been stored looked reverted — as did every
/// rotation stored on an earlier day.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';
import 'util/viewer_harness.dart';

/// An album of one 900x600 landscape image carrying the given orientation.
String albumOriented(String orientation) =>
    '["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, '
    '"width": 900, "height": 600, "orientation": "$orientation", '
    '"rating": 0}]]}]';

/// The [Image] drawing the thumbnail of the given file.
Finder thumbnailImage(String name) => find.byWidgetPredicate((widget) =>
    widget is Image &&
    thumbnailOf(widget.image)?.imageUrl.endsWith(name) == true);

/// The quarter turns (clockwise) the tile of [name] is drawn with, `0` where
/// nothing turns it.
int quarterTurnsOf(WidgetTester tester, String name) {
  var boxes = tester.widgetList<RotatedBox>(
    find.ancestor(
      of: thumbnailImage(name),
      matching: find.byType(RotatedBox),
    ),
  );
  return boxes.fold(0, (sum, box) => sum + box.quarterTurns);
}

/// Whether the tile of [name] is drawn mirrored.
bool isMirrored(WidgetTester tester, String name) => tester
    .widgetList<Transform>(
      find.ancestor(
        of: thumbnailImage(name),
        matching: find.byType(Transform),
      ),
    )
    .any((transform) => transform.transform.entry(0, 0) < 0);

/// Pumps an album whose single image carries the given orientation.
Future<void> pumpAlbum(WidgetTester tester, String orientation) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      VAlbumApp(client: clientReturning(albumOriented(orientation))),
    );
    await tester.pumpAndSettle();
  });
}

/// The matrix the viewer's picture layer is drawn with.
Matrix4 pictureMatrix(WidgetTester tester, Key key) => tester
    .widget<Transform>(
      find
          .ancestor(of: find.byKey(key), matching: find.byType(Transform))
          .first,
    )
    .transform;

/// An album of one group whose three members carry `ROT_L`, `FLIP_H` and no
/// orientation at all, all of them 900x600 landscape files.
const String albumWithOrientedGroup =
    '["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": ['
    '["ImageGroup", {"representative": 0, "images": ['
    '{"kind": "IMAGE", "name": "g-rotl.jpg", "date": 1, "width": 900, '
    '"height": 600, "orientation": "ROT_L", "rating": 0},'
    '{"kind": "IMAGE", "name": "g-flip.jpg", "date": 2, "width": 900, '
    '"height": 600, "orientation": "FLIP_H", "rating": 0},'
    '{"kind": "IMAGE", "name": "g-plain.jpg", "date": 3, "width": 900, '
    '"height": 600, "orientation": "IDENTITY", "rating": 0}'
    ']}]]}]';

/// The tile of the group member with the given file name.
Finder memberTile(String name) => find.byKey(ValueKey("group-tile-$name"));

/// The quarter turns (clockwise) the tile of the group member [name] is drawn
/// with.
int memberTurnsOf(WidgetTester tester, String name) => tester
    .widgetList<RotatedBox>(
      find.ancestor(of: memberTile(name), matching: find.byType(RotatedBox)),
    )
    .fold(0, (sum, box) => sum + box.quarterTurns);

/// Whether the tile of the group member [name] is drawn mirrored.
bool isMemberMirrored(WidgetTester tester, String name) => tester
    .widgetList<Transform>(
      find.ancestor(of: memberTile(name), matching: find.byType(Transform)),
    )
    .any((transform) => transform.transform.entry(0, 0) < 0);

/// The box the tile of the group member [name] occupies in the layout.
Size memberBoxOf(WidgetTester tester, String name) => tester.getSize(
      find
          .ancestor(of: memberTile(name), matching: find.byType(GestureDetector))
          .first,
    );

void main() {
  setUp(withEmptyImageCache);

  group('a stored orientation in the album tile', () {
    testWidgets('turns a quarter to the left inside the portrait box it is '
        'laid out in', (tester) async {
      await pumpAlbum(tester, "ROT_L");

      // The layout already swaps width and height: the box of a landscape
      // file stored `rotL` is portrait.
      var box = tester.getSize(find.byKey(const ValueKey("a.jpg")));
      expect(box.height, greaterThan(box.width));
      expect(box.width / box.height, closeTo(600 / 900, 0.01));

      // A quarter turn counter-clockwise is three clockwise, which is what
      // [RotatedBox] counts.
      expect(quarterTurnsOf(tester, "a.jpg"), 3);
      expect(isMirrored(tester, "a.jpg"), isFalse);

      // And it fills that box: the rendition is drawn landscape and the turn
      // makes it the portrait box exactly — no letterboxing at rest.
      var drawn = tester.getSize(thumbnailImage("a.jpg"));
      expect(drawn.width, closeTo(box.height, 0.5));
      expect(drawn.height, closeTo(box.width, 0.5));
    });

    testWidgets('turns a half for ROT_180', (tester) async {
      await pumpAlbum(tester, "ROT_180");

      var box = tester.getSize(find.byKey(const ValueKey("a.jpg")));
      expect(box.width, greaterThan(box.height));

      expect(quarterTurnsOf(tester, "a.jpg"), 2);
      expect(isMirrored(tester, "a.jpg"), isFalse);

      var drawn = tester.getSize(thumbnailImage("a.jpg"));
      expect(drawn.width, closeTo(box.width, 0.5));
      expect(drawn.height, closeTo(box.height, 0.5));
    });

    testWidgets('mirrors without turning for FLIP_H', (tester) async {
      await pumpAlbum(tester, "FLIP_H");

      expect(quarterTurnsOf(tester, "a.jpg"), 0);
      expect(isMirrored(tester, "a.jpg"), isTrue);
    });

    testWidgets('leaves the rendition alone for IDENTITY', (tester) async {
      await pumpAlbum(tester, "IDENTITY");

      expect(
        find.ancestor(
          of: thumbnailImage("a.jpg"),
          matching: find.byType(RotatedBox),
        ),
        findsNothing,
      );
      expect(isMirrored(tester, "a.jpg"), isFalse);
    });
  });

  testWidgets('a rotation made in the edit mode stays on the screen when it '
      'is saved', (tester) async {
    // What the server answers a listing with: the stored album, which carries
    // the rotation once it was saved.
    var stored = albumOriented("IDENTITY");
    var client = clientHandling((request) {
      if (request.method == "PUT") {
        stored = albumOriented("ROT_L");
        return http.Response("", 200);
      }
      return http.Response(stored, 200);
    });

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();

      // Into the edit mode, and rotate the tile to the left.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.rotate_left));
      await tester.pumpAndSettle();

      var turnedWhileEditing = quarterTurnsOf(tester, "a.jpg");
      expect(turnedWhileEditing, 3);

      // Save: the album is written and fetched again, and what comes back
      // carries the rotation.
      await tester.tap(find.byIcon(Icons.save));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.save), findsNothing, reason: "the edit ended");
      expect(quarterTurnsOf(tester, "a.jpg"), turnedWhileEditing);
      expect(isMirrored(tester, "a.jpg"), isFalse);

      // And the album is laid out for the stored orientation now: portrait.
      var box = tester.getSize(find.byKey(const ValueKey("a.jpg")));
      expect(box.height, greaterThan(box.width));
    });
  });

  testWidgets('the alternatives of a group show what each member has stored',
      (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: clientReturning(albumWithOrientedGroup)),
      );
      await tester.pumpAndSettle();

      // The album shows the group by its representative; from its viewer the
      // way down leads to the alternatives.
      await tester.tap(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.expand_more));
      await tester.pumpAndSettle();
    });

    expect(find.byType(GroupView), findsOneWidget);

    // A quarter turn counter-clockwise is three clockwise, and the layout of
    // the alternatives swaps the box just as the album's does.
    expect(memberTurnsOf(tester, "g-rotl.jpg"), 3);
    expect(isMemberMirrored(tester, "g-rotl.jpg"), isFalse);
    var turned = memberBoxOf(tester, "g-rotl.jpg");
    expect(turned.height, greaterThan(turned.width));
    expect(turned.width / turned.height, closeTo(600 / 900, 0.01));

    // A mirror swaps nothing, so the box stays landscape.
    expect(memberTurnsOf(tester, "g-flip.jpg"), 0);
    expect(isMemberMirrored(tester, "g-flip.jpg"), isTrue);
    var mirrored = memberBoxOf(tester, "g-flip.jpg");
    expect(mirrored.width, greaterThan(mirrored.height));

    // And an image with nothing stored is drawn as it always was.
    expect(memberTurnsOf(tester, "g-plain.jpg"), 0);
    expect(isMemberMirrored(tester, "g-plain.jpg"), isFalse);
  });

  group('the viewer', () {
    testWidgets('shows a stored rotation whether or not the caller may '
        'download', (tester) async {
      var matrices = <bool, Matrix4>{};
      for (var mayDownload in [true, false]) {
        if (matrices.isNotEmpty) {
          // The harness pushes the viewer over a start page; the next round
          // needs that page back.
          tester.state<NavigatorState>(find.byType(Navigator).last).pop();
          await tester.pumpAndSettle();
        }
        var images = [viewerImagePart("a.jpg")..orientation = Orientation.rotL];
        viewerAlbum(
          images,
          rights: mayDownload ? const ["view", "download"] : const ["view"],
        );
        fakeImageRequests();

        await pumpViewerHarness(
          tester,
          images[0],
          client: recordingViewerClient([]),
          caller: mayDownload ? viewerMember("bob") : null,
          share: mayDownload ? null : viewerShareSession(),
        );

        matrices[mayDownload] =
            pictureMatrix(tester, const Key("image-picture"));
        // The thumbnail kept beneath the picture (issue #101) stands in the
        // same coordinates, so it cannot contradict what is over it.
        expect(
          pictureMatrix(tester, const Key("image-thumbnail")),
          matrices[mayDownload],
        );
      }
      // The preview rendition the `view`-only caller is shown is turned
      // exactly like the original the other one gets.
      expect(matrices[false], matrices[true]);

      // And it really is turned: a quarter turn maps x onto y.
      expect(matrices[true]!.entry(0, 0), 0);
      expect(matrices[true]!.entry(0, 1), isNot(0));
    });
  });
}
