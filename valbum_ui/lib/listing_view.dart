/// The folder listing view and the dialogs creating albums and folders.
library;

import 'package:date_field/date_field.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'app.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'resource.dart';
import 'offline.dart';
import 'settings.dart';
import 'thumbnails.dart';

/// The edge length (in CSS pixels) of the square folder preview the retired
/// GWT client rendered its index pictures into.
///
/// The [ThumbnailInfo.tx]/[ThumbnailInfo.ty] the server computes are pixel
/// offsets within a preview of exactly this size (see `.va-preview` in the
/// former `valbum.css`, and `ResourceCache.loadFolderInfo` deriving
/// `ty = (h - w) / h * 150`, half of this box). They are therefore scaled to
/// the actual tile size before use, see [thumbnailTransform].
const double cssPreviewSize = 300.0;

/// The transform cropping an index picture into a square tile of [tileSize].
///
/// This reproduces the CSS the GWT client emitted on the preview image:
///
/// ```css
/// transform: scale(<scale>) translate(<tx>px, <ty>px);
/// ```
///
/// CSS applies the functions of a transform list right to left about the
/// element's centre (the default `transform-origin`), so the image is first
/// translated by (tx, ty) and the result is then scaled about the centre --
/// the offsets are given in *pre-scale* pixels. The matrix below is built in
/// exactly that order (`scale * translate`), and the offsets are scaled by
/// `tileSize / cssPreviewSize` so that the same [ThumbnailInfo] yields the
/// same crop at any tile size.
///
/// A [ThumbnailInfo.scale] of zero (the field's default, i.e. an index picture
/// without a scale) is read as "no zoom".
Matrix4 thumbnailTransform(ThumbnailInfo info, double tileSize) {
  var scale = info.scale > 0 ? info.scale : 1.0;
  var factor = tileSize / cssPreviewSize;
  return Matrix4.diagonal3Values(scale, scale, 1.0)
      .multiplied(Matrix4.translationValues(
    info.tx * factor,
    info.ty * factor,
    0,
  ));
}

/// The square tile showing an index picture, in the listing and in the crop
/// editor of the album properties alike, so that what is edited is what is
/// shown.
///
/// This is the square preview the GWT client had: the thumbnail is fitted into
/// the box (CSS `max-width/max-height: 100%` on a centred image), cropped by
/// the box (`overflow: hidden`) and zoomed/shifted by the index picture's
/// transform.
Widget indexPictureTile(
  VAlbumClient client,
  String imageUrl,
  ThumbnailInfo info,
  double size, {
  Key? key,
}) =>
    SizedBox(
      key: key,
      width: size,
      height: size,
      child: ClipRect(
        child: Transform(
          alignment: Alignment.center,
          transform: thumbnailTransform(info, size),
          child: thumbnail(
            client,
            imageUrl,
            width: size,
            height: size,
            fit: BoxFit.contain,
          ),
        ),
      ),
    );

/// Displays a [ListingInfo] as a grid of folder tiles.
class ListingView extends StatelessWidget {
  final VAlbumState albumState;
  final ListingInfo listing;

  const ListingView(this.albumState, this.listing, {super.key});

  VAlbumClient get client => albumState.client;
  String get baseUrl => albumState.baseUrl;

