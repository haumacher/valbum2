/// The album view: the row layout of the images, the rating filter, the edit
/// mode with the per-tile editor and the album properties editor.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'about.dart';
import 'action_guard.dart';
import 'album_date.dart';
import 'album_edit.dart';
import 'album_labels.dart';
import 'album_model.dart';
import 'album_stacks.dart';
import 'app.dart';
import 'broken.dart';
import 'cache_refresh.dart';
import 'caller.dart';
import 'client.dart';
import 'collections.dart';
import 'crop.dart';
import 'crop_editor.dart';
import 'dialog_style.dart';
import 'downloads.dart';
import 'drag_scroll.dart';
import 'form_dialog.dart';
import 'group_by_dialog.dart';
import 'image_properties.dart';
import 'key_guard.dart';
import 'kept_photos.dart';
import 'keyboard_scroll.dart';
import 'label_dialog.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart';
import 'news.dart';
import 'move_view.dart';
import 'name_date.dart';
import 'resource.dart';
import 'return_reveal.dart';
import 'not_ready.dart';
import 'offline.dart';
import 'persons_view.dart';
import 'photo_actions.dart';
import 'oriented_thumbnail.dart';
import 'routes.dart';
import 'search_grouping.dart' show albumOfHeading;
import 'search_query.dart';
import 'rights.dart';
import 'select_mode.dart';
import 'selectable.dart';
import 'settings.dart';
import 'share_session.dart';
import 'share_view.dart';
import 'thumbnails.dart';
import 'tile_keys.dart';
import 'trash_view.dart' show hasTrashedImages, trashRating;
import 'video_view.dart';
import 'page_app_bar.dart';

/// The clearance an album is shown with in the edit mode, see issue #46.
///
/// [owner] is the album as this device is entitled to see it — the normal edit
/// view. The other two ask the server to answer as it would for a caller of
/// that clearance (`?viewAs=members`, `?viewAs=public`), so that the author
/// can see what the family, or a share link, will be shown.
enum ViewAs {
  owner(Icons.edit),
  members(Icons.group),
  public(Icons.public);

  /// The icon the app shows this view by.
  final IconData icon;

  const ViewAs(this.icon);

  /// What the app calls this view, as the menu entry choosing it reads.
  String label(AppLocalizations l10n) => switch (this) {
        ViewAs.owner => l10n.viewAsYourself,
        ViewAs.members => l10n.viewAsMembers,
        ViewAs.public => l10n.viewAsPublic,
      };

  /// The same view named in the line that says which one is on the screen:
  /// "View as — yourself", see [AlbumContentState.albumMenu] (issue #100).
  String state(AppLocalizations l10n) => switch (this) {
        ViewAs.owner => l10n.viewAsStateYourself,
        ViewAs.members => l10n.viewAsStateMembers,
        ViewAs.public => l10n.viewAsStatePublic,
      };

  /// The value of the `viewAs` request parameter, `null` for the [owner],
  /// whose request carries none.
  String? get parameter => this == ViewAs.owner ? null : name;
}

/// A menu entry like the app's [menuItem], carrying a key.
///
/// The shared helper takes none, and the menus of the app are found by their
/// text. The entries of issues #98 and #100 are addressed by key instead —
/// "Refresh previews" and the three "View as" choices, whose text is what the
/// user reads and may be translated — so they are built here rather than
/// changing what every menu of the app is built from.
PopupMenuItem<void Function(BuildContext)> keyedMenuItem(
  Key key,
  IconData icon,
  String text,
  void Function(BuildContext) action,
) =>
    PopupMenuItem<void Function(BuildContext)>(
      key: key,
      value: action,
      child: Row(
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Icon(icon, color: Colors.blueAccent),
          ),
          // Wraps instead of overflowing the menu at a long text.
          Flexible(child: Text(text)),
        ],
      ),
    );

/// Makes Flutter forget every thumbnail it has decoded (issue #98).
///
/// The whole cache, not the album's own entries: a tile that knows the height
/// it is drawn at resolves its picture through a [ResizeImage] whose key is
/// built from that height times the device's pixel ratio (see
/// `resizedThumbnail`), and neither the heights of a laid-out album nor the
/// keys held by the cache can be enumerated from here — a per-image eviction
/// would therefore miss exactly the tiles that are on the screen, which is the
/// one thing it has to hit.
///
/// [ImageCache.clear] alone is not enough either: a mounted tile holds a
/// listener on its stream (see `thumbnails.dart`), which makes its image a
/// *live* one, and a live image is handed out again without being fetched.
/// Both halves therefore go — and the encoded bytes the tiles are decoded
/// from (`ThumbnailByteCache`, issue #225), or the new decodings would be
/// made of the old pictures.
///
/// The cost is that the listing's index pictures and the albums visited before
/// are decoded again as they are shown. That is a few dozen decodes, once, for
/// an action an administrator invokes when something is visibly broken.
void forgetDecodedThumbnails() {
  var cache = PaintingBinding.instance.imageCache;
  cache.clear();
  cache.clearLiveImages();
  forgetThumbnailBytes();
}

/// The icon the app shows the given privacy level by, `null` for
/// [privacyPublic] — an image everybody may see is not marked at all.
IconData? privacyIcon(int level) => switch (level) {
      privacyPublic => null,
      privacyMembers => Icons.group,
      _ => Icons.lock,
    };

/// The icon of the privacy control, which shows every level, [privacyPublic]
/// included.
IconData privacyControlIcon(int level) => privacyIcon(level) ?? Icons.public;

/// What is to become of the unsaved changes when an album being edited is
/// left, see [AlbumContentState.confirmLeave] (issue #99).
enum LeaveEdit {
  /// Write the album back and leave once the server has it.
  save,

  /// Leave and throw the changes away.
  discard,

  /// Do not leave; the edit goes on.
  stay,
}

/// The icon of the action gathering the camera group of [image] (issue #274).
IconData selectSameCameraIcon(ImagePart image) =>
    image.camera.isEmpty ? Icons.no_photography : Icons.photo_camera;

class AlbumContent extends StatefulWidget {
  final VAlbumState albumState;
  final AlbumInfo album;
  final String baseUrl;
  final Future Function(AbstractImage image, String name) pushPart;

  /// Told when the select mode is entered (`true`) or left (`false`), so that
  /// the search view hides its own bar while the mode's bar is shown (issue
  /// #243); `null` in an album.
  final ValueChanged<bool>? onSelectModeChanged;

  /// The download of the selected photographs where the page above does it
  /// itself — the search view, whose names are paths below the folder
  /// searched (issue #243); `null` in an album, which downloads its own.
  final Future<void> Function(List<String> names)? downloadSelected;

  /// The collection of the named photographs, offered in the select mode's
  /// bar and in a tile's menu where it is given — the search view (issue
  /// #243); `null` in an album.
  final Future<void> Function(List<String> names)? collectSelected;

  /// Shown above the photographs and scrolled away with them, below the
  /// title row the floating controls stand in: the criteria of the search
  /// view, which would otherwise take a phone's screen (issue #248); `null`
  /// in an album.
  final Widget? header;

  /// Whether the photographs are shown dimmed, the [header] not: the search
  /// view's answer while a newer search is on its way, stale but kept in
  /// view (issue #302).
  final bool dimmed;

  const AlbumContent(
    this.albumState,
    this.album,
    this.baseUrl,
    this.pushPart, {
    super.key,
    this.onSelectModeChanged,
    this.downloadSelected,
    this.collectSelected,
    this.header,
    this.dimmed = false,
  });

  @override
  State<StatefulWidget> createState() {
    return AlbumContentState();
  }
}

