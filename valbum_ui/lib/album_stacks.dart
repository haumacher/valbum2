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
library;

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

/// Whether the album stacks its bursts on this device, as its view last
/// said ([setAlbumStacking]): kept beside the album object like its label
/// filter, so that the viewer opened from it pages as the grid shows.
final Expando<bool> _stackingOf = Expando("albumStacking");

/// The transient groups of the bursts, see [isStackGroup].
final Expando<bool> _stackGroups = Expando("stackGroup");

/// Whether [album] stacks its bursts: on until its view says otherwise.
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

/// The transient groups of the bursts of [album], the best shot the
/// representative: none where the album does not stack ([albumStacking]) or
/// is no ordinary album.
///
/// Each is in no list of the album, so nothing writes it back; the
/// [AlbumInitializer] links it in place of its shots.
List<ImageGroup> stackGroups(AlbumInfo album) {
  if (album.kind != AlbumKind.album || !albumStacking(album)) {
    return const [];
  }
  return [
    for (var stack in shownStacks(album.parts, album.stacks))
      () {
        var group = ImageGroup(images: [...stack.members], representative: 0);
        _stackGroups[group] = true;
        return group;
      }(),
  ];
}

/// Makes the transient group [stack] of [album] a stored [ImageGroup]
/// represented by [representative] (issue #314): exactly the burst's shots,
/// in the burst's order, standing where the burst's tile stood; its shots
/// leave the album's top-level parts. Answers the new group, `null` where
/// [stack] is no burst of [album] or [representative] none of its shots.
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
