/// The one catalogue of what can be done with a photo of a grid (issue
/// #290): every surface showing tiles — the album in its view, edit and
/// select modes, a collection, a saved search, the search view, the view of an
/// entry of the bell, the inbox — and the viewer choose which of these actions
/// they present and how (a tile's menu, a toolbar, a selection bar), but take
/// the decision whether an action is there, its words and what it does from
/// here.
///
/// An action has two predicates: [PhotoAction.appliesTo] asks the photo
/// (a file that cannot be read has no picture to turn or crop, a photo of a
/// collection that is gone has nothing to open), [PhotoAction.allowedFor] the
/// view it is shown in ([PhotoFacts]: which view, which mode, the rights, the
/// session). What it does is the surface's own operation, reached through
/// [PhotoActionHost], which every surface implements for the actions it can
/// be allowed. An action either works on the selection
/// ([PhotoAction.onSelection], in a mode that has one) or on the one photo
/// whose tile was asked.
///
/// The tile's menu ([showPhotoTileMenu]) is the one menu of every tile: the
/// actions that apply and are allowed, in the order of [photoActions], under
/// the key `<prefix>-<id>` the surface has always given its entries.
library;

import 'package:flutter/material.dart';

import 'app.dart';
import 'broken.dart';
import 'collections.dart';
import 'crop.dart' show posterMillisOf;
import 'l10n/app_localizations.dart';
import 'move_view.dart' show ImageSubject;
import 'resource.dart';
import 'rights.dart';
import 'routes.dart';

/// The views a photo of a grid is shown in.
enum PhotoView {
  /// An album of files.
  album,

  /// A collection: references to photos lying in other albums (#221).
  collection,

  /// A saved search (#227).
  savedSearch,

  /// The unsaved results of the search view (#243).
  search,

  /// The photographs of an entry of the bell (#268).
  news,

  /// An inbox, always selecting, writing at once (#136, #160).
  inbox,

  /// The viewer of one photo.
  viewer,
}

/// What a tap on a tile of the view means, see [PhotoFacts.mode].
enum PhotoMode {
  /// A tap opens the viewer.
  view,

  /// The album's edit mode: a tap selects, edits go into the buffer.
  edit,

  /// The select mode (#209), or the inbox: a tap selects.
  select,
}

/// Whether "Add to collection…" may be asked of photographs of a folder, the
/// rule of the server (`ImageServlet.collect`): never through a share link,
/// and otherwise by a signed-in member of the space or whoever may contribute
/// to the folder the photographs are taken from. Whether the collection
/// chosen may be written is the picker's question (`edit` on the target).
bool mayCollectFrom({
  required bool shareLink,
  required bool signedIn,
  required Rights rights,
}) =>
    !shareLink && (signedIn || rights.mayContribute);

/// Where a photo lies (issue #294): the album whose sidecar holds what is
/// written about it, and the name of its file there.
@immutable
class PhotoSource {
  /// The path of the album, in the coordinates of the view (`~owner/…` where
  /// the view reaches into another space).
  final List<String> folder;

  /// The name of the photo's file in [folder].
  final String name;

  const PhotoSource(this.folder, this.name);

  @override
  bool operator ==(Object other) =>
      other is PhotoSource &&
      other.name == name &&
      other.folder.join("/") == folder.join("/");

  @override
  int get hashCode => Object.hash(folder.join("/"), name);

  @override
  String toString() => "${folder.join("/")}/$name";
}

/// The album the photo [image] of the view showing [album] at [folder] lies
/// in, the one rule of where the viewer writes (issue #294) — the rule of the
/// server, which writes a crop, a poster, a face or a description asked
/// through a collection or a search into the photo's own album, under the
/// rights of that album (#292).
///
///  * A photo found by a search or held by a collection names its album
///    itself ([ImagePart.ref], answered to a member alone): that album, in
///    the space the view stands in, and the file's own name there.
///  * Without that name such a photo has no album the app could write to —
///    nobody is told where it lies who could write there.
///  * Every other photo — of an album, of an inbox, of the copy of an album
///    an entry of the bell shows — lies in [folder] under its own name.
///
/// `null` where nothing is known: no folder, or no reference.
PhotoSource? photoSourceOf(
  ImagePart image, {
  AlbumInfo? album,
  List<String>? folder,
}) {
  var ref = image.ref;
  if (ref != null && ref.path.isNotEmpty) {
    var segments = ref.path.split("/");
    var space = folder != null && spaceOwnerOf(folder) != null
        ? [folder.first]
        : const <String>[];
    return PhotoSource(
      [...space, ...segments.sublist(0, segments.length - 1)],
      segments.last,
    );
  }
  var kind = album?.kind;
  if (kind == AlbumKind.collection || kind == AlbumKind.search) {
    return null;
  }
  return folder == null ? null : PhotoSource(folder, image.name);
}