class AlbumContentState extends State<AlbumContent>
    with
        SingleTickerProviderStateMixin,
        ReturnReveal<AlbumContent>,
        PhotoActionHost {
  /// The edit in progress: the mode, the selection and the click anchor.
  ///
  /// It lives with the router, not with this widget, so that a trip into an
  /// image or into the alternatives of a group and back finds the album still
  /// being edited, see [AlbumEditSession].
  ///
  /// The results of the search view have a session of their own, kept under
  /// [VAlbumState.statePath]: the search of an album (issue #249) stands on
  /// the album's path, and what is selected among the results is no selection
  /// of the album beneath (issue #265).
  AlbumEditSession get session => widget.albumState.navigator.delegate
      .editSession(widget.albumState.statePath);

  /// Whether the album is being edited *and* shown as its owner.
  ///
  /// A "view as" preview is read-only: the tile editor, the reordering and the
  /// tile toolbars are all off while it is shown, see [previewing]. The edit
  /// session itself stays on, so the switch back to the owner's view is still
  /// there and the album is still the one being edited.
  /// Never inside a share link: a link is not an account, and the app offers
  /// nothing it would have to refuse, see issue #51.
  bool get editMode => session.editMode && rights.mayEdit;
  set editMode(bool value) => session.editMode = value;

  /// What the caller may do with this album, as the server answered it with
  /// the album itself, see issue #49.
  ///
  /// Read from the album that was loaded, never from a "view as" preview: the
  /// preview is a smaller album, and what the caller may do does not change
  /// because they are looking at somebody else's view of it.
  ///
  /// Where the server answered no rights at all, the caller's role decides
  /// what is offered instead, and nothing is edited through a share link, see
  /// [rightsOf] and issues #85, #293. `peek`, not a dependency: this is read
  /// from [initState] as well, and the view is a dependent of the caller
  /// scope through [didChangeDependencies] anyway, so the answer arriving
  /// rebuilds it.
  ///
  /// What the caller *holds*, which is what the sharing notice says and what
  /// sharing and searching ask; what may be *done* on the screen is
  /// [rights], which a "view as" preview narrows.
  Rights get heldRights => rightsOf(context, widget.album, listen: false);

  /// What may be done on the screen: [heldRights], and nothing at all while
  /// a "view as" preview is shown ([rightsOf]) — the edit mode, the tools
  /// and the download are all off over it.
  Rights get rights =>
      rightsOf(context, widget.album, previewing: previewing, listen: false);

  /// What the caller may do and see, as the server said it (issue #85).
  ///
  /// `peek`, not a dependency: this is read from [initState] as well, and the
  /// view is a dependent of the caller scope through [didChangeDependencies]
  /// anyway, so the answer arriving rebuilds it.
  CallerPermission get _permission =>
      CallerInfo.peek(context)?.permission ?? CallerPermission.unknown;

  /// The line saying that this album belongs to somebody else, `null` while
  /// the caller is its owner.
  String? get sharedLine =>
      sharingNotice(_l10n, widget.albumState.path, heldRights);

  /// The words this view reads in.
  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  /// The share link this album is being looked at through, `null` in an
  /// ordinary session, see issue #51.
  ///
  /// Read in [didChangeDependencies], not on every access: the state is asked
  /// for it while it is being disposed as well.
  ShareSession? share;

  /// Whether the caller may hand out a link to this album (issues #83/#85).
  ///
  /// No request of its own: the caller's own permission says whether they may
  /// share at all, and the album's rights say the rest, see [mayShareFolder].
  bool get _mayShare =>
      share == null &&
      !isSearchView &&
      mayShareFolder(
        client,
        widget.albumState.path,
        heldRights,
        // A caller nobody named is offered it as before, see issue #85.
        mayShare: _permission.mayShare || !_permission.named,
      );

  /// Whether the album carries edits that have not been written back yet.
  bool get dirty => session.dirty;

  /// The clearance the album is shown with, see [setViewAs] (issue #46).
  ViewAs _viewAs = ViewAs.owner;

  ViewAs get viewAs => _viewAs;

  /// The album the server answered for [_viewAs], `null` while the album is
  /// shown as its owner.
  AlbumInfo? _preview;

  /// Whether a "view as" preview is shown instead of the album itself.
  bool get previewing => _preview != null;

  /// The album on the screen: the preview while one is shown, the album being
  /// edited otherwise.
  AlbumInfo get shownAlbum => _preview ?? widget.album;

  /// The selected album parts (an [ImageGroup] is selected as a whole).
  Set<AlbumPart> get selection => session.selection;

  /// The part clicked last, the anchor of a shift-click range selection.
  AlbumPart? get lastClicked => session.lastClicked;
  set lastClicked(AlbumPart? value) => session.lastClicked = value;

  /// The orientation each image had when the album was laid out.
  ///
  /// The row layout is computed from these, not from the (possibly just
  /// rotated) orientation of the model: rotating an image in the tile editor
  /// must not reflow the album under the user's hands. The rotated image is
  /// scaled into the tile box it already has; the layout follows on the next
  /// load of the album. This is the `setDownScale` of the retired GWT client.
  final Map<ImagePart, Orientation> _layoutOrientation = {};

  /// The album parts in the order their tiles are laid out on the page.
  ///
  /// The row layout is free to show a portrait image *before* the landscape
  /// image it follows in [AlbumInfo.parts] (see `album_layout.dart`), so the
  /// drop gesture of the reordering (issue #37) cannot read the part before
  /// the insert cursor off the stored order. It reads it off this list, which
  /// [buildParts] fills while it turns the layout into rows: the headings in
  /// their place and, between them, the images in the order the rows show
  /// them, row by row and left to right.
  final List<AlbumPart> _displayOrder = [];

  /// The parts a drag in progress carries, empty while no part is dragged.
  ///
  /// A drag that picks up a tile of the current selection carries the whole
  /// selection (issue #41), so the tile under the pointer is not the only one
  /// on its way: every carried tile is dimmed and none of them is a drop
  /// target. A tile does not see the [Draggable] that started the drag, so
  /// the set is kept here, filled when the drag starts and emptied when it
  /// ends — whether it was dropped or cancelled.
  final Set<AlbumPart> _carried = Set.identity();

  /// This view's answer to the router's question whether the album may be
  /// left, see [confirmLeave] (issue #99).
  ///
  /// One closure, held: two tear-offs of the same method need not be
  /// identical, and the registration is taken back by identity.
  late final Future<bool> Function() _leaveGuard = confirmLeave;

  /// Scrolls the album while a carried tile rests near the top or the bottom
  /// edge of the view, see [DragEdgeScroller] (issue #42).
  late final DragEdgeScroller _dragScroller;

  /// The pointer of the gesture that is currently down on the album, and where
  /// it was seen last, see [_trackPointer].
  int? _pointerId;
  PointerDeviceKind _pointerKind = PointerDeviceKind.touch;
  Offset? _pointerPosition;

  /// The rating an image needs to be shown, see [AlbumInfo.minRating].
  int get minRating => shownAlbum.minRating;

  @override
  void initState() {
    super.initState();
    _dragScroller = DragEdgeScroller(this, onScrolled: _followScrolledContent);
    // While this view is on the screen it is the one that answers what is to
    // become of the unsaved changes when the app leaves the album, see
    // [confirmLeave] and [VAlbumRouterDelegate.leaveAlbum] (issue #99).
    // Under the key of this level, not the bare path: the results of the
    // album's search must not take over the album's guard (issue #265).
    widget.albumState.navigator.delegate
        .registerLeaveGuard(widget.albumState.statePath, _leaveGuard);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    share = ShareSession.of(context);
    // The way back from a viewer opened by its address, issue #272.
    watchReturn();
    // The photo "Show in album" opened this album for, issue #289.
    _revealMarked();
    _loadStacking();
  }

  @override
  VAlbumState get revealView => widget.albumState;

  @override
  void didUpdateWidget(AlbumContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _layoutOrientation.clear();
      selection.clear();
      lastClicked = null;
    }
  }

  /// Lays the album out anew, after a part changed its aspect behind the
  /// edit buffer's back (a crop written at once, issue #212).
  void relayout() => setState(() {});

  /// The orientation the given image is laid out with, see
  /// [_layoutOrientation].
  Orientation layoutOrientation(ImagePart image) =>
      _layoutOrientation.putIfAbsent(image, () => image.orientation);

  /// Enters the edit mode with the given part selected — every shot of the
  /// burst where [selected] is the tile of a folded one (issue #314): the
  /// bursts stay as they are shown, folded or opened.
  ///
  /// Refused while the app is offline: an edit that could never be saved is
  /// worse than no edit at all, so the reason is said instead, see
  /// [refuseWhileOffline].
  void setEditMode(AlbumPart selected) {
    // Nothing is greyed out: a caller without `edit` is simply not offered the
    // way in, and the album stays the album, see issue #49.
    if (!rights.mayEdit) {
      return;
    }
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      editMode = true;
      // The edit mode is the whole album, every heading included (issue
      // #213): the label filter of the view is left behind with it.
      setLabelFilter(widget.album, null);
      selection
        ..clear()
        ..addAll(standsFor(selected));
      lastClicked = selected;
    });
  }

  bool isSelected(AlbumPart part) => selection.contains(part);

  bool get hasMultiSelection => selection.length > 1;

  /// Whether the given part is carried by a drag in progress, see [_carried].
  bool isCarried(AlbumPart part) => _carried.contains(part);

  /// What a drag started on the given part carries, see [DraggedParts].
  ///
  /// A tile of the current multi-selection carries the whole selection, in
  /// the order the parts are stored in; any other tile carries itself alone
  /// and leaves the selection alone. The tile of a folded burst is all of its
  /// shots (issue #314): it carries them as one block, in their stored order,
  /// or the whole selection where they are part of a larger one.
  DraggedParts dragOf(AlbumPart part) {
    var stack = _stackView.foldedOf(part);
    if (stack != null) {
      var members = Set<AlbumPart>.identity()..addAll(stack.members);
      if (!stackSelected(stack) || selection.length <= members.length) {
        return DraggedParts(
          [
            for (var candidate in widget.album.parts)
              if (members.contains(candidate)) candidate,
          ],
          part,
        );
      }
    } else if (!hasMultiSelection || !isSelected(part)) {
      return DraggedParts([part], part);
    }
    return DraggedParts(
      [
        for (var candidate in widget.album.parts)
          if (isSelected(candidate)) candidate,
      ],
      part,
    );
  }

  /// Remembers the parts a starting drag carries, see [_carried].
  void startCarry(DraggedParts dragged) {
    // The album scrolls while the carried tile rests near an edge, issue #42.
    _dragScroller.begin(albumScrollPosition, _pointerPosition);
    setState(() {
      _carried
        ..clear()
        ..addAll(dragged.parts);
    });
  }

  /// Forgets the parts of a finished drag, dropped or cancelled.
  void endCarry() {
    _dragScroller.end();
    if (_carried.isNotEmpty) {
      setState(_carried.clear);
    }
  }

  /// Whether the album is scrolling under a carried tile right now.
  ///
  /// Only the tests look at this: it is the proof that the scroller stops
  /// when the pointer leaves the edge zone and when the drag ends.
  bool get dragScrolling => _dragScroller.scrolling;

  /// The scroll position of the album's own scroll view, `null` if there is
  /// none (no ambient [PrimaryScrollController], or more than one scroll view
  /// attached to it — then it cannot be told which one is the album's).
  ScrollPosition? get albumScrollPosition {
    var controller = PrimaryScrollController.maybeOf(context);
    if (controller == null || controller.positions.length != 1) {
      return null;
    }
    return controller.positions.first;
  }

  /// Remembers the pointer that is down on the album and where it is.
  ///
  /// The drag machinery reports the pointer to the drop targets, but neither
  /// to the dragged tile's neighbours nor to the album: [Draggable.onDragUpdate]
  /// would give the position but not the pointer's identity, which the nudge
  /// of [_followScrolledContent] needs. A [Listener] over the whole album sees
  /// both — the moves of a drag in progress travel the hit test path of the
  /// pointer down that started it, and this listener is on it.
  void _trackPointer(PointerEvent event) {
    if (event is PointerDownEvent && _carried.isEmpty) {
      // The gesture that may become a drag. A second finger put down while a
      // tile is already on its way is none, and is left alone.
      _pointerId = event.pointer;
    }
    if (event.pointer != _pointerId) {
      return;
    }
    _pointerKind = event.kind;
    _pointerPosition = event.position;
    if (_carried.isNotEmpty) {
      _dragScroller.update(event.position);
    }
  }

  /// Ends the gesture the album was watching, see [_trackPointer].
  void _endGesture(PointerEvent event) {
    if (event.pointer != _pointerId) {
      return;
    }
    _pointerId = null;
    _pointerPosition = null;
    // Nothing is carried once the pointer is gone; a drag that reported its
    // own end has cleared this already and [endCarry] is idempotent.
    endCarry();
  }

  /// Lets the insert cursor follow the album scrolling under a resting pointer.
  ///
  /// [Draggable] hit tests for its drop targets only when the pointer moves
  /// (`_DragAvatar.updateDrag`), so a tile scrolling under a pointer that
  /// rests would neither take the cursor over nor take the drop: the target
  /// found last would keep both. A pointer move with a zero delta, routed to
  /// the drag's gesture recognizer, makes the drag look at what is under the
  /// pointer again, which is exactly what is needed and nothing more.
  ///
  /// It has to wait for the end of the frame: the scroll offset is set from a
  /// ticker callback, so the viewport has not been laid out at its new offset
  /// yet, and a hit test before that would still find the old tile.
  void _followScrolledContent() {
    var pointer = _pointerId;
    var position = _pointerPosition;
    if (pointer == null || position == null) {
      return;
    }
    var kind = _pointerKind;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _carried.isEmpty) {
        return;
      }
      GestureBinding.instance.pointerRouter.route(
        PointerMoveEvent(
          pointer: pointer,
          kind: kind,
          position: position,
          delta: Offset.zero,
          synthesized: true,
        ),
      );
    });
  }

  @override
  void dispose() {
    widget.albumState.navigator.delegate
        .unregisterLeaveGuard(widget.albumState.statePath, _leaveGuard);
    _dragScroller.dispose();
    // The search view hides its bar while the select mode's is shown (issue
    // #243): results gone in the mode give it back, after the frame — the
    // tree is locked while this view is taken out of it.
    var selectModeChanged = widget.onSelectModeChanged;
    if (_selecting && selectModeChanged != null) {
      WidgetsBinding.instance
          .addPostFrameCallback((_) => selectModeChanged(false));
    }
    // A page left in the middle of an edit gives its claim on the browser's
    // menu back: the edit session outlives this view, the claim must not
    // (issue #156).
    _browserMenu.release();
    tileHighlight.dispose();
    super.dispose();
  }

  /// This view's claim on the browser's own context menu, see
  /// [_syncBrowserMenu] and [BrowserMenuClaim].
  final BrowserMenuClaim _browserMenu = BrowserMenuClaim();

  /// Holds the claim on the browser's context menu while the tiles have a
  /// menu of their own (issues #156, #290), the counted [BrowserMenuClaim] of
  /// the persons editor (#144): on the web a secondary click on a tile would
  /// otherwise open the browser's menu over the tile's own, see
  /// [showTileMenu].
  ///
  /// Asked on every build, because whether the tiles have a menu is not one
  /// switch but a derived state — the edit mode, a "view as" preview, what
  /// the album holds — changed in many places. Holding a claim held already
  /// is nothing; a page above holds a claim of its own, so its leaving never
  /// gives the menu back under this one.
  void _syncBrowserMenu() =>
      _browserMenu.hold(editMode || (offersTileMenu && holdsImages));

  /// Handles a click on the tile of the given part.
  ///
  /// Plain: select exactly this part, clicking the only selected one clears
  /// the selection. Ctrl/Meta: toggle. Shift: extend the selection to the
  /// range between the part clicked last and this one in the album's order,
  /// as the GWT client did. The decision is [selectionAfterTap], which the
  /// inbox takes its clicks from as well (issue #160).
  void handleTap(AlbumPart part) {
    var keyboard = HardwareKeyboard.instance;
    setState(() {
      var outcome = selectionAfterTap<AlbumPart>(
        // The album's own order, as ever: the row layout may stack a
        // portrait beside a landscape in a column, so the order its visitor
        // reports is no more "left to right" than the stored one is, and the
        // stored order is the one every reorder of this page speaks. The
        // select mode (issue #209) ranges over what is shown alone: an image
        // the rating filter or the label chip (#213) hides is not there to be
        // downloaded.
        ordered: [
          for (var stored
              in selectMode ? shownParts(shownAlbum) : widget.album.parts)
            if (stored is AbstractImage) stored,
        ],
        tapped: part,
        current: selection,
        anchor: lastClicked,
        shift: keyboard.isShiftPressed,
        ctrl: keyboard.isControlPressed || keyboard.isMetaPressed,
      );
      selection
        ..clear()
        ..addAll(outcome.selection);
      lastClicked = outcome.anchor;
    });
  }

  /// Makes the given part the whole selection, the anchor of a following
  /// shift-click included — what a secondary click on a tile outside the
  /// selection does before its menu opens (issue #156).
  void selectOnly(AlbumPart part) => setState(() {
        selection
          ..clear()
          ..add(part);
        lastClicked = part;
      });

  /// Adds or removes the given part from the selection.
  void toggleSelection(AlbumPart part) => setState(() {
        _toggle(part);
        lastClicked = part;
      });

  void _toggle(AlbumPart part) {
    if (!selection.remove(part)) {
      selection.add(part);
    }
  }

  void clearSelection() {
    selection.clear();
    lastClicked = null;
  }

  /// Applies [edit] to the model and redraws the album.
  ///
  /// Every edit made here is one the album has to be saved for, so it marks
  /// the album dirty, see [AlbumEditSession.dirty].
  void editImage(VoidCallback edit) => setState(() {
        edit();
        markDirty();
      });

  /// Rates by the rating button for [value] pressed on the tile of [part]
  /// (issue #224), into the buffer like every edit here.
  ///
  /// The button acts on the whole selection where [part] is part of a
  /// selection of several ([ratingTargets]), otherwise on [part] alone. What
  /// the pressed tile's button shows decides the new rating for all of them
  /// ([toggleRating]): its active button resets every one to unrated. A part
  /// is rated by the image its tile shows, a group by its representative, as
  /// the tile always rated it. Rating several as trash asks first
  /// ([confirmTrashing]); a part rated as trash disappears from the grid and
  /// so leaves the selection.
  Future<void> rate(AlbumPart part, int value) async {
    if (part is! AbstractImage) {
      return;
    }
    var rating = toggleRating(layouter.ToImage.toImage(part).rating, value);
    var targets = [
      for (var target in ratingTargets<AlbumPart>(part, selection))
        if (target is AbstractImage) target,
    ];
    if (rating == trashRating && !await confirmTrashingOwn(targets)) {
      return;
    }
    if (!mounted) {
      return;
    }
    editImage(() {
      for (var target in targets) {
        layouter.ToImage.toImage(target).rating = rating;
        if (!isVisiblePart(target, minMinRating)) {
          selection.remove(target);
          if (identical(lastClicked, target)) {
            lastClicked = null;
          }
        }
      }
    });
  }

  /// Asks before [targets] are rated as trash: rating as trash is deleting,
  /// and a photo other albums still show moves there instead (issue #307) —
  /// the question names those albums, asked of the server; where it keeps
  /// nothing (or cannot be asked) it is [confirmTrashing], unchanged. A
  /// reference rated as trash is taken out of the album and asked nothing
  /// more than before.
  Future<bool> confirmTrashingOwn(List<AbstractImage> targets) async {
    var names = [
      for (var target in targets)
        if (layouter.ToImage.toImage(target) case var image
            when !isReference(image) && !image.missing)
          image.name,
    ];
    var question = keptQuestion(
      _l10n,
      await keptOnDelete(context, client, widget.albumState.path, names),
      names.length,
    );
    if (!mounted) {
      return false;
    }
    return question == null
        ? confirmTrashing(context, targets.length)
        : confirmKeeping(context,
            question: question, confirm: _l10n.moveToTrash);
  }

  /// Remembers that the album differs from what the server holds.
  void markDirty() => session.dirty = true;

  /// Groups the selected parts, [representative] becoming the image shown for
  /// the group in the album, see [groupSelection].
  ///
  /// The group is opened straight away, full-screen, on the image it was made
  /// from (issue #79): which of several shots of one scene is the best is not
  /// a question tiles can answer, and the answer — "make this the
  /// representative" — is offered exactly there. The created-from image stays
  /// the representative until another is chosen.
  ///
  /// A route change, not an overlay: the album is already loaded and the
  /// router hands the *same* [AlbumInfo] to the viewer (resources are cached
  /// by album path, see `VAlbumRouterDelegate.resourceAt`), so the viewer
  /// shows the group that exists only in this editing buffer — and the
  /// group member's own URL is what the location bar shows, deep-linkable as
  /// before.
  ///
  /// Returns whether the group was created.
  bool groupSelected(AbstractImage representative) {
    var group = groupSelection(widget.album, selection, representative);
    if (group == null) {
      return false;
    }
    markDirty();
    setState(clearSelection);
    // The group is addressed by its representative, which is the image the
    // group was made from — and that is the member the viewer opens on.
    var name = group.images[group.representative].name;
    widget.albumState.navigator.go(
      MemberRoute(widget.albumState.path, name, name),
    );
    return true;
  }

  /// Dissolves the given group, its images stay selected, see [ungroup].
  void ungroupSelected(ImageGroup group) => setState(() {
        markDirty();
        var members = ungroup(widget.album, group);
        clearSelection();
        selection.addAll(members);
        lastClicked = members.isEmpty ? null : members.last;
      });

  /// Removes the given heading from the album.
  void deleteHeading(Heading heading) => setState(() {
        markDirty();
        widget.album.parts =
            widget.album.parts.where((p) => !identical(p, heading)).toList();
        selection.remove(heading);
      });

  /// Opens the heading editor prefilled with the current text and level.
  ///
  /// An empty text is refused, as in the GWT client, which ignored the save.
  /// The level is chosen in the same dialog (issue #158); a heading of an
  /// older sidecar, which carries no level, is offered as the section it is
  /// drawn as, and is written back with the level only once it is changed.
  Future<void> editHeading(Heading heading) async {
    var input = await showFormDialog<HeadingInput>(
      context: context,
      builder: (context) => HeadingDialog(
        title: _l10n.editHeadingTitle,
        text: heading.text,
        level: headingLevel(heading),
      ),
    );
    if (input == null || input.text.trim().isEmpty || !mounted) {
      return;
    }
    setState(() {
      heading.text = input.text;
      if (input.level != headingLevel(heading)) {
        heading.level = input.level;
      }
      markDirty();
    });
  }

  /// Whether the album holds an image, a tile a long press opens the edit
  /// mode on.
  bool get holdsImages =>
      widget.album.parts.any((part) => part is AbstractImage);

  /// Whether the caller may open an edit session on this album — the
  /// conditions of [setEditMode], without the offline check.
  bool get mayEnterEditMode => rights.mayEdit;

  /// Appends a heading at the end of the album, the menu entry "Add heading…"
  /// (issue #158).
  ///
  /// The way to lay out the sections of an album before any image arrives: a
  /// heading is otherwise inserted before a tile, and an empty album has
  /// none. An edit like every other one, written by Save. Because the edit
  /// mode is otherwise entered by a long press on a tile, which an album
  /// without images does not have, the entry is offered in the view mode of
  /// such an album too, to a caller who may edit, and opens the edit session
  /// with the heading in its buffer; a cancelled dialog leaves the album as it
  /// was.
  Future<void> addHeading() async {
    if (!editMode) {
      if (!mayEnterEditMode || refuseWhileOffline(context)) {
        return;
      }
    }
    var input = await showFormDialog<HeadingInput>(
      context: context,
      builder: (context) => HeadingDialog(
        title: _l10n.insertHeading,
        text: "",
        level: headingSection,
      ),
    );
    if (input == null || input.text.trim().isEmpty || !mounted) {
      return;
    }
    editImage(() {
      if (!editMode) {
        editMode = true;
        clearSelection();
      }
      // A new list: the parts of an album parsed without any are a constant.
      widget.album.parts = [
        ...widget.album.parts,
        Heading(text: input.text, level: input.level),
      ];
    });
  }

  /// Selects every image under the given heading, or takes exactly those back
  /// out where all of them are selected already — the check box of a heading
  /// in the edit mode (issue #158), the gesture of the inbox's day and month
  /// headings (#136). What stands under a heading is [imagesUnder]; a heading
  /// tap is no click on an image, so the anchor of a shift-click stays where
  /// it was.
  void toggleHeading(Heading heading) => setState(() {
        var images = imagesUnder(shownParts(widget.album), heading);
        if (images.every(selection.contains)) {
          images.forEach(selection.remove);
        } else {
          selection.addAll(images);
        }
      });

  /// Whether everything under the given heading is selected, which lights its
  /// check box; a heading with nothing under it is never lit.
  bool headingSelected(Heading heading) {
    var images = imagesUnder(shownParts(widget.album), heading);
    return images.isNotEmpty && images.every(selection.contains);
  }

  /// Orders the parts of the album by date, section by section (issue #76).
  ///
  /// The album an upload from several devices grew into is ordered on demand,
  /// never behind the author's back: the headings stay where they are and
  /// each section is sorted on its own, see [sortSectionsByDate]. The new
  /// order is an edit like every other one — it marks the album dirty and is
  /// written back by the save action, so it can be looked at and discarded.
  ///
  /// An album that is already in order says so and changes nothing: silence
  /// would read as a menu entry that does not work, and marking a dirty album
  /// for a write that changes nothing is worse still.
  void sortByDate() {
    if (!sortSectionsByDate(widget.album)) {
      showMessage(_l10n.alreadyInOrder);
      return;
    }
    setState(markDirty);
  }

  /// Makes the album's headings from its photos, by day or place (issue
  /// #238): on the selection where there is one, on the whole album
  /// otherwise, see `group_by.dart`. The dialog previews the headings; what
  /// it applies goes into the edit buffer like every other edit, so Save
  /// writes it and Cancel takes it back, and from then on they are ordinary
  /// headings.
  Future<void> groupBy() async {
    var plan = await showGroupByDialog(
      context: context,
      parts: widget.album.parts,
      selection: selection,
      // What the edit mode shows, see [shownParts].
      minRating: widget.album.minRating,
    );
    if (plan == null || plan.isEmpty || !mounted) {
      return;
    }
    editImage(() {
      widget.album.parts = plan.parts;
      AlbumInitializer().init(widget.album);
      // A heading that was replaced is no part of the album any more.
      var kept = Set<AlbumPart>.identity()..addAll(plan.parts);
      selection.removeWhere((part) => part is Heading && !kept.contains(part));
    });
  }

  /// Adds every image of one camera to the selection (issues #78 and #274).
  ///
  /// An album fed from a phone and a camera is gathered device by device: the
  /// images carrying the same [ImagePart.camera] label as [reference] — or,
  /// for a reference without one, every image without camera data — are
  /// *added* to what is selected, never replacing it, so a second call on an
  /// image of another camera collects that one too. A grouped image joins the
  /// selection as the image, see [sameCamera].
  ///
  /// This is the selection the recording time adjustment of issue #77 works
  /// on: select one photo of the camera whose clock was off, gather its
  /// images, correct them in one go.
  void selectSameCamera(ImagePart reference) {
    var added = [
      for (var part in sameCamera(widget.album, reference))
        if (!selection.contains(part)) part,
    ];
    if (added.isEmpty) {
      showMessage(nothingElseFromCamera(_l10n, reference));
      return;
    }
    setState(() {
      selection.addAll(added);
      lastClicked = added.last;
    });
  }

  /// Corrects the recording time of the selected images (issue #77).
  ///
  /// The tile the action was invoked on names the reference image — the one
  /// whose correct time is entered; the offset to what it carries now is
  /// added to every selected image, and each of them is then filed where its
  /// new date belongs, see [adjustRecordingTime]. Only the sidecar is edited;
  /// the EXIF data of the originals is never touched.
  ///
  /// Like every other edit this marks the album dirty and is written back by
  /// the save action. An offset of zero changes nothing and says so.
  Future<void> adjustRecordingTimeOf(AlbumPart invokedOn) async {
    var images = selectedImages(widget.album, selection);
    var reference = referenceImage(widget.album, selection, invokedOn);
    if (reference == null) {
      showMessage(_l10n.nothingToAdjust);
      return;
    }

    var answer = await showFormDialog<TimeCorrection>(
      context: context,
      builder: (context) => AdjustRecordingTimeDialog(
        reference: reference,
        images: images,
      ),
    );
    if (answer == null || !mounted) {
      return;
    }

    switch (answer) {
      case UseNameDates():
        applyNameDates(images);
      case ShiftToTime(corrected: var corrected):
        var offset = offsetFor(reference, corrected);
        if (offset == null ||
            !adjustRecordingTime(widget.album, selection, offset)) {
          showMessage(_l10n.nothingToAdjust);
          return;
        }
        keepAdjusted(images);
    }
  }

  /// Dates every one of [images] by the time its own *file name* says, the
  /// app half of issue #102.
  ///
  /// Not one offset for all of them, as [adjustRecordingTime] applies: the
  /// name of each file says when that file was recorded, and the reason the
  /// dates are wrong is that the server had to fall back to the write time,
  /// which is the time of the copy and is a different lie for every file. An
  /// image whose name says nothing, or says what the image already carries,
  /// is left exactly as it is, see [differingNameDate].
  ///
  /// Each image is corrected by the machinery of issue #77, one at a time, so
  /// it is refiled where its new date belongs and taken out of a group and a
  /// heading section it no longer fits — exactly as a manual correction of
  /// that one image would have done it. Only the sidecar changes; the album
  /// is marked dirty and written back by the save action.
  void applyNameDates(List<ImagePart> images) {
    var changed = false;
    for (var image in images) {
      var named = differingNameDate(image);
      var offset = named == null ? null : offsetFor(image, named);
      if (offset == null) {
        continue;
      }
      var one = Set<AlbumPart>.identity()..add(image);
      changed = adjustRecordingTime(widget.album, one, offset) || changed;
    }
    if (!changed) {
      showMessage(_l10n.nothingToAdjust);
      return;
    }
    keepAdjusted(images);
  }

  /// Marks the album dirty and keeps the adjusted images selected.
  void keepAdjusted(List<ImagePart> images) {
    setState(() {
      markDirty();
      // What was adjusted is what the user is now looking for — and a group
      // that was dissolved on the way is gone from the album, so it must not
      // stay in the selection either.
      selection
        ..clear()
        ..addAll(images);
      lastClicked = images.isEmpty ? null : images.last;
    });
  }

  /// Shows one rating level more (the `+` key of the GWT client).
  void showMore() =>
      setState(() => shownAlbum.minRating = showMoreRating(minRating));

  /// Shows one rating level less (the `-` key of the GWT client).
  void showLess() =>
      setState(() => shownAlbum.minRating = showLessRating(minRating));

  /// Writes the album back to the server and leaves the edit mode.
  ///
  /// The album is stored as the `index.json` sidecar of its own folder; the
  /// server keeps the previous sidecar as a backup. On success the album is
  /// re-fetched, so that the transient links between its parts are rebuilt
  /// from the state the server now has. A failed write keeps the edit mode
  /// open and reports the HTTP status.
  ///
  /// Answers whether the album reached the server: what is on the way out of
  /// the album asks, so that a failed write keeps the user here with the
  /// message instead of leaving the changes behind, see [confirmLeave].
  Future<bool> save() async {
    var l10n = _l10n;
    var messenger = ScaffoldMessenger.of(context);

    var done = await writeAction(
      context,
      () => client.saveAlbum(widget.albumState.path, widget.album),
      refusal: (error) => l10n.saveFailed("$error"),
    );
    if (done == null) {
      // Stay in edit mode, the changes are not persisted yet.
      return false;
    }
    var stored = done.value;

    if (!mounted) {
      return true;
    }

    setState(() {
      editMode = false;
      session.dirty = false;
      clearSelection();
      // The album is laid out anew from what was saved.
      _layoutOrientation.clear();
    });

    // An edit of a referenced photograph was written to the album it lies in
    // (issues #221, #307): that album is fetched anew when it is opened.
    forgetSources(widget.albumState.navigator.delegate, widget.album,
        folder: widget.albumState.path);

    // Re-load the album, so that the transient part links are rebuilt. The
    // listing above shows the album by its index picture, which may have
    // just been chosen: it is fetched anew on the way up.
    var path = widget.albumState.path;
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    followStored(stored, messenger);
    return true;
  }

  /// Goes where the album is now and says so, after its properties were
  /// written, see issue #130.
  ///
  /// A changed title or date renames the album's folder, so the address the
  /// app is showing may have just become a 404: the server answers where the
  /// album really is, and the app follows it instead of reloading nothing.
  /// Where nothing was renamed this is the ordinary reload.
  void followStored(CreateResult stored, ScaffoldMessengerState messenger) {
    // A photo rated as trash that other albums show went there (issue #307).
    showKeptReport(messenger, keptReport(_l10n, stored.kept));
    if (stored.message.isEmpty) {
      widget.albumState.reload();
      return;
    }
    widget.albumState.showPath(splitPath(stored.path));
    // Nothing happens silently: the new name is said, not guessed at.
    messenger.showSnackBar(
      SnackBar(
        content: Text(stored.message),
        duration: const Duration(seconds: 6),
      ),
    );
  }

  /// Leaves the edit mode, asking about unsaved changes first (issue #99).
  ///
  /// The counterpart of [save]: an edit that was accidental, wrong or simply
  /// unwanted is taken back here. Nothing unsaved is thrown away silently —
  /// with changes in the buffer the album asks, and [discardEdits] is what
  /// the answer "discard" runs.
  Future<void> cancelEdit() async {
    if (!dirty) {
      setState(() {
        editMode = false;
        clearSelection();
      });
      return;
    }
    var discard = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("discard-dialog"),
        title: Text(_l10n.discardChangesTitle),
        content: Text(_l10n.discardChangesMessage),
        actions: [
          TextButton(
            key: const Key("keep-editing"),
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.keepEditing),
          ),
          ElevatedButton(
            key: const Key("discard-changes"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.discard),
          ),
        ],
      ),
    );
    if (discard != true || !mounted) {
      return;
    }
    discardEdits();
  }

  /// Throws the unsaved changes away and leaves the edit mode.
  ///
  /// What [save] does after a successful write, minus the write: the album in
  /// the buffer carries the edits, so it is forgotten and fetched again — what
  /// is then on the screen is exactly what the server has. Nothing is sent,
  /// the server never heard of the edit.
  ///
  /// [reload] is off where the album is being left anyway: the view is about
  /// to go, and forgetting the buffer is enough for the next visit to show
  /// what the server has.
  void discardEdits({bool reload = true}) {
    setState(() {
      editMode = false;
      session.dirty = false;
      clearSelection();
      _layoutOrientation.clear();
    });
    widget.albumState.navigator.delegate.forget(widget.albumState.statePath);
    if (reload) {
      widget.albumState.reload();
    }
  }

  /// Whether the app may leave this album, see
  /// [VAlbumRouterDelegate.leaveAlbum] (issue #99).
  ///
  /// Asked by the router for every way out — the way up, the home button, a
  /// listing tile above, the system's or the browser's back button, a deep
  /// link — and only where the edit carries unsaved changes. Saving leaves
  /// only where the write went through: a server that refused keeps the user
  /// here with the message, exactly as [save] does otherwise.
  Future<bool> confirmLeave() async {
    if (!mounted) {
      return true;
    }
    var choice = await showAppDialog<LeaveEdit>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("leave-dialog"),
        title: Text(_l10n.saveChangesTitle),
        content: Text(_l10n.saveChangesMessage),
        actions: [
          TextButton(
            key: const Key("stay"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.stay),
            child: Text(_l10n.stay),
          ),
          TextButton(
            key: const Key("discard-and-leave"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.discard),
            child: Text(_l10n.discard),
          ),
          ElevatedButton(
            key: const Key("save-and-leave"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.save),
            child: Text(_l10n.save),
          ),
        ],
      ),
    );
    if (!mounted) {
      return true;
    }
    switch (choice) {
      case null:
      case LeaveEdit.stay:
        return false;
      case LeaveEdit.discard:
        discardEdits(reload: false);
        return true;
      case LeaveEdit.save:
        return save();
    }
  }

  /// Whether the face editor of issue #126 is offered for this album.
  ///
  /// Three conditions, and each of them says that there is nothing to open
  /// otherwise: the space looks for faces at all (`?type=auth`), this album
  /// carries one or is still being looked at, and the caller is not inside a
  /// share link — a link is answered no face, so its editor would be empty by
  /// construction.
  bool mayOpenPersons(BuildContext context) =>
      share == null &&
      CallerInfo.facesOf(context) &&
      (shownAlbum.facesPending || PersonsContentState.hasFaces(shownAlbum));

  /// Opens the face editor of this album, see issue #126.
  void showPersons() =>
      widget.albumState.navigator.go(PersonsRoute(widget.albumState.path));

  /// Whether "Show trash" is offered for this album, see issue #152.
  ///
  /// To whoever may change the album ([mayEditAlbum]: `edit`, no share link,
  /// no "view as" preview — the server answers a trashed photograph to
  /// editors alone), outside the edit mode — the trash page writes at once,
  /// and a write of the album under an edit session's unsaved buffer would
  /// save that buffer with it — and only where the album holds a photograph
  /// rated as trash, group members counted one by one.
  ///
  /// Its own photographs alone: a reference (issue #307) is put into the trash
  /// of the album its photograph lies in, and restored and purged there.
  bool get mayShowTrash =>
      mayEditAlbum &&
      !editMode &&
      !showsOtherAlbums &&
      hasTrashedImages(widget.album);

  /// Opens the trash of this album, see issue #152.
  void showTrash() =>
      widget.albumState.navigator.go(TrashRoute(widget.albumState.path));

  /// Whether this caller may change the album itself (issue #121).
  ///
  /// What the edit mode needs, without needing the edit mode: the album's
  /// properties are edited from the menu in the view mode as well, and are
  /// then written by themselves, see [editProperties]. Never inside a share
  /// link — a link is not an account — and never over somebody else's view of
  /// the album, which is read-only, see [setViewAs].
  bool get mayEditAlbum => rights.mayEdit;

  // --- Searches, see issue #227 and `search_view.dart`. ---

  /// Whether this album is the result of the search view: looked at, never
  /// edited, its photographs named by their path below the folder searched.
  bool get isSearchView => widget.albumState.route is SearchRoute;

  /// Whether this album is the view of an entry of the bell (issue #268): a
  /// copy of the album holding the entry's photographs alone, looked at and
  /// never edited, its photographs named as in their album.
  bool get isNewsView => widget.albumState.route is NewsRoute;

  /// Whether the tile of [image] is marked as new for this visit of the
  /// album: it arrived with the entry of the bell the album was reached from
  /// (issue #268), see [VAlbumRouterDelegate.freshIn].
  bool isFresh(ImagePart image) {
    if (widget.albumState.route is! ListingOrAlbumRoute) {
      return false;
    }
    var fresh =
        widget.albumState.navigator.delegate.freshIn(widget.albumState.path);
    if (fresh == null) {
      return false;
    }
    var group = image.group;
    return group == null
        ? fresh.contains(image.name)
        : group.images.any((member) => fresh.contains(member.name));
  }

  /// Whether the photographs shown lie in other albums, which keep their
  /// order, groups and headings: a search, saved or not. An album's
  /// references lie in other albums one by one, see [isReference].
  bool get showsOtherAlbums => isSearch(widget.album);

  /// Whether "Edit search" is offered: a saved search, to whoever may edit
  /// it.
  bool get mayEditSearch =>
      isSearch(widget.album) && !isSearchView && mayEditAlbum && !editMode;

  /// Changes what this saved search looks for, in the search view below the
  /// folder it lies in; "Save" there writes the query back, see issue #227.
  void editSearch() {
    var criteria = SearchCriteria.fromQuery(widget.album.query);
    if (criteria == null) {
      showMessage(_l10n.searchCannotEdit);
      return;
    }
    var path = widget.albumState.path;
    var scope = searchScopeOf(path);
    var delegate = widget.albumState.navigator.delegate;
    delegate.searchSession(scope).edit(path, widget.album, criteria);
    delegate.go(SearchRoute(scope));
  }

  /// Whether "Add all to album (as reference)…" is offered: in a saved search
  /// showing photographs, to a member outside the edit mode, which offers
  /// "Add to album (as reference)…" for its selection.
  bool get mayCollectAll =>
      isSearch(widget.album) &&
      !isSearchView &&
      !editMode &&
      share == null &&
      (_permission.named || heldRights.mayEdit) &&
      holdsImages;

  /// Adds every photograph shown to an album as references, chosen or
  /// created, see issues #221, #227 and #307.
  Future<void> collectAll() => collectWithPicker(
        context: context,
        client: client,
        source: widget.albumState.path,
        names: [
          for (var image in shownImages(shownParts(shownAlbum).toSet()))
            image.name,
        ],
        delegate: widget.albumState.navigator.delegate,
      );

  /// Whether the menu's move acts on the selection, see issue #121.
  ///
  /// Exactly the condition the toolbar button carried: the edit mode holding
  /// a selection. The edit mode itself already asks for `edit` and refuses a
  /// share link, see [editMode].
  ///
  /// A reference moves as a reference (issue #307): its photograph stays where
  /// it lies.
  bool get mayMoveSelection =>
      selection.isNotEmpty && PhotoAction.move.allowedFor(photoFacts);

  /// The photographs of the selection another album can reference, see issues
  /// #221 and #307: every selected image (a group by its representative),
  /// never a photograph that is gone.
  List<String> get collectableNames => [
        for (var part in widget.album.parts)
          if (isSelected(part) &&
              part is AbstractImage &&
              layouter.ToImage.toImage(part).hasPicture)
            part.thumbnailName,
      ];

  /// Whether "Add to album (as reference)…" is offered: a selection of the
  /// edit mode holding a photograph (issues #221, #307).
  bool get mayCollectSelection =>
      editMode &&
      PhotoAction.collect.allowedFor(photoFacts) &&
      collectableNames.isNotEmpty;

  /// Adds the selected photographs to another album as references, chosen or
  /// created in the picker, see issues #221 and #307. Nothing changes in this
  /// album.
  Future<void> collectSelection() => collectWithPicker(
        context: context,
        client: client,
        source: widget.albumState.path,
        names: collectableNames,
        delegate: widget.albumState.navigator.delegate,
      );

  /// The references of the selection, by their names in this album (issue
  /// #307).
  List<String> get selectedReferences => [
        for (var part in widget.album.parts)
          if (isSelected(part) && part is ImagePart && isReference(part))
            part.name,
      ];

  /// Whether "Remove from album" is offered: a selection of the edit mode
  /// holding a reference, see issues #221 and #307.
  bool get mayRemoveReferences =>
      PhotoAction.removeReference.allowedFor(photoFacts) &&
      selectedReferences.isNotEmpty;

  /// Takes the selected references out of this album, see issues #221 and
  /// #307.
  ///
  /// Refused with unsaved changes, as a move is: the album is fetched again
  /// afterwards. The photographs stay in their own albums.
  Future<void> removeSelectedReferences() async {
    if (dirty) {
      showMessage(_l10n.saveOrDiscardFirst);
      return;
    }
    var names = selectedReferences;
    if (names.isEmpty) {
      return;
    }
    var removed = await removeReferencesFromAlbum(
      context: context,
      client: client,
      path: widget.albumState.path,
      names: names,
    );
    if (removed && mounted) {
      setState(() {
        clearSelection();
        _layoutOrientation.clear();
      });
      var path = widget.albumState.path;
      if (path.isNotEmpty) {
        widget.albumState.navigator.delegate
            .forget(path.sublist(0, path.length - 1));
      }
      widget.albumState.reload();
    }
  }

  /// The label the view shows the photographs of, `null` for all of them
  /// (issue #213); the edit mode is never filtered by a label.
  String? get labelFilter => editMode ? null : labelFilterOf(shownAlbum);

  /// The parts the album shows: the rating filter's, and in the view mode
  /// the label chip's, with the headings under which nothing is shown left
  /// out wherever a photograph is hidden — the one rule of every filtered
  /// view (issue #213). The edit mode and an unfiltered view keep every
  /// heading, empty ones included.
  List<AlbumPart> shownParts(AlbumInfo self) {
    var parts = visibleParts(self);
    if (editMode) {
      return parts;
    }
    var label = labelFilterOf(self);
    // Unfiltered — no chip, and the rating filter hiding nothing — the album
    // keeps every heading, empty ones included.
    var hidesSome = parts.whereType<AbstractImage>().length !=
        self.parts.whereType<AbstractImage>().length;
    return label == null && !hidesSome ? parts : labelView(parts, label);
  }

  /// Whether "Label…" is offered: a selection of the edit mode (issue #213).
  bool get mayLabelSelection =>
      PhotoAction.labels.allowedFor(photoFacts) &&
      selection.any((part) => part is AbstractImage);

  /// Gives the selected photographs labels or takes labels off them, in the
  /// edit buffer (issue #213): saved with the album like every other edit.
  Future<void> labelSelection() async {
    var parts = [
      for (var part in widget.album.parts)
        if (part is AbstractImage && selection.contains(part)) part,
    ];
    if (parts.isEmpty) {
      return;
    }
    var changes = await askLabels(
      context,
      labels: [for (var entry in albumLabels(widget.album.parts)) entry.label],
      parts: parts,
    );
    if (changes == null || changes.isEmpty || !mounted) {
      return;
    }
    setState(() {
      var changed = false;
      changes.forEach((label, carry) {
        changed |= carry ? addLabel(parts, label) : removeLabel(parts, label);
      });
      if (changed) {
        markDirty();
      }
    });
  }

  /// Filters the view to the photographs of [label], or shows them all again
  /// where it is the label already shown (issue #213).
  void toggleLabelFilter(String label) => setState(() {
        var album = shownAlbum;
        setLabelFilter(album, labelFilterOf(album) == label ? null : label);
      });

  /// The chips of the labels of the album above its photographs, each with
  /// the number of photographs a tap on it shows (issue #213); `null` where
  /// there is none to show, and always inside a share link — the link is
  /// already the view, and a link is answered no label.
  Widget? labelChips(AlbumInfo self) {
    if (share != null) {
      return null;
    }
    var labels = albumLabels(
      self.parts,
      shown: (part) => isVisiblePart(part, self.minRating),
    );
    if (labels.isEmpty) {
      return null;
    }
    var active = labelFilterOf(self);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Wrap(
        alignment: WrapAlignment.center,
        spacing: 8,
        runSpacing: 4,
        children: [
          for (var entry in labels)
            _labelChip(entry.label, entry.count, active == entry.label),
        ],
      ),
    );
  }

  Widget _labelChip(String label, int count, bool active) {
    Widget chip = FilterChip(
      key: Key("label-chip-$label"),
      selected: active,
      showCheckmark: false,
      // Opaque: Material 3 lays a chip on a Material in the theme's light
      // surface, which a translucent colour lets through instead of the
      // black page — white text on near white.
      backgroundColor: Color.alphaBlend(Colors.white12, Colors.black),
      selectedColor: Colors.white,
      side: const BorderSide(color: Colors.white38),
      label: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: TextStyle(color: active ? Colors.black : Colors.white),
          ),
          const SizedBox(width: 6),
          Text(
            "$count",
            key: Key("label-count-$label"),
            style: TextStyle(color: active ? Colors.black54 : Colors.white60),
          ),
        ],
      ),
      onSelected: (_) => toggleLabelFilter(label),
    );
    // On hover only: a long press is the editor's way to the label's menu.
    chip = Tooltip(
      message: _l10n.labelChipTooltip(label),
      triggerMode: TooltipTriggerMode.manual,
      child: chip,
    );
    if (!mayEditAlbum) {
      return chip;
    }
    // An editor renames or removes a label where it is shown: a long press
    // on a touch screen, a secondary click with a mouse.
    return SelectionExcluded(
      child: Builder(
        builder: (context) => GestureDetector(
          onLongPress: () {
            var box = context.findRenderObject() as RenderBox;
            _showLabelMenu(
                label, box.localToGlobal(box.size.center(Offset.zero)));
          },
          onSecondaryTapUp: (details) =>
              _showLabelMenu(label, details.globalPosition),
          child: chip,
        ),
      ),
    );
  }

  Future<void> _showLabelMenu(String label, Offset position) async {
    var overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
    var action = await showMenu<Future<void> Function()>(
      context: context,
      position: RelativeRect.fromRect(
        position & Size.zero,
        Offset.zero & overlay.size,
      ),
      items: [
        PopupMenuItem(
          key: const Key("label-rename"),
          value: () => renameLabel(label),
          child: Text(_l10n.labelRename),
        ),
        PopupMenuItem(
          key: const Key("label-delete"),
          value: () => deleteLabel(label),
          child: Text(_l10n.labelDelete),
        ),
      ],
    );
    if (action != null && mounted) {
      await action();
    }
  }

  /// Renames [label] on every photograph of the album, at once (issue #213):
  /// the server carries a share link showing it along.
  Future<void> renameLabel(String label) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var name = await askLabelRename(context, label);
    if (name == null || !mounted) {
      return;
    }
    await _relabel(label, name);
  }

  /// Takes [label] off every photograph of the album, at once, after asking
  /// (issue #213).
  Future<void> deleteLabel(String label) async {
    if (refuseWhileOffline(context)) {
      return;
    }
    if (!await confirmLabelDelete(context, label) || !mounted) {
      return;
    }
    await _relabel(label, "");
  }

  Future<void> _relabel(String from, String to) async {
    var done = await writeAction(
        context, () => client.relabel(widget.albumState.path, from, to));
    if (done == null || !mounted) {
      return;
    }
    var answer = done.value;
    setState(() {
      var album = widget.album;
      if (answer != null) {
        applyLabelsFrom(album, answer);
      } else {
        renameLabelIn(album.parts, from, to);
      }
      if (labelFilterOf(album) == from) {
        setLabelFilter(album, to.isEmpty ? null : to);
      }
    });
  }

  /// Whether the menu's move acts on the album itself, see [moveAlbum].
  ///
  /// Nothing at the root of the space: an album is an entry of the listing
  /// above it, and above the root there is no listing to move it into.
  bool get mayMoveAlbum => mayEditAlbum && widget.albumState.path.isNotEmpty;

  /// The originals the menu's download takes, see issues #164 and #209.
  ///
  /// With the `download` right on the album only: the images of the selection
  /// while the edit mode holds one, and otherwise the view — every image shown
  /// at the rating filter standing, for a member exactly as for a share link,
  /// photographs and videos alike, and only the photographs of the label chip
  /// standing (#213). What the view does not show is not taken: an image below
  /// the filter, the alternatives of a group — a group is its representative
  /// alone, the photograph its tile shows (#230) — and a photograph rated as
  /// trash (#152), which no filter shows. Always the originals.
  List<ImagePart> get downloadImages {
    if (!rights.mayDownload || isSearchView) {
      return const [];
    }
    // A reference whose photograph is gone has no original (#221, #307).
    if (editMode && selection.isNotEmpty) {
      return [
        for (var image in downloadedImages(widget.album, selection))
          if (!image.missing) image,
      ];
    }
    return shownImages(shownParts(shownAlbum).toSet());
  }

  /// The images of [parts] the album shows, see [downloadImages]: a group is
  /// its representative, which is what its tile shows (and rates it by).
  List<ImagePart> shownImages(Set<AlbumPart> parts) {
    var label = labelFilter;
    var representatives = Set<ImagePart>.identity()
      ..addAll([
        for (var part in parts)
          if (part is ImageGroup) part.images[part.representative],
      ]);
    return [
      for (var image in downloadedImages(shownAlbum, parts))
        if (image.rating >= minRating &&
            !image.missing &&
            // A group is shown under the chip when one of its members
            // carries the label (#213); its tile is still the representative.
            (label == null ||
                representatives.contains(image) ||
                carriesLabel(image, label)))
          image,
    ];
  }

  /// Fetches [images] — [downloadImages] where none are named — and hands
  /// them to the platform, see `downloads.dart`: one archive named by the
  /// album's title where the platform keeps files, one original after the
  /// other on a phone.
  Future<void> downloadSelection([List<ImagePart>? images]) {
    var names = [for (var image in images ?? downloadImages) image.name];
    var own = widget.downloadSelected;
    if (own != null) {
      return own(names);
    }
    return runDownload(
      context,
      (progress) => downloadOriginals(
        client,
        widget.albumState.path,
        names,
        archiveName: archiveNameOf(widget.album.title),
        progress: progress,
      ),
    );
  }

  // --- The keyboard, see issue #295 and `tile_keys.dart`. ---

  /// What tells the tiles apart for the keyboard: the name of the photo a
  /// tile shows, a group's representative's — the key its tile has always
  /// had.
  static Object tileIdOf(AbstractImage part) =>
      layouter.ToImage.toImage(part).name;

  /// The tile the keyboard stands on (issue #295), see [TileHighlight]: its
  /// tiles handed over by [buildSlivers], its keys asked by [onKey].
  final TileHighlight<AbstractImage> tileHighlight =
      TileHighlight<AbstractImage>(idOf: tileIdOf);

  /// What the keys do with the tile highlighted (issue #295), the policy of
  /// a tap of the mode ([PhotoTileGestures]): in the view mode `Enter`
  /// opens the viewer — on the tile of a burst too, as on a group's; in a
  /// mode with a selection — the edit and the select mode — `Enter` and
  /// `Space` toggle the tile (all shots of a burst) and `Ctrl+A` selects
  /// every photo shown; the menu key opens the tile's menu wherever a
  /// secondary click does. A "view as" preview is acted on by no key.
  TileKeyActions<AbstractImage> get tileKeyActions {
    if (previewing) {
      return const TileKeyActions();
    }
    void menu(AbstractImage part, Offset position) =>
        showTileMenu(PhotoTile(part, layouter.ToImage.toImage(part)), position);
    if (editMode || selectMode) {
      // The tile of a burst stands for all of its shots (issue #257).
      return TileKeyActions(
        onEnter: toggleTile,
        onToggle: toggleTile,
        onMenu: offersTileMenu ? menu : null,
        onSelectAll: selectAllShown,
      );
    }
    return TileKeyActions(
      onEnter: (part) {
        // The tile of a burst opens the viewer on its best shot, as a
        // group's opens it on its representative (issue #314).
        var image = layouter.ToImage.toImage(part);
        // A photograph that is gone opens no viewer (issue #221).
        if (!image.missing) {
          widget.pushPart(part, image.name);
        }
      },
      onMenu: offersTileMenu ? menu : null,
    );
  }

  /// Selects every photo the page shows, in the edit and the select mode
  /// (`Ctrl+A`, issue #295): what the filters hide is not there to be acted
  /// on.
  void selectAllShown() => setState(() {
        selection
          ..clear()
          ..addAll(_displayOrder.whereType<AbstractImage>());
        // The shots under the tile of a burst are shown by it (issue #257).
        for (var stack in _stackView.folded.values) {
          selection.addAll(stack.members);
        }
        lastClicked = null;
      });

  // --- The bursts, see issue #257 and `album_stacks.dart`. ---

  /// Whether the albums of this device stack their bursts, by the store it
  /// is remembered in: read once per store, not once per album, so that the
  /// next album opens stacked or not from its first frame.
  static final Expando<bool> _stackingOf = Expando();

  /// Whether this album stacks its bursts, see [stacksShown]; on until the
  /// device's choice is read.
  bool _stacking = true;

  /// The store the choice whether to stack is remembered in, `null` where
  /// the album is shown without the app around it.
  SettingsStore? get _settingsStore =>
      ServerSettingsScope.maybeOf(context)?.settings.store;

  /// Reads the choice of this device whether to stack, see [_stackingOf].
  void _loadStacking() {
    var store = _settingsStore;
    if (store == null) {
      return;
    }
    var known = _stackingOf[store];
    if (known != null) {
      _stacking = known;
      return;
    }
    store.loadAlbumStacking().then((stacking) {
      var value = stacking ?? true;
      _stackingOf[store] ??= value;
      if (mounted && _stacking != _stackingOf[store]) {
        setState(() => _stacking = _stackingOf[store]!);
      }
    });
  }

  /// Whether the tiles stack the bursts of the album: where the server
  /// answered some and the device has not switched it off — in the edit mode
  /// as well, which keeps the bursts as they were shown (issue #314); the
  /// results of a search or of an entry of the bell are no album of their
  /// own.
  bool get stacksShown =>
      _stacking && !isSearchView && !isNewsView && shownAlbum.stacks.isNotEmpty;

  /// Whether the album's menu offers to switch the stacking: where the album
  /// has bursts to stack, outside the edit mode.
  bool get offersStacking =>
      !editMode && !isSearchView && !isNewsView && shownAlbum.stacks.isNotEmpty;

  /// Whether the bursts are stacked, see [stacksShown].
  bool get stacking => _stacking;

  /// Switches the stacking of the bursts on or off, for every album of this
  /// device, and remembers it.
  void setStacking(bool value) {
    setState(() => _stacking = value);
    var store = _settingsStore;
    if (store != null) {
      _stackingOf[store] = value;
      store.saveAlbumStacking(value);
    }
  }

  /// What the grid shows of the album's parts, see [stackView]; laid out
  /// anew with the grid.
  StackView _stackView = StackView.unstacked(const []);

  /// What the grid shows, see [_stackView].
  StackView get shownStacks => _stackView;

  /// Whether the viewer opened from this album pages past the bursts, as
  /// past a group's alternatives (issue #314, see [albumStacking]): where the
  /// device stacks them and the page is the album itself.
  bool get _pagesStacked => _stacking && !isSearchView && !isNewsView;

  /// Tells the album whether its bursts are groups to the viewer, and links
  /// it anew where that changed (see [AlbumInitializer]).
  void _syncAlbumStacking() {
    var album = widget.album;
    if (setAlbumStacking(album, _pagesStacked)) {
      AlbumInitializer().init(album);
    }
  }

  /// The parts the tile of [part] stands for: every shot of the burst where
  /// it is the tile of one, [part] alone otherwise.
  List<AlbumPart> standsFor(AlbumPart part) =>
      _stackView.foldedOf(part)?.members ?? [part];

  /// Whether the tile of [part] shows as selected: all shots of a folded
  /// burst where it stands for one, see [stackSelected].
  bool tileSelected(AlbumPart part) {
    var stack = _stackView.foldedOf(part);
    return stack == null ? isSelected(part) : stackSelected(stack);
  }

  /// A tap on the tile of [part] in a mode with a selection: toggles every
  /// shot of a folded burst ([toggleStack]), a click on any other tile
  /// ([handleTap]).
  void tapTile(AlbumPart part) {
    var stack = _stackView.foldedOf(part);
    stack == null ? handleTap(part) : toggleStack(stack);
  }

  /// A toggle of the tile of [part] — a long press, its check mark: every
  /// shot of a folded burst ([toggleStack]), the part alone otherwise.
  void toggleTile(AlbumPart part) {
    var stack = _stackView.foldedOf(part);
    stack == null ? toggleSelection(part) : toggleStack(stack);
  }

  /// Whether every shot of the folded burst [stack] is selected: what its
  /// tile shows as selected.
  bool stackSelected(ShownStack stack) =>
      stack.members.every((member) => selection.contains(member));

  /// Selects every shot of the folded burst [stack], or none where all are
  /// selected: a tile standing for several photographs selects them all.
  void toggleStack(ShownStack stack) => setState(() {
        if (stackSelected(stack)) {
          selection.removeAll(stack.members);
        } else {
          selection.addAll(stack.members);
        }
        lastClicked = stack.tile;
      });

  /// The way back from a viewer to a shot hidden under the tile of a burst
  /// finds that tile (issue #272).
  @override
  List<String> revealNames(List<String> names) => [
        ...names,
        for (var name in names)
          if (_stackView.hiddenUnder[name] case var tile?) tile,
      ];

  // --- The select mode, see issue #209 and `select_mode.dart`. ---

  /// Whether the album is in the select mode.
  bool _selecting = false;

  /// Whether the album is in the select mode — never in the edit mode or a
  /// "view as" preview, which the mode is never offered over.
  bool get selectMode => _selecting && maySelect;

  /// Whether the select mode is offered: to whoever may download from this
  /// album and has no edit mode — a share link made with `download`, a member
  /// without `edit` — where there is something to select. An editor selects
  /// in the edit mode.
  ///
  /// The unsaved search view has no edit mode at all (issue #243): there the
  /// mode is offered to whoever may download, editors included. A saved
  /// search is an album like any other and keeps the rule of the album.
  ///
  /// What the mode is for decides as well (issue #290): the download, or the
  /// collection of the selection ([mayCollectFrom]) — a member who may not
  /// download still gathers photographs for a collection.
  bool get maySelect =>
      (rights.mayDownload || _mayCollectHere) &&
      !previewing &&
      (isSearchView || isNewsView || !mayEnterEditMode) &&
      !session.editMode &&
      holdsImages;

  /// Enters the select mode, with [first] selected where a long press opened
  /// it — every shot of a folded burst where [first] is its tile (issue
  /// #314).
  void enterSelectMode([AlbumPart? first]) {
    if (!maySelect) {
      return;
    }
    setState(() {
      _selecting = true;
      selection.clear();
      if (first != null) {
        selection.addAll(standsFor(first));
      }
      lastClicked = first;
    });
    widget.onSelectModeChanged?.call(true);
  }

  /// Leaves the select mode; the selection goes with it.
  void leaveSelectMode() {
    setState(() {
      _selecting = false;
      clearSelection();
    });
    widget.onSelectModeChanged?.call(false);
  }

  /// The names the select mode's collection takes: the selected photographs
  /// the view shows (issue #243).
  List<String> get selectedNames => [
        for (var image in selectedDownloads)
          if (image.hasPicture) image.name,
      ];

  /// Whether photographs of this view may be added to a collection, see
  /// [mayCollectFrom].
  bool get _mayCollectHere => mayCollectFrom(
        shareLink: share != null,
        signedIn: _signedIn,
        rights: heldRights,
      );

  /// Whether the caller is a signed-in member of the space; where the server
  /// has not said who the caller is, nothing is hidden on a guess (as
  /// [offeredRights] does) — the server refuses what it must.
  bool get _signedIn => CallerInfo.peek(context) == null || _permission.named;

  // --- The actions of a photo, see `photo_actions.dart` (issue #290). ---

  @override
  PhotoFacts get photoFacts => PhotoFacts(
        view: isSearchView
            ? PhotoView.search
            : isNewsView
                ? PhotoView.news
                : isSearch(widget.album)
                    ? PhotoView.savedSearch
                    : PhotoView.album,
        mode: editMode
            ? PhotoMode.edit
            : selectMode
                ? PhotoMode.select
                : PhotoMode.view,
        rights: rights,
        signedIn: _signedIn,
        shareLink: share != null,
        previewing: previewing,
        maySelect: maySelect,
        // The search view and the view of an entry of the bell have none.
        mayEnterEdit: mayEnterEditMode && !isSearchView && !isNewsView,
        searchesContent: CallerInfo.contentOf(context),
      );

  @override
  BuildContext get photoContext => context;

  @override
  int get actedOnCount =>
      selectMode ? selectedDownloads.length : selection.length;

  @override
  VAlbumRouterDelegate? get photoDelegate =>
      widget.albumState.navigator.delegate;

  /// The album the photo of [tile] lies in, in the space this view stands
  /// in (issues #294, #301).
  @override
  PhotoSource? sourceOfPhoto(PhotoTile tile) => photoSourceOf(
        tile.image,
        album: widget.album,
        folder: widget.albumState.path,
      );

  /// Whether the tiles have a menu: on everything but a "view as" preview,
  /// which is what somebody else sees, nothing to act on.
  bool get offersTileMenu => !previewing;

  /// The menu of the tile [tile], at [position] (issues #156, #243, #290):
  /// in a mode with a selection it acts on the whole selection when the tile
  /// is part of it; otherwise the tile first becomes the selection alone, so
  /// that what the menu says is what it acts on. In the view mode it acts on
  /// the tile alone.
  Future<void> showTileMenu(PhotoTile tile, Offset position) async {
    if ((editMode || selectMode) && !tileSelected(tile.part)) {
      // The tile of a folded burst is all of its shots (issue #314).
      setState(() {
        selection
          ..clear()
          ..addAll(standsFor(tile.part));
        lastClicked = tile.part;
      });
    }
    await showPhotoTileMenu(
      host: this,
      tile: tile,
      position: position,
      keyPrefix: "tile-context",
    );
  }

  @override
  void openPhoto(PhotoTile tile) => widget.pushPart(tile.part, tile.image.name);

  @override
  void selectPhoto(PhotoTile tile) => enterSelectMode(tile.part);

  @override
  void editPhoto(PhotoTile tile) => setEditMode(tile.part);

  @override
  Future<void> movePhotos(PhotoTile tile) =>
      // The photo under the pointer covers an album made for it (#273).
      moveSelection(cover: tile.image.name);

  /// Puts the photos of the selection into the trash — never a reference,
  /// whose photograph lies in another album: references are taken out of
  /// the album instead (issue #307), the files put into the trash after.
  @override
  Future<void> deletePhotos(PhotoTile tile) async {
    if (selectedReferences.isNotEmpty) {
      await removeSelectedReferences();
      return;
    }
    await rate(tile.part, trashRating);
  }

  @override
  Future<void> labelPhotos(PhotoTile tile) => labelSelection();

  @override
  Future<void> collectPhotos(PhotoTile? tile) {
    var names = tile != null && !photoFacts.onSelection
        ? [tile.image.name]
        : editMode
            ? collectableNames
            : selectedNames;
    var own = widget.collectSelected;
    if (own != null) {
      return own(names);
    }
    return collectWithPicker(
      context: context,
      client: client,
      source: widget.albumState.path,
      names: names,
      delegate: widget.albumState.navigator.delegate,
    );
  }

  @override
  Future<void> removeReferences(PhotoTile tile) => removeSelectedReferences();

  @override
  Future<void> downloadPhotos(PhotoTile? tile) {
    if (tile != null && !photoFacts.onSelection) {
      return downloadSelection([tile.image]);
    }
    return downloadSelection(selectMode ? selectedDownloads : null);
  }

  @override
  Future<void> adjustPhotoTime(PhotoTile tile) =>
      adjustRecordingTimeOf(tile.part);

  /// Edits what the photo of [tile] is in the edit mode, the one image
  /// properties dialog the viewer opens as well (issue #122); shows it
  /// read-only everywhere else — the catalogue's rule,
  /// [PhotoFacts.editsProperties].
  @override
  Future<void> showPhotoProperties(PhotoTile tile) async {
    var image = tile.image;
    if (!photoFacts.editsProperties) {
      await showImageProperties(context, image, editable: false);
      return;
    }
    var answer = await showImageProperties(context, image);
    if (answer == null || !mounted) {
      return;
    }
    editImage(() => answer.applyTo(image));
  }

  /// Crops the photo of [tile] (issue #212): the editor the viewer's menu
  /// opens, on this photograph alone whatever is selected, written at once.
  ///
  /// The album's edit session may hold unsaved changes: the crop is its own
  /// action and stores nothing else, and it lands in this very part of the
  /// buffer, so Save writes it again unchanged and Cancel (which fetches the
  /// album anew) finds it stored. Nothing is marked dirty for it.
  @override
  Future<void> cropPhotoOf(PhotoTile tile) async {
    var image = tile.image;
    var changed = await cropPhoto(
      context,
      client: client,
      albumPath: widget.albumState.path,
      imageUrl: "$albumUrl${image.name}",
      image: image,
      album: widget.album,
    );
    // A referenced photograph is cropped in its own album (#221, #307), in
    // the space the view stands in (#301).
    var source = sourceOfPhoto(tile);
    if (changed &&
        source != null &&
        source.folder.join("/") != widget.albumState.path.join("/")) {
      widget.albumState.navigator.delegate.forgetTree(source.folder);
    }
    if (changed && mounted) {
      // The tile has another aspect now: the album is laid out anew.
      relayout();
    }
  }

  /// Whether the tile of [image] is the one "Show in album" opened this
  /// album for (issue #289), see [VAlbumRouterDelegate.markIn].
  bool isMarked(ImagePart image) {
    if (widget.albumState.route is! ListingOrAlbumRoute) {
      return false;
    }
    var marked =
        widget.albumState.navigator.delegate.markedIn(widget.albumState.path);
    if (marked == null) {
      return false;
    }
    var group = image.group;
    return group == null
        ? image.name == marked
        : group.images.any((member) => member.name == marked);
  }

  /// The mark of [VAlbumRouterDelegate.markIn] this album brought into view
  /// last.
  int? _revealedMark;

  /// Brings the photo "Show in album" opened this album for into view, once
  /// per mark and once the album is the page on top (issue #289).
  void _revealMarked() {
    if (widget.albumState.route is! ListingOrAlbumRoute ||
        !(ModalRoute.isCurrentOf(context) ?? true)) {
      return;
    }
    var delegate = widget.albumState.navigator.delegate;
    var marked = delegate.markedIn(widget.albumState.path);
    if (marked == null || _revealedMark == delegate.markVersion) {
      return;
    }
    _revealedMark = delegate.markVersion;
    revealPhoto([marked]);
  }

  /// What the select mode's download takes: the selected images the album
  /// shows.
  List<ImagePart> get selectedDownloads => shownImages(selection);

  /// Moves this album itself into another folder, see issues #47 and #121.
  ///
  /// The move the listing above offers on the album's own tile, asked from
  /// inside the album: the entry that moves is this album's folder, and the
  /// move is posted to the folder it lives in. Afterwards the app ascends —
  /// the album is no longer where the address says it is, and the listing
  /// above is where it can be found again.
  Future<void> moveAlbum() async {
    var path = widget.albumState.path;
    if (path.isEmpty) {
      return;
    }
    // The same rule the move of a selection follows: the album is fetched
    // again afterwards, which would throw unsaved edits away.
    if (dirty) {
      showMessage(_l10n.saveOrDiscardFirst);
      return;
    }
    var name = path.last;
    var parent = path.sublist(0, path.length - 1);
    var delegate = widget.albumState.navigator.delegate;
    var moved = false;

    await moveWithPicker(
      context: context,
      client: client,
      source: parent,
      names: [name],
      subject: EntrySubject(name),
      delegate: delegate,
      onMoved: () {
        moved = true;
        // Neither the album nor the listing above is what was loaded: the
        // album may live somewhere else now, and the listing no longer shows
        // it where it did.
        delegate.forget(path);
        delegate.forget(parent);
      },
    );

    // Ascended after the outcome was read out, not inside [onMoved]: the
    // report of a refused entry needs this view still on the screen to open
    // its dialog, see `moveWithPicker`.
    if (moved && mounted) {
      widget.albumState.showParent();
    }
  }

  /// Whether the menu's delete acts on this album, see [deleteAlbum].
  ///
  /// What [mayMoveAlbum] asks, for the same reason: deleting an album changes
  /// the listing it is an entry of, and at the root of a space there is no
  /// listing above to change.
  bool get mayDeleteAlbum => mayMoveAlbum;

  /// Deletes this album itself, see issue #109.
  ///
  /// The delete the listing above offers on the album's own tile, asked from
  /// inside the album: the entry that goes is this album's folder, and the
  /// request is posted to the folder it lives in. Afterwards the app ascends —
  /// the album is no longer where the address says it is, and the listing
  /// above is where that is visible.
  Future<void> deleteAlbum() async {
    var path = widget.albumState.path;
    if (path.isEmpty) {
      return;
    }
    // The same rule the move of this album follows: the view is left behind
    // afterwards, which would throw unsaved edits away.
    if (dirty) {
      showMessage(_l10n.saveOrDiscardFirst);
      return;
    }
    var name = path.last;
    var parent = path.sublist(0, path.length - 1);
    var delegate = widget.albumState.navigator.delegate;

    var deleted = await deleteWithConfirmation(
      context: context,
      client: client,
      parent: parent,
      names: [name],
      what: "'$name'",
      onDeleted: () {
        // Neither this album nor the listing above is what was loaded: the
        // album is gone from where it was, and the listing no longer shows it.
        delegate.forget(path);
        delegate.forget(parent);
      },
    );

    // Ascended after the outcome was read out, as the move does it: the
    // message needs a messenger, and the one it was given is the one above.
    if (deleted && mounted) {
      widget.albumState.showParent();
    }
  }

  /// Moves the selected parts into another folder, see issue #47.
  ///
  /// The names sent are the file names of the selected images; a selected
  /// group is named by its representative, which is how the server is told to
  /// move the whole group. A [Heading] is not a file and cannot be moved, so a
  /// selection of nothing else says so instead of sending an empty request.
  ///
  /// Refused on an album with unsaved changes: the album is fetched again
  /// after the move, and that would throw the edits away — the same rule the
  /// "view as" preview follows, see [setViewAs].
  ///
  /// [cover] is the photo a tile's context menu was opened on, see
  /// `moveWithPicker` and issue #273.
  Future<void> moveSelection({String? cover}) async {
    if (dirty) {
      showMessage(_l10n.saveOrDiscardFirst);
      return;
    }
    var selected = [
      for (var part in widget.album.parts)
        if (isSelected(part) && part is AbstractImage) part,
    ];
    var names = [for (var part in selected) part.thumbnailName];
    if (names.isEmpty) {
      showMessage(_l10n.headingCannotMove);
      return;
    }

    await moveWithPicker(
      context: context,
      client: client,
      source: widget.albumState.path,
      names: names,
      subject: ImageSubject(names.length),
      delegate: widget.albumState.navigator.delegate,
      // An album created in the picker is dated by the photos that go into
      // it (issue #114), and the album suggested lies nearest them (#279).
      takenOn: earliestTaken(selected),
      cover: cover,
      onMoved: () {
        if (!mounted) {
          return;
        }
        setState(() {
          clearSelection();
          _layoutOrientation.clear();
        });
        // The listing above shows this album by an index picture that may
        // just have moved away, and the album itself is asked for anew.
        var path = widget.albumState.path;
        if (path.isNotEmpty) {
          widget.albumState.navigator.delegate
              .forget(path.sublist(0, path.length - 1));
        }
        widget.albumState.reload();
      },
    );
  }

  /// Opens the album properties editor and applies its result to the album.
  ///
  /// Inside an edit session the result goes into the buffer like every other
  /// edit, and is written when the album is saved. Outside of one — the menu
  /// offers this in the view mode too, see issue #121 — there is no buffer to
  /// put it in: the album is written at once, the way the folder properties
  /// of a listing are, and a refused write takes the change back and says the
  /// server's own reason. An edit that could never be written is refused
  /// before the dialog opens, see [refuseWhileOffline].
  Future<void> editProperties() async {
    var album = widget.album;
    var editing = session.editMode;
    if (!editing && refuseWhileOffline(context)) {
      return;
    }
    var result = await showFormDialog<AlbumProperties>(
      context: context,
      builder: (context) => AlbumPropertiesDialog(
        AlbumProperties(
          title: album.title,
          subTitle: album.subTitle,
          date: album.date,
          indexPicture: album.indexPicture,
          kind: album.kind,
          placeCaption: album.placeCaption,
        ),
        client: client,
        baseUrl: widget.baseUrl,
        indexImage: indexImageOf(album),
        effectiveDate: album.effectiveDate,
        // The server does not fill the path of a loaded album; the folder the
        // album lives in is the one the view is showing, see issue #48.
        dateSource: describeDateSource(album, folderName: albumFolderName),
      ),
    );

    if (result == null || !mounted) {
      return;
    }

    // What the album held before, so that a refused write can be taken back.
    var before = AlbumProperties(
      title: album.title,
      subTitle: album.subTitle,
      date: album.date,
      indexPicture: album.indexPicture,
      kind: album.kind,
      placeCaption: album.placeCaption,
    );

    void apply(AlbumProperties values) {
      album.title = values.title;
      album.subTitle = values.subTitle;
      // The explicit date, and only that one: the effective date is derived
      // by the server on every read and is never written by the app.
      album.date = values.date;
      album.indexPicture = values.indexPicture;
      album.placeCaption = values.placeCaption;
    }

    setState(() {
      apply(result);
      if (editing) {
        markDirty();
      }
    });

    if (editing) {
      // The edit session writes the whole album when it is saved.
      return;
    }

    var messenger = ScaffoldMessenger.of(context);
    var done = await writeAction(
        context, () => client.saveAlbum(widget.albumState.path, album));
    if (!mounted) {
      return;
    }
    if (done == null) {
      // Nothing was stored, so nothing has changed: the screen says what the
      // server holds, not what was attempted.
      setState(() => apply(before));
      return;
    }
    var stored = done.value;

    // The listing above shows this album by its title and its index picture,
    // both of which may have just changed -- and so may the album's own folder
    // name, see issue #130.
    var path = widget.albumState.path;
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    followStored(stored, messenger);
  }

  /// Stars the album, or takes its star away, see issue #239: its tile in the
  /// folder above is drawn 2×2.
  ///
  /// A property of the album like its title, and handled as
  /// [editProperties] handles that: inside an edit session the change goes
  /// into the buffer and is written with the album; outside of one it is
  /// written at once, and a refused write takes it back and says the
  /// server's own reason.
  Future<void> toggleStar() async {
    var album = widget.album;
    var editing = session.editMode;
    if (!editing && refuseWhileOffline(context)) {
      return;
    }
    var before = album.starred;
    setState(() {
      album.starred = !before;
      if (editing) {
        markDirty();
      }
    });
    if (editing) {
      return;
    }
    var messenger = ScaffoldMessenger.of(context);
    var done = await writeAction(
        context, () => client.saveAlbum(widget.albumState.path, album));
    if (!mounted) {
      return;
    }
    if (done == null) {
      setState(() => album.starred = before);
      return;
    }
    var stored = done.value;
    // The tile in the listing above is what has changed.
    var path = widget.albumState.path;
    widget.albumState.navigator.delegate
        .forget(path.sublist(0, path.length - 1));
    followStored(stored, messenger);
  }

  String get albumUrl => "${widget.baseUrl}/${widget.album.path}";

  /// The name of the folder this album lives in, empty at the root.
  String get albumFolderName {
    var path = widget.albumState.path;
    return path.isEmpty ? "" : path.last;
  }

  /// The transport to the album server.
  VAlbumClient get client => widget.albumState.client;

  /// The album, asked again by itself while the server is still preparing it
  /// or a picture of it is not made yet (issues #253, #258), see
  /// [NotReadyReAsk]: not in the edit mode and the select mode, whose
  /// selection a re-read album takes away, nor in a "view as" preview.
  @override
  Widget build(BuildContext context) => NotReadyReAsk(
        paused: () => editMode || selectMode || previewing,
        preparing: () => widget.album.preparing,
        onReAsk: _reAskAlbum,
        child: _buildAlbum(context),
      );

  /// Reads the album again while the server is preparing it (issue #253):
  /// the ordinary reload of the album, the one the face editor's poll uses as
  /// well ([PersonsContentState]). Its pictures are asked again by the
  /// re-ask itself, each tile that was not made yet, see [NotReadyReAsk].
  void _reAskAlbum() {
    if (!widget.album.preparing) {
      return;
    }
    // The results of a search are no resource of the album searched: under
    // their own key there is nothing cached to forget (issue #265).
    widget.albumState.navigator.delegate.forget(widget.albumState.statePath);
    widget.albumState.reload();
  }

  Widget _buildAlbum(BuildContext context) {
    _syncBrowserMenu();
    var self = shownAlbum;

    // The album shows its photos, not its chrome: with something to show and
    // nothing being edited there is no app bar, only the floating controls over
    // the photos, see [contentView]. A "view as" preview keeps the app bar:
    // the switch back to the owner's view lives in it.
    // A share link is no exception (issue #97): a link shows the album exactly
    // as its owner sees it. The link's *label* names the link where links are
    // managed, it is not a caption — shown over the album it only said the
    // title twice, in two styles, before the album had started.
    var link = share;
    var editing = session.editMode;
    var selecting = selectMode;
    var immersive = !editing && !selecting && self.parts.isNotEmpty;
    // The way out of the album sits where a way out belongs: at the left of
    // the app bar, ahead of everything the edit mode offers (issue #99).
    var up = wayUp();

    var selected = selecting ? selectedDownloads : const <ImagePart>[];
    // The bar offers what the catalogue allows the select mode (#290).
    var facts = photoFacts;
    return Scaffold(
      // The select mode's bar holds the way out, the count and the download,
      // nothing else (issue #209).
      appBar: selecting
          ? selectModeAppBar(
              l10n: _l10n,
              count: selected.length,
              onLeave: leaveSelectMode,
              offerDownload: PhotoAction.download.allowedFor(facts),
              onDownload:
                  selected.isEmpty ? null : () => downloadSelection(selected),
              offerCollect: PhotoAction.collect.allowedFor(facts),
              onCollect: selected.isEmpty ? null : () => collectPhotos(null),
            )
          : immersive
              ? null
              // Black like the album under it (issue #310), in every mode.
              : PageAppBar.black(
                  // The way out is the leading control in every mode — the
                  // edit mode, an empty or preparing album, the view of an
                  // entry of the bell (issues #99, #308).
                  leading: up.isNotEmpty ? up.first : null,
                  automaticallyImplyLeading: false,
                  title: PageTitle(self.title, subtitle: self.subTitle),
                  // Then what the edit offers, and the album's menu last at
                  // the right, where a menu belongs (issues #99, #100). "View
                  // as" is not a button of its own any more — it is an entry
                  // of the menu, see [albumMenu] (issue #100); the move and
                  // the album properties are entries of that menu too, and are
                  // therefore offered in the view mode as well, see issue
                  // #121; so is the search (issue #309). Save and Cancel stay
                  // here: they are the edit's own.
                  actions: [
                    if (!editing && !isNewsView) ...albumMenu(context),
                    if (!editing && isNewsView) ...newsMenu(context),
                    if (editMode)
                      IconButton(
                        onPressed: () => save(),
                        tooltip: _l10n.save,
                        icon: const Icon(Icons.save),
                      ),
                    if (editMode)
                      IconButton(
                        key: const Key("edit-cancel"),
                        onPressed: cancelEdit,
                        tooltip: _l10n.cancel,
                        icon: const Icon(Icons.close),
                      ),
                    if (editing) ...albumMenu(context),
                  ],
                ),
      backgroundColor: Colors.black,
      body: Column(
        children: [
          // Says plainly when the album below is the copy from the cache.
          OfflineBanner(onRetry: widget.albumState.reload),
          // Says plainly when the album below is what somebody else sees.
          if (previewing) previewBanner(),
          if (self.parts.isNotEmpty)
            Expanded(child: contentView(self))
          // A saved "More like this" that finds nothing, and why (issue
          // #257): its photo is not there for the reader, or not analysed
          // yet.
          else if (self.searchProblem != SearchProblem.none)
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(
                    searchProblemText(_l10n, self.searchProblem),
                    key: const Key("search-problem"),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white70),
                  ),
                ),
              ),
            )
          // An empty album the server has not read yet (issue #253): the
          // album is not empty, its photos are still on their way.
          else if (self.preparing)
            Expanded(
              child: Center(
                child: SingleChildScrollView(
                  child: preparingNotice(empty: true),
                ),
              ),
            ),
        ],
      ),
      // Nothing is added to an album while somebody else's view of it is on
      // the screen: a preview is read-only, see [setViewAs].
      // Nothing is added to an album the caller may not contribute to either:
      // the button is not offered rather than refused, see issue #49.
      // Inside a link the upload is offered exactly when the link allows a
      // contribution, which is what the server answered, see issue #51.
      // A collection is an album and takes files like any (issue #307).
      floatingActionButton: previewing ||
              isNewsView ||
              isSearch(self) ||
              !rights.mayContribute ||
              (link != null && !link.writeAllowed)
          ? null
          : FloatingActionButton(
              onPressed: widget.albumState.uploadImages,
              tooltip: _l10n.upload,
              child: const Icon(Icons.cloud_upload),
            ),
    );
  }

  /// Whether the search of this album is offered (issue #243), as an entry of
  /// its menu (issue #309): under the rule of a folder's app bar — to a member of the space or an editor,
  /// never inside a link — and not on a saved search, which is a search of
  /// its own, nor on the results of the search view.
  bool get maySearch =>
      share == null &&
      !isSearchView &&
      !isSearch(widget.album) &&
      (_permission.named || heldRights.mayEdit);

  /// The way out of the album, nothing at the root: the leading control of
  /// its bar, or floating at the top left over the photos, see [contentView].
  /// An album without it is a dead end — the browser back button aside,
  /// there was no way back to the index, see issue #35. There is no home
  /// button (issue #309): the start page is one or more steps up.
  List<Widget> wayUp() => [
        if (widget.albumState.path.isNotEmpty && !isSearchView)
          WayBackButton(onPressed: widget.albumState.showParent),
      ];

  /// The album's menu, with the rating filter in it.
  ///
  /// The `+` and `-` keys stay the shortcut of the filter, see [onKey]; the
  /// menu is the touch equivalent, telling the current threshold and offering
  /// the two steps, each disabled at its GWT bound.
  List<Widget> albumMenu(BuildContext context) => [
        menu(context, [
          // Whose album this is and what may be done with it, where that is
          // not simply "mine", see issue #49.
          if (sharedLine != null) ...[
            PopupMenuItem<void Function(BuildContext)>(
              enabled: false,
              child: Text(sharedLine!, key: const Key("shared-line")),
            ),
            const PopupMenuDivider(),
          ],
          // The search of this album, see [maySearch]: it finds the album's
          // own photographs alone (issue #249), and the search view offers to
          // widen it to the whole library. An entry of the menu, not a button
          // of the bar (issue #309); not in the edit mode, which a search
          // would leave with its edits unsaved.
          if (maySearch && !session.editMode)
            keyedMenuItem(
              const Key("open-search"),
              Icons.search,
              _l10n.searchAction,
              (_) => widget.albumState.navigator
                  .go(SearchRoute(widget.albumState.path)),
            ),
          // What the album is looked at as, in the edit session where the
          // button used to be (issue #100): a line saying which view is on
          // the screen, the three choices under it — the idiom the rating
          // filter below uses.
          if (session.editMode) ...viewAsEntries(),
          if (_mayShare)
            menuItem(
                Icons.link, _l10n.shareLinkAction, (_) => shareAlbumLink()),
          // What is done *with* this album, offered in the view mode as well
          // as in the edit mode (issue #121): the properties were a toolbar
          // icon of the edit mode alone, so retitling an album meant entering
          // an edit session first.
          if (mayEditAlbum)
            keyedMenuItem(
              const Key("album-properties"),
              Icons.tune,
              _l10n.albumProperties,
              (_) => editProperties(),
            ),
          // Whether the album -- or the collection -- stands out in the folder
          // it lies in (issue #239); an album at the root lies in none.
          if (mayEditAlbum && widget.albumState.path.isNotEmpty)
            keyedMenuItem(
              const Key("toggle-star"),
              widget.album.starred ? Icons.star_border : Icons.star,
              widget.album.starred
                  ? _l10n.removeStarAction
                  : _l10n.addStarAction,
              (_) => toggleStar(),
            ),
          // One entry, two meanings, told apart by what there is to move: the
          // selection while the edit mode holds one, the album itself
          // otherwise — which is the move the listing tile above offers.
          if (mayMoveSelection)
            keyedMenuItem(
              const Key("move-to"),
              Icons.drive_file_move,
              _l10n.moveSubjectTo(ImageSubject(selection.length).asked(_l10n)),
              (_) => moveSelection(),
            )
          else if (mayMoveAlbum)
            keyedMenuItem(
              const Key("move-to"),
              Icons.drive_file_move,
              _l10n.moveAlbumTo,
              (_) => moveAlbum(),
            ),
          // The copy the `download` right promises (issues #164, #209): the
          // selection of the edit mode, and otherwise the whole view.
          if (downloadImages.isNotEmpty)
            keyedMenuItem(
              const Key("download-selection"),
              Icons.download,
              _l10n.downloadSelection(downloadImages.length),
              (_) => downloadSelection(),
            ),
          // The labels of the selection, the album's sub-views (issue #213):
          // an edit of the buffer like every other one, saved with it.
          if (mayLabelSelection)
            keyedMenuItem(
              const Key("label-selection"),
              Icons.label_outline,
              _l10n.labelSelectionAction,
              (_) => labelSelection(),
            ),
          // Some of them, for whoever has no edit mode to select in (#209).
          if (maySelect)
            keyedMenuItem(
              const Key("select-mode"),
              Icons.check_circle_outline,
              _l10n.selectPhotos,
              (_) => enterSelectMode(),
            ),
          // The bursts as one tile each, or every shot singly: a view of
          // this device, remembered (issue #257).
          if (offersStacking)
            keyedMenuItem(
              const Key("toggle-stacks"),
              stacking ? Icons.view_module_outlined : Icons.burst_mode_outlined,
              stacking ? _l10n.unstackShotsAction : _l10n.stackShotsAction,
              (_) => setStacking(!stacking),
            ),
          // The delete the listing above offers on this album's own tile,
          // asked from inside the album (#109) — and under the same condition
          // the move is: the album is an entry of the listing above it, and
          // changing that listing is what deleting it is.
          if (mayDeleteAlbum)
            keyedMenuItem(
              const Key("delete-album"),
              Icons.delete_outline,
              _l10n.deleteAlbumAction,
              (_) => deleteAlbum(),
            ),
          // An edit like every other one: offered inside the edit session, so
          // that the new order is reviewed and saved (or discarded) the way a
          // move or a heading is, see issue #76.
          // Sections laid out before the images arrive, see issue #158 — in
          // the view mode too where the album holds no image, since the edit
          // mode is otherwise entered by a long press on a tile.
          // A search shows its photographs in date order and stores no
          // heading (issue #227).
          if ((editMode || (mayEnterEditMode && !holdsImages)) &&
              !isSearch(widget.album))
            keyedMenuItem(
              const Key("add-heading"),
              Icons.title,
              _l10n.addHeading,
              (_) => addHeading(),
            ),
          if (editMode && !isSearch(widget.album))
            menuItem(Icons.sort, _l10n.sortByDate, (_) => sortByDate()),
          // The headings made from the photos (issue #238), into the buffer
          // like the other heading actions beside it.
          if (editMode && holdsImages && !isSearch(widget.album))
            keyedMenuItem(
              const Key("group-by"),
              Icons.segment,
              _l10n.groupByAction,
              (_) => groupBy(),
            ),
          menuLabel(
            _l10n.minRatingLabel,
            "≥ $minRating",
            valueKey: const Key("minRating"),
          ),
          menuItem(
            Icons.add_circle_outline,
            _l10n.showMoreImages,
            (_) => showMore(),
            enabled: minRating > minMinRating,
          ),
          menuItem(
            Icons.remove_circle_outline,
            _l10n.showFewerImages,
            (_) => showLess(),
            enabled: minRating < maxMinRating,
          ),
          // References to the selection in another album, chosen or created
          // (issues #221, #307); nothing changes in this album. Below the
          // filter, so that the entries a selection always had keep their
          // places.
          if (mayCollectSelection)
            keyedMenuItem(
              const Key("add-to-collection"),
              Icons.add_link,
              _l10n.addToAlbumAsReference,
              (_) => collectSelection(),
            ),
          if (mayRemoveReferences)
            keyedMenuItem(
              const Key("remove-from-collection"),
              Icons.link_off,
              _l10n.removeReference,
              (_) => removeSelectedReferences(),
            ),
          // What a saved search looks for, and what it found added to an
          // album as references (issues #227, #307).
          if (mayEditSearch)
            keyedMenuItem(
              const Key("edit-search"),
              Icons.manage_search,
              _l10n.editSearch,
              (_) => editSearch(),
            ),
          if (mayCollectAll)
            keyedMenuItem(
              const Key("collect-all"),
              Icons.add_link,
              _l10n.addAllToAlbumAsReference,
              (_) => collectAll(),
            ),

          const PopupMenuDivider(),
          menuItem(Icons.update, _l10n.reload, (_) => reloadShown()),
          // Who is in this album, see issue #126. Offered to everybody who
          // sees the menu — only the editing controls need `edit`, and the
          // screen says so itself — but never in a share link, which the
          // server answers no face at all (issue #124), and never where the
          // space does not look for faces or this album has none.
          if (mayOpenPersons(context) && !showsOtherAlbums)
            keyedMenuItem(
              const Key("persons"),
              Icons.people_outline,
              _l10n.personsMenuEntry,
              (_) => showPersons(),
            ),
          // The photographs rated as trash, restored or purged on a page of
          // their own, see issue #152.
          if (mayShowTrash)
            keyedMenuItem(
              const Key("show-trash"),
              Icons.delete_sweep_outlined,
              _l10n.showTrash,
              (_) => showTrash(),
            ),
          // Whoever may change this album may have the camera and the position
          // read out of the files again, which an album described before they existed
          // lacks, see issue #161.
          if (mayEditAlbum && !showsOtherAlbums)
            keyedMenuItem(
              const Key("reanalyze"),
              Icons.manage_search,
              _l10n.reanalyze,
              (_) => reanalyze(),
            ),
          // Only the administrator, who owns the server's own files: the
          // entry a non-admin may not use is not offered at all, see #98.
          if (mayRefreshCache(context) && !isSearchView)
            keyedMenuItem(
              const Key("refresh-previews"),
              Icons.cleaning_services,
              _l10n.refreshPreviews,
              (_) => refreshPreviews(),
            ),
          // A visitor of a link has no server of their own to configure.
          if (share == null)
            menuItem(Icons.settings, _l10n.serverMenuEntry, openServerSettings),
          // The way out of this device's sign-in, the very one of the
          // settings screen; only where there is a sign-in to end.
          if (share == null && mayOfferSignOut(context))
            keyedMenuItem(
              const Key("sign-out"),
              Icons.logout,
              _l10n.signOut,
              (context) => signOutFromMenu(
                context,
                showRoot: widget.albumState.showRoot,
              ),
            ),
          // What this app is, the last entry of every main menu — and the one
          // a visitor of a share link most wants to find (#187).
          // Who a personal link's visitor is, and the way to be somebody else
          // (issue #202).
          ...switchPersonEntries(context),
          aboutMenuItem(_l10n),
        ]),
      ];

  /// The menu of the view of an entry of the bell (issue #295): of the
  /// album's menu what applies to the photographs it shows — the download of
  /// them all, the select mode, the rating filter and the reload. Nothing of
  /// the album itself (its properties, its move, its trash): the album is a
  /// tap on the way back away.
  List<Widget> newsMenu(BuildContext context) => [
        menu(context, [
          if (downloadImages.isNotEmpty)
            keyedMenuItem(
              const Key("download-selection"),
              Icons.download,
              _l10n.downloadSelection(downloadImages.length),
              (_) => downloadSelection(),
            ),
          if (maySelect)
            keyedMenuItem(
              const Key("select-mode"),
              Icons.check_circle_outline,
              _l10n.selectPhotos,
              (_) => enterSelectMode(),
            ),
          menuLabel(
            _l10n.minRatingLabel,
            "≥ $minRating",
            valueKey: const Key("minRating"),
          ),
          menuItem(
            Icons.add_circle_outline,
            _l10n.showMoreImages,
            (_) => showMore(),
            enabled: minRating > minMinRating,
          ),
          menuItem(
            Icons.remove_circle_outline,
            _l10n.showFewerImages,
            (_) => showLess(),
            enabled: minRating < maxMinRating,
          ),
          const PopupMenuDivider(),
          menuItem(Icons.update, _l10n.reload, (_) => reloadShown()),
        ]),
      ];

  /// The "view as" switch of the edit session, see [setViewAs] (issue #46).
  ///
  /// Entries of the album's own menu since issue #100, where it was a button
  /// of its own before: the app bar of the edit mode carries the way out, the
  /// move, the properties, the save and the cancel, and a phone has no room
  /// for a seventh control — while the menu is where the album's other
  /// standing choice, the rating filter, already lives. The idiom is that
  /// one: a label naming the view on the screen, the three choices under it.
  List<PopupMenuEntry<void Function(BuildContext)>> viewAsEntries() => [
        menuLabel(
          _l10n.viewAsLabel,
          viewAs.state(_l10n),
          valueKey: const Key("view-as-state"),
        ),
        for (var value in ViewAs.values)
          keyedMenuItem(
            Key("view-as-${value.name}"),
            value.icon,
            value.label(_l10n),
            (_) => setViewAs(value),
          ),
        const PopupMenuDivider(),
      ];

  /// Says which view is on the screen while a preview is shown.
  Widget previewBanner() => Material(
        color: Colors.amber.shade800,
        child: SizedBox(
          width: double.infinity,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              previewMessage(_l10n, _viewAs),
              key: const Key("view-as-banner"),
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ),
      );

  /// What the banner over a preview says.
  static String previewMessage(AppLocalizations l10n, ViewAs view) =>
      switch (view) {
        ViewAs.members => l10n.previewAsMembers,
        ViewAs.public => l10n.previewAsPublic,
        ViewAs.owner => "",
      };

  /// Shows the album as a caller of the given clearance would receive it.
  ///
  /// [ViewAs.owner] is the album itself: the preview is dropped and the album
  /// is fetched from the server again, without a `viewAs` parameter. The other
  /// two fetch the album with `?viewAs=members` or `?viewAs=public` and show
  /// *that* answer, read-only — it is a smaller album than this device is
  /// entitled to, and it is never written to the offline cache, see
  /// [VAlbumClient.loadPreview].
  ///
  /// Both directions reload, so unsaved edits would be lost: a dirty album
  /// refuses the switch and says why instead.
  Future<void> setViewAs(ViewAs target) async {
    if (target == _viewAs) {
      return;
    }
    if (dirty) {
      showMessage(_l10n.saveOrDiscardFirst);
      return;
    }
    if (target == ViewAs.owner) {
      setState(() {
        _preview = null;
        _viewAs = ViewAs.owner;
      });
      // Asks the server again, this time without the parameter.
      widget.albumState.navigator.delegate.forget(widget.albumState.path);
      widget.albumState.reload();
      return;
    }
    await _showPreview(target);
  }

  /// Fetches the album as [target] would receive it and shows that answer.
  Future<void> _showPreview(ViewAs target) async {
    Resource? answer;
    try {
      answer = await client.loadPreview(
        widget.albumState.path,
        target.parameter!,
      );
    } catch (error) {
      if (mounted) {
        // The server's own reason, as any other loading failure shows it.
        showMessage(error is VAlbumException ? error.message : "$error");
      }
      return;
    }
    if (!mounted) {
      return;
    }
    if (answer is! AlbumInfo) {
      showMessage(_l10n.notAnAlbumAnswer);
      return;
    }
    AlbumInitializer().init(answer);
    setState(() {
      _preview = answer as AlbumInfo;
      _viewAs = target;
      clearSelection();
      _layoutOrientation.clear();
    });
  }

  /// Asks the server for the shown view again: the preview while one is shown,
  /// the album itself otherwise.
  void reloadShown() {
    if (previewing) {
      _showPreview(_viewAs);
    } else {
      widget.albumState.reload();
    }
  }

  /// Whether this caller may throw the album's generated cache away (#98).
  ///
  /// The administrator, and only them: the server's own files are theirs, and
  /// the server refuses anybody else with `CACHE_REFRESH_REFUSED`. An entry
  /// that would be refused is not offered, see issue #49. Inside a share link
  /// nothing of the kind is offered either — a link is a view, not a console.
  bool mayRefreshCache(BuildContext context) =>
      share == null && CallerInfo.permissionOf(context).role == roleAdmin;

  /// Throws the album's generated previews and video renditions away and
  /// fetches them anew, the app half of issue #98.
  ///
  /// Asked first, because it is work the server has to do again: a preview of
  /// a 4K photo costs a second, and an album of five hundred costs an
  /// afternoon of a home server. The confirmation therefore names exactly
  /// what goes and what stays.
  ///
  /// Afterwards the app has to forget its *own* copies, or the broken
  /// thumbnail the author is looking at stays on the screen: the decoded
  /// images of Flutter's [ImageCache] go (see [forgetDecodedThumbnails]) and
  /// the album is asked for again. The offline store needs no eviction — a
  /// thumbnail is fetched network-first and only a server that cannot be
  /// *reached* is answered from it, so the next request overwrites it, see
  /// [VAlbumClient.thumbnailBytes].
  Future<void> refreshPreviews() async {
    // Refused offline before the question is asked (issue #293).
    if (refuseWhileOffline(context)) {
      return;
    }
    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("refresh-previews-dialog"),
        title: Text(_l10n.refreshPreviews),
        content: Text(_l10n.refreshPreviewsMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("refresh-previews-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.refresh),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    // The server's own reason where it refuses — a refusal speaks, see
    // issue #49.
    var done = await writeAction(
        context, () => client.refreshCache(widget.albumState.path));
    if (done == null || !mounted) {
      return;
    }
    var answer = done.value;

    // Said before the album is thrown away and fetched again, so that the
    // count survives the rebuild that follows it.
    showMessage(_l10n.previewsRefreshed(answer.removed));
    forgetDecodedThumbnails();
    // The album itself is asked for again: the server may now answer other
    // dimensions for a part whose preview it regenerates.
    widget.albumState.navigator.delegate.forget(widget.albumState.path);
    widget.albumState.reload();
  }

  /// Reads the camera and the position of the album's photographs from the
  /// files again, the app half of `?action=reanalyze` (issue #161).
  ///
  /// Asked first, although nothing already stored is changed: it writes the
  /// album's sidecar, and the question says what it touches and what not.
  Future<void> reanalyze() async {
    if (refuseWhileOffline(context)) {
      return;
    }
    var confirmed = await showAppDialog<bool>(
      context: context,
      builder: (context) => AppAlertDialog(
        key: const Key("reanalyze-dialog"),
        title: Text(_l10n.reanalyze),
        content: Text(_l10n.reanalyzeExplanation),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.cancel),
          ),
          ElevatedButton(
            key: const Key("reanalyze-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.reanalyze),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    // The server's own reason where it refuses -- a refusal speaks, see
    // issue #49.
    var done = await writeAction(
        context, () => client.reanalyze(widget.albumState.path));
    if (done == null || !mounted) {
      return;
    }
    var answer = done.value;

    var counts = answer.running
        ? _l10n.reanalyzeRunning(answer.examined, answer.filled)
        : _l10n.reanalyzeDone(answer.examined, answer.filled);
    // The recording times read in the wrong zone before issue #183 are named
    // only where some were corrected.
    showMessage(answer.datesCorrected > 0
        ? "$counts ${_l10n.reanalyzeDatesCorrected(answer.datesCorrected)}"
        : counts);
    if (answer.albums > 0) {
      widget.albumState.navigator.delegate.forgetTree(widget.albumState.path);
      widget.albumState.reload();
    }
  }

  /// Opens the share-link dialog on this album, see issue #51.
  void shareAlbumLink() => shareLinksOf(
        context: context,
        client: client,
        path: widget.albumState.path,
        label: "'${widget.album.title}'",
        // A link may show the photographs of one label (issue #213).
        photoLabels: [
          for (var entry in albumLabels(widget.album.parts)) entry.label,
        ],
      );

  /// Says something to the user that no view of its own says.
  void showMessage(String message) =>
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
      );

  /// How far the floating controls stand from the edge of what the system
  /// leaves the page, see [contentView].
  static const double floatingMargin = 8;

  /// A floating control over the photos: dark and translucent, so that the
  /// album stays what it is about while never losing its way back.
  Widget floating(List<Widget> children) => DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black54,
          borderRadius: BorderRadius.circular(16),
        ),
        child: IconTheme.merge(
          data: const IconThemeData(color: Colors.white, size: 20),
          child: Row(mainAxisSize: MainAxisSize.min, children: children),
        ),
      );

  Widget contentView(AlbumInfo self) {
    var shown = shownParts(self);
    var hidesEverything =
        self.parts.isNotEmpty && !shown.any((part) => part is AbstractImage);

    // What the system keeps for itself on an edge-to-edge screen: the status
    // bar and a display cutout at the top, a cutout or the navigation bar at
    // a side in landscape. The view mode has no app bar to take the top inset
    // away, so the album's heading starts below it and the floating controls
    // stand clear of it — under the status bar they could be seen but not
    // tapped, the bar taking the touch. The edit mode's app bar has
    // already taken the top inset out of [MediaQuery], so this is zero there.
    var insets = MediaQuery.paddingOf(context);
    // Where the pointer of a drag is, and which one it is, see
    // [_trackPointer]: what the edge scrolling of issue #42 runs on.
    return Listener(
      onPointerDown: _trackPointer,
      onPointerMove: _trackPointer,
      // The end of the gesture, whatever became of it: the rows of the
      // album are built on demand since issue #111, so the tile a drag
      // started on may have been disposed on the way (the edge scrolling of
      // issue #42 can carry it three viewports away) — and a [Draggable]
      // whose state is gone no longer reports the end of its drag. The
      // pointer does, here, and the drop itself has been taken by then: the
      // gesture arena and the drag avatar are served before this listener
      // sees the release.
      onPointerUp: _endGesture,
      onPointerCancel: _endGesture,
      child: Stack(
        children: [
          // The scrollbar and the paging keys of issue #168, around the
          // album's own keys ([onKey]) and on the ambient controller the
          // edge scrolling of issue #42 drives too.
          KeyboardScroll(
            onKeyEvent: guardKeys(onKey),
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                // The layout of the whole album — cheap, and it needs every
                // image to decide the rows. Only the *widgets* are built on
                // demand, see [buildSlivers] (issue #111).
                var slivers = buildSlivers(
                  self,
                  constraints.maxWidth - insets.left - insets.right,
                  hidesEverything: hidesEverything,
                );
                // Every sliver is given the full width of the page, whatever
                // it holds: an album whose images the rating filter all hides
                // keeps its title centred instead of collapsing it into the
                // top left corner, which is what the [SizedBox] around the
                // former [Column] was for, see issue #35.
                return CustomScrollView(
                  scrollDirection: Axis.vertical,
                  // The reasonable context around the visible range: the rows
                  // within three viewport heights of it are built (and their
                  // thumbnails therefore asked for), the rest follows as the
                  // album is scrolled, see issue #111.
                  scrollCacheExtent:
                      const ScrollCacheExtent.viewport(contextViewports),
                  slivers: [
                    SliverPadding(
                      padding: EdgeInsets.fromLTRB(
                          insets.left, insets.top, insets.right, 0),
                      sliver: SliverMainAxisGroup(slivers: slivers),
                    ),
                    // The last row of tiles ends above the system navigation
                    // bar instead of running under it, see issue #60.
                    SliverToBoxAdapter(
                      child: SizedBox(height: insets.bottom),
                    ),
                  ],
                );
              },
            ),
          ),
          if (!editMode && !selectMode && widget.albumState.path.isNotEmpty)
            Positioned(
              top: insets.top + floatingMargin,
              left: insets.left + floatingMargin,
              child: floating(wayUp()),
            ),
          // The floating bar stands in for the album's app bar, under its
          // rules: the status indicator and the bell on the top-level page
          // alone — an album at the root — and the search in the menu
          // (issue #309). The view of an entry of the bell offers the way
          // back to its album (issue #268) and what its menu does with the
          // photographs shown (#295).
          if (!editMode && !selectMode)
            Positioned(
              top: insets.top + floatingMargin,
              right: insets.right + floatingMargin,
              child: floating([
                ...topLevelStatus(
                  topLevel: widget.albumState.path.isEmpty &&
                      !isSearchView &&
                      !isNewsView,
                ),
                ...(isNewsView ? newsMenu(context) : albumMenu(context)),
              ]),
            ),
        ],
      ),
    );
  }

  /// The `+` and `-` keys of the GWT client, widening and narrowing the rating
  /// filter — and `Escape`, which is Cancel in the edit mode (issue #99).
  ///
  /// The keys of the tiles come first (issue #295), see [tileKeyActions].
  KeyEventResult onKey(FocusNode node, KeyEvent event) {
    var tiles = tileKeyEvent(tileHighlight, node, event, tileKeyActions);
    if (tiles != KeyEventResult.ignored) {
      return tiles;
    }
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    var key = event.logicalKey;
    if (key == LogicalKeyboardKey.escape) {
      if (selectMode) {
        leaveSelectMode();
        return KeyEventResult.handled;
      }
      if (!editMode) {
        return KeyEventResult.ignored;
      }
      cancelEdit();
      return KeyEventResult.handled;
    }
    // By the character typed (issue #306): a German `ß` is the US `-` key.
    if (typedKey(event, "+", numpad: LogicalKeyboardKey.numpadAdd)) {
      showMore();
      return KeyEventResult.handled;
    }
    if (typedKey(event, "-", numpad: LogicalKeyboardKey.numpadSubtract)) {
      showLess();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// The album as the slivers of its scroll view: the title, the headings
  /// and, between them, one list of the rows of each block of images.
  ///
  /// Each run of images is laid out as a block of rows, headings separate
  /// those blocks; images whose rating is below [minRating] are left out,
  /// headings are always shown.
  ///
  /// The layout itself is computed here, for the whole album: it is cheap,
  /// and a row cannot be laid out without its neighbours. What is deferred is
  /// the *widget* of a row — and with it the [ThumbnailImage] of every tile in
  /// it, which is what used to ask the server for all 800 thumbnails of a
  /// large album the moment it was opened, see issue #111. The rows are built
  /// in the order they are shown, so the visible ones are the first to ask.
  ///
  /// A block of rows is a [SliverVariedExtentList], which is told each row's
  /// height before it is built ([rowExtent]): the scroll view then knows the
  /// height of the whole album from the start and can lay out any offset
  /// without touching what lies above it. That is what makes a jump — the
  /// scrollbar, the `End` key, the scroll offset restored on the way back or
  /// on a deep link — as cheap as a scroll: only the rows around the offset
  /// are built, not every row on the way. The title, the headings and the
  /// notice of the rating filter are text, whose height nobody can promise,
  /// so each of them is a sliver of its own that measures itself.
  List<Widget> buildSlivers(
    AlbumInfo self,
    double maxWidth, {
    required bool hidesEverything,
  }) {
    var result = <Widget>[];
    var images = <AbstractImage>[];
    _displayOrder.clear();
    beginRowBlocks();
    // Where every tile lies, for the keyboard (issue #295): the layout's
    // pixels, the blocks stacked one below the other with room between them
    // for the heading that separates them.
    var spots = <TileSpot<AbstractImage>>[];
    var blockTop = 0.0;

    if (!editMode) {
      result.add(
        SliverToBoxAdapter(
          child: Padding(
            // Between the floating up button and the menu, in the row they
            // float in: the side padding keeps a long title from running
            // underneath them, the top padding centres a single line on them.
            padding: const EdgeInsets.fromLTRB(64, 12, 64, 4),
            child: Text(
              self.title,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 28, color: Colors.white),
            ),
          ),
        ),
      );
      // When the album is, under its own title and above its subtitle, in the
      // same words the listing tile says it in, see issue #107. The key is
      // `album-heading-date` because `album-date` is the field of the
      // properties dialog.
      var dateLabel = albumDateLabel(self.effectiveDate);
      if (dateLabel != null) {
        result.add(
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
              child: Text(
                dateLabel,
                key: const Key("album-heading-date"),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, color: Colors.white60),
              ),
            ),
          ),
        );
      }
      if (self.subTitle.isNotEmpty) {
        result.add(
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
              child: Text(
                self.subTitle,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 16, color: Colors.white),
              ),
            ),
          ),
        );
      }
      var chips = labelChips(self);
      if (chips != null) {
        result.add(SliverToBoxAdapter(child: chips));
      }
    }
    // What the page above puts over the photographs, scrolling with them —
    // the criteria of the search view (issue #248).
    var header = widget.header;
    if (header != null) {
      result.add(SliverToBoxAdapter(child: header));
    }
    // What follows is what [AlbumContent.dimmed] dims.
    var photosFrom = result.length;
    // An album the server has not read yet says so (issue #253): an empty
    // page would look like an empty album, and one missing its newest photos
    // like an album without them. It is asked for again meanwhile, see
    // [NotReadyReAsk].
    if (self.preparing) {
      var empty = !self.parts.any((part) => part is AbstractImage);
      result.add(SliverToBoxAdapter(child: preparingNotice(empty: empty)));
    }
    // A filter that hides everything says so: an empty black page would look
    // like an empty album.
    if (hidesEverything) {
      result.add(
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 24, 16, 24),
            child: Text(
              labelFilter == null
                  ? AppLocalizations.of(context)!
                      .ratingFilterHidesAll(minRating)
                  : AppLocalizations.of(context)!
                      .labelFilterHidesAll(labelFilter!),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 16, color: Colors.white70),
            ),
          ),
        ),
      );
    }

    void flushImages() {
      if (images.isEmpty) {
        return;
      }
      var pending = images;
      var layout = withLayoutOrientations(
        pending,
        () => layouter.AlbumLayout(maxWidth, 250, pending),
      );
      // The images in the order the rows show them, which is not necessarily
      // the order they were handed to the layout in, see [_displayOrder].
      _displayOrder.addAll(layout.getAllImages());
      var pageWidth = layout.getPageWidth();
      var builder = ContentWidgetBuilder(imageTile, pageWidth);
      var rows = layout.getRows();
      // The gap that used to be a [SizedBox] between two rows: a sliver of
      // its own would be a sliver that draws nothing, so it is carried by the
      // row below it — in its widget and in its extent alike.
      double topGap(int index) => index > 0 ? tileSpacing : 0.0;
      double extentOf(int index) =>
          rowExtent(rows[index], pageWidth) + topGap(index);
      var rowTop = blockTop;
      for (var index = 0; index < rows.length; index++) {
        rowTop += topGap(index);
        rows[index].visit(
          TileSpotVisitor(pageWidth, spots),
          (Offset(0, rowTop), RowBox.page),
        );
        rowTop += rowExtent(rows[index], pageWidth);
      }
      // A heading is taller than any gap between two rows.
      blockTop = rowTop + 1000;
      result.add(
        SliverVariedExtentList(
          // Where the way back from a viewer finds a row, see [ReturnReveal].
          key: rowBlock(rows, extentOf, topGap),
          itemExtentBuilder: (index, dimensions) => extentOf(index),
          delegate: RowListDelegate(
            (context, index) =>
                builder.buildRow(rows[index], topGap: topGap(index)),
            childCount: rows.length,
            totalExtent: [
              for (var index = 0; index < rows.length; index++) extentOf(index),
            ].fold(0.0, (sum, extent) => sum + extent),
          ),
        ),
      );
      images = <AbstractImage>[];
    }

    // The bursts of the album, each one tile where it is stacked (#257).
    var shown = shownParts(self);
    _syncAlbumStacking();
    _stackView = stacksShown
        ? stackView(shown, self.stacks)
        : StackView.unstacked(shown);
    for (var part in _stackView.parts) {
      if (part is AbstractImage) {
        images.add(part);
      } else if (part is Heading) {
        flushImages();
        _displayOrder.add(part);
        result.add(SliverToBoxAdapter(child: headingView(part)));
      }
    }
    flushImages();
    tileHighlight.spots = spots;

    // Wrapped whether dimmed or not, where a page above may dim them (the
    // search view), so that a change of it keeps the tree, and with it the
    // tiles, as it is.
    if (header != null) {
      for (var index = photosFrom; index < result.length; index++) {
        result[index] = SliverOpacity(
          opacity: widget.dimmed ? albumDimmedOpacity : 1,
          sliver: result[index],
        );
      }
    }
    return result;
  }

  /// What an album the server has not read yet says (issue #253), see
  /// [AlbumInfo.preparing]: that its photos are on their way where it shows
  /// none ([empty]), that some may still be missing where it shows some.
  Widget preparingNotice({required bool empty}) {
    var l10n = AppLocalizations.of(context)!;
    return Padding(
      padding: empty
          ? const EdgeInsets.fromLTRB(16, 32, 16, 32)
          : const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (empty) ...[
            // A still icon and not a spinner: nothing on this page is
            // running, the album is merely asked for again now and then.
            const Icon(Icons.hourglass_empty, size: 32, color: Colors.white70),
            const SizedBox(height: 16),
          ],
          Text(
            empty ? l10n.albumPreparing : l10n.albumStillPreparing,
            key: Key(empty ? "album-preparing" : "album-still-preparing"),
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: empty ? 16 : 14, color: Colors.white70),
          ),
        ],
      ),
    );
  }

  /// The parts in the order their tiles are shown, see [_displayOrder].
  List<AlbumPart> get displayOrder => List.unmodifiable(_displayOrder);

  /// Drops the parts of [dragged] at the insert cursor drawn on the given
  /// [side] of the tile of [target], see [ReorderablePart].
  ///
  /// The cursor stands between two displayed tiles; the part shown directly
  /// before it becomes the new predecessor of the dragged block in the stored
  /// order, see [moveParts]. A cursor at the very beginning of the album has
  /// no such part, and the block becomes the first parts of the album.
  ///
  /// Carried tiles are skipped when the predecessor is looked up: they are on
  /// their way to the cursor themselves, so the part displayed before the
  /// cursor is the nearest one that stays where it is. ([target] is never a
  /// carried part, a carried tile refuses the drop.)
  void dropPart(DraggedParts dragged, AlbumPart target, InsertSide side) {
    var index = _displayOrder.indexWhere((part) => identical(part, target));
    if (index < 0) {
      return;
    }
    AlbumPart? predecessor;
    if (side == InsertSide.after) {
      predecessor = target;
    } else {
      for (var before = index - 1; before >= 0; before--) {
        var candidate = _displayOrder[before];
        if (!dragged.contains(candidate)) {
          predecessor = candidate;
          break;
        }
      }
    }
    // Behind the tile of a folded burst is behind all of its shots (issue
    // #314): the block must not land between the tile and the shots hidden
    // under it.
    if (predecessor != null) {
      if (_stackView.foldedOf(predecessor) case var stack?) {
        var members = Set<AlbumPart>.identity()..addAll(stack.members);
        for (var stored in widget.album.parts) {
          if (members.contains(stored) && !dragged.contains(stored)) {
            predecessor = stored;
          }
        }
      }
    }

    setState(() {
      if (moveParts(widget.album, dragged.parts, predecessor)) {
        markDirty();
        // The moved parts are what the user is now looking for.
        selection
          ..clear()
          ..addAll(dragged.parts);
        lastClicked = dragged.primary;
      }
    });
  }

  /// The tile of the given image, with the tile editor in the edit mode.
  ///
  /// A tile's gestures are its own (a tap opens, a long press selects, a drag
  /// reorders), so its surface takes no part in the text selection of the
  /// page, see [SelectionExcluded].
  ///
  /// The keyboard walks the tiles (issue #295): each is drawn under the ring
  /// of [tileHighlight] while it is the one highlighted.
  Widget imageTile(AbstractImage image, double width, double height) =>
      HighlightedTile<AbstractImage>(
        key: ValueKey(("tile-highlight", tileIdOf(image))),
        highlight: tileHighlight,
        entry: image,
        child: SelectionExcluded.keyed(image.visitAbstractImage(
            ImageWidgetBuilder(this, width, height), null)),
      );

  /// Runs [body] with the images set to the orientation they are laid out
  /// with, see [_layoutOrientation].
  ///
  /// The layout reads [ImagePart.orientation] from the model to decide whether
  /// width and height are swapped. While an image is being rotated, the model
  /// is already ahead of the layout, so the value is swapped in for the
  /// duration of the layout computation.
  T withLayoutOrientations<T>(List<AbstractImage> images, T Function() body) {
    var representatives = images.map(layouter.ToImage.toImage).toList();
    var current = [for (var image in representatives) image.orientation];
    // The crop rides along with the orientation (issue #212), so the layout
    // measures the same region in the frame it lays out in.
    var crops = [for (var image in representatives) image.crop];
    for (var image in representatives) {
      turnImage(image, layoutOrientation(image));
    }
    try {
      return body();
    } finally {
      for (var i = 0; i < representatives.length; i++) {
        representatives[i].orientation = current[i];
        representatives[i].crop = crops[i];
      }
    }
  }

  /// The heading between two blocks of images.
  ///
  /// In the edit mode it carries the two inline tools of the GWT
  /// `HeadingDisplay`: edit the text and delete the heading.
  Widget headingView(Heading heading) {
    var view = headingRow(heading);
    // A search's headings are derived, never dragged (issue #244).
    if (!editMode || isSearch(widget.album)) {
      return view;
    }
    // A heading is an album part like any other: it can be dragged, and an
    // image can be dropped directly behind it, see [ReorderablePart].
    return ReorderablePart(
      album: this,
      part: heading,
      feedback: Text(
        heading.text,
        style:
            TextStyle(fontSize: headingFontSize(heading), color: Colors.white),
      ),
      child: view,
    );
  }

  /// The size a heading is written in: a section as ever, a subsection in the
  /// proportion of the inbox's day heading to its month line (20 to 26, issue
  /// #158).
  static double headingFontSize(Heading heading) =>
      headingLevel(heading) == headingSubsection ? 17 : 22;

  /// The row of a heading, left-aligned in both modes (issue #158): in the
  /// edit mode a check box stands at the gutter in front of the text, and a
  /// centred heading with a check box in front of it reads as a stray control
  /// — the look of the inbox's derived headings (#136). Tapping the check box
  /// or the text selects what stands under the heading, see [toggleHeading].
  Widget headingRow(Heading heading) {
    var fontSize = headingFontSize(heading);
    // The select mode's headings carry the check box too, and nothing else
    // of the edit mode's (issue #209).
    var selectable = editMode || selectMode;
    // The headings of a search are derived from its grouping on every open
    // (issue #244): never edited, never deleted.
    var editable = editMode && !isSearch(widget.album);
    var text = Text(
      heading.text,
      style: TextStyle(fontSize: fontSize, color: Colors.white),
    );
    var row = Row(
      key: ValueKey(heading),
      mainAxisAlignment: MainAxisAlignment.start,
      children: [
        if (selectable) ...[
          Icon(
            headingSelected(heading)
                ? Icons.check_box
                : Icons.check_box_outline_blank,
            size: fontSize,
            color:
                headingSelected(heading) ? Colors.amberAccent : Colors.white54,
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Tooltip(message: _l10n.headingSelectTooltip, child: text),
          ),
        ] else
          Flexible(child: text),
        if (editable)
          IconButton(
            icon: const Icon(Icons.edit),
            iconSize: 20,
            color: Colors.white,
            tooltip: _l10n.editHeadingTitle,
            onPressed: () => editHeading(heading),
          ),
        if (editable)
          IconButton(
            icon: const Icon(Icons.delete_outline),
            iconSize: 20,
            color: Colors.white,
            tooltip: _l10n.deleteHeadingTooltip,
            onPressed: () => deleteHeading(heading),
          ),
        // The drag handle of the edit mode stands in the top right corner,
        // see [ReorderablePart.handle]; the row keeps clear of it.
        if (editable) const SizedBox(width: dragHandleSize),
      ],
    );
    var padded = Padding(
      padding: EdgeInsets.only(
        top: headingLevel(heading) == headingSubsection ? 16 : 24,
        bottom: 8,
        left: 16,
        right: 16,
      ),
      child: row,
    );
    if (!selectable) {
      // The album a heading of a search grouped by album names, opened by a
      // tap (issue #289) — where the server said where the photos lie.
      var album = isSearch(widget.album) && share == null && !previewing
          ? albumOfHeading(heading)
          : null;
      if (album == null) {
        return padded;
      }
      return InkWell(
        key: ValueKey(("heading-open-album", heading)),
        onTap: () => widget.albumState.navigator.go(ListingOrAlbumRoute(album)),
        child: padded,
      );
    }
    return InkWell(
      key: ValueKey(("heading-select", heading)),
      onTap: () => toggleHeading(heading),
      child: padded,
    );
  }
}

