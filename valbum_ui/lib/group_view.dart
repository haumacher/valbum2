/// The "alternatives" view of an [ImageGroup]: all images of a group in the
/// row layout, and the single image viewer navigating within the group.
///
/// This is the retired GWT `GroupDisplay`. The album shows a group by its
/// representative; opening that image and pressing the "down" chevron (or
/// `ArrowDown`, or swiping up) lands here, where every image of the group is
/// shown, no matter how it is rated. Tapping one of them opens the viewer in
/// "detail mode": its previous/next follow the group's own image order (the
/// links [AlbumInitializer] built inside the group), "up" returns here, and
/// there is no "down" any more.
library;

import 'package:flutter/material.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'album_edit.dart';
import 'album_model.dart';
import 'album_view.dart';
import 'client.dart';
import 'image_view.dart';
import 'resource.dart';
import 'thumbnails.dart';

/// The height a row of the group layout aims at, as in the album view.
const double _maxRowHeight = 250;

/// Lists all images of an [ImageGroup].
class GroupView extends StatelessWidget {
  /// The transport to the album server.
  final VAlbumClient client;

  /// The URL of the album the group belongs to.
  final String baseUrl;

  /// The group whose images are shown.
  final ImageGroup group;

  /// Leaves the alternatives view, back to the image it was opened from.
  final VoidCallback onUp;

  /// Opens the given member of the group in the viewer ("detail mode").
  final void Function(ImagePart image) onShowDetail;

  const GroupView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.group,
    required this.onUp,
    required this.onShowDetail,
  });

  /// The title of the album the group belongs to, as in the GWT client.
  String get title => group.owner?.title ?? "";

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_upward),
          tooltip: "Zurück zum Album",
          onPressed: onUp,
        ),
      ),
      backgroundColor: Colors.black,
      body: LayoutBuilder(
        builder: (context, constraints) {
          var layout = layouter.AlbumLayout(
            constraints.maxWidth,
            _maxRowHeight,
            group.images,
          );
          var builder = ContentWidgetBuilder(
            (image, width, height) => tile(context, image, width, height),
            layout.getPageWidth(),
          );
          return SingleChildScrollView(
            child: Column(children: builder.buildRows(layout)),
          );
        },
      ),
    );
  }

  /// The tile of one group member; tapping it opens the viewer in detail mode.
  Widget tile(
    BuildContext context,
    AbstractImage image,
    double width,
    double height,
  ) {
    var self = layouter.ToImage.toImage(image);
    return GestureDetector(
      onTap: () => showDetail(context, self),
      child: Stack(
        children: [
          thumbnail(
            client,
            "$baseUrl/${self.name}",
            key: ValueKey("group-tile-${self.name}"),
            width: width,
            height: height,
            fit: BoxFit.contain,
          ),
          // The representative stands out, so that the choice can be checked
          // here, where all alternatives are seen side by side.
          if (identical(group.images[group.representative], self))
            const Positioned(
              right: 8,
              top: 8,
              child: Tooltip(
                message: "Gruppenbild",
                child: Icon(
                  Icons.check_circle,
                  key: Key("group-representative"),
                  color: Colors.amberAccent,
                  shadows: [Shadow(color: Colors.black, blurRadius: 4)],
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// Opens the given image of the group in the single image viewer.
  void showDetail(BuildContext context, ImagePart image) => onShowDetail(image);
}

/// The single image viewer inside a group: previous/next stay in the group,
/// no image is filtered out and "down" does nothing.
class GroupDetailView extends StatelessWidget {
  final VAlbumClient client;
  final String baseUrl;
  final ImagePart image;

  /// Shows another member of the same group.
  final void Function(AbstractImage image) onShowImage;

  /// Leaves the detail view, back to the alternatives.
  final VoidCallback onUp;

  /// Whether [image] is the one representing its group in the album.
  final bool isRepresentative;

  /// Makes [image] the representative of its group; `null` outside the edit
  /// mode of the album, where the choice is not offered.
  ///
  /// Deciding which of several shots of one scene is the best is what the
  /// detail view is for: the shots are compared full-screen, one after the
  /// other, and the winner is picked where it is seen.
  final VoidCallback? onSetRepresentative;

  const GroupDetailView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.image,
    required this.onShowImage,
    required this.onUp,
    this.isRepresentative = false,
    this.onSetRepresentative,
  });

  @override
  Widget build(BuildContext context) {
    var onSetRepresentative = this.onSetRepresentative;
    return ImageView(
      client: client,
      baseUrl: baseUrl,
      image: image,
      onShowImage: onShowImage,
      onUp: onUp,
      // No "down" out of the detail view, and no rating filter: the group
      // shows all of its alternatives.
      minRating: noMinRating,
      actions: [
        if (onSetRepresentative != null)
          imageOverlayButton(
            isRepresentative ? Icons.check_circle : Icons.check_circle_outline,
            isRepresentative
                ? "Dieses Bild ist das Gruppenbild"
                : "Als Gruppenbild verwenden",
            onSetRepresentative,
            color: isRepresentative ? Colors.amberAccent : Colors.white,
          ),
      ],
    );
  }
}