/// What a view says about itself to the catalogue, see [PhotoAction.allowedFor].
@immutable
class PhotoFacts {
  /// The view the photos are shown in.
  final PhotoView view;

  /// What a tap on a tile means there.
  final PhotoMode mode;

  /// The rights on the folder shown, as offered to this caller.
  final Rights rights;

  /// Whether the server named the caller: a signed-in member of the space.
  final bool signedIn;

  /// Whether the view is seen through a share link.
  final bool shareLink;

  /// Whether the view is a "view as" preview, on which nothing is done.
  final bool previewing;

  /// Whether the view offers its select mode (#209), its own rule.
  final bool maySelect;

  /// Whether the view offers its edit mode, its own rule.
  final bool mayEnterEdit;

  /// Whether the view knows the album its photo lies in ([photoSourceOf]):
  /// the viewer's question alone, which writes a crop, a poster or a face
  /// there at once (issue #294). For the viewer [rights] are the rights on
  /// that album — the album's own where the photo lies in the album shown,
  /// and otherwise those of a member (the only caller told where a photo
  /// lies), which are the same in every folder of the space.
  final bool knowsSource;

  /// Whether the space looks for faces, so that faces can be named (#147).
  final bool findsFaces;

  /// Whether the photo lies in an inbox, which keeps no description (#136).
  final bool inInbox;

  const PhotoFacts({
    required this.view,
    required this.mode,
    required this.rights,
    this.signedIn = false,
    this.shareLink = false,
    this.previewing = false,
    this.maySelect = false,
    this.mayEnterEdit = false,
    this.knowsSource = false,
    this.findsFaces = false,
    this.inInbox = false,
  });

  /// Whether the actions on several photos act on the selection: in a mode
  /// that has one.
  bool get onSelection => mode != PhotoMode.view;

  /// Whether the photographs shown lie in other albums.
  bool get showsOtherAlbums =>
      view == PhotoView.collection ||
      view == PhotoView.savedSearch ||
      view == PhotoView.search;

  /// Whether the sidecar of the folder may be written: `edit`, no link.
  bool get mayWrite => rights.mayEdit && !shareLink;

  /// Whether photographs may be taken out of an inbox: `edit` or
  /// `contribute`, no link (#135).
  bool get mayTakeOut => (rights.mayEdit || rights.mayContribute) && !shareLink;

  /// See [mayCollectFrom].
  bool get mayCollect => mayCollectFrom(
        shareLink: shareLink,
        signedIn: signedIn,
        rights: rights,
      );

  /// Whether the edits of a tile's tools are allowed: the album's edit mode,
  /// or an inbox to whoever may write it.
  bool get edits =>
      (mode == PhotoMode.edit && view != PhotoView.inbox) ||
      (view == PhotoView.inbox && mayWrite);

  /// Whether the viewer writes into the album its photo lies in, at once
  /// (issue #294): it knows that album ([knowsSource]) and may write it.
  /// Wherever the viewer was opened from — the album, a collection, a saved
  /// or an unsaved search, an entry of the bell — and whether or not the
  /// album's edit session is on: a crop, a poster and a face are written at
  /// once, and need none.
  bool get writesSource => view == PhotoView.viewer && knowsSource && mayWrite;

  /// Whether the properties dialog edits the description, rather than only
  /// showing what the photo is: in the viewer whoever [writesSource] (the
  /// edit session decides only where the change goes), on a tile the edit
  /// mode; never in an inbox (#136).
  bool get editsProperties => view == PhotoView.viewer
      ? writesSource && !inInbox
      : mode == PhotoMode.edit && view != PhotoView.inbox;
}

