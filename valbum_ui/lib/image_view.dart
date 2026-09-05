/// The single image viewer: zoom, pan, swipe and keyboard navigation.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'album_layout.dart' show ToImage;
import 'client.dart';
import 'image_transform.dart';
import 'resource.dart';
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

  const ImageView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.image,
    required this.onShowImage,
    this.onShowGroup,
    this.onUp,
    this.minRating,
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

  @override
  void didUpdateWidget(ImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.image != widget.image) {
      // Start over with the fitted view.
      _transform = null;
    }
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

  /// The navigation chevrons and the comment shown on top of the image.
  List<Widget> buildOverlay(BuildContext context) {
    var self = part;
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
      if (self.comment.isNotEmpty)
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          child: buildComment(self.comment),
        ),
    ];
  }

  Widget overlayButton(IconData icon, String tooltip, VoidCallback onPressed) =>
      IconButton(
        icon: Icon(icon),
        iconSize: 32,
        color: Colors.white,
        tooltip: tooltip,
        style: IconButton.styleFrom(backgroundColor: Colors.black38),
        onPressed: onPressed,
      );

  /// The comment of the image, one [Text] per paragraph.
  Widget buildComment(String comment) => Container(
        key: const Key("image-comment"),
        color: Colors.black54,
        padding: const EdgeInsets.all(16),
        child: Column(
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
        ),
      );

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
