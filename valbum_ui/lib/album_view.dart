/// The album view: the row layout of the images, the rating filter, the edit
/// mode with the per-tile editor and the album properties editor.
library;

import 'dart:math';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:valbum_ui/album_layout.dart' as layouter;

import 'album_date.dart';
import 'album_edit.dart';
import 'album_model.dart';
import 'app.dart';
import 'attribution.dart';
import 'cache_refresh.dart';
import 'camera_roll_view.dart';
import 'caller.dart';
import 'client.dart';
import 'listing_view.dart';
import 'move_view.dart';
import 'name_date.dart';
import 'resource.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'routes.dart';
import 'rights.dart';
import 'settings.dart';
import 'share_session.dart';
import 'share_view.dart';
import 'video_view.dart';

/// The clearance an album is shown with in the edit mode, see issue #46.
///
/// [owner] is the album as this device is entitled to see it — the normal edit
/// view. The other two ask the server to answer as it would for a caller of
/// that clearance (`?viewAs=members`, `?viewAs=public`), so that the author
/// can see what the family, or a share link, will be shown.
enum ViewAs {
  owner("Yourself", "yourself", Icons.edit),
  members("Members", "members", Icons.group),
  public("Public", "public", Icons.public);

  /// What the app calls this view, as the menu entry choosing it reads.
  final String label;

  /// The same view named in the line that says which one is on the screen:
  /// "View as — yourself", see [AlbumContentState.albumMenu] (issue #100).
  final String state;

  /// The icon the app shows this view by.
  final IconData icon;

  const ViewAs(this.label, this.state, this.icon);

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
/// Both halves therefore go.
///
/// The cost is that the listing's index pictures and the albums visited before
/// are decoded again as they are shown. That is a few dozen decodes, once, for
/// an action an administrator invokes when something is visibly broken.
void forgetDecodedThumbnails() {
  var cache = PaintingBinding.instance.imageCache;
  cache.clear();
  cache.clearLiveImages();
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

class AlbumContent extends StatefulWidget {
  final VAlbumState albumState;
  final AlbumInfo album;
  final String baseUrl;
  final Future Function(AbstractImage image, String name) pushPart;

  const AlbumContent(
    this.albumState,
    this.album,
    this.baseUrl,
    this.pushPart, {
    super.key,
  });

  @override
  State<StatefulWidget> createState() {
    return AlbumContentState();
  }
}

class AlbumContentState extends State<AlbumContent>
    with SingleTickerProviderStateMixin {
  /// The edit in progress: the mode, the selection and the click anchor.
  ///
  /// It lives with the router, not with this widget, so that a trip into an
  /// image or into the alternatives of a group and back finds the album still
  /// being edited, see [AlbumEditSession].
  AlbumEditSession get session =>
      widget.albumState.navigator.delegate.editSession(widget.albumState.path);

  /// Whether the album is being edited *and* shown as its owner.
  ///
  /// A "view as" preview is read-only: the tile editor, the reordering and the
  /// tile toolbars are all off while it is shown, see [previewing]. The edit
  /// session itself stays on, so the switch back to the owner's view is still
  /// there and the album is still the one being edited.
  /// Never inside a share link: a link is not an account, and the app offers
  /// nothing it would have to refuse, see issue #51.
  bool get editMode =>
      session.editMode && !previewing && rights.mayEdit && share == null;
  set editMode(bool value) => session.editMode = value;

  /// What the caller may do with this album, as the server answered it with
  /// the album itself, see issue #49.
  ///
  /// Read from the album that was loaded, never from a "view as" preview: the
  /// preview is a smaller album, and what the caller may do does not change
  /// because they are looking at somebody else's view of it.
  ///
  /// Where the server answered no rights at all, the caller's role decides
  /// what is offered instead, see [offeredRights] and issue #85. `peek`, not
  /// a dependency: this is read from [initState] as well, and the view is a
  /// dependent of the caller scope through [didChangeDependencies] anyway, so
  /// the answer arriving rebuilds it.
  Rights get rights => offeredRights(Rights.of(widget.album), _permission);

  /// What the caller may do and see, as the server said it (issue #85).
  ///
  /// `peek`, not a dependency: this is read from [initState] as well, and the
  /// view is a dependent of the caller scope through [didChangeDependencies]
  /// anyway, so the answer arriving rebuilds it.
  CallerPermission get _permission =>
      CallerInfo.peek(context)?.permission ?? CallerPermission.unknown;

  /// The line saying that this album belongs to somebody else, `null` while
  /// the caller is its owner.
  String? get sharedLine => sharingNotice(widget.albumState.path, rights);

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
      mayShareFolder(
        client,
        widget.albumState.path,
        rights,
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
    widget.albumState.navigator.delegate
        .registerLeaveGuard(widget.albumState.path, _leaveGuard);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    share = ShareSession.of(context);
  }

  @override
  void didUpdateWidget(AlbumContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _layoutOrientation.clear();
      selection.clear();
      lastClicked = null;
    }
  }

  /// The orientation the given image is laid out with, see
  /// [_layoutOrientation].
  Orientation layoutOrientation(ImagePart image) =>
      _layoutOrientation.putIfAbsent(image, () => image.orientation);

  /// Enters the edit mode with the given part selected.
  ///
  /// Refused while the app is offline: an edit that could never be saved is
  /// worse than no edit at all, so the reason is said instead, see
  /// [refuseWhileOffline].
  void setEditMode(AlbumPart selected) {
    // Nothing is greyed out: a caller without `edit` is simply not offered the
    // way in, and the album stays the album, see issue #49.
    if (!rights.mayEdit || share != null) {
      return;
    }
    if (refuseWhileOffline(context)) {
      return;
    }
    setState(() {
      editMode = true;
      selection
        ..clear()
        ..add(selected);
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
  /// and leaves the selection alone.
  DraggedParts dragOf(AlbumPart part) {
    if (!hasMultiSelection || !isSelected(part)) {
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
        .unregisterLeaveGuard(widget.albumState.path, _leaveGuard);
    _dragScroller.dispose();
    super.dispose();
  }

  /// Handles a click on the tile of the given part.
  ///
  /// Plain: select exactly this part, clicking the only selected one clears
  /// the selection. Ctrl/Meta: toggle. Shift: extend the selection to the
  /// range between the part clicked last and this one, as the GWT client did.
  void handleTap(AlbumPart part) {
    var keyboard = HardwareKeyboard.instance;
    setState(() {
      if (keyboard.isShiftPressed) {
        var anchor = lastClicked ?? widget.album.parts.first;
        var select = selection.contains(anchor);
        for (var image in imageRange(widget.album.parts, anchor, part)) {
          if (select) {
            selection.add(image);
          } else {
            selection.remove(image);
          }
        }
      } else if (keyboard.isControlPressed || keyboard.isMetaPressed) {
        _toggle(part);
      } else {
        var wasOnlySelection = selection.length == 1 && isSelected(part);
        selection.clear();
        if (!wasOnlySelection) {
          selection.add(part);
        }
      }
      lastClicked = part;
    });
  }

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

  /// Opens the heading editor prefilled with the current text.
  ///
  /// An empty text is refused, as in the GWT client, which ignored the save.
  Future<void> editHeading(Heading heading) async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: "Überschrift bearbeiten",
        label: "Überschrift",
        text: heading.text,
      ),
    );
    if (text == null || text.trim().isEmpty || !mounted) {
      return;
    }
    setState(() {
      heading.text = text;
      markDirty();
    });
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
      showMessage("Already in order");
      return;
    }
    setState(markDirty);
  }

  /// Adds every image of one camera to the selection (issue #78).
  ///
  /// An album fed from a phone and a camera is gathered device by device: the
  /// images carrying the same [ImagePart.camera] label as [reference] are
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
      showMessage("No other image from this camera");
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
      showMessage("Nothing to adjust");
      return;
    }

