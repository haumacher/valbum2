/// The crop of a photograph in the album (issue #212): the tile is drawn cut
/// at the crop's aspect, and the context menu of an edit-mode tile opens the
/// crop editor on that photograph and writes at once — a write the album's
/// edit buffer neither loses by a later Save nor takes back by a Cancel.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' show json, pathOf, tile;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server of the album `Inbox`, which remembers what was written to it.
class FakeAlbumServer {
  late AlbumInfo album =
      Resource.read(JsonReader.fromString(fixture("album-move.json")))
          as AlbumInfo;

  final List<http.Request> requests = [];

  ImagePart image(String name) => [
        for (var part in album.parts)
          if (part is ImagePart)
            part
          else if (part is ImageGroup)
            ...part.images,
      ].singleWhere((image) => image.name == name);

  VAlbumClient client() => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          var path = pathOf(request);
          if (path != "/valbum/data/Inbox/") {
            return http.Response("No such resource: $path", 404);
          }
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == "crop") {
            var part = AbstractImage.read(JsonReader.fromString(request.body))
                as ImagePart;
            image(part.name).crop = part.crop;
            return json(album.toString());
          }
          if (request.method == "PUT") {
            album =
                Resource.read(JsonReader.fromString(request.body)) as AlbumInfo;
            return json("");
          }
          return json(album.toString());
        }),
      );
}

Future<void> pumpInbox(WidgetTester tester, FakeAlbumServer server) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: server.client(),
      initialRoute: const ListingOrAlbumRoute(["Inbox"]),
    ));
    await tester.pumpAndSettle();
  });
}

/// The thumbnail URL the tile of [name] draws.
String tileUrl(WidgetTester tester, String name) {
  var image = tester.widget<Image>(
      find.descendant(of: tile(name), matching: find.byType(Image)).first);
  return thumbnailOf(image.image)!.url;
}

/// Draws a free frame on the editor's picture and applies it.
Future<void> cropTo(WidgetTester tester, Rect frame) async {
  await tester.tap(find.byKey(const Key("crop-aspect-free")));
  await tester.pumpAndSettle();
  var rect = tester.getRect(find.byKey(const Key("crop-area")));
  Offset at(Offset p) =>
      Offset(rect.left + p.dx * rect.width, rect.top + p.dy * rect.height);
  var from = at(frame.topLeft);
  var to = at(frame.bottomRight);
  var gesture = await tester.startGesture(from);
  for (var i = 1; i <= 4; i++) {
    await gesture.moveTo(Offset.lerp(from, to, i / 4)!);
    await tester.pump();
  }
  await gesture.up();
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("crop-apply")));
  await tester.pumpAndSettle();
}

Future<void> enterEditMode(WidgetTester tester) async {
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

Future<void> secondaryClick(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy),
      buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
  await tester.pumpAndSettle();
}

