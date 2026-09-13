/// The single image viewer: zoom, pan, swipe and keyboard navigation.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'album_layout.dart' show ToImage;
import 'attribution.dart';
import 'client.dart';
import 'image_transform.dart';
import 'move_view.dart';
import 'resource.dart';
import 'thumbnails.dart';
import 'video_view.dart';

/// The velocity (in pixels per second) a drag must reach to count as a swipe.
const double _swipeVelocity = 400;

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
  });

  @override
  State<ImageView> createState() => ImageViewState();
}

class ImageViewState extends State<ImageView> {
  ImageTransform? _transform;

  /// The state the transform had when the current gesture started.
  double _gestureScale = 1;
  double _gestureTx = 0;
  double _gestureTy = 0;
  Offset _gestureFocus = Offset.zero;

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

  /// The group the displayed image belongs to, `null` if it is a single image.
  ImageGroup? get group {
    var self = widget.image;
    return self is ImageGroup ? self : part.group;
  }

  /// The server's reason for not showing the original, `null` while the
  /// picture is fine or the reason has not been asked for yet (issue #49).
  ///
  /// A `view`-only grant lets the album and its thumbnails through and refuses
  /// the original with a 403 and a message meant for the user. The viewer
  /// opens the original — until a preview rendition exists (Phase 4) that is
  /// all there is — so it says what the server said, over the thumbnail, and
  /// never shows a broken picture.
  String? _refusal;

  /// Whether the reason has already been asked for, so that a picture that
  /// keeps failing asks once.
  bool _refusalAsked = false;

  @override
  void didUpdateWidget(ImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.image != widget.image) {
      // Start over with the fitted view.
      _transform = null;
      _refusal = null;
      _refusalAsked = false;
    }
  }

  /// Asks the server why the original did not load, once per image.
  void _askRefusal() {
    if (_refusalAsked) {
      return;
    }
    _refusalAsked = true;
    widget.client.originalRefusal(dataUrl).then((message) {
      if (mounted && message != null) {
        setState(() => _refusal = message);
      }
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
        onScaleStart: (details) {
          _gestureScale = tx.scale;
          _gestureTx = tx.tx;
          _gestureTy = tx.ty;
          _gestureFocus = details.localFocalPoint;
        },
        onScaleUpdate: (details) => setState(() {
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
        }),
        onScaleEnd: (details) => onDragEnd(tx, details),
        child: ClipRect(
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned(
                left: 0,
                top: 0,
                width: tx.rawWidth,
                height: tx.rawHeight,
                child: Transform(
                  transform: tx.matrix,
                  child: buildContent(),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The image itself.
  Widget buildContent() {
    var self = part;
    return Image.network(
      widget.client.originalUrl(dataUrl),
      // The original is not cached (an album of originals would fill the
      // device), but it must still identify itself: a server started with
      // `--auth all` refuses an anonymous image.
      headers: widget.client.authHeaders,
      width: self.width.toDouble(),
      height: self.height.toDouble(),
      fit: BoxFit.fill,
      // `Image.network` opens a connection of its own and can only say *that*
      // the picture failed; the reason is asked for through the client, see
      // [_askRefusal]. Until it arrives the thumbnail stands in, so the screen
      // is never a broken image.
      errorBuilder: (context, error, stackTrace) {
        WidgetsBinding.instance
            .addPostFrameCallback((_) => _askRefusal());
        return buildRefused(self);
      },
    );
  }

  /// What is shown in place of an original the server did not hand over: the
  /// thumbnail, with the server's own reason over it.
  Widget buildRefused(ImagePart self) {
    var message = _refusal;
    return SizedBox(
      width: self.width.toDouble(),
      height: self.height.toDouble(),
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Whatever the album already showed: a thumbnail the caller may see
          // (and the cache may still hold) is better than a grey box.
          thumbnail(widget.client, dataUrl, fit: BoxFit.contain),
          if (message != null)
            Center(
              child: Container(
                color: Colors.black54,
                padding: const EdgeInsets.all(16),
                child: Text(
                  message,
                  key: const Key("image-refusal"),
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white, fontSize: 16),
                ),
              ),
            ),
        ],
      ),
    );
  }

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
            videoUrl: widget.client.originalUrl(dataUrl),
            posterUrl: widget.client.thumbnailUrl(dataUrl),
            headers: widget.client.authHeaders,
          ),
        ),
      ),
    );
  }

  /// Finishes a drag: a fast drag of the un-zoomed image is a swipe.
  void onDragEnd(ImageTransform tx, ScaleEndDetails details) {
    if (details.pointerCount > 1 || tx.scale > tx.fitScale) {
      // A pinch, or the image is zoomed in: the drag was a pan.
      return;
    }

    // The image was dragged around while fitted: snap it back.
    setState(tx.reset);

    onSwipe(details);
  }

  /// Navigates if the finished gesture was a fast enough swipe.
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
      if (velocity.dy > 0) {
        showParent();
      } else {
        showGroup();
      }
    }
  }

  /// The navigation chevrons and the caption shown on top of the image.
  List<Widget> buildOverlay(BuildContext context) {
    var self = part;
    var attribution = attributionOf(context, self);
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
        left: 8,
        top: 8,
        child: overlayButton(Icons.arrow_back, "Back to the album", showParent),
      ),
      if (previous != null)
        Positioned(
          left: 8,
          top: 0,
          bottom: 0,
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
          right: 8,
          top: 0,
          bottom: 0,
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
          right: 8,
          top: 8,
          child: Row(mainAxisSize: MainAxisSize.min, children: actions),
        ),
      if (group != null && widget.onShowGroup != null)
        Positioned(
          left: 0,
          right: 0,
          top: 8,
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
          child: buildCaption(self.comment, attribution),
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
  Widget buildCaption(String comment, String? attribution) => Container(
        key: const Key("image-caption"),
        color: Colors.black54,
        padding: const EdgeInsets.all(16),
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
