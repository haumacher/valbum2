/// The bursts of an album as its grid shows them, see issue #257.
///
/// The server derives the bursts of an album where the space has the content
/// search on ([AlbumInfo.stacks]): photographs that look alike and were taken
/// a few minutes apart, the best shot first. The grid shows each burst as
/// **one tile** — its best shot, where that shot lies in the album, with a
/// badge saying how many shots it stands for — and leaves every other tile as
/// it is. A tap on such a tile opens the burst **in place**: its shots get
/// their tiles back, the first of them carrying the badge that folds them
/// together again. In place rather than on a page of its own: the album keeps
/// its scroll offset, the viewer pages through the album as ever, and there
/// is no further level to come back from.
///
/// Presentation only, and nothing is hidden by it but tiles: the viewer, the
/// downloads, the counts, the index picture and the keyboard's walk over the
/// tiles see the album as it is. The edit mode — which arranges the album —
/// shows every photograph singly, and a tile standing for a burst selects all
/// of its shots, so no photograph becomes unreachable. Whether the album
/// stacks at all is a view of this device, switched in the album's menu and
/// remembered ([SettingsStore.loadAlbumStacking]).
library;

import 'resource.dart';

/// One burst as the grid shows it.
class ShownStack {
  /// The shots of the burst the album shows, the tile's first.
  final List<ImagePart> members;

  ShownStack(this.members);

  /// The shot the tile of the burst shows: the best one shown.
  ImagePart get tile => members.first;

  /// The names of the shots, the tile's first.
  List<String> get names => [for (var member in members) member.name];
}

/// What the grid shows of an album's parts with its bursts stacked.
class StackView {
  /// The parts that get a tile, in the album's order: those of [parts] less
  /// the shots hidden under the tile of a folded burst.
  final List<AlbumPart> parts;

  /// The bursts shown as one tile, by that tile's photograph (identity).
  final Map<ImagePart, ShownStack> folded;

  /// The bursts opened in place, by their first shot (identity).
  final Map<ImagePart, ShownStack> opened;

  /// The name of the tile every hidden shot is shown under.
  final Map<String, String> hiddenUnder;

  StackView(this.parts, this.folded, this.opened, this.hiddenUnder);

  /// The view of [parts] without any stack.
  StackView.unstacked(List<AlbumPart> parts)
      : this(parts, Map.identity(), Map.identity(), const {});

  /// The burst [part] stands for as one tile, `null` where it is no such
  /// tile.
  ShownStack? foldedOf(AlbumPart part) =>
      part is ImagePart ? folded[part] : null;

  /// The burst [part] is the first shot of while it is opened, `null` where
  /// it is none.
  ShownStack? openedOf(AlbumPart part) =>
      part is ImagePart ? opened[part] : null;
}

/// The view of [parts] with the given [stacks] of their album, see
/// [StackView].
///
/// Only a photograph among [parts] counts — what a filter hides is no shot
/// of the burst here — and a burst of which fewer than two are shown is no
/// burst. A burst is opened where [isOpened] says so of the name of its
/// tile, and where one of its shots is named by [keepShown] (the photograph
/// "Show in album" opened the album for, issue #289): that must not hide
/// under another tile.
StackView stackView(
  List<AlbumPart> parts,
  List<PhotoStack> stacks, {
  required bool Function(String tile) isOpened,
  Set<String> keepShown = const {},
}) {
  if (stacks.isEmpty) {
    return StackView.unstacked(parts);
  }
  var byName = <String, ImagePart>{
    for (var part in parts)
      if (part is ImagePart) part.name: part,
  };
  var folded = Map<ImagePart, ShownStack>.identity();
  var opened = Map<ImagePart, ShownStack>.identity();
  var hiddenUnder = <String, String>{};
  var hidden = Set<ImagePart>.identity();
  for (var stack in stacks) {
    var members = [
      for (var member in stack.members)
        if (byName[member.name] case var image?) image,
    ];
    // A shot named twice, or in two bursts, is shown once.
    members = [
      for (var (index, image) in members.indexed)
        if (!hidden.contains(image) &&
            !folded.containsKey(image) &&
            !opened.containsKey(image) &&
            members.indexOf(image) == index)
          image,
    ];
    if (members.length < 2) {
      continue;
    }
    var shown = ShownStack(members);
    if (isOpened(shown.tile.name) ||
        members.any((image) => keepShown.contains(image.name))) {
      opened[shown.tile] = shown;
      continue;
    }
    folded[shown.tile] = shown;
    for (var image in members.skip(1)) {
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
    opened,
    hiddenUnder,
  );
}