    var answer = await showDialog<TimeCorrection>(
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
          showMessage("Nothing to adjust");
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
      showMessage("Nothing to adjust");
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
    if (refuseWhileOffline(context)) {
      return false;
    }
    var messenger = ScaffoldMessenger.of(context);

    try {
      await client.saveAlbum(widget.albumState.path, widget.album);
    } catch (error) {
      messenger.showSnackBar(
        SnackBar(
          content: Text("Speichern fehlgeschlagen: $error"),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );
      // Stay in edit mode, the changes are not persisted yet.
      return false;
    }

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

    // Re-load the album, so that the transient part links are rebuilt. The
    // listing above shows the album by its index picture, which may have
    // just been chosen: it is fetched anew on the way up.
    var path = widget.albumState.path;
    if (path.isNotEmpty) {
      widget.albumState.navigator.delegate
          .forget(path.sublist(0, path.length - 1));
    }
    widget.albumState.reload();
    return true;
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
    var discard = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("discard-dialog"),
        title: const Text("Discard the changes to this album?"),
        content: const Text(
          "The changes made here have not been saved. Discarding them shows "
          "the album again as the server has it.",
        ),
        actions: [
          TextButton(
            key: const Key("keep-editing"),
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Keep editing"),
          ),
          ElevatedButton(
            key: const Key("discard-changes"),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text("Discard"),
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
    var path = widget.albumState.path;
    widget.albumState.navigator.delegate.forget(path);
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
    var choice = await showDialog<LeaveEdit>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("leave-dialog"),
        title: const Text("Save the changes to this album?"),
        content: const Text(
          "Leaving the album ends the edit. Unsaved changes are lost unless "
          "they are saved now.",
        ),
        actions: [
          TextButton(
            key: const Key("stay"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.stay),
            child: const Text("Stay"),
          ),
          TextButton(
            key: const Key("discard-and-leave"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.discard),
            child: const Text("Discard"),
          ),
          ElevatedButton(
            key: const Key("save-and-leave"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.save),
            child: const Text("Save"),
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
  Future<void> moveSelection() async {
    if (dirty) {
      showMessage("Save or discard your changes first");
      return;
    }
    var names = [
      for (var part in widget.album.parts)
        if (isSelected(part) && part is AbstractImage) part.thumbnailName,
    ];
    if (names.isEmpty) {
      showMessage("A heading cannot be moved.");
      return;
    }

    await moveWithPicker(
      context: context,
      client: client,
      source: widget.albumState.path,
      names: names,
      subject: ImageSubject(names.length),
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
  Future<void> editProperties() async {
    var album = widget.album;
    var result = await showDialog<AlbumProperties>(
      context: context,
      builder: (context) => AlbumPropertiesDialog(
        AlbumProperties(
          title: album.title,
          subTitle: album.subTitle,
          date: album.date,
          indexPicture: album.indexPicture,
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

    setState(() {
      album.title = result.title;
      album.subTitle = result.subTitle;
      // The explicit date, and only that one: the effective date is derived
      // by the server on every read and is never written by the app.
      album.date = result.date;
      album.indexPicture = result.indexPicture;
      markDirty();
    });
  }

  String get albumUrl => "${widget.baseUrl}/${widget.album.path}";

  /// The name of the folder this album lives in, empty at the root.
  String get albumFolderName {
    var path = widget.albumState.path;
    return path.isEmpty ? "" : path.last;
  }

  /// The transport to the album server.
  VAlbumClient get client => widget.albumState.client;

  @override
  Widget build(BuildContext context) {
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
    var immersive = !editing && self.parts.isNotEmpty;
    // The way out of the album sits where a way out belongs: at the left of
    // the app bar, ahead of everything the edit mode offers (issue #99).
    var up = wayUp();

    return Scaffold(
      appBar: immersive
          ? null
          : AppBar(
              leading: editing && up.isNotEmpty ? up.first : null,
              automaticallyImplyLeading: false,
              title: Column(
                children: [
                  Text(self.title),
                  if (self.subTitle.isNotEmpty) Text(self.subTitle),
                ],
              ),
              centerTitle: true,
              // The edit mode reads from the left: the way out, then what
              // the edit offers, and the album's menu last at the right,
              // where a menu belongs (issues #99, #100). Outside the edit
              // mode the navigation keeps the place it had. "View as" is not
              // a button of its own any more — it is an entry of the menu,
              // see [albumMenu] (issue #100).
              actions: [
                if (!editing) ...navigationActions(context),
                if (editMode && selection.isNotEmpty)
                  IconButton(
                    key: const Key("move-to"),
                    onPressed: moveSelection,
                    tooltip: "Move to…",
                    icon: const Icon(Icons.drive_file_move),
                  ),
                if (editMode)
                  IconButton(
                    onPressed: editProperties,
                    tooltip: "Album properties",
                    icon: const Icon(Icons.tune),
                  ),
                if (editMode)
                  IconButton(
                    onPressed: () => save(),
                    tooltip: "Save",
                    icon: const Icon(Icons.save),
                  ),
                if (editMode)
                  IconButton(
                    key: const Key("edit-cancel"),
                    onPressed: cancelEdit,
                    tooltip: "Cancel",
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
          if (self.parts.isNotEmpty) Expanded(child: contentView(self)),
        ],
      ),
      // Nothing is added to an album while somebody else's view of it is on
      // the screen: a preview is read-only, see [setViewAs].
      // Nothing is added to an album the caller may not contribute to either:
      // the button is not offered rather than refused, see issue #49.
      // Inside a link the upload is offered exactly when the link allows a
      // contribution, which is what the server answered, see issue #51.
      floatingActionButton: previewing ||
              !rights.mayContribute ||
              (link != null && !link.writeAllowed)
          ? null
          : FloatingActionButton(
              onPressed: widget.albumState.uploadImages,
              tooltip: 'Upload',
              child: const Icon(Icons.cloud_upload),
            ),
    );
  }

  /// The controls of the album: the way up and the album's menu.
  ///
  /// The same controls wherever the album shows them: in the app bar of the
  /// edit mode and of an empty album, and floating over the photos otherwise,
  /// see [contentView]. An album without them is a dead end — the browser
  /// back button aside, there was no way back to the index, see issue #35.
  /// There is no home button: the index is one or more steps up, and a
  /// listing below the root offers the home from there.
  List<Widget> navigationActions(BuildContext context) => [
        ...wayUp(),
        ...albumMenu(context),
      ];

  /// The way out of the album, nothing at the root.
  List<Widget> wayUp() => [
        if (widget.albumState.path.isNotEmpty)
          IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: "Up",
            onPressed: widget.albumState.showParent,
          ),
      ];

  /// The album's menu, with the rating filter in it.
  ///
  /// The `+` and `-` keys stay the shortcut of the filter, see [onKey]; the
  /// menu is the touch equivalent, telling the current threshold and offering
  /// the two steps, each disabled at its GWT bound.
  List<Widget> albumMenu(BuildContext context) => [
        // Unobtrusive while a camera-roll sync runs, nothing otherwise.
        const CameraRollIndicator(),
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
          // What the album is looked at as, in the edit session where the
          // button used to be (issue #100): a line saying which view is on
          // the screen, the three choices under it — the idiom the rating
          // filter below uses.
          if (session.editMode) ...viewAsEntries(),
          if (_mayShare)
            menuItem(Icons.link, "Share link…", (_) => shareAlbumLink()),
          // An edit like every other one: offered inside the edit session, so
          // that the new order is reviewed and saved (or discarded) the way a
          // move or a heading is, see issue #76.
          if (editMode)
            menuItem(Icons.sort, "Sort by date", (_) => sortByDate()),
          menuLabel(
            "Mindestbewertung",
            "≥ $minRating",
            valueKey: const Key("minRating"),
          ),
          menuItem(
            Icons.add_circle_outline,
            "Mehr Bilder zeigen",
            (_) => showMore(),
            enabled: minRating > minMinRating,
          ),
          menuItem(
            Icons.remove_circle_outline,
            "Weniger Bilder zeigen",
            (_) => showLess(),
            enabled: minRating < maxMinRating,
          ),
          const PopupMenuDivider(),
          menuItem(Icons.update, "Reload", (_) => reloadShown()),
          // Only the administrator, who owns the server's own files: the
          // entry a non-admin may not use is not offered at all, see #98.
          if (mayRefreshCache(context))
            keyedMenuItem(
              const Key("refresh-previews"),
              Icons.cleaning_services,
              "Refresh previews",
              (_) => refreshPreviews(),
            ),
          // A visitor of a link has no server of their own to configure.
          if (share == null)
            menuItem(Icons.settings, "Server...", openServerSettings),
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
          "View as",
          viewAs.state,
          valueKey: const Key("view-as-state"),
        ),
        for (var value in ViewAs.values)
          keyedMenuItem(
            Key("view-as-${value.name}"),
            value.icon,
            value.label,
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
              previewMessage(_viewAs),
              key: const Key("view-as-banner"),
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ),
      );

  /// What the banner over a preview says.
  static String previewMessage(ViewAs view) => switch (view) {
        ViewAs.members => "Viewing as members - this is what members see",
        ViewAs.public => "Viewing as public - this is what the public sees",
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
      showMessage("Save or discard your changes first");
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
      showMessage("The server did not answer with an album.");
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
    var confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("refresh-previews-dialog"),
        title: const Text("Refresh previews"),
        content: const Text(
          "The thumbnails and video renditions of this album are thrown away "
          "and made anew when they are next shown. The photos themselves are "
          "not touched.",
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text("Cancel"),
          ),
          ElevatedButton(
            key: const Key("refresh-previews-confirm"),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text("Refresh"),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }

    CacheRefreshed answer;
    try {
      answer = await client.refreshCache(widget.albumState.path);
    } catch (error) {
      if (mounted) {
        // The server's own reason — a refusal speaks, see issue #49.
        showMessage(error is VAlbumException ? error.message : "$error");
      }
      return;
    }
    if (!mounted) {
      return;
    }

    // Said before the album is thrown away and fetched again, so that the
    // count survives the rebuild that follows it.
    showMessage(
      "${answer.removed} cached files thrown away; "
      "the previews are made anew.",
    );
    forgetDecodedThumbnails();
    // The album itself is asked for again: the server may now answer other
    // dimensions for a part whose preview it regenerates.
    widget.albumState.navigator.delegate.forget(widget.albumState.path);
    widget.albumState.reload();
  }

  /// Opens the share-link dialog on this album, see issue #51.
  void shareAlbumLink() => shareLinksOf(
        context: context,
        client: client,
        path: widget.albumState.path,
        label: "'${widget.album.title}'",
      );

  /// Says something to the user that no view of its own says.
  void showMessage(String message) =>
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
      );

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
    var shown = visibleParts(self);
    var hidesEverything =
        self.parts.isNotEmpty && !shown.any((part) => part is AbstractImage);

    return Focus(
      autofocus: true,
      onKeyEvent: onKey,
      // Where the pointer of a drag is, and which one it is, see
      // [_trackPointer]: what the edge scrolling of issue #42 runs on.
      child: Listener(
        onPointerDown: _trackPointer,
        onPointerMove: _trackPointer,
        child: Stack(
          children: [
            LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                return SingleChildScrollView(
                  scrollDirection: Axis.vertical,
                  // The last row of tiles ends above the system navigation
                  // bar instead of running under it, see issue #60.
                  padding: EdgeInsets.only(
                    bottom: MediaQuery.paddingOf(context).bottom,
                  ),
                  // The full width, whatever the content: a `Column` shrinks to
                  // its widest child, so an album whose images the rating filter
                  // all hides used to collapse its title into the top left
                  // corner, under the filter bar, see issue #35.
                  child: SizedBox(
                    width: constraints.maxWidth,
                    child: Column(
                      children: [
                        if (!editMode)
                          Padding(
                            // Between the floating up button and the menu,
                            // in the row they float in: the side padding keeps
                            // a long title from running underneath them, the
                            // top padding centres a single line on them.
                            padding: const EdgeInsets.fromLTRB(64, 12, 64, 4),
                            child: Text(
                              self.title,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 28,
                                color: Colors.white,
                              ),
                            ),
                          ),
                        if (!editMode)
                          if (self.subTitle.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
                              child: Text(
                                self.subTitle,
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                  fontSize: 16,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                        // A filter that hides everything says so: an empty black
                        // page would look like an empty album.
                        if (hidesEverything)
                          Padding(
                            padding: const EdgeInsets.fromLTRB(16, 24, 16, 24),
                            child: Text(
                              "No image is rated $minRating or better - "
                              "press + (or the + button) to show more.",
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 16,
                                color: Colors.white70,
                              ),
                            ),
                          ),
                        ...buildParts(self, constraints.maxWidth),
                      ],
                    ),
                  ),
                );
              },
            ),
            if (!editMode && widget.albumState.path.isNotEmpty)
              Positioned(top: 8, left: 8, child: floating(wayUp())),
            if (!editMode)
              Positioned(
                top: 8,
                right: 8,
                child: floating(albumMenu(context)),
              ),
          ],
        ),
      ),
    );
  }

  /// The `+` and `-` keys of the GWT client, widening and narrowing the rating
  /// filter — and `Escape`, which is Cancel in the edit mode (issue #99).
  KeyEventResult onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    var key = event.logicalKey;
    if (key == LogicalKeyboardKey.escape) {
      if (!editMode) {
        return KeyEventResult.ignored;
      }
      cancelEdit();
      return KeyEventResult.handled;
    }
    if (event.character == "+" ||
        key == LogicalKeyboardKey.add ||
        key == LogicalKeyboardKey.numpadAdd) {
      showMore();
      return KeyEventResult.handled;
    }
    if (event.character == "-" ||
        key == LogicalKeyboardKey.minus ||
        key == LogicalKeyboardKey.numpadSubtract) {
      showLess();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// Renders the album parts: each run of images is laid out as a block of
  /// rows, headings separate those blocks.
  ///
  /// Images whose rating is below [minRating] are left out, headings are
  /// always shown.
  List<Widget> buildParts(AlbumInfo self, double maxWidth) {
    var result = <Widget>[];
    var images = <AbstractImage>[];
    _displayOrder.clear();

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
      var builder = ContentWidgetBuilder(imageTile, layout.getPageWidth());
      result.addAll(builder.buildRows(layout));
      images = <AbstractImage>[];
    }

    for (var part in visibleParts(self)) {
      if (part is AbstractImage) {
        images.add(part);
      } else if (part is Heading) {
        flushImages();
        _displayOrder.add(part);
        result.add(headingView(part));
      }
    }
    flushImages();

    return result;
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
  Widget imageTile(AbstractImage image, double width, double height) =>
      image.visitAbstractImage(ImageWidgetBuilder(this, width, height), null);

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
    for (var image in representatives) {
      image.orientation = layoutOrientation(image);
    }
    try {
      return body();
    } finally {
      for (var i = 0; i < representatives.length; i++) {
        representatives[i].orientation = current[i];
      }
    }
  }

  /// The heading between two blocks of images.
  ///
  /// In the edit mode it carries the two inline tools of the GWT
  /// `HeadingDisplay`: edit the text and delete the heading.
  Widget headingView(Heading heading) {
    var view = headingRow(heading);
    if (!editMode) {
      return view;
    }
    // A heading is an album part like any other: it can be dragged, and an
    // image can be dropped directly behind it, see [ReorderablePart].
    return ReorderablePart(
      album: this,
      part: heading,
      feedback: Text(
        heading.text,
        style: const TextStyle(fontSize: 22, color: Colors.white),
      ),
      child: view,
    );
  }

  Widget headingRow(Heading heading) => Padding(
        padding: const EdgeInsets.only(top: 24, bottom: 8),
        child: Row(
          key: ValueKey(heading),
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              heading.text,
              style: const TextStyle(fontSize: 22, color: Colors.white),
            ),
            if (editMode)
              IconButton(
                icon: const Icon(Icons.edit),
                iconSize: 20,
                color: Colors.white,
                tooltip: "Überschrift bearbeiten",
                onPressed: () => editHeading(heading),
              ),
            if (editMode)
              IconButton(
                icon: const Icon(Icons.delete_outline),
                iconSize: 20,
                color: Colors.white,
                tooltip: "Überschrift löschen",
                onPressed: () => deleteHeading(heading),
              ),
          ],
        ),
      );
}

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
    } else if (state.previewing) {
      // A "view as" preview is what somebody else sees, nothing to act on:
      // no viewer, no way into the edit mode, see [AlbumContentState.setViewAs].
      return SizedBox(
        key: ValueKey(image.name),
        width: width,
        height: height,
        child: imageThumbnail(image),
      );
    } else {
      return GestureDetector(
        key: ValueKey(image.name),
        onTap: () => state.widget.pushPart(part, image.name),
        onLongPress: () => state.setEditMode(part),
        child: imageThumbnail(image),
      );
    }
  }

  /// The thumbnail of the given image, filling the tile box.
  ///
  /// The server bakes the orientation of the image *file* into the thumbnail
  /// it serves; what is stored beside the image comes on top of it and is
  /// applied here, see [orientedImageThumbnail].
  Widget imageThumbnail(ImagePart image) {
    var thumbnail = orientedImageThumbnail(
      state.client,
      "${state.albumUrl}${image.thumbnailName}",
      image,
      width: width,
      height: height,
    );
    var marker = privacyMarker(image);
    if (image.kind == ImageKind.image && marker == null) {
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
        ],
      ),
    );
    if (image.kind == ImageKind.image || state.editMode || state.previewing) {
      // Nothing to tease, and nothing to tease *with* while the album is
      // being edited or looked at as somebody else: a video starting under
      // the pointer of somebody dragging tiles is noise, see issue #75.
      return tile;
    }
    return VideoTeaser(
      teaserUrl: state.client.teaserUrl("${state.albumUrl}${image.name}"),
      headers: state.client.authHeaders,
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
        message: privacyName(level),
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

  bool get selected => album.isSelected(part);
  bool get multiSelected => selected && album.hasMultiSelection;

  /// Whether the tile shows its toolbars.
  bool get active => selected || _hovered;

  @override
  Widget build(BuildContext context) {
    var width = widget.builder.width;
    var height = widget.builder.height;

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
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => album.handleTap(part),
                  onLongPress: () => album.toggleSelection(part),
                  child: widget.builder.imageThumbnail(image),
                ),
              ),
              if (isIndexPicture(album.widget.album, image))
                const Positioned(
                  right: 4,
                  bottom: 4,
                  child: IgnorePointer(
                    child: Tooltip(
                      message: "Albumbild",
                      child: Icon(
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
              if (active)
                Positioned(bottom: 0, left: 0, right: 0, child: bottomBar()),
            ],
          ),
        ),
      ),
    );
  }

  /// The rotation tools, next to the selection mark.
  Widget topBar() => toolbar([
        toolButton(
          selected ? Icons.check_box : Icons.check_box_outline_blank,
          "Auswählen",
          () => album.toggleSelection(part),
          active: selected,
        ),
        toolButton(Icons.rotate_right, "Nach rechts drehen", rotateRight),
        toolButton(Icons.swap_vert, "Vertikal spiegeln", flipVertically),
        toolButton(Icons.rotate_left, "Nach links drehen", rotateLeft),
      ]);

  /// The tools acting on the selection.
  Widget? centerBar() {
    if (!selected) {
      return null;
    }
    var self = part;
    return toolbar([
      if (multiSelected)
        toolButton(Icons.join_left, "Gruppieren", createGroup)
      else ...[
        toolButton(Icons.title, "Überschrift einfügen", createHeading),
        if (self is ImageGroup) ...[
          toolButton(
            Icons.call_split,
            "Gruppierung aufheben",
            () => album.ungroupSelected(self),
          ),
          // Into the alternatives to pick the representative: the edit mode
          // stays on for the way back, see [AlbumEditSession].
          toolButton(
            Icons.collections,
            "Gruppenbild wählen",
            () => album.widget.albumState.showGroupView(self),
          ),
        ],
      ],
      // Everything one device contributed, in one gesture — the selection the
      // recording time correction below then works on, see issue #78. An
      // image whose camera is unknown has nothing to gather, so the tool is
      // not offered on it rather than refusing the tap.
      if (image.camera.isNotEmpty)
        toolButton(
          Icons.photo_camera,
          "Select all from this camera",
          () => album.selectSameCamera(image),
        ),
      // A camera whose clock is off files its photos in the wrong place; the
      // correction acts on the whole selection, see issue #77.
      toolButton(
        Icons.more_time,
        "Adjust recording time…",
        () => album.adjustRecordingTimeOf(part),
      ),
      toolButton(Icons.notes, "Bildeigenschaften", editImageProperties),
      // The image standing for the album in the listing above, chosen where
      // the images are compared: the representative of a group stands for it.
      toolButton(
        Icons.photo_album,
        "Als Albumbild verwenden",
        setIndexPicture,
        active: isIndexPicture(album.widget.album, image),
      ),
    ]);
  }

  /// The rating chooser and, beside it, the privacy level.
  Widget bottomBar() => toolbar([
        ratingButton(Icons.star, "Sehr gut", 2),
        ratingButton(Icons.add, "Gut", 1),
        ratingButton(Icons.remove, "Schlecht", -1),
        ratingButton(Icons.delete, "Papierkorb", -2),
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
      "Privacy: ${privacyName(level)} "
      "(tap for ${privacyName(next)})",
      () => setPrivacy(next),
      active: level != privacyPublic,
      key: const Key("privacy-control"),
    );
  }

  Widget ratingButton(IconData icon, String tooltip, int value) => toolButton(
        icon,
        tooltip,
        () => setRating(value),
        active: isActiveRating(image.rating, value),
      );

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

  void rotateLeft() =>
      album.editImage(() => image.orientation = OrientationOps.rotL(
            image.orientation,
          ));

  void rotateRight() =>
      album.editImage(() => image.orientation = OrientationOps.rotR(
            image.orientation,
          ));

  void flipVertically() =>
      album.editImage(() => image.orientation = OrientationOps.flipV(
            image.orientation,
          ));

  void setRating(int value) => album.editImage(
        () => image.rating = toggleRating(image.rating, value),
      );

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
        const SnackBar(
          content: Text("Zum Gruppieren mindestens zwei Bilder auswählen"),
          duration: Duration(seconds: 4),
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
    var text = await showDialog<String>(
      context: context,
      builder: (context) => const TextInputDialog(
        title: "Überschrift einfügen",
        label: "Überschrift",
        text: "",
      ),
    );
    if (text == null || !mounted) {
      return;
    }
    album.editImage(() => insertHeadingBeforeDisplayed(
          album.widget.album,
          part,
          album.displayOrder,
          text,
        ));
  }

  /// Edits the comment of the image shown by this tile.
  Future<void> editImageProperties() async {
    var text = await showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: "Bildeigenschaften",
        label: "Kommentar",
        text: image.comment,
        multiLine: true,
        // What the album knows about this image and does not edit here: the
        // recording time it is sorted by (the sidecar's, which the adjustment
        // of issue #77 may have corrected) and the camera that took it (issue
        // #78). Each line only where there is something to say.
        details: [
          // What the image is in the folder: the name the file carries, for
          // an image and a video alike, and the representative's name where
          // the tile shows a group, see issue #103.
          "Datei: ${image.name}",
          if (image.date != 0)
            "Aufnahmezeit: "
                "${AdjustRecordingTimeDialogState.timeFormat.format(
              DateTime.fromMillisecondsSinceEpoch(image.date),
            )}",
          if (image.camera.isNotEmpty) "Kamera: ${image.camera}",
        ],
        // Who added this photo, the editor's own contributions included: the
        // screen saying what an image is says where it came from, see #53.
        note: attributionShown(image),
      ),
    );
    if (text == null || !mounted) {
      return;
    }
    album.editImage(() => image.comment = text);
  }
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

