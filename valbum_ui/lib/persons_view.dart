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

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';

import 'album_view.dart' show LeaveEdit, dragFeedbackScale, dragHandleSize;
import 'app.dart';
import 'caller.dart';
import 'client.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
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

  /// Whether the server has a crop of it, see [PersonsContentState.facesOf].
  final bool detected;

  const AlbumFace(this.image, this.face, {required this.detected});

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

class PersonsContentState extends State<PersonsContent> {
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
    _read(widget.album);
    widget.albumState.navigator.delegate.registerPersonsGuard(path, _leaveGuard);
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
    _poll?.cancel();
    widget.albumState.navigator.delegate
        .unregisterPersonsGuard(path, _leaveGuard);
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
    for (var face in facesOf(album)) {
      var where = storedGroupOf(face.face);
      stored[face.key] = where;
      placement[face.key] = kept[face.key] ?? where;
    }
  }

  /// Every face of the album, the photographs in their own order.
  ///
  /// Whether the server has a **crop** of a face cannot be read off one
  /// answer: the detections come first and a stored tag no detection matched
  /// is appended behind them (issue #125), and the wire numbers both the same
  /// way. What tells them apart is the cluster — a detection of a clustered
  /// album carries one, an appended tag never does — so a face without a
  /// cluster standing behind every clustered one is drawn from the picture
  /// and its box instead of from a crop. The doubtful case (an album whose
  /// faces are not clustered yet) falls to the picture as well, which is the
  /// harmless direction: the box is right either way, and no request is made
  /// for a crop that is not there.
  static List<AlbumFace> facesOf(AlbumInfo album) {
    var result = <AlbumFace>[];
    for (var image in imagesOf(album)) {
      var clustered = -1;
      for (var face in image.faces) {
        if (face.cluster.isNotEmpty) {
          clustered = max(clustered, face.index);
        }
      }
      for (var face in image.faces) {
        result.add(AlbumFace(
          image,
          face,
          detected: face.cluster.isNotEmpty || face.index < clustered,
        ));
      }
    }
    return result;
  }

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
  bool get dirty =>
      placement.entries.any((entry) => stored[entry.key] != entry.value);

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
        take("$personGroupPrefix${group.substring(suggestedGroupPrefix.length)}");
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
      return nameOf(group.person);
    }
    if (group.suggested) {
      return _l10n.personsSuggestedHeading(nameOf(group.person));
    }
    return unknownCount > 1
        ? _l10n.personsUnknownGroupNumbered(unknownNumber)
        : _l10n.personsUnknownGroup;
  }

  /// What the person of the given id is called, the id itself where the
  /// register does not name them.
  String nameOf(String id) => people[id]?.name ?? id;

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

  void startCarry(DraggedFaces dragged) => setState(() {
        carried
          ..clear()
          ..addAll(dragged.faces.map((face) => face.key));
      });

  void endCarry() => setState(() => carried.clear());

  bool isCarried(AlbumFace face) => carried.contains(face.key);

  /// Puts the carried faces into the given group.
  void drop(DraggedFaces dragged, String group) {
    setState(() {
      for (var face in dragged.faces) {
        placement[face.key] = group;
      }
      selection.clear();
    });
  }

  /// Makes a group of the carried faces, to be named later.
  void dropIntoNewGroup(DraggedFaces dragged) {
    setState(() => _newGroups++);
    drop(dragged, "$newGroupPrefix$_newGroups");
  }

  /// Takes back what was decided about the given faces (issue #138).
  ///
  /// A face nobody decided about is passed over: there is nothing to forget,
  /// and an `UNDECIDED` for it would be a request the server does nothing
  /// with. What is forgotten falls back into the group it would stand in
  /// without the decision — its cluster, or the plain "Who is this?".
  void forgetDecision(Iterable<AlbumFace> faces) {
    if (!mayEdit) {
      return;
    }
    setState(() {
      for (var face in faces) {
        if (!face.decided) {
          continue;
        }
        placement[face.key] = face.forgottenGroup;
      }
      selection.clear();
    });
  }

  /// The selected faces, in the order the album answers them.
  List<AlbumFace> get selected => [
        for (var face in facesOf(widget.album))
          if (selection.contains(face.key)) face
      ];

  /// Whether anything selected carries a decision that could be forgotten.
  bool get mayForget => mayEdit && selected.any((face) => face.decided);

  // -------------------------------------------------------------------------
  // The people of the space: created, renamed and merged at once.
  // -------------------------------------------------------------------------

  /// Names the faces of the given group, asking who they are.
  Future<void> nameGroup(FaceGroup group) async {
    if (!mayEdit) {
      return;
    }
    var chosen = await showDialog<Person>(
      context: context,
      builder: (context) => PersonChooser(
        people: people.values.toList(),
        onCreate: createPerson,
      ),
    );
    if (chosen == null || !mounted) {
      return;
    }
    setState(() {
      people[chosen.id] = chosen;
      for (var face in group.faces) {
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
        initial: nameOf(group.person),
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
    var unknownCount =
        shown.where((group) => group.key.startsWith(clusterGroupPrefix)).length +
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
            // What can be done to the faces standing selected. Only "Forget"
            // so far (issue #138), and only where something selected carries
            // a decision: a face nobody decided about has nothing to take
            // back, so it is not offered one.
            if (mayForget)
              PopupMenuButton<void Function()>(
                key: const Key("persons-selection-menu"),
                onSelected: (action) => action(),
                itemBuilder: (context) => [
                  PopupMenuItem<void Function()>(
                    key: const Key("persons-forget"),
                    value: () => forgetDecision(selected),
                    child: Text(_l10n.personsForgetEntry),
                  ),
                ],
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
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            OfflineBanner(onRetry: widget.albumState.reload),
            if (pending) _banner(_l10n.personsPendingNotice, "persons-pending"),
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
    );
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
        onTap: () => toggle(face),
        onLongPress: () => showPhoto(face),
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

  /// Shows the whole photograph a face was found in.
  ///
  /// A dialog and not the viewer route: the viewer is a page level of the
  /// *album*, so opening it would take this page off the stack and the buffer
  /// with it. What is wanted here is only "let me see the whole picture".
  void showPhoto(AlbumFace face) => showDialog<void>(
        context: context,
        builder: (context) => Dialog(
          key: const Key("persons-photo"),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(
                child: orientedImageThumbnail(
                  client,
                  "${widget.baseUrl}/${face.image.name}",
                  face.image,
                  width: 480,
                  height: 480,
                ),
              ),
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: Text(_l10n.close),
              ),
            ],
          ),
        ),
      );
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
    if (!face.detected) {
      return clipped();
    }
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

  const PersonChooser({
    super.key,
    required this.people,
    this.title,
    this.notice,
    this.onCreate,
  });

  @override
  State<StatefulWidget> createState() => _PersonChooserState();
}

class _PersonChooserState extends State<PersonChooser> {
  final TextEditingController _search = TextEditingController();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var needle = _search.text.trim().toLowerCase();
    var shown = [
      for (var person in widget.people)
        if (needle.isEmpty || person.name.toLowerCase().contains(needle)) person
    ];
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
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 8),
            if (shown.isEmpty && widget.people.isEmpty)
              Text(l10n.personsNobodyYet, key: const Key("persons-nobody")),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (var person in shown)
                    ListTile(
                      key: Key("persons-pick-${person.id}"),
                      title: Text(person.name),
                      onTap: () => Navigator.of(context).pop(person),
                    ),
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
