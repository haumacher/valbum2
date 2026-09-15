/// Inline playback of the videos of an album.
library;

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import 'diagnostics.dart';

/// The headline shown when a video cannot be played (issue #73).
///
/// English, like the rest of this file and of the image viewer: a refusal
/// speaks in the language of the screen it appears on.
const String videoErrorHeadline = "Cannot play this video.";

/// What the person is told when the video could not be fetched at all.
///
/// Both halves in one sentence on purpose: from here the two cannot be told
/// apart — a server that is not on this network and a server that refused the
/// request look the same to the player.
const String videoNetworkHint =
    "The server could not be reached, or it refused the video.";

/// What the person is told when the video arrived but cannot be decoded.
const String videoFormatHint =
    "This device cannot play the format of this video.";

/// Where the raw failure is, for a bug report.
const String videoErrorDiagnosticsHint =
    "The technical details are in the diagnostics log of the server settings.";

/// Words in a player failure that mean the *contents* cannot be decoded.
///
/// Checked before [_networkWords], because a container nothing can read
/// surfaces as an ExoPlayer "Source error" carrying an
/// `UnrecognizedInputFormatException`: the bytes did arrive, so it is the
/// format that is the problem, and saying "the server could not be reached"
/// would send the person looking in the wrong place.
const List<String> _formatWords = [
  "unrecognized",
  "decoder",
  "codec",
  "extractor",
  "unsupported",
  "renderer error",
  "malformed",
  "parser",
  "no suitable",
];

/// Words in a player failure that mean the video never arrived.
const List<String> _networkWords = [
  "source error",
  "httpdatasource",
  "cleartext",
  "unable to connect",
  "failed to connect",
  "connection",
  "connect timed out",
  "response code",
  "network",
  "socket",
  "timeout",
  "unknown host",
  "refused",
  "not permitted",
];

/// The one hint the person gets besides [videoErrorHeadline], or `null` where
/// the failure cannot be classified (issue #73).
///
/// The classification is deliberately a keyword match on the platform's own
/// message and deliberately allowed to answer nothing: the player's failures
/// are strings from three different platforms, and a wrong hint is worse than
/// none — what is certain is in the log either way, see
/// [videoErrorDiagnosticsHint].
String? videoErrorHint(Object problem) {
  var text = problem.toString().toLowerCase();
  for (var word in _formatWords) {
    if (text.contains(word)) {
      return videoFormatHint;
    }
  }
  for (var word in _networkWords) {
    if (text.contains(word)) {
      return videoNetworkHint;
    }
  }
  return null;
}

/// Creates the controller playing the video at the given URL.
///
/// Injected into [VideoView] so that tests can supply a controller that fails
/// or never initialises.
typedef VideoControllerFactory = VideoPlayerController Function(
  Uri url, {
  Map<String, String> headers,
});

/// The [VideoControllerFactory] used in production: a plain network player.
///
/// The headers carry the device token: a server started with `--auth all`
/// refuses an anonymous request, and the player opens a connection of its own.
VideoPlayerController networkController(
  Uri url, {
  Map<String, String> headers = const {},
}) =>
    VideoPlayerController.networkUrl(url, httpHeaders: headers);

/// Plays a single video, showing its poster image until playback can start.
///
/// This is the video counterpart of the `Image.network` the image viewer shows
/// for a still image: it fills the slot it is given, starts playing as soon
/// as the controller is initialised (as the retired GWT UI did with
/// `<video controls autoplay>`) and offers a play/pause button and a progress
/// bar. While the video is not (yet) playable, the poster - the server's video
/// thumbnail - is shown; if the controller cannot be initialised at all, the
/// failing URL and one plain sentence are displayed on top of the poster —
/// never the raw `PlatformException` of the platform player, which said
/// nothing to the person who reported issue #73. The raw text goes to the
/// [VideoView.log], where a bug report can fetch it.
class VideoView extends StatefulWidget {
  /// The URL of the video itself (the "original" URL of the image part).
  final String videoUrl;

  /// The URL of the poster shown until the video is playable.
  final String posterUrl;

  /// The headers every request of this view carries, see [networkController].
  final Map<String, String> headers;

  /// Whether to start playing as soon as the video is initialised.
  final bool autoPlay;

  /// Creates the controller for [videoUrl], see [VideoControllerFactory].
  final VideoControllerFactory createController;

  /// Where the raw text of a failure is written (issue #73).
  ///
  /// The log of the client the view was built from, see `image_view.dart`;
  /// `null` in a view pumped on its own, which then simply logs nothing.
  final DiagnosticsLog? log;

  const VideoView({
    super.key,
    required this.videoUrl,
    required this.posterUrl,
    this.headers = const {},
    this.autoPlay = true,
    this.createController = networkController,
    this.log,
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
      controller = widget.createController(
        Uri.parse(widget.videoUrl),
        headers: widget.headers,
      );
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
    // The whole text, before anything is shown: what the screen says is a
    // sentence, and the platform's own words belong in the bug report — the
    // `!!` is the convention of [DiagnosticsLog.failed].
    widget.log?.add("video ${maskUrl(widget.videoUrl)} !! $problem");
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
          headers: widget.headers,
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
        // The error box gets the whole slot, loosely: [Center] passes the
        // tight constraints the expanded [Stack] gave it on as a maximum, so
        // the box knows how much room there is and can scroll inside it
        // instead of overflowing it, see [buildError].
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

  /// The message shown when the video cannot be played at all (issue #73).
  ///
  /// One plain sentence, the URL that was refused, and — where the platform's
  /// message can be classified — one hint saying where to look, see
  /// [videoErrorHint]. Never the raw `PlatformException`: that is the thing
  /// the author of the report could make nothing of, and it is in the
  /// diagnostics log instead.
  ///
  /// The box fits whatever slot the view has: a video tile in landscape is a
  /// few dozen pixels tall, a long URL wraps over several lines, and four
  /// sentences do not fit either. It therefore *scrolls* inside the slot
  /// rather than being cut off — the panel shrink-wraps its content while it
  /// fits and stops growing at the slot's edge, see the [Center] in [build]
  /// that hands it the slot loosely. Nothing here is ever truncated: a refusal
  /// that cannot be read to its end is the bug of issue #73 all over again.
  Widget buildError(Object problem) {
    var hint = videoErrorHint(problem);
    return Container(
      key: const Key("video-error"),
      color: Colors.black87,
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.videocam_off, color: Colors.white, size: 32),
            const SizedBox(height: 8),
            const Text(
              videoErrorHeadline,
              key: Key("video-error-headline"),
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            if (hint != null) ...[
              const SizedBox(height: 8),
              Text(
                hint,
                key: const Key("video-error-hint"),
                style: const TextStyle(color: Colors.white70),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 8),
            Text(
              widget.videoUrl,
              key: const Key("video-error-url"),
              style: const TextStyle(color: Colors.white70),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            const Text(
              videoErrorDiagnosticsHint,
              key: Key("video-error-diagnostics"),
              style: TextStyle(color: Colors.white54, fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
