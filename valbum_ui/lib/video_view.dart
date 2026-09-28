/// Inline playback of the videos of an album.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:video_player/video_player.dart';

import 'client.dart';
import 'diagnostics.dart';
import 'l10n/app_localizations.dart';
import 'oriented_thumbnail.dart';
import 'platform.dart' show platformDescription;
import 'resource.dart';
import 'page_insets.dart';

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

/// How long a video may take to start before its silence is a failure
/// (issue #184).
///
/// Counted from the moment the player is opened, which is the tap that
/// opened the viewer. A player that neither starts nor fails leaves a poster
/// and nothing else, which is a video that "says nothing at all".
const Duration videoStartTimeout = Duration(seconds: 10);

/// The longest a video may take to start while bytes are still arriving,
/// see [videoStartTimeout].
///
/// A 4K original whose index sits at the end of the file costs two round
/// trips and a long read before the first frame; while the player reports
/// its buffer growing, it is given another [videoStartTimeout], up to this.
const Duration videoStartTimeoutMax = Duration(seconds: 60);

/// How often the view asks for a rendition the server is still making before
/// it stops asking and says so (issue #184).
///
/// With the server's `Retry-After` of ten seconds that is a minute of
/// waiting, which is what "try again in a minute" then says; the person can
/// ask again with one tap, or play the original at once.
const int renditionPendingAttempts = 6;

/// Which file the view plays, see [VideoView.renditionUrl].
enum VideoSource {
  /// The server's playable version (`?type=video`).
  rendition,

  /// The file as it was taken.
  original,
}

/// What kept a video from playing, in the terms the person is told it in,
/// see [VideoFailure] and issue #184.
enum VideoFailureKind {
  /// The server was still making the rendition after
  /// [renditionPendingAttempts] asks.
  pending,

  /// The address got no answer at all.
  unreachable,

  /// The server answered with an error status.
  refused,

  /// The server delivered the file and the platform's player refused it.
  format,

  /// The server delivers the file, and the player reported that it could not
  /// fetch it (a cleartext policy, a lost connection).
  notFetched,

  /// The player neither started nor failed within [videoStartTimeout].
  silent,

  /// Nothing is known beyond the player's own error: there was nobody to ask
  /// the server, see [VideoView.probeRendition].
  unknown,
}

/// Why a video did not play: what was tried, what the server answered, what
/// the platform said (issue #184).
///
/// One value both the message on the screen and the entry of the
/// [DiagnosticsLog] are made from, so the two cannot tell different stories.
@immutable
class VideoFailure {
  /// What the person is told, see [VideoFailureKind].
  final VideoFailureKind kind;

  /// Which file was played.
  final VideoSource source;

  /// The server's answer to the file that was played, `null` where nobody
  /// could ask.
  final SourceAnswer? answer;

  /// The server's refusal of the rendition, where the original was played
  /// because of it.
  final SourceAnswer? renditionRefusal;

  /// What the platform's player reported, `null` where it said nothing.
  final Object? problem;

  /// How long the view waited for a player that said nothing.
  final Duration? waited;

  /// How often the rendition was asked about, for [VideoFailureKind.pending].
  final int asked;

  const VideoFailure({
    required this.kind,
    required this.source,
    this.answer,
    this.renditionRefusal,
    this.problem,
    this.waited,
    this.asked = 0,
  });

  /// Decides the [kind] from the facts, see [VideoFailureKind].
  ///
  /// The server's answer comes first — a player that fails on a `403` has
  /// nothing to say about formats — and the player's own words only decide
  /// between "cannot play this format" and "could not fetch it" for a file
  /// the server does deliver.
  static VideoFailureKind classify({
    required SourceAnswer? answer,
    Object? problem,
    bool timedOut = false,
    bool gaveUp = false,
  }) {
    if (gaveUp) {
      return VideoFailureKind.pending;
    }
    if (answer == null) {
      return timedOut ? VideoFailureKind.silent : VideoFailureKind.unknown;
    }
    if (answer.transportError != null) {
      return VideoFailureKind.unreachable;
    }
    if (!answer.delivers) {
      return VideoFailureKind.refused;
    }
    if (timedOut) {
      return VideoFailureKind.silent;
    }
    return _isNetwork(problem)
        ? VideoFailureKind.notFetched
        : VideoFailureKind.format;
  }