/// The band along the top and the bottom edge of the album's viewport in which
/// a carried tile makes the album scroll, in logical pixels (issue #42).
const double dragScrollZone = 64;

/// The share of the viewport the edge band may take at most.
///
/// A phone in landscape, or a small window, is not much higher than two
/// [dragScrollZone] bands; without this the two would meet in the middle and
/// there would be no place left to hold a tile still.
const double dragScrollZoneFraction = 0.25;

/// How fast the album scrolls while a tile is carried at the very edge of the
/// viewport, in logical pixels per second.
const double dragScrollMaxSpeed = 800;

/// Scrolls the album while a tile is carried near the top or the bottom edge
/// of its viewport (issue #42).
///
/// A drop target outside the viewport used to be out of reach: the album
/// stands still while a tile is carried, so it had to be scrolled to the right
/// place first, or the tile had to be dropped and picked up again. Resting the
/// pointer in a band of [dragScrollZone] along an edge now scrolls the album
/// that way, the faster the closer the pointer is to the edge — the behaviour
/// of the usual reorderable list.
///
/// It is driven by a [Ticker] rather than by the pointer moves, so that a
/// pointer that *rests* in the band keeps scrolling; the ticker runs only
/// while there is something to scroll and is stopped when the pointer leaves
/// the band, when the drag ends and when the album is disposed.
///
/// Flutter's own [EdgeDraggingAutoScroller] was the model but not the means:
/// it scrolls by how far a dragged *rectangle* sticks out of the viewport,
/// capped at 20 pixels of overdrag, which turns the speed curve flat over most
/// of a band and would have had to be faked with a rectangle around the
/// pointer.
class DragEdgeScroller {
  /// Called after every scroll step, see
  /// [AlbumContentState._followScrolledContent].
  final VoidCallback? onScrolled;

