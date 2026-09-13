/// The folder listing view and the dialogs creating albums and folders.
library;

import 'package:date_field/date_field.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'album_date.dart';
import 'app.dart';
import 'caller.dart';
import 'camera_roll_view.dart';
import 'client.dart';
import 'links.dart';
import 'move_view.dart';
import 'resource.dart';
import 'offline.dart';
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

/// What a guest's own, empty root says (issue #56).
///
/// A guest has no albums of their own — their library is what others share
/// with them — so an empty root is not a mistake, and the screen says so
/// rather than showing a black page with a name on it.
const String guestRootEmptyNotice =
    "Nothing has been shared with you yet. Albums others share with you "
    "appear here.";

/// What an empty library says, see [guestRootEmptyNotice].
const String libraryEmptyNotice = "There are no albums here yet.";

/// How an empty library is filled, said where the caller may fill it.
const String libraryEmptyHint =
    "Create the first one from the menu at the top right.";

/// What an empty folder below the root says.
const String folderEmptyNotice = "This folder has no albums yet.";

/// Displays a [ListingInfo] as a grid of folder tiles.
class ListingView extends StatelessWidget {
  final VAlbumState albumState;
  final ListingInfo listing;

  const ListingView(this.albumState, this.listing, {super.key});

  VAlbumClient get client => albumState.client;
  String get baseUrl => albumState.baseUrl;

  /// What the caller may do with this folder, as the server answered it with
  /// the listing itself, see issue #49.
  Rights get rights => Rights.of(listing);

  /// The line saying that this folder belongs to somebody else, `null` while
  /// the caller is its owner.
  String? get sharedLine => sharingNotice(albumState.path, rights);

  /// Whether this folder is a guest's own root, where nothing may be created.
  ///
  /// A guest's library is what others share with them: the server hands them
  /// every right there — they rearrange and decline their links — and refuses
  /// by *role* the one thing that folder is not for, an album, a photo or a
  /// folder of their own (`AuthService.GUEST_SPACE_REFUSED`, issue #52). What
  /// is refused is not offered, so the creating entries go.
  ///
  /// "Their own" is the very question [sharingNotice] already answers: a path
  /// that names nobody else's space and rights that are complete. A guest who
  /// was granted `edit` on somebody's folder *through a link* reads the same
  /// way and loses the creating entries there too — the answer carries the
  /// rights but not whose space they are in, and a guest who edits another
  /// member's folder is rare enough to pay that price rather than offer
  /// something the server refuses at the root, which is the common case.
  bool guestRoot(BuildContext context) =>
      CallerInfo.isGuestCaller(context) && sharedLine == null;

  /// Whether the caller manages the grants of the folder at [path], asked once
  /// and remembered by the router.
  ///
  /// Asked only where the answer can be "yes" at all, see
  /// [couldManageGrants]: the rights the listing already carries decide
  /// whether the server is troubled with the question.
  ///
  /// Never inside a share link: a link caller carries a token and may hold
  /// every right the link gives, but manages nothing, see issue #51.
  /// Never in a guest's own root either: there the answer is known without
  /// asking, see [guestRoot] and [couldManageGrants].
  Future<bool> mayShare(BuildContext context, List<String> path) =>
      ShareSession.of(context) == null &&
              couldManageGrants(
                client,
                albumState.path,
                rights,
                isGuest: CallerInfo.isGuestCaller(context),
              )
          ? albumState.navigator.delegate.mayManageGrants(path)
          : _no;

  /// The answer of a question that was not asked; one instance, so that the
  /// [FutureBuilder] of the menu is handed the same future on every rebuild.
  ///
  /// A [SynchronousFuture], not a `Future.value`: this one instance outlives
  /// every view that reads it, and a plain future hands its callbacks to the
  /// [Zone] it was *created* in — which in a test is the zone of whichever
  /// test happened to build the first listing, so a later `await` of it would
  /// never complete. A synchronous future calls back in the zone that asks.
  static final Future<bool> _no = SynchronousFuture(false);

