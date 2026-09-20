/// Tests for the orientation a crop was made in (issue #115).
///
/// A rendition the server makes is upright by the *file*; the rotation an
/// author stored beside the image is applied on top of it. A crop is therefore
/// only meaningful together with the frame it was measured in, and that frame
/// travels with it as [ThumbnailInfo.orientation]. These tests pin the three
/// ends of that: what [indexPictureOf] writes, what the listing tile and the
/// crop editor draw, and that a crop without the field keeps its old meaning.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String albumFolder = "A";

/// An album holding one landscape file its author turned a quarter left,
/// shown by [crop].
String albumShowing(String crop) =>
    '["AlbumInfo",{"path":"","title":"A","indexPicture":$crop,"parts":['
    '["ImagePart",{"kind":"IMAGE","name":"turned.jpg","width":2048,'
    '"height":1536,"orientation":"ROT_L","rating":0}]]}]';

/// A listing whose one folder is shown by [crop].
String listingShowing(String crop) =>
    '["ListingInfo",{"path":"","title":"Test-album","folders":['
    '{"name":"$albumFolder","title":"A","indexPicture":$crop}]}]';

/// The crop [indexPictureOf] makes for the turned image, as JSON.
const String turnedCrop = '{"image":"turned.jpg","scale":1.3333333333333333,'
    '"tx":0.0,"ty":37.5,"orientation":"ROT_L"}';

/// The same crop as a sidecar written before the field existed.
const String oldCrop =
    '{"image":"turned.jpg","scale":1.3333333333333333,"tx":0.0,"ty":37.5}';

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

/// The picture of the index picture showing [image].
Finder pictureOf(String image) => find.byWidgetPredicate(
      (widget) =>
          widget is Image &&
          widget.image is ThumbnailImage &&
          (widget.image as ThumbnailImage).url.contains(image),
    );

/// The crop transform the given picture is drawn through.
Matrix4 cropOf(WidgetTester tester, Finder picture) => tester
    .widget<Transform>(
      find.ancestor(of: picture, matching: find.byType(Transform)).first,
    )
    .transform;

/// A crop transform for a square tile of [size], made comparable with one for
/// a tile of a different size: the offsets are pixels of the tile.
List<double> normalised(Matrix4 crop, double size) => [
      crop.getMaxScaleOnAxis(),
      crop.getTranslation().x / size,
      crop.getTranslation().y / size,
    ];

void expectClose(List<double> actual, List<double> expected, {String? reason}) {
  expect(actual, hasLength(expected.length));
  for (var i = 0; i < expected.length; i++) {
    expect(actual[i], closeTo(expected[i], 1e-9), reason: reason);
  }
}

/// Serves [listing] at the root and [album] below it, accepting every PUT.
VAlbumClient library(String listing, String album,
        {List<http.Request>? requests}) =>
    clientHandling(
      (request) {
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        var path = Uri.decodeComponent(request.url.path);
        return http.Response(path.endsWith("/data/") ? listing : album, 200,
            headers: {"content-type": "application/json"});
      },
      requests: requests,
    );

/// Enters the edit mode of the album, choosing [image] as the album picture.
Future<void> chooseIndexPicture(WidgetTester tester, String image) async {
  await settle(tester, () => tester.longPress(find.byType(Image).first));
  var box = tester.getRect(find.byKey(ValueKey(image)));
  if (find
      .descendant(
          of: find.byKey(ValueKey(image)), matching: find.byIcon(Icons.check_box))
      .evaluate()
      .isEmpty) {
    await settle(
        tester, () => tester.tapAt(Offset(box.left + 8, box.center.dy)));
  }
  await tap(
    tester,
    find.descendant(
      of: find.byKey(ValueKey(image)),
      matching: find.byTooltip("Als Albumbild verwenden"),
    ),
  );
}

AlbumInfo albumOf(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent)).widget.album;

