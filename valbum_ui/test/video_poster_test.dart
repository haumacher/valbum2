/// The poster of a video chosen in the viewer (issue #285): an editor stops
/// the video and chooses "Use as thumbnail" in the viewer's menu or in the
/// menu of a right click on the video; the moment the player stands at is
/// posted at once, the viewer's poster and the album's tile then ask for the
/// frame of that moment, "Reset thumbnail" takes it away, and a refusal is
/// said in the server's own words.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/crop.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'crop_album_test.dart' show tileUrl;
import 'move_test.dart' show json, pathOf, tile;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// What the server answers a poster with: the album, the video carrying the
/// moment [millis].
String posteredAlbum(int millis) => AlbumInfo(title: "Album", parts: [
      ImagePart(
        name: "clip.mp4",
        kind: ImageKind.video,
        width: 2000,
        height: 1000,
        poster: millis > 0 ? VideoPoster(millis: millis) : null,
      ),
    ]).toString();

/// A client answering a poster with [answer], recording every request.
VAlbumClient posterClient(
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
            request.url.queryParameters["action"] == "poster") {
          if (answer != null) {
            return answer(request);
          }
          var part = AbstractImage.read(JsonReader.fromString(request.body))
              as ImagePart;
          return http.Response(posteredAlbum(part.poster?.millis ?? 0), 200,
              headers: {"content-type": "application/json"});
        }
        if (request.url.queryParameters["type"] == "video") {
          return http.Response.bytes([0, 0, 0, 0], 200,
              headers: {"content-type": "video/mp4"});
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

late FakeVideoPlayerPlatform platform;

/// Shows `clip.mp4` in the viewer, stopped at 3.2 s.
Future<ImagePart> pumpPaused(
  WidgetTester tester,
  VAlbumClient client, {
  List<String> rights = const ["view", "download", "contribute", "edit"],
  int poster = 0,
  bool offline = false,
}) async {
  withEmptyImageCache();
  var image = viewerImagePart("clip.mp4", kind: ImageKind.video)
    ..poster = poster > 0 ? VideoPoster(millis: poster) : null;
  viewerAlbum([image], rights: rights);
  fakeImageRequests();
  await pumpViewerHarness(
    tester,
    image,
    client: client,
    caller: const CallerInfo(userName: "anna", role: roleMember, space: "anna"),
    editPath: const ["album"],
    offline: offline,
  );
  await tester.pumpAndSettle();
  expect(find.byKey(const Key("video-play-pause")), findsOneWidget,
      reason: "the player is up");
  // Stopped by the person, at 3.2 s.
  platform.position = const Duration(milliseconds: 3200);
  var player = tester.state<VideoViewState>(find.byType(VideoView));
  if (player.shownPosition != null) {
    await tester.tap(find.byKey(const Key("video-play-pause")));
    await tester.pumpAndSettle();
  }
  return image;
}

Future<void> openViewerMenu(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("viewer-menu")));
  await tester.pumpAndSettle();
}

final Finder useEntry = find.byKey(const Key("viewer-use-as-poster"));

final Finder resetEntry = find.byKey(const Key("viewer-reset-poster"));

/// The address the viewer's poster is fetched at.
String posterUrl(WidgetTester tester) =>
    tester.widget<VideoView>(find.byType(VideoView)).posterUrl;

/// The server of the album `Videos`: a video and a photograph, and the
/// poster the viewer chooses is remembered.
class FakeVideoAlbumServer {
  final AlbumInfo album = AlbumInfo(
    title: "Videos",
    rights: [
      for (var name in ["view", "download", "contribute", "edit"])
        RightName(name: name),
    ],
    parts: [
      ImagePart(
          name: "clip.mp4",
          kind: ImageKind.video,
          date: 1,
          width: 1920,
          height: 1080),
      ImagePart(name: "a.jpg", date: 2, width: 2000, height: 1000),
    ],
  );

  final List<http.Request> requests = [];

  ImagePart get video => album.parts.first as ImagePart;

  VAlbumClient client() => VAlbumClient(
        dataUrl: viewerDataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          if (request.url.queryParameters["type"] == "video") {
            return http.Response.bytes([0, 0, 0, 0], 200,
                headers: {"content-type": "video/mp4"});
          }
          var path = pathOf(request);
          if (path != "/valbum/data/Videos/") {
            return http.Response("No such resource: $path", 404);
          }
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == "poster") {
            var part = AbstractImage.read(JsonReader.fromString(request.body))
                as ImagePart;
            video.poster = part.poster;
          }
          return json(album.toString());
        }),
      );
}

