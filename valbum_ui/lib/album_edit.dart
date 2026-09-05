/// Widget-free editing logic of the album view.
///
/// This library holds the model operations behind the tile editor (issue #18)
/// and the rating filter (issue #17): the orientation algebra, the rating
/// helpers, the visibility filter and the selection arithmetic. Nothing here
/// depends on Flutter, so all of it is unit-testable without a widget tree.
library;

import 'album_layout.dart' show ToImage;
import 'resource.dart';

/// A rigid transformation of the image plane — the dihedral group `D4`.
///
/// Every [Orientation] denotes such a transformation: the one that has to be
/// applied to the raw pixel data to obtain the image as it should be seen.
/// Writing `r` for a quarter turn and `m` for the mirror at the vertical axis,
/// every element of the group is `r^k ∘ m^b` with `k ∈ 0..3` and `b ∈ 0..1`
/// (the mirror is applied first, the rotation afterwards).
///
/// Turning the eight orientations into group elements makes the whole
/// orientation algebra one multiplication:
///
/// ```
/// concat(self, tx) = self ∘ tx
/// ```
///
/// which is exactly what the retired Java `Orientations.rotL/rotR/flipH/flipV`
/// tabulate — see the derivation on [PlaneTransform.of].
class PlaneTransform {
  /// Number of quarter turns, counter-clockwise, applied after the mirror.
  final int quarterTurns;

  /// Whether the plane is mirrored at the vertical axis before the rotation.
  final bool mirrored;

  const PlaneTransform(this.quarterTurns, this.mirrored);

  /// The transformation denoted by the given [Orientation].
  ///
  /// The table is derived from the JPEG orientation tag, see
  /// "http://sylvana.net/jpegcrop/exif_orientation.html" and the doc of
  /// [Orientation]: an orientation says where the 0th row and the 0th column
  /// of the raw data belong in the display. The names of the enum constants
  /// spell the transformation out — `rotL` is a quarter turn, `rotLFlipH` a
  /// quarter turn of the horizontally mirrored plane, and so on:
  ///
  /// | Orientation (code)  | k | mirrored |
  /// |---------------------|---|----------|
  /// | `identity` (1)      | 0 | no       |
  /// | `flipH` (2)         | 0 | yes      |
  /// | `rot180` (3)        | 2 | no       |
  /// | `flipV` (4)         | 2 | yes      |
  /// | `rotLFlipV` (5)     | 3 | yes      |
  /// | `rotL` (6)          | 1 | no       |
  /// | `rotLFlipH` (7)     | 1 | yes      |
  /// | `rotR` (8)          | 3 | no       |
  ///
  /// Two facts pin this table down. The width and height of an image are
  /// swapped exactly for the codes `>= 5` (see `Orientations.widthInt`), so
  /// those four are the odd quarter turns. And the four operation tables of
  /// the Java `Orientations` are, under this assignment, *exactly* the right
  /// multiplication with the group element of the operation's own name:
  /// `rotL(x) = x ∘ rotL`, `rotR(x) = x ∘ rotR`, `flipH(x) = x ∘ flipH`,
  /// `flipV(x) = x ∘ flipV` — for all eight arguments each. No other
  /// assignment of the four mirrored codes does that;
  /// `album_orientation_test.dart` replays all four of them against this
  /// implementation.
  static PlaneTransform of(Orientation orientation) {
    switch (orientation) {
      case Orientation.identity:
        return const PlaneTransform(0, false);
      case Orientation.flipH:
        return const PlaneTransform(0, true);
      case Orientation.rot180:
        return const PlaneTransform(2, false);
      case Orientation.flipV:
        return const PlaneTransform(2, true);
      case Orientation.rotLFlipV:
        return const PlaneTransform(3, true);
      case Orientation.rotL:
        return const PlaneTransform(1, false);
      case Orientation.rotLFlipH:
        return const PlaneTransform(1, true);
      case Orientation.rotR:
        return const PlaneTransform(3, false);
    }
  }

  /// The [Orientation] denoting this transformation.
  Orientation get orientation {
    switch (quarterTurns % 4) {
      case 0:
        return mirrored ? Orientation.flipH : Orientation.identity;
      case 1:
        return mirrored ? Orientation.rotLFlipH : Orientation.rotL;
      case 2:
        return mirrored ? Orientation.flipV : Orientation.rot180;
      default:
        return mirrored ? Orientation.rotLFlipV : Orientation.rotR;
    }
  }

  /// This transformation followed by nothing — the neutral element.
  static const PlaneTransform identity = PlaneTransform(0, false);

  /// The composition `this ∘ other`: [other] is applied first.
  ///
  /// `(r^a m^b) ∘ (r^c m^d) = r^(a ± c) m^(b+d)`, because `m ∘ r = r⁻¹ ∘ m`.
  PlaneTransform concat(PlaneTransform other) => PlaneTransform(
        (quarterTurns + (mirrored ? -other.quarterTurns : other.quarterTurns)) %
            4,
        mirrored != other.mirrored,
      );

  /// The transformation undoing this one.
  PlaneTransform get inverse => mirrored
      ? this // (r^k m)² = identity, every mirrored element is an involution.
      : PlaneTransform((4 - quarterTurns % 4) % 4, false);