/// The children of one block of rows, which knows the block's exact height.
///
/// A sliver list that has not laid out its last child *estimates* what is
/// left of it from the average of the children it has — and a list whose rows
/// differ in height (the last row of a block carries a padding) is then off
/// by a little, which moves the end of the album about while it is scrolled
/// to. Here the height of every row is known before anything is built (see
/// [rowExtent]), so the block answers its own total instead of letting it be
/// guessed, and the scroll extent of the album is the same before and after
/// any jump (issue #111).
class RowListDelegate extends SliverChildBuilderDelegate {
  /// The sum of the extents of every row of the block, gaps included.
  final double totalExtent;

  RowListDelegate(
    super.builder, {
    required super.childCount,
    required this.totalExtent,
  });

  @override
  double? estimateMaxScrollOffset(
    int firstIndex,
    int lastIndex,
    double leadingScrollOffset,
    double trailingScrollOffset,
  ) =>
      totalExtent;
}

/// The height a row of the layout is drawn at, in logical pixels.
///
/// The same number [ContentWidgetBuilder.visitRow] gives the row's contents:
/// the page minus the gaps between the tiles, divided by the row's unit
/// width. The row layout fixes it before a single byte of a thumbnail has
/// arrived, which is what lets the scroll view be told every row's extent up
/// front, see [AlbumContentState.buildSlivers] (issue #111).
double rowExtent(layouter.Row row, double pageWidth) {
  var unitWidth = row.getUnitWidth();
  if (unitWidth <= 0) {
    return 0;
  }
  var gaps = (row.size() - 1) * tileSpacing;
  return max(0.0, pageWidth - gaps) / unitWidth;
}