/// The tile a menu was opened on: the part it stands for (a group or one
/// photo) and the photo it shows.
@immutable
class PhotoTile {
  final AbstractImage part;
  final ImagePart image;

  const PhotoTile(this.part, this.image);
}

/// What a surface does for the actions of the catalogue, see the library
/// comment. An operation a surface is never allowed (by its [photoFacts])
/// is never called; the defaults say so where it would be.
abstract mixin class PhotoActionHost {
  /// The context the actions open their dialogs in.
  BuildContext get photoContext;

  /// What the view says about itself.
  PhotoFacts get photoFacts;

  /// How many photos an action on the selection acts on, asked by its label.
  int get actedOnCount;

  /// The router, for "Show in album".
  VAlbumRouterDelegate? get photoDelegate;

  /// The album the photo of [tile] lies in, in the coordinates of the view
  /// ([photoSourceOf]): where "Show in album" goes (issue #301); `null` where
  /// the view does not know it.
  PhotoSource? sourceOfPhoto(PhotoTile tile) => null;

  /// What an operation never called answers, see the class comment.
  Never _unsupported() => throw UnimplementedError();

  void openPhoto(PhotoTile tile) => _unsupported();

  void selectPhoto(PhotoTile tile) => _unsupported();

  void editPhoto(PhotoTile tile) => _unsupported();

  Future<void> movePhotos(PhotoTile tile) => _unsupported();

  Future<void> deletePhotos(PhotoTile tile) => _unsupported();

  Future<void> labelPhotos(PhotoTile tile) => _unsupported();

  Future<void> collectPhotos(PhotoTile? tile) => _unsupported();

  Future<void> removePhotosFromCollection(PhotoTile tile) => _unsupported();

  Future<void> downloadPhotos(PhotoTile? tile) => _unsupported();

  Future<void> adjustPhotoTime(PhotoTile tile) => _unsupported();

  Future<void> showPhotoProperties(PhotoTile tile) => _unsupported();

  Future<void> cropPhotoOf(PhotoTile tile) => _unsupported();

  Future<void> usePhotoAsPoster(PhotoTile tile) => _unsupported();

  Future<void> resetPhotoPoster(PhotoTile tile) => _unsupported();

  Future<void> namePersonsOf(PhotoTile tile) => _unsupported();
}

/// One action of the catalogue, see the library comment.
class PhotoAction {
  /// The name of the action, the last part of its menu entry's key.
  final String id;

  /// Its icon, where a surface shows one.
  final IconData icon;

  /// Whether it acts on the selection in a mode that has one; otherwise on
  /// the photo of the tile alone.
  final bool onSelection;

  final String Function(AppLocalizations l10n, PhotoFacts facts, int count)
      _label;

  final bool Function(ImagePart image) _applies;

  final bool Function(PhotoFacts facts) _allowed;

  final Future<void> Function(PhotoActionHost host, PhotoTile tile) _run;

  const PhotoAction._({
    required this.id,
    required this.icon,
    required this.onSelection,
    required String Function(AppLocalizations, PhotoFacts, int) label,
    required bool Function(ImagePart) applies,
    required bool Function(PhotoFacts) allowed,
    required Future<void> Function(PhotoActionHost, PhotoTile) run,
  })  : _label = label,
        _applies = applies,
        _allowed = allowed,
        _run = run;

  /// Whether the action means something for [image].
  bool appliesTo(ImagePart image) => _applies(image);

  /// Whether the view described by [facts] offers the action; never on a
  /// "view as" preview.
  bool allowedFor(PhotoFacts facts) => !facts.previewing && _allowed(facts);

  /// Whether a tile showing [image] in the view of [facts] offers it.
  bool offeredOn(ImagePart image, PhotoFacts facts) =>
      appliesTo(image) && allowedFor(facts);

  /// The words of the entry, for [count] photos acted on.
  String label(AppLocalizations l10n, PhotoFacts facts, int count) =>
      _label(l10n, facts, count);

  /// Does it, for the tile [tile].
  Future<void> run(PhotoActionHost host, PhotoTile tile) => _run(host, tile);

  /// The key of its entry in a menu of the surface keyed by [prefix].
  Key keyIn(String prefix) => Key("$prefix-$id");

