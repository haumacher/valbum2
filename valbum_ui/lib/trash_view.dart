/// The trash of an album, the app half of issue #152.
///
/// A photograph rated −2 ("Trash") is hidden from the album grid by its
/// rating filter, and since issue #152 the server answers it to editors alone.
/// This page is where an editor finds those photographs again: only the
/// trashed ones of this album, group members one by one, each with a
/// **Restore** tool, and — for an administrator — **Purge…**, which deletes
/// them from disk.
///
/// ## A level of its own
///
/// Like the face editor of issue #126 it is a page level of issue #93
/// (`<album>/trash`, see [TrashRoute]), not a dialog: the album stays mounted
/// beneath it and the way back is the app bar's `leading` (issue #100).
///
/// ## Nothing buffered
///
/// Every action is written at once — the doctrine of the inbox (#136): refuse
/// offline, apply, save, and on a refusal take the change back and say the
/// server's own reason. There is no Save, no Cancel and therefore no leave
/// guard.
library;

import 'dart:math';

import 'package:flutter/material.dart' hide Orientation;

import 'album_edit.dart' show PlaneTransform;
import 'app.dart';
import 'caller.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'move_view.dart' show showRefusal;
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'resource.dart';
import 'share_session.dart';

/// The rating of a photograph in the trash, the lowest of the scale.
const int trashRating = -2;

/// The rating a restored photograph gets: unrated.
const int restoredRating = 0;

/// The side of a tile of the trash page, in logical pixels.
const double trashTileSize = 160;

/// The photographs of [album] rated as trash, group members one by one, in
/// the order the album holds them.
///
/// A group is not judged by its representative here: the rating belongs to
/// one photograph, and a trashed member of a group whose representative is
/// kept is in the trash all the same.
List<ImagePart> trashedImages(AlbumInfo album) => [
      for (var part in album.parts)
        ...switch (part) {
          ImagePart() => [part],
          ImageGroup() => part.images,
          _ => const <ImagePart>[],
        },
    ].where((image) => image.rating <= trashRating).toList();

/// Whether [album] holds a photograph rated as trash, see [trashedImages].
bool hasTrashedImages(AlbumInfo album) => trashedImages(album).isNotEmpty;

/// The trash page of one album.
class TrashContent extends StatefulWidget {
  /// The page this screen stands in, see [VAlbumState].
  final VAlbumState albumState;

  /// The album as the server answered it.
  final AlbumInfo album;

  /// The URL of the album's folder, which an image URL is built from.
  final String baseUrl;

  const TrashContent(this.albumState, this.album, this.baseUrl, {super.key});

  @override
  State<StatefulWidget> createState() => TrashContentState();
}

class TrashContentState extends State<TrashContent> {
  /// Whether a purge is on its way to the server.
  bool _purging = false;

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  List<String> get path => widget.albumState.path;

  AlbumInfo get album => widget.album;

  /// Where the thumbnail of an image of this album is asked for.
  String get albumUrl => "${widget.baseUrl}/${album.path}";

  /// The photographs shown, see [trashedImages].
  List<ImagePart> get trashed => trashedImages(album);

  /// Whether the caller may purge: an administrator, outside a share link.
  ///
  /// The server decides (issue #152: purging deletes from disk and is the
  /// administrator's act alone); the button a caller may not use is simply
  /// not offered, the way "Refresh previews" is not (#98).
  bool get mayPurge =>
      ShareSession.of(context) == null &&
      CallerInfo.permissionOf(context).role == roleAdmin;

