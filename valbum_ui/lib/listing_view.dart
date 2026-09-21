/// The folder listing view and the dialogs creating albums and folders.
library;

import 'package:date_field/date_field.dart';
import 'package:flutter/material.dart';

import 'album_date.dart';
import 'app.dart';
import 'caller.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'move_view.dart';
import 'resource.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'rights.dart';
import 'settings.dart';
import 'share_session.dart';
import 'share_view.dart';
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
///
/// The server's rendition is upright by the *file*; the rotation an author
/// stored beside the image is applied on top of it, here as everywhere else
/// (see `orientedBox`, issue #106). It is applied *before* the crop, because
/// that is the frame the crop was measured in, see
/// [ThumbnailInfo.orientation] and issue #115: the box is square, so the turn
/// costs the fitted picture nothing, and a crop without the field
/// (`Orientation.identity`, every sidecar written before it) draws exactly the
/// tree it always drew.
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
          child: orientedBox(
            info.orientation,
            thumbnail(
              client,
              imageUrl,
              width: size,
              height: size,
              fit: BoxFit.contain,
            ),
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

  /// What the caller may do with this folder, as the server answered it with
  /// the listing itself, see issue #49.
  ///
  /// Where the server answered no rights at all, the caller's *role* decides
  /// what is offered instead, and that is why this takes a context: the role
  /// is published to the tree, see [offeredRights] and issue #85.
  Rights rightsIn(BuildContext context) => offeredRights(
        Rights.of(listing),
        CallerInfo.permissionOf(context),
      );

  /// The line saying that this folder belongs to somebody else, `null` while
  /// the caller is its owner.
  String? sharedLineIn(BuildContext context) => sharingNotice(
        AppLocalizations.of(context)!,
        albumState.path,
        rightsIn(context),
      );

  /// Whether the caller may hand out a link to the folder at [path].
  ///
  /// No request of its own since issue #83: the rights this listing already
  /// carries and the caller's own permission answer it, see [mayShareFolder].
  ///
  /// Never inside a share link: a link caller carries a token and may hold
  /// every right the link gives, but manages nothing, see issue #51.
  bool mayShare(BuildContext context, List<String> path) =>
      ShareSession.of(context) == null &&
      mayShareFolder(
        client,
        path,
        rightsIn(context),
        // A caller who may hand out no links is offered none; a caller nobody
        // named is offered them as before, see issue #85.
        mayShare: CallerInfo.permissionOf(context).mayShare ||
            !CallerInfo.permissionOf(context).named,
      );

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var self = listing;
    // Inside a share link nothing that changes anything is offered, and
    // neither is the way to the settings. The folder is named by the folder,
    // not by the link (issue #97): the link's label names the link where links
    // are managed, and over the folder's own title it only said the same thing
    // twice.
    var link = ShareSession.of(context);
    var sharedLine = sharedLineIn(context);
    var mayChange = rightsIn(context).mayEdit && link == null;
    return Scaffold(
      // Black like the album pages, so that the way down does not flash from
      // a light page to a dark one, see issue #40.
      backgroundColor: Colors.black,
      appBar: AppBar(
        // The way back is the leading control at the left on every page of
        // the app — the album, the viewer and this listing (issue #100).
        // Nothing to go up to at the root, see issue #40.
        leading: albumState.path.isEmpty
            ? null
            : IconButton(
                icon: const Icon(Icons.arrow_back),
                tooltip: l10n.up,
                onPressed: albumState.showParent,
              ),
        automaticallyImplyLeading: false,
        title: Text(self.title),
        actions: <Widget>[
          // Unobtrusive while a camera-roll sync runs, nothing otherwise.
          const CameraRollIndicator(),
          // No home on the home screen, see issue #40.
          if (albumState.path.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.home),
              tooltip: l10n.home,
              onPressed: albumState.showRoot,
            ),
          // The three-dots menu is the last control at the right, here as on
          // every other page (issue #100).
          menu(context, [
            // Whose folder this is and what may be done with it, where that
            // is not simply "mine", see issue #49.
            if (sharedLine != null) ...[
              PopupMenuItem<void Function(BuildContext)>(
                enabled: false,
                child: Text(sharedLine, key: const Key("shared-line")),
              ),
              const PopupMenuDivider(),
            ],
            // Only with `edit`: what the caller may not do is not offered,
            // never offered and then refused, see issue #49.
            if (mayChange)
              menuItem(Icons.create_new_folder, l10n.createAlbum, createAlbum),
            if (mayChange)
              menuItem(
                Icons.create_new_folder_outlined,
                l10n.createFolder,
                createFolder,
              ),
            if (mayChange)
              menuItem(Icons.tune, l10n.folderProperties, editFolder),
            // Only where there is a rule to apply: a folder without one has
            // nothing to file, see issue #48.
            if (mayChange && self.placement != Placement.none)
              menuItem(Icons.auto_awesome_motion, l10n.applyRule, applyRule),
            if (mayShare(context, albumState.path))
              menuItem(
                Icons.link,
                l10n.shareLinkAction,
                (context) =>
                    shareFolderLink(context, albumState.path, self.title),
              ),
            menuItem(Icons.update, l10n.reload, (_) => albumState.reload()),
            // A visitor of a link has no server of their own to configure.
            if (link == null)
              menuItem(Icons.settings, l10n.serverMenuEntry, openServerSettings),
          ]),
        ],
      ),
      body: Column(
        children: [
          // Says plainly when the tiles below are the copy from the cache.
          OfflineBanner(onRetry: albumState.reload),
          // An empty folder says what it is, rather than showing a black
          // page with nothing but the app bar on it, see issue #56.
          if (self.folders.isEmpty)
            Expanded(child: emptyNotice(context, link != null, mayChange))
          else
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
                    // The last row of tiles ends above the system navigation
                    // bar instead of running under it, see issue #60.
                    padding: EdgeInsets.only(
                      bottom: MediaQuery.paddingOf(context).bottom,
                    ),
                    child: buildFolderList(
                      context,
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

  /// What an empty folder says instead of showing nothing (issue #56).
  ///
  /// A listing with no tiles used to be a black page with an app bar on it,
  /// which says neither that the folder is empty nor that anything went wrong
  /// — and the person it hits hardest is a guest who has just joined, whose
  /// library is *correctly* empty until somebody shares an album with them.
  ///
  /// Two sentences, because there are two empty folders: the root of a
  /// library (no albums yet, and how to make one where the caller may) and
  /// any other folder. Inside a share link the shared folder is the root, but
  /// it is somebody else's folder, so it reads as a folder.
  Widget emptyNotice(BuildContext context, bool inLink, bool mayChange) {
    var l10n = AppLocalizations.of(context)!;
    var atRoot = albumState.path.isEmpty && !inLink;
    String sentence;
    if (atRoot) {
      sentence = mayChange
          ? "${l10n.libraryEmptyNotice} ${l10n.libraryEmptyHint}"
          : l10n.libraryEmptyNotice;
    } else {
      sentence = l10n.folderEmptyNotice;
    }
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Text(
          sentence,
          key: const Key("listing-empty"),
          textAlign: TextAlign.center,
          style: const TextStyle(color: Colors.white70, fontSize: 16),
        ),
      ),
    );
  }

  Wrap buildFolderList(
    BuildContext context,
    ListingInfo self,
    double imageWidth,
    double imageBorder,
  ) {
    return Wrap(
      // The newest first, the undated behind them by name -- the order the
      // server sends a listing in since issue #48, applied here as well, so
      // that an older server and the offline cache read the same way.
      children: sortedFolders(self.folders).map((folder) {
        return Padding(
          padding: EdgeInsets.all(imageBorder),
          child: GestureDetector(
            onTap: () => albumState.showElement(folder.name),
            // A listing tile had no menu of its own; the long press is the
            // touch idiom the album already uses to reach a tile's tools, and
            // this menu holds only what a tile can do, see issue #47.
            onLongPressStart: (details) =>
                showTileMenu(context, folder, details.globalPosition),
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
                    style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  // When the album is, between its title and its subtitle --
                  // the date the server derived, never one read off the
                  // folder name here, and no line at all where nothing says
                  // when the album happened, see issue #107.
                  //
                  // Only an album has a date: a folder of folders carries an
                  // `effectiveDate` too, but that is the key its listing is
                  // sorted by -- a folder named `2026` sorts with the year it
                  // names -- and showing it read "2026 - Jan 1 2026", see
                  // issue #133.
                  if (folderHasDate(folder))
                    Text(
                      albumDateLabel(folder.effectiveDate)!,
                      key: const Key("folder-date"),
                      style: const TextStyle(
                        fontSize: 12,
                        color: Colors.white60,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  if (folder.subTitle.isNotEmpty)
                    Text(
                      folder.subTitle,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Colors.white70,
                      ),
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

  /// The picture of one tile.
  ///
  /// A `link` field on a folder is what issue #50 made and issue #85 retired:
  /// a space is addressed by its own URL now, and nothing of somebody else's
  /// hangs in this tree. A listing that still carries one — an old server —
  /// shows the folder plainly, and never an error.
  Widget buildFolderWidget(FolderInfo folder, double width) =>
      buildFolderPicture(folder, width);

  Widget buildFolderPicture(FolderInfo folder, double width) {
    var indexPicture = folder.indexPicture;
    if (indexPicture == null) {
      // An inbox says what it is (issues #131, #136): it is undated and it
      // stands first, and the icon is what makes that read as "this wants
      // doing" rather than as a folder that lost its date.
      var inbox = folderIsInbox(folder);
      return Container(
        width: width,
        height: width,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(5),
          border: Border.all(color: Colors.blue, width: 3),
        ),
        child: Center(
          child: Icon(
            inbox ? Icons.inbox : Icons.folder,
            key: inbox ? const Key("inbox-icon") : null,
            size: width / 2,
            color: Colors.blue,
          ),
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

  /// The menu of one folder tile, at the point it was pressed.
  Future<void> showTileMenu(
    BuildContext context,
    FolderInfo folder,
    Offset position,
  ) async {
    var l10n = AppLocalizations.of(context)!;
    var overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
    var childPath = [...albumState.path, folder.name];
    // Moving an entry out of this folder is an edit *of this folder*; sharing
    // the entry is a question about the entry itself, so the two are asked
    var link = ShareSession.of(context);
    var mayMove = rightsIn(context).mayEdit && link == null;
    // An inbox is never handed out: the server refuses `?action=share` on one
    // with `INBOX_NOT_SHARED`, so the entry is not offered, see issue #135.
    var mayShareChild = mayShare(context, childPath) && !folderIsInbox(folder);
    if (!context.mounted || (!mayMove && !mayShareChild)) {
      // Nothing this caller may do here: no menu rather than an empty one.
      return;
    }
    var chosen = await showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(
        position.dx,
        position.dy,
        overlay.size.width - position.dx,
        overlay.size.height - position.dy,
      ),
      items: [
        if (mayMove)
          PopupMenuItem<String>(
            value: "move",
            child: ListTile(
              leading: const Icon(Icons.drive_file_move),
              title: Text(l10n.moveToAction),
            ),
          ),
        if (mayShareChild)
          PopupMenuItem<String>(
            value: "share-link",
            child: ListTile(
              leading: const Icon(Icons.link),
              title: Text(l10n.shareLinkAction),
            ),
          ),
        // Which entry stands for this folder in the listing above it, see
        // issue #110: the choice is a field of *this* folder's sidecar, so it
        // is an edit of this folder exactly as the move and the delete are.
        // It is offered on every entry, an album and a folder alike — the
        // server resolves a folder's own picture through the sidecars.
        if (mayMove)
          PopupMenuItem<String>(
            key: const Key("use-as-folder-picture"),
            value: "folder-picture",
            child: ListTile(
              leading: const Icon(Icons.photo_size_select_large),
              title: Text(l10n.useAsFolderPicture),
            ),
          ),
        // Only where there is one to take away: a folder without a choice
        // shows the folder icon already, see issue #110.
        if (mayMove && listing.index.isNotEmpty)
          PopupMenuItem<String>(
            key: const Key("clear-folder-picture"),
            value: "no-folder-picture",
            child: ListTile(
              leading: const Icon(Icons.hide_image_outlined),
              title: Text(l10n.useNoFolderPicture),
            ),
          ),
        // Deleting an entry is an edit of *this* folder, exactly as moving one
        // out of it is, and it is offered under the same condition (#109).
        if (mayMove)
          PopupMenuItem<String>(
            key: const Key("delete-entry"),
            value: "delete",
            child: ListTile(
              leading: const Icon(Icons.delete_outline),
              title: Text(l10n.deleteEllipsis),
            ),
          ),
      ],
    );
    if (chosen == null || !context.mounted) {
      return;
    }
    if (chosen == "share-link") {
      await shareFolderLink(context, childPath, folder.title);
      return;
    }
    if (chosen == "folder-picture") {
      await setFolderPicture(context, folder.name);
      return;
    }
    if (chosen == "no-folder-picture") {
      await setFolderPicture(context, "");
      return;
    }
    if (chosen == "delete") {
      await deleteWithConfirmation(
        context: context,
        client: client,
        parent: albumState.path,
        names: [folder.name],
        what: "'${folder.name}'",
        onDeleted: albumState.reload,
      );
      return;
    }
    await moveWithPicker(
      context: context,
      client: client,
      source: albumState.path,
      names: [folder.name],
      subject: EntrySubject(folder.name),
      delegate: albumState.navigator.delegate,
      onMoved: albumState.reload,
    );
  }

  /// Makes [name] the entry whose picture stands for this folder, the empty
  /// name taking the choice away, see issue #110.
  ///
  /// The choice is a field of *this* folder's sidecar ([ListingInfo.index]),
  /// written by the ordinary listing PUT — what was loaded, with the one
  /// field changed; the server drops the derived `folders` before it writes.
  /// Nothing is resolved here: the server answers the *parent's*
  /// [FolderInfo.indexPicture] with the path from the chosen child down to
  /// the photograph, so both this listing and the one above it are asked
  /// again — the tile of this folder up there is what has just changed
  /// (issue #134).
  Future<void> setFolderPicture(BuildContext context, String name) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var stored = ListingInfo(
      path: listing.path,
      title: listing.title,
      placement: listing.placement,
      index: name,
      folders: listing.folders,
    );
    try {
      await client.saveListing(albumState.path, stored);
    } catch (error) {
      // The server's own reason, as every other refused write shows it.
      showRefusal(messenger, error);
      return;
    }
    var self = albumState.path;
    if (self.isNotEmpty) {
      albumState.navigator.delegate.forget(self.sublist(0, self.length - 1));
    }
    albumState.reload();
  }

  /// Opens the share-link dialog on the folder at [path], see issue #51.
  Future<void> shareFolderLink(
    BuildContext context,
    List<String> path,
    String title,
  ) =>
      shareLinksOf(
        context: context,
        client: client,
        path: path,
        label: title.isEmpty ? null : "'$title'",
      );

  void createFolder(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
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

    try {
      await client.putResource("$baseUrl/${folder.path}", folder);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    albumState.reload();
    albumState.showElement(folder.path);
  }

  void createAlbum(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    AlbumInfo? album = await showDialog<AlbumInfo>(
      context: context,
      builder: (context) => const CreateAlbumDialog(),
    );

    if (album == null) {
      return;
    }

    CreateResult result;
    try {
      result = await client.createAlbum(albumState.path, album);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    // The whole tree below this folder, not this folder alone: a placement
    // rule files the new album into a year folder *inside* it, and a year
    // folder visited earlier in the session still holds its old listing, see
    // issue #134.
    albumState.navigator.delegate.forgetTree(albumState.path);
    albumState.reload();
    // Where the album landed, not where it was asked for: a placement rule on
    // this folder files it into its year folder, see issue #48. The path the
    // server answers is relative to the root of the caller's space.
    albumState.showPath(splitPath(result.path));

    if (result.message.isNotEmpty) {
      // Nothing happens silently: an album that was filed away says so.
      messenger.showSnackBar(
        SnackBar(
          content: Text(result.message),
          duration: const Duration(seconds: 6),
        ),
      );
    }
  }

  /// Edits the title and the placement rule of the folder being shown.
  ///
  /// The rule is typically set on the root of a space, which is a folder like
  /// any other, so this is offered there as well.
  void editFolder(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var edited = await showDialog<FolderProperties>(
      context: context,
      builder: (context) => FolderPropertiesDialog(
        FolderProperties(title: listing.title, placement: listing.placement),
      ),
    );

    if (edited == null) {
      return;
    }

    // What was loaded, with what was edited changed: the server drops the
    // derived dates of the children before it writes, see issue #48.
    var stored = ListingInfo(
      path: listing.path,
      title: edited.title,
      placement: edited.placement,
      // Carried along untouched: the folder's own picture is chosen on a tile
      // (issue #110), and writing the title must not take it away.
      index: listing.index,
      folders: listing.folders,
    );

    CreateResult written;
    try {
      written = await client.saveListing(albumState.path, stored);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    if (written.message.isEmpty) {
      albumState.reload();
      return;
    }
    // A changed title renames the folder, so the address on the screen has
    // just become a 404: the server says where the folder is now, and the app
    // goes there and says the new name, see issue #130. What the session holds
    // under the old address -- this folder and everything below it -- is gone
    // with the rename, and the listing above shows the old name (issue #134).
    var self = albumState.path;
    var delegate = albumState.navigator.delegate;
    delegate.forgetTree(self);
    if (self.isNotEmpty) {
      delegate.forget(self.sublist(0, self.length - 1));
    }
    albumState.showPath(splitPath(written.path));
    messenger.showSnackBar(
      SnackBar(
        content: Text(written.message),
        duration: const Duration(seconds: 6),
      ),
    );
  }

  /// Applies the placement rule of this folder to what is already in it.
  void applyRule(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var l10n = AppLocalizations.of(context)!;
    var messenger = ScaffoldMessenger.of(context);

    MoveResult result;
    try {
      result = await client.place(albumState.path);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    // What was filed is no longer where it was: the listing on the screen has
    // to be fetched again before the outcome is read out -- and so has every
    // year folder below it, which is where the albums went (issue #134).
    albumState.navigator.delegate.forgetTree(albumState.path);
    albumState.reload();

    var filed = result.outcomes.length - refusedOutcomes(result).length;
    if (!context.mounted) {
      return;
    }
    await reportOutcomes(
      context: context,
      messenger: messenger,
      title: l10n.applyRule,
      summary: filed == 0 ? l10n.nothingToFile : l10n.filedAlbums(filed),
      result: result,
    );
  }
}

/// The segments of a space-relative resource path, the empty path none.
List<String> splitPath(String path) => [
      for (var segment in path.split("/"))
        if (segment.isNotEmpty) segment
    ];

/// The values edited by the [FolderPropertiesDialog].
class FolderProperties {
  final String title;
  final Placement placement;

  const FolderProperties({required this.title, required this.placement});
}

/// Edits the title of a folder and the rule by which it files what lands in
/// it, see issue #48.
class FolderPropertiesDialog extends StatefulWidget {
  final FolderProperties properties;

  const FolderPropertiesDialog(this.properties, {super.key});

  @override
  State<StatefulWidget> createState() => FolderPropertiesDialogState();
}

class FolderPropertiesDialogState extends State<FolderPropertiesDialog> {
  late final TextEditingController titleController =
      TextEditingController(text: widget.properties.title);

  late Placement placement = widget.properties.placement;

  /// How the three rules are named, in the order they are offered.
  static Map<Placement, String> placementLabels(AppLocalizations l10n) => {
        Placement.none: l10n.placementNone,
        Placement.byYear: l10n.placementByYear,
        Placement.byYearMonth: l10n.placementByYearMonth,
      };

  @override
  void dispose() {
    titleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Dialog(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                DefaultTextStyle(
                  style: DialogTheme.of(context).titleTextStyle ??
                      Theme.of(context).textTheme.titleLarge!,
                  child: Semantics(
                    namesRoute:
                        Theme.of(context).platform != TargetPlatform.iOS,
                    container: true,
                    child: Text(l10n.folderProperties),
                  ),
                ),
                TextField(
                  controller: titleController,
                  autofocus: true,
                  decoration: InputDecoration(label: Text(l10n.titleLabel)),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Text(
                    l10n.placementHeading,
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                ),
                // What the rule does, plainly: it places what arrives, it
                // does not tidy up behind itself.
                Padding(
                  padding: const EdgeInsets.only(top: 4, bottom: 4),
                  child: Text(
                    l10n.placementExplanation,
                    key: const Key("placement-explanation"),
                  ),
                ),
                RadioGroup<Placement>(
                  groupValue: placement,
                  onChanged: (value) => setState(
                    () => placement = value ?? Placement.none,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (var entry in placementLabels(l10n).entries)
                        RadioListTile<Placement>(
                          key: Key("placement-${entry.key.name}"),
                          contentPadding: EdgeInsets.zero,
                          title: Text(entry.value),
                          value: entry.key,
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: Text(l10n.cancel),
                      ),
                      const SizedBox(width: 8),
                      ElevatedButton.icon(
                        icon: const Icon(Icons.check),
                        label: Text(l10n.apply),
                        onPressed: () => Navigator.of(context).pop(
                          FolderProperties(
                            title: titleController.text,
                            placement: placement,
                          ),
                        ),
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
}


class CreateAlbumDialog extends StatefulWidget {
  /// The day the album is proposed with, `null` for none — the field is then
  /// empty, and it may stay empty: an album without a date is left where it
  /// is made, see issue #119.
  ///
  /// The move dialog fills it with the day the selected photos were taken on,
  /// see issue #114 and `moveWithPicker`.
  final DateTime? initialDate;

  const CreateAlbumDialog({super.key, this.initialDate});

  @override
  State<StatefulWidget> createState() => CreateAlbumDialogState();
}

class CreateAlbumDialogState extends State<CreateAlbumDialog> {
  var formKey = GlobalKey<FormState>();
  String? albumTitle;
  String? albumSubTitle;
  DateTime? albumDate;

  /// Whether the folder being made is an inbox, see issue #136.
  ///
  /// An inbox has no date — the server derives none for one and files it
  /// nowhere — so the date field goes while the box is ticked, and the folder
  /// is named by its title alone.
  bool inbox = false;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
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
                  child: Text(l10n.newAlbumTitle),
                ),
              ),
              // What is being made, before anything is typed: an inbox has no
              // date, so the choice stands above the field it takes away.
              CheckboxListTile(
                key: const Key("create-kind-inbox"),
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                value: inbox,
                title: Text(l10n.createInboxLabel),
                subtitle: Text(
                  l10n.createInboxHint,
                  key: const Key("create-kind-inbox-hint"),
                  style: const TextStyle(fontSize: 12),
                ),
                onChanged: (value) => setState(() => inbox = value == true),
              ),
              if (!inbox)
                DateTimeFormField(
                  mode: DateTimeFieldPickerMode.date,
                  firstDate: DateTime(1900),
                  lastDate: now,
                  // The day proposed stands in the field, so that it is the
                  // album's date without anything further being done — and the
                  // calendar opens on it when it is changed, see issue #114.
                  initialValue: widget.initialDate,
                  initialPickerDateTime: widget.initialDate ?? now,
                  // The field is cleared by the dialog, not by an icon of the
                  // package's own: `date_field` 7 would otherwise replace the
                  // calendar icon below with a cross as soon as a day stands in
                  // the field (issue #108 upgraded the package for `intl`).
                  canClear: false,
                  onSaved: (value) => albumDate = value,
                  dateFormat: folderDateFormat,
                  // No validator: an album without a date is one the server
                  // leaves in the folder it was made in, which is how an
                  // `Inbox` is made by hand, see issue #119.
                  decoration: InputDecoration(
                    label: Text(l10n.dateLabel),
                    suffixIcon: const Icon(Icons.date_range),
                  ),
                ),
              if (!inbox)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    l10n.createAlbumUndatedHint,
                    key: const Key("create-album-date-hint"),
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
              TextFormField(
                decoration: InputDecoration(label: Text(l10n.titleLabel)),
                onSaved: (value) => albumTitle = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? l10n.mustNotBeEmpty
                      : null;
                },
              ),
              TextFormField(
                decoration: InputDecoration(label: Text(l10n.subtitleLabel)),
                onSaved: (value) => albumSubTitle = value,
              ),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: cancelPressed,
                      child: Text(l10n.cancel),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: Text(l10n.create),
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
    // An inbox has no date, whatever the field held before the box was
    // ticked: the server derives none for one, see issue #136.
    var date = inbox ? null : albumDate;

    var info = AlbumInfo(
      title: title,
      subTitle: albumSubTitle ?? "",
      // The explicit date, at local midnight of the day that was picked: the
      // server files the new album by it, see issue #48.
      date: date == null
          ? 0
          : DateTime(date.year, date.month, date.day).millisecondsSinceEpoch,
      // `yyyy-MM-dd title`, the title alone without a date -- the one
      // composition, shared with the move picker, see [albumFolderName].
      path: albumFolderName(date, title),
      kind: inbox ? AlbumKind.inbox : AlbumKind.album,
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
    var l10n = AppLocalizations.of(context)!;
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
                  child: Text(l10n.newFolderTitle),
                ),
              ),
              TextFormField(
                decoration: InputDecoration(label: Text(l10n.nameLabel)),
                onSaved: (value) => folderName = value,
                validator: (String? value) {
                  return value == null || value.isEmpty
                      ? l10n.mustNotBeEmpty
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
                      child: Text(l10n.cancel),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: Text(l10n.create),
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
