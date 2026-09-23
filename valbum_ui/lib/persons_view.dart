/// The face editor of an album, the app half of issue #126.
///
/// The server finds faces (issue #124) and stores what somebody decided about
/// them (issue #125); this screen is where that deciding happens. Every face
/// of the album stands in a group — the person it is, the cluster the server
/// believes it to be, or "Not a face" — and the groups are named, corrected
/// and merged here.
///
/// ## A level of its own
///
/// It is a page level of issue #93 (`<album>/persons`, see [PersonsRoute]),
/// not a dialog: the album stays mounted beneath it with its scroll offset
/// and its thumbnails, the way back is the app bar's `leading` (issue #100),
/// and the browser's back button leads out of it through the very guard the
/// album's edit mode passes (issue #99).
///
/// ## Two kinds of change, two ways of writing them
///
/// A **person** is space-level: creating, renaming and merging one changes
/// the register of the space and no album at all, so it is posted at once —
/// there is nothing about it that a Cancel of this screen could take back.
///
/// An **assignment** — this face is Anna, this face is not Anna, this is no
/// face — belongs to the album, and is buffered exactly as the album's edit
/// mode buffers a reordering: [PersonsContentState.placement] holds where
/// every face stands *now*, [PersonsContentState.stored] where the server put
/// it, and Save posts the difference as one `tag-faces`. A face that did not
/// change is not in the request.
///
/// ## What the difference says
///
/// The wire knows four states and the editor maps its groups onto them:
///
///  * a face standing in a **person** group is `CONFIRMED` for that person —
///    whether it was dragged there, whether its cluster was named, or whether
///    a suggestion of issue #127 was confirmed;
///  * a face dragged **out** of a person's group is `REJECTED` *for the person
///    it stood under*, which is what keeps the suggestion from coming back;
///  * a face in "Not a face" is `NOT_A_FACE`;
///  * a face moved between two unknown groups says nothing the server stores:
///    a cluster is the server's own guess and there is no decision to write.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/widgets.dart' as widgets show Action;
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

import 'album_edit.dart' show PlaneTransform;
import 'album_view.dart' show LeaveEdit, dragFeedbackScale, dragHandleSize;
import 'app.dart';
import 'caller.dart';
import 'client.dart';
import 'drag_scroll.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'person_names.dart';
import 'resource.dart';
import 'rights.dart';
import 'share_session.dart';
import 'thumbnails.dart';

/// How often the editor asks again while the server is still detecting.
///
/// Injectable so that a test does not wait ten seconds, see
/// [PersonsContent.pendingInterval].
const Duration personsPendingInterval = Duration(seconds: 10);

/// The side of a face tile, in logical pixels.
const double faceTileSize = 96;

/// The prefix of the group of the faces confirmed as one person.
const String personGroupPrefix = "person:";

/// The prefix of the group of the faces *suggested* to be one person.
///
/// A group of its own although it is drawn under the person: a suggestion is
/// not a confirmation, and confirming it has to be a change the buffer can
/// see, see [PersonsContentState.delta].
const String suggestedGroupPrefix = "suggest:";

/// The prefix of the group of one cluster of the server's own guess.
const String clusterGroupPrefix = "cluster:";

/// The prefix of a group this editor made, see "New group".
const String newGroupPrefix = "new:";

/// The group of everything somebody said is no face at all.
const String notAFaceGroup = "notAFace";

/// The browser's own context menu, which must not open over a face tile
/// (issue #144).
///
/// On the web a secondary click shows the browser's menu unless the app takes
/// it away, and it would cover the tile's own menu of issue #139. It is taken
/// away while the face editor stands open and given back when it leaves, so
/// that the rest of the app — a text field, a link of the settings — keeps
/// it. Off the web and where the menu is already in the wanted state, both
/// calls are nothing at all.
class BrowserMenu {
  const BrowserMenu();

  /// Takes the browser's context menu away, if there is one.
  Future<void> disable() async {
    if (kIsWeb && BrowserContextMenu.enabled) {
      await BrowserContextMenu.disableContextMenu();
    }
  }

  /// Gives the browser's context menu back, if there is one.
  Future<void> enable() async {
    if (kIsWeb && !BrowserContextMenu.enabled) {
      await BrowserContextMenu.enableContextMenu();
    }
  }
}

/// The browser's context menu as this app reaches it (issue #144).
///
/// A seam, because `kIsWeb` is false under the test binding: a test replaces
/// this with a double and asserts that the editor takes the menu away while
/// it is mounted and gives it back afterwards.
BrowserMenu browserMenu = const BrowserMenu();

/// The prefix of the placement of a face whose decision was taken back.
///
/// "Forget" is issue #138: a stored decision — confirmed, rejected, no face at
/// all — can be removed again, and the face is then the plain detection it was
/// before anybody said anything about it. On the screen it simply falls back
/// into the group it would stand in without that decision, and *that* group
/// stands behind this prefix, so the buffer can tell "taken back" from "put
/// here" — a rejected face falls back into the very cluster group it already
/// stands in, and without the mark the difference would be invisible.
const String forgottenGroupPrefix = "forget:";

/// The group the given placement is shown in, [forgottenGroupPrefix] stripped.
String shownGroupOf(String placement) =>
    placement.startsWith(forgottenGroupPrefix)
        ? placement.substring(forgottenGroupPrefix.length)
        : placement;

/// One face of the album: the photograph it was found in, and what the server
/// says about it.
///
/// [FaceInfo.index] is the position in the answer the album was read from and
/// is stable for that answer alone — which is the lifetime of this object.
class AlbumFace {
  /// The photograph the face is in.
  final ImagePart image;

  /// What the server answered about it.
  final FaceInfo face;

  const AlbumFace(this.image, this.face);

  /// What identifies this face while the editor is open.
  String get key => "${image.name}#${face.index}";

  /// Whether somebody decided about this face, which is what "Forget" undoes.
  ///
  /// The state the *server* answered: a suggestion of issue #127 carries a
  /// person and no decision, so it is `UNDECIDED` and there is nothing here to
  /// take back. Only a stored tag — confirmed, rejected, no face at all — is.
  bool get decided => face.state != FaceState.undecided;

  /// Where this face stands when nothing is decided about it (issue #138).
  ///
  /// Its own cluster, or the plain "Who is this?" of a face the server never
  /// clustered — which is what the answer itself would put it in.
  String get undecidedGroup => "$clusterGroupPrefix${face.cluster}";

  /// Where this face stands when its decision is taken back, see
  /// [forgottenGroupPrefix].
  String get forgottenGroup => "$forgottenGroupPrefix$undecidedGroup";
}

/// What a drag of the face editor carries.
///
/// The [DraggedParts] of issue #94 for faces: a drag that picks up a face of
/// the current selection carries the whole selection, any other face carries
/// itself alone.
class DraggedFaces {
  /// The carried faces.
  final List<AlbumFace> faces;

  /// The face whose tile was picked up, one of [faces].
  final AlbumFace primary;

  const DraggedFaces(this.faces, this.primary);

  /// Whether the given face is one of the carried ones.
  bool contains(AlbumFace face) => faces.any((one) => one.key == face.key);

  /// Whether more than one face is carried.
  bool get isBlock => faces.length > 1;
}

/// One group of the editor: a heading and the faces standing under it.
class FaceGroup {
  /// What the group is, see [personGroupPrefix] and its siblings.
  final String key;

  /// The faces standing in it.
  final List<AlbumFace> faces;

  const FaceGroup(this.key, this.faces);

  /// Whether this is a person's own group.
  bool get named => key.startsWith(personGroupPrefix);

  /// Whether this is a group of faces only *suggested* to be somebody.
  bool get suggested => key.startsWith(suggestedGroupPrefix);

  /// The person this group is about, empty for an unknown one.
  String get person => named
      ? key.substring(personGroupPrefix.length)
      : suggested
          ? key.substring(suggestedGroupPrefix.length)
          : "";
}

/// Whether the given group takes a face that is dropped onto it.
///
/// Every group but a suggestion: a suggestion is the server's belief, and
/// nothing is dropped *into* a belief — it is confirmed or it is left.
bool takesDrops(String group) => !group.startsWith(suggestedGroupPrefix);

/// What a click left behind: the faces selected, and where the next range
/// starts from.
class FaceSelection {
  /// The selected faces, by [AlbumFace.key].
  final Set<String> keys;

  /// The face a following shift-click measures its range from, `null` where
  /// there is none.
  final String? anchor;

  const FaceSelection(this.keys, this.anchor);
}

/// What a click on a face does to the selection (issue #139).
///
/// The selection semantics every file manager has, written as a function of
/// what is on the screen so that it can be checked without a screen: [order]
/// is the display order of the page — every face as it is drawn, groups in
/// their order, so a range may span a group boundary — [clicked] the face the
/// click landed on, [anchor] the face of the last plain or toggling click.
///
///  * plain: the clicked face alone, whatever stood selected before;
///  * [toggle] (ctrl, and meta on a Mac): the clicked face joins or leaves
///    the selection, the rest untouched, and it becomes the new anchor;
///  * [range] (shift): everything from the anchor to the clicked face takes
///    **the anchor's own state** — selected from a selected anchor, and
///    deselected from one that was just toggled off — and the anchor stays
///    where it is, so the range can be redrawn from it.
///
/// A range without an anchor, or whose anchor is gone (the album was read
/// anew), is a plain click: there is nothing to measure from.
FaceSelection faceSelectionAfterClick({
  required List<String> order,
  required String clicked,
  required Set<String> selection,
  String? anchor,
  bool toggle = false,
  bool range = false,
}) {
  var from = anchor == null ? -1 : order.indexOf(anchor);
  var to = order.indexOf(clicked);
  if (range && from >= 0 && to >= 0) {
    var select = selection.contains(anchor);
    var result = Set<String>.from(selection);
    for (var i = min(from, to); i <= max(from, to); i++) {
      if (select) {
        result.add(order[i]);
      } else {
        result.remove(order[i]);
      }
    }
    return FaceSelection(result, anchor);
  }
  if (toggle) {
    var result = Set<String>.from(selection);
    if (!result.remove(clicked)) {
      result.add(clicked);
    }
    return FaceSelection(result, clicked);
  }
  return FaceSelection({clicked}, clicked);
}