  /// Whether the platform's words say the video never arrived, see
  /// [videoErrorHint].
  static bool _isNetwork(Object? problem) {
    if (problem == null) {
      return false;
    }
    var text = problem.toString().toLowerCase();
    if (_formatWords.any(text.contains)) {
      return false;
    }
    return _networkWords.any(text.contains);
  }

  /// The main sentence: what happened, in the person's words.
  String headline(AppLocalizations l10n) {
    var refusal = renditionRefusal;
    if (refusal != null && source == VideoSource.original) {
      return refusalSentence(l10n, refusal);
    }
    return sentence(l10n);
  }

  /// The second sentence, `null` where the headline says it all.
  String? detail(AppLocalizations l10n) {
    if (renditionRefusal != null && source == VideoSource.original) {
      return sentence(l10n);
    }
    return switch (kind) {
      VideoFailureKind.unknown =>
        problem == null ? null : videoErrorHint(l10n, problem!),
      VideoFailureKind.silent when answer?.delivers ?? false =>
        l10n.videoSilentDelivers(typeOf(l10n, answer!)),
      _ => null,
    };
  }

  /// What [kind] says of the file that was played.
  String sentence(AppLocalizations l10n) {
    var answer = this.answer;
    return switch (kind) {
      VideoFailureKind.pending => l10n.videoPendingGaveUp,
      VideoFailureKind.unreachable =>
        l10n.videoServerUnreachable(answer?.transportError ?? ""),
      VideoFailureKind.refused => refusalSentence(l10n, answer!),
      VideoFailureKind.format => l10n.videoFormatRefused(typeOf(l10n, answer!)),
      VideoFailureKind.notFetched =>
        l10n.videoNotFetched(typeOf(l10n, answer!)),
      VideoFailureKind.silent =>
        l10n.videoDidNotStart((waited ?? videoStartTimeout).inSeconds),
      VideoFailureKind.unknown => l10n.videoCannotPlay,
    };
  }

  /// What a refusal of the server says: the conversion that failed with its
  /// reason for a `500` of the rendition, the status and the server's own
  /// sentence otherwise.
  static String refusalSentence(AppLocalizations l10n, SourceAnswer answer) {
    var transport = answer.transportError;
    if (transport != null) {
      return l10n.videoServerUnreachable(transport);
    }
    var status = answer.status ?? 0;
    var message = answer.message;
    if (status == 500 && message != null && message.isNotEmpty) {
      return l10n.videoConversionFailed(message);
    }
    if (message == null || message.isEmpty) {
      return l10n.videoServerRefusedBare(status);
    }
    return l10n.videoServerRefused(message, status);
  }

  /// The `Content-Type` as the sentences name it.
  static String typeOf(AppLocalizations l10n, SourceAnswer answer) {
    var type = answer.contentType;
    return type == null || type.isEmpty ? l10n.videoUnknownType : type;
  }

  /// What the entry of the [DiagnosticsLog] calls [kind].
  String get diagnosis => switch (kind) {
        VideoFailureKind.pending =>
          "the server was still making the playable version after $asked asks",
        VideoFailureKind.unreachable => "the server could not be reached",
        VideoFailureKind.refused => "the server refused the file",
        VideoFailureKind.format =>
          "the server delivered the file and the platform refused it",
        VideoFailureKind.notFetched =>
          "the server delivers the file and the player could not fetch it",
        VideoFailureKind.silent =>
          "the player neither started nor failed within "
              "${(waited ?? videoStartTimeout).inSeconds} s",
        VideoFailureKind.unknown =>
          "the player failed and the server could not be asked",
      };
}

