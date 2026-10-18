/// The trash of an album, the app half of issue #152.
///
/// A photograph rated −2 ("Trash") is hidden from the album grid by its
/// rating filter, and since issue #152 the server answers it to editors alone.
/// This page is where an editor finds those photographs again: only the
/// trashed ones of this album, group members one by one, each with a
/// **Restore** tool, and — for an administrator — **Purge…**, which deletes
/// them from disk. Several are restored at once in its select mode (issue
/// #295): entered by "Select photos…" in the app bar or a long press on a
/// tile, left by its `×` or `Escape`, its bar restoring what is selected; and
/// the large picture a tap shows restores its photograph as well.
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
import 'package:flutter/services.dart';

import 'action_guard.dart';
import 'album_edit.dart' show PlaneTransform;
import 'app.dart';
import 'caller.dart';
import 'client.dart';
import 'collections.dart' show isReference;
import 'crop.dart';
import 'dialog_style.dart';
import 'kept_photos.dart';
import 'key_guard.dart';
import 'listing_view.dart' show splitPath;
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'resource.dart';
import 'select_mode.dart';
import 'selectable.dart';
import 'share_session.dart';
import 'page_insets.dart';
import 'page_app_bar.dart';

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
/// kept is in the trash all the same. A reference (issue #307) is never in
/// this album's trash: its photograph is in the trash of its own album.
List<ImagePart> trashedImages(AlbumInfo album) => [
      for (var part in album.parts)
        ...switch (part) {
          ImagePart() => [part],
          ImageGroup() => part.images,
          _ => const <ImagePart>[],
        },
    ]
        .where((image) => image.rating <= trashRating && !isReference(image))
        .toList();

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

  /// Whether the page is in its select mode (issue #295).
  bool _selecting = false;

  /// The names of the photographs selected in the select mode.
  final Set<String> _selected = {};

  /// Whether the page is in its select mode, offered to whoever may restore.
  bool get selecting => _selecting && mayRestore;

  /// The photographs selected, in the order the page shows them.
  List<ImagePart> get selected => [
        for (var image in trashed)
          if (_selected.contains(image.name)) image,
      ];

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  List<String> get path => widget.albumState.path;

  AlbumInfo get album => widget.album;

  /// Where the thumbnail of an image of this album is asked for.
  String get albumUrl => "${widget.baseUrl}/${album.path}";

  /// The photographs shown, see [trashedImages].
  List<ImagePart> get trashed => trashedImages(album);

  /// Whether the caller may restore a photograph: `edit` on this album, as
  /// [rightsOf] answers it (issue #293).
  ///
  /// The restore is the album's sidecar PUT, which the server grants to
  /// `edit` alone. It answers trashed photographs to editors alone as well
  /// (#152), so a non-editor who reaches this page by a deep link mostly sees
  /// none; but an album whose rights the server did not answer falls back on
  /// the role, and a share link never edits — the tool is not offered where
  /// it would be refused.
  bool get mayRestore => rightsOf(context, album).mayEdit;

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
    // Applied only once [writeAction] lets the write go out: offline nothing
    // changes on the screen either.
    await writeAction(
      context,
      () {
        setState(edit);
        return client.saveAlbum(path, album);
      },
      onRefused: (_) {
        if (mounted) {
          setState(undo);
        }
      },
    );
  }

  /// Gives [image] its rating back — unrated — and writes it at once.
  Future<void> restore(ImagePart image) {
    var before = image.rating;
    return writeNow(
      () => image.rating = restoredRating,
      () => image.rating = before,
    );
  }

  /// Enters the select mode (issue #295), with [first] selected where a
  /// long press on its tile opened it.
  void enterSelectMode([ImagePart? first]) {
    if (!mayRestore) {
      return;
    }
    setState(() {
      _selecting = true;
      _selected.clear();
      if (first != null) {
        _selected.add(first.name);
      }
    });
  }

  /// Leaves the select mode; the selection goes with it.
  void leaveSelectMode() => setState(() {
        _selecting = false;
        _selected.clear();
      });

  /// Adds [image] to the selection or takes it out.
  void toggle(ImagePart image) => setState(() {
        if (!_selected.remove(image.name)) {
          _selected.add(image.name);
        }
      });

  /// Gives every selected photograph its rating back — unrated — in one
  /// write (issue #295), and leaves the select mode once it is written; a
  /// refusal takes all of them back.
  Future<void> restoreSelected() async {
    var images = selected;
    if (images.isEmpty) {
      return;
    }
    var before = [for (var image in images) image.rating];
    var restored = false;
    await writeNow(
      () {
        for (var image in images) {
          image.rating = restoredRating;
        }
        restored = true;
      },
      () {
        for (var (index, image) in images.indexed) {
          image.rating = before[index];
        }
        restored = false;
      },
    );
    if (restored && mounted) {
      leaveSelectMode();
    }
  }

  /// Deletes the trashed photographs of this album from disk, after asking.
  Future<void> purge() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    // A photo other albums still show is never deleted: it moves there, and
    // the question says where (issue #307).
    var question =
        keptPurgeQuestion(_l10n, await keptOnPurge(context, client, path));
    if (!mounted) {
      return;
    }
    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("trash-purge-dialog"),
        title: Text(_l10n.trashPurgeTitle),
        content: Text(question == null
            ? _l10n.trashPurgeMessage
            : "${_l10n.trashPurgeMessage}\n\n$question"),
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
    // The server's own reason where it refuses — a refusal speaks, see issue
    // #49.
    var done = await writeAction(
      context,
      () {
        setState(() => _purging = true);
        return client.purge(path);
      },
      onRefused: (_) {
        if (mounted) {
          setState(() => _purging = false);
        }
      },
    );
    if (done == null || !mounted) {
      return;
    }
    var answer = done.value;
    setState(() => _purging = false);
    var kept = keptOf(answer);
    var purged = answer.outcomes.where((outcome) => !isKeptPhoto(outcome));
    var report = keptPurgeReport(_l10n, kept);
    messenger.showSnackBar(
      SnackBar(
        key: const Key("trash-purged"),
        content: Text(report == null
            ? _l10n.trashPurged(purged.length)
            : "${_l10n.trashPurged(purged.length)} $report"),
      ),
    );
    if (kept.isNotEmpty) {
      // The albums the photos went to are not what was loaded.
      for (var photo in kept) {
        widget.albumState.navigator.delegate.forget(splitPath(photo.album));
      }
    }
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
    showAppDialog<void>(
      context: context,
      builder: (context) => AppDialog(
        key: const Key("trash-photo"),
        child: Padding(
          padding: DialogSpacing.picture,
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
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Restored from here as from its tile (issue #295): the
                  // picture is what one looks at to decide.
                  if (mayRestore)
                    TextButton.icon(
                      key: const Key("trash-photo-restore"),
                      icon: const Icon(Icons.restore_from_trash),
                      label: Text(AppLocalizations.of(context)!.trashRestore),
                      onPressed: () {
                        Navigator.of(context).pop();
                        restore(image);
                      },
                    ),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(AppLocalizations.of(context)!.close),
                  ),
                ],
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
    // The region of a cropped photograph, which is what its rendition shows
    // (issue #212).
    var size = renditionSize(image);
    var fileWidth = size.width > 0 ? size.width : 1.0;
    var fileHeight = size.height > 0 ? size.height : 1.0;
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
    var chosen = selecting ? selected : const <ImagePart>[];
    // `Escape` leaves the select mode, as in the album (issue #209).
    return PageShortcuts(
      bindings: {
        if (selecting)
          const SingleActivator(LogicalKeyboardKey.escape): leaveSelectMode,
      },
      child: Focus(
        autofocus: true,
        child: _page(context, shown, chosen),
      ),
    );
  }

  Widget _page(
      BuildContext context, List<ImagePart> shown, List<ImagePart> chosen) {
    return Scaffold(
      appBar: selecting
          ? selectModeAppBar(
              l10n: _l10n,
              count: chosen.length,
              onLeave: leaveSelectMode,
              onDownload: null,
              offerDownload: false,
              extra: [
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: TextButton.icon(
                    key: const Key("trash-restore-selection"),
                    onPressed: chosen.isEmpty ? null : restoreSelected,
                    icon: const Icon(Icons.restore_from_trash),
                    label: Text(_l10n.trashRestoreSelection(chosen.length)),
                  ),
                ),
              ],
            )
          : PageAppBar(
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
                // Several restored at once (issue #295).
                if (mayRestore && shown.isNotEmpty)
                  IconButton(
                    key: const Key("trash-select"),
                    icon: const Icon(Icons.check_circle_outline),
                    tooltip: _l10n.selectPhotos,
                    onPressed: () => enterSelectMode(),
                  ),
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
        padding: pagePadding(context, const EdgeInsets.all(8)),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [for (var image in shown) _tile(image)],
        ),
      );

  /// One trashed photograph: its thumbnail, a tap to see it large, and the
  /// Restore tool.
  ///
  /// A gesture surface of its own, see [SelectionExcluded].
  Widget _tile(ImagePart image) => SelectionExcluded.keyed(_trashTile(image));

  Widget _trashTile(ImagePart image) {
    var thumbnail = ColoredBox(
      color: Colors.black12,
      child: orientedImageThumbnail(
        client,
        "$albumUrl${image.name}",
        image,
        width: trashTileSize,
        height: trashTileSize,
      ),
    );
    if (selecting) {
      // The select mode (issue #295): a tap, a long press and the mark all
      // toggle — there is no viewer to open here, and no range to extend.
      return SizedBox(
        key: ValueKey("trash-tile-${image.name}"),
        width: trashTileSize,
        height: trashTileSize,
        child: SelectableTile(
          selected: _selected.contains(image.name),
          onTap: () => toggle(image),
          onToggle: () => toggle(image),
          child: thumbnail,
        ),
      );
    }
    return SizedBox(
      key: ValueKey("trash-tile-${image.name}"),
      width: trashTileSize,
      height: trashTileSize,
      child: Stack(
        fit: StackFit.expand,
        children: [
          GestureDetector(
            onTap: () => showPhoto(image),
            // The way into the select mode, as on an album's tile.
            onLongPress: mayRestore ? () => enterSelectMode(image) : null,
            child: thumbnail,
          ),
          if (mayRestore)
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
}