/// How far beyond the visible range the album is built, in viewport heights
/// (issue #111).
///
/// The rows within this distance are built and their thumbnails asked for —
/// the "reasonable context" around what is on the screen; everything farther
/// away waits until scrolling brings it near. Three viewport heights is a
/// flick's worth of album in either direction.
const double contextViewports = 3;

/// Builds the tile of one image, given the box the layout assigned to it.
typedef TileBuilder = Widget Function(
  AbstractImage image,
  double width,
  double height,
);

/// The gap between two neighbouring tiles of the album, in logical pixels.
///
/// The retired GWT client separated the images by 2px, which is what makes a
/// row of photos read as separate pictures instead of one collage, see issue
/// #34. The gap is applied when the layout is turned into widgets, not in the
/// layout algorithm itself: the algorithm decides which images share a row,
/// and that decision must not depend on the decoration.
const double tileSpacing = 2.0;

/// The box a [layouter.Row] hands to each of its contents.
///
/// A content is as wide as its unit width times [scale] and as high as
/// [height]. The two are the same number for a row filling the page, but not
/// inside a [layouter.DoubleRow], whose two halves have to give up
/// [tileSpacing] of their height to the gap between them while still filling
/// the width of the double row.
class RowBox {
  /// The factor a content's unit width is multiplied by to get its width.
  final double scale;