  // --- The catalogue. ---

  /// Opens the viewer on the photo.
  static final PhotoAction open = PhotoAction._(
    id: "open",
    icon: Icons.open_in_full,
    onSelection: false,
    label: (l10n, _, __) => l10n.open,
    applies: (image) => !image.missing,
    allowed: (facts) => facts.mode != PhotoMode.edit,
    run: (host, tile) async => host.openPhoto(tile),
  );

  /// Enters the select mode with the photo selected (#209, #243).
  static final PhotoAction select = PhotoAction._(
    id: "select",
    icon: Icons.check_circle_outline,
    onSelection: false,
    label: (l10n, _, __) => l10n.select,
    applies: (_) => true,
    allowed: (facts) => facts.mode == PhotoMode.view && facts.maySelect,
    run: (host, tile) async => host.selectPhoto(tile),
  );

  /// Enters the edit mode with the photo selected.
  static final PhotoAction edit = PhotoAction._(
    id: "edit",
    icon: Icons.edit,
    onSelection: false,
    label: (l10n, _, __) => l10n.tileEditAction,
    applies: (_) => true,
    allowed: (facts) => facts.mode == PhotoMode.view && facts.mayEnterEdit,
    run: (host, tile) async => host.editPhoto(tile),
  );

  /// Moves the selection into another album (#47, #121); never out of a
  /// collection or a search, whose photos lie elsewhere.
  static final PhotoAction move = PhotoAction._(
    id: "move",
    icon: Icons.drive_file_move,
    onSelection: true,
    label: (l10n, _, count) =>
        l10n.moveSubjectTo(ImageSubject(count).asked(l10n)),
    applies: (_) => true,
    allowed: (facts) => facts.view == PhotoView.inbox
        ? facts.mayTakeOut
        : facts.view == PhotoView.album && facts.mode == PhotoMode.edit,
    run: (host, tile) => host.movePhotos(tile),
  );

  /// Puts the selection into the trash (#152, #160): rated as trash by an
  /// editor, into the trash folder by a contributor of an inbox. Never in a
  /// collection or a search, which have no trash of their own (#221).
  static final PhotoAction delete = PhotoAction._(
    id: "delete",
    icon: Icons.delete_outline,
    onSelection: true,
    label: (l10n, _, count) =>
        l10n.deleteSubjectAction(ImageSubject(count).asked(l10n)),
    applies: (image) => !image.missing,
    allowed: (facts) => facts.view == PhotoView.inbox
        ? facts.mayTakeOut
        : facts.view == PhotoView.album && facts.mode == PhotoMode.edit,
    run: (host, tile) => host.deletePhotos(tile),
  );

  /// Labels the selection (#213), into the edit buffer.
  static final PhotoAction labels = PhotoAction._(
    id: "label",
    icon: Icons.label_outline,
    onSelection: true,
    label: (l10n, _, __) => l10n.labelSelectionAction,
    applies: (_) => true,
    allowed: (facts) =>
        facts.mode == PhotoMode.edit && facts.view != PhotoView.inbox,
    run: (host, tile) => host.labelPhotos(tile),
  );

  /// Adds the selection, or the photo, to a collection (#221), see
  /// [mayCollectFrom].
  static final PhotoAction collect = PhotoAction._(
    id: "collect",
    icon: Icons.collections_bookmark_outlined,
    onSelection: true,
    label: (l10n, facts, count) => facts.onSelection
        ? l10n.addCountToCollection(count)
        : l10n.addToCollection,
    applies: (image) => image.hasPicture,
    // The viewer collects from the album the photo lies in (#294).
    allowed: (facts) =>
        (facts.view != PhotoView.viewer || facts.knowsSource) &&
        facts.mayCollect,
    run: (host, tile) => host.collectPhotos(tile),
  );

  /// Takes the selection out of the collection (#221).
  static final PhotoAction removeFromCollection = PhotoAction._(
    id: "remove-from-collection",
    icon: Icons.remove_circle_outline,
    onSelection: true,
    label: (l10n, _, __) => l10n.removeFromCollection,
    applies: (_) => true,
    allowed: (facts) =>
        facts.view == PhotoView.collection && facts.mode == PhotoMode.edit,
    run: (host, tile) => host.removePhotosFromCollection(tile),
  );

