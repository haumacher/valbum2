/// The bursts of an album, see issues #257 and #314: **automatic groups**.
///
/// The server derives the bursts of an album where the space has the content
/// search on ([AlbumInfo.stacks]): photographs that look alike and were taken
/// a few minutes apart, the best shot first. The app treats each burst as an
/// [ImageGroup] the author never made, and exactly like one:
///
///  * The grid shows the burst as **one tile** — its best shot, where that
///    shot lies in the album — marked with a badge saying how many shots it
///    stands for ([stackView]). The badge is the only thing telling a burst
///    from a group; it is a mark, not a control.
///  * A tap opens the viewer on the best shot. Previous and next walk the
///    tiles of the album, past the burst's other shots; "down" opens the
///    alternatives of the burst (`group_view.dart`), where the viewer pages
///    through its shots, and "up" leads back. This is the navigation of a
///    group because it *is* one: [AlbumInitializer] builds a transient
///    [ImageGroup] for each burst ([stackGroups]), links it into the album's
///    chain in place of its shots and gives each shot its
///    [ImagePart.group]. The album's stored parts are never touched: the
///    group is in no list that is written back.
///  * Choosing the shot that represents it, in the alternatives view, makes
///    the burst a stored [ImageGroup] ([storeStack]): of exactly its shots,
///    in its order, where its tile stood. A group's shots are no top-level
///    photographs any more, and the server stacks none of them again.
///  * In the edit and the select mode the tile stands for all of its shots:
///    a tap, a long press, `Enter` and `Space` select or deselect them all,
///    and a drag moves them as one block in their stored order. A long press
///    into these modes leaves the bursts as they are.
///
/// Whether the album stacks at all is a view of this device, switched in the
/// album's menu and remembered ([SettingsStore.loadAlbumStacking]); switched
/// off, every shot is a tile of its own and the viewer walks every shot.
///
/// The grid and the viewer walk **one** set of bursts (issue #316): the
/// transient groups [AlbumInitializer] linked, built from what the album's
/// filters let through ([stackedParts]) — a shot the rating floor or the
/// label filter hides is no shot of the burst, and its best shown shot is the
/// tile. Every view of the album — the grid and every route of the viewer —
/// first says how this device stacks ([syncStacks]), which links the album
/// anew wherever that or its filters changed the bursts since; the grid then
/// folds exactly the linked groups ([albumStackView]).
library;

import 'album_edit.dart' show isVisiblePart;
import 'album_labels.dart' show labelFilterOf, partCarriesLabel;
import 'album_model.dart' show AlbumInitializer;
import 'resource.dart';

/// One burst: the shots of the album it names, the best one first.
class ShownStack {
  /// The shots of the burst, the tile's first.
  final List<ImagePart> members;

  ShownStack(this.members);

  /// The shot the tile of the burst shows: the best one.
  ImagePart get tile => members.first;

  /// The names of the shots, the tile's first.
  List<String> get names => [for (var member in members) member.name];
}

/// What the grid shows of an album's parts with its bursts stacked.
class StackView {
  /// The parts that get a tile, in the album's order: those of [parts] less
  /// the shots hidden under the tile of a burst.
  final List<AlbumPart> parts;

  /// The bursts shown as one tile, by that tile's photograph (identity).
  final Map<ImagePart, ShownStack> folded;

  /// The name of the tile every hidden shot is shown under.
  final Map<String, String> hiddenUnder;

  StackView(this.parts, this.folded, this.hiddenUnder);

  /// The view of [parts] without any stack.
  StackView.unstacked(List<AlbumPart> parts)
      : this(parts, Map.identity(), const {});

  /// The burst [part] stands for as one tile, `null` where it is no such
  /// tile.
  ShownStack? foldedOf(AlbumPart part) =>
      part is ImagePart ? folded[part] : null;
}