/// Rates [name] good by its tile's button: an edit into the buffer.
Future<void> rateGood(WidgetTester tester, String name) async {
  await tester.tap(find.descendant(
      of: tile(name), matching: find.byKey(const Key("rating-good"))));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets("a cropped tile is drawn cut, at the crop's aspect",
      (tester) async {
    var server = FakeAlbumServer();
    // 2048 x 1536; the left half is 1024 x 1536, a portrait.
    server.image("a.jpg").crop = Crop(x: 0, y: 0, w: 0.5, h: 1);
    await pumpInbox(tester, server);

    expect(tileUrl(tester, "a.jpg"),
        "http://server/valbum/data/Inbox/a.jpg?crop=0.0000,0.0000,0.5000,1.0000&type=tn");
    expect(tileUrl(tester, "b.jpg"),
        "http://server/valbum/data/Inbox/b.jpg?type=tn");
    var a = tester.getSize(tile("a.jpg"));
    var b = tester.getSize(tile("b.jpg"));
    // A portrait spans two rows, as every portrait does.
    expect(a.height, greaterThan(1.9 * b.height));
    expect(a.width / a.height, closeTo(1024 / 1536, 0.02));
    expect(b.width / b.height, closeTo(2048 / 1536, 0.02));
  });

  testWidgets("the context menu crops this photograph alone, at once",
      (tester) async {
    var server = FakeAlbumServer();
    await pumpInbox(tester, server);
    await enterEditMode(tester);
    await secondaryClick(tester, "b.jpg");
    expect(find.byKey(const Key("tile-context-crop")), findsOneWidget);
    await tester.tap(find.byKey(const Key("tile-context-crop")));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("crop-editor")), findsOneWidget,
        reason: "the editor, not the viewer");
    await withFakeImageHttp(
        () => cropTo(tester, const Rect.fromLTRB(0.1, 0.1, 0.6, 0.9)));

    var post = server.requests.singleWhere((r) => r.method == "POST");
    expect(post.url.queryParameters["action"], "crop");
    var sent =
        AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
    expect(sent.name, "b.jpg");
    expect(server.image("b.jpg").crop!.x, closeTo(0.1, 0.01));
    expect(server.image("a.jpg").crop, isNull);
    // The tile is drawn cut at once, and the album is still in the edit mode.
    expect(tileUrl(tester, "b.jpg"), contains("crop=0.1"));
    expect(find.byKey(const Key("edit-cancel")), findsOneWidget);
  });

  testWidgets("a video is not offered the crop", (tester) async {
    var server = FakeAlbumServer();
    server.image("b.jpg").kind = ImageKind.video;
    await pumpInbox(tester, server);
    await enterEditMode(tester);
    await secondaryClick(tester, "b.jpg");
    expect(find.byKey(const Key("tile-context-properties")), findsOneWidget);
    expect(find.byKey(const Key("tile-context-crop")), findsNothing);
  });

  testWidgets("edit, crop, Save: both are stored", (tester) async {
    var server = FakeAlbumServer();
    await pumpInbox(tester, server);
    await enterEditMode(tester);
    await rateGood(tester, "a.jpg");
    await secondaryClick(tester, "b.jpg");
    await tester.tap(find.byKey(const Key("tile-context-crop")));
    await tester.pumpAndSettle();
    await withFakeImageHttp(
        () => cropTo(tester, const Rect.fromLTRB(0.2, 0.2, 0.7, 0.8)));
    expect(server.image("a.jpg").rating, 0, reason: "not saved yet");
    expect(server.image("b.jpg").crop, isNotNull, reason: "stored at once");

    await withFakeImageHttp(() async {
      await tester.tap(find.byTooltip("Save"));
      await tester.pumpAndSettle();
    });
    var put = server.requests.singleWhere((r) => r.method == "PUT");
    expect(put.body, contains('"crop"'));
    expect(server.image("a.jpg").rating, 1);
    expect(server.image("b.jpg").crop!.x, closeTo(0.2, 0.01),
        reason: "Save wrote the crop again, unchanged");
  });

  testWidgets("crop, Cancel: the crop stays", (tester) async {
    var server = FakeAlbumServer();
    await pumpInbox(tester, server);
    await enterEditMode(tester);
    await rateGood(tester, "a.jpg");
    await secondaryClick(tester, "b.jpg");
    await tester.tap(find.byKey(const Key("tile-context-crop")));
    await tester.pumpAndSettle();
    await withFakeImageHttp(
        () => cropTo(tester, const Rect.fromLTRB(0.2, 0.2, 0.7, 0.8)));

    await withFakeImageHttp(() async {
      await tester.tap(find.byKey(const Key("edit-cancel")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("discard-changes")));
      await tester.pumpAndSettle();
    });
    expect(server.requests.where((r) => r.method == "PUT"), isEmpty);
    expect(server.image("a.jpg").rating, 0, reason: "the rating was discarded");
    expect(server.image("b.jpg").crop!.x, closeTo(0.2, 0.01));
    expect(tileUrl(tester, "b.jpg"), contains("crop=0.2"),
        reason: "the album read anew shows the stored crop");
  });

  testWidgets("a listing tile asks for the region the server derived",
      (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const listing = '["ListingInfo", {"path": "", "title": "Top", "folders": ['
        '{"name": "Inbox", "title": "Inbox", "indexPicture": {"image": "a.jpg",'
        ' "scale": 1.5, "crop": {"x": 0.5, "y": 0, "w": 0.5, "h": 0.5}}},'
        '{"name": "Plain", "title": "Plain", "indexPicture": {"image": "b.jpg",'
        ' "scale": 1.5}}]}]';
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async => isThumbnailRequest(request)
          ? http.Response.bytes(transparentPixelPng, 200,
              headers: {"content-type": "image/png"})
          : json(listing)),
    );
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });
    var urls = [
      for (var image in tester.widgetList<Image>(find.byType(Image)))
        if (thumbnailOf(image.image) != null) thumbnailOf(image.image)!.url,
    ];
    expect(
        urls,
        containsAll([
          "http://server/valbum/data/Inbox/a.jpg?crop=0.5000,0.0000,0.5000,0.5000&type=tn",
          "http://server/valbum/data/Plain/b.jpg?type=tn",
        ]));
  });
}
