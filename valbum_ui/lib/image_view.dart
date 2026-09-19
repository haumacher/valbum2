/// The single image viewer: zoom, pan, swipe and keyboard navigation.
library;

import 'dart:async';

import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, kIsWeb, TargetPlatform;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'album_layout.dart' show ToImage;
import 'album_view.dart' show TextInputDialog;
import 'attribution.dart';
import 'client.dart';
import 'image_transform.dart';
import 'move_view.dart';
import 'offline.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart';
import 'thumbnails.dart';
import 'video_view.dart';

/// What a caller who may not change this album is told (issue #80).
///
/// Refusals speak: a long press that does nothing at all would look like a
/// broken gesture. English, like the rest of this view.
const String notEditableMessage = "You may not edit this album.";

/// The heading of the description dialog, as the tile editor of the album
/// spells it.
///
/// Deliberately the album's own wording and not this view's English: it is the
/// same dialog editing the same field of the same album, and one thing must
/// not have two names depending on where it was opened from.
const String descriptionDialogTitle = "Bildeigenschaften";

/// The label of the field, likewise the album's, see [descriptionDialogTitle].
const String descriptionDialogLabel = "Kommentar";

/// The velocity (in pixels per second) a drag must reach to count as a swipe.
const double _swipeVelocity = 400;

/// How far a finger must move before the drag axis is decided (issue #61).
const double _axisThreshold = 8;

/// The part of the page width a slow drag must cross to navigate (issue #61).
const double _swipeDistanceFraction = 1 / 3;

/// The part of the page width a drag against the album's end may cover.
///
/// At the first (last) image there is nothing to page to, so the drag is only
/// answered by a rubber band that approaches this much and never passes it.
const double _rubberBandFraction = 1 / 5;

/// How long a drag that did not navigate takes to slide back (issue #61).
const Duration _snapBackDuration = Duration(milliseconds: 150);

/// What is said when a picture could not be delivered at all (issue #95).
///
/// A *right* the caller does not hold is never mentioned here: what they may
/// not do is not offered, so the viewer never asks for it and the server never
/// refuses it. This is the other case — a server error, a broken connection —
/// and that one speaks, once, the way every other failure of this app speaks.
const String pictureFailedMessage = "This picture could not be loaded.";

/// The picture the viewer shows for the image at [imageUrl] (issue #95).
///
/// With `download` that is the original, as it always was. Without it the
/// server would refuse the original (`AuthService.DOWNLOAD_REFUSED`), so the
/// viewer asks for the **preview rendition** instead — `?type=tn`, which the
/// `view` right allows and which the album tiles are drawn from anyway. It is
/// the largest picture such a caller can have, so it is shown fitted to the
/// page and never resized on the way in.
///
/// One helper for one question, because the display and the prefetch of
/// issue #101 must produce the *same* [ImageProvider]: an [ImageCache] key
/// that differs by a hair turns a prefetched neighbour into a second download.
ImageProvider viewerPicture(
  VAlbumClient client,
  String imageUrl, {
  required bool mayDownload,
}) {
  if (mayDownload) {
    // The original is not cached (an album of originals would fill the
    // device), but it must still identify itself: a server started with
    // `--auth all` refuses an anonymous image.
    return NetworkImage(client.originalUrl(imageUrl),
        headers: client.authHeaders);
  }
  return ThumbnailImage(client, imageUrl);
}

/// The axis a drag of the fitted image was locked to, see issue #61.
///
/// A drag of the fitted image follows the finger along one axis only: the
/// picture must not drift diagonally away from a horizontal paging swipe.
/// The axis is decided from the first few pixels of the movement; a zoomed
/// image and a pinch are [free] and pan as they always did.
enum _DragAxis { undecided, horizontal, vertical, free }

/// How many image viewers are on screen, see [_enterImmersive].
int _immersiveViewers = 0;

/// Whether this platform has system bars the viewer should hide (issue #60).
///
/// Only Android and iOS have them; on the web and on the desktop the calls
/// are not available (and would be meaningless), so they are skipped.
bool get _hasSystemBars =>
    !kIsWeb &&
    (defaultTargetPlatform == TargetPlatform.android ||
        defaultTargetPlatform == TargetPlatform.iOS);

/// Hides the system bars while an image viewer is on screen (issue #60).
///
/// The navigation bar overlaps the picture and, in landscape, sits exactly
/// where the viewer's chevrons are, so the viewer runs immersive. Stepping
/// from image to image creates the next viewer before the previous one is
/// disposed; the counter keeps the bars from flickering in between, and the
/// restore is deferred by a microtask so that the order of the two does not
/// matter.
void _enterImmersive() {
  if (_immersiveViewers++ == 0) {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }
}