/// The bursts of [stacks] among the photographs of [parts]: only a top-level
/// photograph counts — what a filter hides is no shot of the burst here, a
/// member of a group is the author's already — a shot named twice, or in two
/// bursts, counts once, and a burst of fewer than two is none.
List<ShownStack> shownStacks(List<AlbumPart> parts, List<PhotoStack> stacks) {
  if (stacks.isEmpty) {
    return const [];
  }
  var byName = <String, ImagePart>{
    for (var part in parts)
      if (part is ImagePart) part.name: part,
  };
  var taken = Set<ImagePart>.identity();
  var result = <ShownStack>[];
  for (var stack in stacks) {
    var members = <ImagePart>[];
    for (var member in stack.members) {
      var image = byName[member.name];
      if (image != null && taken.add(image)) {
        members.add(image);
      }
    }
    if (members.length < 2) {
      taken.removeAll(members);
      continue;
    }
    result.add(ShownStack(members));
  }
  return result;
}

/// The view of [parts] with the given [stacks] of their album, see
/// [StackView] and [shownStacks].
StackView stackView(List<AlbumPart> parts, List<PhotoStack> stacks) {
  var found = shownStacks(parts, stacks);
  if (found.isEmpty) {
    return StackView.unstacked(parts);
  }
  var folded = Map<ImagePart, ShownStack>.identity();
  var hiddenUnder = <String, String>{};
  var hidden = Set<ImagePart>.identity();
  for (var shown in found) {
    folded[shown.tile] = shown;
    for (var image in shown.members.skip(1)) {
      hidden.add(image);
      hiddenUnder[image.name] = shown.tile.name;
    }
  }
  return StackView(
    [
      for (var part in parts)
        if (part is! ImagePart || !hidden.contains(part)) part,
    ],
    folded,
    hiddenUnder,
  );
}

/// Whether the album stacks its bursts on this device, as the view of it
/// last said ([syncStacks], [setAlbumStacking]).
final Expando<bool> _stackingOf = Expando("albumStacking");

/// The transient groups of the bursts, see [isStackGroup].
final Expando<bool> _stackGroups = Expando("stackGroup");

/// The transient groups the album was last linked with, see [linkedStacks].
final Expando<List<ImageGroup>> _linkedStacks = Expando("linkedStacks");

/// Whether [album] stacks its bursts: on until a view of it says otherwise.
bool albumStacking(AlbumInfo album) => _stackingOf[album] ?? true;

/// Says whether [album] stacks its bursts; answers whether that changed —
/// the album must then be initialized anew to page as it is shown.
bool setAlbumStacking(AlbumInfo album, bool stacking) {
  var changed = albumStacking(album) != stacking;
  _stackingOf[album] = stacking;
  return changed;
}

/// Whether [group] is the transient group of a burst, see [stackGroups],
/// rather than one the album stores.
bool isStackGroup(ImageGroup group) => _stackGroups[group] ?? false;

/// The top-level parts of [album] its bursts are drawn from: those its rating
/// floor ([AlbumInfo.minRating]) and its label filter ([labelFilterOf]) let
/// through — what the grid shows of it, and what the viewer pages through
/// (issue #316). Headings are kept; they are no shot of any burst.
List<AlbumPart> stackedParts(AlbumInfo album) {
  var label = labelFilterOf(album);
  return [
    for (var part in album.parts)
      if (isVisiblePart(part, album.minRating) &&
          (label == null || part is Heading || partCarriesLabel(part, label)))
        part,
  ];
}

/// The bursts [album] shows as it is filtered now, see [stackedParts]: none
/// where it does not stack ([albumStacking]) or is no ordinary album.
List<ShownStack> _wantedStacks(AlbumInfo album) =>
    album.kind != AlbumKind.album ||
            !albumStacking(album) ||
            album.stacks.isEmpty
        ? const []
        : shownStacks(stackedParts(album), album.stacks);