  /// The height of the row.
  final double height;

  const RowBox(this.scale, this.height);

  /// The box of a top-level row, whose geometry follows from the page width.
  static const RowBox page = RowBox(0, 0);
}

/// Turns the rows of an [layouter.AlbumLayout] into widgets.
///
/// The tiles themselves are built by the [TileBuilder] handed in: the album
/// view builds an [ImageWidgetBuilder] tile (with the edit mode on top), the
/// alternatives view of a group (`group_view.dart`) a plain thumbnail.
class ContentWidgetBuilder implements layouter.ContentVisitor<Widget, RowBox> {
  final TileBuilder tile;
  final double pageWidth;

  const ContentWidgetBuilder(this.tile, this.pageWidth);

  /// The rows of [layout] as widgets, separated by [tileSpacing].
  List<Widget> buildRows(layouter.AlbumLayout layout) => [
        for (var (index, row) in layout.getRows().indexed) ...[
          if (index > 0) const SizedBox(height: tileSpacing),
          row.visit(this, RowBox.page),
        ],
      ];

  /// One row as a widget, [topGap] logical pixels below the row above it.
  ///
  /// What the album builds a row with when it builds them one at a time, see
  /// [AlbumContentState.buildItems]: the gap between two rows of a block is
  /// carried by the lower one, because a list item that draws nothing but a
  /// gap would be an item to build for nothing.
  Widget buildRow(layouter.Row row, {double topGap = 0}) {
    var built = row.visit(this, RowBox.page);
    if (topGap <= 0) {
      return built;
    }
    return Padding(padding: EdgeInsets.only(top: topGap), child: built);
  }