  /// Applies [edit], writes the album and takes [edit] back through [undo]
  /// where the server refuses — the inbox's `writeNow` (#136).
  Future<void> writeNow(VoidCallback edit, VoidCallback undo) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    setState(edit);
    try {
      await client.saveAlbum(path, album);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(undo);
      showRefusal(messenger, error);
    }
  }

  /// Gives [image] its rating back — unrated — and writes it at once.
  Future<void> restore(ImagePart image) {
    var before = image.rating;
    return writeNow(
      () => image.rating = restoredRating,
      () => image.rating = before,
    );
  }

  /// Deletes the trashed photographs of this album from disk, after asking.
  Future<void> purge() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("trash-purge-dialog"),
        title: Text(_l10n.trashPurgeTitle),
        content: Text(_l10n.trashPurgeMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("trash-purge-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.trashPurgeConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    var messenger = ScaffoldMessenger.of(context);
    setState(() => _purging = true);
    MoveResult answer;
    try {
      answer = await client.purge(path);
    } catch (error) {
      if (mounted) {
        setState(() => _purging = false);
        // The server's own reason — a refusal speaks, see issue #49.
        showRefusal(messenger, error);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _purging = false);
    messenger.showSnackBar(
      SnackBar(
        key: const Key("trash-purged"),
        content: Text(_l10n.trashPurged(answer.outcomes.length)),
      ),
    );
    // What is gone from the disk is gone from the album: it is fetched anew,
    // and this page and the album beneath it show what the server has.
    widget.albumState.navigator.delegate.forget(path);
    widget.albumState.reload();
  }

  /// Shows [image] as large as the screen allows, in a dialog.
  ///
  /// A dialog and not the viewer route: the viewer is a page level of the
  /// *album*, so opening it would take this page off the stack.
  void showPhoto(ImagePart image) {
    showDialog<void>(
      context: context,
      builder: (context) => Dialog(
        key: const Key("trash-photo"),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(child: largePhoto(context, image)),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  image.name,
                  key: const Key("trash-photo-name"),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(AppLocalizations.of(context)!.close),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The picture of [image] fitted into 90 % of the screen, turned by its
  /// stored orientation like every rendition of the app.
  Widget largePhoto(BuildContext context, ImagePart image) {
    var screen = MediaQuery.sizeOf(context);
    var availableWidth = max(screen.width * 0.9 - 48, 120.0);
    var availableHeight = max(screen.height * 0.9 - 140, 120.0);
    var fileWidth = image.width > 0 ? image.width.toDouble() : 1.0;
    var fileHeight = image.height > 0 ? image.height.toDouble() : 1.0;
    var transform = PlaneTransform.of(image.orientation);
    var turnedWidth = transform.swapsDimensions ? fileHeight : fileWidth;
    var turnedHeight = transform.swapsDimensions ? fileWidth : fileHeight;
    var scale =
        min(availableWidth / turnedWidth, availableHeight / turnedHeight);
    return orientedImageThumbnail(
      client,
      "$albumUrl${image.name}",
      image,
      key: const Key("trash-photo-picture"),
      width: turnedWidth * scale,
      height: turnedHeight * scale,
    );
  }

  @override
  Widget build(BuildContext context) {
    var shown = trashed;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          key: const Key("trash-up"),
          icon: const Icon(Icons.arrow_back),
          tooltip: _l10n.up,
          onPressed: widget.albumState.navigator.up,
        ),
        automaticallyImplyLeading: false,
        title: Column(
          children: [
            Text(_l10n.trashPageTitle),
            Text(
              album.title,
              key: const Key("trash-subtitle"),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
        centerTitle: true,
        actions: [
          if (mayPurge && shown.isNotEmpty)
            TextButton.icon(
              key: const Key("trash-purge"),
              onPressed: _purging ? null : purge,
              icon: const Icon(Icons.delete_forever),
              label: Text(_l10n.trashPurgeAction),
            ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OfflineBanner(onRetry: widget.albumState.reload),
          Expanded(
            child: shown.isEmpty ? _empty() : _grid(shown),
          ),
        ],
      ),
    );
  }

  /// What the page says where nothing is in the trash (any more).
  Widget _empty() => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _l10n.trashEmptyNotice,
                key: const Key("trash-empty"),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              TextButton(
                key: const Key("trash-back"),
                onPressed: widget.albumState.navigator.up,
                child: Text(_l10n.trashBackToAlbum),
              ),
            ],
          ),
        ),
      );

  Widget _grid(List<ImagePart> shown) => SingleChildScrollView(
        padding: const EdgeInsets.all(8),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [for (var image in shown) _tile(image)],
        ),
      );

  /// One trashed photograph: its thumbnail, a tap to see it large, and the
  /// Restore tool.
  Widget _tile(ImagePart image) => SizedBox(
        key: ValueKey("trash-tile-${image.name}"),
        width: trashTileSize,
        height: trashTileSize,
        child: Stack(
          fit: StackFit.expand,
          children: [
            GestureDetector(
              onTap: () => showPhoto(image),
              child: ColoredBox(
                color: Colors.black12,
                child: orientedImageThumbnail(
                  client,
                  "$albumUrl${image.name}",
                  image,
                  width: trashTileSize,
                  height: trashTileSize,
                ),
              ),
            ),
            Positioned(
              right: 4,
              bottom: 4,
              child: Material(
                color: Colors.black54,
                shape: const CircleBorder(),
                child: IconButton(
                  key: const Key("trash-restore"),
                  icon: const Icon(Icons.restore_from_trash),
                  color: Colors.white,
                  tooltip: _l10n.trashRestore,
                  onPressed: () => restore(image),
                ),
              ),
            ),
          ],
        ),
      );
}
