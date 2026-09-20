/// Widget-free editing logic of the album view.
///
/// This library holds the model operations behind the tile editor (issue #18)
/// and the rating filter (issue #17): the orientation algebra, the rating
/// helpers, the visibility filter and the selection arithmetic. Nothing here
/// depends on Flutter, so all of it is unit-testable without a widget tree.
library;

import 'album_layout.dart' show ToImage;
import 'album_model.dart';
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
  /// The group's division: `to ∘ from⁻¹`. Nothing in the app applies a delta
  /// to a rendition any more — a rendition is upright by the file and the
  /// whole [ImagePart.orientation] is applied to it, see issue #106 — but the
  /// operation is part of the algebra and is pinned by
  /// `album_orientation_test.dart`.
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

/// The privacy level of an image that everyone may see, share links included.
///
/// The levels of issue #46: `0` public, `1` members, `2` private. The server
/// filters what it sends by the caller's clearance; the app never filters by
/// privacy itself, it only shows and edits the level.
const int privacyPublic = 0;

/// The privacy level of an image only signed-in members of the album may see.
const int privacyMembers = 1;

/// The privacy level of an image only the owner of the space may see.
const int privacyPrivate = 2;

/// The three [privacyPublic], [privacyMembers] and [privacyPrivate] levels,
/// in the order the tile control cycles through them.
const List<int> privacyLevels = [privacyPublic, privacyMembers, privacyPrivate];

/// The name every view of the app calls the given privacy level by.
///
/// An unknown level (a server newer than this app) reads as [privacyPrivate]:
/// a level the app does not know is never claimed to be public.
String privacyName(int level) => switch (level) {
      privacyPublic => "Public",
      privacyMembers => "Members",
      _ => "Private",
    };

/// The privacy level of the image representing the given album part.
///
/// For an [ImageGroup] this is the level of its representative — a group is
/// one thing to a viewer, and [setPrivacyOf] keeps its members in step.
int privacyOf(AlbumPart part) =>
    part is AbstractImage ? ToImage.toImage(part).privacy : privacyPublic;

/// The level the tile control shows after one tap on the level [level].
///
/// Public, members, private and around again, see [privacyLevels].
int nextPrivacy(int level) {
  var index = privacyLevels.indexOf(level);
  return privacyLevels[index < 0 ? 0 : (index + 1) % privacyLevels.length];
}

/// Sets the privacy level of the given album part.
///
/// Every image of an [ImageGroup] is set: a group is one thing to a viewer, so
/// a member left behind at a lower level would leak the group the moment the
/// representative is hidden. A [Heading] has no level and is left alone.
void setPrivacyOf(AlbumPart part, int level) {
  if (part is ImageGroup) {
    for (var image in part.images) {
      image.privacy = level;
    }
  } else if (part is ImagePart) {
    part.privacy = level;
  }
}

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

/// Inserts a heading with the given text before [part], at its stored index.
///
/// The plain form of [insertHeadingBeforeDisplayed]: with no display order at
/// hand the stored order is the displayed one.
///
/// Returns the index the heading was inserted at, or `-1` if [part] is not a
/// part of the album.
int insertHeadingBefore(AlbumInfo album, AlbumPart part, String text) =>
    insertHeadingBeforeDisplayed(album, part, const [], text);