  @override
  Widget build(BuildContext context) {
    var self = listing;
    // Inside a share link the folder is named by the link: a visitor arriving
    // at a bare URL is told what they were given, see issue #51. Nothing that
    // changes anything is offered, and neither is the way to the settings.
    var link = ShareSession.of(context);
    var mayChange = rights.mayEdit && link == null && !guestRoot(context);
    return Scaffold(
      // Black like the album pages, so that the way down does not flash from
      // a light page to a dark one, see issue #40.
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: link == null
            ? Text(self.title)
            : Column(
                children: [
                  Text(link.label, key: const Key("share-label")),
                  if (self.title.isNotEmpty) Text(self.title),
                ],
              ),
        actions: <Widget>[
          // Unobtrusive while a camera-roll sync runs, nothing otherwise.
          const CameraRollIndicator(),
          // No home on the home screen, see issue #40.
          if (albumState.path.isNotEmpty)
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
          // The share entry appears once the server has answered whether this
          // caller manages the grants here; everything else is there at once.
          FutureBuilder<bool>(
            future: mayShare(context, albumState.path),
            builder: (context, snapshot) => menu(context, [
              // Whose folder this is and what may be done with it, where that
              // is not simply "mine", see issue #49.
              if (sharedLine != null) ...[
                PopupMenuItem<void Function(BuildContext)>(
                  enabled: false,
                  child: Text(sharedLine!, key: const Key("shared-line")),
                ),
                const PopupMenuDivider(),
              ],
              // Only with `edit`: what the caller may not do is not offered,
              // never offered and then refused, see issue #49.
              if (mayChange)
                menuItem(Icons.create_new_folder, 'Create album', createAlbum),
              if (mayChange)
                menuItem(
                  Icons.create_new_folder_outlined,
                  'Create folder',
                  createFolder,
                ),
              if (mayChange)
                menuItem(Icons.tune, 'Folder properties', editFolder),
              // Only where there is a rule to apply: a folder without one has
              // nothing to file, see issue #48.
              if (mayChange && self.placement != Placement.none)
                menuItem(Icons.auto_awesome_motion, 'Apply rule', applyRule),
              if (snapshot.data ?? false)
                menuItem(
                  Icons.share,
                  'Share with…',
                  (context) => shareFolder(context, albumState.path, self.title),
                ),
              if (snapshot.data ?? false)
                menuItem(
                  Icons.link,
                  'Share link…',
                  (context) =>
                      shareFolderLink(context, albumState.path, self.title),
                ),
              menuItem(Icons.update, "Reload", (_) => albumState.reload()),
              // A visitor of a link has no server of their own to configure.
              if (link == null)
                menuItem(Icons.settings, "Server...", openServerSettings),
            ]),
          ),
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
  /// Three sentences, because there are three empty folders: the guest's own
  /// root (nothing has been shared yet), the root of a library (no albums
  /// yet, and how to make one where the caller may), and any other folder.
  /// Inside a share link the shared folder is the root, but it is somebody
  /// else's folder, so it reads as a folder.
  Widget emptyNotice(BuildContext context, bool inLink, bool mayChange) {
    var atRoot = albumState.path.isEmpty && !inLink;
    String sentence;
    if (atRoot && CallerInfo.isGuestCaller(context)) {
      sentence = guestRootEmptyNotice;
    } else if (atRoot) {
      sentence = mayChange
          ? "$libraryEmptyNotice $libraryEmptyHint"
          : libraryEmptyNotice;
    } else {
      sentence = folderEmptyNotice;
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
                  if (folder.subTitle.isNotEmpty)
                    Text(
                      folder.subTitle,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Colors.white70,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  // Whose album this tile shows: a link carries the name of
                  // the owner who shared it, see issue #50.
                  if (linkOwnerOf(folder) != null)
                    Text(
                      "from ${linkOwnerOf(folder)}",
                      key: Key("link-owner-${folder.name}"),
                      style: const TextStyle(
                        fontSize: 12,
                        fontStyle: FontStyle.italic,
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

  /// The picture of one tile, with the link badge on it where the tile is a
  /// link to somebody else's album, see issue #50.
  Widget buildFolderWidget(FolderInfo folder, double width) {
    var picture = buildFolderPicture(folder, width);
    var owner = linkOwnerOf(folder);
    if (owner == null) {
      return picture;
    }
    return Stack(
      children: [
        picture,
        Positioned(
          top: 4,
          left: 4,
          child: Tooltip(
            message: "Shared by $owner",
            child: Semantics(
              label: "Shared by $owner",
              child: Container(
                key: Key("link-badge-${folder.name}"),
                padding: const EdgeInsets.all(3),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: const Icon(Icons.link, size: 16, color: Colors.white),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget buildFolderPicture(FolderInfo folder, double width) {
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

  /// The menu of one folder tile, at the point it was pressed.
  Future<void> showTileMenu(
    BuildContext context,
    FolderInfo folder,
    Offset position,
  ) async {
    var overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
    var childPath = [...albumState.path, folder.name];
    // Moving an entry out of this folder is an edit *of this folder*; sharing
    // the entry is a question about the entry itself, so the two are asked
    // separately. The answer is remembered per folder, so a second long press
    // asks nothing again.
    var link = ShareSession.of(context);
    // Moving inside a guest's own root is refused as well: every target the
    // picker offers lies in that root, see [guestRoot].
    var mayMove = rights.mayEdit && link == null && !guestRoot(context);
    // A link is an entry of *this* folder, so removing it is an edit of this
    // folder; the album it points at is not touched, see issue #50.
    var mayUnlink = rights.mayEdit && link == null && folder.link.isNotEmpty;
    var mayShareChild = await mayShare(context, childPath);
    if (!context.mounted || (!mayMove && !mayShareChild && !mayUnlink)) {
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
          const PopupMenuItem<String>(
            value: "move",
            child: ListTile(
              leading: Icon(Icons.drive_file_move),
              title: Text("Move to…"),
            ),
          ),
        if (mayShareChild)
          const PopupMenuItem<String>(
            value: "share",
            child: ListTile(
              leading: Icon(Icons.share),
              title: Text("Share with…"),
            ),
          ),
        if (mayShareChild)
          const PopupMenuItem<String>(
            value: "share-link",
            child: ListTile(
              leading: Icon(Icons.link),
              title: Text("Share link…"),
            ),
          ),
        if (mayUnlink)
          const PopupMenuItem<String>(
            value: "unlink",
            child: ListTile(
              leading: Icon(Icons.link_off),
              title: Text("Remove from my albums"),
            ),
          ),
      ],
    );
    if (chosen == null || !context.mounted) {
      return;
    }
    if (chosen == "share") {
      await shareFolder(context, childPath, folder.title);
      return;
    }
    if (chosen == "share-link") {
      await shareFolderLink(context, childPath, folder.title);
      return;
    }
    if (chosen == "unlink") {
      await removeLink(context, folder);
      return;
    }
    await moveWithPicker(
      context: context,
      client: client,
      source: albumState.path,
      names: [folder.name],
      subject: EntrySubject(folder.name),
      onMoved: albumState.reload,
    );
  }

  /// Removes the link [folder] from this folder, see issue #50.
  ///
  /// Only the entry goes: the album stays with its owner, and it is the
  /// owner's to share again. That is what the confirmation says — a tile that
  /// looks exactly like an album of one's own must not vanish on a menu entry
  /// without a word about what is being thrown away.
  Future<void> removeLink(BuildContext context, FolderInfo folder) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var owner = linkOwnerOf(folder) ?? "its owner";
    var messenger = ScaffoldMessenger.of(context);
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("unlink-confirm"),
        title: const Text("Remove from my albums"),
        content: Text(
          "The album stays with $owner; only your entry is removed. "
          "It does not come back on its own.",
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Cancel"),
          ),
          ElevatedButton.icon(
            key: const Key("unlink-confirmed"),
            icon: const Icon(Icons.link_off),
            label: const Text("Remove"),
            onPressed: () => Navigator.of(context).pop(true),
          ),
        ],
      ),
    );
    if (confirmed != true) {
      return;
    }

    MoveResult result;
    try {
      result = await client.unlink(albumState.path, [folder.name]);
    } catch (error) {
      // The server's own reason, as every refused write shows it.
      showRefusal(messenger, error);
      return;
    }

    // The tile is gone from the folder: the listing on the screen has to be
    // fetched again before the outcome is read out.
    albumState.reload();

    var removed = result.outcomes.length - refusedOutcomes(result).length;
    var summary =
        removed == 0 ? "Nothing removed." : "Removed from my albums.";
    if (!context.mounted) {
      messenger.showSnackBar(
        SnackBar(content: Text(summary), duration: const Duration(seconds: 6)),
      );
      return;
    }
    await reportOutcomes(
      context: context,
      messenger: messenger,
      title: "Remove",
      summary: summary,
      result: result,
    );
  }

  /// Opens the share dialog on the folder at [path], see issue #49.
  Future<void> shareFolder(
    BuildContext context,
    List<String> path,
    String title,
  ) =>
      shareWith(
        context: context,
        client: client,
        path: path,
        label: title.isEmpty ? null : "'$title'",
      );

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
      folders: listing.folders,
    );

    try {
      await client.saveListing(albumState.path, stored);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    albumState.reload();
  }

  /// Applies the placement rule of this folder to what is already in it.
  void applyRule(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);

    MoveResult result;
    try {
      result = await client.place(albumState.path);
    } catch (error) {
      showRefusal(messenger, error);
      return;
    }

    // What was filed is no longer where it was: the listing on the screen has
    // to be fetched again before the outcome is read out.
    albumState.reload();

    var filed = result.outcomes.length - refusedOutcomes(result).length;
    if (!context.mounted) {
      return;
    }
    await reportOutcomes(
      context: context,
      messenger: messenger,
      title: "Apply rule",
      summary: filed == 0
          ? "Nothing to file."
          : "Filed $filed album${filed == 1 ? "" : "s"}.",
      result: result,
    );
  }
}

/// The segments of a space-relative resource path, the empty path none.
List<String> splitPath(String path) =>
    [for (var segment in path.split("/")) if (segment.isNotEmpty) segment];

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
  static const Map<Placement, String> placementLabels = {
    Placement.none: "keine Regel",
    Placement.byYear: "nach Jahr",
    Placement.byYearMonth: "nach Jahr und Monat",
  };

  @override
  void dispose() {
    titleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Dialog(
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
                    child: const Text("Ordnereigenschaften"),
                  ),
                ),
                TextField(
                  controller: titleController,
                  autofocus: true,
                  decoration: const InputDecoration(label: Text("Titel")),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Text(
                    "Ablageregel",
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                ),
                // What the rule does, plainly: it places what arrives, it
                // does not tidy up behind itself.
                const Padding(
                  padding: EdgeInsets.only(top: 4, bottom: 4),
                  child: Text(
                    "Was hier ankommt, wird in seinen Jahresordner abgelegt. "
                    "Was schon hier liegt, bleibt liegen, bis „Apply rule“ "
                    "aufgerufen wird.",
                    key: Key("placement-explanation"),
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
                      for (var entry in placementLabels.entries)
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
                        child: const Text("Abbrechen"),
                      ),
                      const SizedBox(width: 8),
                      ElevatedButton.icon(
                        icon: const Icon(Icons.check),
                        label: const Text("Übernehmen"),
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
      // The explicit date, at local midnight of the day that was picked: the
      // server files the new album by it, see issue #48.
      date: date == null
          ? 0
          : DateTime(date.year, date.month, date.day).millisecondsSinceEpoch,
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