  @override
  Widget visitImg(layouter.Img content, RowBox box) {
    var image = content.getImage();

    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    return tile(image, width, height);
  }

  @override
  Widget visitRow(layouter.Row content, RowBox box) {
    // The gaps are taken out of the page first, so that the tiles still fill
    // the width exactly and keep their aspect ratio.
    var gaps = (content.size() - 1) * tileSpacing;
    var scale = max(0.0, pageWidth - gaps) / content.getUnitWidth();
    // A row of a double row is told its height, see [visitDoubleRow]; a
    // top-level row derives it from the page width.
    var contentBox = RowBox(scale, box.height > 0 ? box.height : scale);

    return Row(
      children: [
        for (var (index, child) in content.indexed) ...[
          if (index > 0) const SizedBox(width: tileSpacing),
          child.visit(this, contentBox),
        ],
      ],
    );
  }

  @override
  Widget visitDoubleRow(layouter.DoubleRow content, RowBox box) {
    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    // The double row keeps the height of the row it sits in: the gap between
    // its halves comes out of their heights, which keep their ratio.
    var inner = max(0.0, height - tileSpacing);
    var contentBuilder = ContentWidgetBuilder(tile, width);
    var upperRow = content.getUpper().visit(
          contentBuilder,
          RowBox(0, inner * content.getH1()),
        );
    var lowerRow = content.getLower().visit(
          contentBuilder,
          RowBox(0, inner * content.getH2()),
        );

    return SizedBox(
      width: width,
      height: height,
      child: Column(
        children: [
          upperRow,
          const SizedBox(height: tileSpacing),
          lowerRow,
        ],
      ),
    );
  }

  @override
  Widget visitPadding(layouter.Padding content, RowBox box) {
    var width = content.getUnitWidth() * box.scale;
    var height = box.height;

    return SizedBox(width: width, height: height);
  }
}

/// Finds where each tile of a row lies, the geometry [ContentWidgetBuilder]
/// draws it with, for the keyboard's highlight (issue #295): [spots] is
/// filled in reading order, the argument is the row's top left corner and
/// its box.
class TileSpotVisitor
    implements layouter.ContentVisitor<double, (Offset, RowBox)> {
  final double pageWidth;
  final List<TileSpot<AbstractImage>> spots;

  TileSpotVisitor(this.pageWidth, this.spots);

  /// Each visit answers the width of the content visited.
  @override
  double visitImg(layouter.Img content, (Offset, RowBox) arg) {
    var (origin, box) = arg;
    var width = content.getUnitWidth() * box.scale;
    spots.add(TileSpot(content.getImage(), origin & Size(width, box.height)));
    return width;
  }

  @override
  double visitRow(layouter.Row content, (Offset, RowBox) arg) {
    var (origin, box) = arg;
    var gaps = (content.size() - 1) * tileSpacing;
    var scale = max(0.0, pageWidth - gaps) / content.getUnitWidth();
    var contentBox = RowBox(scale, box.height > 0 ? box.height : scale);
    var x = origin.dx;
    for (var (index, child) in content.indexed) {
      if (index > 0) {
        x += tileSpacing;
      }
      x += child.visit(this, (Offset(x, origin.dy), contentBox));
    }
    return x - origin.dx;
  }

  @override
  double visitDoubleRow(layouter.DoubleRow content, (Offset, RowBox) arg) {
    var (origin, box) = arg;
    var width = content.getUnitWidth() * box.scale;
    var inner = max(0.0, box.height - tileSpacing);
    var halves = TileSpotVisitor(width, spots);
    var upper = inner * content.getH1();
    content.getUpper().visit(halves, (origin, RowBox(0, upper)));
    content.getLower().visit(
      halves,
      (
        origin.translate(0, upper + tileSpacing),
        RowBox(0, inner * content.getH2())
      ),
    );
    return width;
  }

  @override
  double visitPadding(layouter.Padding content, (Offset, RowBox) arg) =>
      content.getUnitWidth() * arg.$2.scale;
}

/// The room between the caption tooltip of a tile and the tile's edges, see
/// [tileCaptionPosition].
const double tileCaptionInset = 4;

/// The width the caption tooltip of a narrow tile may take beyond the tile,
/// so that it is not wrapped into a column of single words.
const double tileCaptionMinWidth = 160;

/// Where the caption tooltip of a tile is shown (issue #247): inside the
/// tile, on its bottom edge with [tileCaptionInset] to spare, centred on it —
/// not below the tile's middle, where a tooltip goes by default and covers
/// what the picture shows. A caption taller than the tile starts at its top,
/// and the overlay keeps it all: a tile cut off by the screen's edge shows it
/// at that edge.
Offset tileCaptionPosition(TooltipPositionContext context) {
  var tile = Rect.fromCenter(
    center: context.target,
    width: context.targetSize.width,
    height: context.targetSize.height,
  );
  var size = context.tooltipSize;
  var x = tile.center.dx - size.width / 2;
  var y = max(
    tile.top + tileCaptionInset,
    tile.bottom - tileCaptionInset - size.height,
  );
  var overlay = context.overlaySize;
  if (overlay.isFinite) {
    x = x.clamp(0.0, max(0.0, overlay.width - size.width));
    y = y.clamp(0.0, max(0.0, overlay.height - size.height));
  }
  return Offset(x, y);
}

/// How visible the photographs of an [AlbumContent.dimmed] are.
const double albumDimmedOpacity = 0.4;

/// The tooltip of the tile of [image] in [album], `null` for none: the
/// caption ([captionOf]), and for a photo found by what it shows ("More like
/// this", the AI search: [ImagePart.score] above `0`, issue #302) a line
/// saying how similar it is, "Similarity 0.62", in the language shown.
String? tileTooltipText(
  AppLocalizations l10n,
  ImagePart image,
  AlbumInfo? album,
) {
  var caption = captionOf(image, album)?.text;
  var similarity = image.score > 0
      ? l10n.tileSimilarity(formatSimilarity(l10n.localeName, image.score))
      : null;
  if (caption == null || similarity == null) {
    return caption ?? similarity;
  }
  return "$caption\n$similarity";
}

/// The key of the mark on the tile of the photo "Show in album" opened its
/// album for (issue #289).
const Key shownInAlbumMarkKey = Key("shown-in-album-mark");

class ImageWidgetBuilder implements AbstractImageVisitor<Widget, void> {
  final AlbumContentState state;
  final double width, height;

  const ImageWidgetBuilder(this.state, this.width, this.height);

  @override
  Widget visitImageGroup(ImageGroup self, void arg) {
    return thumbnailView(self.images[self.representative], self);
  }

  @override
  Widget visitImagePart(ImagePart self, void arg) {
    return thumbnailView(self, self);
  }

  /// The tile for [image], the image representing the album part [part].
  ///
  /// A tap opens the viewer on [part], not on [image]: for a group that is the
  /// group itself, whose previous/next are the album's — the representative's
  /// own links stay inside the group (see [AlbumInitializer]).
  Widget thumbnailView(ImagePart image, AbstractImage part) {
    if (state.editMode) {
      return ThumbnailEditor(
        state,
        this,
        image,
        part,
        key: ValueKey(image.name),
      );
    }
    // One tile shell for the other modes (issue #290): the mode says what a
    // tap means, a secondary click always opens the tile's menu, and a long
    // press switches the mode where there is one to switch to and opens the
    // menu where there is none.
    var tile = PhotoTile(part, image);
    void Function(Offset)? menu = state.offersTileMenu
        ? (position) => state.showTileMenu(tile, position)
        : null;
    if (state.selectMode) {
      // The select mode of issue #209: a tap selects, the viewer stays shut.
      // The tile of a folded burst stands for all of its shots (#257).
      return SizedBox(
        key: ValueKey(image.name),
        width: width,
        height: height,
        child: SelectableTile(
          selected: state.tileSelected(part),
          onTap: () => state.tapTile(part),
          onToggle: () => state.toggleTile(part),
          onMenu: menu,
          child: stacked(part, imageThumbnail(image)),
        ),
      );
    } else if (state.previewing) {
      // A "view as" preview is what somebody else sees, nothing to act on:
      // no viewer, no way into the edit mode, see [AlbumContentState.setViewAs].
      return SizedBox(
        key: ValueKey(image.name),
        width: width,
        height: height,
        child: stacked(part, imageThumbnail(image)),
      );
    }
    var facts = state.photoFacts;
    // The way into the edit mode, and for whoever has none the way into the
    // select mode (issue #209) — in the search view, which has no edit mode,
    // the select mode alone (issue #243).
    VoidCallback? modeSwitch = facts.maySelect
        ? () => state.enterSelectMode(part)
        : facts.mayEnterEdit
            ? () => state.setEditMode(part)
            : null;
    return PhotoTileGestures(
      key: ValueKey(image.name),
      // A photograph that is gone opens no viewer (issue #221). The tile of
      // a burst opens the viewer on its best shot, as a group's opens it on
      // its representative (issue #314).
      onTap:
          image.missing ? null : () => state.widget.pushPart(part, image.name),
      onLongPress: modeSwitch,
      onMenu: menu,
      child: stacked(part, imageThumbnail(image)),
    );
  }

  /// The given tile with the badge of the burst it stands for, or of the
  /// burst it opens, see [stackBadge]; [child] itself for every other tile.
  Widget stacked(AbstractImage part, Widget child) {
    var badge = stackBadge(part);
    if (badge == null) {
      return child;
    }
    return SizedBox(
      width: width,
      height: height,
      child: Stack(
        fit: StackFit.expand,
        children: [child, badge],
      ),
    );
  }

