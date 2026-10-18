/// The folder listing view and the dialogs creating albums and folders.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:date_field/date_field.dart';
import 'package:flutter/material.dart';

import 'about.dart';
import 'action_guard.dart';
import 'album_date.dart';
import 'album_edit.dart' show downloadedImages, indexImageOf;
import 'album_view.dart'
    show AlbumProperties, AlbumPropertiesDialog, keyedMenuItem;
import 'app.dart';
import 'caller.dart';
import 'client.dart';
import 'crop.dart';
import 'downloads.dart';
import 'form_dialog.dart';
import 'key_guard.dart';
import 'keyboard_scroll.dart';
import 'l10n/app_localizations.dart';
import 'news.dart';
import 'listing_layout.dart';
import 'move_view.dart';
import 'resource.dart';
import 'not_ready.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'persons_view.dart' show BrowserMenuHold;
import 'rights.dart';
import 'routes.dart' show DuplicatesRoute, SearchRoute, SimilarRoute;
import 'selectable.dart';
import 'settings.dart';
import 'share_session.dart';
import 'share_view.dart';
import 'thumbnails.dart';
import 'tile_keys.dart';
import 'upload_target_view.dart';
import 'status.dart';
import 'page_app_bar.dart';

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
///
/// A [large] tile is the 2×2 tile of a starred entry (issues #239, #241): it
/// asks for the server's large preview where its pixels exceed the normal
/// one ([wantsLargeThumbnail]), and decodes the picture at the height it is
/// drawn at — at most the square times the zoom, the bound [thumbnail] needs
/// to never draw from fewer pixels than shown — rather than the whole large
/// preview.
Widget indexPictureTile(
  VAlbumClient client,
  String imageUrl,
  ThumbnailInfo info,
  double size, {
  Key? key,
  bool large = false,
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
              displayHeight:
                  large ? size * (info.scale > 0 ? info.scale : 1.0) : null,
              largeTileSize: large ? size : null,
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
  /// is published to the tree, see [rightsOf] and issue #85. Nothing is
  /// edited through a share link (issue #51), which [rightsOf] says as well.
  Rights rightsIn(BuildContext context) => rightsOf(context, listing);

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

  /// The listing, whose tiles answered "not made yet" — the large preview of
  /// a tile just starred, the cut preview of a cover just chosen (issue #260)
  /// — are asked again by themselves, see [NotReadyReAsk] and issue #258.
  ///
  /// The counts its tiles asked for on hover live as long as this view shows
  /// this folder, see [TileCountScope] and issue #286.
  @override
  Widget build(BuildContext context) => NotReadyReAsk(
        child: TileCountScope(
          key: ValueKey<String>(albumState.path.join("/")),
          child: _buildListing(context),
        ),
      );

  Widget _buildListing(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var self = listing;
    // Inside a share link nothing that changes anything is offered, and
    // neither is the way to the settings. The folder is named by the folder,
    // not by the link (issue #97): the link's label names the link where links
    // are managed, and over the folder's own title it only said the same thing
    // twice.
    var link = ShareSession.of(context);
    var sharedLine = sharedLineIn(context);
    var mayChange = rightsIn(context).mayEdit;
    // The inbox of the space is no tile of any listing; it is reached from the
    // start page, by an icon carrying how much waits there and by the same
    // entry in the menu -- for whoever may put something in it, which is
    // whom the server names it to (issue #226).
    var caller = CallerInfo.maybeOf(context);
    var inbox = link == null &&
            albumState.path.isEmpty &&
            caller != null &&
            caller.hasInbox
        ? caller
        : null;
    // Which photographs lie in several albums is the space's overview, asked
    // of the start page by its members -- each of them is answered what they
    // may see -- and never inside a link, see issue #220. A server without
    // authentication names nobody but lets everybody edit.
    var mayAskDuplicates = link == null &&
        albumState.path.isEmpty &&
        (CallerInfo.permissionOf(context).named || rightsIn(context).mayEdit);
    // The photographs below this folder that match a search, for its members
    // and never inside a link -- the start page's searches the space, see
    // issue #227.
    var maySearch = link == null &&
        (CallerInfo.permissionOf(context).named || rightsIn(context).mayEdit);
    return Scaffold(
      // Black like the album pages, so that the way down does not flash from
      // a light page to a dark one, see issue #40.
      backgroundColor: Colors.black,
      appBar: PageAppBar(
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
          // What runs and what failed, for a member and while this device
          // uploads (issue #305).
          const StatusIndicator(),
          // What arrived from others, for a member (issue #268).
          const NewsBellButton(),
          if (maySearch)
            IconButton(
              key: const Key("open-search"),
              icon: const Icon(Icons.search),
              tooltip: l10n.searchAction,
              onPressed: () =>
                  albumState.navigator.go(SearchRoute(albumState.path)),
            ),
          if (inbox != null)
            InboxButton(
              count: inbox.inboxCount,
              onPressed: () => albumState.showPath(inbox.inboxPath),
            ),
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
            if (inbox != null)
              keyedMenuItem(
                const Key("open-inbox"),
                Icons.inbox,
                l10n.inboxMenuEntry(inbox.inboxCount),
                (_) => albumState.showPath(inbox.inboxPath),
              ),
            // Only with `edit`: what the caller may not do is not offered,
            // never offered and then refused, see issue #49.
            if (mayChange)
              menuItem(Icons.create_new_folder, l10n.createAlbum, createAlbum),
            // A collection of references to photographs of other albums,
            // made wherever an album may be made, see issue #221.
            if (mayChange)
              keyedMenuItem(
                const Key("create-collection"),
                Icons.collections_bookmark_outlined,
                l10n.createCollection,
                createCollection,
              ),
            if (mayChange)
              menuItem(
                Icons.create_new_folder_outlined,
                l10n.createFolder,
                createFolder,
              ),
            if (mayChange)
              menuItem(Icons.tune, l10n.folderProperties, editFolder),
            // Whether this folder stands out in the one above it (#239); the
            // root of a space lies in no folder.
            if (mayChange && albumState.path.isNotEmpty)
              keyedMenuItem(
                const Key("toggle-star"),
                self.starred ? Icons.star_border : Icons.star,
                self.starred ? l10n.removeStarAction : l10n.addStarAction,
                toggleStar,
              ),
            // Only where there is a rule to apply: a folder without one has
            // nothing to file, see issue #48.
            if (mayChange && self.placement != Placement.none)
              menuItem(Icons.auto_awesome_motion, l10n.applyRule, applyRule),
            if (mayAskDuplicates)
              keyedMenuItem(
                const Key("open-duplicates"),
                Icons.photo_library_outlined,
                l10n.duplicatesMenuEntry,
                (_) => albumState.navigator.go(const DuplicatesRoute()),
              ),
            // The shots of the same scenery in several albums, beside the
            // duplicates and asked by whoever may ask those (issue #257).
            if (mayAskDuplicates)
              keyedMenuItem(
                const Key("open-similar"),
                Icons.burst_mode_outlined,
                l10n.similarMenuEntry,
                (_) => albumState.navigator.go(const SimilarRoute()),
              ),
            if (mayShare(context, albumState.path))
              menuItem(
                Icons.link,
                l10n.shareLinkAction,
                (context) =>
                    shareFolderLink(context, albumState.path, self.title),
              ),
            menuItem(Icons.update, l10n.reload, (_) {
              albumState.reload();
              // How much waits in the inbox is part of what the start page
              // says (issue #226).
              CallerInfo.refresh(context);
            }),
            // A visitor of a link has no server of their own to configure.
            if (link == null)
              menuItem(
                  Icons.settings, l10n.serverMenuEntry, openServerSettings),
            // The way out of this device's sign-in, the very one of the
            // settings screen; only where there is a sign-in to end.
            if (link == null && mayOfferSignOut(context))
              keyedMenuItem(
                const Key("sign-out"),
                Icons.logout,
                l10n.signOut,
                (context) =>
                    signOutFromMenu(context, showRoot: albumState.showRoot),
              ),
            // What this app is, the last entry of every main menu (#187).
            // Who a personal link's visitor is, and the way to be somebody else
            // (issue #202).
            ...switchPersonEntries(context),
            aboutMenuItem(l10n),
          ]),
        ],
      ),
      body: Column(
        children: [
          // Says plainly when the tiles below are the copy from the cache.
          OfflineBanner(onRetry: albumState.reload),
          // Where new photos of this device go while that is not the inbox,
          // with the one tap back to it (issue #240); on the start page.
          if (albumState.path.isEmpty && link == null)
            const UploadTargetBanner(),
          // An empty folder says what it is, rather than showing a black
          // page with nothing but the app bar on it, see issue #56.
          if (self.folders.isEmpty)
            Expanded(child: emptyNotice(context, link != null, mayChange))
          else
            Expanded(
              // The keyboard walks the tiles (issue #295): the arrows, Enter
              // to open, the menu key for the tile's menu.
              child: TileHighlightScope<FolderInfo>(
                idOf: (folder) => folder.name,
                builder: (context, highlight) => KeyboardScroll(
                  // A scrollbar and the paging keys, issue #168.
                  onKeyEvent: guardKeys((node, event) => tileKeyEvent(
                    highlight,
                    node,
                    event,
                    TileKeyActions<FolderInfo>(
                      onEnter: (folder) => albumState.showElement(folder.name),
                      onMenu: (folder, position) =>
                          showTileMenu(context, folder, position),
                    ),
                  )),
                  child: LayoutBuilder(
                    builder:
                        (BuildContext context, BoxConstraints constraints) {
                      double imageBorder = 8;
                      var preferredImageWidth = 200;
                      // The tiles stay clear of a cutout or the navigation
                      // bar at a side in landscape, the app bar having taken
                      // the top inset already.
                      var insets = MediaQuery.paddingOf(context);
                      var maxWidth =
                          constraints.maxWidth - insets.left - insets.right;
                      double preferredImageSpace =
                          preferredImageWidth + 2 * imageBorder;
                      double imagesPerRowFrag = maxWidth / preferredImageSpace;
                      // At least one column, however narrow the window.
                      var imagesPerRow = math.max(1, imagesPerRowFrag.round());
                      // A starred tile takes two columns of the first row
                      // (issue #239), so a few tiles fill a row sooner.
                      var cellsUsed = self.folders.fold<int>(
                        0,
                        (sum, folder) =>
                            sum + (folder.starred && imagesPerRow >= 2 ? 2 : 1),
                      );
                      bool underflow = cellsUsed < imagesPerRow;
                      double difference = underflow
                          ? 0
                          : maxWidth - imagesPerRow * preferredImageSpace;
                      double imageSpace =
                          preferredImageSpace + difference / imagesPerRow;

                      return SingleChildScrollView(
                        scrollDirection: Axis.vertical,
                        // The last row of tiles ends above the system
                        // navigation bar instead of running under it, see
                        // issue #60.
                        padding: EdgeInsets.only(
                          left: insets.left,
                          right: insets.right,
                          bottom: insets.bottom,
                        ),
                        child: buildFolderList(
                          context,
                          self,
                          imageSpace - 2 * imageBorder,
                          imageBorder,
                          imagesPerRow,
                          highlight: highlight,
                        ),
                      );
                    },
                  ),
                ),
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

  /// The tiles of the folder, in a grid of [columns] columns, each
  /// [imageWidth] wide and a starred one twice that and twice as high (issue
  /// #239), see [tileCells].
  TileGrid buildFolderList(
    BuildContext context,
    ListingInfo self,
    double imageWidth,
    double imageBorder,
    int columns, {
    TileHighlight<FolderInfo>? highlight,
  }) {
    // The oldest first in a dated folder, the newest first in an undated
    // one, the undated behind them by name -- the order the server sends a
    // listing in (issues #48, #270), applied here as well, so that an older
    // server and the offline cache read the same way.
    var folders = shownFolders(
      self.folders,
      albumState.path,
      linkRoot: ShareSession.of(context) != null,
    );
    var cells =
        tileCells([for (var folder in folders) folder.starred], columns);
    // Where each tile lies, in cells, for the keyboard (issue #295).
    highlight?.spots = [
      for (var n = 0; n < folders.length; n++)
        TileSpot(
          folders[n],
          Rect.fromLTWH(
            cells[n].column.toDouble(),
            cells[n].row.toDouble(),
            cells[n].width.toDouble(),
            cells[n].height.toDouble(),
          ),
        ),
    ];
    Widget walked(FolderInfo folder, Widget tile) => highlight == null
        ? tile
        : HighlightedTile<FolderInfo>(
            key: ValueKey(("tile-highlight", folder.name)),
            highlight: highlight,
            entry: folder,
            child: tile,
          );
    return TileGrid(
      key: const Key("listing-tiles"),
      cellWidth: imageWidth + 2 * imageBorder,
      cells: cells,
      children: [
        for (var n = 0; n < folders.length; n++)
          walked(
            folders[n],
            buildTile(
              context,
              folders[n],
              cells[n],
              // A large tile spans the gutter between its columns too.
              cells[n].width * (imageWidth + 2 * imageBorder) - 2 * imageBorder,
              imageBorder,
            ),
          ),
      ],
    );
  }

  /// One tile of the listing: the picture, the title, the date and the
  /// subtitle, the picture [width] wide and as high.
  ///
  /// The tile of a starred entry (issue #239) carries a star in the corner of
  /// its picture, and its title is set larger: the tile is twice as wide.
  ///
  /// A tap opens it, a long press offers its actions: a gesture surface of
  /// its own, see [SelectionExcluded].
  Widget buildTile(
    BuildContext context,
    FolderInfo folder,
    TileCell cell,
    double width,
    double imageBorder,
  ) =>
      SelectionExcluded.keyed(
          _buildTile(context, folder, cell, width, imageBorder));

  Widget _buildTile(
    BuildContext context,
    FolderInfo folder,
    TileCell cell,
    double width,
    double imageBorder,
  ) {
    var large = cell.isLarge;
    // The browser's own menu is away while the pointer is over a tile whose
    // menu has something to offer (issue #290), and only then: a secondary
    // click never lands on nothing.
    return TileMenuHold(
      key: ValueKey<(String, String)>(("tile-menu-hold", folder.name)),
      offers: offersTileMenu(context, folder),
      child: _tileBody(context, folder, width, large, imageBorder),
    );
  }

  Widget _tileBody(
    BuildContext context,
    FolderInfo folder,
    double width,
    bool large,
    double imageBorder,
  ) {
    return Padding(
      key: ValueKey<String>("tile-${folder.name}"),
      padding: EdgeInsets.all(imageBorder),
      child: GestureDetector(
        onTap: () => albumState.showElement(folder.name),
        // A listing tile had no menu of its own; the long press is the
        // touch idiom the album already uses to reach a tile's tools, and
        // this menu holds only what a tile can do, see issue #47.
        onLongPressStart: (details) =>
            showTileMenu(context, folder, details.globalPosition),
        // A mouse's way to the same menu, at the pointer (issue #290).
        onSecondaryTapUp: (details) =>
            showTileMenu(context, folder, details.globalPosition),
        child: SizedBox(
          width: width,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                // The star takes the top right corner; the count shown on
                // hover takes the bottom right one, see issue #286.
                child: TileCountHover(
                  folder: folder,
                  large: large,
                  // What the listing did not count is asked of the folder.
                  ask: asksImageCount(folder)
                      ? () =>
                          client.imageCount([...albumState.path, folder.name])
                      : null,
                  child: folder.starred
                      ? Stack(
                          children: [
                            buildFolderWidget(folder, width, large: large),
                            Positioned(
                              top: 6,
                              right: 6,
                              child: starBadge(context, large),
                            ),
                          ],
                        )
                      : buildFolderWidget(folder, width, large: large),
                ),
              ),
              Text(
                folder.title,
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                  fontSize: large ? 18 : null,
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
                  style: TextStyle(
                    fontSize: large ? 14 : 12,
                    color: Colors.white60,
                  ),
                  textAlign: TextAlign.center,
                ),
              if (folder.subTitle.isNotEmpty)
                Text(
                  folder.subTitle,
                  style: TextStyle(
                    fontSize: large ? 14 : 12,
                    color: Colors.white70,
                  ),
                  textAlign: TextAlign.center,
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// The small star in the corner of a starred tile, see issue #239.
  Widget starBadge(BuildContext context, bool large) => Tooltip(
        message: AppLocalizations.of(context)!.starredBadge,
        child: Container(
          key: const Key("star-badge"),
          padding: const EdgeInsets.all(3),
          decoration: const BoxDecoration(
            color: Colors.black54,
            shape: BoxShape.circle,
          ),
          child: Icon(
            Icons.star,
            color: Colors.amber,
            size: large ? 28 : 20,
          ),
        ),
      );

  /// The picture of one tile.
  ///
  /// A `link` field on a folder is what issue #50 made and issue #85 retired:
  /// a space is addressed by its own URL now, and nothing of somebody else's
  /// hangs in this tree. A listing that still carries one — an old server —
  /// shows the folder plainly, and never an error.
  ///
  /// A [large] tile is the 2×2 tile of a starred entry, which asks for the
  /// large preview on a dense screen, see [indexPictureTile] and issue #241.
  Widget buildFolderWidget(FolderInfo folder, double width,
          {bool large = false}) =>
      buildFolderPicture(folder, width, large: large);

  Widget buildFolderPicture(FolderInfo folder, double width,
      {bool large = false}) {
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
          child: Icon(
            // A collection of references says what it is, see issue #221, and
            // so does a saved search that finds nothing for this caller, #227.
            folder.kind == FolderKind.collection
                ? Icons.collections_bookmark_outlined
                : folder.kind == FolderKind.search
                    ? Icons.saved_search
                    : Icons.folder,
            key: folder.kind == FolderKind.collection
                ? const Key("collection-icon")
                : folder.kind == FolderKind.search
                    ? const Key("search-icon")
                    : null,
            size: width / 2,
            color: Colors.blue,
          ),
        ),
      );
    }

    return indexPictureTile(
      client,
      // A cropped photograph is asked for cut, by the region the server
      // derived for this cover (issue #212), a video at the moment of its
      // chosen poster (issue #285).
      coverImageUrl(
          "$baseUrl/${folder.name}/${indexPicture.image}", indexPicture),
      indexPicture,
      width,
      large: large,
    );
  }

  /// Whether the tile of [folder] has a menu for this caller, see
  /// [showTileMenu]: the move of an editor, a share link of the entry, and
  /// on an album's tile its properties for everybody (issue #295).
  bool offersTileMenu(BuildContext context, FolderInfo folder) =>
      rightsIn(context).mayEdit ||
      mayShare(context, [...albumState.path, folder.name]) ||
      isAlbumTile(folder);

  /// Whether [folder] is an album of photographs — an album, a collection,
  /// a saved search — whose tile offers what the album's own menu does with
  /// it: its properties and its download (issue #295). A folder of folders
  /// has its own properties in its own listing.
  static bool isAlbumTile(FolderInfo folder) =>
      folder.kind == FolderKind.album ||
      folder.kind == FolderKind.collection ||
      folder.kind == FolderKind.search;

  /// Whether the tile of [folder] offers the download of the album: where
  /// the album's menu would, to whoever may download here (issue #295) —
  /// the album answers its own rights, which decide when it is loaded.
  bool offersDownload(BuildContext context, FolderInfo folder) =>
      isAlbumTile(folder) && rightsIn(context).mayDownload;

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
    var mayMove = rightsIn(context).mayEdit;
    var mayShareChild = mayShare(context, childPath);
    var albumTile = isAlbumTile(folder);
    var mayDownload = offersDownload(context, folder);
    if (!context.mounted || !offersTileMenu(context, folder)) {
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
        // A star is the entry's own property (issue #239), and the right to
        // set it is the right to edit the entry; whoever may edit this folder
        // may edit what lies in it.
        if (mayMove)
          PopupMenuItem<String>(
            key: const Key("toggle-star"),
            value: "star",
            child: ListTile(
              leading: Icon(folder.starred ? Icons.star_border : Icons.star),
              title: Text(
                folder.starred ? l10n.removeStarAction : l10n.addStarAction,
              ),
            ),
          ),
        // What the album's own menu does with the album, asked from its
        // tile (issue #295): what it is — edited by whoever may edit it,
        // shown to everybody else — and the copy of its photographs.
        if (albumTile)
          PopupMenuItem<String>(
            key: const Key("tile-properties"),
            value: "properties",
            child: ListTile(
              leading: const Icon(Icons.tune),
              title: Text(l10n.albumProperties),
            ),
          ),
        if (mayDownload)
          PopupMenuItem<String>(
            key: const Key("tile-download"),
            value: "download",
            child: ListTile(
              leading: const Icon(Icons.download),
              title: Text(l10n.downloadAlbumAction),
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
    if (chosen == "star") {
      await toggleChildStar(context, folder);
      return;
    }
    if (chosen == "properties") {
      await childProperties(context, folder);
      return;
    }
    if (chosen == "download") {
      await downloadChild(context, folder);
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
    var stored = ListingInfo(
      path: listing.path,
      title: listing.title,
      placement: listing.placement,
      index: name,
      // The folder's own star stays what it was (issue #239).
      starred: listing.starred,
      folders: listing.folders,
    );
    // Refused offline, and the server's own reason where it refuses.
    var done = await writeAction(
        context, () => client.saveListing(albumState.path, stored));
    if (done == null) {
      return;
    }
    var self = albumState.path;
    if (self.isNotEmpty) {
      albumState.navigator.delegate.forget(self.sublist(0, self.length - 1));
    }
    albumState.reload();
  }

  /// Stars the entry [folder] of this listing, or takes its star away, see
  /// issue #239.
  ///
  /// The star is the entry's own property, stored in the entry's own sidecar,
  /// so the entry is read and written back with the one field changed --
  /// the ordinary property save its own menu uses, see [toggleStar]. Where
  /// the save renames the entry's folder (its properties compose another
  /// name, issue #130), the server says so and the listing shows the new
  /// name.
  Future<void> toggleChildStar(BuildContext context, FolderInfo folder) async {
    var messenger = ScaffoldMessenger.of(context);
    var childPath = [...albumState.path, folder.name];
    var done = await writeAction(context, () async {
      var child = await client.loadResource(childPath);
      if (child is AlbumInfo) {
        return await client.saveAlbum(
            childPath, child..starred = !folder.starred);
      } else if (child is ListingInfo) {
        return await client.saveListing(
            childPath, child..starred = !folder.starred);
      }
      return null;
    });
    if (done == null) {
      return;
    }
    var written = done.value;
    if (written == null) {
      // Nothing there that could carry a star: the listing is stale.
      albumState.reload();
      return;
    }
    // What the session holds of the entry is not what it is now -- and not
    // where it is, if it was renamed.
    albumState.navigator.delegate.forgetTree(childPath);
    albumState.reload();
    if (written.message.isNotEmpty) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(written.message),
          duration: const Duration(seconds: 6),
        ),
      );
    }
  }

  /// The album of the tile of [folder], loaded for an action of its tile
  /// (issue #295); `null`, said in a red bar, where it cannot be loaded, and
  /// the listing asked anew where the entry is no album any more.
  Future<AlbumInfo?> loadChildAlbum(
      BuildContext context, FolderInfo folder) async {
    var messenger = ScaffoldMessenger.of(context);
    Resource? child;
    try {
      child = await client.loadResource([...albumState.path, folder.name]);
    } catch (error) {
      if (messenger.mounted) {
        showRefusal(messenger, error);
      }
      return null;
    }
    if (child is! AlbumInfo) {
      albumState.reload();
      return null;
    }
    return child;
  }

  /// The properties of the album of [folder], from its tile (issue #295):
  /// the album's own dialog, edited and written at once by whoever may edit
  /// the album ([rightsOf] of the album itself), shown read-only to
  /// everybody else.
  Future<void> childProperties(BuildContext context, FolderInfo folder) async {
    var album = await loadChildAlbum(context, folder);
    if (album == null || !context.mounted) {
      return;
    }
    var editable = rightsOf(context, album).mayEdit;
    var properties = AlbumProperties(
      title: album.title,
      subTitle: album.subTitle,
      date: album.date,
      indexPicture: album.indexPicture,
      kind: album.kind,
      placeCaption: album.placeCaption,
    );
    var dialog = AlbumPropertiesDialog(
      properties,
      client: client,
      baseUrl: "$baseUrl/${folder.name}",
      indexImage: indexImageOf(album),
      effectiveDate: album.effectiveDate,
      dateSource: describeDateSource(album, folderName: folder.name),
      readOnly: !editable,
    );
    if (!editable) {
      await showAppDialog<void>(context: context, builder: (_) => dialog);
      return;
    }
    var result = await showFormDialog<AlbumProperties>(
      context: context,
      builder: (_) => dialog,
    );
    if (result == null || !context.mounted) {
      return;
    }
    album
      ..title = result.title
      ..subTitle = result.subTitle
      ..date = result.date
      ..indexPicture = result.indexPicture
      ..placeCaption = result.placeCaption;
    var messenger = ScaffoldMessenger.of(context);
    var childPath = [...albumState.path, folder.name];
    var done =
        await writeAction(context, () => client.saveAlbum(childPath, album));
    if (done == null) {
      return;
    }
    // The tile shows the title and the picture just written, and the album
    // may have moved to the folder name they compose (issue #130).
    albumState.navigator.delegate.forgetTree(childPath);
    albumState.reload();
    var written = done.value;
    if (written.message.isNotEmpty) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(written.message),
          duration: const Duration(seconds: 6),
        ),
      );
    }
  }

  /// Downloads the album of [folder] from its tile (issue #295): what the
  /// album's menu downloads where nothing is selected — every photograph the
  /// album shows at its rating filter, one archive named by its title. The
  /// album's own rights decide, and a refusal speaks.
  Future<void> downloadChild(BuildContext context, FolderInfo folder) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var album = await loadChildAlbum(context, folder);
    if (album == null || !context.mounted) {
      return;
    }
    var l10n = AppLocalizations.of(context)!;
    var messenger = ScaffoldMessenger.of(context);
    if (!rightsOf(context, album).mayDownload) {
      messenger.showSnackBar(refusalBar(l10n.downloadNotAllowed,
          message: l10n.downloadNotAllowed));
      return;
    }
    var names = [
      for (var image in downloadedImages(album, album.parts.toSet()))
        if (!image.missing && image.rating >= album.minRating) image.name,
    ];
    if (names.isEmpty) {
      messenger.showSnackBar(SnackBar(content: Text(l10n.nothingToDownload)));
      return;
    }
    var childPath = [...albumState.path, folder.name];
    await runDownload(
      context,
      (progress) => downloadOriginals(
        client,
        childPath,
        names,
        archiveName: archiveNameOf(album.title),
        progress: progress,
      ),
    );
  }

  /// Stars the folder being shown, or takes its star away, see issue #239:
  /// its tile in the listing above is drawn 2×2.
  ///
  /// Written like its properties, see [editFolder]: what was loaded, with the
  /// one field changed.
  void toggleStar(BuildContext context) async {
    var messenger = ScaffoldMessenger.of(context);
    var stored = ListingInfo(
      path: listing.path,
      title: listing.title,
      placement: listing.placement,
      index: listing.index,
      starred: !listing.starred,
      folders: listing.folders,
    );
    var done = await writeAction(
        context, () => client.saveListing(albumState.path, stored));
    if (done == null) {
      return;
    }
    followWritten(done.value, messenger);
  }

  /// Shows the folder where it is after its sidecar was written, see
  /// [editFolder]: the listing above shows its tile, which has changed, and a
  /// rename (issue #130) has moved the folder away from the address shown.
  void followWritten(CreateResult written, ScaffoldMessengerState messenger) {
    var self = albumState.path;
    var delegate = albumState.navigator.delegate;
    if (self.isNotEmpty) {
      delegate.forget(self.sublist(0, self.length - 1));
    }
    if (written.message.isEmpty) {
      albumState.reload();
      return;
    }
    delegate.forgetTree(self);
    albumState.showPath(splitPath(written.path));
    messenger.showSnackBar(
      SnackBar(
        content: Text(written.message),
        duration: const Duration(seconds: 6),
      ),
    );
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
    // A form dialog: Escape and its own Cancel are the way back (issue #35),
    // a tap beside it is not, since that would throw the input away (#178).
    ListingInfo? folder = await showFormDialog<ListingInfo>(
      context: context,
      builder: (context) => const CreateFolderDialog(),
    );

    if (folder == null || !context.mounted) {
      return;
    }

    var done = await writeAction(
        context, () => client.putResource("$baseUrl/${folder.path}", folder));
    if (done == null) {
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
    AlbumInfo? album = await showFormDialog<AlbumInfo>(
      context: context,
      builder: (context) => const CreateAlbumDialog(),
    );

    if (album == null || !context.mounted) {
      return;
    }

    var done = await writeAction(
        context, () => client.createAlbum(albumState.path, album));
    if (done == null) {
      return;
    }
    var result = done.value;

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

  /// Creates a collection in the folder being shown, see issue #221: an
  /// album of references to photographs that lie in other albums.
  void createCollection(BuildContext context) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    AlbumInfo? collection = await showFormDialog<AlbumInfo>(
      context: context,
      builder: (context) => const CreateCollectionDialog(),
    );
    if (collection == null || !context.mounted) {
      return;
    }
    var done = await writeAction(
        context, () => client.createAlbum(albumState.path, collection));
    if (done == null) {
      return;
    }
    var result = done.value;
    albumState.navigator.delegate.forgetTree(albumState.path);
    albumState.reload();
    albumState.showPath(splitPath(result.path));
    if (result.message.isNotEmpty) {
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
    var edited = await showFormDialog<FolderProperties>(
      context: context,
      builder: (context) => FolderPropertiesDialog(
        FolderProperties(title: listing.title, placement: listing.placement),
      ),
    );

    if (edited == null || !context.mounted) {
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
      // So is its star, which is set from the menu (issue #239).
      starred: listing.starred,
      folders: listing.folders,
    );

    var done = await writeAction(
        context, () => client.saveListing(albumState.path, stored));
    if (done == null) {
      return;
    }
    var written = done.value;

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
    var l10n = AppLocalizations.of(context)!;
    var messenger = ScaffoldMessenger.of(context);

    var done = await writeAction(context, () => client.place(albumState.path));
    if (done == null) {
      return;
    }
    var result = done.value;

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
    return FormDialogFrame(
      title: Text(l10n.folderProperties),
      fields: [
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
        // What the rule does, plainly: it places what arrives, it does not
        // tidy up behind itself.
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
      ],
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
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

  /// The title proposed, `null` for none.
  ///
  /// The folder picker fills it with what was typed in its search field, see
  /// `FolderPickerState`: the album looked for and not found is the one to
  /// create.
  final String? initialTitle;

  const CreateAlbumDialog({super.key, this.initialDate, this.initialTitle});

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
    var l10n = AppLocalizations.of(context)!;
    var now = DateTime.now();

    // Always an album: an inbox is rare, and is made by switching an album in
    // its properties (`album-kind`) or by the camera-roll sync, see #178.
    return Form(
      key: formKey,
      child: FormDialogFrame(
        title: Text(l10n.newAlbumTitle),
        fields: [
          DateTimeFormField(
            mode: DateTimeFieldPickerMode.date,
            // Any day, the future included: an album for the holiday that
            // starts next week may be made today (issue #178).
            firstDate: firstAlbumDate,
            lastDate: lastAlbumDate,
            // The day proposed stands in the field, so that it is the album's
            // date without anything further being done — and the calendar
            // opens on it when it is changed, see issue #114.
            initialValue: widget.initialDate,
            initialPickerDateTime: widget.initialDate ?? now,
            // The field is cleared by the dialog, not by an icon of the
            // package's own: `date_field` 7 would otherwise replace the
            // calendar icon below with a cross as soon as a day stands in the
            // field (issue #108 upgraded the package for `intl`).
            canClear: false,
            onSaved: (value) => albumDate = value,
            dateFormat: folderDateFormat,
            // No validator: an album without a date is one the server leaves
            // in the folder it was made in, which is how an `Inbox` is made
            // by hand, see issue #119.
            decoration: InputDecoration(
              label: Text(l10n.dateLabel),
              suffixIcon: const Icon(Icons.date_range),
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              l10n.createAlbumUndatedHint,
              key: const Key("create-album-date-hint"),
              style: const TextStyle(fontSize: 12),
            ),
          ),
          TextFormField(
            initialValue: widget.initialTitle,
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
        ],
        actions: [
          TextButton(
            onPressed: cancelPressed,
            child: Text(l10n.cancel),
          ),
          ElevatedButton.icon(
            icon: const Icon(Icons.check),
            label: Text(l10n.create),
            onPressed: createPressed,
          ),
        ],
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
      // `yyyy-MM-dd title`, the title alone without a date -- the one
      // composition, shared with the move picker, see [albumFolderName].
      path: albumFolderName(date, title),
      kind: AlbumKind.album,
    );

    Navigator.of(context).pop(info);
  }
}

/// The dialog that names a new collection, see issue #221.
///
/// A title and nothing else: a collection has no photographs of its own whose
/// day it could carry, so it is made without a date and stays in the folder it
/// is made in (a date may still be given in its properties). It answers the
/// sidecar to create, of kind [AlbumKind.collection].
class CreateCollectionDialog extends StatefulWidget {
  /// The name proposed, `null` for none — the folder picker fills it with what
  /// was typed in its search field.
  final String? initialTitle;

  const CreateCollectionDialog({super.key, this.initialTitle});

  @override
  State<StatefulWidget> createState() => CreateCollectionDialogState();
}

class CreateCollectionDialogState extends State<CreateCollectionDialog> {
  var formKey = GlobalKey<FormState>();
  String? title;

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Form(
      key: formKey,
      child: FormDialogFrame(
        key: const Key("create-collection-dialog"),
        title: Text(l10n.newCollectionTitle),
        fields: [
          Text(
            l10n.newCollectionHint,
            style: const TextStyle(fontSize: 12),
          ),
          TextFormField(
            key: const Key("collection-title"),
            autofocus: true,
            initialValue: widget.initialTitle,
            decoration: InputDecoration(label: Text(l10n.titleLabel)),
            onSaved: (value) => title = value,
            validator: (String? value) => value == null || value.trim().isEmpty
                ? l10n.mustNotBeEmpty
                : null,
          ),
        ],
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(l10n.cancel),
          ),
          ElevatedButton.icon(
            key: const Key("create-collection-confirm"),
            icon: const Icon(Icons.check),
            label: Text(l10n.create),
            onPressed: createPressed,
          ),
        ],
      ),
    );
  }

  void createPressed() {
    var formState = formKey.currentState!;
    if (!formState.validate()) {
      return;
    }
    formState.save();
    var name = title!.trim();
    Navigator.of(context).pop(AlbumInfo(
      title: name,
      path: albumFolderName(null, name),
      kind: AlbumKind.collection,
    ));
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
    return Form(
      key: formKey,
      child: FormDialogFrame(
        title: Text(l10n.newFolderTitle),
        fields: [
          TextFormField(
            decoration: InputDecoration(label: Text(l10n.nameLabel)),
            onSaved: (value) => folderName = value,
            validator: (String? value) {
              return value == null || value.isEmpty
                  ? l10n.mustNotBeEmpty
                  : null;
            },
          ),
        ],
        actions: [
          TextButton(
            onPressed: cancelPressed,
            child: Text(l10n.cancel),
          ),
          ElevatedButton.icon(
            icon: const Icon(Icons.check),
            label: Text(l10n.create),
            onPressed: createPressed,
          ),
        ],
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

/// The inbox of the space on the start page, see issue #226: an icon carrying
/// a badge with how many photographs wait there for the caller.
///
/// The inbox is no tile of any listing; this is how it is opened. No badge
/// for an empty inbox, `99+` above that many.
class InboxButton extends StatelessWidget {
  /// How many photographs wait, see [CallerInfo.inboxCount].
  final int count;

  /// Opens the inbox.
  final VoidCallback onPressed;

  const InboxButton({super.key, required this.count, required this.onPressed});

  @override
  Widget build(BuildContext context) => IconButton(
        key: const Key("inbox-button"),
        tooltip: AppLocalizations.of(context)!.inboxTooltip(count),
        onPressed: onPressed,
        icon: Badge.count(
          key: const Key("inbox-badge"),
          count: count,
          maxCount: 99,
          isLabelVisible: count > 0,
          child: const Icon(Icons.inbox),
        ),
      );
}

/// How long a mouse pointer rests on a listing tile before the tile tells how
/// many photographs it holds, see [TileCountHover] and issue #286: a pointer
/// passing over the grid on its way elsewhere shows nothing and asks nothing.
const Duration tileCountDwell = Duration(milliseconds: 500);

/// Whether the listing itself told how many photographs [folder] holds, so
/// that its tile shows that number on hover without asking, see issue #286.
///
/// An album and an inbox are counted by the listing -- an album as the caller
/// is shown it; a count of `0` is an album that shows the caller nothing, and
/// its tile says nothing. A folder of folders is never walked for a count, and
/// a saved search would have to be run.
bool showsImageCount(FolderInfo folder) =>
    folder.imageCount > 0 &&
    switch (folder.kind) {
      FolderKind.album || FolderKind.inbox => true,
      _ => false,
    };

/// Whether the tile of [folder] asks the server for its count when the
/// pointer has rested on it, see [VAlbumClient.imageCount] and issue #286.
///
/// A collection's count is its references resolved for the caller, which a
/// listing never does: it is asked of the collection itself, and only for
/// the tile somebody is looking at.
bool asksImageCount(FolderInfo folder) => folder.kind == FolderKind.collection;

/// The counts the tiles of one listing view asked the server for, see
/// [TileCountHover] and issue #286: each tile is asked about at most once for
/// as long as the view shows its folder, whatever the answer was.
class TileCounts {
  final Map<String, Future<int?>> _asked = {};
  final Map<String, int?> _answered = {};

  /// Whether the entry [name] was asked about and answered; [answer] tells
  /// what.
  bool answered(String name) => _answered.containsKey(name);

  /// What the entry [name] was answered: its count, `null` where asking it
  /// failed or was refused.
  int? answer(String name) => _answered[name];

  /// The count of the entry [name], asked by [request] the first time only;
  /// `null` where that request failed.
  ///
  /// A failure is kept like an answer and never asked again: the count is a
  /// nicety, like the teaser of a tile, and a tile that cannot say it simply
  /// says nothing -- silently, and not again on every hover.
  Future<int?> ask(String name, Future<int> Function() request) =>
      _asked.putIfAbsent(name, () async {
        int? count;
        try {
          count = await request();
        } catch (_) {
          // Silent by design, see above.
          count = null;
        }
        _answered[name] = count;
        return count;
      });
}

/// Keeps the [TileCounts] of the listing view below it for as long as it
/// stands in the tree, see issue #286; keyed by the folder, so that another
/// folder starts afresh.
class TileCountScope extends StatefulWidget {
  final Widget child;

  const TileCountScope({super.key, required this.child});

  /// The counts of the listing view [context] lies in, `null` outside one.
  static TileCounts? of(BuildContext context) =>
      context.getInheritedWidgetOfExactType<_TileCountsProvider>()?.counts;

  @override
  State<TileCountScope> createState() => _TileCountScopeState();
}

class _TileCountScopeState extends State<TileCountScope> {
  final TileCounts _counts = TileCounts();

  @override
  Widget build(BuildContext context) =>
      _TileCountsProvider(counts: _counts, child: widget.child);
}

class _TileCountsProvider extends InheritedWidget {
  final TileCounts counts;

  const _TileCountsProvider({required this.counts, required super.child});

  @override
  bool updateShouldNotify(_TileCountsProvider oldWidget) =>
      counts != oldWidget.counts;
}

/// The picture of a listing tile that, once a mouse pointer has rested on it
/// for [tileCountDwell], shows in a small bubble at its bottom right how many
/// photographs the entry holds, see issue #286.
///
/// The number is the listing's own where it carries one
/// ([showsImageCount]); otherwise it is asked by [ask] when the pointer has
/// rested there, once for the life of the listing view ([TileCounts]). A
/// pointer that leaves before the dwell asks nothing; one that leaves while
/// the answer is on its way is shown nothing now, and the answer waits for
/// the next visit. A refused or failed answer shows no bubble.
///
/// The bottom right corner is free: the star of a starred tile (issue #239)
/// sits at the top right. A touch screen has no hover and never shows the
/// bubble -- a tap opens the album, which says the same.
class TileCountHover extends StatefulWidget {
  /// The entry the tile shows.
  final FolderInfo folder;

  /// Whether the tile is the 2×2 tile of a starred entry.
  final bool large;

  /// Asks the server how many photographs the entry shows the caller, where
  /// the listing did not say ([asksImageCount]); `null` where it did.
  final Future<int> Function()? ask;

  /// The picture of the tile.
  final Widget child;

  const TileCountHover({
    super.key,
    required this.folder,
    required this.large,
    this.ask,
    required this.child,
  });

  @override
  State<TileCountHover> createState() => _TileCountHoverState();
}

class _TileCountHoverState extends State<TileCountHover> {
  /// The counts of a tile outside a listing view, asked by it alone.
  TileCounts? _own;

  Timer? _dwell;

  /// Counts the pointer's visits, so that an answer arriving after the
  /// pointer left (and perhaps came back) is not shown for an old visit.
  int _visit = 0;

  bool _hovered = false;

  /// The number the bubble shows, `null` while it shows none.
  int? _shown;

  bool get _counts => showsImageCount(widget.folder) || widget.ask != null;

  void _enter() {
    _hovered = true;
    var visit = ++_visit;
    _dwell?.cancel();
    _dwell = Timer(tileCountDwell, () => _dwelt(visit));
  }

  void _exit() {
    _hovered = false;
    _visit++;
    _dwell?.cancel();
    _dwell = null;
    if (_shown != null) setState(() => _shown = null);
  }

  void _dwelt(int visit) {
    if (!mounted || !_hovered || visit != _visit) return;
    var ask = widget.ask;
    if (ask == null) {
      _show(widget.folder.imageCount);
      return;
    }
    var counts = TileCountScope.of(context) ?? (_own ??= TileCounts());
    var name = widget.folder.name;
    if (counts.answered(name)) {
      _show(counts.answer(name));
      return;
    }
    counts.ask(name, ask).then((count) {
      if (mounted && _hovered && visit == _visit) _show(count);
    });
  }

  void _show(int? count) {
    if (count == null || count <= 0) return;
    setState(() => _shown = count);
  }

  @override
  void dispose() {
    _dwell?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_counts) return widget.child;
    var shown = _shown;
    return MouseRegion(
      onEnter: (_) => _enter(),
      onExit: (_) => _exit(),
      child: Stack(
        children: [
          widget.child,
          if (shown != null)
            Positioned(
              bottom: 6,
              right: 6,
              child: _bubble(context, shown),
            ),
        ],
      ),
    );
  }

  Widget _bubble(BuildContext context, int count) {
    var large = widget.large;
    var label = AppLocalizations.of(context)!.imageCount(count);
    // The look of the star's badge: a translucent dark ground that keeps the
    // white number readable on a light photograph and a dark one alike.
    return Tooltip(
      message: label,
      // The label below says it once, for a screen reader.
      excludeFromSemantics: true,
      child: Semantics(
        label: label,
        excludeSemantics: true,
        child: Container(
          key: const Key("image-count-bubble"),
          padding: EdgeInsets.symmetric(
            horizontal: large ? 10 : 7,
            vertical: large ? 4 : 3,
          ),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(large ? 14 : 11),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.photo_outlined,
                color: Colors.white,
                size: large ? 18 : 14,
              ),
              SizedBox(width: large ? 5 : 4),
              Text(
                "$count",
                style: TextStyle(
                  color: Colors.white,
                  fontSize: large ? 15 : 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Holds the claim on the browser's context menu while the pointer is over a
/// tile whose own menu [offers] something (issue #290), see
/// [BrowserMenuClaim]: elsewhere — and on a tile without a menu — a secondary
/// click keeps the browser's menu.
class TileMenuHold extends StatefulWidget {
  final bool offers;
  final Widget child;

  const TileMenuHold({super.key, required this.offers, required this.child});

  @override
  State<TileMenuHold> createState() => _TileMenuHoldState();
}

class _TileMenuHoldState extends State<TileMenuHold> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) => MouseRegion(
        opaque: false,
        hitTestBehavior: HitTestBehavior.translucent,
        onEnter: (_) => setState(() => _hovered = true),
        onExit: (_) => setState(() => _hovered = false),
        child: BrowserMenuHold(
          hold: widget.offers && _hovered,
          child: widget.child,
        ),
      );
}
