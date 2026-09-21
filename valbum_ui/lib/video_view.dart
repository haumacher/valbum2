/// Inline playback of the videos of an album.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:video_player/video_player.dart';

import 'client.dart';
import 'diagnostics.dart';
import 'l10n/app_localizations.dart';
import 'oriented_thumbnail.dart';
import 'resource.dart';

/// Asks the server whether a rendition can be played, see
/// [VAlbumClient.renditionState].
///
/// Injected rather than the whole client: what this view needs is one
/// question, and a test answers it without a transport.
typedef RenditionProbe = Future<RenditionState> Function(String url);

/// Waits [delay]; injected so that a test does not sit out a `Retry-After`.
typedef Wait = Future<void> Function(Duration delay);

/// The wait of a running app.
Future<void> realWait(Duration delay) => Future<void>.delayed(delay);

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

/// The one hint the person gets besides `videoCannotPlay`, or `null` where
/// the failure cannot be classified (issue #73).
///
/// The classification is deliberately a keyword match on the platform's own
/// message and deliberately allowed to answer nothing: the player's failures
/// are strings from three different platforms, and a wrong hint is worse than
/// none — what is certain is in the log either way, see
/// `videoDiagnosticsHint`.
///
/// It takes the [AppLocalizations] rather than keeping English of its own
/// (issue #108): the classification is the app's, the words are the ARB's.
String? videoErrorHint(AppLocalizations l10n, Object problem) {
  var text = problem.toString().toLowerCase();
  for (var word in _formatWords) {
    if (text.contains(word)) {
      return l10n.videoFormatHint;
    }
  }
  for (var word in _networkWords) {
    if (text.contains(word)) {
      return l10n.videoNetworkHint;
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
///
/// What is played is the *rendition* where there is one (issues #74/#75): a
/// faststart 720p file the server transcodes beside the original, because a
/// 4K original costs two round trips for its index before the first frame and
/// then saturates the home network. [VideoView.renditionUrl] is probed before
/// anything is opened; while the server is still making it, the poster stays
/// up with [videoPreparingMessage] and the view asks again at the server's
/// `Retry-After`; where there will be none, the original is played instead.
/// [VideoView.videoUrl] is therefore always the original — the fallback, and
/// what [videoPlayOriginalLabel] opens.
class VideoView extends StatefulWidget {
  /// The URL of the video itself (the "original" URL of the image part).
  final String videoUrl;

  /// The URL of the poster shown until the video is playable.
  final String posterUrl;

  /// The headers every request of this view carries, see [networkController].
  final Map<String, String> headers;

  /// Whether to start playing as soon as the video is initialised.
  final bool autoPlay;

  /// Creates the controller for the URL that is played, see
  /// [VideoControllerFactory].
  final VideoControllerFactory createController;

  /// The URL of the playback rendition, `null` where there is none to ask for.
  ///
  /// With a [renditionUrl] and a [probeRendition] this view plays the
  /// rendition and falls back to [videoUrl]; without either it plays
  /// [videoUrl] straight away, which is what it did before issue #75.
  final String? renditionUrl;

  /// Asks whether [renditionUrl] can be played, see [RenditionProbe].
  final RenditionProbe? probeRendition;

  /// How the view waits out a `Retry-After`, see [Wait].
  final Wait wait;

  /// Where the raw text of a failure is written (issue #73).
  ///
  /// The log of the client the view was built from, see `image_view.dart`;
  /// `null` in a view pumped on its own, which then simply logs nothing.
  final DiagnosticsLog? log;

  /// The orientation stored beside the video (issue #116).
  ///
  /// The poster the server makes and the frames the player decodes are both
  /// upright *by the file*; [ImagePart.orientation] is the turn stored beside
  /// it, on top of what the file says — the same one the album tile is turned
  /// by since issue #106. Poster and player are wrapped in it together, see
  /// [orientedBox] and [VideoViewState.oriented]: a video the author rotated
  /// in the album played on its side here while its tile stood upright.
  ///
  /// The slot this view is given is the *oriented* one — the caller computes
  /// the fit from `Orientations.width/height`, see `ImageViewState
  /// .buildVideoViewer`. [Orientation.identity] wraps nothing at all.
  final Orientation orientation;

  const VideoView({
    super.key,
    required this.videoUrl,
    required this.posterUrl,
    this.headers = const {},
    this.autoPlay = true,
    this.createController = networkController,
    this.log,
    this.renditionUrl,
    this.probeRendition,
    this.wait = realWait,
    this.orientation = Orientation.identity,
  });

  @override
  State<VideoView> createState() => VideoViewState();
}

class VideoViewState extends State<VideoView> {
  VideoPlayerController? _controller;

  /// The problem that kept the video from playing, `null` if there is none.
  Object? _error;

  /// Whether the server is still making the rendition, see
  /// [videoPreparingMessage].
  bool _preparing = false;

  /// Which attempt is the current one.
  ///
  /// Every way of starting over — a new video, "Play the original" — makes
  /// this a new number, so that a retry loop that is still waiting out its
  /// `Retry-After` finds itself stale when it wakes up and stops.
  int _attempt = 0;

  /// Whether the controller has reported a playable video.
  bool get isPlayable => _controller?.value.isInitialized ?? false;

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  @override
  void didUpdateWidget(VideoView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.videoUrl != widget.videoUrl ||
        oldWidget.renditionUrl != widget.renditionUrl) {
      _close();
      _error = null;
      _preparing = false;
      _resolve();
    }
  }

  /// Decides what to play and plays it, see [VideoView.renditionUrl].
  ///
  /// The rendition where the server has it, the original where it says there
  /// will be none, and the poster with [videoPreparingMessage] for as long as
  /// it is being made — asking again at the interval the server itself named,
  /// never faster, see [VAlbumClient.renditionState].
  Future<void> _resolve() async {
    var rendition = widget.renditionUrl;
    var probe = widget.probeRendition;
    var attempt = ++_attempt;
    if (rendition == null || probe == null) {
      _open(widget.videoUrl);
      return;
    }
    while (mounted && _attempt == attempt) {
      RenditionState state;
      try {
        state = await probe(rendition);
      } catch (problem) {
        // The probe is a courtesy; a question that cannot be asked is not a
        // reason to refuse the video, it is a reason to play the original.
        widget.log?.add("video rendition ${maskUrl(rendition)} !! $problem");
        _fallBackToOriginal(attempt);
        return;
      }
      if (!mounted || _attempt != attempt) {
        return;
      }
      if (state.isReady) {
        if (_preparing) {
          setState(() => _preparing = false);
        }
        _open(rendition);
        return;
      }
      if (!state.isPending) {
        // There will be no rendition — a failed transcode, or a caller who may
        // not have it. The original is what is left, and if that fails too the
        // speaking error of issue #73 says so.
        widget.log?.add(
          "video rendition ${maskUrl(rendition)} unavailable"
          "${state.message == null ? "" : ": ${state.message}"}",
        );
        _fallBackToOriginal(attempt);
        return;
      }
      if (!_preparing) {
        setState(() => _preparing = true);
      }
      await widget.wait(state.retryAfter);
    }
  }

  /// Plays the original instead of the rendition, ending attempt [attempt].
  void _fallBackToOriginal(int attempt) {
    if (!mounted || _attempt != attempt) {
      return;
    }
    if (_preparing) {
      setState(() => _preparing = false);
    }
    _open(widget.videoUrl);
  }

  /// Stops waiting for the rendition and plays the original (issue #75).
  ///
  /// Offered while the rendition is being made: a transcode takes minutes on
  /// a small server, and the original was playable all along — it is only the
  /// worse thing to play, not an impossible one.
  void playOriginal() {
    _attempt++;
    _close();
    setState(() {
      _preparing = false;
      _error = null;
    });
    _open(widget.videoUrl);
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

  /// Creates the controller for [url] and starts playing, or records the
  /// failure.
  Future<void> _open(String url) async {
    VideoPlayerController controller;
    try {
      controller = widget.createController(
        Uri.parse(url),
        headers: widget.headers,
      );
    } catch (problem) {
      _failed(problem, url);
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
        _failed(problem, url);
      }
      return;
    }
    _update();
  }

  void _failed(Object problem, String url) {
    // The whole text, before anything is shown: what the screen says is a
    // sentence, and the platform's own words belong in the bug report — the
    // `!!` is the convention of [DiagnosticsLog.failed]. The URL is the one
    // that failed, which is the rendition where a rendition was played.
    widget.log?.add("video ${maskUrl(url)} !! $problem");
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
        oriented(
          Image.network(
            widget.posterUrl,
            headers: widget.headers,
            fit: BoxFit.contain,
          ),
        ),
        if (controller != null && isPlayable)
          oriented(
            Center(
              child: AspectRatio(
                aspectRatio: controller.value.aspectRatio,
                child: VideoPlayer(controller),
              ),
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
        // While the server is making the rendition: the poster, one line, and
        // the way past the wait, see [buildPreparing] and issue #75.
        if (_error == null && _preparing && !isPlayable)
          Center(
            child: buildPreparing(),
          ),
      ],
    );
  }

  /// [child] turned by the stored orientation (issue #116).
  ///
  /// The poster and the playing surface, and nothing else: the controls, the
  /// "being prepared" panel and the error box are chrome of the *screen*, not
  /// of the picture, and a rotated video must not hand the person a progress
  /// bar standing on its end. Both pictures go through the one wrapper — the
  /// same [orientedBox] the album tile and the image viewer use — so that the
  /// poster and the frames that replace it can never disagree.
  Widget oriented(Widget child) => orientedBox(widget.orientation, child);

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
              tooltip: controller.value.isPlaying
                  ? AppLocalizations.of(context)!.pause
                  : AppLocalizations.of(context)!.play,
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

  /// What is shown while the server is still making the rendition
  /// (issue #75).
  ///
  /// The poster stays behind it, the line says what is happening, and the
  /// button plays the original for whoever does not want to wait. It scrolls
  /// inside its slot for the same reason the error box does.
  Widget buildPreparing() {
    var l10n = AppLocalizations.of(context)!;
    return Container(
      key: const Key("video-preparing"),
      color: Colors.black87,
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.white70,
              ),
            ),
            const SizedBox(height: 12),
            Text(
              l10n.videoPreparing,
              key: const Key("video-preparing-line"),
              style: const TextStyle(color: Colors.white),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 12),
            TextButton(
              key: const Key("video-play-original"),
              onPressed: playOriginal,
              child: Text(
                l10n.videoPlayOriginal,
                style: const TextStyle(color: Colors.white),
              ),
            ),
          ],
        ),
      ),
    );
  }

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
    var l10n = AppLocalizations.of(context)!;
    var hint = videoErrorHint(l10n, problem);
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
            Text(
              l10n.videoCannotPlay,
              key: const Key("video-error-headline"),
              style: const TextStyle(
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
            Text(
              l10n.videoDiagnosticsHint,
              key: const Key("video-error-diagnostics"),
              style: const TextStyle(color: Colors.white54, fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

/// Whether a teaser is played when the pointer rests on a video tile
/// (issue #75).
///
/// Everywhere but Android and iOS, which is the same thing as "where there is
/// a pointer": a phone has no hover, and a phone *browser* reports itself as
/// Android or iOS too, so this is right on the web as well.
///
/// There is deliberately no teaser on a touch platform. The substitute would
/// be "the tile has stood still in the viewport for a moment", and that is a
/// worse deal than it looks: it needs every tile to watch the scroll position,
/// it fights the album's own scroll memory, and it would start a decoder and a
/// range request per tile while somebody flicks through a hundred photos on a
/// home Wi-Fi. The teaser is a pointer affordance; on a phone a tap opens the
/// video, which is what the tap is for.
bool teaserOnHoverSupported() =>
    defaultTargetPlatform != TargetPlatform.android &&
    defaultTargetPlatform != TargetPlatform.iOS;

/// Plays the teaser of a video while the pointer rests on its tile
/// (issue #75).
///
/// The tile itself is [child] — the poster the album lays out — and the teaser
/// is drawn over it, muted and looping, for as long as the pointer is there.
/// On exit the controller is disposed and the poster is all that is left: a
/// tile is a tile, and nothing of this survives the pointer leaving it.
///
/// A teaser the server has not made yet (`202`) is simply not played. Nothing
/// is shown for it and nothing is said: a teaser is a nicety, and a row of
/// error boxes over an album would be worse than no teaser at all. The next
/// hover asks once more, and after that this tile stops asking for as long as
/// it lives — one retry per view, never a retry storm, see issue #75.
class VideoTeaser extends StatefulWidget {
  /// The URL of the teaser, see [VAlbumClient.teaserUrl].
  final String teaserUrl;

  /// The headers the player sends, see [networkController].
  final Map<String, String> headers;

  /// Creates the controller playing the teaser.
  final VideoControllerFactory createController;

  /// Asks whether the teaser is there, `null` where nobody can be asked — the
  /// tile then stays a poster.
  final RenditionProbe? probeTeaser;

  /// Whether to offer the teaser at all; the platform decides where this is
  /// `null`, see [teaserOnHoverSupported].
  final bool? enabled;

  /// The tile as the album laid it out.
  final Widget child;

  const VideoTeaser({
    super.key,
    required this.teaserUrl,
    required this.child,
    this.headers = const {},
    this.createController = networkController,
    this.probeTeaser,
    this.enabled,
  });

  /// Whether this tile plays a teaser at all.
  bool get plays =>
      probeTeaser != null && (enabled ?? teaserOnHoverSupported());

  @override
  State<VideoTeaser> createState() => VideoTeaserState();
}

class VideoTeaserState extends State<VideoTeaser> {
  VideoPlayerController? _controller;

  /// How often this tile has asked for its teaser.
  int _asked = 0;

  /// Whether this tile has stopped asking, see [VideoTeaser].
  bool _givenUp = false;

  /// Which hover is the current one, so that a probe answering after the
  /// pointer has left finds itself stale.
  int _hover = 0;

  @override
  void dispose() {
    _stop();
    super.dispose();
  }

  void _stop() {
    var controller = _controller;
    _controller = null;
    if (controller != null) {
      controller.removeListener(_update);
      controller.dispose();
    }
  }

  void _update() {
    if (mounted) {
      setState(() {});
    }
  }

  /// The pointer arrived: ask for the teaser and play it.
  Future<void> _enter() async {
    var probe = widget.probeTeaser;
    if (_givenUp || probe == null || _controller != null) {
      return;
    }
    var hover = ++_hover;
    _asked++;
    RenditionState state;
    try {
      state = await probe(widget.teaserUrl);
    } catch (_) {
      // A teaser is a nicety; a question that cannot be asked ends it.
      _givenUp = true;
      return;
    }
    if (!mounted || _hover != hover) {
      return;
    }
    if (!state.isReady) {
      // Pending is asked about once more on the next hover; anything else is
      // final. Either way nothing is shown.
      _givenUp = !state.isPending || _asked > 1;
      return;
    }
    VideoPlayerController controller;
    try {
      controller = widget.createController(
        Uri.parse(widget.teaserUrl),
        headers: widget.headers,
      );
    } catch (_) {
      _givenUp = true;
      return;
    }
    _controller = controller;
    controller.addListener(_update);
    try {
      await controller.initialize();
      if (!mounted || _hover != hover || _controller != controller) {
        _stop();
        return;
      }
      await controller.setVolume(0);
      await controller.setLooping(true);
      await controller.play();
    } catch (_) {
      // A teaser that will not start is a teaser that is not shown.
      if (_controller == controller) {
        _stop();
      }
      _givenUp = true;
      return;
    }
    _update();
  }

  /// The pointer left: the tile is a poster again.
  void _leave() {
    _hover++;
    if (_controller == null) {
      return;
    }
    _stop();
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.plays) {
      return widget.child;
    }
    var controller = _controller;
    var playing = controller != null && controller.value.isInitialized;
    return MouseRegion(
      onEnter: (_) => _enter(),
      onExit: (_) => _leave(),
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          widget.child,
          if (playing)
            Positioned.fill(
              child: FittedBox(
                fit: BoxFit.contain,
                clipBehavior: Clip.hardEdge,
                child: SizedBox.fromSize(
                  size: controller.value.size,
                  child: VideoPlayer(
                    controller,
                    key: const Key("video-teaser"),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