/// Whether a click of the given pointer kind carries modifiers at all.
///
/// A phone has no ctrl and no shift, and a tap there keeps toggling as it did
/// before issue #139; a mouse and a trackpad get the regular semantics.
bool pointerSelects(PointerDeviceKind kind) =>
    kind == PointerDeviceKind.mouse || kind == PointerDeviceKind.trackpad;

/// The face editor of one album.
class PersonsContent extends StatefulWidget {
  /// The page this screen stands in, see [VAlbumState].
  final VAlbumState albumState;

  /// The album as the server answered it, with its faces.
  final AlbumInfo album;

  /// The URL of the album's folder, which an image URL is built from.
  final String baseUrl;

  /// How often the editor asks again while the detection is pending.
  final Duration pendingInterval;

  const PersonsContent(
    this.albumState,
    this.album,
    this.baseUrl, {
    super.key,
    this.pendingInterval = personsPendingInterval,
  });

  @override
  State<StatefulWidget> createState() => PersonsContentState();
}

class PersonsContentState extends State<PersonsContent>
    with TickerProviderStateMixin {
  /// Where every face stands now, by [AlbumFace.key].
  final Map<String, String> placement = {};

  /// Where the server put every face, by [AlbumFace.key].
  final Map<String, String> stored = {};

  /// The people of the space by their id, loaded once per screen.
  final Map<String, Person> people = {};

  /// The faces selected, by [AlbumFace.key].
  final Set<String> selection = {};

  /// The faces a drag in progress carries, by [AlbumFace.key].
  final Set<String> carried = {};

  /// The face a shift-click measures its range from (issue #139), `null`
  /// before the first click and after the album was read anew.
  String? anchor;

  /// The face the last click landed on and when, so that the next one can be
  /// recognised as the second half of a double click, see [handleTap].
  String? _lastClickKey;
  DateTime _lastClickAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// The scroll view of the page, which the edge scrolling drives.
  final ScrollController _scroll = ScrollController();

  /// Scrolls the page while a carried face rests near the top or the bottom
  /// edge of the view, see [DragEdgeScroller] (issue #42, brought here by
  /// issue #139: a group heading below the fold used to be a dead end).
  late final DragEdgeScroller _dragScroller;

  /// The pointer of the gesture that is currently down on the page, and where
  /// it was seen last, see [_trackPointer].
  int? _pointerId;
  PointerDeviceKind _pointerKind = PointerDeviceKind.touch;
  Offset? _pointerPosition;

  /// How many groups this editor made, so the next one gets a fresh key.
  int _newGroups = 0;

  /// Whether "Not a face" is folded away, which it is until it is opened.
  bool _notAFaceOpen = false;

  /// Whether a Save is on its way to the server.
  bool _saving = false;

  /// The link session this screen stands in, `null` outside one.
  ShareSession? share;

  /// Asks the server again while it is still detecting, see [pending].
  Timer? _poll;

  /// This screen's answer to the router's question whether it may be left.
  late final Future<bool> Function() _leaveGuard = confirmLeave;

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  VAlbumClient get client => widget.albumState.client;

  List<String> get path => widget.albumState.path;

  @override
  void initState() {
    super.initState();
    // A right click belongs to the tile here, not to the browser (issue
    // #144); the menu is given back in [dispose].
    browserMenu.disable();
    _dragScroller = DragEdgeScroller(this, onScrolled: _followScrolledContent);
    _read(widget.album);
    widget.albumState.navigator.delegate
        .registerPersonsGuard(path, _leaveGuard);
    _loadPeople();
    _schedulePoll();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    share = ShareSession.of(context);
  }

  @override
  void didUpdateWidget(PersonsContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.album, widget.album)) {
      _read(widget.album);
      _schedulePoll();
    }
  }

  @override
  void dispose() {
    browserMenu.enable();
    _poll?.cancel();
    widget.albumState.navigator.delegate
        .unregisterPersonsGuard(path, _leaveGuard);
    _dragScroller.dispose();
    _scroll.dispose();
    super.dispose();
  }

  /// Reads the album's faces, keeping what was changed about a face that is
  /// still there.
  ///
  /// A re-fetch — the detection finishing, a Save answering — brings faces
  /// that were not there before and may drop ones that were. What somebody
  /// decided about a face that is still in the answer stays in the buffer;
  /// everything else goes, because there is nothing left to say it about.
  void _read(AlbumInfo album) {
    var kept = Map<String, String>.from(placement);
    stored.clear();
    placement.clear();
    selection.clear();
    anchor = null;
    for (var face in facesOf(album)) {
      var where = storedGroupOf(face.face);
      stored[face.key] = where;
      placement[face.key] = kept[face.key] ?? where;
    }
  }

  /// Every face of the album, the photographs in their own order.
  ///
  /// Every one of them has a crop since issue #155: a stored tag no detection
  /// matched is cut from the original by its own box, exactly as a detection
  /// is, so a tile no longer has to guess which of the two a face is.
  static List<AlbumFace> facesOf(AlbumInfo album) => [
        for (var image in imagesOf(album))
          for (var face in image.faces) AlbumFace(image, face),
      ];

  /// Every photograph of the album, the members of a group included.
  static List<ImagePart> imagesOf(AlbumInfo album) {
    var result = <ImagePart>[];
    for (var part in album.parts) {
      if (part is ImagePart) {
        result.add(part);
      } else if (part is ImageGroup) {
        result.addAll(part.images);
      }
    }
    return result;
  }

  /// Whether the album carries a face at all, see the album's menu entry.
  static bool hasFaces(AlbumInfo album) =>
      imagesOf(album).any((image) => image.faces.isNotEmpty);

  /// The group the server's answer puts the given face in.
  static String storedGroupOf(FaceInfo face) {
    if (face.state == FaceState.notAFace) {
      return notAFaceGroup;
    }
    if (face.person.isNotEmpty) {
      if (face.state == FaceState.confirmed) {
        return "$personGroupPrefix${face.person}";
      }
      if (face.state == FaceState.undecided) {
        // A suggestion of issue #127: somebody's name on a face nobody has
        // confirmed, shown under that person with a question mark.
        return "$suggestedGroupPrefix${face.person}";
      }
      // Rejected: the person stands in the answer so that the suggestion
      // stays away, but the face itself is unknown again.
    }
    return "$clusterGroupPrefix${face.cluster}";
  }

  /// Loads the people of the space once, so the groups can be named.
  Future<void> _loadPeople() async {
    PersonList list;
    try {
      list = await client.people();
    } catch (_) {
      // A register that cannot be read is no reason to show nothing: the
      // groups stand, and a person is named by their id until it can.
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      // Replaced and not merged: a link is answered from both ends and can be
      // taken away elsewhere, so what is shown is the register as it *is*.
      people.clear();
      for (var person in list.people) {
        people[person.id] = person;
      }
    });
  }

  /// Whether the server is still looking for faces in this album.
  bool get pending => widget.album.facesPending;

  /// Asks the server again while it is still detecting, see [pending].
  ///
  /// Never while something is unsaved: a re-fetch rebuilds the groups, and
  /// doing that under somebody's hands while they are sorting faces is worse
  /// than waiting for them to finish.
  void _schedulePoll() {
    _poll?.cancel();
    if (!pending) {
      return;
    }
    _poll = Timer.periodic(widget.pendingInterval, (_) {
      if (!mounted || dirty || _saving) {
        return;
      }
      widget.albumState.navigator.delegate.forget(path);
      widget.albumState.reload();
    });
  }

  /// What this caller may do with the album, as the server answered it.
  Rights get rights =>
      offeredRights(Rights.of(widget.album), CallerInfo.permissionOf(context));

  /// The name the caller is signed in under, empty for an anonymous one.
  String get callerName => CallerInfo.maybeOf(context)?.userName ?? "";

  /// Whether this caller administers the space, who links anybody to anybody.
  bool get isAdmin => CallerInfo.permissionOf(context).role == roleAdmin;

  /// Whether some person of the register already is the caller (issue #128).
  ///
  /// One member is at most one person, so "This is me" is offered only while
  /// nobody carries that name — the server would refuse the second link with
  /// a 409, and an entry that is always refused is no entry.
  bool get callerLinked =>
      callerName.isNotEmpty &&
      people.values.any((person) => person.user == callerName);

  /// Whether this caller may name and correct faces.
  ///
  /// Never inside a share link: a link is not an account, and the server
  /// answers it no face at all, see issue #124.
  bool get mayEdit => rights.mayEdit && share == null;

  /// Whether anything was changed that is not on the server yet.
  ///
  /// Exactly what Save would write, see [delta] — not whether the buffer
  /// differs from what was read (issue #151): a deferred group and the mark of
  /// a taken-back decision live in the buffer alone, the server stores neither,
  /// so after a Save they still differ from the stored state and the guard
  /// used to ask about changes that had nothing left to save.
  bool get dirty => delta().isNotEmpty;

  // -------------------------------------------------------------------------
  // The groups.
  // -------------------------------------------------------------------------

  /// The group the given face stands in on the screen.
  ///
  /// The placement of the buffer, the mark of a taken-back decision stripped
  /// off it (issue #138): "forgotten" is a statement about what will be
  /// *written*, and on the screen such a face is simply back among the
  /// undecided ones.
  String groupShown(AlbumFace face) =>
      shownGroupOf(placement[face.key] ?? stored[face.key] ?? "");

  /// The groups as they are shown: the people, the suggestions under them, the
  /// unknown groups, and "Not a face" last.
  List<FaceGroup> get groups {
    var faces = facesOf(widget.album);
    var byGroup = <String, List<AlbumFace>>{};
    for (var face in faces) {
      byGroup.putIfAbsent(groupShown(face), () => []).add(face);
    }
    // An empty group this editor made stays on the screen: it was made to be
    // filled, and it is filled by the very drop that made it.
    var heldOpen = <String>{};
    for (var n = 1; n <= _newGroups; n++) {
      heldOpen.add("$newGroupPrefix$n");
    }
    // And so does a person the buffer emptied: everything of theirs may have
    // been dragged away or forgotten (issue #138), and putting one face back
    // needs the heading to still be there to put it onto.
    for (var face in faces) {
      var was = stored[face.key] ?? "";
      if (was.startsWith(personGroupPrefix)) {
        heldOpen.add(was);
      }
    }
    for (var key in heldOpen) {
      byGroup.putIfAbsent(key, () => []);
    }

    var result = <FaceGroup>[];
    var seen = <String>{};

    void take(String key) {
      if (!seen.add(key)) {
        return;
      }
      result.add(FaceGroup(key, byGroup[key] ?? const []));
    }

    // The people, each followed by what is only suggested to be them; the
    // ones the buffer emptied stand where they stood.
    for (var face in faces) {
      for (var group in [groupShown(face), stored[face.key] ?? ""]) {
        if (group.startsWith(personGroupPrefix)) {
          take(group);
          take(
            "$suggestedGroupPrefix${group.substring(personGroupPrefix.length)}",
          );
        }
      }
    }
    for (var face in faces) {
      var group = groupShown(face);
      if (group.startsWith(suggestedGroupPrefix)) {
        take(
            "$personGroupPrefix${group.substring(suggestedGroupPrefix.length)}");
        take(group);
      }
    }
    // The unknown ones, the server's clusters first.
    var clusters = byGroup.keys
        .where((key) => key.startsWith(clusterGroupPrefix))
        .toList()
      ..sort();
    clusters.forEach(take);
    for (var n = 1; n <= _newGroups; n++) {
      take("$newGroupPrefix$n");
    }
    // And what is no face at all, last and folded away.
    if (byGroup.containsKey(notAFaceGroup)) {
      take(notAFaceGroup);
    }
    // Nothing is dropped silently: a group nobody named above is still shown.
    for (var key in byGroup.keys) {
      take(key);
    }
    return [
      for (var group in result)
        if (group.faces.isNotEmpty || heldOpen.contains(group.key)) group
    ];
  }

  /// What the heading of the given group reads.
  String headingOf(FaceGroup group, int unknownNumber, int unknownCount) {
    if (group.key == notAFaceGroup) {
      return _l10n.personsNotAFaceGroup;
    }
    if (group.named) {
      return labelOf(group.person);
    }
    if (group.suggested) {
      return _l10n.personsSuggestedHeading(labelOf(group.person));
    }
    return unknownCount > 1
        ? _l10n.personsUnknownGroupNumbered(unknownNumber)
        : _l10n.personsUnknownGroup;
  }

  /// What the person of the given id is called, the id itself where the
  /// register does not name them.
  ///
  /// The nickname where they have one (issue #146): a heading of this editor
  /// stands over photographs, and that is where a person is *called*
  /// something rather than identified.
  String nameOf(String id) {
    var person = people[id];
    return person == null ? id : displayName(person);
  }

  /// What the person of the given id is called among the people shown here.
  ///
  /// [nameOf], except where somebody else on this page is called the same: a
  /// nickname need not be unique, and two headings reading "Oma" would name
  /// nobody, so both of them name the person in full (issue #146).
  String labelOf(String id) => _labels[id] ?? nameOf(id);

  /// What each person shown is called, see [labelOf] and [disambiguate].
  ///
  /// Rebuilt from the groups on the screen at every build, because which
  /// people stand side by side is exactly what changes while faces are moved.
  Map<String, String> _labels = const {};

  // -------------------------------------------------------------------------
  // Selecting and dragging.
  // -------------------------------------------------------------------------

  bool isSelected(AlbumFace face) => selection.contains(face.key);

  void toggle(AlbumFace face) {
    if (!mayEdit) {
      return;
    }
    setState(() {
      if (!selection.remove(face.key)) {
        selection.add(face.key);
      }
      anchor = face.key;
    });
  }

  /// Forgets the click a double click would be counted from, see [handleTap].
  ///
  /// Called wherever something opens over the page — a context menu, the
  /// photograph — so that the click which dismisses it, or the one that comes
  /// after it, is a first click again and never the second half of something
  /// the reader has long stopped doing.
  void _forgetClick() => _lastClickKey = null;

  /// Every face of the page in the order it is drawn (issue #139).
  ///
  /// What a shift-click measures its range in: the groups as they stand, the
  /// faces inside a group in their own order — so a range runs across a group
  /// boundary as naturally as it runs inside one. "Not a face" counts only
  /// while it is open, because a face nobody can see is a face nobody meant
  /// to sweep over.
  List<AlbumFace> get displayOrder => [
        for (var group in groups)
          if (group.key != notAFaceGroup || _notAFaceOpen) ...group.faces,
      ];

  /// Handles a click on the tile of the given face.
  ///
  /// A finger has no modifiers, so a tap keeps toggling as it always did; a
  /// mouse gets the regular semantics of [faceSelectionAfterClick].
  void handleTap(AlbumFace face, PointerDeviceKind kind) {
    if (!mayEdit) {
      return;
    }
    if (!pointerSelects(kind)) {
      toggle(face);
      return;
    }
    var keyboard = HardwareKeyboard.instance;
    var toggles = keyboard.isControlPressed || keyboard.isMetaPressed;
    var ranges = keyboard.isShiftPressed;
    // The second click of a double click, counted here instead of through a
    // [GestureDetector.onDoubleTap] (issue #139): that one holds the gesture
    // arena open for [kDoubleTapTimeout] and would make *every* selecting
    // click of this page wait a third of a second — in the one editor where
    // clicking is the whole work. Selecting is idempotent for a plain click,
    // so acting at once and opening the photograph on the second click costs
    // nothing and delays nothing. A finger keeps its long press: there a
    // second tap means "deselect", which must not open anything.
    //
    // **Only a plain click counts**, on either side: a ctrl-click adds a face
    // and a shift-click draws a range, and neither is half of "show me this
    // photograph" — a modified click is therefore neither the first half nor
    // the second, and it forgets what stood before it, so the plain click
    // that follows selects rather than opening a dialog over the page.
    if (toggles || ranges) {
      _forgetClick();
    } else {
      var now = DateTime.now();
      var again = _lastClickKey == face.key &&
          now.difference(_lastClickAt) < kDoubleTapTimeout;
      _lastClickKey = again ? null : face.key;
      _lastClickAt = now;
      if (again) {
        showPhoto(face);
        return;
      }
    }
    var next = faceSelectionAfterClick(
      order: [for (var one in displayOrder) one.key],
      clicked: face.key,
      selection: selection,
      anchor: anchor,
      toggle: toggles,
      range: ranges,
    );
    setState(() {
      selection
        ..clear()
        ..addAll(next.keys);
      anchor = next.anchor;
    });
  }

  /// What a drag started on the given face carries, see [DraggedFaces].
  DraggedFaces dragOf(AlbumFace face) {
    if (!isSelected(face) || selection.length < 2) {
      return DraggedFaces([face], face);
    }
    var faces = facesOf(widget.album)
        .where((one) => selection.contains(one.key))
        .toList();
    return DraggedFaces(faces, face);
  }

  void startCarry(DraggedFaces dragged) {
    // The page scrolls while the carried face rests near an edge, issue #42.
    _dragScroller.begin(
      _scroll.hasClients ? _scroll.position : null,
      _pointerPosition,
    );
    setState(() {
      carried
        ..clear()
        ..addAll(dragged.faces.map((face) => face.key));
    });
  }

  void endCarry() {
    _dragScroller.end();
    if (carried.isNotEmpty) {
      setState(carried.clear);
    }
  }

  bool isCarried(AlbumFace face) => carried.contains(face.key);

  /// Whether the page is scrolling under a carried face right now.
  ///
  /// Only the tests look at this: it is the proof that the scroller stops
  /// when the pointer leaves the edge band and when the drag ends.
  bool get dragScrolling => _dragScroller.scrolling;

  /// Remembers the pointer that is down on the page and where it is.
  ///
  /// The album does the same for the same reason: the drag machinery reports
  /// the pointer to the drop targets but not to the page, and the edge
  /// scrolling needs both its identity and its place.
  void _trackPointer(PointerEvent event) {
    if (event is PointerDownEvent && carried.isEmpty) {
      _pointerId = event.pointer;
    }
    if (event.pointer != _pointerId) {
      return;
    }
    _pointerKind = event.kind;
    _pointerPosition = event.position;
    if (carried.isNotEmpty) {
      _dragScroller.update(event.position);
    }
  }

  /// Ends the gesture the page was watching, see [_trackPointer].
  void _endGesture(PointerEvent event) {
    if (event.pointer != _pointerId) {
      return;
    }
    _pointerId = null;
    _pointerPosition = null;
    endCarry();
  }

  /// Lets the drop targets follow the page scrolling under a resting pointer.
  ///
  /// A [Draggable] hit tests for its targets only when the pointer moves, so
  /// a heading scrolling under a pointer that rests would neither take the
  /// highlight nor take the drop. A synthesized move of zero length, routed
  /// to the drag after the frame the new offset was laid out in, makes the
  /// drag look again — which is exactly what is needed and nothing more.
  void _followScrolledContent() {
    var pointer = _pointerId;
    var position = _pointerPosition;
    if (pointer == null || position == null) {
      return;
    }
    var kind = _pointerKind;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (!mounted || carried.isEmpty) {
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

  /// Puts the carried faces into the given group.
  void drop(DraggedFaces dragged, String group) =>
      putInto(dragged.faces, group);

  /// Puts the given faces into the given group, clearing the selection.
  ///
  /// What a drop does, and what a menu entry acting on the selection does
  /// (issue #144) — one place, so the two can never mean different things.
  void putInto(Iterable<AlbumFace> faces, String group) {
    setState(() {
      for (var face in faces) {
        placement[face.key] = group;
      }
      selection.clear();
    });
  }

  /// Makes a group of the carried faces, to be named later.
  void dropIntoNewGroup(DraggedFaces dragged) => putIntoNewGroup(dragged.faces);

  /// Makes a group of the given faces, to be named later ("Defer").
  ///
  /// What dropping on "New group" does, reachable without a drag since issue
  /// #139: these faces belong together and who they are is a question for
  /// later.
  void putIntoNewGroup(Iterable<AlbumFace> faces) {
    if (!mayEdit) {
      return;
    }
    setState(() {
      _newGroups++;
      for (var face in faces) {
        placement[face.key] = "$newGroupPrefix$_newGroups";
      }
      selection.clear();
    });
  }

  /// Puts the given faces into "Not a face" (issue #144).
  ///
  /// Exactly what dropping them on that heading does — [drop] into
  /// [notAFaceGroup] — reachable without a drag: Save posts `NOT_A_FACE` for
  /// every one of them, see [delta].
  void markAsNoFace(Iterable<AlbumFace> faces) {
    if (!mayEdit) {
      return;
    }
    putInto(faces, notAFaceGroup);
  }

  /// Takes back what was decided about the given faces (issue #138).
  ///
  /// There are two things to take back, and this takes back both (issue
  /// #139):
  ///
  ///  * a **stored** decision — confirmed, rejected, no face at all — is
  ///    marked as forgotten, so that Save writes the `UNDECIDED` that removes
  ///    the tag; the face falls back into the group it would stand in without
  ///    it, its cluster or the plain "Who is this?";
  ///  * a placement that is only **in the buffer** — dragged somewhere, named
  ///    by a menu, deferred — is simply dropped, and the face stands where the
  ///    server put it again. Nothing is written for it, because nothing was.
  ///
  /// A face with neither is passed over: there is nothing to forget, and an
  /// `UNDECIDED` for it would be a request the server does nothing with.
  void forgetDecision(Iterable<AlbumFace> faces) {
    if (!mayEdit) {
      return;
    }
    setState(() {
      for (var face in faces) {
        if (face.decided) {
          placement[face.key] = face.forgottenGroup;
        } else if (canForget(face)) {
          placement[face.key] = stored[face.key] ?? face.undecidedGroup;
        }
      }
      selection.clear();
    });
  }

  /// Whether there is anything to take back about the given face.
  bool canForget(AlbumFace face) =>
      face.decided || placement[face.key] != stored[face.key];

  /// The selected faces, in the order the album answers them.
  List<AlbumFace> get selected => [
        for (var face in facesOf(widget.album))
          if (selection.contains(face.key)) face
      ];

  /// Whether anything selected carries something that could be taken back.
  bool get mayForget => mayEdit && selected.any(canForget);

  // -------------------------------------------------------------------------
  // The people of the space: created, renamed and merged at once.
  // -------------------------------------------------------------------------

  /// Who already carries a face of this album, see issue #150.
  ///
  /// A confirmation and a suggestion of issue #127 alike: both put a name on
  /// the screen here, and both make that person one of the few this album is
  /// about.
  Set<String> get personsOfAlbum => {
        for (var face in facesOf(widget.album))
          if (face.face.person.isNotEmpty) face.face.person,
      };

  /// Names the faces of the given group, asking who they are.
  ///
  /// Also what "Someone else…" does on a suggestion (issue #139): the guess
  /// is simply not confirmed and the chosen person is, which is one
  /// `CONFIRMED` and nothing else, see [delta].
  Future<void> nameGroup(FaceGroup group) => nameFaces(group.faces);

  /// Names the selected faces, asking who they are (issue #139).
  Future<void> nameSelection() => nameFaces(selected);

  /// Asks who the given faces are and puts them into that person's group.
  Future<void> nameFaces(List<AlbumFace> faces) async {
    if (!mayEdit) {
      return;
    }
    var chosen = await showDialog<Person>(
      context: context,
      builder: (context) => PersonChooser(
        people: people.values.toList(),
        onCreate: createPerson,
        // Who already has a face here stands at the top, see issue #150.
        inAlbum: personsOfAlbum,
      ),
    );
    if (chosen == null || !mounted) {
      return;
    }
    setState(() {
      people[chosen.id] = chosen;
      for (var face in faces) {
        placement[face.key] = "$personGroupPrefix${chosen.id}";
      }
      selection.clear();
    });
  }

  /// Confirms what the server only suggested, see issue #127.
  void confirmSuggestion(FaceGroup group) {
    if (!mayEdit) {
      return;
    }
    setState(() {
      for (var face in group.faces) {
        placement[face.key] = "$personGroupPrefix${group.person}";
      }
    });
  }

  /// Adds a person to the register of the space, `null` where it was refused.
  ///
  /// Posted at once: it is not a change to this album, so Cancel does not take
  /// it back and Save does not write it.
  Future<Person?> createPerson(String name) async {
    try {
      return await client.createPerson(name);
    } catch (error) {
      _refused(error);
      return null;
    }
  }

  /// Renames the person of the given group, everywhere in the space.
  Future<void> renamePerson(FaceGroup group) async {
    var name = await showDialog<String>(
      context: context,
      builder: (context) => PersonNameDialog(
        title: _l10n.personsRenameTitle,
        notice: _l10n.personsRenameNotice,
        // Both halves, in the one field the server parses them out of: a round
        // trip keeps the nickname and deleting the bracket clears it (#146).
        initial: people[group.person] == null
            ? group.person
            : fullLabel(people[group.person]!),
      ),
    );
    if (name == null || !mounted) {
      return;
    }
    Person renamed;
    try {
      renamed = await client.renamePerson(group.person, name);
    } catch (error) {
      _refused(error);
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => people[renamed.id] = renamed);
  }

  /// Merges the person of the given group into another one.
  ///
  /// The merged-away id becomes an alias of the survivor and the server
  /// resolves it on every read (issue #125), so the editor does the same here:
  /// what stood under the old id now stands under the new one, and nothing of
  /// it is a change this album has to write.
  Future<void> mergePerson(FaceGroup group) async {
    var into = await showDialog<Person>(
      context: context,
      builder: (context) => PersonChooser(
        people: [
          for (var person in people.values)
            if (person.id != group.person) person
        ],
        title: _l10n.personsMergeTitle,
        notice: _l10n.personsMergeNotice,
        inAlbum: personsOfAlbum,
      ),
    );
    if (into == null || !mounted) {
      return;
    }
    Person survivor;
    try {
      survivor = await client.mergePersons(into.id, group.person);
    } catch (error) {
      _refused(error);
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      people.remove(group.person);
      people[survivor.id] = survivor;
      _rename(placement, group.person, survivor.id);
      _rename(stored, group.person, survivor.id);
    });
  }

  // -------------------------------------------------------------------------
  // The member a person is (issue #128): posted at once, like a rename.
  // -------------------------------------------------------------------------

  /// Says that the person of the given group is the caller themselves.
  Future<void> linkToMe(FaceGroup group) => linkTo(group.person, callerName);

  /// Links the person of the given group to a member, for an administrator.
  ///
  /// The members are asked of the server here rather than kept: this editor
  /// is about faces, and the one moment it needs to know who is in the space
  /// is the moment somebody opens this chooser.
  Future<void> linkToMember(FaceGroup group) async {
    List<UserEntry> members;
    try {
      members = (await client.users()).users;
    } catch (error) {
      if (mounted) {
        _refused(error);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    var chosen = await showDialog<UserEntry>(
      context: context,
      builder: (context) => MemberChooser(members: linkableMembers(members)),
    );
    if (chosen == null || !mounted) {
      return;
    }
    await linkTo(group.person, chosen.name);
  }

  /// Takes the link of the person of the given group back.
  Future<void> unlinkPerson(FaceGroup group) => linkTo(group.person, "");

  /// Posts the link and shows the register as the server then has it.
  Future<void> linkTo(String id, String user) async {
    Person linked;
    try {
      linked = await client.linkPerson(id, user);
    } catch (error) {
      if (mounted) {
        _refused(error);
      }
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => people[linked.id] = linked);
    await _loadPeople();
  }

  /// The member the person of [id] is, empty where they are nobody.
  String memberOf(String id) => people[id]?.user ?? "";

  static void _rename(Map<String, String> groups, String from, String into) {
    for (var entry in groups.entries.toList()) {
      if (entry.value == "$personGroupPrefix$from") {
        groups[entry.key] = "$personGroupPrefix$into";
      } else if (entry.value == "$suggestedGroupPrefix$from") {
        groups[entry.key] = "$suggestedGroupPrefix$into";
      }
    }
  }

  // -------------------------------------------------------------------------
  // Saving.
  // -------------------------------------------------------------------------

  /// What Save posts: one decision per face that changed, and nothing else.
  List<FaceAssignment> delta() {
    var result = <FaceAssignment>[];
    for (var face in facesOf(widget.album)) {
      var now = placement[face.key];
      var before = stored[face.key];
      if (now == null || now == before) {
        continue;
      }
      if (now.startsWith(forgottenGroupPrefix)) {
        // Taken back (issue #138): the tag on that box is removed and the
        // face is a plain detection again. Only where there *was* a decision
        // — a face the server never stored anything about has nothing to
        // forget, and the request would say nothing.
        if (face.decided) {
          result.add(FaceAssignment(
            image: face.image.name,
            face: face.face.index,
            person: "",
            state: FaceState.undecided,
          ));
        }
      } else if (now.startsWith(personGroupPrefix)) {
        result.add(FaceAssignment(
          image: face.image.name,
          face: face.face.index,
          person: now.substring(personGroupPrefix.length),
          state: FaceState.confirmed,
        ));
      } else if (now == notAFaceGroup) {
        result.add(FaceAssignment(
          image: face.image.name,
          face: face.face.index,
          person: "",
          state: FaceState.notAFace,
        ));
      } else if (face.face.person.isNotEmpty &&
          face.face.state != FaceState.rejected) {
        // Taken out of somebody's group: what is stored is that this face is
        // *not* that person — which is what keeps the suggestion away.
        result.add(FaceAssignment(
          image: face.image.name,
          face: face.face.index,
          person: face.face.person,
          state: FaceState.rejected,
        ));
      }
      // Anything else is a move between two guesses of the server's own,
      // which is nothing the album stores.
    }
    return result;
  }

  /// Writes the decisions back, answering whether the server has them.
  Future<bool> save() async {
    if (refuseWhileOffline(context)) {
      return false;
    }
    var assignments = delta();
    if (assignments.isEmpty) {
      return true;
    }
    setState(() => _saving = true);
    try {
      await client.tagFaces(path, assignments);
    } catch (error) {
      if (mounted) {
        setState(() => _saving = false);
        _refused(error);
      }
      // The buffer stays: a refusal must not throw away what was sorted.
      return false;
    }
    if (!mounted) {
      return true;
    }
    setState(() {
      _saving = false;
      stored
        ..clear()
        ..addAll(placement);
    });
    // The album carries the faces, so it is fetched anew — this screen and
    // the album beneath it then show what the server has.
    widget.albumState.navigator.delegate.forget(path);
    widget.albumState.reload();
    return true;
  }

  /// Says what the server refused, in the server's own words.
  void _refused(Object error) => ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          key: const Key("persons-refusal"),
          content: Text(
            error is VAlbumException ? error.message : "$error",
          ),
          backgroundColor: Colors.red.shade700,
          duration: const Duration(seconds: 8),
        ),
      );

  /// Leaves the editor, asking about unsaved changes first.
  Future<void> cancel() async {
    if (!dirty) {
      widget.albumState.navigator.up();
      return;
    }
    var discard = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("persons-discard-dialog"),
        title: Text(_l10n.personsDiscardTitle),
        content: Text(_l10n.personsDiscardMessage),
        actions: [
          TextButton(
            key: const Key("persons-keep-editing"),
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_l10n.keepEditing),
          ),
          ElevatedButton(
            key: const Key("persons-discard"),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_l10n.discard),
          ),
        ],
      ),
    );
    if (discard != true || !mounted) {
      return;
    }
    setState(() {
      placement
        ..clear()
        ..addAll(stored);
    });
    widget.albumState.navigator.up();
  }

  /// Whether the app may leave this page, asked by the router (issue #99).
  Future<bool> confirmLeave() async {
    if (!mounted || !dirty) {
      return true;
    }
    var choice = await showDialog<LeaveEdit>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key("persons-leave-dialog"),
        title: Text(_l10n.personsSaveTitle),
        content: Text(_l10n.personsSaveMessage),
        actions: [
          TextButton(
            key: const Key("persons-stay"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.stay),
            child: Text(_l10n.stay),
          ),
          TextButton(
            key: const Key("persons-discard-and-leave"),
            onPressed: () => Navigator.of(context).pop(LeaveEdit.discard),
            child: Text(_l10n.discard),
          ),
          ElevatedButton(
            key: const Key("persons-save-and-leave"),
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
        setState(() {
          placement
            ..clear()
            ..addAll(stored);
        });
        return true;
      case LeaveEdit.save:
        return save();
    }
  }

  // -------------------------------------------------------------------------
  // The screen.
  // -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    var shown = groups;
    _labels = disambiguate([
      for (var group in shown)
        if (group.named || group.suggested)
          if (people[group.person] != null) people[group.person]!,
    ]);
    var unknownCount = shown
            .where((group) => group.key.startsWith(clusterGroupPrefix))
            .length +
        shown.where((group) => group.key.startsWith(newGroupPrefix)).length;
    var unknown = 0;

    return Focus(
      autofocus: true,
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent &&
            event.logicalKey == LogicalKeyboardKey.escape) {
          cancel();
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            key: const Key("persons-up"),
            icon: const Icon(Icons.arrow_back),
            tooltip: _l10n.up,
            onPressed: widget.albumState.navigator.up,
          ),
          automaticallyImplyLeading: false,
          title: Column(
            children: [
              Text(_l10n.personsTitle),
              Text(
                selection.isEmpty
                    ? widget.album.title
                    : _l10n.personsSelectedCount(selection.length),
                key: const Key("persons-subtitle"),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
          centerTitle: true,
          actions: [
            // What can be done to the faces standing selected, without
            // dragging any of them anywhere (issue #139): who they are, that
            // they belong together and are somebody yet to be named, and the
            // take-back of issue #138. "Forget" only where something selected
            // has anything to take back — a face nobody decided about and
            // nobody moved is not offered one.
            if (mayEdit && selection.isNotEmpty)
              PopupMenuButton<void Function()>(
                key: const Key("persons-selection-menu"),
                onSelected: (action) => action(),
                itemBuilder: (context) => selectionActions(
                  const [
                    "persons-name",
                    "persons-defer",
                    "persons-not-a-face",
                    "persons-forget",
                  ],
                ),
              ),
            if (mayEdit)
              IconButton(
                key: const Key("persons-save"),
                onPressed: _saving ? null : save,
                tooltip: _l10n.save,
                icon: const Icon(Icons.save),
              ),
            if (mayEdit)
              IconButton(
                key: const Key("persons-cancel"),
                onPressed: cancel,
                tooltip: _l10n.cancel,
                icon: const Icon(Icons.close),
              ),
          ],
        ),
        // Where the pointer of a drag is, and which one it is, see
        // [_trackPointer]: what the edge scrolling of issue #42 runs on.
        body: Listener(
          onPointerDown: _trackPointer,
          onPointerMove: _trackPointer,
          // The end of the gesture, whatever became of it: a [Draggable] whose
          // tile was disposed on the way no longer reports its own end, the
          // pointer does.
          onPointerUp: _endGesture,
          onPointerCancel: _endGesture,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OfflineBanner(onRetry: widget.albumState.reload),
              if (pending)
                _banner(_l10n.personsPendingNotice, "persons-pending"),
              if (!mayEdit)
                _banner(_l10n.personsReadOnlyNotice, "persons-read-only"),
              Expanded(
                child: shown.isEmpty && !pending
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            _l10n.personsEmptyNotice,
                            key: const Key("persons-empty"),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      )
                    : ListView(
                        controller: _scroll,
                        padding: const EdgeInsets.only(bottom: 32),
                        children: [
                          for (var group in shown)
                            _group(
                              group,
                              group.key.startsWith(clusterGroupPrefix) ||
                                      group.key.startsWith(newGroupPrefix)
                                  ? ++unknown
                                  : 0,
                              unknownCount,
                            ),
                          if (mayEdit) _dropTargets(),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The things that can be done to a selection (issue #139, issue #144).
  ///
  /// One list, two places: the app bar's menu and the context menu of a tile,
  /// which must not drift apart — what a right click offers is what the menu
  /// offers, and both act on the very same faces.
  List<PopupMenuEntry<void Function()>> selectionActions(List<String> keys) => [
        PopupMenuItem<void Function()>(
          key: Key(keys[0]),
          value: nameSelection,
          child: Text(_l10n.personsNameEntry),
        ),
        PopupMenuItem<void Function()>(
          key: Key(keys[1]),
          value: () => putIntoNewGroup(selected),
          child: Text(_l10n.personsDeferEntry),
        ),
        PopupMenuItem<void Function()>(
          key: Key(keys[2]),
          value: () => markAsNoFace(selected),
          child: Text(_l10n.personsNotAFaceEntry),
        ),
        if (mayForget)
          PopupMenuItem<void Function()>(
            key: Key(keys[3]),
            value: () => forgetDecision(selected),
            child: Text(_l10n.personsForgetEntry),
          ),
      ];

  /// The context menu of a tile, opened by a secondary click (issue #139).
  ///
  /// It acts on the selection the face is part of; a face standing outside it
  /// *becomes* the selection first, which is what a right click does
  /// everywhere — nothing is ever done to something the pointer is not on.
  Future<void> showFaceMenu(AlbumFace face, Offset position) async {
    if (!mayEdit) {
      return;
    }
    _forgetClick();
    if (!isSelected(face)) {
      setState(() {
        selection
          ..clear()
          ..add(face.key);
        anchor = face.key;
      });
    }
    var overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
    var action = await showMenu<void Function()>(
      context: context,
      position: RelativeRect.fromRect(
        position & Size.zero,
        Offset.zero & overlay.size,
      ),
      items: [
        PopupMenuItem<void Function()>(
          key: const Key("persons-context-show"),
          value: () => showPhoto(face),
          child: Text(_l10n.personsShowPhoto),
        ),
        ...selectionActions(const [
          "persons-context-name",
          "persons-context-defer",
          "persons-context-not-a-face",
          "persons-context-forget",
        ]),
      ],
    );
    if (!mounted) {
      return;
    }
    action?.call();
  }

  Widget _banner(String text, String key) => Material(
        color: Colors.blueGrey.shade700,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(
            text,
            key: Key(key),
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white),
          ),
        ),
      );

  /// One group: its heading and the faces standing under it.
  Widget _group(FaceGroup group, int unknownNumber, int unknownCount) {
    var folded = group.key == notAFaceGroup && !_notAFaceOpen;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _heading(group, unknownNumber, unknownCount, folded),
        if (!folded)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var face in group.faces) _tile(face),
              ],
            ),
          ),
      ],
    );
  }

  /// The heading of a group, which is also what faces are dropped onto.
  Widget _heading(
    FaceGroup group,
    int unknownNumber,
    int unknownCount,
    bool folded,
  ) {
    var text = headingOf(group, unknownNumber, unknownCount);
    var member = group.named ? memberOf(group.person) : "";
    var row = Row(
      children: [
        if (group.key == notAFaceGroup)
          Icon(folded ? Icons.chevron_right : Icons.expand_more),
        Expanded(
          child: Text(
            text,
            key: Key("persons-heading-${group.key}"),
            style: Theme.of(context).textTheme.titleMedium,
          ),
        ),
        // Who of the space this person is (issue #128), said where the person
        // is named: a badge and not a line, because it belongs to the name.
        if (member.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: Chip(
              key: const Key("persons-member-badge"),
              avatar: const Icon(Icons.account_circle, size: 18),
              label: Text(_l10n.personsMemberBadge(member)),
              visualDensity: VisualDensity.compact,
            ),
          ),
        Text(
          _l10n.personsFaceCount(group.faces.length),
          key: Key("persons-count-${group.key}"),
          style: Theme.of(context).textTheme.bodySmall,
        ),
        if (mayEdit && group.suggested)
          Padding(
            padding: const EdgeInsets.only(left: 8),
            child: TextButton(
              key: Key("persons-confirm-${group.person}"),
              onPressed: () => confirmSuggestion(group),
              child: Text(_l10n.personsConfirmSuggestion),
            ),
          ),
        // The other answer to "is this Anna?" (issue #139): it is somebody
        // else, and saying who is one step instead of a drag per face. The
        // wrong guess is simply not confirmed — nothing rejects it, because
        // naming the right person is the whole statement.
        if (mayEdit && group.suggested)
          Padding(
            padding: const EdgeInsets.only(left: 8),
            child: TextButton(
              key: Key("persons-someone-else-${group.person}"),
              onPressed: () => nameGroup(group),
              child: Text(_l10n.personsSomeoneElse),
            ),
          ),
        if (mayEdit && group.named)
          PopupMenuButton<void Function()>(
            key: Key("persons-menu-${group.person}"),
            onSelected: (action) => action(),
            itemBuilder: (context) => [
              PopupMenuItem<void Function()>(
                key: const Key("persons-rename"),
                value: () => renamePerson(group),
                child: Text(_l10n.personsRenameEntry),
              ),
              PopupMenuItem<void Function()>(
                key: const Key("persons-merge"),
                value: () => mergePerson(group),
                child: Text(_l10n.personsMergeEntry),
              ),
              // Who this person is in the space (issue #128). A member says
              // it of themselves, an administrator of anybody; what is
              // already said is taken back by whoever may — the
              // administrator, and the member it is about.
              if (member.isEmpty && callerName.isNotEmpty && !callerLinked)
                PopupMenuItem<void Function()>(
                  key: const Key("persons-link-me"),
                  value: () => linkToMe(group),
                  child: Text(_l10n.personsLinkMeEntry),
                ),
              if (member.isEmpty && isAdmin)
                PopupMenuItem<void Function()>(
                  key: const Key("persons-link-member"),
                  value: () => linkToMember(group),
                  child: Text(_l10n.personsLinkMemberEntry),
                ),
              if (member.isNotEmpty && (isAdmin || member == callerName))
                PopupMenuItem<void Function()>(
                  key: const Key("persons-unlink"),
                  value: () => unlinkPerson(group),
                  child: Text(_l10n.personsUnlinkEntry),
                ),
            ],
          ),
      ],
    );

    Widget heading = InkWell(
      key: Key("persons-header-${group.key}"),
      onTap: () {
        if (group.key == notAFaceGroup) {
          setState(() => _notAFaceOpen = !_notAFaceOpen);
        } else if (mayEdit && !group.named && !group.suggested) {
          nameGroup(group);
        }
      },
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
        child: row,
      ),
    );

    if (!mayEdit || !takesDrops(group.key)) {
      return heading;
    }
    return DragTarget<DraggedFaces>(
      onWillAcceptWithDetails: (details) => true,
      onAcceptWithDetails: (details) => drop(details.data, group.key),
      builder: (context, candidate, rejected) => Material(
        color: candidate.isEmpty
            ? Colors.transparent
            : Theme.of(context).colorScheme.primaryContainer,
        child: heading,
      ),
    );
  }

  /// The two targets at the foot of the page: a new group, and "Forget".
  ///
  /// Side by side because they are the two answers to "this face does not
  /// belong where it stands": it is somebody else (a group of its own), or
  /// nobody said anything about it after all (issue #138).
  Widget _dropTargets() => Row(
        children: [
          Expanded(child: _newGroupTarget()),
          Expanded(child: _forgetTarget()),
        ],
      );

  /// The target that makes a group of what is dropped onto it.
  Widget _newGroupTarget() => DragTarget<DraggedFaces>(
        onWillAcceptWithDetails: (details) => true,
        onAcceptWithDetails: (details) => dropIntoNewGroup(details.data),
        builder: (context, candidate, rejected) => _target(
          candidate,
          _l10n.personsNewGroup,
          const Key("persons-new-group"),
        ),
      );

  /// The target that takes back what was decided about what is dropped onto
  /// it (issue #138).
  Widget _forgetTarget() => DragTarget<DraggedFaces>(
        onWillAcceptWithDetails: (details) => true,
        onAcceptWithDetails: (details) => forgetDecision(details.data.faces),
        builder: (context, candidate, rejected) => _target(
          candidate,
          _l10n.personsForgetTarget,
          const Key("persons-forget-target"),
        ),
      );

  /// The frame both foot targets are drawn in.
  Widget _target(List<Object?> candidate, String label, Key key) => Padding(
        padding: const EdgeInsets.all(12),
        child: Material(
          color: candidate.isEmpty
              ? Colors.transparent
              : Theme.of(context).colorScheme.primaryContainer,
          shape: RoundedRectangleBorder(
            side: BorderSide(color: Theme.of(context).dividerColor),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Text(label, key: key, textAlign: TextAlign.center),
          ),
        ),
      );

  /// One face: its crop, selectable, draggable and openable.
  Widget _tile(AlbumFace face) {
    var picture = FaceTile(
      client: client,
      baseUrl: widget.baseUrl,
      face: face,
      size: faceTileSize,
    );
    var selected = isSelected(face);
    Widget box = Container(
      width: faceTileSize,
      height: faceTileSize,
      decoration: BoxDecoration(
        border: Border.all(
          color: selected ? Colors.blueAccent : Colors.transparent,
          width: 3,
        ),
      ),
      child: Opacity(opacity: isCarried(face) ? 0.3 : 1, child: picture),
    );

    Widget tile = Tooltip(
      message: _l10n.personsShowPhoto,
      child: GestureDetector(
        key: Key("face-${face.key}"),
        // The pointer kind decides what a click means (issue #139): a finger
        // toggles, a mouse selects, adds and ranges — and a second click of a
        // mouse on the same face opens the photograph, see [handleTap].
        onTapUp: (details) => handleTap(face, details.kind),
        // Three ways to the photograph the crop was cut from, and one of them
        // a mouse finds: the double click above, the long press a finger
        // makes, and the first entry of the context menu.
        onLongPress: () => showPhoto(face),
        onSecondaryTapUp: (details) =>
            showFaceMenu(face, details.globalPosition),
        child: box,
      ),
    );

    if (!mayEdit) {
      return tile;
    }
    var dragged = dragOf(face);
    return SizedBox(
      width: faceTileSize,
      height: faceTileSize,
      child: Stack(
        children: [
          _carrier(dragged, affinity: Axis.horizontal, child: tile),
          Positioned(top: 0, right: 0, child: _handle(dragged)),
        ],
      ),
    );
  }

  /// A [Draggable] carrying the given faces, the [ReorderablePart] of the
  /// album's edit mode built for a face (issue #94).
  ///
  /// The same two ways up as there: the tile itself on a sideways pull, which
  /// is what a mouse does naturally, and a handle that lifts it in any
  /// direction, which is the only gesture a finger reliably makes.
  Widget _carrier(
    DraggedFaces dragged, {
    Axis? affinity,
    required Widget child,
  }) =>
      Draggable<DraggedFaces>(
        data: dragged,
        onDragStarted: () => startCarry(dragged),
        onDragEnd: (details) => endCarry(),
        onDraggableCanceled: (velocity, offset) => endCarry(),
        affinity: affinity,
        hitTestBehavior: HitTestBehavior.opaque,
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
                child: FaceTile(
                  client: client,
                  baseUrl: widget.baseUrl,
                  face: dragged.primary,
                  size: faceTileSize,
                ),
              ),
            ),
          ),
        ),
        child: child,
      );

  Widget _handle(DraggedFaces dragged) => _carrier(
        dragged,
        child: Tooltip(
          message: _l10n.personsDragToGroup,
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

  /// Shows the whole photograph a face was found in, the face marked on it.
  ///
  /// A dialog and not the viewer route: the viewer is a page level of the
  /// *album*, so opening it would take this page off the stack and the buffer
  /// with it. What is wanted here is only "let me see the whole picture" —
  /// and, since issue #139, *where in it*: the crop of a tile is a hundred
  /// pixels of a face and says nothing about the scene it was cut from, so
  /// the picture fills the screen and carries the very box the tile shows.
  ///
  /// Three gestures lead here (issue #139): a double click, the long press a
  /// finger makes, and the first entry of the tile's context menu — the
  /// tooltip promised the photograph long before anything but a long press
  /// delivered it.
  void showPhoto(AlbumFace face) {
    _forgetClick();
    showDialog<void>(
      context: context,
      builder: (context) => Dialog(
        key: const Key("persons-photo"),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(child: markedPhoto(context, face)),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  face.image.name,
                  key: const Key("persons-photo-name"),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(_l10n.close),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The photograph as large as the screen allows, with [face] marked on it.
  ///
  /// The box of a face is normalised in the **raw raster of the file** — the
  /// frame before every turn, see issue #124 — which is exactly the frame the
  /// picture is drawn in here: the rendition at the file's own aspect ratio,
  /// the marker placed on it by the plain fractions it carries, and the two of
  /// them turned together by [ImagePart.orientation] through [orientedBox].
  /// That is the rule [FaceTile.clipped] follows for a tag without a crop, so
  /// what is marked here is the very area a tile shows.
  Widget markedPhoto(BuildContext context, AlbumFace face) {
    var info = face.face;
    var screen = MediaQuery.sizeOf(context);
    // What is left of the screen for the picture: the dialog's own margins,
    // the file name and the Close button stand beside it.
    var availableWidth = max(screen.width * 0.9 - 48, 120.0);
    var availableHeight = max(screen.height * 0.9 - 140, 120.0);

    var fileWidth = face.image.width > 0 ? face.image.width.toDouble() : 1.0;
    var fileHeight = face.image.height > 0 ? face.image.height.toDouble() : 1.0;
    var transform = PlaneTransform.of(face.image.orientation);
    var turnedWidth = transform.swapsDimensions ? fileHeight : fileWidth;
    var turnedHeight = transform.swapsDimensions ? fileWidth : fileHeight;
    var scale =
        min(availableWidth / turnedWidth, availableHeight / turnedHeight);
    var drawnWidth = fileWidth * scale;
    var drawnHeight = fileHeight * scale;

    return orientedBox(
      face.image.orientation,
      SizedBox(
        key: const Key("persons-photo-picture"),
        width: drawnWidth,
        height: drawnHeight,
        child: Stack(
          children: [
            Positioned.fill(
              child: thumbnail(
                client,
                "${widget.baseUrl}/${face.image.name}",
                width: drawnWidth,
                height: drawnHeight,
                displayHeight: drawnHeight,
                fit: BoxFit.fill,
              ),
            ),
            Positioned(
              left: info.x * drawnWidth,
              top: info.y * drawnHeight,
              width: max(info.w * drawnWidth, 2.0),
              height: max(info.h * drawnHeight, 2.0),
              child: Container(
                key: const Key("persons-photo-box"),
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.blueAccent, width: 3),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The picture of one face: the server's crop, or the photograph's own
/// thumbnail clipped to the box.
///
/// A face the detector found has a crop of its own — cut from the preview with
/// a margin, see issue #124 — and that is what is shown. A face that is only a
/// **stored tag** (issue #125) has none: the server answers it behind the
/// detections, without a cluster and without a crop, and it is drawn here from
/// the picture and the box it carries. Nothing is requested for it, so nothing
/// 404s.
///
/// The box is normalised in the **raw raster of the file**. What the app can
/// put over it is [ImagePart.orientation], the turn stored beside the image,
/// which [orientedBox] applies to the finished tile; the EXIF orientation the
/// server baked into the thumbnail is not on the wire, so a file that carries
/// one is clipped in its own upright frame.
class FaceTile extends StatelessWidget {
  final VAlbumClient client;

  /// The URL of the album's folder.
  final String baseUrl;

  /// The face shown.
  final AlbumFace face;

  /// The side of the square tile.
  final double size;

  const FaceTile({
    super.key,
    required this.client,
    required this.baseUrl,
    required this.face,
    required this.size,
  });

  String get imageUrl => "$baseUrl/${face.image.name}";

  @override
  Widget build(BuildContext context) {
    // Every face has a crop since issue #155 — a tag without a detection is
    // cut from the original by its own box — so the clipped thumbnail is only
    // what stands in for a crop that cannot be had.
    return Image(
      image: FaceImage(client, imageUrl, face.face.index),
      width: size,
      height: size,
      fit: BoxFit.cover,
      gaplessPlayback: true,
      // A crop the server does not have after all: the picture and the box
      // say the same thing, so nothing is left blank.
      errorBuilder: (context, error, stack) => clipped(),
    );
  }

  /// The photograph's thumbnail, clipped to the face's box.
  Widget clipped() {
    var info = face.face;
    var width = face.image.width > 0 ? face.image.width.toDouble() : 1.0;
    var height = face.image.height > 0 ? face.image.height.toDouble() : 1.0;
    // The same quarter-box margin the server's crop leaves, so the two kinds
    // of tile show the face at the same size.
    var span = max(info.w * 1.5 * width, info.h * 1.5 * height);
    if (span <= 0) {
      span = max(width, height);
    }
    var scale = size / span;
    var drawnWidth = width * scale;
    var drawnHeight = height * scale;
    var centerX = (info.x + info.w / 2) * drawnWidth;
    var centerY = (info.y + info.h / 2) * drawnHeight;

    return orientedBox(
      face.image.orientation,
      ClipRect(
        child: SizedBox(
          width: size,
          height: size,
          child: Stack(
            clipBehavior: Clip.hardEdge,
            children: [
              Positioned(
                left: size / 2 - centerX,
                top: size / 2 - centerY,
                width: drawnWidth,
                height: drawnHeight,
                child: thumbnail(
                  client,
                  imageUrl,
                  width: drawnWidth,
                  height: drawnHeight,
                  displayHeight: drawnHeight,
                  fit: BoxFit.fill,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The members an administrator may link a person to (issue #128).
///
/// A named member who has joined and whom no person claims yet: a pending
/// invitation is nobody yet, and one member is at most one person, so a member
/// the register already holds is not offered a second time.
List<UserEntry> linkableMembers(List<UserEntry> users) => [
      for (var user in users)
        if (!user.pending && user.name.trim().isNotEmpty && user.person.isEmpty)
          user
    ];

/// Which member a person is: the administrator's chooser of issue #128.
class MemberChooser extends StatelessWidget {
  /// The members offered, see [linkableMembers].
  final List<UserEntry> members;

  const MemberChooser({super.key, required this.members});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      key: const Key("persons-member-chooser"),
      title: Text(l10n.personsLinkChooseTitle),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l10n.personsLinkChooseNotice),
            const SizedBox(height: 8),
            if (members.isEmpty)
              Text(
                l10n.personsLinkNobodyFree,
                key: const Key("persons-link-nobody"),
              ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (var member in members)
                    ListTile(
                      key: Key("persons-member-${member.name}"),
                      title: Text(member.name),
                      onTap: () => Navigator.of(context).pop(member),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key("persons-member-chooser-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
      ],
    );
  }
}

/// Who a group of faces is: one of the people of the space, or a new one.
/// The given people in the order a chooser shows them, see issue #150.
///
/// By what is written on the line — the display name of issue #146, the
/// nickname where there is one — compared ignoring case, because a reader
/// looking for a name does not know how it is capitalised. The canonical name
/// breaks a tie, so two people called the same keep a fixed order (and are
/// shown by their full label anyway, see [disambiguate]).
List<Person> byDisplayName(List<Person> people) {
  var result = [...people];
  result.sort((one, other) {
    var byShown = displayName(one)
        .toLowerCase()
        .compareTo(displayName(other).toLowerCase());
    return byShown != 0 ? byShown : one.name.compareTo(other.name);
  });
  return result;
}

/// Moves the chooser's highlight one entry down, see issue #150.
class _NextPerson extends Intent {
  const _NextPerson();
}

/// Moves the chooser's highlight one entry up, see issue #150.
class _PreviousPerson extends Intent {
  const _PreviousPerson();
}

/// Picks what the chooser's highlight stands on, see issue #150.
class _PickPerson extends Intent {
  const _PickPerson();
}

class PersonChooser extends StatefulWidget {
  /// The people offered.
  final List<Person> people;

  /// What the dialog is called; the naming of a group when nothing is said.
  final String? title;

  /// One line under the title, where there is something to say.
  final String? notice;

  /// Adds a person of the given name, `null` where the server refused;
  /// absent where a new person is not offered (the merge).
  final Future<Person?> Function(String name)? onCreate;

  /// Who of them is already in the album being named, see issue #150.
  ///
  /// The ids of the people who carry a confirmed face or a suggestion of issue
  /// #127 in the album at hand. They stand in a section of their own at the
  /// top, because naming a face of a family album means one of the handful of
  /// people that album is about far more often than it means one of the
  /// hundreds the space knows.
  final Set<String> inAlbum;

  const PersonChooser({
    super.key,
    required this.people,
    this.title,
    this.notice,
    this.onCreate,
    this.inAlbum = const {},
  });

  @override
  State<StatefulWidget> createState() => _PersonChooserState();
}

class _PersonChooserState extends State<PersonChooser> {
  final TextEditingController _search = TextEditingController();

  /// Which of the remaining entries the keyboard stands on, `-1` for none.
  ///
  /// Counted over both sections in the order they are shown (issue #150): the
  /// keyboard walks what is on the screen, not what the register holds. It
  /// starts on nothing — a chooser that opened with somebody highlighted would
  /// pick that person on a stray `Enter` — and it is **reset by every change
  /// of the filter**, because a highlight left on an entry the filter removed
  /// would pick somebody nobody can see.
  int _highlighted = -1;

  /// Where each shown row is, so that the highlighted one can be scrolled to.
  final Map<String, GlobalKey> _rows = {};

  /// The entries as they stand on the screen, filled by every build.
  List<Person> _shown = const [];

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  /// Moves the highlight by [step], never past either end (issue #150).
  void _move(int step) {
    if (_shown.isEmpty) {
      return;
    }
    var next = _highlighted < 0
        ? (step > 0 ? 0 : _shown.length - 1)
        : (_highlighted + step).clamp(0, _shown.length - 1);
    setState(() => _highlighted = next);
    // After the frame that marks it: the row may not be laid out before.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _highlighted < 0 || _highlighted >= _shown.length) {
        return;
      }
      var row = _rows[_shown[_highlighted].id]?.currentContext;
      if (row != null) {
        Scrollable.ensureVisible(row, alignment: 0.5, duration:
            const Duration(milliseconds: 100));
      }
    });
  }

  /// What `Enter` does: pick the highlighted person, or create the typed one.
  ///
  /// Where nothing is highlighted and the typed text matches nobody, typing a
  /// name and pressing `Enter` is the "New person…" entry with that name —
  /// which is what somebody who has just typed a name nobody carries means.
  void _enter() {
    if (_highlighted >= 0 && _highlighted < _shown.length) {
      Navigator.of(context).pop(_shown[_highlighted]);
      return;
    }
    if (_shown.isEmpty && widget.onCreate != null &&
        _search.text.trim().isNotEmpty) {
      _create();
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var needle = _search.text.trim().toLowerCase();
    var shown = [
      for (var person in widget.people)
        if (needle.isEmpty ||
            person.name.toLowerCase().contains(needle) ||
            person.nickname.toLowerCase().contains(needle))
          person
    ];
    // Here a person is *chosen*, so both names are said: the one they are
    // called by as the line, the one they are identified by beneath it, and
    // two people called the same are told apart by the first line already
    // (issue #146).
    var labels = disambiguate(shown);
    // In this album first, everybody else behind them, each section in the
    // order of what is written on it (issue #150).
    var here = byDisplayName(
      [for (var person in shown) if (widget.inAlbum.contains(person.id)) person],
    );
    var elsewhere = byDisplayName(
      [for (var person in shown) if (!widget.inAlbum.contains(person.id)) person],
    );
    // What the keyboard walks: the two sections, in the order they are drawn.
    _shown = [...here, ...elsewhere];
    // The arrows and `Enter` while the typing goes on: the idiom of Flutter's
    // own autocomplete, because a single-line field answers neither (issue
    // #150). `Escape` is the dialog's own and is not taken away here.
    return Shortcuts(
      shortcuts: const <ShortcutActivator, Intent>{
        SingleActivator(LogicalKeyboardKey.arrowDown): _NextPerson(),
        SingleActivator(LogicalKeyboardKey.arrowUp): _PreviousPerson(),
        SingleActivator(LogicalKeyboardKey.enter): _PickPerson(),
        SingleActivator(LogicalKeyboardKey.numpadEnter): _PickPerson(),
      },
      child: Actions(
        actions: <Type, widgets.Action<Intent>>{
          _NextPerson: CallbackAction<_NextPerson>(
            onInvoke: (_) {
              _move(1);
              return null;
            },
          ),
          _PreviousPerson: CallbackAction<_PreviousPerson>(
            onInvoke: (_) {
              _move(-1);
              return null;
            },
          ),
          _PickPerson: CallbackAction<_PickPerson>(
            onInvoke: (_) {
              _enter();
              return null;
            },
          ),
        },
        child: _dialog(context, l10n, labels, here, elsewhere),
      ),
    );
  }

  Widget _dialog(
    BuildContext context,
    AppLocalizations l10n,
    Map<String, String> labels,
    List<Person> here,
    List<Person> elsewhere,
  ) {
    return AlertDialog(
      key: const Key("persons-chooser"),
      title: Text(widget.title ?? l10n.personsChooseTitle),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.notice != null) Text(widget.notice!),
            TextField(
              key: const Key("persons-search"),
              controller: _search,
              decoration: InputDecoration(labelText: l10n.personsSearchLabel),
              // A changed filter leaves the highlight nowhere, see [_highlighted].
              onChanged: (_) => setState(() => _highlighted = -1),
              autofocus: true,
              onSubmitted: (_) => _enter(),
            ),
            const SizedBox(height: 8),
            if (_shown.isEmpty && widget.people.isEmpty)
              Text(l10n.personsNobodyYet, key: const Key("persons-nobody")),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  // A section with nobody in it is no section: a heading over
                  // an empty list says there is something there.
                  if (here.isNotEmpty && elsewhere.isNotEmpty)
                    _heading(
                      context,
                      l10n.personsChooserInAlbum,
                      const Key("persons-chooser-in-album"),
                    ),
                  for (var person in here) _pick(context, person, labels),
                  if (here.isNotEmpty && elsewhere.isNotEmpty)
                    _heading(
                      context,
                      l10n.personsChooserAll,
                      const Key("persons-chooser-all"),
                    ),
                  for (var person in elsewhere) _pick(context, person, labels),
                ],
              ),
            ),
            if (widget.onCreate != null)
              TextButton.icon(
                key: const Key("persons-new-person"),
                onPressed: _create,
                icon: const Icon(Icons.person_add),
                label: Text(l10n.personsNewPersonEntry),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key("persons-chooser-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
      ],
    );
  }

  /// One person to pick, see issue #146 for the two names.
  Widget _pick(BuildContext context, Person person, Map<String, String> labels) {
    var highlighted = _highlighted >= 0 &&
        _highlighted < _shown.length &&
        _shown[_highlighted].id == person.id;
    return KeyedSubtree(
      // Only so that the highlighted row can be scrolled to; the marking
      // itself is the tile's own, which paints it on the Material it stands
      // on rather than behind a box of its own.
      key: _rows.putIfAbsent(person.id, () => GlobalKey()),
      child: ListTile(
        selected: highlighted,
        selectedTileColor:
            Theme.of(context).colorScheme.primary.withValues(alpha: 0.12),
        key: Key("persons-pick-${person.id}"),
        title: Text(labels[person.id] ?? displayName(person)),
        subtitle: person.nickname.trim().isEmpty
            ? null
            : Text(
                person.name,
                key: Key("persons-pick-name-${person.id}"),
                style: Theme.of(context).textTheme.bodySmall,
              ),
        onTap: () => Navigator.of(context).pop(person),
      ),
    );
  }

  /// What a section of the chooser is called, see issue #150.
  Widget _heading(BuildContext context, String text, Key key) => Padding(
        padding: const EdgeInsets.only(top: 8, bottom: 4),
        child: Text(
          text,
          key: key,
          style: Theme.of(context).textTheme.labelMedium,
        ),
      );

  Future<void> _create() async {
    var l10n = AppLocalizations.of(context)!;
    var name = await showDialog<String>(
      context: context,
      builder: (context) => PersonNameDialog(
        title: l10n.personsNewPersonTitle,
        initial: _search.text.trim(),
      ),
    );
    if (name == null || !mounted) {
      return;
    }
    var created = await widget.onCreate!(name);
    if (created == null || !mounted) {
      return;
    }
    Navigator.of(context).pop(created);
  }
}

/// Asks for a person's name: the new one, and the renaming of an old one.
class PersonNameDialog extends StatefulWidget {
  /// What the dialog is called.
  final String title;

  /// One line under the title, where there is something to say.
  final String? notice;

  /// What the field starts with.
  final String initial;

  const PersonNameDialog({
    super.key,
    required this.title,
    this.notice,
    this.initial = "",
  });

  @override
  State<StatefulWidget> createState() => _PersonNameDialogState();
}

class _PersonNameDialogState extends State<PersonNameDialog> {
  late final TextEditingController _name =
      TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      key: const Key("persons-name-dialog"),
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.notice != null) Text(widget.notice!),
          TextField(
            key: const Key("persons-name"),
            controller: _name,
            autofocus: true,
            decoration: InputDecoration(labelText: l10n.personsNameLabel),
            onSubmitted: (_) => _accept(),
          ),
        ],
      ),
      actions: [
        TextButton(
          key: const Key("persons-name-cancel"),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        ElevatedButton(
          key: const Key("persons-name-ok"),
          onPressed: _accept,
          child: Text(l10n.save),
        ),
      ],
    );
  }

  void _accept() {
    var name = _name.text.trim();
    if (name.isEmpty) {
      return;
    }
    Navigator.of(context).pop(name);
  }
}