  /// Downloads the originals of the selection, or of the photo (#164, #209).
  static final PhotoAction download = PhotoAction._(
    id: "download",
    icon: Icons.download,
    onSelection: true,
    label: (l10n, facts, count) =>
        facts.onSelection ? l10n.downloadSelection(count) : l10n.viewerDownload,
    applies: (image) => !image.missing,
    allowed: (facts) => facts.rights.mayDownload,
    run: (host, tile) => host.downloadPhotos(tile),
  );

  /// Opens the album the photo lies in, with the photo in view and marked
  /// (#289): for a photo of a collection or a search, whose source the
  /// server names to a member alone ([ImagePart.ref]) — whoever is not told
  /// where it lies is not offered the way there.
  static final PhotoAction showInAlbum = PhotoAction._(
    id: "show-in-album",
    icon: Icons.photo_album_outlined,
    onSelection: false,
    label: (l10n, _, __) => l10n.showInAlbum,
    applies: (image) => !image.missing && sourceFolderOf(image) != null,
    allowed: (facts) =>
        !facts.shareLink &&
        (facts.showsOtherAlbums || facts.view == PhotoView.viewer),
    run: (host, tile) async {
      var delegate = host.photoDelegate;
      var source = host.sourceOfPhoto(tile);
      if (delegate != null && source != null) {
        showPhotoInAlbum(delegate, source);
      }
    },
  );

  /// Shows what the photo is (#122), to everybody; edits its description
  /// where [PhotoFacts.editsProperties] (#294).
  static final PhotoAction properties = PhotoAction._(
    id: "properties",
    icon: Icons.notes,
    onSelection: false,
    label: (l10n, _, __) => l10n.imageProperties,
    applies: (image) => !image.missing,
    allowed: (_) => true,
    run: (host, tile) => host.showPhotoProperties(tile),
  );

  /// Corrects the recording time of the selection (#77); a file that cannot
  /// be read has none (#253).
  static final PhotoAction time = PhotoAction._(
    id: "time",
    icon: Icons.more_time,
    onSelection: true,
    label: (l10n, _, __) => l10n.adjustRecordingTimeAction,
    applies: (image) => image.hasPicture,
    allowed: (facts) => facts.edits,
    run: (host, tile) => host.adjustPhotoTime(tile),
  );

  /// Crops the photo, whatever is selected (#212): on a tile in the edit
  /// mode, in the viewer wherever it [PhotoFacts.writesSource] (#294).
  static final PhotoAction crop = PhotoAction._(
    id: "crop",
    icon: Icons.crop,
    onSelection: false,
    label: (l10n, _, __) => l10n.cropMenu,
    applies: (image) => image.kind == ImageKind.image && image.hasPicture,
    allowed: (facts) => facts.edits || facts.writesSource,
    run: (host, tile) => host.cropPhotoOf(tile),
  );

  // --- The viewer's alone: never on a tile, see [viewerActions]. ---

  /// Makes the moment the player shows the poster of the video (#285).
  static final PhotoAction usePoster = PhotoAction._(
    id: "use-as-poster",
    icon: Icons.photo_size_select_actual_outlined,
    onSelection: false,
    label: (l10n, _, __) => l10n.posterUse,
    applies: (image) => image.kind != ImageKind.image && image.hasPicture,
    allowed: (facts) => facts.writesSource,
    run: (host, tile) => host.usePhotoAsPoster(tile),
  );

  /// Takes the chosen poster of the video away (#285).
  static final PhotoAction resetPoster = PhotoAction._(
    id: "reset-poster",
    icon: Icons.restart_alt,
    onSelection: false,
    label: (l10n, _, __) => l10n.posterReset,
    applies: (image) =>
        image.kind != ImageKind.image &&
        image.hasPicture &&
        posterMillisOf(image) > 0,
    allowed: (facts) => facts.writesSource,
    run: (host, tile) => host.resetPhotoPoster(tile),
  );