void main() {
  setUp(() {
    platform = FakeVideoPlayerPlatform()
      ..videoDuration = const Duration(seconds: 10);
    VideoPlayerPlatform.instance = platform;
  });

  group("the menu", () {
    testWidgets("offers Use as thumbnail to an editor", (tester) async {
      await pumpPaused(tester, posterClient([]));
      await openViewerMenu(tester);
      expect(useEntry, findsOneWidget);
      expect(find.text(testL10n.posterUse), findsOneWidget);
      expect(resetEntry, findsNothing, reason: "no moment is chosen yet");
    });

    testWidgets("not to a viewer", (tester) async {
      await pumpPaused(tester, posterClient([]),
          rights: const ["view", "download"]);
      await openViewerMenu(tester);
      expect(useEntry, findsNothing);
      expect(resetEntry, findsNothing);
    });

    testWidgets("not on a photograph", (tester) async {
      withEmptyImageCache();
      var image = viewerImagePart("a.jpg");
      viewerAlbum([image], rights: const ["view", "download", "edit"]);
      fakeImageRequests();
      await pumpViewerHarness(tester, image,
          client: posterClient([]),
          caller: const CallerInfo(
              userName: "anna", role: roleMember, space: "anna"),
          editPath: const ["album"]);
      await openViewerMenu(tester);
      expect(useEntry, findsNothing);
    });

    testWidgets("Reset stands where a moment is chosen", (tester) async {
      await pumpPaused(tester, posterClient([]), poster: 1500);
      await openViewerMenu(tester);
      expect(useEntry, findsOneWidget);
      expect(resetEntry, findsOneWidget);
      expect(find.text(testL10n.posterReset), findsOneWidget);
    });

    testWidgets("a right click on the video opens the same entries",
        (tester) async {
      await pumpPaused(tester, posterClient([]), poster: 1500);
      await tester.tap(find.byType(VideoView), buttons: kSecondaryButton);
      await tester.pumpAndSettle();
      expect(useEntry, findsOneWidget);
      expect(resetEntry, findsOneWidget);
    });

    testWidgets(
        "a right click offers a viewer no poster, the rest of the menu "
        "(issue #294)", (tester) async {
      await pumpPaused(tester, posterClient([]), rights: const ["view"]);
      await tester.tap(find.byType(VideoView), buttons: kSecondaryButton);
      await tester.pumpAndSettle();
      expect(useEntry, findsNothing);
      expect(find.byKey(const Key("viewer-properties")), findsOneWidget);
    });
  });

  group("the write", () {
    testWidgets("posts the moment the player stands at, and the poster follows",
        (tester) async {
      var requests = <http.Request>[];
      var image = await pumpPaused(tester, posterClient(requests));
      expect(posterUrl(tester), "$viewerBaseUrl/clip.mp4?type=tn");
      await openViewerMenu(tester);
      await tester.tap(useEntry);
      await tester.pumpAndSettle();

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(post.url.path, "/valbum/data/album/");
      expect(post.url.queryParameters["action"], "poster");
      var sent =
          AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
      expect(sent.name, "clip.mp4");
      expect(sent.poster!.millis, 3200);

      expect(image.poster!.millis, 3200, reason: "the album's own part");
      expect(croppedImageUrl("$viewerBaseUrl/clip.mp4", image),
          "$viewerBaseUrl/clip.mp4?poster=3200",
          reason: "the album's tile asks for the new poster");
      expect(posterUrl(tester), "$viewerBaseUrl/clip.mp4?poster=3200&type=tn",
          reason: "the viewer's poster is the new one");
      expect(find.byKey(const Key("poster-failed")), findsNothing);
    });

    testWidgets("Reset posts no moment", (tester) async {
      var requests = <http.Request>[];
      var image =
          await pumpPaused(tester, posterClient(requests), poster: 1500);
      expect(posterUrl(tester), "$viewerBaseUrl/clip.mp4?poster=1500&type=tn");
      await openViewerMenu(tester);
      await tester.tap(resetEntry);
      await tester.pumpAndSettle();

      var post = requests.singleWhere((r) => r.method == "POST");
      var sent =
          AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
      expect(sent.poster, isNull);
      expect(image.poster, isNull);
      expect(posterUrl(tester), "$viewerBaseUrl/clip.mp4?type=tn");
    });

    testWidgets("a refusal is said in the server's words", (tester) async {
      var image = await pumpPaused(
        tester,
        posterClient([], answer: (request) {
          return http.Response(
              ErrorInfo(
                      message: "Only an editor of this album may choose the "
                          "thumbnail of its videos.")
                  .toString(),
              403,
              headers: {"content-type": "application/json"});
        }),
      );
      await openViewerMenu(tester);
      await tester.tap(useEntry);
      await tester.pumpAndSettle();
      var failure = find.byKey(const Key("poster-failed"));
      expect(failure, findsOneWidget);
      expect(tester.widget<Text>(failure).data,
          "Only an editor of this album may choose the thumbnail of its videos.");
      expect(image.poster, isNull, reason: "nothing changed");
      expect(posterUrl(tester), "$viewerBaseUrl/clip.mp4?type=tn");
    });

    testWidgets("is refused offline", (tester) async {
      var requests = <http.Request>[];
      await pumpPaused(tester, posterClient(requests), offline: true);
      await openViewerMenu(tester);
      await tester.tap(useEntry);
      await tester.pumpAndSettle();
      expect(requests.where((r) => r.method == "POST"), isEmpty);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
    });
  });

  testWidgets("the album's tile shows the new poster when the viewer is left",
      (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    var server = FakeVideoAlbumServer();
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: server.client(),
        initialRoute: const ListingOrAlbumRoute(["Videos"]),
      ));
      await tester.pumpAndSettle();
    });
    expect(
        tileUrl(tester, "clip.mp4"), "$viewerDataUrl/Videos/clip.mp4?type=tn");

    await withFakeImageHttp(() async {
      await tester.tap(tile("a.jpg"));
      await tester.pumpAndSettle();
      // Paged onto the video, which plays; stopped at 3.2 s.
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pumpAndSettle();
    });
    expect(find.byType(VideoView), findsOneWidget);
    platform.position = const Duration(milliseconds: 3200);
    await tester.tap(find.byKey(const Key("video-play-pause")));
    await tester.pumpAndSettle();
    await openViewerMenu(tester);
    await tester.tap(useEntry);
    await tester.pumpAndSettle();
    expect(server.video.poster!.millis, 3200);

    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
    });
    expect(find.byType(VideoView), findsNothing);
    expect(tileUrl(tester, "clip.mp4"),
        "$viewerDataUrl/Videos/clip.mp4?poster=3200&type=tn");
  });
}
