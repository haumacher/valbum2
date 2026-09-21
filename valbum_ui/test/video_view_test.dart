import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/thumbnails.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player/video_player.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// A [VideoControllerFactory] that cannot even create a controller.
VideoPlayerController failingFactory(
  Uri url, {
  Map<String, String> headers = const {},
}) =>
    throw StateError("No player for $url.");

ImagePart videoPart({
  String name = "clip.mp4",
  ImageKind kind = ImageKind.video,
}) =>
    ImagePart(name: name, kind: kind, width: 1920, height: 1080);

/// Links the given images into an album, as loading an album does.
AlbumInfo linkedAlbum(List<AbstractImage> images) {
  var album = AlbumInfo(parts: images);
  for (var i = 0; i < images.length; i++) {
    var image = images[i];
    image.owner = album;
    image.previous = i > 0 ? images[i - 1] : null;
    image.next = i < images.length - 1 ? images[i + 1] : null;
    image.home = images.first;
    image.end = images.last;
  }
  return album;
}

/// Shows the viewer for the given part (no route push, so no settling).
Future<void> pumpViewer(WidgetTester tester, AbstractImage image) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: ImageView(
          client: clientReturning("{}"),
          baseUrl: "http://server/valbum/data/album",
          image: image,
          onShowImage: (next) {},
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  });
}

/// Shows a bare [VideoView] filling the screen.
Future<void> pumpVideo(
  WidgetTester tester, {
  VideoControllerFactory createController = networkController,
  bool autoPlay = true,
  String videoUrl = "http://server/valbum/data/album/clip.mp4",
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: videoUrl,
          posterUrl: "$videoUrl?type=tn",
          autoPlay: autoPlay,
          createController: createController,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  });
}

/// The URLs of all images currently displayed.
///
/// A still image is drawn in two layers since issue #101 — the thumbnail, and
/// the picture over it — so both kinds of provider are read here.
List<String> shownUrls(WidgetTester tester) => tester
    .widgetList<Image>(find.byType(Image))
    .map((image) => urlOf(image.image))
    .toList();

/// The URL a provider of the viewer fetches from.
String urlOf(ImageProvider provider) => switch (provider) {
      NetworkImage(url: var url) => url,
      _ => thumbnailOf(provider)?.url ?? "$provider",
    };

void main() {
  late FakeVideoPlayerPlatform platform;

  setUp(() {
    platform = FakeVideoPlayerPlatform();
    VideoPlayerPlatform.instance = platform;
  });

  testWidgets('the viewer plays a video inline', (tester) async {
    var video = videoPart();
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    expect(find.byType(VideoView), findsOneWidget);
    // The *rendition*, not the original: the viewer plays what the server
    // transcoded for playing, see issue #75. The original stays the download.
    expect(
      platform.dataSources.single.uri,
      "http://server/valbum/data/album/clip.mp4?type=video",
    );

    // The poster is the server's video thumbnail.
    expect(
      shownUrls(tester),
      contains("http://server/valbum/data/album/clip.mp4?type=tn"),
    );
  });

  testWidgets('the viewer plays a quicktime video inline', (tester) async {
    var video = videoPart(name: "clip.mov", kind: ImageKind.quicktime);
    linkedAlbum([video]);

    await pumpViewer(tester, video);

    expect(find.byType(VideoView), findsOneWidget);
    expect(
      platform.dataSources.single.uri,
      "http://server/valbum/data/album/clip.mov?type=video",
    );
  });

  testWidgets('the viewer shows an image without a player', (tester) async {
    var image = ImagePart(name: "a.jpg", width: 2000, height: 1000);
    linkedAlbum([image]);

    await pumpViewer(tester, image);

    expect(find.byType(VideoView), findsNothing);
    expect(shownUrls(tester), [
      "http://server/valbum/data/album/a.jpg?type=tn",
      "http://server/valbum/data/album/a.jpg",
    ]);
  });

  testWidgets('the viewer keeps its chrome around a video', (tester) async {
    var images = [videoPart(name: "a.mp4"), videoPart(name: "b.mp4")];
    linkedAlbum(images);

    await pumpViewer(tester, images[0]);

    expect(find.byIcon(Icons.arrow_back), findsOneWidget);
    expect(find.byIcon(Icons.chevron_right), findsOneWidget);
  });

  testWidgets('the video shows its poster and starts playing', (tester) async {
    await pumpVideo(tester);

    expect(
      shownUrls(tester),
      ["http://server/valbum/data/album/clip.mp4?type=tn"],
    );
    expect(find.byKey(const Key("video-controls")), findsOneWidget);
    expect(platform.calls, contains("play"));
  });

  testWidgets('the play button starts and stops the video', (tester) async {
    await pumpVideo(tester, autoPlay: false);

    expect(platform.calls, isNot(contains("play")));
    expect(find.byIcon(Icons.play_arrow), findsOneWidget);

    await tester.tap(find.byKey(const Key("video-play-pause")));
    await tester.pump();

    expect(platform.calls, contains("play"));
    expect(find.byIcon(Icons.pause), findsOneWidget);

    await tester.tap(find.byKey(const Key("video-play-pause")));
    await tester.pump();

    expect(platform.calls, contains("pause"));
    expect(find.byIcon(Icons.play_arrow), findsOneWidget);
  });

  testWidgets('a video that cannot be opened says so', (tester) async {
    await pumpVideo(tester, createController: failingFactory);

    expect(find.byKey(const Key("video-error")), findsOneWidget);

    // The message names the URL and says one plain sentence; the raw failure
    // is in the diagnostics log, not on the screen, see issue #73 and
    // `video_error_test.dart`.
    expect(
      find.text("http://server/valbum/data/album/clip.mp4"),
      findsOneWidget,
    );
    expect(find.text(videoErrorHeadline), findsOneWidget);
    expect(find.textContaining("No player for"), findsNothing);

    // The poster stays visible behind the message.
    expect(
      shownUrls(tester),
      ["http://server/valbum/data/album/clip.mp4?type=tn"],
    );
    expect(find.byKey(const Key("video-controls")), findsNothing);
  });

  testWidgets('a video the platform rejects says so', (tester) async {
    platform.failInit = true;

    await pumpVideo(tester);

    expect(find.byKey(const Key("video-error")), findsOneWidget);
    expect(find.text(videoErrorHeadline), findsOneWidget);
    expect(find.textContaining("PlatformException"), findsNothing);
    expect(
      shownUrls(tester),
      ["http://server/valbum/data/album/clip.mp4?type=tn"],
    );
  });

  testWidgets('leaving the video disposes the player', (tester) async {
    await pumpVideo(tester, autoPlay: false);

    await tester.pumpWidget(const MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: SizedBox()));

    // The controller disposes asynchronously, off the fake clock.
    await tester.runAsync(() async {});

    expect(platform.calls, contains("dispose"));
  });
}
