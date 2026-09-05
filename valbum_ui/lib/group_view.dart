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

  const GroupView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.group,
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
          onPressed: () => Navigator.maybePop(context),
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
            child: Column(
              children: layout.map((row) => row.visit(builder, 0.0)).toList(),
            ),
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
      child: Image.network(
        client.thumbnailUrl("$baseUrl/${self.name}"),
        key: ValueKey("group-tile-${self.name}"),
        width: width,
        height: height,
        fit: BoxFit.contain,
      ),
    );
  }

  /// Opens the given image of the group in the single image viewer.
  void showDetail(BuildContext context, ImagePart image) {
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (context) => GroupDetailView(
          client: client,
          baseUrl: baseUrl,
          image: image,
        ),
      ),
    );
  }
}

/// The single image viewer inside a group: previous/next stay in the group,
/// no image is filtered out and "down" does nothing.
class GroupDetailView extends StatefulWidget {
  final VAlbumClient client;
  final String baseUrl;
  final ImagePart image;

  const GroupDetailView({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.image,
  });

  @override
  State<GroupDetailView> createState() => GroupDetailViewState();
}

class GroupDetailViewState extends State<GroupDetailView> {
  late AbstractImage image = widget.image;

  @override
  Widget build(BuildContext context) => ImageView(
        client: widget.client,
        baseUrl: widget.baseUrl,
        image: image,
        onShowImage: (next) => setState(() => image = next),
        // No "down" out of the detail view, and no rating filter: the group
        // shows all of its alternatives.
        minRating: noMinRating,
      );
}