  /// The badge of the burst the tile of [part] stands for (issues #257,
  /// #314), in the tile's bottom right corner: how many shots it holds;
  /// `null` for every other tile.
  ///
  /// A mark and no control: a burst is a group the author never made, its
  /// tile is opened, selected and moved as a group's is, and the badge is
  /// all that tells the two apart.
  Widget? stackBadge(AbstractImage part) {
    var stack = state.shownStacks.foldedOf(part);
    if (stack == null) {
      return null;
    }
    var l10n = AppLocalizations.of(state.context)!;
    var tile = stack.tile.name;
    return Positioned(
      right: 4,
      bottom: 4,
      child: IgnorePointer(
        child: Tooltip(
          message: l10n.stackCount(stack.members.length),
          child: Container(
            key: ValueKey("stack-badge-$tile"),
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: Colors.black54,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.burst_mode, size: 16, color: Colors.white),
                const SizedBox(width: 4),
                Text(
                  "${stack.members.length}",
                  key: ValueKey("stack-count-$tile"),
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// The thumbnail of the given image, filling the tile box, with the
  /// picture's caption ([captionOf]) as its tooltip where it has one.
  ///
  /// The tooltip is the viewer's caption in a word: the description, or the
  /// place where there is none — the text alone, the map is a tap into the
  /// viewer away. It comes with the mouse only: the long press of a tile is
  /// the way into the edit and select modes, and a tooltip triggered by it
  /// would take that gesture, so it is [TooltipTriggerMode.manual]. Here,
  /// where the viewing and the editing tile both build their picture, so that
  /// both behave alike.
  ///
  /// It is shown inside the tile on its bottom edge (issue #247), see
  /// [tileCaptionPosition], no wider than the tile where the tile leaves room
  /// for a few words a line.
  ///
  /// A photo found by what it shows (issue #302) says how similar it is,
  /// below its caption, see [tileTooltipText].
  Widget imageThumbnail(ImagePart image) {
    var thumbnail = _imageThumbnail(image);
    var message = tileTooltipText(
      AppLocalizations.of(state.context)!,
      image,
      state.widget.album,
    );
    if (message == null) {
      return thumbnail;
    }
    return Tooltip(
      key: const Key("tile-caption"),
      message: message,
      triggerMode: TooltipTriggerMode.manual,
      waitDuration: const Duration(milliseconds: 500),
      constraints: BoxConstraints(
        maxWidth: max(width - 2 * tileCaptionInset, tileCaptionMinWidth),
      ),
      positionDelegate: tileCaptionPosition,
      child: thumbnail,
    );
  }

  /// The thumbnail of the given image, filling the tile box.
  ///
  /// The server bakes the orientation of the image *file* into the thumbnail
  /// it serves; what is stored beside the image comes on top of it and is
  /// applied here, see [orientedImageThumbnail].
  Widget _imageThumbnail(ImagePart image) {
    if (image.isBroken) {
      // A file that cannot be read (issue #253): its icon and its name, and
      // no thumbnail, teaser or play mark — nothing of it can be decoded.
      return brokenTile(image, width, height);
    }
    if (image.missing) {
      // A reference whose photograph is gone (issues #221, #307): nothing to
      // ask a thumbnail of.
      return missingTile(AppLocalizations.of(state.context)!, width, height);
    }
    var thumbnail = orientedImageThumbnail(
      state.client,
      "${state.albumUrl}${image.thumbnailName}",
      image,
      width: width,
      height: height,
    );
    var marker = privacyMarker(image);
    // New for this visit: it arrived with the entry of the bell the album was
    // reached from (issue #268).
    var fresh = state.isFresh(image);
    // The photo "Show in album" opened this album for (issue #289).
    var shown = state.isMarked(image);
    // A reference to a photo of another album (issue #307), marked as such.
    var reference = isReference(image);
    if (image.kind == ImageKind.image &&
        marker == null &&
        !fresh &&
        !shown &&
        !reference) {
      return thumbnail;
    }
    var tile = SizedBox(
      width: width,
      height: height,
      child: Stack(
        fit: StackFit.expand,
        children: [
          thumbnail,
          // A video tile carries the play mark the old client showed.
          if (image.kind != ImageKind.image)
            const Center(
              child: Icon(
                Icons.play_circle_outline,
                key: Key("video-indicator"),
                size: 48,
                color: Colors.white70,
                shadows: [Shadow(color: Colors.black54, blurRadius: 8)],
              ),
            ),
          if (marker != null) Positioned(top: 4, left: 4, child: marker),
          if (reference)
            Positioned(
              bottom: 4,
              left: 4,
              child: Icon(
                Icons.link,
                key: referenceMarkKey,
                size: 18,
                color: Colors.white,
                shadows: const [Shadow(color: Colors.black87, blurRadius: 6)],
                semanticLabel:
                    AppLocalizations.of(state.context)!.referenceMark,
              ),
            ),
          if (shown)
            const Positioned.fill(
              child: IgnorePointer(
                child: DecoratedBox(
                  key: shownInAlbumMarkKey,
                  decoration: BoxDecoration(
                    border: Border.fromBorderSide(
                      BorderSide(color: Colors.amberAccent, width: 4),
                    ),
                  ),
                ),
              ),
            ),
          if (fresh)
            Positioned(
              top: 4,
              right: 4,
              child: Container(
                key: newsFreshMarkerKey,
                width: 12,
                height: 12,
                decoration: BoxDecoration(
                  color: Colors.lightBlueAccent,
                  shape: BoxShape.circle,
                  border: Border.all(color: Colors.white, width: 2),
                ),
              ),
            ),
        ],
      ),
    );
    if (image.kind == ImageKind.image ||
        state.editMode ||
        state.selectMode ||
        state.previewing) {
      // Nothing to tease, and nothing to tease *with* while the album is
      // being edited or looked at as somebody else: a video starting under
      // the pointer of somebody dragging tiles is noise, see issue #75.
      return tile;
    }
    return VideoTeaser(
      teaserUrl: state.client.teaserUrl("${state.albumUrl}${image.name}"),
      headers: state.client.authHeaders,
      signUrl: state.client.mediaSigner(),
      probeTeaser: state.client.renditionState,
      child: tile,
    );
  }

  /// The mark of an image the album does not show to everyone, `null` for a
  /// public one (issue #46).
  ///
  /// It is on the tile in the view mode as well as in the edit mode: the
  /// author sees at a glance what the family, or a share link, will not be
  /// shown.
  Widget? privacyMarker(ImagePart image) {
    var level = image.privacy;
    var icon = privacyIcon(level);
    if (icon == null) {
      return null;
    }
    return IgnorePointer(
      child: Tooltip(
        message: privacyName(AppLocalizations.of(state.context)!, level),
        child: Icon(
          icon,
          key: const Key("privacy-marker"),
          size: 18,
          color: Colors.white,
          shadows: const [Shadow(color: Colors.black, blurRadius: 4)],
        ),
      ),
    );
  }
}

/// How a tile draws one of its tool buttons: the icon, the tooltip, the
/// action, whether the button is highlighted, and its key.
typedef ToolButtonBuilder = Widget Function(
  IconData icon,
  String tooltip,
  VoidCallback onPressed, {
  bool active,
  Key? key,
});

/// The four rating buttons of a tile — very good, good, poor and trash — for
/// an image rated [rating], each calling [onRate] with its value.
///
/// One definition for the album's edit tile and the inbox tile (issue #160),
/// each drawing the buttons in its own toolbar through [button]. Pressing the
/// active button resets the rating, see [toggleRating], which is the caller's
/// business: [onRate] is handed the button's value, not the new rating.
List<Widget> ratingButtons(
  AppLocalizations l10n, {
  required int rating,
  required void Function(int value) onRate,
  required ToolButtonBuilder button,
}) =>
    [
      for (var (icon, tooltip, value, name) in [
        (Icons.star, l10n.ratingVeryGood, 2, "very-good"),
        (Icons.add, l10n.ratingGood, 1, "good"),
        (Icons.remove, l10n.ratingPoor, -1, "poor"),
        (Icons.delete, l10n.ratingTrash, -2, "trash"),
      ])
        button(
          icon,
          tooltip,
          () => onRate(value),
          active: isActiveRating(rating, value),
          key: Key("rating-$name"),
        ),
    ];

/// What a tile's rating button acts on (issue #224): the whole [selection]
/// where [tapped] is part of a selection of several, otherwise [tapped] alone
/// — the rule every per-tile action of a selection follows (#139, #156, #222).
///
/// Compared by identity, as everywhere in the album model.
List<T> ratingTargets<T>(T tapped, Iterable<T> selection) {
  var chosen = selection.toList();
  return chosen.length > 1 && chosen.any((item) => identical(item, tapped))
      ? chosen
      : [tapped];
}

/// Asks before [count] photos are rated as trash in one gesture (issue
/// #224): "Move n photos to the trash?" — Cancel / Move to trash.
///
/// Trash is the one rating easy to hit by accident on a selection; a single
/// photo is asked nothing, because "Show trash" restores it (#152). Answers
/// whether to go on. Shared by the album's edit mode and the inbox.
Future<bool> confirmTrashing(BuildContext context, int count) async {
  if (count <= 1) {
    return true;
  }
  var l10n = AppLocalizations.of(context)!;
  var confirmed = await showAppDialog<bool>(
    context: context,
    builder: (context) => AppAlertDialog(
      key: const Key("trash-confirm-dialog"),
      title: Text(l10n.trashSeveralQuestion(count)),
      actions: [
        TextButton(
          key: const Key("trash-confirm-cancel"),
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("trash-confirm"),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.moveToTrash),
        ),
      ],
    ),
  );
  return confirmed == true;
}

/// The tile of an image in the album edit mode: selection and the three
/// overlay toolbars.
class ThumbnailEditor extends StatefulWidget {
  final AlbumContentState state;
  final ImageWidgetBuilder builder;

  /// The image shown, the representative if [part] is an [ImageGroup].
  final ImagePart image;

  /// The album part this tile stands for.
  final AlbumPart part;

  const ThumbnailEditor(
    this.state,
    this.builder,
    this.image,
    this.part, {
    super.key,
  });

  @override
  State<StatefulWidget> createState() => ThumbnailEditorState();
}

class ThumbnailEditorState extends State<ThumbnailEditor> {
  bool _hovered = false;

  AlbumContentState get album => widget.state;
  ImagePart get image => widget.image;
  AlbumPart get part => widget.part;

  /// Whether the tile shows as selected: all shots of the folded burst it
  /// stands for (issue #314), see [AlbumContentState.tileSelected].
  bool get selected => album.tileSelected(part);

  /// Whether more is selected than this tile stands for: the shots of a
  /// burst alone are one tile's, as a group's members are (issue #314).
  bool get multiSelected =>
      selected && album.selection.length > album.standsFor(part).length;

  /// Whether the tile shows its toolbars.
  bool get active => selected || _hovered;

  /// The words this tile reads in.
  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  @override
  Widget build(BuildContext context) {
    var width = widget.builder.width;
    var height = widget.builder.height;
    // The badge of a burst (issue #314) keeps the bottom right corner, and
    // what else stands there makes room for it.
    var badge = widget.builder.stackBadge(part as AbstractImage);
    var badgeRoom = badge == null ? 0.0 : 48.0;

    return ReorderablePart(
      album: album,
      part: part,
      feedback: SizedBox(
        width: width,
        height: height,
        child: widget.builder.imageThumbnail(image),
      ),
      child: MouseRegion(
        hitTestBehavior: HitTestBehavior.translucent,
        opaque: false,
        onEnter: (event) => setState(() => _hovered = true),
        onExit: (event) => setState(() => _hovered = false),
        child: SizedBox(
          width: width,
          height: height,
          child: Stack(
            children: [
              Positioned.fill(
                // The tile shell of every mode (issue #290): a tap selects,
                // a long press toggles, a secondary click opens the tile's
                // menu at the pointer (issue #156). The tile of a folded
                // burst does so with all of its shots (issue #314).
                child: PhotoTileGestures(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => album.tapTile(part),
                  onLongPress: () => album.toggleTile(part),
                  onMenu: showTileMenu,
                  child: widget.builder.imageThumbnail(image),
                ),
              ),
              if (isIndexPicture(album.widget.album, image))
                Positioned(
                  right: 4 + badgeRoom,
                  bottom: 4,
                  child: IgnorePointer(
                    child: Tooltip(
                      message: _l10n.albumPictureTooltip,
                      child: const Icon(
                        Icons.photo_album,
                        key: Key("album-index-picture"),
                        color: Colors.amberAccent,
                        shadows: [Shadow(color: Colors.black, blurRadius: 4)],
                      ),
                    ),
                  ),
                ),
              if (selected)
                Positioned.fill(
                  child: IgnorePointer(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.blueAccent, width: 3),
                      ),
                    ),
                  ),
                ),
              if (active)
                Positioned(top: 0, left: 0, right: 0, child: topBar()),
              if (active) Positioned.fill(child: Center(child: centerBar())),
              if (active && !image.missing)
                Positioned(
                  bottom: 0,
                  left: 0,
                  right: badgeRoom,
                  child: bottomBar()!,
                ),
              if (badge != null) badge,
            ],
          ),
        ),
      ),
    );
  }

  /// The rotation tools, next to the selection mark.
  ///
  /// A reference whose photograph is gone (issues #221, #307) has nothing to
  /// turn: it is only selected, to be removed.
  Widget topBar() => toolbar([
        toolButton(
          selected ? Icons.check_box : Icons.check_box_outline_blank,
          _l10n.select,
          () => album.toggleTile(part),
          active: selected,
        ),
        // Nor has a file that cannot be read (issue #253).
        if (image.hasPicture) ...[
          toolButton(Icons.rotate_right, _l10n.turnRight, rotateRight),
          toolButton(Icons.swap_vert, _l10n.flipVertically, flipVertically),
          toolButton(Icons.rotate_left, _l10n.turnLeft, rotateLeft),
        ],
      ]);

  /// The tools acting on the selection.
  Widget? centerBar() {
    if (!selected || image.missing) {
      return null;
    }
    var self = part;
    // A reference is never a member of a group (issue #307): it stands for a
    // photograph of another album, one by one, where its author put it.
    var references = album.selectedReferences.isNotEmpty;
    // A search has neither: its photographs lie in other albums and are shown
    // in date order (issue #227).
    var search = isSearch(album.widget.album);
    return toolbar([
      // A file that cannot be read represents no group (issue #253).
      if (!search && multiSelected && !references && image.hasPicture)
        toolButton(Icons.join_left, _l10n.group, createGroup)
      else if (!search && multiSelected)
        toolButton(Icons.title, _l10n.insertHeading, createHeading)
      else if (!search) ...[
        toolButton(Icons.title, _l10n.insertHeading, createHeading),
        if (self is ImageGroup) ...[
          toolButton(
            Icons.call_split,
            _l10n.ungroupAction,
            () => album.ungroupSelected(self),
          ),
          // Into the alternatives to pick the representative: the edit mode
          // stays on for the way back, see [AlbumEditSession].
          toolButton(
            Icons.collections,
            _l10n.chooseGroupPicture,
            () => album.widget.albumState.showGroupView(self),
          ),
        ] else if (image.group case var stack?
            when isStackGroup(stack) && album.shownStacks.foldedOf(self) != null)
          // A burst is a group the author never made (issue #314): its
          // picture is chosen the same way, which makes it a stored group.
          // Nothing to ungroup — its shots are single photographs already.
          toolButton(
            Icons.collections,
            _l10n.chooseGroupPicture,
            () => album.widget.albumState.showGroupView(stack),
          ),
      ],
      // Everything one device contributed, in one gesture — the selection the
      // recording time correction below then works on, see issue #78; on an
      // image without camera data, everything else without any (#274). A
      // file that cannot be read is not offered it (#253).
      if (image.hasPicture)
        toolButton(
          selectSameCameraIcon(image),
          selectSameCameraLabel(_l10n, image),
          () => album.selectSameCamera(image),
        ),
      // A camera whose clock is off files its photos in the wrong place; the
      // correction acts on the whole selection, see issue #77. A file that
      // cannot be read has no recording time, only the time of the file
      // (issue #253), and no picture to stand for the album.
      if (image.hasPicture)
        toolButton(
          Icons.more_time,
          _l10n.adjustRecordingTimeAction,
          () => album.adjustRecordingTimeOf(part),
        ),
      toolButton(Icons.notes, _l10n.imageProperties, editImageProperties),
      // The image standing for the album in the listing above, chosen where
      // the images are compared: the representative of a group stands for it.
      if (image.hasPicture)
        toolButton(
          Icons.photo_album,
          _l10n.useAsAlbumPicture,
          setIndexPicture,
          active: isIndexPicture(album.widget.album, image),
        ),
    ]);
  }

  /// The rating chooser and, beside it, the privacy level.
  Widget? bottomBar() => image.missing
      ? null
      : toolbar([
          ...ratingButtons(
            _l10n,
            rating: image.rating,
            onRate: setRating,
            button: toolButton,
          ),
          privacyButton(),
        ]);

  /// The privacy level of this tile, one tap per step (issue #46).
  ///
  /// One cycling button, not one button per level: the rating already spends
  /// four buttons of this bar, and three more would shrink the whole toolbar
  /// (it scales down to fit the tile) to the point of being unreadable. It
  /// keeps the idiom of the rating buttons where it matters — one tap, no
  /// dialog, and the current value is visible on the tile: the icon *is* the
  /// level, and a restricted level is highlighted the way an active rating is.
  Widget privacyButton() {
    var level = privacyOf(part);
    var next = nextPrivacy(level);
    return toolButton(
      privacyControlIcon(level),
      // The generated signature names the next level first, see
      // `app_localizations.dart`.
      _l10n.privacyControlTooltip(
        privacyName(_l10n, next),
        privacyName(_l10n, level),
      ),
      () => setPrivacy(next),
      active: level != privacyPublic,
      key: const Key("privacy-control"),
    );
  }

  Widget toolbar(List<Widget> buttons) => FittedBox(
        fit: BoxFit.scaleDown,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: buttons),
        ),
      );

  Widget toolButton(
    IconData icon,
    String tooltip,
    VoidCallback onPressed, {
    bool active = false,
    Key? key,
  }) =>
      IconButton(
        key: key,
        icon: Icon(icon),
        iconSize: 18,
        color: active ? Colors.amberAccent : Colors.white,
        tooltip: tooltip,
        padding: const EdgeInsets.all(4),
        visualDensity: VisualDensity.compact,
        constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
        onPressed: onPressed,
      );

  void rotateLeft() => turn(OrientationOps.rotL);

  void rotateRight() => turn(OrientationOps.rotR);

  void flipVertically() => turn(OrientationOps.flipV);

  /// Applies [operation] to this tile's image.
  ///
  /// An image that is the album's picture takes its crop along into the new
  /// frame, so that the listing tile keeps showing what the crop editor shows,
  /// see [syncIndexPictureOrientation] and issue #115.
  ///
  /// A cropped image keeps the same region selected, carried into the new
  /// frame (issue #212, [turnImage]).
  void turn(Orientation Function(Orientation) operation) => album.editImage(() {
        turnImage(image, operation(image.orientation));
        syncIndexPictureOrientation(album.widget.album, image);
      });

  /// Rates by the button for [value]: this tile, or the whole selection
  /// where this tile is part of one of several, see
  /// [AlbumContentState.rate] (issue #224).
  Future<void> setRating(int value) => album.rate(part, value);

  /// Sets the privacy level of this tile's album part.
  ///
  /// A group is set as a whole — it is one thing to a viewer, see
  /// [setPrivacyOf].
  void setPrivacy(int level) =>
      album.editImage(() => setPrivacyOf(part, level));

  /// Makes this tile's image the album's index picture, see [indexPictureOf].
  void setIndexPicture() => album.editImage(
        () => album.widget.album.indexPicture = indexPictureOf(image),
      );

  /// Groups the selected images, this tile's image representing the group.
  void createGroup() {
    var self = part;
    if (self is! AbstractImage || !album.groupSelected(self)) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_l10n.groupNeedsTwo),
          duration: const Duration(seconds: 4),
        ),
      );
    }
  }

  /// Inserts a heading before this tile's part.
  ///
  /// The heading is anchored on what is *displayed* after this tile, not on
  /// the tile's stored index — see [insertHeadingBeforeDisplayed] for why the
  /// two differ and why the display decides (issue #71).
  Future<void> createHeading() async {
    var input = await showFormDialog<HeadingInput>(
      context: context,
      builder: (context) => HeadingDialog(
        title: _l10n.insertHeading,
        text: "",
        level: headingSection,
      ),
    );
    if (input == null || !mounted) {
      return;
    }
    album.editImage(() => insertHeadingBeforeDisplayed(
          album.widget.album,
          part,
          album.displayOrder,
          input.text,
          level: input.level,
        ));
  }

  /// The context menu of this tile, opened by a secondary click at
  /// [position] (issue #156): the catalogue's tile menu, see
  /// [AlbumContentState.showTileMenu] — on the whole selection when this tile
  /// is part of it, otherwise this tile first becomes the selection alone.
  Future<void> showTileMenu(Offset position) {
    var self = part as AbstractImage;
    return album.showTileMenu(PhotoTile(self, image), position);
  }

  /// Edits the description of the image shown by this tile, see
  /// [AlbumContentState.showPhotoProperties] (issue #122).
  Future<void> editImageProperties() =>
      album.showPhotoProperties(PhotoTile(part as AbstractImage, image));
}

/// The side of a tile the insert cursor of a drag is drawn on: the dragged
/// part lands before or behind the part shown by that tile.
enum InsertSide { before, after }

/// The width of the insert cursor, in logical pixels.
const double insertCursorWidth = 4;

/// The factor the dragged tile is reduced by while it follows the pointer.
const double dragFeedbackScale = 0.5;

/// The side of the square drag handle of a tile, in logical pixels.
///
/// A finger's target: Flutter's own reorderable lists hand the drag to a
/// handle on touch platforms for the same reason, see issue #94.
const double dragHandleSize = 40;

/// What a drag of the album's edit mode carries.
///
/// A drag picking up a tile that belongs to the current multi-selection
/// carries the whole selection (issue #41), any other tile carries itself
/// alone, see [AlbumContentState.dragOf].
class DraggedParts {
  /// The carried parts, in the order they are stored in.
  final List<AlbumPart> parts;

  /// The part whose tile was picked up, one of [parts].
  final AlbumPart primary;

  const DraggedParts(this.parts, this.primary);

  /// Whether the given part is one of the carried ones.
  bool contains(AlbumPart part) =>
      parts.any((carried) => identical(carried, part));

  /// Whether more than one part is carried.
  bool get isBlock => parts.length > 1;
}

/// One album part as a drag source and a drop target of the reordering of the
/// edit mode (issue #37).
///
/// Two ways to pick a part up, one machinery (issue #94):
///
///  * a **handle** in the tile's top right corner ([handle]), which lifts the
///    tile immediately and in *any* direction, on every platform. A finger's
///    first movement is rarely straight; its vertical component reaches the
///    touch slop first and the album's scroll view wins the gesture arena, so
///    the sideways pull below all but never lifted a tile on a phone — and
///    nothing on the screen said the gesture existed. Flutter's own
///    reorderable lists answer the same problem the same way.
///  * a **horizontal** drag on the tile itself, which is what a mouse does
///    naturally and what the edit mode had before: the long press already
///    toggles the selection (and enters the edit mode outside of it), the tap
///    opens the tile, and the album scrolls vertically — so the horizontal
///    axis is the only one still free, and [Draggable.affinity] hands it to
///    the drag while every vertical drag stays with the scroll view.
///
/// Both go through [carrier]: the same [DraggedParts] (a tile of the current
/// multi-selection carries the whole selection), the same feedback, the same
/// drop targets, the same insert cursor and the same edge scrolling. A tile
/// is carried anywhere once it is up, whichever way it was lifted.
///
/// While a part is carried over this one, an insert cursor is drawn on the
/// half the pointer is in: dropping puts the dragged part before or behind
/// the part *displayed* here, see [AlbumContentState.dropPart]. A part is not
/// a drop target of itself.
///
/// Nothing is moved while a part is carried around: the tile it was picked up
/// from keeps its box (only dimmed), so the album cannot reflow under the
/// pointer. The rows are laid out anew from the new order after the drop, and
/// the reordering is written to the server by the Save action of the edit
/// mode, with every other edit.
class ReorderablePart extends StatefulWidget {
  final AlbumContentState album;

  /// The part shown by [child], the one dragged and the one dropped onto.
  final AlbumPart part;

  /// What follows the pointer while this part is dragged, reduced to
  /// [dragFeedbackScale] and centred on the pointer.
  final Widget feedback;

  final Widget child;

  const ReorderablePart({
    super.key,
    required this.album,
    required this.part,
    required this.feedback,
    required this.child,
  });

  @override
  State<StatefulWidget> createState() => ReorderablePartState();
}

class ReorderablePartState extends State<ReorderablePart> {
  /// The side the insert cursor is drawn on, `null` while no part is carried
  /// over this one.
  InsertSide? _cursor;

  /// Whether this part is on its way somewhere else, see
  /// [AlbumContentState.isCarried].
  bool get carried => widget.album.isCarried(widget.part);

