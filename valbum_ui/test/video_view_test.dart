import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player/video_player.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// A [VideoPlayerPlatform] that answers without any platform channel.
///
/// Only the calls [VideoPlayerController] makes are implemented; the video
/// itself is a 100x100 clip of one second. Modelled after the fake the
/// `video_player` package uses in its own tests.
class FakeVideoPlayerPlatform extends VideoPlayerPlatform {
  /// The data sources the controllers were created for, in order.
  final List<DataSource> dataSources = <DataSource>[];

  /// The platform calls made, in order (`play`, `pause`, ...).
  final List<String> calls = <String>[];

  /// Whether creating a player reports an error instead of a video.
  bool failInit = false;

  final Map<int, StreamController<VideoEvent>> _streams =
      <int, StreamController<VideoEvent>>{};
  int _nextPlayerId = 0;

  @override
  Future<void> init() async {}

  @override
  Future<int?> createWithOptions(VideoCreationOptions options) async {
    calls.add("create");
    dataSources.add(options.dataSource);
    var playerId = _nextPlayerId++;
    var stream = StreamController<VideoEvent>();
    _streams[playerId] = stream;
    if (failInit) {
      stream.addError(
        PlatformException(code: "VideoError", message: "Cannot open video"),
      );
    } else {
      stream.add(
        VideoEvent(
          eventType: VideoEventType.initialized,
          size: const Size(100, 100),
          duration: const Duration(seconds: 1),
        ),
      );
    }
    return playerId;
  }

  @override
  Stream<VideoEvent> videoEventsFor(int playerId) => _streams[playerId]!.stream;

  @override
  Future<void> dispose(int playerId) async {
    calls.add("dispose");
    await _streams.remove(playerId)?.close();
  }

  @override
  Future<void> play(int playerId) async => calls.add("play");

  @override
  Future<void> pause(int playerId) async => calls.add("pause");

  @override
  Future<void> setLooping(int playerId, bool looping) async =>
      calls.add("setLooping");

  @override
  Future<void> setVolume(int playerId, double volume) async =>
      calls.add("setVolume");

  @override
  Future<void> setPlaybackSpeed(int playerId, double speed) async =>
      calls.add("setPlaybackSpeed");

  @override
  Future<void> seekTo(int playerId, Duration position) async =>
      calls.add("seekTo");

  @override
  Future<Duration> getPosition(int playerId) async => Duration.zero;

  @override
  Widget buildViewWithOptions(VideoViewOptions options) =>
      const SizedBox.expand(key: Key("fake-video-surface"));
}

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
List<String> shownUrls(WidgetTester tester) => tester
    .widgetList<Image>(find.byType(Image))
    .map((image) => (image.image as NetworkImage).url)
    .toList();

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
    expect(
      platform.dataSources.single.uri,
      "http://server/valbum/data/album/clip.mp4",
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
      "http://server/valbum/data/album/clip.mov",
    );
  });

  testWidgets('the viewer shows an image without a player', (tester) async {
    var image = ImagePart(name: "a.jpg", width: 2000, height: 1000);
    linkedAlbum([image]);

    await pumpViewer(tester, image);

    expect(find.byType(VideoView), findsNothing);
    expect(shownUrls(tester), ["http://server/valbum/data/album/a.jpg"]);
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

    // The message names the URL and the problem.
    expect(
      find.text("http://server/valbum/data/album/clip.mp4"),
      findsOneWidget,
    );
    expect(
      find.textContaining("No player for"),
      findsOneWidget,
    );

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
    expect(find.textContaining("Cannot open video"), findsOneWidget);
    expect(
      shownUrls(tester),
      ["http://server/valbum/data/album/clip.mp4?type=tn"],
    );
  });

  testWidgets('leaving the video disposes the player', (tester) async {
    await pumpVideo(tester, autoPlay: false);

    await tester.pumpWidget(const MaterialApp(home: SizedBox()));

    // The controller disposes asynchronously, off the fake clock.
    await tester.runAsync(() async {});

    expect(platform.calls, contains("dispose"));
  });
}