/// The transient groups of the bursts of [album], the best shot shown the
/// representative: one for each of [_wantedStacks] — none where the album
/// does not stack or is no ordinary album, and a filtered burst of the shots
/// the filters let through alone.
///
/// Each is in no list of the album, so nothing writes it back; the
/// [AlbumInitializer] links it in place of its shots, and the album
/// remembers them as its [linkedStacks].
List<ImageGroup> stackGroups(AlbumInfo album) {
  var result = [
    for (var stack in _wantedStacks(album))
      () {
        var group = ImageGroup(images: [...stack.members], representative: 0);
        _stackGroups[group] = true;
        return group;
      }(),
  ];
  _linkedStacks[album] = result;
  return result;
}

/// The transient groups [album] was last linked with ([stackGroups]): the
/// bursts the viewer walks, and the grid folds ([albumStackView]).
List<ImageGroup> linkedStacks(AlbumInfo album) =>
    _linkedStacks[album] ?? const [];

/// Makes the bursts the viewer walks in [album] those it shows now: says
/// whether this device [stacking] stacks them (where given) and links the
/// album anew ([AlbumInitializer]) where that, or a filter, or a rating, has
/// changed which shots form which burst since it was linked. Answers whether
/// it was linked anew.
///
/// Called by every view of the album before it reads the links — the grid
/// as it is built, the viewer and the alternatives on every route — so that
/// a viewer opened by its address pages as the grid would show, and follows
/// the filters as they are set at that moment (issue #316).
bool syncStacks(AlbumInfo album, {bool? stacking}) {
  if (stacking != null) {
    setAlbumStacking(album, stacking);
  }
  var wanted = _wantedStacks(album);
  var linked = _linkedStacks[album];
  if (linked != null && _sameStacks(wanted, linked)) {
    return false;
  }
  if (linked == null && wanted.isEmpty) {
    // Never linked with a burst, and none to link: nothing walks one.
    return false;
  }
  AlbumInitializer().init(album);
  return true;
}

bool _sameStacks(List<ShownStack> wanted, List<ImageGroup> linked) {
  if (wanted.length != linked.length) {
    return false;
  }
  for (var i = 0; i < wanted.length; i++) {
    var members = wanted[i].members;
    var images = linked[i].images;
    if (members.length != images.length) {
      return false;
    }
    for (var j = 0; j < members.length; j++) {
      if (!identical(members[j], images[j])) {
        return false;
      }
    }
  }
  return true;
}

/// What the grid shows of [shown], the parts of [album] it draws, with the
/// bursts the album is linked with ([linkedStacks]) folded: the very groups
/// the viewer walks, so that the grid and the viewer cannot disagree.
StackView albumStackView(List<AlbumPart> shown, AlbumInfo album) {
  var linked = linkedStacks(album);
  if (linked.isEmpty) {
    return StackView.unstacked(shown);
  }
  return stackView(shown, [
    for (var group in linked)
      PhotoStack(members: [
        for (var image in group.images) StackMember(name: image.name),
      ]),
  ]);
}

/// Makes the transient group [stack] of [album] a stored [ImageGroup]
/// represented by [representative] (issue #314): exactly the burst's shots,
/// in the burst's order, standing where the burst's tile stood; its shots
/// leave the album's top-level parts. Answers the new group, `null` where
/// [stack] is no burst of [album] or [representative] none of its shots.
///
/// Under a filter the burst is the shots it showed (issue #316): those the
/// alternatives view offered, the hidden ones staying top-level photographs.
///
/// The caller initializes the album anew.
ImageGroup? storeStack(
  AlbumInfo album,
  ImageGroup stack,
  ImagePart representative,
) {
  var index = stack.images.indexWhere((m) => identical(m, representative));
  if (!isStackGroup(stack) || index < 0) {
    return null;
  }
  var members = Set<AlbumPart>.identity()..addAll(stack.images);
  var tile = stack.images[stack.representative];
  if (!album.parts.any((part) => identical(part, tile))) {
    return null;
  }
  var group = ImageGroup(images: [...stack.images], representative: index);
  album.parts = [
    for (var part in album.parts)
      if (identical(part, tile)) group else if (!members.contains(part)) part,
  ];
  return group;
}