  late final Ticker _ticker;

  /// The position scrolled while a tile is carried, `null` while none is.
  ScrollPosition? _position;

  /// Where the pointer of the drag was seen last, in global coordinates.
  Offset? _pointer;

  /// The tick the last step was computed for, to measure a step's duration.
  Duration _lastTick = Duration.zero;

  /// A scroll step smaller than this is no step at all: the end of the range.
  static const double _scrollEpsilon = 0.001;

  DragEdgeScroller(TickerProvider vsync, {this.onScrolled}) {
    _ticker = vsync.createTicker(_tick);
  }

  /// Whether the album is scrolling under the pointer right now.
  bool get scrolling => _ticker.isActive;

  /// Starts to watch the pointer of a drag that has just begun.
  ///
  /// [position] is the album's own scroll position, `null` if it has none —
  /// then nothing scrolls.
  void begin(ScrollPosition? position, Offset? pointer) {
    _position = position;
    _pointer = pointer;
    _startIfNeeded();
  }

  /// Follows the pointer of the drag in progress.
  void update(Offset pointer) {
    _pointer = pointer;
    _startIfNeeded();
  }

  /// Ends the scrolling of a drag that was dropped or cancelled.
  void end() {
    _stop();
    _position = null;
    _pointer = null;
  }

  void dispose() {
    _ticker.dispose();
  }