void main() {
  group('the crop is made in the frame the image is displayed in', () {
    test('a turned landscape file is framed as the portrait picture it is',
        () {
      var info = indexPictureOf(ImagePart(
        name: "turned.jpg",
        width: 2048,
        height: 1536,
        orientation: Orientation.rotL,
      ));

      expect(info.orientation, Orientation.rotL,
          reason: "the frame travels with the crop");
      expect(info.scale, closeTo(4 / 3, 1e-9),
          reason: "the displayed picture is 1536x2048 and fills the square");
      expect(info.ty, closeTo(37.5, 1e-9),
          reason: "a portrait picture is shifted to its middle");
    });

    test('an untouched image is framed exactly as it always was', () {
      var info = indexPictureOf(
        ImagePart(name: "l.jpg", width: 2048, height: 1536),
      );
      expect(info.orientation, Orientation.identity);
      expect(info.scale, closeTo(4 / 3, 1e-9));
      expect(info.ty, 0);
    });

    test('an image without dimensions still says which frame it is in', () {
      var info = indexPictureOf(
        ImagePart(name: "x.jpg", orientation: Orientation.rotR),
      );
      expect(info.scale, 1);
      expect(info.orientation, Orientation.rotR);
    });

    test('panning and zooming keep the frame', () {
      var start = indexPictureOf(ImagePart(
        name: "turned.jpg",
        width: 2048,
        height: 1536,
        orientation: Orientation.rotL,
      ));
      expect(panIndexPicture(start, 10, 10, 300).orientation, Orientation.rotL);
      expect(zoomIndexPicture(start, 2).orientation, Orientation.rotL);
    });
  });

  group('an old crop keeps its meaning', () {
    test('a crop without the field is one made in the un-oriented frame', () {
      var old = ThumbnailInfo(
          image: "x.jpg", scale: 1.3333333333333333, tx: 0, ty: 37.5);
      expect(old.orientation, Orientation.identity);
    });

    test('thumbnailTransform is untouched by this issue', () {
      // The numbers the transform had before the field existed, pinned: the
      // crop maths did not change, only what is turned before it.
      var info = ThumbnailInfo(image: "x.jpg", scale: 2, tx: 30, ty: -15);
      var crop = thumbnailTransform(info, 150);
      expect(crop.getMaxScaleOnAxis(), closeTo(2, 1e-9));
      expect(crop.getTranslation().x, closeTo(30, 1e-9));
      expect(crop.getTranslation().y, closeTo(-15, 1e-9));

      expect(thumbnailTransform(ThumbnailInfo(image: "x.jpg"), 200),
          Matrix4.identity());
    });

    testWidgets('a tile without the field draws the tree it always drew',
        (tester) async {
      var client = library(listingShowing(oldCrop), albumShowing(oldCrop));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));

      var picture = pictureOf("turned.jpg");
      expect(picture, findsOneWidget);
      expect(
        find.ancestor(of: picture, matching: find.byType(RotatedBox)),
        findsNothing,
        reason: "nothing is turned where the crop names no frame",
      );
    });
  });

  group('the listing tile', () {
    testWidgets('turns the rendition inside the crop transform',
        (tester) async {
      var client = library(listingShowing(turnedCrop), albumShowing(turnedCrop));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));

      var picture = pictureOf("turned.jpg");
      expect(picture, findsOneWidget);
      var turn = find.ancestor(of: picture, matching: find.byType(RotatedBox));
      expect(turn, findsOneWidget, reason: "the tile of a turned cover turns");

      // The turn is *inside* the crop: the crop is measured in the turned
      // frame, so turning after it would crop the wrong picture.
      expect(
        find.ancestor(of: turn, matching: find.byType(Transform)),
        findsWidgets,
      );
      var size = tester.getSize(find.ancestor(of: picture,
          matching: find.byType(ClipRect)).first);
      expectClose(
        normalised(cropOf(tester, picture), size.width),
        normalised(
          thumbnailTransform(
            ThumbnailInfo(
              image: "turned.jpg",
              scale: 4 / 3,
              ty: 37.5,
              orientation: Orientation.rotL,
            ),
            size.width,
          ),
          size.width,
        ),
      );
    });
  });

  group('the crop editor and the tile show the same picture', () {
    testWidgets('same crop, same turn, same transform', (tester) async {
      var client = library(listingShowing(turnedCrop), albumShowing(turnedCrop));
      await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));

      var tilePicture = pictureOf("turned.jpg");
      var tileSize = tester
          .getSize(find
              .ancestor(of: tilePicture, matching: find.byType(ClipRect))
              .first)
          .width;
      var tileCrop = normalised(cropOf(tester, tilePicture), tileSize);
      var tileTurned = find
          .ancestor(of: tilePicture, matching: find.byType(RotatedBox))
          .evaluate()
          .isNotEmpty;

      // Into the album and open its properties, where the same crop is edited.
      await tap(tester, find.text("A"));
      await settle(tester, () => tester.longPress(find.byType(Image).first));
      await tap(tester, find.byIcon(Icons.more_vert).last);
      await tap(tester, find.byKey(const Key("album-properties")));

      var editor = find.byKey(const Key("index-picture-editor"));
      expect(editor, findsOneWidget);
      var editorPicture = find.descendant(
          of: editor, matching: pictureOf("turned.jpg"));
      expect(editorPicture, findsOneWidget);

      expectClose(
        normalised(cropOf(tester, editorPicture), indexPictureEditorSize),
        tileCrop,
        reason: "what is edited is what the listing shows",
      );
      expect(
        find
            .ancestor(of: editorPicture, matching: find.byType(RotatedBox))
            .evaluate()
            .isNotEmpty,
        tileTurned,
        reason: "the editor turns the rendition exactly as the tile does",
      );
    });
  });

  group('rotating the album picture', () {
    testWidgets('takes its crop along into the new frame, and saves it',
        (tester) async {
      var requests = <http.Request>[];
      var client = library(listingShowing(turnedCrop), albumShowing(turnedCrop),
          requests: requests);
      await settle(
        tester,
        () => tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute([albumFolder]),
        )),
      );

      await chooseIndexPicture(tester, "turned.jpg");
      expect(albumOf(tester).indexPicture!.orientation, Orientation.rotL);

      await tap(
        tester,
        find.descendant(
          of: find.byKey(const ValueKey("turned.jpg")),
          matching: find.byTooltip("Nach links drehen"),
        ),
      );
      expect(albumOf(tester).indexPicture!.orientation, Orientation.rot180,
          reason: "the crop follows the image into its new frame");

      await tap(tester, find.byIcon(Icons.save));
      var put = requests.where((r) => r.method == "PUT").single;
      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect(saved.indexPicture?.orientation, Orientation.rot180);
      expect(saved.indexPicture?.image, "turned.jpg");
    });

    testWidgets('leaves the crop of another image alone', (tester) async {
      var client = library(listingShowing(turnedCrop), albumShowing(turnedCrop));
      await settle(
        tester,
        () => tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute([albumFolder]),
        )),
      );

      await settle(tester, () => tester.longPress(find.byType(Image).first));
      // The album's picture is the one the listing named; the tile's image is
      // turned without ever having been chosen here.
      albumOf(tester).indexPicture =
          ThumbnailInfo(image: "somebody-else.jpg", scale: 2);
      await tap(
        tester,
        find.descendant(
          of: find.byKey(const ValueKey("turned.jpg")),
          matching: find.byTooltip("Nach rechts drehen"),
        ),
      );

      expect(albumOf(tester).indexPicture!.orientation, Orientation.identity,
          reason: "a crop of another image is not touched");
    });
  });
}