  /// Names the faces of the photo (#147), in the album it lies in (#294).
  static final PhotoAction namePersons = PhotoAction._(
    id: "edit-persons",
    icon: Icons.people_outline,
    onSelection: false,
    label: (l10n, _, __) => l10n.viewerEditPersons,
    applies: (image) => image.kind == ImageKind.image && image.hasPicture,
    allowed: (facts) => facts.writesSource && facts.findsFaces,
    run: (host, tile) => host.namePersonsOf(tile),
  );
}

/// Every action of the catalogue, in the order a tile's menu lists them.
final List<PhotoAction> photoActions = [
  PhotoAction.open,
  PhotoAction.select,
  PhotoAction.edit,
  PhotoAction.move,
  PhotoAction.delete,
  PhotoAction.labels,
  PhotoAction.collect,
  PhotoAction.removeFromCollection,
  PhotoAction.download,
  PhotoAction.showInAlbum,
  PhotoAction.properties,
  PhotoAction.time,
  PhotoAction.crop,
];

/// The actions of the viewer's menu, in its order (issue #294): it shows
/// those [PhotoAction.offeredOn] its photo, the same wherever it was opened
/// from.
final List<PhotoAction> viewerActions = [
  PhotoAction.download,
  PhotoAction.crop,
  PhotoAction.showInAlbum,
  PhotoAction.collect,
  PhotoAction.usePoster,
  PhotoAction.resetPoster,
  PhotoAction.namePersons,
  PhotoAction.properties,
];

/// The actions a tile showing [image] offers in the view of [facts].
List<PhotoAction> tileActions(ImagePart image, PhotoFacts facts) => [
      for (var action in photoActions)
        if (action.offeredOn(image, facts)) action,
    ];

/// The menu of the tile [tile] of [host], at [position] (issues #156, #222,
/// #243, #290): the actions it offers, each acting on the selection where the
/// mode has one (the caller has made the tile part of it beforehand), on the
/// tile alone otherwise. Its entries are keyed `<keyPrefix>-<id>`.
Future<void> showPhotoTileMenu({
  required PhotoActionHost host,
  required PhotoTile tile,
  required Offset position,
  required String keyPrefix,
}) async {
  var context = host.photoContext;
  var facts = host.photoFacts;
  var actions = tileActions(tile.image, facts);
  if (actions.isEmpty) {
    return;
  }
  var l10n = AppLocalizations.of(context)!;
  var count = host.actedOnCount;
  var overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
  var chosen = await showMenu<PhotoAction>(
    context: context,
    position: RelativeRect.fromRect(
      position & Size.zero,
      Offset.zero & overlay.size,
    ),
    items: [
      for (var action in actions)
        PopupMenuItem<PhotoAction>(
          key: action.keyIn(keyPrefix),
          value: action,
          child: Text(
            action.label(l10n, facts,
                action.onSelection && facts.onSelection ? count : 1),
          ),
        ),
    ],
  );
  if (chosen == null || !context.mounted) {
    return;
  }
  await chosen.run(host, tile);
}

/// Opens the album [source] names — where a photo lies, [photoSourceOf] —
/// with the photo brought into view and marked, see
/// [VAlbumRouterDelegate.markIn] (issue #289); in the space the view stands
/// in, its `~owner` prefix kept (issue #301).
void showPhotoInAlbum(VAlbumRouterDelegate delegate, PhotoSource source) {
  delegate.markIn(source.folder, source.name);
  delegate.go(ListingOrAlbumRoute(source.folder));
}

/// The gestures of every photo tile (issue #290): what a tap means is the
/// mode's ([onTap]); a long press is the mode's switch or toggle where
/// [onLongPress] is given, and otherwise opens the menu; a secondary click
/// always opens the menu ([onMenu], at the pointer).
class PhotoTileGestures extends StatelessWidget {
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final void Function(Offset globalPosition)? onMenu;
  final HitTestBehavior? behavior;
  final Widget child;

  const PhotoTileGestures({
    super.key,
    required this.onTap,
    required this.onLongPress,
    required this.onMenu,
    this.behavior,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    var menu = onMenu;
    return GestureDetector(
      behavior: behavior,
      onTap: onTap,
      onLongPress: onLongPress,
      onLongPressStart: onLongPress == null && menu != null
          ? (details) => menu(details.globalPosition)
          : null,
      onSecondaryTapUp:
          menu == null ? null : (details) => menu(details.globalPosition),
      child: child,
    );
  }
}
