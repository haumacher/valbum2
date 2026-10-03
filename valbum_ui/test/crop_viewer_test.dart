/// The crop of a photograph in the viewer (issue #212): the viewer draws the
/// region alone — of the original, and of the cut preview a caller without
/// `download` and a share link are shown — a face outside it is not drawn,
/// "Crop…" stands in the viewer's menu for an editor and writes at once
/// (refused offline, the server's refusal said in its own words), and the
/// download stays the whole original.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/image_transform.dart' show pictureRectOnPage;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'download_test.dart' show installSaver, originalBytes, tapViewerDownload;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// The crop of the tests: the top right quarter.
Crop topRight() => Crop(x: 0.5, y: 0, w: 0.5, h: 0.5);

/// What the server answers a crop with: the album, the photograph carrying
/// [crop].
String croppedAlbum(Crop? crop) => AlbumInfo(title: "Album", parts: [
      ImagePart(name: "a.jpg", width: 2000, height: 1000, crop: crop),
    ]).toString();

/// A client answering a crop with [answer], recording every request.
VAlbumClient cropClient(
  List<http.Request> requests, {
  http.Response Function(http.Request)? answer,
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      token: "tok-1",
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(transparentPixelPng, 200,
              headers: {"content-type": "image/png"});
        }
        if (request.method == "POST" &&
            request.url.queryParameters["action"] == "crop") {
          if (answer != null) {
            return answer(request);
          }
          var part = AbstractImage.read(JsonReader.fromString(request.body))
              as ImagePart;
          return http.Response(croppedAlbum(part.crop), 200,
              headers: {"content-type": "application/json"});
        }
        if (request.url.path == "/valbum/data/album/a.jpg" &&
            request.url.query.isEmpty) {
          return http.Response.bytes(originalBytes, 200,
              headers: {"content-type": "image/jpeg"});
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// Shows `a.jpg` in the viewer, cropped where [crop] is given.
Future<ImagePart> pumpCropped(
  WidgetTester tester,
  VAlbumClient client, {
  Crop? crop,
  List<String> rights = const ["view", "download", "contribute", "edit"],
  ImageKind kind = ImageKind.image,
  ShareSession? share,
  bool offline = false,
  bool faces = false,
  List<FaceInfo> faceList = const [],
}) async {
  withEmptyImageCache();
  var image = viewerImagePart("a.jpg", kind: kind)
    ..crop = crop
    ..faces = faceList;
  viewerAlbum([image], rights: rights);
  fakeImageRequests();
  await pumpViewerHarness(
    tester,
    image,
    client: client,
    caller: share == null
        ? CallerInfo(
            userName: "anna", role: roleMember, space: "anna", faces: faces)
        : null,
    share: share,
    editPath: const ["album"],
    offline: offline,
  );
  return image;
}

Future<void> openViewerMenu(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("viewer-menu")));
  await tester.pumpAndSettle();
}

final Finder cropEntry = find.byKey(const Key("viewer-crop"));

/// Draws a frame from one normalised point of the editor's picture to another
/// and applies it by the button.
Future<void> drawAndApply(WidgetTester tester, Offset from, Offset to) async {
  await tester.tap(find.byKey(const Key("crop-aspect-free")));
  await tester.pumpAndSettle();
  var rect = tester.getRect(find.byKey(const Key("crop-area")));
  Offset at(Offset p) =>
      Offset(rect.left + p.dx * rect.width, rect.top + p.dy * rect.height);
  var gesture = await tester.startGesture(at(from));
  for (var i = 1; i <= 4; i++) {
    await gesture.moveTo(Offset.lerp(at(from), at(to), i / 4)!);
    await tester.pump();
  }
  await gesture.up();
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("crop-apply")));
}