/// Inserts a heading with the given text before the *displayed* [part].
///
/// This is the model side of the tile action "insert a heading before this
/// tile's part" (issue #71). The row layout buffers landscape images to pair
/// one with a portrait image into a double row and re-flows the rest
/// afterwards (see `album_layout.dart`), so within a block of rows the
/// displayed order is a permutation of the stored order: a part stored behind
/// [part] may well be shown before it, and one stored before it behind it.
///
/// A heading splits the stored sequence and the layout re-flows each side on
/// its own, so *every* part stored before the heading is displayed before it.
/// Inserting at the stored index of [part] therefore pulled a part that was
/// displayed *after* the tile in front of the new heading — the tile the
/// heading was meant to introduce ended up behind a photo the heading was
/// supposed to come after.
///
/// The anchor is read off the display instead: the heading lands before every
/// part that is displayed at or after [part], at the smallest stored index
/// among `{part} ∪ {parts displayed after part}`. The intent of "before this
/// tile" is stated relative to what follows the cursor, so what follows the
/// cursor follows the heading. Parts displayed *before* the tile but stored
/// behind that index are re-flowed behind the heading; in a sequence model one
/// of the two has to give, and this is the smaller surprise.
///
/// Two insertions before the same tile stack in the order they were made: the
/// first heading is displayed before the tile and is not part of the
/// displayed-after set, so the second one lands between the first heading and
/// the parts it introduces.
///
/// [displayOrder] is the parts in the order their tiles are shown, headings
/// included, as `AlbumView`'s display order provides it (its parts are
/// compared by identity). It need not cover the whole album: parts the rating
/// filter hides are simply not displayed after [part] and stay where they are.
/// If [displayOrder] does not hold [part], or holds a part that does not
/// belong to the album, the stored index of [part] is the anchor — the plain
/// behaviour, never a refusal.
///
/// Returns the index the heading was inserted at, or `-1` if [part] is not a
/// part of the album.
int insertHeadingBeforeDisplayed(
  AlbumInfo album,
  AlbumPart part,
  List<AlbumPart> displayOrder,
  String text,
) {
  var parts = album.parts;
  var stored = Map<AlbumPart, int>.identity();
  for (var i = 0; i < parts.length; i++) {
    stored[parts[i]] = i;
  }
  var index = stored[part];
  if (index == null) {
    return -1;
  }

  var at = _displayedHeadingAnchor(stored, part, index, displayOrder);
  parts.insert(at, Heading(text: text));
  return at;
}