  /// Whether this transformation swaps width and height.
  bool get swapsDimensions => quarterTurns % 2 == 1;

  @override
  bool operator ==(Object other) =>
      other is PlaneTransform &&
      other.quarterTurns % 4 == quarterTurns % 4 &&
      other.mirrored == mirrored;

  @override
  int get hashCode => Object.hash(quarterTurns % 4, mirrored);

  @override
  String toString() => "PlaneTransform($quarterTurns, mirrored: $mirrored)";
}

/// The operations of the tile editor on an [Orientation].
///
/// The Dart [ToImage]'s neighbour `Orientations` in `album_layout.dart` only
/// carries `rotL`; these are the remaining ones of the retired Java
/// `Orientations`, derived from the group structure instead of tabulated (see
/// [PlaneTransform.of]).
class OrientationOps {
  /// Combines the given orientation with the given transformation.
  static Orientation concat(Orientation self, Orientation tx) =>
      PlaneTransform.of(self).concat(PlaneTransform.of(tx)).orientation;

  /// Rotates the given orientation to the left.
  static Orientation rotL(Orientation self) => concat(self, Orientation.rotL);

  /// Rotates the given orientation to the right.
  static Orientation rotR(Orientation self) => concat(self, Orientation.rotR);

  /// Horizontally flips the given orientation.
  static Orientation flipH(Orientation self) => concat(self, Orientation.flipH);

  /// Vertically flips the given orientation.
  static Orientation flipV(Orientation self) => concat(self, Orientation.flipV);

  /// The transformation that turns an image displayed in the orientation
  /// [from] into the same image displayed in the orientation [to].
  ///
  /// The server bakes the orientation it read from the image file into the
  /// thumbnail it serves. While the tile editor rotates an image, the
  /// thumbnail on the server is therefore stale by exactly this delta, and the
  /// tile has to apply it itself.
  static PlaneTransform delta(Orientation from, Orientation to) =>
      PlaneTransform.of(to).concat(PlaneTransform.of(from).inverse);
}

/// The rating of the image representing the given album part.
///
/// For an [ImageGroup] this is the rating of its representative, mirroring the
/// server-side `ToImage`.
int ratingOf(AbstractImage image) => ToImage.toImage(image).rating;

/// The lowest [AlbumInfo.minRating] the user can filter down to.
///
/// Images rated `-2` ("trash") stay hidden in the album view; the retired GWT
/// client had the same floor (`if (minRating > -1) minRating--`).
const int minMinRating = -1;

/// The highest [AlbumInfo.minRating] the user can filter up to.
const int maxMinRating = 2;

/// The threshold showing one more rating level, the `+` key of the GWT client.
int showMoreRating(int minRating) =>
    minRating > minMinRating ? minRating - 1 : minRating;

/// The threshold showing one rating level less, the `-` key of the GWT client.
int showLessRating(int minRating) =>
    minRating < maxMinRating ? minRating + 1 : minRating;

/// Whether the given part is shown in an album filtered to [minRating].
///
/// Headings are never filtered out.
bool isVisiblePart(AlbumPart part, int minRating) =>
    part is! AbstractImage || ratingOf(part) >= minRating;

/// The parts of the album that its [AlbumInfo.minRating] lets through.
List<AlbumPart> visibleParts(AlbumInfo album) =>
    album.parts.where((part) => isVisiblePart(part, album.minRating)).toList();

/// The rating a tile has after its rating button for [value] was pressed.
///
/// Pressing the active button resets the rating, as in the GWT client's
/// `makeChoice`.
int toggleRating(int current, int value) => current == value ? 0 : value;

/// Whether the rating button for [value] is the active one for [rating].
///
/// The outermost buttons stay active for ratings beyond their own value, as in
/// the GWT client (`rating >= 2`, `rating <= -2`).
bool isActiveRating(int rating, int value) {
  if (value >= maxMinRating) return rating >= value;
  if (value <= -2) return rating <= value;
  return rating == value;
}

/// Inserts a heading with the given text before [part].
///
/// Returns the index the heading was inserted at, or `-1` if [part] is not a
/// part of the album.
int insertHeadingBefore(AlbumInfo album, AlbumPart part, String text) {
  var index = album.parts.indexOf(part);
  if (index < 0) {
    return -1;
  }
  album.parts.insert(index, Heading(text: text));
  return index;
}

/// The images between [from] and [to] in the album's part order, excluding
/// [from] and including [to].
///
/// This is the range a shift-click adds to (or removes from) the selection;
/// the GWT client walked the part list in either direction the same way.
List<AbstractImage> imageRange(
  List<AlbumPart> parts,
  AlbumPart from,
  AlbumPart to,
) {
  var start = parts.indexOf(from);
  var stop = parts.indexOf(to);
  if (start < 0 || stop < 0) {
    return const [];
  }
  var delta = start < stop ? 1 : -1;
  var result = <AbstractImage>[];
  for (var index = start + delta; index != stop + delta; index += delta) {
    var part = parts[index];
    if (part is AbstractImage) {
      result.add(part);
    }
  }
  return result;
}
