/// Inline playback of the videos of an album.
library;

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// Creates the controller playing the video at the given URL.
///
/// Injected into [VideoView] so that tests can supply a controller that fails
/// or never initialises.
typedef VideoControllerFactory = VideoPlayerController Function(Uri url);

/// The [VideoControllerFactory] used in production: a plain network player.
VideoPlayerController networkController(Uri url) =>
    VideoPlayerController.networkUrl(url);

/// Plays a single video, showing its poster image until playback can start.
///
/// This is the video counterpart of the `Image.network` the image viewer shows
/// for a still image: it fills the slot it is given, starts playing as soon
/// as the controller is initialised (as the retired GWT UI did with
/// `<video controls autoplay>`) and offers a play/pause button and a progress
/// bar. While the video is not (yet) playable, the poster - the server's video
/// thumbnail - is shown; if the controller cannot be initialised at all, the
/// failing URL and the error are displayed on top of the poster.
class VideoView extends StatefulWidget {
  /// The URL of the video itself (the "original" URL of the image part).
  final String videoUrl;

  /// The URL of the poster shown until the video is playable.
  final String posterUrl;

  /// Whether to start playing as soon as the video is initialised.
  final bool autoPlay;

  /// Creates the controller for [videoUrl], see [VideoControllerFactory].
  final VideoControllerFactory createController;

  const VideoView({
    super.key,
    required this.videoUrl,
    required this.posterUrl,
    this.autoPlay = true,
    this.createController = networkController,
  });

  @override
  State<VideoView> createState() => VideoViewState();
}

class VideoViewState extends State<VideoView> {
  VideoPlayerController? _controller;

  /// The problem that kept the video from playing, `null` if there is none.
  Object? _error;

  /// Whether the controller has reported a playable video.
  bool get isPlayable => _controller?.value.isInitialized ?? false;

  @override
  void initState() {
    super.initState();
    _open();
  }

  @override
  void didUpdateWidget(VideoView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.videoUrl != widget.videoUrl) {
      _close();
      _error = null;
      _open();
    }
  }

  @override
  void dispose() {
    _close();
    super.dispose();
  }

  void _close() {
    var controller = _controller;
    _controller = null;
    if (controller != null) {
      controller.removeListener(_update);
      controller.dispose();
    }
  }

  /// Creates the controller and starts playing, or records the failure.
  Future<void> _open() async {
    VideoPlayerController controller;
    try {
      controller = widget.createController(Uri.parse(widget.videoUrl));
    } catch (problem) {
      _failed(problem);
      return;
    }
    _controller = controller;
    controller.addListener(_update);

    try {
      await controller.initialize();
      if (!mounted || _controller != controller) {
        return;
      }
      await controller.setLooping(false);
      if (widget.autoPlay) {
        await controller.play();
      }
    } catch (problem) {
      if (_controller == controller) {
        _failed(problem);
      }
      return;
    }
    _update();
  }

  void _failed(Object problem) {
    if (!mounted) {
      _error = problem;
      return;
    }
    setState(() => _error = problem);
  }

  /// Rebuilds for the current controller state (playing, position, ...).
  void _update() {
    if (mounted) {
      setState(() {});
    }
  }

  /// Starts or stops playback.
  void togglePlay() {
    var controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return;
    }
    if (controller.value.isPlaying) {
      controller.pause();
    } else {
      controller.play();
    }
  }

  @override
  Widget build(BuildContext context) {
    var controller = _controller;
    return Stack(
      fit: StackFit.expand,
      children: [
        Image.network(
          widget.posterUrl,
          fit: BoxFit.contain,
        ),
        if (controller != null && isPlayable)
          Center(
            child: AspectRatio(
              aspectRatio: controller.value.aspectRatio,
              child: VideoPlayer(controller),
            ),
          ),
        if (controller != null && isPlayable)
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: buildControls(controller),
          ),
        if (_error != null)
          Center(
            child: buildError(_error!),
          ),
      ],
    );
  }

  /// The play/pause button and the progress bar.
  Widget buildControls(VideoPlayerController controller) => Container(
        key: const Key("video-controls"),
        color: Colors.black54,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        child: Row(
          children: [
            IconButton(
              key: const Key("video-play-pause"),
              icon: Icon(
                controller.value.isPlaying ? Icons.pause : Icons.play_arrow,
              ),
              color: Colors.white,
              tooltip: controller.value.isPlaying ? "Pause" : "Play",
              onPressed: togglePlay,
            ),
            Expanded(
              child: VideoProgressIndicator(
                controller,
                allowScrubbing: true,
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
            ),
          ],
        ),
      );

  /// The message shown when the video cannot be played at all.
  ///
  /// It names the URL and the problem: a refusal has to say what was refused.
  Widget buildError(Object problem) => Container(
        key: const Key("video-error"),
        color: Colors.black87,
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.videocam_off, color: Colors.white, size: 32),
            const SizedBox(height: 8),
            const Text(
              "Cannot play this video.",
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              widget.videoUrl,
              style: const TextStyle(color: Colors.white70),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              "$problem",
              style: const TextStyle(color: Colors.white70),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
}