/// Plays a single video, showing its poster image until playback can start.
///
/// This is the video counterpart of the `Image.network` the image viewer shows
/// for a still image: it fills the slot it is given, starts playing as soon
/// as the controller is initialised (as the retired GWT UI did with
/// `<video controls autoplay>`) and offers a play/pause button and a progress
/// bar. While the video is not (yet) playable, the poster - the server's video
/// thumbnail - is shown, with a spinner that never runs longer than
/// [videoStartTimeout] (or [videoStartTimeoutMax] while bytes still arrive).
///
/// If the video cannot be played, the message **says the main thing itself**
/// (issue #184): the playable version is still being made, the server could
/// not convert the video and why, this browser or device cannot play the
/// format the server delivered (naming it), or the status and the server's
/// own sentence — never the raw `PlatformException` of the platform player,
/// which said nothing to the person who reported issue #73. The facts go into
/// the [VideoView.log] as one entry, see [VideoFailure]; the pointer to it is
/// one extra line. Because the platform fetches the URL itself and tells
/// nobody what the server answered, the view asks with one request of its
/// own, once per failure, see [VAlbumClient.probeSource].
///
/// What is played is the *rendition* where there is one (issues #74/#75): a
/// faststart 720p file the server transcodes beside the original, because a
/// 4K original costs two round trips for its index before the first frame and
/// then saturates the home network. [VideoView.renditionUrl] is probed before
/// anything is opened; while the server is still making it, the poster stays
/// up with [AppLocalizations.videoPreparing] and the view asks again at the
/// server's `Retry-After`, at most [renditionPendingAttempts] times and saying
/// when; where there will be none, the original is played instead, and the
/// server's refusal stands over it as a notice. [VideoView.videoUrl] is
/// therefore always the original — the fallback, and what
/// [AppLocalizations.videoPlayOriginal] opens.
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

  /// Asks the server about a URL, see [RenditionProbe]: whether the rendition
  /// can be played, and — after a failure — what the server answers for the
  /// file that failed (issue #184).
  final RenditionProbe? probeRendition;

  /// How the view waits out a `Retry-After`, see [Wait].
  final Wait wait;

  /// Where a failure is written down (issues #73, #184).
  ///
  /// The log of the client the view was built from, see `image_view.dart`;
  /// `null` in a view pumped on its own, which then simply logs nothing.
  final DiagnosticsLog? log;

  /// The video as the album describes it, for the log entry: its name, kind
  /// and dimensions. `null` in a view pumped on its own; the name is then
  /// read off [videoUrl].
  final ImagePart? part;

  /// The album the video lies in, as the log entry names it, see
  /// `albumLabel`.
  final String? album;

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
    this.part,
    this.album,
  });

  @override
  State<VideoView> createState() => VideoViewState();
}

class VideoViewState extends State<VideoView> {
  VideoPlayerController? _controller;

  /// Why the video is not playing, `null` while nothing went wrong.
  VideoFailure? _failure;

  /// Whether a failure is being looked into — the server being asked what it
  /// answers, see [_fail] — so the spinner stays up a moment longer.
  bool _diagnosing = false;

  /// Whether the server is still making the rendition, see
  /// [AppLocalizations.videoPreparing].
  bool _preparing = false;

  /// How often the rendition was asked about in this attempt.
  int _asked = 0;

  /// How long the view waits before it asks again.
  Duration _retryIn = renditionRetryDefault;

  /// Which file is played, and why that one — the facts of the log entry.
  VideoSource _source = VideoSource.original;
  String _why = "";

  /// The server's last answer about the rendition.
  SourceAnswer? _renditionAnswer;

  /// The refusal that made the view play the original, see
  /// [VideoFailure.renditionRefusal].
  SourceAnswer? _renditionRefusal;

  /// Whether the notice that the original is played instead is closed.
  bool _noticeDismissed = false;

  /// The controller whose failure is being or was reported, so that a
  /// player reporting the same failure twice (its listener and its
  /// `initialize`) is written down once.
  VideoPlayerController? _failedController;

  /// The timer of [videoStartTimeout], see [_watch].
  Timer? _watchdog;

  /// How long the current player has been waited for.
  Duration _waited = Duration.zero;

  /// How far the current player's buffer reached at the last look.
  Duration _buffered = Duration.zero;

  /// Which attempt is the current one.
  ///
  /// Every way of starting over — a new video, "Play the original", "Try
  /// again" — makes this a new number, so that a retry loop that is still
  /// waiting out its `Retry-After` finds itself stale when it wakes up and
  /// stops.
  int _attempt = 0;

  /// Whether the controller has reported a playable video.
  bool get isPlayable => _controller?.value.isInitialized ?? false;