/// The stored index a heading inserted before the displayed [part] lands at,
/// see [insertHeadingBeforeDisplayed].
int _displayedHeadingAnchor(
  Map<AlbumPart, int> stored,
  AlbumPart part,
  int index,
  List<AlbumPart> displayOrder,
) {
  var shown = displayOrder.indexWhere((p) => identical(p, part));
  if (shown < 0) {
    // The display order does not know this part: the stored order is all
    // there is to go by.
    return index;
  }

  var anchor = index;
  for (var i = shown + 1; i < displayOrder.length; i++) {
    var behind = stored[displayOrder[i]];
    if (behind == null) {
      // The display order and the album disagree; fall back to the plain
      // stored index rather than anchoring on a guess.
      return index;
    }
    if (behind < anchor) {
      anchor = behind;
    }
  }
  return anchor;
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

/// A rating filter letting every image through.
///
/// The "alternatives" view of an [ImageGroup] shows all of its images, no
/// matter how they are rated — the retired GWT `GroupDisplay` inherited
/// `getMinRating() == Integer.MIN_VALUE` for the same reason.
///
/// Spelled out as a literal on purpose: `-1 << 31` is this value on the VM,
/// but dart2js masks a shift to an unsigned 32-bit result, so in the browser
/// it came out as `2147483648` — a threshold no image reaches, which took the
/// previous/next images away from the detail view of a group.
const int noMinRating = -2147483648;

/// Groups the selected parts of the album into one [ImageGroup].
///
/// The new group takes the position of [representative] in [AlbumInfo.parts];
/// every other selected part is removed. Its images are all images of the
/// selected parts — a selected [ImageGroup] contributes its members, so
/// grouping a group with an image flattens it, as the GWT client did — sorted
/// by [ImagePart.date] ascending, and [ImageGroup.representative] is the index
/// of the image representing [representative].
///
/// The transient links of the album are rebuilt afterwards, so the group is
/// one link in the album's chain and its members form a chain of their own
/// (see [AlbumInitializer]).
///
/// Returns the group created, or `null` if fewer than two parts were selected
/// or [representative] is not among them.
ImageGroup? groupSelection(
  AlbumInfo album,
  Set<AlbumPart> selection,
  AbstractImage representative,
) {
  if (selection.length < 2 || !selection.contains(representative)) {
    return null;
  }

  var images = <ImagePart>[];
  for (var part in album.parts) {
    if (!selection.contains(part)) {
      continue;
    }
    if (part is ImageGroup) {
      images.addAll(part.images);
    } else if (part is ImagePart) {
      images.add(part);
    }
  }
  if (images.length < 2) {
    return null;
  }

  // A stable sort by date: images taken in the same second keep the order
  // they had in the album.
  var order = {for (var i = 0; i < images.length; i++) images[i]: i};
  images.sort((a, b) {
    var byDate = a.date.compareTo(b.date);
    return byDate != 0 ? byDate : order[a]!.compareTo(order[b]!);
  });

  var representingImage = ToImage.toImage(representative);
  var group = ImageGroup(
    images: images,
    representative: images.indexOf(representingImage).clamp(0, images.length),
  );

  var parts = <AlbumPart>[];
  for (var part in album.parts) {
    if (identical(part, representative)) {
      parts.add(group);
    } else if (!selection.contains(part)) {
      parts.add(part);
    }
  }
  album.parts = parts;
  AlbumInitializer().init(album);
  return group;
}

/// Moves [moved] behind [displayedPredecessor] in [AlbumInfo.parts].
///
/// This is the model side of the drag and drop reordering of issue #37. The
/// album view lays its images out in rows, and the row layout may show a
/// portrait image *before* the landscape image it follows in the stored order
/// (see `album_layout.dart`). The insert cursor of the drop gesture therefore
/// names the part it is displayed behind, not a stored index: the caller —
/// the only one knowing the layout — resolves the cursor to the part shown
/// directly before it and hands it in as [displayedPredecessor], `null` for
/// the very beginning of the album.
///
/// The stored order follows: [moved] is taken out and put back directly
/// behind [displayedPredecessor], wherever that part sits in
/// [AlbumInfo.parts]. Nothing happens if [moved] is [displayedPredecessor]
/// itself, if either part does not belong to the album, or if [moved] already
/// sits at that very position.
///
/// The transient links are rebuilt afterwards, so the moved part is in its new
/// place of the album's chain (see [AlbumInitializer]).
///
/// Returns whether the stored order changed.
bool movePart(
  AlbumInfo album,
  AlbumPart moved,
  AlbumPart? displayedPredecessor,
) =>
    moveParts(album, [moved], displayedPredecessor);

/// Moves all of [moved] behind [displayedPredecessor] in [AlbumInfo.parts].
///
/// The many-parts form of [movePart] (issue #41): a drag that picks up a tile
/// of the current selection carries the whole selection, and the selection is
/// put down as one block. The rule for the place is the one of [movePart]:
/// the block lands directly behind [displayedPredecessor], the part *shown*
/// before the insert cursor, `null` for the very beginning of the album.
///
/// The parts of the block keep the order they are *stored* in, not the order
/// they were selected or displayed in, and not the part the drag was started
/// from first: a selection put down elsewhere reads as it read before.
///
/// If [displayedPredecessor] is itself one of [moved], the block cannot be
/// inserted behind it without being dropped into itself. The insertion point
/// is then the nearest part *stored* before it that is not moved along (the
/// very beginning of the album if there is none): the cursor names the place
/// after everything that stays in front of it, and that is the last part
/// which does. So a cursor inside a block already standing together is a
/// move to where the block already is, and nothing changes.
///
/// Nothing happens if one of [moved] or [displayedPredecessor] does not
/// belong to the album, if [moved] is empty, or if the resulting order is the
/// one the album already has. The transient links are rebuilt afterwards (see
/// [AlbumInitializer]).
///
/// Returns whether the stored order changed.
bool moveParts(
  AlbumInfo album,
  Iterable<AlbumPart> moved,
  AlbumPart? displayedPredecessor,
) {
  // The album parts are compared by identity: two headings of the same text
  // are two parts, and an album may well hold them both.
  var block = Set<AlbumPart>.identity()..addAll(moved);
  if (block.isEmpty) {
    return false;
  }

  var parts = album.parts;
  var belongs = Set<AlbumPart>.identity()..addAll(parts);
  if (!block.every(belongs.contains)) {
    return false;
  }
  if (displayedPredecessor != null && !belongs.contains(displayedPredecessor)) {
    return false;
  }

  var predecessor = displayedPredecessor;
  if (predecessor != null && block.contains(predecessor)) {
    // The cursor stands inside the block, see above: the nearest part before
    // it that stays where it is takes its place.
    predecessor = null;
    for (var i =
            parts.indexWhere((p) => identical(p, displayedPredecessor)) - 1;
        i >= 0;
        i--) {
      if (!block.contains(parts[i])) {
        predecessor = parts[i];
        break;
      }
    }
  }

  var carried = [
    for (var part in parts)
      if (block.contains(part)) part,
  ];
  var staying = [
    for (var part in parts)
      if (!block.contains(part)) part,
  ];
  var to = predecessor == null
      ? 0
      : staying.indexWhere((p) => identical(p, predecessor)) + 1;

  var result = [
    ...staying.sublist(0, to),
    ...carried,
    ...staying.sublist(to),
  ];
  if (_sameOrder(result, parts)) {
    return false;
  }

  album.parts = result;
  AlbumInitializer().init(album);
  return true;
}

/// The date the given album part is sorted by, `0` if it has none.
///
/// An [ImagePart] — a photo or a video — is sorted by its own
/// [ImagePart.date], the date the sidecar holds for it (never the EXIF data
/// of the original, which the app does not touch). An [ImageGroup] is sorted
/// by the *earliest* date of its images, skipping the undated ones: a group
/// is one thing in the album, and it belongs where its first photo belongs.
/// A [Heading] has no date and answers `0` — [sortSectionsByDate] never asks
/// it, headings stay where they are.
int dateOf(AlbumPart part) {
  if (part is ImagePart) {
    return part.date;
  }
  if (part is ImageGroup) {
    var earliest = 0;
    for (var image in part.images) {
      var date = image.date;
      if (date != 0 && (earliest == 0 || date < earliest)) {
        earliest = date;
      }
    }
    return earliest;
  }
  return 0;
}

/// The given parts ordered by [dateOf], ascending.
///
/// The order is stable in both senses the album needs (issue #76): parts of
/// the same date keep the order they were in, and a part without a date
/// (`0` — an image the server could not read a date from) keeps its relative
/// position at the *end*, where an undated part is the least in the way.
/// Dart's [List.sort] is not stable, so the original position is the
/// tie-breaker.
List<T> sortedByDate<T extends AlbumPart>(List<T> parts) {
  var position = Map<T, int>.identity();
  var dated = <T>[];
  var undated = <T>[];
  for (var i = 0; i < parts.length; i++) {
    var part = parts[i];
    position[part] = i;
    (dateOf(part) == 0 ? undated : dated).add(part);
  }
  dated.sort((left, right) {
    var byDate = dateOf(left).compareTo(dateOf(right));
    return byDate != 0 ? byDate : position[left]!.compareTo(position[right]!);
  });
  return [...dated, ...undated];
}

/// Sorts the images of the given group by date, see [sortedByDate].
///
/// [ImageGroup.representative] is an index into [ImageGroup.images] and is
/// carried along, so the group keeps showing the image it showed.
///
/// Returns whether the order of the images changed.
bool sortGroupByDate(ImageGroup group) {
  var images = group.images;
  var sorted = sortedByDate(images);
  if (_sameOrder(sorted, images)) {
    return false;
  }

  var index = group.representative;
  var representing = index >= 0 && index < images.length ? images[index] : null;
  group.images = sorted;
  if (representing != null) {
    group.representative =
        sorted.indexWhere((image) => identical(image, representing));
  }
  return true;
}

/// Sorts the parts of the album by date, section by section (issue #76).
///
/// An album grows by uploads from several devices and by moves, and every
/// part is inserted by date when it arrives — but a stored order is never
/// resorted behind the author's back (issue #72). This is the one action that
/// says: order this album by date now.
///
/// The headings partition the album into sections, and each section is sorted
/// on its own while every [Heading] stays exactly where it is: an album that
/// was given chapters keeps them, and an album without headings is sorted as
/// a whole. A group sorts by the earliest date of its images and its images
/// are sorted inside it (see [sortGroupByDate]); an undated part keeps its
/// relative position at the end of its section, and equal dates keep their
/// order (see [sortedByDate]).
///
/// The transient links are rebuilt afterwards, so the parts are chained in
/// their new order (see [AlbumInitializer]).
///
/// Returns whether anything changed — the caller marks the album dirty only
/// then, so an album that is already in order is never written back.
bool sortSectionsByDate(AlbumInfo album) {
  var changed = false;
  var result = <AlbumPart>[];
  var section = <AlbumPart>[];

  void flushSection() {
    if (section.isEmpty) {
      return;
    }
    var sorted = sortedByDate(section);
    if (!_sameOrder(sorted, section)) {
      changed = true;
    }
    result.addAll(sorted);
    section = [];
  }

  for (var part in album.parts) {
    if (part is Heading) {
      flushSection();
      result.add(part);
      continue;
    }
    if (part is ImageGroup && sortGroupByDate(part)) {
      changed = true;
    }
    section.add(part);
  }
  flushSection();

  if (!changed) {
    return false;
  }
  album.parts = result;
  AlbumInitializer().init(album);
  return true;
}

/// The images of the album the given selection stands for, in stored order.
///
/// A selected [ImageGroup] stands for all of its images — a group is one
/// thing to a viewer, and an action on it is an action on its members. A
/// selected [ImagePart] stands for itself, whether it sits in the album or
/// inside a group; a selected [Heading] is no image and contributes nothing.
///
/// The parts are compared by identity, as everywhere in the album model.
List<ImagePart> selectedImages(AlbumInfo album, Set<AlbumPart> selection) {
  var chosen = Set<AlbumPart>.identity()..addAll(selection);
  var result = <ImagePart>[];
  for (var part in album.parts) {
    if (part is ImagePart) {
      if (chosen.contains(part)) {
        result.add(part);
      }
    } else if (part is ImageGroup) {
      var whole = chosen.contains(part);
      for (var image in part.images) {
        if (whole || chosen.contains(image)) {
          result.add(image);
        }
      }
    }
  }
  return result;
}

/// The images of the album that were taken by the same camera as [reference]
/// (issue #78).
///
/// The parts to *add* to the selection, so that one gesture gathers everything
/// one device contributed to an album that was fed from several — the
/// selection the recording time adjustment of issue #77 then acts on.
///
/// [ImagePart.camera] is a label the server derived from the EXIF make and
/// model, and it is only ever compared for equality: an image whose label is
/// empty (a video, an original without the tags, a part written before the
/// field existed) matches nothing at all, not even another image without a
/// label. The reference itself is part of the answer where it carries one.
///
/// An image inside an [ImageGroup] joins the answer *as the image*, not as its
/// group: that is how a selection names a group member everywhere else (see
/// [selectedImages]), and a group holding photos of two cameras must not be
/// adjusted as a whole because one of them matched. The group's tile is
/// therefore not marked — what is selected is the image, and the edit that
/// follows says how many images it applies to.
Set<AlbumPart> sameCamera(AlbumInfo album, ImagePart reference) {
  var result = Set<AlbumPart>.identity();
  var label = reference.camera;
  if (label.isEmpty) {
    return result;
  }
  for (var part in album.parts) {
    if (part is ImagePart) {
      if (part.camera == label) {
        result.add(part);
      }
    } else if (part is ImageGroup) {
      for (var image in part.images) {
        if (image.camera == label) {
          result.add(image);
        }
      }
    }
  }
  return result;
}

/// The image the recording time adjustment is stated relative to (issue #77).
///
/// The tile the action was invoked on, if that tile shows a selected image (a
/// group's tile stands for its representative); otherwise the first selected
/// image in stored order. `null` if nothing that carries a date is selected.
ImagePart? referenceImage(
  AlbumInfo album,
  Set<AlbumPart> selection, [
  AlbumPart? invokedOn,
]) {
  var images = selectedImages(album, selection);
  if (invokedOn is AbstractImage) {
    var image = ToImage.toImage(invokedOn);
    if (images.any((candidate) => identical(candidate, image))) {
      return image;
    }
  }
  return images.isEmpty ? null : images.first;
}

/// The offset that moves the recording time of [reference] to [corrected].
///
/// `null` when there is nothing to compute from: no corrected time was
/// entered, or the reference image carries no date at all (`0`) — an offset
/// from "unknown" would be an invented recording time, see
/// [adjustRecordingTime].
Duration? offsetFor(ImagePart reference, DateTime? corrected) {
  if (corrected == null || reference.date == 0) {
    return null;
  }
  return corrected
      .difference(DateTime.fromMillisecondsSinceEpoch(reference.date));
}

/// The given offset in words, as the dialog shows it before applying it:
/// `"+2 h 13 min 5 s"`, `"−1 d 0 h 4 min"`.
///
/// The sign is always there (a real minus sign, U+2212, not a hyphen), and
/// the units run from the largest one that is not zero down to the smallest
/// one that is not zero — a zero in between is kept, so that the reading is
/// unambiguous, a zero at either end is left out. The zero offset reads
/// `"0 s"`; the dialog says "Nothing to adjust" instead.
String offsetInWords(Duration offset) {
  var millis = offset.inMilliseconds;
  if (millis == 0) {
    return "0 s";
  }
  var sign = millis < 0 ? "−" : "+";
  var rest = millis.abs();
  var units = [
    ("d", Duration.millisecondsPerDay),
    ("h", Duration.millisecondsPerHour),
    ("min", Duration.millisecondsPerMinute),
    ("s", Duration.millisecondsPerSecond),
  ];
  var values = <(String, int)>[];
  for (var (name, size) in units) {
    values.add((name, rest ~/ size));
    rest = rest % size;
  }
  var first = values.indexWhere((value) => value.$2 != 0);
  var last = values.lastIndexWhere((value) => value.$2 != 0);
  if (first < 0) {
    // Less than a second, but not nothing: say it in seconds rather than
    // claiming there is no offset.
    return "$sign${(offset.inMilliseconds.abs() / 1000).toStringAsFixed(3)} s";
  }
  var words = [
    for (var i = first; i <= last; i++) "${values[i].$2} ${values[i].$1}",
  ];
  return "$sign${words.join(" ")}";
}

/// Where an image taken at [date] is inserted into the given parts.
///
/// The album's insertion rule, mirroring the server's `AlbumUtil.insertSorted`
/// (`image-server-shared`): directly *after* the last entry, in stored order,
/// whose date is not later than [date]. A group counts with [dateOf], the
/// earliest date of its images; a [Heading] has no date and is passed over,
/// so an image lands in the section of the entry it follows. If no entry is
/// that old the image goes *before* the earliest-dated entry, and into an
/// album holding no dated entry at all it goes last.
int insertIndexByDate(List<AlbumPart> parts, int date) {
  var after = -1;
  for (var index = 0; index < parts.length; index++) {
    var part = parts[index];
    if (part is Heading) {
      continue;
    }
    if (dateOf(part) <= date) {
      after = index;
    }
  }
  if (after >= 0) {
    return after + 1;
  }

  var earliest = -1;
  int? least;
  for (var index = 0; index < parts.length; index++) {
    var part = parts[index];
    if (part is Heading) {
      continue;
    }
    var candidate = dateOf(part);
    if (least == null || candidate < least) {
      least = candidate;
      earliest = index;
    }
  }
  return earliest < 0 ? parts.length : earliest;
}

/// Adds [offset] to the recording time of every selected image and puts the
/// adjusted images back where their new date belongs (issue #77).
///
/// The cameras an album is fed from run off by seconds, minutes or even days,
/// which makes a date order nonsense. The correction is a *sidecar* edit: only
/// [ImagePart.date] changes, and the EXIF data of the original file is never
/// touched — the server can read the original recording time again for any
/// part that is taken out of the sidecar.
///
/// Every selected image is adjusted, videos included (see [selectedImages]:
/// a selected group stands for all of its images). An image without a date
/// (`0`) is left alone: `0` means "no recording time known", and shifting it
/// would invent one and file the image at the beginning of the album.
///
/// Each adjusted image is then taken out of wherever it stands and re-inserted
/// at the album level by [insertIndexByDate], earliest first — a clock that
/// was a day off moved the image under the wrong heading, and the correction
/// has to be able to move it out of that section again. Taking it out means:
///
/// * out of its group. A group left with a single image is dissolved the way
///   [ungroup] dissolves it today, and a group left with none disappears;
///   [ImageGroup.representative] follows its image, falling back to the first
///   remaining one if the representative itself was adjusted away.
/// * out of its heading section — the re-insertion decides the new one.
///
/// Adjusted images that shared a group and end up side by side again are *not*
/// regrouped: the group was an edit of its own, and grouping them again is one
/// gesture away.
///
/// Entries that were not adjusted never move relative to each other.
///
/// Returns whether anything changed, so the caller marks the album dirty (and
/// writes it back) only then.
bool adjustRecordingTime(
  AlbumInfo album,
  Set<AlbumPart> selected,
  Duration offset,
) {
  var millis = offset.inMilliseconds;
  if (millis == 0) {
    return false;
  }
  // The undated ones keep their "unknown", see above.
  var moved = [
    for (var image in selectedImages(album, selected))
      if (image.date != 0) image,
  ];
  if (moved.isEmpty) {
    return false;
  }

  var adjusted = Set<ImagePart>.identity()..addAll(moved);
  for (var image in moved) {
    image.date += millis;
  }

  // Take the adjusted images out of the album, groups included.
  var remaining = <AlbumPart>[];
  var dissolving = <ImageGroup>[];
  for (var part in album.parts) {
    if (part is ImagePart) {
      if (!adjusted.contains(part)) {
        remaining.add(part);
      }
      continue;
    }
    if (part is ImageGroup && part.images.any(adjusted.contains)) {
      var kept = [
        for (var image in part.images)
          if (!adjusted.contains(image)) image,
      ];
      if (kept.isEmpty) {
        // Nothing left of it.
        continue;
      }
      var index = part.representative;
      var representing =
          index >= 0 && index < part.images.length ? part.images[index] : null;
      part.images = kept;
      var stillThere = representing == null
          ? -1
          : kept.indexWhere((image) => identical(image, representing));
      part.representative = stillThere < 0 ? 0 : stillThere;
      remaining.add(part);
      if (kept.length == 1) {
        dissolving.add(part);
      }
      continue;
    }
    remaining.add(part);
  }
  album.parts = remaining;

  // A group of one is no group, see [ungroup].
  for (var group in dissolving) {
    ungroup(album, group);
  }

  // And back in, the earliest first, so that images of the same new date
  // keep the order they had.
  for (var image in sortedByDate(moved)) {
    album.parts.insert(insertIndexByDate(album.parts, image.date), image);
  }

  AlbumInitializer().init(album);
  return true;
}

/// Whether both lists hold the same parts in the same order.
bool _sameOrder(List<AlbumPart> left, List<AlbumPart> right) {
  if (left.length != right.length) {
    return false;
  }
  for (var i = 0; i < left.length; i++) {
    if (!identical(left[i], right[i])) {
      return false;
    }
  }
  return true;
}

/// Dissolves the given group: its images take its place in the album, in the
/// order they are stored in.
///
/// Returns the images now standing in the album on their own, empty if [group]
/// is not a part of [album].
List<ImagePart> ungroup(AlbumInfo album, ImageGroup group) {
  var index = album.parts.indexOf(group);
  if (index < 0) {
    return const [];
  }

  var members = group.images.toList();
  album.parts = [
    ...album.parts.sublist(0, index),
    ...members,
    ...album.parts.sublist(index + 1),
  ];
  AlbumInitializer().init(album);
  return members;
}

/// The width of the square listing tile the index picture's offsets refer to,
/// in the pixels of the retired GWT client.
const double indexPictureTileSize = 300;

/// The index picture showing the given image in the parent listing, framed
/// as the server frames its own fallback (the first image of a folder without
/// a sidecar, see `ResourceCache.loadFolderInfo` and `PrivacyFilter.thumbnail`).
///
/// The listing tile is square; the image is scaled up by its aspect ratio so
/// that it fills the square, and a portrait image is shifted down so that its
/// middle is what the square shows. The offsets are in the pixels of the
/// 300px tile of the GWT client, see `thumbnailTransform`.
///
/// The frame is the one the image is *displayed* in, not the one the file
/// holds: a landscape file stored as `rotL` is a portrait picture and is
/// framed as one. Which frame that is travels with the crop
/// ([ThumbnailInfo.orientation], issue #115), so that the tile can turn the
/// server's rendition by it before applying the crop — the two are only
/// meaningful together.
ThumbnailInfo indexPictureOf(ImagePart image) {
  var orientation = image.orientation;
  var swapped = PlaneTransform.of(orientation).swapsDimensions;
  double width = (swapped ? image.height : image.width).toDouble();
  double height = (swapped ? image.width : image.height).toDouble();
  if (width <= 0 || height <= 0) {
    return ThumbnailInfo(
      image: image.name,
      scale: 1,
      orientation: orientation,
    );
  }
  var scale = width / height;
  var ty = 0.0;
  if (scale < 1) {
    scale = 1 / scale;
    ty = (height - width) / height * (indexPictureTileSize / 2);
  }
  return ThumbnailInfo(
    image: image.name,
    scale: scale,
    ty: ty,
    orientation: orientation,
  );
}

/// Keeps the crop of the album picture in the frame its image is displayed
/// in, after that image was turned in the edit mode (issue #115).
///
/// The crop values themselves are kept: a quarter turn may shift what the
/// square shows a little, which is less surprising than a crop that resets
/// itself — and the crop editor is one menu entry away. Nothing happens when
/// [image] is not the album's picture.
void syncIndexPictureOrientation(AlbumInfo album, ImagePart image) {
  var info = album.indexPicture;
  if (info != null && info.image == image.name) {
    info.orientation = image.orientation;
  }
}

/// Whether the given image is the one representing its album in the listing.
bool isIndexPicture(AlbumInfo album, ImagePart image) =>
    album.indexPicture?.image == image.name;

/// The least an index picture is scaled by: the whole image inside the square.
const double minIndexPictureScale = 1;

/// The most an index picture is scaled by.
const double maxIndexPictureScale = 8;

/// The index picture panned by ([dx], [dy]) pixels of a square tile of
/// [tileSize], the way the crop editor drags it.
///
/// The listing tile shows the image translated by the offsets (in the pixels
/// of the [indexPictureTileSize]) and then scaled about the centre, see
/// `thumbnailTransform`: a displayed shift of `d` pixels is a shift of
/// `d / (scale * tileSize / indexPictureTileSize)` in the stored offsets.
ThumbnailInfo panIndexPicture(
  ThumbnailInfo info,
  double dx,
  double dy,
  double tileSize,
) {
  var scale = info.scale > 0 ? info.scale : 1.0;
  var factor = scale * tileSize / indexPictureTileSize;
  return ThumbnailInfo(
    image: info.image,
    scale: info.scale,
    tx: info.tx + dx / factor,
    ty: info.ty + dy / factor,
    // The frame the crop is measured in does not change by panning it.
    orientation: info.orientation,
  );
}

/// The index picture zoomed by [factor] about the centre of the tile, within
/// [minIndexPictureScale] and [maxIndexPictureScale].
///
/// The offsets are applied before the scale, so the point of the image at the
/// centre of the tile does not depend on the scale: keeping the offsets keeps
/// the centre where it is.
ThumbnailInfo zoomIndexPicture(ThumbnailInfo info, double factor) {
  var scale = info.scale > 0 ? info.scale : 1.0;
  return ThumbnailInfo(
    image: info.image,
    scale: (scale * factor).clamp(minIndexPictureScale, maxIndexPictureScale),
    tx: info.tx,
    ty: info.ty,
    orientation: info.orientation,
  );
}

/// The image of the album named by its index picture, `null` if there is no
/// index picture or no image of that name (a group member counts).
ImagePart? indexImageOf(AlbumInfo album) {
  var name = album.indexPicture?.image;
  if (name == null) {
    return null;
  }
  for (var part in album.parts) {
    if (part is ImagePart && part.name == name) {
      return part;
    }
    if (part is ImageGroup) {
      for (var image in part.images) {
        if (image.name == name) {
          return image;
        }
      }
    }
  }
  return null;
}