void main() {
  group("the region", () {
    testWidgets("the original is drawn cut to the region", (tester) async {
      await pumpCropped(tester, cropClient([]), crop: topRight());
      expect(find.byKey(const Key("image-region")), findsNWidgets(2));
      expect(shownPictureUrl(tester), "$viewerBaseUrl/a.jpg",
          reason: "the whole original, cut on the client");
      // The thumbnail beneath is the cut one the tile drew.
      var underlay = tester
          .widget<Image>(find.descendant(
              of: find.byKey(const Key("image-thumbnail")),
              matching: find.byType(Image)))
          .image;
      expect(providerUrl(underlay),
          "$viewerBaseUrl/a.jpg?crop=0.5000,0.0000,0.5000,0.5000&type=tn");
      // The clip is the region's own size: 1000 x 500 of the file.
      var state = tester.state<ImageViewState>(find.byType(ImageView));
      var tx = state.transform(tester.getSize(find.byType(ImageView)));
      expect(tx.rawWidth, 1000);
      expect(tx.rawHeight, 500);
    });

    testWidgets("a photograph shown whole has no region", (tester) async {
      await pumpCropped(tester, cropClient([]));
      expect(find.byKey(const Key("image-region")), findsNothing);
    });

    testWidgets("a share session is shown the cut preview", (tester) async {
      await pumpCropped(
        tester,
        cropClient([]),
        crop: topRight(),
        rights: const ["view"],
        share: viewerShareSession(),
      );
      expect(find.byKey(const Key("image-region")), findsNWidgets(2));
      expect(shownPictureUrl(tester),
          "$viewerBaseUrl/a.jpg?crop=0.5000,0.0000,0.5000,0.5000&type=tn");
      await openViewerMenu(tester);
      expect(cropEntry, findsNothing, reason: "a link crops nothing");
    });

    testWidgets("a face outside the region is not drawn", (tester) async {
      await pumpCropped(
        tester,
        cropClient([]),
        crop: topRight(),
        faces: true,
        faceList: [
          // In the top right quarter.
          FaceInfo(index: 0, x: 0.7, y: 0.1, w: 0.1, h: 0.1),
          // In the bottom left one.
          FaceInfo(index: 1, x: 0.1, y: 0.7, w: 0.1, h: 0.1),
        ],
      );
      await openViewerMenu(tester);
      await tester.tap(find.byKey(const Key("viewer-edit-persons")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("face-box-0")), findsOneWidget);
      expect(find.byKey(const Key("face-box-1")), findsNothing);
      // The face that is shown stands where it is on the region.
      var state = tester.state<ImageViewState>(find.byType(ImageView));
      var tx = state.transform(tester.getSize(find.byType(ImageView)));
      var box = tester.getRect(find.byKey(const Key("face-box-0")));
      var page = pictureRectOnPage(tx);
      // x 0.7 of the whole is 0.4 of the region.
      expect(box.left - page.left, closeTo(0.4 * page.width, 0.5));
      expect(box.top - page.top, closeTo(0.2 * page.height, 0.5));
    });
  });

  group("the menu", () {
    testWidgets("offers Crop to an editor of a photograph", (tester) async {
      await pumpCropped(tester, cropClient([]));
      await openViewerMenu(tester);
      expect(cropEntry, findsOneWidget);
      expect(find.text(testL10n.cropMenu), findsOneWidget);
    });

    testWidgets("not without edit", (tester) async {
      await pumpCropped(tester, cropClient([]),
          rights: const ["view", "download"]);
      await openViewerMenu(tester);
      expect(cropEntry, findsNothing);
    });

    testWidgets("not on a video", (tester) async {
      await pumpCropped(tester, cropClient([]), kind: ImageKind.video);
      await openViewerMenu(tester);
      expect(cropEntry, findsNothing);
    });
  });

  group("the write", () {
    testWidgets("is posted at once and adopted", (tester) async {
      var requests = <http.Request>[];
      var image = await pumpCropped(tester, cropClient(requests));
      await openViewerMenu(tester);
      await tester.tap(cropEntry);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("crop-editor")), findsOneWidget);
      await settleViewer(tester, () async {
        await drawAndApply(
            tester, const Offset(0.5, 0.1), const Offset(0.9, 0.6));
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(post.url.path, "/valbum/data/album/");
      expect(post.url.queryParameters["action"], "crop");
      var sent =
          AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
      expect(sent.name, "a.jpg");
      expect(sent.crop!.x, closeTo(0.5, 0.01));
      expect(sent.crop!.y, closeTo(0.1, 0.01));
      expect(sent.crop!.w, closeTo(0.4, 0.01));
      expect(sent.crop!.h, closeTo(0.5, 0.01));
      expect(image.crop!.x, closeTo(0.5, 0.01), reason: "adopted");
      expect(find.byKey(const Key("image-region")), findsNWidgets(2),
          reason: "the viewer shows the region at once");
    });

    testWidgets("Reset takes the crop away", (tester) async {
      var requests = <http.Request>[];
      var image =
          await pumpCropped(tester, cropClient(requests), crop: topRight());
      await openViewerMenu(tester);
      await tester.tap(cropEntry);
      await tester.pumpAndSettle();
      await settleViewer(tester, () async {
        await tester.tap(find.byKey(const Key("crop-reset")));
      });
      var post = requests.singleWhere((r) => r.method == "POST");
      expect(jsonDecode(post.body)[1].containsKey("crop"), isFalse);
      expect(image.crop, isNull);
      expect(find.byKey(const Key("image-region")), findsNothing);
    });

    testWidgets("is refused offline", (tester) async {
      var requests = <http.Request>[];
      var image = await pumpCropped(tester, cropClient(requests),
          crop: topRight(), offline: true);
      await openViewerMenu(tester);
      await tester.tap(cropEntry);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("crop-reset")));
      await tester.pumpAndSettle();
      expect(requests.where((r) => r.method == "POST"), isEmpty);
      expect(image.crop!.x, 0.5, reason: "nothing changed");
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
    });

    testWidgets("a refusal of the server says why and changes nothing",
        (tester) async {
      var requests = <http.Request>[];
      var image = await pumpCropped(
        tester,
        cropClient(requests,
            answer: (_) => http.Response(
                  '["ErrorInfo", {"message": "Only an editor of this album may crop its photographs."}]',
                  403,
                  headers: {"content-type": "application/json"},
                )),
        crop: topRight(),
      );
      await openViewerMenu(tester);
      await tester.tap(cropEntry);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("crop-reset")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("crop-failed")), findsOneWidget);
      expect(
          find.text("Only an editor of this album may crop its photographs."),
          findsOneWidget);
      expect(image.crop!.x, 0.5);
    });

    testWidgets("an unchanged frame writes nothing", (tester) async {
      var requests = <http.Request>[];
      await pumpCropped(tester, cropClient(requests), crop: topRight());
      await openViewerMenu(tester);
      await tester.tap(cropEntry);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(requests.where((r) => r.method == "POST"), isEmpty);
    });
  });

  testWidgets("the download is the whole original", (tester) async {
    var saver = installSaver();
    var requests = <http.Request>[];
    await pumpCropped(tester, cropClient(requests), crop: topRight());
    await settleViewer(tester, () => tapViewerDownload(tester));
    var fetched = requests.where(
        (r) => r.url.path == "/valbum/data/album/a.jpg" && r.url.query.isEmpty);
    expect(fetched, isNotEmpty);
    expect(saver.saved.single.bytes, originalBytes);
    expect(saver.saved.single.name, "a.jpg");
  });
}