  /// Why the video is not playing, `null` while nothing went wrong.
  VideoFailure? get failure => _failure;

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
      _failure = null;
      _diagnosing = false;
      _preparing = false;
      _resolve();
    }
  }

  /// Decides what to play and plays it, see [VideoView.renditionUrl].
  ///
  /// The rendition where the server has it, the original where it says there
  /// will be none, and the poster with [AppLocalizations.videoPreparing] for
  /// as long as it is being made — asking again at the interval the server
  /// itself named, never faster, see [VAlbumClient.renditionState], and at
  /// most [renditionPendingAttempts] times.
  Future<void> _resolve() async {
    var rendition = widget.renditionUrl;
    var probe = widget.probeRendition;
    var attempt = ++_attempt;
    _asked = 0;
    _renditionAnswer = null;
    _renditionRefusal = null;
    _noticeDismissed = false;
    if (rendition == null || probe == null) {
      _open(
        widget.videoUrl,
        VideoSource.original,
        "no playable version is asked for here",
      );
      return;
    }
    while (mounted && _attempt == attempt) {
      RenditionState state;
      _asked++;
      try {
        state = await probe(rendition);
      } catch (problem) {
        // The probe is a courtesy; a question that cannot be asked is not a
        // reason to refuse the video, it is a reason to play the original.
        state = RenditionState(
          RenditionStatus.unavailable,
          answer: SourceAnswer(transportError: "$problem"),
        );
      }
      if (!mounted || _attempt != attempt) {
        return;
      }
      var answer = state.answer ?? _answerOf(state);
      _renditionAnswer = answer;
      if (state.isReady) {
        if (_preparing) {
          setState(() => _preparing = false);
        }
        _open(
          rendition,
          VideoSource.rendition,
          "the server has a playable version ready",
        );
        return;
      }
      if (!state.isPending) {
        // There will be no rendition — a failed transcode, or a caller who may
        // not have it. The original is what is left; the refusal is said over
        // it, and written down once it is known how the original fared.
        _renditionRefusal = answer;
        _fallBackToOriginal(
          attempt,
          "the playable version was refused (see below)",
        );
        return;
      }
      if (_asked >= renditionPendingAttempts) {
        // Asked often enough: say so, and let the person decide.
        _source = VideoSource.rendition;
        _why = "the server makes a playable version of this video";
        if (_preparing) {
          setState(() => _preparing = false);
        }
        _fail(gaveUp: true);
        return;
      }
      setState(() {
        _preparing = true;
        _retryIn = state.retryAfter;
      });
      await widget.wait(state.retryAfter);
    }
  }

  /// The answer a [RenditionState] stands for where it carries none — a probe
  /// that is not the client's, see [VideoView.probeRendition].
  static SourceAnswer _answerOf(RenditionState state) => switch (state.status) {
        RenditionStatus.ready => const SourceAnswer(status: 200),
        RenditionStatus.pending =>
          SourceAnswer(status: 202, message: state.message),
        RenditionStatus.unavailable =>
          SourceAnswer(status: 500, message: state.message),
      };

  /// Plays the original instead of the rendition, ending attempt [attempt].
  void _fallBackToOriginal(int attempt, String why) {
    if (!mounted || _attempt != attempt) {
      return;
    }
    if (_preparing) {
      setState(() => _preparing = false);
    }
    _open(widget.videoUrl, VideoSource.original, why);
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
      _diagnosing = false;
      _failure = null;
      _renditionRefusal = null;
    });
    _open(
      widget.videoUrl,
      VideoSource.original,
      "the person chose to play the original",
    );
  }

  /// Starts over: asks the server again (issue #184).
  ///
  /// Offered where the view stopped asking for a rendition the server was
  /// still making, see [renditionPendingAttempts].
  void tryAgain() {
    _close();
    setState(() {
      _preparing = false;
      _diagnosing = false;
      _failure = null;
    });
    _resolve();
  }

  @override
  void dispose() {
    _close();
    super.dispose();
  }

  void _close() {
    _watchdog?.cancel();
    _watchdog = null;
    var controller = _controller;
    _controller = null;
    if (controller != null) {
      controller.removeListener(_update);
      controller.dispose();
    }
  }

  /// Creates the controller for [url] and starts playing, or records the
  /// failure. [source] and [why] say which file this is and why this one,
  /// for the entry of a failure.
  Future<void> _open(String url, VideoSource source, String why) async {
    _source = source;
    _why = why;
    VideoPlayerController controller;
    try {
      controller = widget.createController(
        Uri.parse(url),
        headers: widget.headers,
      );
    } catch (problem) {
      _fail(problem: problem);
      return;
    }
    _controller = controller;
    controller.addListener(_update);
    _watch(controller);
    if (mounted) {
      // The spinner, see [build].
      setState(() {});
    }

    try {
      await controller.initialize();
      if (!mounted || _controller != controller) {
        return;
      }
      _watchdog?.cancel();
      await controller.setLooping(false);
      if (widget.autoPlay) {
        await controller.play();
      }
    } catch (problem) {
      if (_controller == controller) {
        _fail(problem: problem, controller: controller);
      }
      return;
    }
    _playedInstead();
    _update();
  }

  /// Writes down that the rendition was refused and the original plays
  /// instead: the server failed, even if the person got to see the video.
  void _playedInstead() {
    var refusal = _renditionRefusal;
    if (refusal == null || _source != VideoSource.original) {
      return;
    }
    widget.log?.add(
      "Video: the playable version was refused, the original plays instead: "
      "$_name in $_albumLabel",
      [
        "Tried first: the playable version (?type=video), because the "
            "server makes one for every video",
        if (widget.renditionUrl != null)
          "URL: ${maskUrl(widget.renditionUrl!)}",
        ...refusal.facts,
        "Then: the original, which plays",
        _originalFact(null),
        "Platform: ${platformDescription()}",
      ],
      failureCause(
        attempt: "video fallback",
        answer: _answerCause(refusal),
      ),
      _name,
    );
  }

  /// Starts the [videoStartTimeout] of [controller] (issue #184).
  void _watch(VideoPlayerController controller) {
    _watchdog?.cancel();
    _waited = Duration.zero;
    _buffered = Duration.zero;
    _watchdog = Timer(videoStartTimeout, () => _check(controller));
  }

  /// Looks whether [controller] started, and fails it where it did not and
  /// nothing is arriving any more.
  void _check(VideoPlayerController controller) {
    if (_controller != controller || controller.value.isInitialized) {
      return;
    }
    _waited += videoStartTimeout;
    var value = controller.value;
    var reached =
        value.buffered.isEmpty ? Duration.zero : value.buffered.last.end;
    var arriving = reached > _buffered;
    _buffered = reached;
    if (arriving && _waited < videoStartTimeoutMax) {
      _watchdog = Timer(videoStartTimeout, () => _check(controller));
      return;
    }
    _fail(timedOut: true, controller: controller);
  }

  /// The name of the video, as the album or else its URL says it.
  String get _name {
    var part = widget.part;
    if (part != null && part.name.isNotEmpty) {
      return part.name;
    }
    var segments = Uri.tryParse(widget.videoUrl)?.pathSegments ?? const [];
    return segments.isEmpty ? widget.videoUrl : segments.last;
  }

  /// The album as the entry names it.
  String get _albumLabel => widget.album ?? "an album";

  /// The line describing the original, with its size where [answer] says it.
  String _originalFact(SourceAnswer? answer) {
    var part = widget.part;
    return originalFact(
      _name,
      kind: part?.kind.name,
      width: part?.width ?? 0,
      height: part?.height ?? 0,
      size: answer?.totalLength,
    );
  }

  /// What of a server's answer makes two failures the same, see
  /// [failureCause]: status, type and sentence — never the length, which
  /// differs from file to file.
  static String _answerCause(SourceAnswer answer) =>
      answer.transportError ??
      "${answer.status} ${answer.contentType ?? ""} ${answer.message ?? ""}";

  /// What the player of [controller] said about itself when it failed.
  static String _playerFact(VideoPlayerController controller) {
    var value = controller.value;
    var buffered =
        value.buffered.isEmpty ? Duration.zero : value.buffered.last.end;
    return "Player: ${value.isInitialized ? "initialized" : "not initialized"}"
        ", ${value.isBuffering ? "buffering" : "not buffering"}"
        ", duration ${value.duration == Duration.zero ? "unknown" : value.duration}"
        ", buffered up to $buffered"
        "${value.errorDescription == null ? "" : ", error ${value.errorDescription}"}";
  }

  /// Records that the video did not play and says why (issue #184).
  ///
  /// Asks the server once what it answers for the original — the size of the
  /// file, and for a video played from the original the very answer the
  /// player got — then writes one entry into the log and shows the message.
  /// The rendition's answer is known already, from the probe that chose it.
  Future<void> _fail({
    Object? problem,
    bool timedOut = false,
    bool gaveUp = false,
    VideoPlayerController? controller,
  }) async {
    if (controller != null) {
      if (identical(_failedController, controller)) {
        return;
      }
      _failedController = controller;
    }
    _watchdog?.cancel();
    var attempt = _attempt;
    var source = _source;
    var why = _why;
    var player = controller == null ? null : _playerFact(controller);
    if (timedOut) {
      // The player that said nothing is let go: nothing is left spinning.
      _close();
    }
    if (mounted) {
      setState(() => _diagnosing = true);
    }
    var probe = widget.probeRendition;
    SourceAnswer? original;
    if (probe != null && !gaveUp) {
      try {
        var state = await probe(widget.videoUrl);
        original = state.answer;
      } catch (_) {
        // Nothing more to learn; the entry says what is known.
      }
    }
    if (_attempt != attempt) {
      // Started over meanwhile; the new attempt speaks for itself.
      return;
    }
    var answer = source == VideoSource.rendition ? _renditionAnswer : original;
    var failure = VideoFailure(
      kind: VideoFailure.classify(
        answer: answer,
        problem: problem,
        timedOut: timedOut,
        gaveUp: gaveUp,
      ),
      source: source,
      answer: answer,
      renditionRefusal: _renditionRefusal,
      problem: problem,
      waited: timedOut ? _waited : null,
      asked: _asked,
    );
    var url =
        source == VideoSource.rendition ? widget.renditionUrl! : widget.videoUrl;
    var refusal = _renditionRefusal;
    widget.log?.add(
      "Video could not be played: $_name in $_albumLabel",
      [
        source == VideoSource.rendition
            ? "Tried: the playable version (?type=video), because $why"
            : "Tried: the original, because $why",
        "URL: ${maskUrl(url)}",
        if (answer != null) ...answer.facts,
        if (answer == null) "Server answered: not asked (no probe)",
        if (refusal != null && source == VideoSource.original) ...[
          "Playable version before: "
              "${refusal.facts.join("; ")}",
        ],
        if (gaveUp)
          "Asked: $_asked times, waiting the server's Retry-After "
              "(${_retryIn.inSeconds} s) in between",
        _originalFact(original),
        problem == null
            ? "Platform error: none reported"
            : platformErrorFact(problem),
        if (player != null) player,
        if (timedOut) "Waited: ${_waited.inSeconds} s for the player to start",
        "Platform: ${platformDescription()}",
        "Diagnosis: ${failure.diagnosis}",
      ],
      failureCause(
        attempt: "video ${source.name} ${failure.kind.name}",
        answer: answer == null ? null : _answerCause(answer),
        platformError: problem == null ? null : errorCause(problem, url),
      ),
      _name,
    );
    if (!mounted) {
      _failure = failure;
      return;
    }
    setState(() {
      _diagnosing = false;
      _failure = failure;
    });
  }

  /// Rebuilds for the current controller state (playing, position, ...).
  ///
  /// A player that reports an error of its own after it started — a stream
  /// that breaks off — fails like one that never started.
  void _update() {
    var controller = _controller;
    if (controller != null &&
        controller.value.hasError &&
        controller.value.isInitialized &&
        _failure == null) {
      _fail(
        problem: controller.value.errorDescription,
        controller: controller,
      );
    }
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
    var failure = _failure;
    var loading = failure == null &&
        !_preparing &&
        (_diagnosing || (controller != null && !isPlayable));
    var notice = failure == null &&
        !_noticeDismissed &&
        _renditionRefusal != null &&
        _source == VideoSource.original &&
        isPlayable;
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
        // Bounded: a player that says nothing fails after
        // [videoStartTimeout], see [_check].
        if (loading)
          const Center(
            child: SizedBox(
              key: Key("video-loading"),
              width: 32,
              height: 32,
              child: CircularProgressIndicator(
                strokeWidth: 3,
                color: Colors.white70,
              ),
            ),
          ),
        if (notice)
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            child: buildNotice(),
          ),
        // The error box gets the whole slot, loosely: [Center] passes the
        // tight constraints the expanded [Stack] gave it on as a maximum, so
        // the box knows how much room there is and can scroll inside it
        // instead of overflowing it, see [buildError].
        if (failure != null)
          Center(
            child: buildError(failure),
          ),
        // While the server is making the rendition: the poster, one line, and
        // the way past the wait, see [buildPreparing] and issue #75.
        if (failure == null && _preparing && !isPlayable)
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
        // The bar runs to the edge of the screen and its buttons stay clear
        // of a system bar swiped in over the immersive viewer (issues #60,
        // #171). It stands at the foot, so the status bar at the top is none
        // of its business.
        padding: pagePadding(
          context,
          const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        ).copyWith(top: 4),
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

  /// The notice over an original played because the server refused the
  /// rendition (issue #184): the server failed, and saying so is what makes
  /// a slow start or a format the rendition would have fixed explicable.
  Widget buildNotice() {
    var l10n = AppLocalizations.of(context)!;
    var refusal = _renditionRefusal!;
    return Container(
      key: const Key("video-fallback-notice"),
      color: Colors.black87,
      padding: const EdgeInsets.fromLTRB(12, 4, 4, 4),
      child: Row(
        children: [
          Expanded(
            child: Text(
              "${VideoFailure.refusalSentence(l10n, refusal)} "
              "${l10n.videoPlayingOriginal}",
              style: const TextStyle(color: Colors.white),
            ),
          ),
          IconButton(
            key: const Key("video-fallback-dismiss"),
            icon: const Icon(Icons.close),
            color: Colors.white70,
            tooltip: l10n.videoNoticeDismiss,
            onPressed: () => setState(() => _noticeDismissed = true),
          ),
        ],
      ),
    );
  }

  /// What is shown while the server is still making the rendition
  /// (issue #75).
  ///
  /// The poster stays behind it, the line says what is happening and when the
  /// view asks again (issue #184), and the button plays the original for
  /// whoever does not want to wait. It scrolls inside its slot for the same
  /// reason the error box does.
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
            const SizedBox(height: 4),
            Text(
              l10n.videoPreparingRetry(
                _retryIn.inSeconds,
                _asked + 1,
                renditionPendingAttempts,
              ),
              key: const Key("video-preparing-retry"),
              style: const TextStyle(color: Colors.white70),
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

  /// The message shown when the video cannot be played (issues #73, #184).
  ///
  /// The main sentence says what happened — see [VideoFailure.headline] —
  /// a second one where there is more to say, and one line pointing at the
  /// diagnostics log for the details. Never the raw `PlatformException`:
  /// that is the thing the author of issue #73 could make nothing of, and it
  /// is in the log instead. A rendition the server is still making offers
  /// to ask again and to play the original.
  ///
  /// The box fits whatever slot the view has: a video tile in landscape is a
  /// few dozen pixels tall, and three sentences do not fit it. It therefore
  /// *scrolls* inside the slot rather than being cut off — the panel
  /// shrink-wraps its content while it fits and stops growing at the slot's
  /// edge, see the [Center] in [build] that hands it the slot loosely.
  /// Nothing here is ever truncated: a refusal that cannot be read to its end
  /// is the bug of issue #73 all over again.
  Widget buildError(VideoFailure failure) {
    var l10n = AppLocalizations.of(context)!;
    var detail = failure.detail(l10n);
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
              failure.headline(l10n),
              key: const Key("video-error-headline"),
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            if (detail != null) ...[
              const SizedBox(height: 8),
              Text(
                detail,
                key: const Key("video-error-hint"),
                style: const TextStyle(color: Colors.white70),
                textAlign: TextAlign.center,
              ),
            ],
            if (failure.kind == VideoFailureKind.pending) ...[
              const SizedBox(height: 8),
              Wrap(
                alignment: WrapAlignment.center,
                spacing: 8,
                children: [
                  TextButton(
                    key: const Key("video-try-again"),
                    onPressed: tryAgain,
                    child: Text(
                      l10n.videoTryAgain,
                      style: const TextStyle(color: Colors.white),
                    ),
                  ),
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
            ],
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
