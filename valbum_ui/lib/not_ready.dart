/// A picture the server has not made yet (issues #256, #258).
///
/// The server never makes a preview, a display rendition or a face crop
/// while a request waits: its background makes every one of them, and a
/// request for one that is not there yet is answered `202 Accepted` — which
/// also tells the background that somebody waits for exactly that file, so
/// that it makes it next. The app shows such a picture as the neutral
/// placeholder a tile shows while its bytes are on their way — no error icon,
/// no message, no log entry — and asks again by itself while something is
/// pending, see [NotReadyReAsk]: one re-ask for the whole page, never a timer
/// per tile, honouring the server's `Retry-After`, short right after an upload
/// of this app, and never more than [reAskAttempts] times without progress.
/// The user's reload asks again at once ([forgetNotReadyImages]).
///
/// Not a push from the server: a stream per client is a connection per
/// client, which proxies buffer and mobile networks drop, and which would
/// have to be authenticated on its own. Worth it once a second live feature
/// needs it; asking again only while something is pending is not.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';

import 'offline.dart';

/// What fetching a picture throws where the server answered `202 Accepted`:
/// it has not made the picture yet, see the library comment.
class ImageNotReady implements Exception {
  /// The address that was answered so.
  final String url;

  /// The server's `Retry-After`, `null` where it named none.
  final Duration? retryAfter;

  const ImageNotReady(this.url, {this.retryAfter});

  @override
  String toString() => "ImageNotReady($url)";
}

/// The HTTP status the server answers a picture with that it has not made
/// yet, see [ImageNotReady].
const int notReadyStatus = 202;

/// Whether [error], which an image stream reported, says that the server has
/// not made the picture yet: an [ImageNotReady] of a picture fetched through
/// the client, a [NetworkImageLoadException] of `202` of one the platform
/// fetched itself (the viewer's display rendition).
bool isImageNotReady(Object error) =>
    error is ImageNotReady ||
    (error is NetworkImageLoadException && error.statusCode == notReadyStatus);

/// The providers whose picture was answered "not made yet", see
/// [rememberNotReady].
final Set<ImageProvider> _notReady = <ImageProvider>{};

/// The providers the last [forgetNotReadyImages] threw away, see
/// [wasForgotten].
Set<ImageProvider> _forgotten = <ImageProvider>{};

/// The `Retry-After` the server answered last, see [notReadyRetryAfter].
Duration? _retryAfter;

/// Tells whatever draws a picture answered "not made yet" that the answer was
/// thrown away ([forgetNotReadyImages]): it builds its image afresh, which
/// asks the server again, see [wasForgotten].
final ValueNotifier<int> notReadyForgotten = ValueNotifier<int>(0);

/// Tells [NotReadyReAsk] that this app finished an upload: the new pictures
/// are made within a second or two, so it asks again soon, see [noteUpload].
final ValueNotifier<int> uploadsDone = ValueNotifier<int>(0);

/// Remembers that [provider]'s picture was answered "not made yet".
///
/// Its failed stream stays in Flutter's [ImageCache] on purpose: a tile that
/// is merely built again (a selection, a relayout) is answered from there and
/// asks the server nothing. A re-ask or a reload throws it away, see
/// [forgetNotReadyImages].
void rememberNotReady(ImageProvider provider, {Duration? retryAfter}) {
  _notReady.add(provider);
  if (retryAfter != null) {
    _retryAfter = retryAfter;
  }
}

/// Whether a picture was answered "not made yet" and is still remembered.
bool get hasNotReadyImages => _notReady.isNotEmpty;

/// The `Retry-After` the server answered a picture with last, `null` where it
/// named none.
Duration? get notReadyRetryAfter => _retryAfter;

/// Whether [provider]'s answer "not made yet" was thrown away by the last
/// [forgetNotReadyImages], so that its picture is to be asked for again.
bool wasForgotten(ImageProvider provider) => _forgotten.contains(provider);

/// Forgets every picture the server answered "not made yet", so that it is
/// asked for again: what the user's reload does. A picture that was answered
/// is not asked for again.
void forgetNotReadyImages() => _forget(Set<ImageProvider>.of(_notReady));

/// Forgets the answers "not made yet" of the given pictures, see
/// [forgetNotReadyImages]: what the re-ask of one page does.
void _forget(Set<ImageProvider> providers) {
  if (providers.isEmpty) {
    return;
  }
  for (var provider in providers) {
    provider.evict();
  }
  _notReady.removeAll(providers);
  _forgotten = providers;
  notReadyForgotten.value++;
}