  @override
  Widget build(BuildContext context) {
    var self = listing;
    return Scaffold(
      appBar: AppBar(
        title: Text(self.title),
        actions: <Widget>[
          // Unobtrusive while a camera-roll sync runs, nothing otherwise.
          const CameraRollIndicator(),
          IconButton(
            icon: const Icon(Icons.home),
            tooltip: 'Home',
            onPressed: albumState.showRoot,
          ),
          if (albumState.path.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.arrow_back),
              tooltip: 'Up',
              onPressed: albumState.showParent,
            ),
          menu(context, [
            menuItem(Icons.create_new_folder, 'Create album', createAlbum),
            menuItem(
              Icons.create_new_folder_outlined,
              'Create folder',
              createFolder,
            ),
            menuItem(Icons.update, "Reload", (_) => albumState.reload()),
            menuItem(Icons.settings, "Server...", openServerSettings),
          ]),
        ],
      ),
      body: Column(
        children: [
          // Says plainly when the tiles below are the copy from the cache.
          OfflineBanner(onRetry: albumState.reload),
          Expanded(
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                double imageBorder = 8;
                var preferredImageWidth = 200;
                var maxWidth = constraints.maxWidth;
                double preferredImageSpace =
                    preferredImageWidth + 2 * imageBorder;
                double imagesPerRowFrag = maxWidth / preferredImageSpace;
                var imagesPerRow = imagesPerRowFrag.round();
                bool underflow = self.folders.length < imagesPerRow;
                double difference = underflow
                    ? 0
                    : maxWidth - imagesPerRow * preferredImageSpace;
                double imageSpace =
                    preferredImageSpace + difference / imagesPerRow;

                return SingleChildScrollView(
                  scrollDirection: Axis.vertical,
                  child: buildFolderList(
                    self,
                    imageSpace - 2 * imageBorder,
                    imageBorder,
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Wrap buildFolderList(
    ListingInfo self,
    double imageWidth,
    double imageBorder,
  ) {
    return Wrap(
      children: self.folders.map((folder) {
        return Padding(
          padding: EdgeInsets.all(imageBorder),
          child: GestureDetector(
            onTap: () => albumState.showElement(folder.name),
            child: SizedBox(
              width: imageWidth,
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: buildFolderWidget(folder, imageWidth),
                  ),
                  Text(
                    folder.title,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                  ),
                  if (folder.subTitle.isNotEmpty)
                    Text(
                      folder.subTitle,
                      style: const TextStyle(fontSize: 12),
                      textAlign: TextAlign.center,
                    ),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget buildFolderWidget(FolderInfo folder, double width) {
    var indexPicture = folder.indexPicture;
    if (indexPicture == null) {
      return Container(
        width: width,
        height: width,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(5),
          border: Border.all(color: Colors.blue, width: 3),
        ),
        child: Center(
          child: Icon(Icons.folder, size: width / 2, color: Colors.blue),
        ),
      );
    }

    return indexPictureTile(
      client,
      "$baseUrl/${folder.name}/${indexPicture.image}",
      indexPicture,
      width,
    );
  }

  void createFolder(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    // `showDialog`, not `showGeneralDialog`: it brings the barrier that closes
    // the dialog on a tap beside it and on Escape. Together with the cancel
    // button of the dialog itself, the action has a way back, see issue #35.
    ListingInfo? folder = await showDialog<ListingInfo>(
      context: context,
      builder: (context) => const CreateFolderDialog(),
    );

    if (folder == null) {
      return;
    }

    await client.putResource("$baseUrl/${folder.path}", folder);

    albumState.reload();
    albumState.showElement(folder.path);
  }

  void createAlbum(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    AlbumInfo? album = await showDialog<AlbumInfo>(
      context: context,
      builder: (context) => const CreateAlbumDialog(),
    );

    if (album == null) {
      return;
    }

    await client.putResource("$baseUrl/${album.path}", album);

    albumState.reload();
    albumState.showElement(album.path);
  }
}

class CreateAlbumDialog extends StatefulWidget {
  const CreateAlbumDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateAlbumDialogState();
}

class CreateAlbumDialogState extends State<CreateAlbumDialog> {
  var formKey = GlobalKey<FormState>();
  String? albumTitle;
  String? albumSubTitle;
  DateTime? albumDate;

  @override
  Widget build(BuildContext context) {
    var now = DateTime.now();

    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ??
                    Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: const Text("Neues Album"),
                ),
              ),
              DateTimeFormField(
                mode: DateTimeFieldPickerMode.date,
                firstDate: DateTime(1900),
                lastDate: now,
                initialDate: now,
                onSaved: (value) => albumDate = value,
                dateFormat: DateFormat("yyyy-MM-dd"),
                validator: (value) {
                  return value == null ? "Muss angegeben werden." : null;
                },
                decoration: const InputDecoration(
                  label: Text("Datum"),
                  suffixIcon: Icon(Icons.date_range),
                ),
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Titel")),
                onSaved: (value) => albumTitle = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? "Darf nicht leer sein"
                      : null;
                },
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Untertitel")),
                onSaved: (value) => albumSubTitle = value,
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: cancelPressed,
                      child: const Text("Abbrechen"),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Leaves the dialog without creating anything.
  void cancelPressed() => Navigator.of(context).pop();

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var title = albumTitle!;
    var date = albumDate;

    var info = AlbumInfo(
      title: title,
      subTitle: albumSubTitle ?? "",
      path:
          (date != null ? DateFormat("yyyy-MM-dd ").format(date) : "") + title,
    );

    Navigator.of(context).pop(info);
  }
}

class CreateFolderDialog extends StatefulWidget {
  const CreateFolderDialog({super.key});

  @override
  State<StatefulWidget> createState() => CreateFolderDialogState();
}

class CreateFolderDialogState extends State<CreateFolderDialog> {
  var formKey = GlobalKey<FormState>();
  String? folderName;

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Form(
        key: formKey,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              DefaultTextStyle(
                style: DialogTheme.of(context).titleTextStyle ??
                    Theme.of(context).textTheme.titleLarge!,
                child: Semantics(
                  // For iOS platform, the focus always lands on the title.
                  // Set nameRoute to false to avoid title being announced twice.
                  namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                  container: true,
                  child: const Text("Neuer Ordner"),
                ),
              ),
              TextFormField(
                decoration: const InputDecoration(label: Text("Name")),
                onSaved: (value) => folderName = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? "Darf nicht leer sein"
                      : null;
                },
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: cancelPressed,
                      child: const Text("Abbrechen"),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Anlegen"),
                      onPressed: createPressed,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Leaves the dialog without creating anything.
  void cancelPressed() => Navigator.of(context).pop();

  void createPressed() {
    var formState = formKey.currentState;
    if (!formState!.validate()) {
      return;
    }

    formState.save();

    var name = folderName!;

    var info = ListingInfo(title: name, path: name);

    Navigator.of(context).pop(info);
  }
}