  void _startIfNeeded() {
    if (!_ticker.isActive && _speed() != 0) {
      _lastTick = Duration.zero;
      _ticker.start();
    }
  }

  void _stop() {
    if (_ticker.isActive) {
      _ticker.stop();
    }
  }

  void _tick(Duration elapsed) {
    var seconds =
        (elapsed - _lastTick).inMicroseconds / Duration.microsecondsPerSecond;
    _lastTick = elapsed;
    if (seconds <= 0) {
      // The first tick of a ticker starting within a frame.
      return;
    }
    var speed = _speed();
    if (speed == 0) {
      // The pointer has left the band, or there is nothing to scroll.
      _stop();
      return;
    }
    var position = _position!;
    var target = min(
      max(position.pixels + speed * seconds, position.minScrollExtent),
      position.maxScrollExtent,
    );
    if ((target - position.pixels).abs() < _scrollEpsilon) {
      // The end of the range: there is nothing more to scroll that way.
      _stop();
      return;
    }
    position.jumpTo(target);
    onScrolled?.call();
  }

  /// How fast and which way the album should scroll for the pointer's place,
  /// in logical pixels per second; zero while it rests outside both bands.
  ///
  /// The speed grows linearly with the proximity to the edge, from nothing at
  /// the inner boundary of the band to [dragScrollMaxSpeed] at the edge itself
  /// (and no faster beyond it, where a pointer dragged out of the album ends
  /// up).
  double _speed() {
    var position = _position;
    var pointer = _pointer;
    if (position == null || pointer == null || !position.hasContentDimensions) {
      return 0;
    }
    if (position.maxScrollExtent <= position.minScrollExtent) {
      // The album fits its viewport: there is nothing to scroll.
      return 0;
    }
    var box = position.context.storageContext.findRenderObject();
    if (box is! RenderBox || !box.hasSize) {
      return 0;
    }
    var height = box.size.height;
    var zone = min(dragScrollZone, height * dragScrollZoneFraction);
    if (zone <= 0) {
      return 0;
    }
    var depth = box.globalToLocal(pointer).dy;
    if (depth < zone) {
      return -dragScrollMaxSpeed * min((zone - depth) / zone, 1);
    }
    if (depth > height - zone) {
      return dragScrollMaxSpeed * min((depth - (height - zone)) / zone, 1);
    }
    return 0;
  }
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
    var dragged = widget.album.dragOf(widget.part);
    return DragTarget<DraggedParts>(
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
    );
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
        child: const Tooltip(
          message: "Drag to reorder",
          child: SizedBox(
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
                "${dragged.parts.length} Teile",
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

/// A dialog editing a single text, used for headings and image comments.
class TextInputDialog extends StatefulWidget {
  final String title;
  final String label;
  final String text;
  final bool multiLine;

  /// Read-only lines under the field, what the dialog shows but does not
  /// edit: the file name (issue #103), the recording time and the camera of
  /// an image (issue #78).
  ///
  /// Selectable, so that the name of a file can be copied out of the dialog.
  final List<String> details;

  /// A read-only line under the field, `null` where there is nothing to say.
  ///
  /// What the dialog shows besides what it edits: the attribution of issue
  /// #53, which is derived by the server and can therefore not be edited here
  /// — the field is what the album stores, the note is what the server knows.
  final String? note;

  const TextInputDialog({
    super.key,
    required this.title,
    required this.label,
    required this.text,
    this.multiLine = false,
    this.note,
    this.details = const [],
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
    return Dialog(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            DefaultTextStyle(
              style: DialogTheme.of(context).titleTextStyle ??
                  Theme.of(context).textTheme.titleLarge!,
              child: Semantics(
                namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                container: true,
                child: Text(widget.title),
              ),
            ),
            TextField(
              controller: controller,
              autofocus: true,
              minLines: widget.multiLine ? 3 : 1,
              maxLines: widget.multiLine ? 8 : 1,
              decoration: InputDecoration(label: Text(widget.label)),
            ),
            if (widget.details.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Column(
                  key: const Key("properties-details"),
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    for (var line in widget.details)
                      SelectableText(
                        line,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
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
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text("Abbrechen"),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Übernehmen"),
                      onPressed: () =>
                          Navigator.of(context).pop(controller.text),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
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
  String get offsetText {
    var value = offset;
    if (value == null) {
      return "Not a time (yyyy-MM-dd HH:mm:ss)";
    }
    return value == Duration.zero ? "Nothing to adjust" : offsetInWords(value);
  }

  /// What the count line says.
  String get countText => widget.count == 1
      ? "Applies to 1 image"
      : "Applies to ${widget.count} images";

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
  String get nameDateText {
    if (nameDated.length == 1) {
      var named = differingNameDate(nameDated.first)!;
      return "Use the time in the file name: ${timeFormat.format(named)}";
    }
    return "Use the time in the file name (${nameDated.length} images)";
  }

  /// Fills the field from the date and time pickers.
  Future<void> pickTime() async {
    var start = corrected ?? current;
    var day = await showDatePicker(
      context: context,
      initialDate: start,
      firstDate: DateTime(1900),
      lastDate: DateTime(2100),
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
    var small = Theme.of(context).textTheme.bodySmall;
    return Dialog(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            DefaultTextStyle(
              style: DialogTheme.of(context).titleTextStyle ??
                  Theme.of(context).textTheme.titleLarge!,
              child: Semantics(
                namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                container: true,
                child: const Text("Adjust recording time"),
              ),
            ),
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
                    decoration: const InputDecoration(
                      label: Text("Correct time"),
                    ),
                  ),
                ),
                IconButton(
                  key: const Key("adjust-pick"),
                  tooltip: "Pick date and time",
                  icon: const Icon(Icons.edit_calendar),
                  onPressed: pickTime,
                ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(offsetText, key: const Key("adjust-offset")),
            ),
            Text(countText, key: const Key("adjust-count")),
            // One tap for what the file names already say, offered only where
            // there is something to repair, see issue #102.
            if (nameDated.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: OutlinedButton.icon(
                  key: const Key("use-name-date"),
                  icon: const Icon(Icons.drive_file_rename_outline),
                  label: Text(nameDateText),
                  onPressed: () =>
                      Navigator.of(context).pop(const UseNameDates()),
                ),
              ),
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                "The original recording time stays in the photo; the album "
                "keeps its own.",
                key: const Key("adjust-help"),
                style: small,
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
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Übernehmen"),
                      onPressed: corrected == null
                          ? null
                          : () => Navigator.of(context)
                              .pop(ShiftToTime(corrected!)),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
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

  const AlbumProperties({
    required this.title,
    required this.subTitle,
    this.date = 0,
    this.indexPicture,
  });
}

/// The size of the crop editor's preview of the index picture.
const double indexPictureEditorSize = 200;

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

  const AlbumPropertiesDialog(
    this.properties, {
    super.key,
    this.client,
    this.baseUrl = "",
    this.indexImage,
    this.effectiveDate = 0,
    this.dateSource = DateSource.none,
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

  /// The crop being edited, a copy: the album's own is replaced on apply.
  late ThumbnailInfo? indexPicture = copyOf(widget.properties.indexPicture);

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
        );

  @override
  void dispose() {
    titleController.dispose();
    subTitleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: Padding(
        // Tight, so that the whole dialog still fits a short screen: the
        // album date moved in with issue #48.
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            DefaultTextStyle(
              style: DialogTheme.of(context).titleTextStyle ??
                  Theme.of(context).textTheme.titleLarge!,
              child: Semantics(
                namesRoute: Theme.of(context).platform != TargetPlatform.iOS,
                container: true,
                child: const Text("Albumeigenschaften"),
              ),
            ),
            TextField(
              controller: titleController,
              autofocus: true,
              decoration: const InputDecoration(label: Text("Titel")),
            ),
            TextField(
              controller: subTitleController,
              decoration: const InputDecoration(label: Text("Subtitel")),
            ),
            buildDateRow(context),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                "Albumbild",
                style: Theme.of(context).textTheme.labelLarge,
              ),
            ),
            buildIndexPictureEditor(context),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text("Abbrechen"),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 8),
                    child: ElevatedButton.icon(
                      icon: const Icon(Icons.check),
                      label: const Text("Übernehmen"),
                      onPressed: applyPressed,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// The date of the album: the explicit one when it is set, else the date
  /// the server derived and where it derived it from.
  ///
  /// Nothing is shown out of nowhere: an album whose date comes from its
  /// folder name or from its photos says so, so that the empty field is not
  /// read as "this album has no date", see issue #48.
  Widget buildDateRow(BuildContext context) {
    var shown = date != 0 ? date : derivedDate;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                shown == 0 ? "Datum: keines" : "Datum: ${formatDate(shown)}",
                key: const Key("album-date"),
              ),
            ),
            IconButton(
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.date_range),
              tooltip: "Datum wählen",
              onPressed: pickDate,
            ),
            IconButton(
              key: const Key("album-date-clear"),
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.clear),
              tooltip: "Datum entfernen",
              // Back to "no explicit date": what the server derives then
              // takes over again.
              onPressed: date == 0 ? null : () => setState(() => date = 0),
            ),
          ],
        ),
        if (date == 0 && dateSourceText != null)
          Text(
            dateSourceText!,
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
  String? get dateSourceText => switch (widget.dateSource) {
        DateSource.folderName => "Aus dem Ordnernamen übernommen.",
        DateSource.photos => "Aus den Fotos übernommen.",
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
      firstDate: DateTime(1900),
      lastDate: DateTime(2100),
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
    var info = indexPicture;
    var client = widget.client;
    if (info == null || client == null) {
      return const Padding(
        padding: EdgeInsets.only(top: 4),
        child: Text(
          "Kein Albumbild gewählt – im Bearbeitungsmodus auf einer Kachel "
          "als Albumbild wählen.",
          key: Key("index-picture-hint"),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 4),
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
                  "${widget.baseUrl}/${info.image}",
                  info,
                  indexPictureEditorSize,
                  key: const Key("index-picture-editor"),
                ),
              ),
            ),
          ),
        ),
        Row(
          children: [
            IconButton(
              icon: const Icon(Icons.zoom_in),
              tooltip: "Vergrößern",
              onPressed: info.scale < maxIndexPictureScale
                  ? () => zoom(indexPictureZoomStep)
                  : null,
            ),
            IconButton(
              icon: const Icon(Icons.zoom_out),
              tooltip: "Verkleinern",
              onPressed: info.scale > minIndexPictureScale
                  ? () => zoom(1 / indexPictureZoomStep)
                  : null,
            ),
            IconButton(
              icon: const Icon(Icons.crop_free),
              tooltip: "Ausschnitt zurücksetzen",
              onPressed: widget.indexImage == null ? null : resetCrop,
            ),
          ],
        ),
      ],
    );
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
    var zoomed =
        details.scale == 1 ? start : zoomIndexPicture(start, details.scale);
    setState(() {
      indexPicture = panIndexPicture(
        zoomed,
        shift.dx,
        shift.dy,
        indexPictureEditorSize,
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
      setState(() => indexPicture = zoomIndexPicture(info, factor));
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
      ),
    );
  }
}