/// Gives the system bars back when the last viewer left, see [_enterImmersive].
void _leaveImmersive() {
  if (_immersiveViewers > 0) {
    _immersiveViewers--;
  }
  scheduleMicrotask(() {
    if (_immersiveViewers == 0) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
  });
}

/// Displays a single [AbstractImage] full-screen.
///
/// The image is fitted into the viewport honouring its
/// [ImagePart.orientation]; the mouse wheel zooms around the cursor, dragging
/// pans, a click toggles between the fitted view and a pixel-by-pixel display
/// of the clicked spot (see [ImageTransform]). The chevrons, the arrow keys and
/// swipe gestures navigate to the previous and next image, honouring the album's
/// rating filter (see [nextVisible]).
class ImageView extends StatefulWidget {
  /// The transport to the album server (for building the image URLs).
  final VAlbumClient client;

  /// The URL of the album the image belongs to.
  final String baseUrl;

  /// The image to display, a group is displayed by its representative.
  final AbstractImage image;

  /// Displays another image of the same album.
  final void Function(AbstractImage image) onShowImage;

  /// Opens the "alternatives" view of the group the image belongs to.
  ///
  /// The "down" chevron and the `ArrowDown` key are only offered if this is
  /// given and the image is part of an [ImageGroup].
  final void Function(ImageGroup group)? onShowGroup;

  /// Leaves the viewer, back to the album (or to the alternatives view).
  final VoidCallback? onUp;

  /// The minimum rating an image must have to be shown.
  ///
  /// Defaults to the [AlbumInfo.minRating] of the album owning the image.
  final int? minRating;

  /// Further controls floating over the image at the top right, such as the
  /// [GroupDetailView]'s choice of the group's representative.
  final List<Widget> actions;

  /// The path of the album the image lives in, `null` where the view does not
  /// know it (see issue #53).
  ///
  /// A take-back is a move posted to the album's own folder, so without the
  /// path there is nothing to post to and the button is not offered. The
  /// alternatives view of a group is such a place on purpose: a group moves as
  /// a whole, named by its representative, and the member shown there is not
  /// what the server would move — the take-back of a grouped photo belongs to
  /// the viewer of the album, which shows exactly that representative.
  final List<String>? albumPath;

  /// Called after a photo was taken back, so the view it left can be fetched
  /// again; the viewer falls back to [onUp] where this is not given.
  final VoidCallback? onTakenBack;

  /// The path of the album whose sidecar a description is written to
  /// (issue #80), `null` where the view does not know it.
  ///
  /// Not [albumPath], although it names the same album: that one says where a
  /// *take-back* may be posted, and the alternatives view of a group has none
  /// on purpose — a group moves as a whole. Writing a description is a
  /// different thing, and a group member's description is written to the same
  /// album as any other.
  final List<String>? editPath;

  /// Whether the album this image belongs to is being edited (issue #80).
  ///
  /// Two plain values rather than the album's `AlbumEditSession` itself: that
  /// class lives in `app.dart`, which builds this view, and the view needs no
  /// more of it than this. Inside the edit mode a description edited here goes
  /// into the editing buffer like a tile edit — [onEdited] marks the album
  /// dirty and the album view saves it with everything else, so that nothing
  /// is written twice and the album's discard still covers it. Outside it, the
  /// change is written at once, see [editDescription].
  final bool editing;

  /// Called when a description was changed into the album's editing buffer.
  final VoidCallback? onEdited;

  const ImageView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.image,
    required this.onShowImage,
    this.onShowGroup,
    this.onUp,
    this.minRating,
    this.actions = const [],
    this.albumPath,
    this.onTakenBack,
    this.editPath,
    this.editing = false,
    this.onEdited,
  });

  @override
  State<ImageView> createState() => ImageViewState();
}

