/// A [VideoPlayerPlatform] for widget tests: no platform channel, no network.
///
/// Shared by every test that drives a [VideoView] or a [VideoTeaser] — the
/// video tests of issue #59/#73 and the renditions and teasers of issue #75.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

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