  @override
  Widget build(BuildContext context) {
    if (isSearch(widget.album.widget.album)) {
      // A search keeps the date order: nothing is dragged (issue #227).
      return widget.child;
    }
    var dragged = widget.album.dragOf(widget.part);
    // A part that is dragged is a gesture surface, see [SelectionExcluded].
    return SelectionExcluded(
        child: DragTarget<DraggedParts>(
      // A carried part is not dropped onto itself.
      onWillAcceptWithDetails: (details) => !details.data.contains(widget.part),
      // A rejected target is told about the move all the same, so a part
      // dragged over itself is filtered out here as well.
      onMove: (details) => showCursor(
        details.data.contains(widget.part) ? null : sideOf(details.offset),
      ),
      onLeave: (data) => showCursor(null),
      onAcceptWithDetails: (details) {
        var side = sideOf(details.offset);
        showCursor(null);
        widget.album.dropPart(details.data, widget.part, side);
      },
      builder: (context, candidate, rejected) => Stack(
        children: [
          carrier(
            dragged,
            // Only a sideways pull picks the *tile* up, see [ReorderablePart].
            affinity: Axis.horizontal,
            childWhenDragging: Opacity(opacity: 0.3, child: widget.child),
            child: carried
                ? Opacity(opacity: 0.3, child: widget.child)
                : widget.child,
          ),
          // The handle, which picks the tile up in any direction and on every
          // platform, see [ReorderablePart] (issue #94).
          Positioned(top: 0, right: 0, child: handle(dragged)),
          if (_cursor != null)
            Positioned(
              top: 0,
              bottom: 0,
              left: _cursor == InsertSide.before ? 0 : null,
              right: _cursor == InsertSide.after ? 0 : null,
              child: const IgnorePointer(
                child: SizedBox(
                  key: Key("insert-cursor"),
                  width: insertCursorWidth,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.amberAccent,
                      boxShadow: [
                        BoxShadow(color: Colors.black, blurRadius: 4),
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    ));
  }

  /// A [Draggable] carrying [dragged], built once for the tile and once for
  /// its handle (issue #94).
  ///
  /// Everything that makes the reordering work is in here and is therefore
  /// the same for both: the carried parts, the start and the end of the
  /// carry, the feedback that follows the pointer and the anchor the drop
  /// targets read the insert side from. The two differ in one argument only,
  /// the [affinity] — which is the whole point of the handle.
  Widget carrier(
    DraggedParts dragged, {
    Axis? affinity,
    Key? key,
    Widget? childWhenDragging,
    required Widget child,
  }) =>
      Draggable<DraggedParts>(
        key: key,
        data: dragged,
        onDragStarted: () => widget.album.startCarry(dragged),
        // Whether the drop was taken or the drag was cancelled: the parts
        // are no longer on their way, see [AlbumContentState.endCarry].
        onDragEnd: (details) => widget.album.endCarry(),
        onDraggableCanceled: (velocity, offset) => widget.album.endCarry(),
        affinity: affinity,
        // The tile itself: its [MouseRegion] is not opaque and reports the
        // hit as a miss (`RenderMouseRegion.hitTest`), which would keep
        // the pointer of the drag from ever reaching this [Draggable].
        hitTestBehavior: HitTestBehavior.opaque,
        // The pointer itself is the anchor, so that the offset reported to
        // the drop targets is the position of the pointer and not the
        // corner of the feedback, see [sideOf].
        dragAnchorStrategy: pointerDragAnchorStrategy,
        feedback: FractionalTranslation(
          key: const Key("drag-feedback"),
          translation: const Offset(-0.5, -0.5),
          child: Opacity(
            opacity: 0.75,
            child: Material(
              type: MaterialType.transparency,
              child: Transform.scale(
                scale: dragFeedbackScale,
                child: feedbackOf(dragged),
              ),
            ),
          ),
        ),
        childWhenDragging: childWhenDragging,
        child: child,
      );

  /// The grip a tile is picked up by, in its top right corner (issue #94).
  ///
  /// [dragHandleSize] square, which is a finger's target, and always visible
  /// in the edit mode — a gesture nobody can see is a gesture nobody uses,
  /// and the sideways pull was exactly that.
  Widget handle(DraggedParts dragged) => carrier(
        dragged,
        // No affinity at all: the handle exists to be pulled in *any*
        // direction, see [ReorderablePart]. It is a small target of its own,
        // so the scroll view loses nothing by it.
        child: Tooltip(
          message: AppLocalizations.of(context)!.dragToReorder,
          child: const SizedBox(
            key: Key("drag-handle"),
            width: dragHandleSize,
            height: dragHandleSize,
            child: Icon(
              Icons.drag_indicator,
              size: 22,
              color: Colors.white,
              shadows: [Shadow(color: Colors.black, blurRadius: 4)],
            ),
          ),
        ),
      );

  /// What follows the pointer: the tile picked up, and how many parts are on
  /// their way with it if it is a whole block, see [DraggedParts].
  Widget feedbackOf(DraggedParts dragged) {
    if (!dragged.isBlock) {
      return widget.feedback;
    }
    return Stack(
      clipBehavior: Clip.none,
      children: [
        widget.feedback,
        Positioned(
          right: 0,
          bottom: 0,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Colors.blueAccent,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Text(
                AppLocalizations.of(context)!.partCount(dragged.parts.length),
                key: const Key("drag-feedback-count"),
                style: const TextStyle(
                  fontSize: 28,
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  void showCursor(InsertSide? side) {
    if (_cursor != side && mounted) {
      setState(() => _cursor = side);
    }
  }

  /// The half of this part the given global pointer position is in.
  InsertSide sideOf(Offset pointer) {
    var box = context.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) {
      return InsertSide.after;
    }
    var local = box.globalToLocal(pointer);
    return local.dx < box.size.width / 2 ? InsertSide.before : InsertSide.after;
  }
}

/// What the heading dialog answers: the text and the level of the heading
/// (issue #158), `null` from the dialog when it was cancelled.
class HeadingInput {
  final String text;

  /// [headingSection] or [headingSubsection].
  final int level;

  const HeadingInput(this.text, this.level);
}

/// The dialog of a heading: its text and its level, a section or a
/// subsection (issue #158), used to insert a heading before a tile, to append
/// one at the end of the album and to edit one.
class HeadingDialog extends StatefulWidget {
  final String title;
  final String text;
  final int level;

  const HeadingDialog({
    super.key,
    required this.title,
    required this.text,
    required this.level,
  });

  @override
  State<StatefulWidget> createState() => HeadingDialogState();
}

class HeadingDialogState extends State<HeadingDialog> {
  late final TextEditingController controller =
      TextEditingController(text: widget.text);

  late int level = widget.level;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  void apply() =>
      Navigator.of(context).pop(HeadingInput(controller.text, level));

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    Widget choice(int value, String label) => ChoiceChip(
          key: Key("heading-level-$value"),
          label: Text(label),
          selected: level == value,
          onSelected: (_) => setState(() => level = value),
        );
    return AppAlertDialog(
      // The title and the fields scroll where a phone's keyboard leaves too
      // little room for them; the buttons stay (issue #178).
      scrollable: true,
      title: Text(widget.title),
      content: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: controller,
            autofocus: true,
            decoration: InputDecoration(label: Text(l10n.headingLabel)),
            onSubmitted: (_) => apply(),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            children: [
              choice(headingSection, l10n.headingLevelSection),
              choice(headingSubsection, l10n.headingLevelSubsection),
            ],
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton.icon(
          icon: const Icon(Icons.check),
          label: Text(l10n.apply),
          onPressed: apply,
        ),
      ],
    );
  }
}

/// A dialog editing a single text, used for image comments.
class TextInputDialog extends StatefulWidget {
  final String title;
  final String label;
  final String text;
  final bool multiLine;

  /// Read-only lines under the field, what the dialog shows but does not
  /// edit: the file name (issue #103), the recording time and the camera of
  /// an image (issue #78).
  ///
  /// Widgets, not texts, so that each line carries its own key: the image
  /// properties are composed in one place and addressed line by line, see
  /// `image_properties.dart` (issues #122, #112). They are laid out in the
  /// dialog's small style; a [SelectableText] lets a file name be copied out.
  final List<Widget> details;

  /// A read-only line under the field, `null` where there is nothing to say.
  ///
  /// What the dialog shows besides what it edits: the attribution of issue
  /// #53, which is derived by the server and can therefore not be edited here
  /// — the field is what the album stores, the note is what the server knows.
  final String? note;

  /// Further edited fields under the text field, whose state is their owner's,
  /// see [answer]: the place caption choice of an image's properties.
  final List<Widget> fields;

  /// What "Apply" answers for the typed text, the text itself where `null`.
  ///
  /// A dialog that edits more than the text ([fields]) answers all of it at
  /// once, see `ImagePropertiesDialog`.
  final Object Function(String text)? answer;

  const TextInputDialog({
    super.key,
    required this.title,
    required this.label,
    required this.text,
    this.multiLine = false,
    this.note,
    this.details = const [],
    this.fields = const [],
    this.answer,
  });

  @override
  State<StatefulWidget> createState() => TextInputDialogState();
}

class TextInputDialogState extends State<TextInputDialog> {
  late final TextEditingController controller =
      TextEditingController(text: widget.text);

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FormDialogFrame(
      title: Text(widget.title),
      fields: [
        TextField(
          controller: controller,
          autofocus: true,
          minLines: widget.multiLine ? 3 : 1,
          maxLines: widget.multiLine ? 8 : 1,
          decoration: InputDecoration(label: Text(widget.label)),
        ),
        ...widget.fields,
        if (widget.details.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: DefaultTextStyle.merge(
              style: Theme.of(context).textTheme.bodySmall,
              child: Column(
                key: const Key("properties-details"),
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: widget.details,
              ),
            ),
          ),
        if (widget.note != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              widget.note!,
              key: const Key("properties-contributor"),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
      ],
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(AppLocalizations.of(context)!.cancel),
        ),
        ElevatedButton.icon(
          icon: const Icon(Icons.check),
          label: Text(AppLocalizations.of(context)!.apply),
          onPressed: () => Navigator.of(context).pop(
            widget.answer?.call(controller.text) ?? controller.text,
          ),
        ),
      ],
    );
  }
}

/// What the "Adjust recording time" dialog answers, `null` when it was
/// cancelled (issues #77, #102).
///
/// Two corrections that differ in kind: one *offset*, entered once and added
/// to every selected image (issue #77, a camera whose clock was off by the
/// same amount all along), or every image dated by its **own** file name
/// (issue #102, files whose recording time the server could only guess from
/// the write time, which is a different lie for each of them).
sealed class TimeCorrection {
  const TimeCorrection();
}

/// Move the reference image to [corrected], and everything else by the same
/// offset, see [adjustRecordingTime].
class ShiftToTime extends TimeCorrection {
  /// The time the reference image should carry.
  final DateTime corrected;

  const ShiftToTime(this.corrected);
}

/// Date every image by the time its own file name says, see
/// [AlbumContentState.applyNameDates].
class UseNameDates extends TimeCorrection {
  const UseNameDates();
}

/// The recording time the file name of [image] says where that differs from
/// the time the image carries, `null` otherwise (issue #102).
///
/// `null` for an image whose name says no date, for one whose name says what
/// it already carries, and for one that carries no date at all (`0` means
/// "unknown", and [offsetFor] refuses to compute an offset from it).
///
/// "Differs" is a minute: a part the server dated *from its own name* carries
/// that very second, and a difference of seconds is not worth offering a
/// correction for — what this repairs is an album dated by the write times of
/// a copy, which are off by hours, days or years.
DateTime? differingNameDate(ImagePart image) {
  if (image.date == 0) {
    return null;
  }
  var named = nameDate(image.name);
  if (named == null) {
    return null;
  }
  var stored = DateTime.fromMillisecondsSinceEpoch(image.date);
  return named.difference(stored).abs() < const Duration(minutes: 1)
      ? null
      : named;
}

/// Asks for the correct recording time of one image of the selection and
/// says what that does to the others, see issue #77.
///
/// The dialog answers a [TimeCorrection], `null` when it was cancelled; the
/// offset a [ShiftToTime] applies is the difference to the time the reference
/// carries now (see [offsetFor]).
///
/// The time is edited as text, `yyyy-MM-dd HH:mm:ss`, with the pickers behind
/// the button beside it: a camera that is off by two hours and thirteen
/// minutes is corrected by typing, not by spinning a clock face, and the text
/// is what says precisely which second was meant.
class AdjustRecordingTimeDialog extends StatefulWidget {
  /// The image whose correct time is entered — the offset is its difference.
  final ImagePart reference;

  /// The images the correction is applied to, the reference included.
  ///
  /// The count the dialog names, and — since issue #102 — what it looks
  /// through for file names carrying a recording time of their own, see
  /// [differingNameDate].
  final List<ImagePart> images;

  const AdjustRecordingTimeDialog({
    super.key,
    required this.reference,
    required this.images,
  });

  /// How many images the correction is applied to.
  int get count => images.length;

  @override
  State<StatefulWidget> createState() => AdjustRecordingTimeDialogState();
}

class AdjustRecordingTimeDialogState extends State<AdjustRecordingTimeDialog> {
  /// How a recording time is written in this dialog, read and shown.
  static final DateFormat timeFormat = DateFormat("yyyy-MM-dd HH:mm:ss");

  late final TextEditingController controller;

  /// The time entered, `null` while the text is not a time.
  DateTime? corrected;

  /// The time the reference image carries now.
  DateTime get current =>
      DateTime.fromMillisecondsSinceEpoch(widget.reference.date);

  @override
  void initState() {
    super.initState();
    corrected = current;
    controller = TextEditingController(text: timeFormat.format(current))
      ..addListener(readTime);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  /// Reads the time out of the field, `null` while it is not one.
  void readTime() {
    DateTime? value;
    try {
      value = timeFormat.parseStrict(controller.text.trim());
    } catch (_) {
      value = null;
    }
    if (value != corrected) {
      setState(() => corrected = value);
    }
  }

  /// The offset the dialog is about to apply, `null` while there is no time.
  Duration? get offset => offsetFor(widget.reference, corrected);

  /// What the offset line says.
  String offsetText(AppLocalizations l10n) {
    var value = offset;
    if (value == null) {
      return l10n.notATime;
    }
    return value == Duration.zero
        ? l10n.nothingToAdjust
        : offsetInWords(l10n, value);
  }

  /// What the count line says.
  String countText(AppLocalizations l10n) => l10n.appliesToImages(widget.count);

  /// The images whose file name says a recording time other than the one they
  /// carry — what the suggestion of issue #102 would repair.
  late final List<ImagePart> nameDated = [
    for (var image in widget.images)
      if (differingNameDate(image) != null) image,
  ];

  /// What the "use the file name" button says.
  ///
  /// One image: the very time it would get, so that it can be read before it
  /// is applied. Several: how many of the selection it applies to, because
  /// each of them gets a *different* time and no single one could be shown —
  /// and because the ones whose name says nothing stay as they are, which is
  /// what a count that is smaller than the selection tells.
  String nameDateText(AppLocalizations l10n) {
    if (nameDated.length == 1) {
      var named = differingNameDate(nameDated.first)!;
      return l10n.useNameDateOne(timeFormat.format(named));
    }
    return l10n.useNameDateMany(nameDated.length);
  }

  /// Fills the field from the date and time pickers.
  Future<void> pickTime() async {
    var start = corrected ?? current;
    var day = await showDatePicker(
      context: context,
      initialDate: start,
      firstDate: firstAlbumDate,
      lastDate: lastAlbumDate,
    );
    if (day == null || !mounted) {
      return;
    }
    var time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(start),
    );
    if (!mounted) {
      return;
    }
    var picked = DateTime(
      day.year,
      day.month,
      day.day,
      time?.hour ?? start.hour,
      time?.minute ?? start.minute,
    );
    controller.text = timeFormat.format(picked);
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var small = Theme.of(context).textTheme.bodySmall;
    return FormDialogFrame(
      title: Text(l10n.adjustRecordingTimeTitle),
      fields: [
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            "${widget.reference.name}: ${timeFormat.format(current)}",
            key: const Key("adjust-reference"),
          ),
        ),
        Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key("adjust-time"),
                controller: controller,
                autofocus: true,
                decoration: InputDecoration(
                  label: Text(l10n.correctTime),
                ),
              ),
            ),
            IconButton(
              key: const Key("adjust-pick"),
              tooltip: l10n.pickDateAndTime,
              icon: const Icon(Icons.edit_calendar),
              onPressed: pickTime,
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Text(offsetText(l10n), key: const Key("adjust-offset")),
        ),
        Text(countText(l10n), key: const Key("adjust-count")),
        // One tap for what the file names already say, offered only where
        // there is something to repair, see issue #102.
        if (nameDated.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: OutlinedButton.icon(
              key: const Key("use-name-date"),
              icon: const Icon(Icons.drive_file_rename_outline),
              label: Text(nameDateText(l10n)),
              onPressed: () => Navigator.of(context).pop(const UseNameDates()),
            ),
          ),
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Text(
            l10n.adjustRecordingTimeHelp,
            key: const Key("adjust-help"),
            style: small,
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
          onPressed: corrected == null
              ? null
              : () => Navigator.of(context).pop(ShiftToTime(corrected!)),
        ),
      ],
    );
  }
}

/// The values edited by the [AlbumPropertiesDialog].
class AlbumProperties {
  final String title;
  final String subTitle;

  /// The explicit date of the album in milliseconds since the epoch, `0` when
  /// the author has set none, see [AlbumInfo.date].
  ///
  /// The explicit date and only that one: what the album is actually sorted
  /// and filed by ([AlbumInfo.effectiveDate]) is derived by the server and is
  /// never edited here, see issue #48.
  final int date;

  /// The picture standing for the album in the listing above, with its crop.
  final ThumbnailInfo? indexPicture;

  /// What this folder is: an ordinary album or the inbox of the space.
  ///
  /// Read only (issue #226): the server decides by where the folder lies, and
  /// the dialog of the inbox shows no date and no album picture.
  final AlbumKind kind;

  /// Whether the photos of the album show their place as caption where they
  /// have no description, see [AlbumInfo.placeCaption]: "Display locations".
  final PlaceCaption placeCaption;

  const AlbumProperties({
    required this.title,
    required this.subTitle,
    this.date = 0,
    this.indexPicture,
    this.kind = AlbumKind.album,
    this.placeCaption = PlaceCaption.unset,
  });
}

/// The size of the crop editor's preview of the index picture.
const double indexPictureEditorSize = 200;

/// The height the album properties need for their content to show the crop
/// editor beside at least one field, see [AlbumPropertiesDialogState.build]:
/// the title, the editor with its zoom row, the buttons, and one field.
const double cropEditorNeedsHeight = 420;

/// The factor one zoom step of the crop editor scales by.
const double indexPictureZoomStep = 1.25;

/// Edits the title, the subtitle and the crop of the index picture of an
/// album.
///
/// The picture itself is chosen on a tile of the album (see
/// [ThumbnailEditorState.setIndexPicture]); here it is framed: dragged to
/// pan, pinched, wheeled or stepped to zoom within the square the listing
/// shows it in, see [indexPictureTile]. Nothing is applied before
/// "Übernehmen".
class AlbumPropertiesDialog extends StatefulWidget {
  final AlbumProperties properties;

  /// The transport for the preview of the index picture.
  final VAlbumClient? client;

  /// The URL of the album, the preview is loaded below it.
  final String baseUrl;

  /// The image the index picture names, for the default framing of the
  /// "reset" tool; `null` if there is none.
  final ImagePart? indexImage;

  /// The date the album is sorted and filed by, see [AlbumInfo.effectiveDate].
  ///
  /// Shown while no explicit date is set, so that the dialog never shows a
  /// date out of nowhere; read only, never edited.
  final int effectiveDate;

  /// Where [effectiveDate] comes from, so the dialog can say it.
  final DateSource dateSource;

  /// Whether the dialog only shows what the album is, for whoever may not
  /// edit it (issue #295): its fields read-only, no picture to frame, and
  /// "Close" its one button.
  final bool readOnly;

  const AlbumPropertiesDialog(
    this.properties, {
    super.key,
    this.client,
    this.baseUrl = "",
    this.indexImage,
    this.effectiveDate = 0,
    this.dateSource = DateSource.none,
    this.readOnly = false,
  });

  @override
  State<StatefulWidget> createState() => AlbumPropertiesDialogState();
}

class AlbumPropertiesDialogState extends State<AlbumPropertiesDialog> {
  late final TextEditingController titleController =
      TextEditingController(text: widget.properties.title);
  late final TextEditingController subTitleController =
      TextEditingController(text: widget.properties.subTitle);

  /// The explicit date being edited, `0` while none is set.
  late int date = widget.properties.date;

  /// Whether "Display locations" is on: unless the album said
  /// [PlaceCaption.hidden], an album that said nothing shows them.
  late bool placesShown = widget.properties.placeCaption != PlaceCaption.hidden;

  /// The crop being edited, a copy: the album's own is replaced on apply.
  late ThumbnailInfo? indexPicture = copyOf(widget.properties.indexPicture);

  /// Whether the dialog is editing the inbox of the space.
  ///
  /// An inbox has no date and no album picture — the server derives neither
  /// for one — so those two rows are not shown: a field that is written and
  /// then ignored is worse than no field.
  bool get isInbox => widget.properties.kind == AlbumKind.inbox;

  /// The crop when the current gesture started, see [onScaleUpdate].
  ThumbnailInfo? _gestureStart;
  Offset _gestureFocus = Offset.zero;

  static ThumbnailInfo? copyOf(ThumbnailInfo? info) => info == null
      ? null
      : ThumbnailInfo(
          image: info.image,
          scale: info.scale,
          tx: info.tx,
          ty: info.ty,
          // The frame the crop is measured in travels with it, see issue #115.
          orientation: info.orientation,
        );

  @override
  void dispose() {
    titleController.dispose();
    subTitleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return FormDialogFrame(
      // The crop editor needs every line a phone has, see issue #136.
      dense: true,
      title: Text(l10n.albumProperties),
      fields: [
        TextField(
          key: const Key("album-title"),
          controller: titleController,
          autofocus: !widget.readOnly,
          readOnly: widget.readOnly,
          decoration: InputDecoration(label: Text(l10n.titleLabel)),
        ),
        TextField(
          key: const Key("album-subtitle"),
          controller: subTitleController,
          readOnly: widget.readOnly,
          decoration: InputDecoration(label: Text(l10n.subtitleLabel)),
        ),
        if (!isInbox) buildDateRow(context),
        // An inbox shows nothing but what waits to be sorted: like its date,
        // its captions are not its own business.
        if (!isInbox)
          SwitchListTile(
            key: const Key("album-place-caption"),
            contentPadding: EdgeInsets.zero,
            title: Text(l10n.displayLocations),
            value: placesShown,
            onChanged: widget.readOnly
                ? null
                : (value) => setState(() => placesShown = value),
          ),
      ],
      // Only the fields above scroll (issue #178). The crop editor stands
      // below them, outside the scroll view: a scroll view would take the
      // pan gesture away from it (issue #136). It cannot shrink either, so
      // where the dialog is shorter than [cropEditorNeedsHeight] — a phone
      // with its keyboard open, or turned sideways — it steps aside and the
      // fields have the room; the crop is kept in this state and the editor
      // is back as soon as there is room again (on a phone, a touch beside
      // the field closes the keyboard). A desktop always has the room.
      fixed: [
        if (!isInbox && !widget.readOnly) buildIndexPictureEditor(context),
      ],
      fixedNeedsHeight: cropEditorNeedsHeight,
      actions: widget.readOnly
          ? [
              TextButton(
                key: const Key("album-properties-close"),
                onPressed: () => Navigator.of(context).pop(),
                child: Text(l10n.close),
              ),
            ]
          : [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(l10n.cancel),
              ),
              ElevatedButton.icon(
                icon: const Icon(Icons.check),
                label: Text(l10n.apply),
                onPressed: applyPressed,
              ),
            ],
    );
  }

  /// The date of the album: the explicit one when it is set, else the date
  /// the server derived and where it derived it from.
  ///
  /// Nothing is shown out of nowhere: an album whose date comes from its
  /// folder name or from its photos says so, so that the empty field is not
  /// read as "this album has no date", see issue #48.
  Widget buildDateRow(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var shown = date != 0 ? date : derivedDate;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                shown == 0 ? l10n.dateNone : l10n.dateIs(formatDate(shown)),
                key: const Key("album-date"),
              ),
            ),
            if (!widget.readOnly) ...[
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.date_range),
                tooltip: l10n.pickDate,
                onPressed: pickDate,
              ),
              IconButton(
                key: const Key("album-date-clear"),
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.clear),
                tooltip: l10n.clearDate,
                // Back to "no explicit date": what the server derives then
                // takes over again.
                onPressed: date == 0 ? null : () => setState(() => date = 0),
              ),
            ],
          ],
        ),
        if (date == 0 && dateSourceText(l10n) != null)
          Text(
            dateSourceText(l10n)!,
            key: const Key("album-date-source"),
            style: Theme.of(context).textTheme.bodySmall,
          ),
      ],
    );
  }

  /// The date the server derived for this album, `0` when what it sent was
  /// the explicit date this dialog has just cleared.
  ///
  /// What the server will derive for an album whose explicit date is taken
  /// away is not known here — so nothing is claimed: the field reads "keines"
  /// until the album has been saved and loaded again.
  int get derivedDate => widget.properties.date == 0 ? widget.effectiveDate : 0;

  /// Where the date shown comes from while none is set here, `null` when
  /// there is no date at all.
  String? dateSourceText(AppLocalizations l10n) => switch (widget.dateSource) {
        DateSource.folderName => l10n.dateFromFolderName,
        DateSource.photos => l10n.dateFromPhotos,
        _ => null,
      };

  static String formatDate(int millis) => DateFormat("yyyy-MM-dd")
      .format(DateTime.fromMillisecondsSinceEpoch(millis));

  /// Asks for the day the album happened on, starting at the date it is
  /// shown with.
  Future<void> pickDate() async {
    var shown = date != 0 ? date : derivedDate;
    var initial = shown == 0
        ? DateTime.now()
        : DateTime.fromMillisecondsSinceEpoch(shown);
    var picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: firstAlbumDate,
      lastDate: lastAlbumDate,
    );
    if (picked == null || !mounted) {
      return;
    }
    // Local midnight of the day that was picked: a date, not an instant.
    setState(() => date =
        DateTime(picked.year, picked.month, picked.day).millisecondsSinceEpoch);
  }

  /// The square preview of the index picture with the pan and zoom gestures,
  /// and the zoom tools below it; a hint if no picture is chosen.
  Widget buildIndexPictureEditor(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var info = indexPicture;
    var client = widget.client;
    if (info == null || client == null) {
      return Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(
          l10n.noAlbumPictureHint,
          key: const Key("index-picture-hint"),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 4),
          // Panned and zoomed, see [SelectionExcluded].
          child: SelectionExcluded(
            child: Listener(
              onPointerSignal: onPointerSignal,
              child: GestureDetector(
                onScaleStart: onScaleStart,
                onScaleUpdate: onScaleUpdate,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.grey),
                  ),
                  child: indexPictureTile(
                    client,
                    // The cut picture of a cropped photograph, on which the
                    // album picture is measured (issue #212).
                    _croppedImage == null
                        ? "${widget.baseUrl}/${info.image}"
                        : croppedImageUrl(
                            "${widget.baseUrl}/${info.image}", _croppedImage!),
                    info,
                    indexPictureEditorSize,
                    key: const Key("index-picture-editor"),
                  ),
                ),
              ),
            ),
          ),
        ),
        Row(
          children: [
            IconButton(
              icon: const Icon(Icons.zoom_in),
              tooltip: l10n.zoomIn,
              onPressed: info.scale <
                      greatestIndexPictureScale(
                          info, pictureWidth, pictureHeight)
                  ? () => zoom(indexPictureZoomStep)
                  : null,
            ),
            IconButton(
              icon: const Icon(Icons.zoom_out),
              tooltip: l10n.zoomOut,
              // Disabled where the picture just covers the square: it
              // may not be zoomed out any further (issue #154).
              onPressed: info.scale >
                      leastIndexPictureScale(
                              info, pictureWidth, pictureHeight) +
                          1e-6
                  ? () => zoom(1 / indexPictureZoomStep)
                  : null,
            ),
            IconButton(
              icon: const Icon(Icons.crop_free),
              tooltip: l10n.resetCrop,
              onPressed: widget.indexImage == null ? null : resetCrop,
            ),
          ],
        ),
      ],
    );
  }

  /// The size of the file the crop shows, `0` where it is not known — then
  /// the crop is not held to covering the square, there being nothing to
  /// measure it against (issue #154).
  ///
  /// The size of the region of a cropped photograph (issue #212): the album
  /// picture is measured on the picture as it is shown.
  int get pictureWidth {
    var image = _croppedImage;
    return image == null ? 0 : renditionSize(image).width.round();
  }

  /// See [pictureWidth].
  int get pictureHeight {
    var image = _croppedImage;
    return image == null ? 0 : renditionSize(image).height.round();
  }

  /// The image the crop shows, `null` unless it is the album's picture this
  /// dialog was opened with.
  ImagePart? get _croppedImage {
    var image = widget.indexImage;
    return image != null && image.name == indexPicture?.image ? image : null;
  }

  void onScaleStart(ScaleStartDetails details) {
    _gestureStart = indexPicture;
    _gestureFocus = details.localFocalPoint;
  }

  /// A drag pans, a pinch zooms; both relative to the state at the start of
  /// the gesture, so the crop follows the fingers instead of accumulating
  /// rounding.
  void onScaleUpdate(ScaleUpdateDetails details) {
    var start = _gestureStart;
    if (start == null) {
      return;
    }
    var shift = details.localFocalPoint - _gestureFocus;
    var zoomed = details.scale == 1
        ? start
        : zoomIndexPicture(
            start,
            details.scale,
            width: pictureWidth,
            height: pictureHeight,
          );
    setState(() {
      indexPicture = panIndexPicture(
        zoomed,
        shift.dx,
        shift.dy,
        indexPictureEditorSize,
        width: pictureWidth,
        height: pictureHeight,
      );
    });
  }

  /// The mouse wheel zooms, one step per notch.
  void onPointerSignal(PointerSignalEvent event) {
    if (event is PointerScrollEvent) {
      zoom(event.scrollDelta.dy < 0
          ? indexPictureZoomStep
          : 1 / indexPictureZoomStep);
    }
  }

  void zoom(double factor) {
    var info = indexPicture;
    if (info != null) {
      setState(() => indexPicture = zoomIndexPicture(
            info,
            factor,
            width: pictureWidth,
            height: pictureHeight,
          ));
    }
  }

  /// Back to the framing the server gives an image, see [indexPictureOf].
  void resetCrop() {
    var image = widget.indexImage;
    if (image != null) {
      setState(() => indexPicture = indexPictureOf(image));
    }
  }

  void applyPressed() {
    Navigator.of(context).pop(
      AlbumProperties(
        title: titleController.text,
        subTitle: subTitleController.text,
        date: date,
        indexPicture: indexPicture,
        kind: widget.properties.kind,
        placeCaption: placeCaption,
      ),
    );
  }

  /// What "Display locations" answers: the album's own word where the switch
  /// was left as it stood (so that an album that said nothing goes on saying
  /// nothing), else [PlaceCaption.shown] or [PlaceCaption.hidden].
  PlaceCaption get placeCaption {
    var stated = widget.properties.placeCaption;
    if (isInbox || placesShown == (stated != PlaceCaption.hidden)) {
      return stated;
    }
    return placesShown ? PlaceCaption.shown : PlaceCaption.hidden;
  }
}