class ImageViewState extends State<ImageView>
    with SingleTickerProviderStateMixin {
  ImageTransform? _transform;

  /// The state the transform had when the current gesture started.
  double _gestureScale = 1;
  double _gestureTx = 0;
  double _gestureTy = 0;
  Offset _gestureFocus = Offset.zero;

  /// The axis the running drag of the fitted image is locked to (issue #61).
  _DragAxis _axis = _DragAxis.free;

  /// How far the finger moved along the locked axis, before the rubber band.
  double _dragDx = 0;

  /// Whether more than one finger took part in the running gesture.
  bool _multiTouch = false;

  /// Slides a drag that did not navigate back to the fitted position.
  late final AnimationController _snapBack = AnimationController(
    vsync: this,
    duration: _snapBackDuration,
  )..addListener(_onSnapBack);

  /// The transform the running snap-back animates, `null` while none runs.
  ImageTransform? _snapping;

  /// The translation the snap-back starts from.
  double _snapFrom = 0;

  /// Whether this viewer hid the system bars, see [_enterImmersive].
  bool _immersive = false;

  @override
  void initState() {
    super.initState();
    _immersive = _hasSystemBars;
    if (_immersive) {
      _enterImmersive();
    }
  }

  @override
  void dispose() {
    _snapBack.dispose();
    if (_immersive) {
      _leaveImmersive();
    }
    super.dispose();
  }

  /// The image displayed, the representative of a group.
  ImagePart get part => ToImage.toImage(widget.image);

  /// Whether the displayed part is a video and not a still image.
  bool get isVideo => part.kind != ImageKind.image;

  /// The URL of the displayed part on the server, without any `type` parameter.
  String get dataUrl => "${widget.baseUrl}/${part.name}";

  /// The rating filter of the album the image belongs to.
  int get minRating => widget.minRating ?? widget.image.owner?.minRating ?? 0;

  /// The previous image passing the rating filter, `null` at the album start.
  AbstractImage? get previous => previousVisible(widget.image, minRating);

  /// The next image passing the rating filter, `null` at the album end.
  AbstractImage? get next => nextVisible(widget.image, minRating);

  /// The album the displayed image belongs to, `null` where the transient
  /// owner link was never built.
  ///
  /// The whole album, because that is what is written back: the sidecar is one
  /// document, and a changed description is saved the way every other album
  /// edit is saved, see [editDescription] and [VAlbumClient.saveAlbum].
  AlbumInfo? get album => widget.image.owner;

  /// What the caller may do with that album, as the server answered it with
  /// the album itself (issue #49).
  Rights get rights => Rights.of(album);

  /// Whether a description may be edited here at all.
  ///
  /// The `edit` right, an album to write into, and never inside a share link:
  /// a link is not an account, see issue #51.
  bool get mayEditDescription =>
      album != null &&
      rights.mayEdit &&
      ShareSession.of(context) == null &&
      (widget.editPath != null || inEditSession);

  /// Whether the album this image belongs to is being edited (issue #80).
  ///
  /// The session lives with the router, keyed by the album's path, so a trip
  /// into an image and back finds the album still being edited; what the album
  /// view reads off it, this view is handed, see `app.dart`.
  bool get inEditSession => widget.editing;

  /// Opens the description dialog on the displayed image (issue #80).
  ///
  /// The dialog the tile editor of the album opens, on the image that is being
  /// looked at — which is where a description is written in practice, and for
  /// a group member in detail mode it is that member's own description. A
  /// caller who may not change the album is told so rather than being left
  /// with a gesture that does nothing, see [notEditableMessage].
  Future<void> editDescription() async {
    var self = album;
    if (self == null) {
      // No album behind this image: nothing to write into, and nothing this
      // view could say that would help.
      return;
    }
    if (!mayEditDescription) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(notEditableMessage, key: Key("image-not-editable")),
          duration: Duration(seconds: 4),
        ),
      );
      return;
    }
    await _askForDescription(self, part, part.comment);
  }

  /// Asks for the description of [image], starting from [initial], and applies
  /// what comes back.
  ///
  /// A refused write comes back here with the text that was typed: the server
  /// said why, and what was written must not be lost to a failed request.
  Future<void> _askForDescription(
    AlbumInfo owner,
    ImagePart image,
    String initial,
  ) async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: descriptionDialogTitle,
        label: descriptionDialogLabel,
        text: initial,
        multiLine: true,
        // Who added this photo: the screen saying what an image is says where
        // it came from, exactly as the tile editor does, see issue #53.
        note: attributionShown(image),
      ),
    );
    if (text == null || !mounted || text == image.comment) {
      return;
    }
    var before = image.comment;
    setState(() => image.comment = text);
    if (inEditSession) {
      // The album is being edited: this belongs in the same buffer as every
      // other edit, and is written when the album is saved.
      widget.onEdited?.call();
      return;
    }
    var path = widget.editPath;
    if (path == null) {
      // Guarded by [mayEditDescription]; here for the reader.
      return;
    }
    if (refuseWhileOffline(context)) {
      setState(() => image.comment = before);
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    try {
      await widget.client.saveAlbum(path, owner);
    } catch (error) {
      if (!mounted) {
        return;
      }
      // Nothing was stored, so nothing has changed: the caption says what the
      // server holds, not what was attempted.
      setState(() => image.comment = before);
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            error is VAlbumException ? error.message : "$error",
            key: const Key("image-description-failed"),
          ),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );
      // The text is not thrown away with the request: the dialog comes back
      // holding it, so that a retry costs no typing.
      await _askForDescription(owner, image, text);
    }
  }

  /// The group the displayed image belongs to, `null` if it is a single image.
  ImageGroup? get group {
    var self = widget.image;
    return self is ImageGroup ? self : part.group;
  }

  /// Whether the failure of the current picture was already reported, so that
  /// a picture that keeps failing says so once (issue #95).
  bool _failureReported = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The first thing a viewer does, before anybody pages anywhere.
    prefetchNeighbours();
  }

  @override
  void didUpdateWidget(ImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.image != widget.image) {
      // Start over with the fitted view.
      _stopSnapBack();
      _transform = null;
      _failureReported = false;
      // The neighbours of the image now shown are two others, see issue #101.
      prefetchNeighbours();
    }
  }

  /// The picture of the displayed image, see [viewerPicture].
  ImageProvider get picture => pictureOf(part);

  /// The picture of any image of this album, the displayed one included.
  ///
  /// The rights are the album's, and the neighbours live in the same album, so
  /// what the caller may have of them is what they may have of this one.
  ImageProvider pictureOf(ImagePart image) => viewerPicture(
        widget.client,
        "${widget.baseUrl}/${image.name}",
        mayDownload: rights.mayDownload,
      );

  /// Fetches and decodes the pictures of [previous] and [next] (issue #101).
  ///
  /// Paging then paints the neighbour from the [ImageCache] in the very frame
  /// the route changes, instead of leaving the screen empty for as long as an
  /// original takes to arrive. Exactly two — the memory of a phone is not an
  /// album — and the same provider the display would use, so that the picture
  /// really is a cache hit and not a second download.
  ///
  /// A video is left alone: what the viewer shows of one is the player, whose
  /// poster is the thumbnail the album has anyway. A prefetch that fails is
  /// simply not cached and never reported — nobody asked for it.
  void prefetchNeighbours() {
    for (var neighbour in [previous, next]) {
      if (neighbour == null) {
        continue;
      }
      var image = ToImage.toImage(neighbour);
      if (image.kind != ImageKind.image) {
        continue;
      }
      precacheImage(pictureOf(image), context, onError: (error, stack) {});
    }
  }

  /// Says once that the picture could not be delivered, see
  /// [pictureFailedMessage].
  ///
  /// Called from a builder, so the message is shown after the frame: a
  /// snack bar may not be put up while the tree is being built.
  void _reportFailure() {
    if (_failureReported) {
      return;
    }
    _failureReported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(pictureFailedMessage, key: Key("image-failed")),
          duration: Duration(seconds: 6),
        ),
      );
    });
  }

  /// The transform of the image in a viewport of the given size.
  ImageTransform transform(Size page) {
    var result = _transform;
    if (result == null ||
        result.pageWidth != page.width ||
        result.pageHeight != page.height) {
      result = _transform = ImageTransform.ofImage(
        part,
        pageWidth: page.width,
        pageHeight: page.height,
      );
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: CallbackShortcuts(
        bindings: <ShortcutActivator, VoidCallback>{
          const SingleActivator(LogicalKeyboardKey.arrowLeft): showPrevious,
          const SingleActivator(LogicalKeyboardKey.arrowRight): showNext,
          const SingleActivator(LogicalKeyboardKey.home): showFirst,
          const SingleActivator(LogicalKeyboardKey.end): showLast,
          const SingleActivator(LogicalKeyboardKey.arrowUp): showParent,
          const SingleActivator(LogicalKeyboardKey.arrowDown): showGroup,
          // Not a touch-only feature: a long press is what a phone has, `e`
          // is what a keyboard has (issue #80).
          const SingleActivator(LogicalKeyboardKey.keyE): editDescription,
        },
        child: Focus(
          autofocus: true,
          child: LayoutBuilder(
            builder: (context, constraints) {
              var page = constraints.biggest;
              return Stack(
                fit: StackFit.expand,
                children: [
                  isVideo ? buildVideoViewer() : buildViewer(page),
                  ...buildOverlay(context),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  /// The image itself, with the gesture handling.
  Widget buildViewer(Size page) {
    var tx = transform(page);

    return Listener(
      onPointerSignal: (event) {
        if (event is PointerScrollEvent) {
          setState(() {
            tx.zoom(
              event.scrollDelta.dy.sign,
              event.localPosition.dx,
              event.localPosition.dy,
            );
          });
        }
      },
      child: GestureDetector(
        onTapUp: (details) => setState(() {
          tx.toggle(details.localPosition.dx, details.localPosition.dy);
        }),
        // The description of the image, where the image is looked at
        // (issue #80).
        onLongPress: editDescription,
        onScaleStart: (details) {
          _stopSnapBack();
          _gestureScale = tx.scale;
          _gestureTx = tx.tx;
          _gestureTy = tx.ty;
          _gestureFocus = details.localFocalPoint;
          _multiTouch = details.pointerCount > 1;
          _dragDx = 0;
          // A fitted image pages; a zoomed one pans, as it always did.
          _axis = _multiTouch || tx.scale > tx.fitScale
              ? _DragAxis.free
              : _DragAxis.undecided;
        },
        onScaleUpdate: (details) {
          if (details.pointerCount > 1) {
            // A second finger turns the drag into a pinch: free pan and zoom.
            _multiTouch = true;
            _axis = _DragAxis.free;
          }
          if (_axis != _DragAxis.free) {
            dragFitted(tx, details.localFocalPoint);
            return;
          }
          setState(() {
            var newScale = _gestureScale * details.scale;

            // The point of the image that was grabbed, in image pixels.
            var imgX = (_gestureFocus.dx - _gestureTx) / _gestureScale;
            var imgY = (_gestureFocus.dy - _gestureTy) / _gestureScale;

            var focus = details.localFocalPoint;
            tx.setCustom(
              focus.dx - imgX * newScale,
              focus.dy - imgY * newScale,
              newScale,
            );
          });
        },
        onScaleEnd: (details) => onDragEnd(tx, details),
        child: ClipRect(
          child: Stack(
            clipBehavior: Clip.none,
            children: buildLayers(tx),
          ),
        ),
      ),
    );
  }

  /// What is drawn into the viewport: the thumbnail, and the picture over it.
  ///
  /// Two layers, because a picture that is not there yet must not leave the
  /// screen empty (issue #101): the thumbnail of the album is already fetched
  /// and decoded, so it is painted in the very first frame and the picture
  /// appears over it as soon as its own first frame arrives. A switch from one
  /// image to the next is therefore thumbnail -> sharp, never blank -> sharp,
  /// and with the neighbour prefetched it is sharp at once.
  List<Widget> buildLayers(ImageTransform tx) => [
        uprightLayer(
          tx,
          thumbnail(
            widget.client,
            dataUrl,
            key: const Key("image-thumbnail"),
            fit: BoxFit.contain,
          ),
        ),
        buildContent(tx),
      ];

  /// The picture itself, in the layer its coordinates belong to.
  Widget buildContent(ImageTransform tx) {
    var image = Image(
      // What a test asks for when it asks what the viewer shows: the
      // thumbnail beneath is a second [Image] in the same tree.
      key: const Key("image-picture"),
      image: picture,
      fit: BoxFit.fill,
      // A rebuild with the same picture keeps what is on the screen.
      gaplessPlayback: true,
      // Nothing until the first frame has decoded: what shows through is the
      // thumbnail beneath, see [buildLayers].
      frameBuilder: (context, child, frame, wasSynchronouslyLoaded) =>
          wasSynchronouslyLoaded || frame != null
              ? child
              : const SizedBox.expand(),
      // `Image` can only say *that* the picture failed. It is never a refused
      // right — the viewer asks for nothing this caller may not have, see
      // [viewerPicture] — so it is a failure, and failures speak once.
      errorBuilder: (context, error, stackTrace) {
        _reportFailure();
        return const SizedBox.expand();
      },
    );
    return rights.mayDownload ? rawLayer(tx, image) : uprightLayer(tx, image);
  }

  /// The layer of a picture in the *raw* pixels of the file: the original.
  ///
  /// [ImageTransform.matrix] maps the raw rectangle onto the viewport, the
  /// [ImagePart.orientation] included — a camera's rotation flag is applied
  /// here, on the way to the screen.
  Widget rawLayer(ImageTransform tx, Widget child) => Positioned(
        left: 0,
        top: 0,
        width: tx.rawWidth,
        height: tx.rawHeight,
        child: Transform(transform: tx.matrix, child: child),
      );

  /// The layer of a picture the server has already turned upright: the
  /// thumbnail and the preview rendition.
  ///
  /// `PreviewCache` applies the orientation when it makes a rendition (which
  /// is why the album tiles show one unrotated), so applying it again here
  /// would lay every portrait photo on its side. Only the zoom and the pan of
  /// [tx] are left, over the box the oriented image occupies.
  Widget uprightLayer(ImageTransform tx, Widget child) => Positioned(
        left: 0,
        top: 0,
        width: tx.width,
        height: tx.height,
        child: Transform(
          transform: Matrix4.identity()
            ..setEntry(0, 0, tx.scale)
            ..setEntry(1, 1, tx.scale)
            ..setEntry(0, 3, tx.tx)
            ..setEntry(1, 3, tx.ty),
          child: child,
        ),
      );

  /// The video player, filling the slot the image would occupy.
  ///
  /// Zoom and pan make no sense for a video (and would fight the player's own
  /// controls), so the video is only fitted into the page; the swipe gestures
  /// of the image viewer are kept, as are all the surrounding chrome and the
  /// keyboard shortcuts.
  Widget buildVideoViewer() {
    var self = part;
    var aspectRatio =
        self.width > 0 && self.height > 0 ? self.width / self.height : 16 / 9;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      // A video has a description like every other part of the album
      // (issue #80).
      onLongPress: editDescription,
      onScaleStart: (details) {},
      onScaleEnd: (details) {
        if (details.pointerCount <= 1) {
          onSwipe(details);
        }
      },
      child: Center(
        child: AspectRatio(
          aspectRatio: aspectRatio,
          child: VideoView(
            key: ValueKey(dataUrl),
            // The original is the fallback and the download; what is played
            // is the rendition, where the server has one (issue #75).
            videoUrl: widget.client.originalUrl(dataUrl),
            renditionUrl: widget.client.playbackUrl(dataUrl),
            probeRendition: widget.client.renditionState,
            posterUrl: widget.client.thumbnailUrl(dataUrl),
            headers: widget.client.authHeaders,
            // A failure of the platform player goes into the same log every
            // request of this app goes into, see issue #73.
            log: widget.client.log,
          ),
        ),
      ),
    );
  }

  /// Moves the fitted image with the finger, along one axis only (issue #61).
  ///
  /// The axis is decided from the first [_axisThreshold] pixels of the
  /// movement: a horizontal drag pages to the neighbouring image and the
  /// picture follows it, a vertical one is the way back to the album (or up to
  /// the group's alternatives) and leaves the picture where it is — there is
  /// no album sliding in behind it that the movement could point at, and an
  /// image that only ever moves where paging can take it says plainly what the
  /// gesture does.
  void dragFitted(ImageTransform tx, Offset focus) {
    var delta = focus - _gestureFocus;
    if (_axis == _DragAxis.undecided) {
      if (delta.distance < _axisThreshold) {
        return;
      }
      _axis = delta.dx.abs() >= delta.dy.abs()
          ? _DragAxis.horizontal
          : _DragAxis.vertical;
    }
    if (_axis != _DragAxis.horizontal) {
      return;
    }

    _dragDx = delta.dx;
    var moved = boundedDrag(tx, delta.dx);
    setState(() => tx.setCustom(_gestureTx + moved, _gestureTy, _gestureScale));
  }

  /// How far the image really moves for a drag of [dx], see [dragFitted].
  ///
  /// A drag towards an image that is there follows the finger; one at the
  /// start (or the end) of the album has nothing to page to and is answered by
  /// a rubber band that approaches [_rubberBandFraction] of the page and never
  /// passes it.
  double boundedDrag(ImageTransform tx, double dx) {
    var blocked = dx > 0 ? previous == null : next == null;
    if (!blocked) {
      return dx;
    }
    var limit = tx.pageWidth * _rubberBandFraction;
    return limit <= 0 ? 0 : dx / (1 + dx.abs() / limit);
  }

  /// Finishes a drag: a fast or a long drag of the un-zoomed image is a swipe.
  void onDragEnd(ImageTransform tx, ScaleEndDetails details) {
    var axis = _axis;
    var dragDx = _dragDx;
    _axis = _DragAxis.free;
    _dragDx = 0;

    if (details.pointerCount > 1 ||
        _multiTouch ||
        axis == _DragAxis.free ||
        tx.scale > tx.fitScale) {
      // A pinch, or the image is zoomed in: the drag was a pan.
      return;
    }

    if (axis == _DragAxis.horizontal) {
      if (!swipeHorizontally(tx, dragDx, details.velocity)) {
        // Not far and not fast enough: the image slides back.
        snapBack(tx);
      }
      return;
    }

    // The vertical gestures never moved the image, see [dragFitted].
    setState(tx.reset);
    if (axis == _DragAxis.vertical) {
      swipeVertically(details.velocity);
    } else {
      onSwipe(details);
    }
  }

  /// Pages to the neighbouring image if the horizontal drag asked for it.
  ///
  /// A flick navigates as it always did; a slow drag navigates once it crossed
  /// [_swipeDistanceFraction] of the page width. Neither passes the ends of
  /// the album: where there is no neighbour, the drag snaps back.
  bool swipeHorizontally(ImageTransform tx, double dragDx, Velocity velocity) {
    var speed = velocity.pixelsPerSecond;
    var flick =
        speed.distance >= _swipeVelocity && speed.dx.abs() > speed.dy.abs();
    var far = dragDx.abs() >= tx.pageWidth * _swipeDistanceFraction;
    if (!flick && !far) {
      return false;
    }

    // Dragging (or flicking) to the left uncovers the next image. A flick
    // against a drag that had already gone far is the finger saying "no":
    // the image goes back to where it was, it does not page the other way.
    var flickForwards = speed.dx < 0;
    var dragForwards = dragDx < 0;
    if (flick && far && flickForwards != dragForwards) {
      return false;
    }
    var forwards = flick ? flickForwards : dragForwards;
    var target = forwards ? next : previous;
    if (target == null) {
      return false;
    }

    setState(tx.reset);
    show(target);
    return true;
  }

  /// Answers a vertical flick: down leaves the viewer, up opens the group.
  void swipeVertically(Velocity velocity) {
    var speed = velocity.pixelsPerSecond;
    if (speed.distance < _swipeVelocity) {
      return;
    }
    if (speed.dy > 0) {
      showParent();
    } else {
      showGroup();
    }
  }

  /// Navigates if the finished gesture was a fast enough swipe.
  ///
  /// Used where the image itself is not dragged (the video, and a gesture too
  /// short to decide an axis). Both ends of the album are respected by [show],
  /// which does nothing where there is no image to go to.
  void onSwipe(ScaleEndDetails details) {
    var velocity = details.velocity.pixelsPerSecond;
    if (velocity.distance < _swipeVelocity) {
      return;
    }

    if (velocity.dx.abs() > velocity.dy.abs()) {
      if (velocity.dx > 0) {
        showPrevious();
      } else {
        showNext();
      }
    } else {
      swipeVertically(details.velocity);
    }
  }

  /// Slides the dragged image back to its fitted position, see [onDragEnd].
  void snapBack(ImageTransform tx) {
    _snapFrom = tx.tx;
    if ((_snapFrom - tx.fitTx).abs() < 0.5) {
      setState(tx.reset);
      return;
    }
    _snapping = tx;
    _snapBack
      ..reset()
      ..forward();
  }

  /// Stops a running snap-back, leaving the image where it stands.
  void _stopSnapBack() {
    _snapping = null;
    _snapBack.stop();
  }

  /// One step of the snap-back animation, see [snapBack].
  void _onSnapBack() {
    var tx = _snapping;
    if (tx == null) {
      return;
    }
    setState(() {
      if (_snapBack.value >= 1) {
        _snapping = null;
        tx.reset();
      } else {
        var moved = Curves.easeOut.transform(_snapBack.value);
        tx.setCustom(
          _snapFrom + (tx.fitTx - _snapFrom) * moved,
          tx.fitTy,
          tx.fitScale,
        );
      }
    });
  }

  /// The navigation chevrons and the caption shown on top of the image.
  List<Widget> buildOverlay(BuildContext context) {
    var self = part;
    var attribution = attributionOf(context, self);
    // The controls stay clear of the system bars (issue #60): the viewer runs
    // immersive, but a bar shown by a swipe from the edge must never sit on a
    // button — in landscape the navigation bar is exactly where the chevrons
    // are.
    var insets = MediaQuery.paddingOf(context);
    var actions = [
      ...widget.actions,
      if (widget.albumPath != null && mayTakeBack(context, self))
        overlayButton(
          Icons.undo,
          "Take back…",
          takeBack,
          key: const Key("image-take-back"),
        ),
    ];
    return [
      Positioned(
        left: insets.left + 8,
        top: insets.top + 8,
        child: overlayButton(Icons.arrow_back, "Back to the album", showParent),
      ),
      if (previous != null)
        Positioned(
          left: insets.left + 8,
          top: insets.top,
          bottom: insets.bottom,
          child: Center(
            child: overlayButton(
              Icons.chevron_left,
              "Previous image",
              showPrevious,
            ),
          ),
        ),
      if (next != null)
        Positioned(
          right: insets.right + 8,
          top: insets.top,
          bottom: insets.bottom,
          child: Center(
            child: overlayButton(
              Icons.chevron_right,
              "Next image",
              showNext,
            ),
          ),
        ),
      if (actions.isNotEmpty)
        Positioned(
          right: insets.right + 8,
          top: insets.top + 8,
          child: Row(mainAxisSize: MainAxisSize.min, children: actions),
        ),
      if (group != null && widget.onShowGroup != null)
        Positioned(
          left: insets.left,
          right: insets.right,
          top: insets.top + 8,
          child: Center(
            child: overlayButton(
              Icons.expand_more,
              "Show the alternatives",
              showGroup,
            ),
          ),
        ),
      if (self.comment.isNotEmpty || attribution != null)
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          child: buildCaption(self.comment, attribution, insets),
        ),
    ];
  }

  Widget overlayButton(
    IconData icon,
    String tooltip,
    VoidCallback onPressed, {
    Key? key,
  }) =>
      imageOverlayButton(icon, tooltip, onPressed, key: key);

  /// What is written under the image: who added it, and what was said about
  /// it.
  ///
  /// One block, not two: the attribution and the comment read as one caption,
  /// in one style, with the attribution first — it says where the picture
  /// comes from, the comment says what it shows. An image with neither carries
  /// no caption at all, exactly as before issue #53.
  Widget buildCaption(
    String comment,
    String? attribution,
    EdgeInsets insets,
  ) =>
      Container(
        key: const Key("image-caption"),
        color: Colors.black54,
        // The block may run under the bars, its text may not (issue #60).
        padding: EdgeInsets.fromLTRB(
          insets.left + 16,
          16,
          insets.right + 16,
          insets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (attribution != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  attribution,
                  key: const Key("image-contributor"),
                  style: const TextStyle(color: Colors.white),
                  textAlign: TextAlign.center,
                ),
              ),
            if (comment.isNotEmpty) buildComment(comment),
          ],
        ),
      );

  /// The comment of the image, one [Text] per paragraph.
  Widget buildComment(String comment) => Column(
        key: const Key("image-comment"),
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var paragraph in commentParagraphs(comment))
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                paragraph,
                style: const TextStyle(color: Colors.white),
                textAlign: TextAlign.center,
              ),
            ),
        ],
      );

  /// Takes the displayed photo back out of this album, see issue #53.
  ///
  /// A take-back is a move and nothing else: the same folder picker the album
  /// offers under "Move to…", the same `?action=move`, the same reading-out of
  /// what the server answered. The app never deletes a photo, and the server
  /// refuses what this caller may not take (`CONTRIBUTION_REFUSED`), which is
  /// then shown in the server's own words.
  ///
  /// A group is named by its representative — the image this viewer shows —
  /// so a grouped photo is taken back with its alternatives, which is how a
  /// group moves everywhere else.
  ///
  /// Deliberately one photo at a time: taking several back would need a
  /// selection, and a selection lives in the edit mode, which is exactly what
  /// a contributor does not have.
  Future<void> takeBack() async {
    var path = widget.albumPath;
    if (path == null) {
      return;
    }
    await moveWithPicker(
      context: context,
      client: widget.client,
      source: path,
      names: [part.name],
      subject: const ImageSubject(1),
      onMoved: () {
        // The photo is no longer in this album, so the viewer showing it has
        // nothing left to show: back to the album, which is fetched again.
        var onTakenBack = widget.onTakenBack;
        if (onTakenBack != null) {
          onTakenBack();
        } else {
          showParent();
        }
      },
    );
  }

  void showPrevious() => show(previous);

  void showNext() => show(next);

  void showFirst() => show(firstVisible(homeOf(widget.image), minRating));

  void showLast() => show(lastVisible(endOf(widget.image), minRating));

  void show(AbstractImage? image) {
    if (image != null) {
      widget.onShowImage(image);
    }
  }

  /// Leaves the viewer, back to the album.
  void showParent() {
    widget.onUp?.call();
  }

  /// Opens the "alternatives" view of the group the image belongs to.
  void showGroup() {
    var self = group;
    var onShowGroup = widget.onShowGroup;
    if (self != null && onShowGroup != null) {
      onShowGroup(self);
    }
  }
}

/// A button floating over the image of an [ImageView].
Widget imageOverlayButton(
  IconData icon,
  String tooltip,
  VoidCallback onPressed, {
  Color color = Colors.white,
  Key? key,
}) =>
    IconButton(
      key: key,
      icon: Icon(icon),
      iconSize: 32,
      color: color,
      tooltip: tooltip,
      style: IconButton.styleFrom(backgroundColor: Colors.black38),
      onPressed: onPressed,
    );