/// Says that this app finished an upload, see [uploadsDone].
void noteUpload() => uploadsDone.value++;

/// Forgets everything remembered, so that one test never answers the next.
@visibleForTesting
void resetNotReady() {
  _notReady.clear();
  _forgotten = <ImageProvider>{};
  _retryAfter = null;
}

/// How often a page asks again without anything having come meanwhile; then
/// it leaves it to the user's reload.
const int reAskAttempts = 12;

/// How long a page waits before it asks again where the server named no
/// `Retry-After` (an album being prepared, a picture the platform fetched).
const Duration reAskDefault = Duration(seconds: 5);

/// How long a page waits first after an upload of this app; doubled with
/// every re-ask up to the server's `Retry-After`.
const Duration reAskAfterUpload = Duration(seconds: 1);

/// Starts the timer of a re-ask; a test may count or shorten the waits.
typedef ReAskTimer = Timer Function(Duration delay, void Function() callback);

/// The [ReAskTimer] of every [NotReadyReAsk], see there.
@visibleForTesting
ReAskTimer reAskTimer = Timer.new;

/// Asks again for what the server answered "not made yet" on the page it
/// wraps, by itself, while something is pending (issue #258).
///
/// Something is pending while a picture of the page is remembered as not made
/// yet ([NotReadyReAsk.remember]) or the page says so itself ([preparing]: an
/// album the server is still reading). Then one re-ask is scheduled for the
/// whole page, not one per tile: it forgets the page's answers "not made yet",
/// so that exactly those tiles ask again — a tile that shows its picture asks
/// nothing, keeps its place and does not flicker — and runs [onReAsk] (the
/// album being prepared is read again). A page covered by another one (the
/// album beneath the viewer) keeps its answers until it is shown again.
///
/// The wait honours the server's `Retry-After` ([notReadyRetryAfter], else
/// [reAskDefault]); right after an upload of this app ([uploadsDone]) it starts
/// at [reAskAfterUpload] and doubles up to it. Nothing is scheduled while
/// nothing is pending, and after [reAskAttempts] re-asks that brought
/// nothing, the user's reload is left to do it.
///
/// A re-ask that falls while it would get in the way waits for the next one:
/// while [paused] says so (the edit and the select mode, whose selection a
/// re-read album takes away; a "view as" preview), offline, and while a page
/// above — the viewer — covers this one.
class NotReadyReAsk extends StatefulWidget {
  /// Whether a re-ask would get in the way now, beyond what every page has.
  final bool Function() paused;

  /// Whether the page is pending by itself, beyond its pictures.
  final bool Function() preparing;

  /// What a re-ask does beyond asking for the pictures again.
  final VoidCallback? onReAsk;

  final Widget child;

  const NotReadyReAsk({
    super.key,
    required this.child,
    this.paused = _never,
    this.preparing = _never,
    this.onReAsk,
  });

  static bool _never() => false;

  /// Remembers that the picture of [provider], drawn at [context], was
  /// answered "not made yet" ([rememberNotReady]), as one the page around
  /// [context] asks again for.
  static void remember(
    BuildContext context,
    ImageProvider provider, {
    Duration? retryAfter,
  }) {
    rememberNotReady(provider, retryAfter: retryAfter);
    context.findAncestorStateOfType<NotReadyReAskState>()?._remember(provider);
  }

  @override
  State<NotReadyReAsk> createState() => NotReadyReAskState();
}

/// The state of a [NotReadyReAsk], public for the tests.
class NotReadyReAskState extends State<NotReadyReAsk> {
  Timer? _timer;

  /// The re-asks since something last came, see [reAskAttempts].
  int _attempts = 0;

  /// Whether the waits start short, after an upload of this app; until a
  /// re-ask finds nothing pending any more.
  bool _afterUpload = false;

  /// What was pending at the last re-ask, to tell progress.
  int _pendingBefore = 0;

  bool _preparingBefore = false;

  /// Whether the re-asks stopped at [reAskAttempts].
  bool _gaveUp = false;

  /// How many times this page asked again; for the tests.
  @visibleForTesting
  int reAsked = 0;

  /// The pictures of this page answered "not made yet".
  final Set<ImageProvider> _mine = <ImageProvider>{};

  bool get _pending => _mine.isNotEmpty || widget.preparing();

  @override
  void initState() {
    super.initState();
    notReadyForgotten.addListener(_forgottenElsewhere);
    uploadsDone.addListener(_uploaded);
    WidgetsBinding.instance.addPostFrameCallback((_) => _schedule());
  }

  void _remember(ImageProvider provider) {
    if (!_mine.add(provider)) {
      return;
    }
    // Learned in the middle of a frame, maybe: scheduled after it.
    scheduleMicrotask(_remembered);
  }

  /// A reload threw the answers away: they are no more pending here, and
  /// what the reload brings back pending is asked again as at first.
  void _forgottenElsewhere() {
    var before = _mine.length;
    _mine.removeWhere(wasForgotten);
    if (_mine.length < before) {
      _gaveUp = false;
      _attempts = 0;
    }
  }

  @override
  void didUpdateWidget(NotReadyReAsk oldWidget) {
    super.didUpdateWidget(oldWidget);
    // An album read again may be prepared now, or still not.
    _schedule();
  }

  @override
  void dispose() {
    notReadyForgotten.removeListener(_forgottenElsewhere);
    uploadsDone.removeListener(_uploaded);
    _timer?.cancel();
    super.dispose();
  }

  void _remembered() {
    if (_gaveUp && _mine.length > _pendingBefore) {
      // A picture not asked for before (a tile scrolled into view).
      _gaveUp = false;
      _attempts = 0;
    }
    _schedule();
  }

  void _uploaded() {
    _afterUpload = true;
    _gaveUp = false;
    _attempts = 0;
    _timer?.cancel();
    _timer = null;
    _schedule();
  }

  /// The wait before the next re-ask.
  Duration get _delay {
    var limit = notReadyRetryAfter ?? reAskDefault;
    if (!_afterUpload) {
      return limit;
    }
    var short = reAskAfterUpload * (1 << _attempts.clamp(0, 10));
    return short < limit ? short : limit;
  }

  void _schedule() {
    if (!mounted || _timer != null || _gaveUp || !_pending) {
      return;
    }
    _timer = reAskTimer(_delay, _fire);
  }

  /// Whether a re-ask would get in somebody's way now.
  bool get _inTheWay =>
      widget.paused() ||
      OfflineScope.isOffline(context) ||
      !(ModalRoute.of(context)?.isCurrent ?? true);

  void _fire() {
    _timer = null;
    if (!mounted) {
      return;
    }
    if (!_pending) {
      _afterUpload = false;
      return;
    }
    if (_inTheWay) {
      // Not now; nothing is counted.
      _timer = reAskTimer(_delay, _fire);
      return;
    }
    var pending = _mine.length;
    var preparing = widget.preparing();
    if (_attempts > 0 &&
        (pending < _pendingBefore || (_preparingBefore && !preparing))) {
      // Something came meanwhile: the count starts over.
      _attempts = 0;
    }
    if (_attempts >= reAskAttempts) {
      _gaveUp = true;
      _pendingBefore = pending;
      return;
    }
    _attempts++;
    reAsked++;
    _pendingBefore = pending;
    _preparingBefore = preparing;
    var forgotten = Set<ImageProvider>.of(_mine);
    _mine.clear();
    _forget(forgotten);
    widget.onReAsk?.call();
    _schedule();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// Builds [builder] afresh, which resolves its image again, each time a
/// re-ask or a reload threw away the answer "not made yet" of [provider], see
/// [notReadyForgotten]. A picture that was answered is never asked again.
class AskAgain extends StatefulWidget {
  /// The picture that may be answered "not made yet".
  final ImageProvider provider;

  final WidgetBuilder builder;

  const AskAgain({super.key, required this.provider, required this.builder});

  @override
  State<AskAgain> createState() => _AskAgainState();
}

class _AskAgainState extends State<AskAgain> {
  int _attempt = 0;

  @override
  void initState() {
    super.initState();
    notReadyForgotten.addListener(_forgotten);
  }

  @override
  void dispose() {
    notReadyForgotten.removeListener(_forgotten);
    super.dispose();
  }

  void _forgotten() {
    if (mounted && wasForgotten(widget.provider)) {
      setState(() => _attempt++);
    }
  }

  @override
  Widget build(BuildContext context) => KeyedSubtree(
      key: ValueKey<int>(_attempt), child: widget.builder(context));
}
