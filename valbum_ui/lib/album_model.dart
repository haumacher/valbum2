/// Pure model helpers operating on the generated album model.
///
/// This library holds the logic that is independent of any widget, so that it
/// can be unit-tested without pumping a widget tree.
library;

import 'album_edit.dart' show noMinRating;
import 'album_layout.dart' show ToImage;
import 'inbox_view.dart' show inboxDays;
import 'locales.dart' show platformMessages;
import 'resource.dart';
import 'search_grouping.dart';

/// Rebuilds the transient fields of an album: the [AbstractImage.previous],
/// [AbstractImage.next], [AbstractImage.home] and [AbstractImage.end] links,
/// the [AlbumPart.owner] of every part and the [ImagePart.group] of every
/// image inside an [ImageGroup].
///
/// This is the `UpdateTransient` of the retired GWT client. The album parts
/// form one chain: an [ImageGroup] is a single link in it, no matter how many
/// images it holds. The images *inside* a group form a chain of their own —
/// that is the order the "alternatives" view of the group navigates in (see
/// `group_view.dart`), so `previous`/`next`/`home`/`end` of a group member
/// stay inside its group.
///
/// A saved search, and the result of the search view, is grouped here by the
/// keys of its query (issue #244, see [groupSearchParts]): its headings are
/// derived from its photos whenever it is initialized, never stored, and the
/// chain the viewer pages through follows the sections.
class AlbumInitializer {
  /// The words of the headings of a grouped search; the app's language where
  /// none are given.
  final SearchGroupTexts? searchTexts;

  AlbumInitializer({this.searchTexts});

  /// Initializes the transient fields of the given album.
  void init(AlbumInfo self) {
    if (self.kind == AlbumKind.search) {
      var keys = self.query?.groupBy ?? const <SearchGroup>[];
      // Headings of a search are always derived: none is ever answered or
      // stored, so dropping them first makes this one idempotent.
      if (keys.isNotEmpty || self.parts.any((part) => part is Heading)) {
        self.parts = groupSearchParts(
          self.parts,
          keys,
          texts: searchTexts ?? SearchGroupTexts.of(platformMessages),
        );
      }
    }
    for (var part in self.parts) {
      part.owner = self;
      if (part is ImageGroup) {
        for (var image in part.images) {
          image.owner = self;
          image.group = part;
        }
        _link(part.images);
      } else if (part is ImagePart) {
        part.group = null;
      }
    }
    _link(_chainOf(self));
  }

  /// The images of the album in the order the viewer pages through them: the
  /// order they are drawn in.
  ///
  /// For an album that is its stored order. An inbox draws something else
  /// than the server's flat newest-first answer — the days newest first, the
  /// photographs of a day chronologically ([inboxDays]) — so its chain follows
  /// the days, or "next" in the viewer would step to the photograph drawn
  /// *before* inside a day. Every photograph is linked, the trashed ones
  /// included; the viewer skips what its rating floor hides, as it does in
  /// an album.
  static List<AbstractImage> _chainOf(AlbumInfo self) {
    if (self.kind == AlbumKind.inbox) {
      return [
        for (var day in inboxDays(self.parts, minRating: noMinRating))
          ...day.images,
      ];
    }
    return self.parts.whereType<AbstractImage>().toList();
  }

  /// Links the given images into a chain of `previous`/`next`/`home`/`end`.
  static void _link(List<AbstractImage> images) {
    if (images.isEmpty) {
      return;
    }
    for (var i = 0; i < images.length; i++) {
      var image = images[i];
      image.previous = i > 0 ? images[i - 1] : null;
      image.next = i < images.length - 1 ? images[i + 1] : null;
      image.home = images.first;
      image.end = images.last;
    }
  }
}

final Expando<Set<String>> _cameraFilters = Expando("cameraFilter");

/// The cameras the given inbox is filtered by on the screen (issue #278),
/// empty for none: the labels of [ImagePart.camera], the empty label for the
/// photographs without camera data.
///
/// Kept beside the album object like its label filter (`labelFilterOf`,
/// issue #213): the inbox and the viewer opened from it read the same album,
/// so paging in the viewer stays inside what the inbox shows — and, being
/// read on every step, follows the filter as it is set at that moment.
Set<String> cameraFilterOf(AlbumInfo? album) =>
    (album == null ? null : _cameraFilters[album]) ?? const {};

/// Filters the given album to [cameras], none showing every photograph.
void setCameraFilter(AlbumInfo album, Set<String> cameras) =>
    _cameraFilters[album] = cameras.isEmpty ? null : Set.unmodifiable(cameras);

/// Whether the viewer shows the given image under its album's camera filter.
bool shownByCameraFilter(AbstractImage image) {
  var cameras = cameraFilterOf(image.owner);
  return cameras.isEmpty || cameras.contains(ToImage.toImage(image).camera);
}

/// The name of the image file representing the given image in a listing or
/// album view (the representative of a group, the image itself otherwise).
extension ThumbnailName on AbstractImage {
  String get thumbnailName {
    if (this is ImagePart) {
      return (this as ImagePart).name;
    } else {
      var group = this as ImageGroup;
      return group.images[group.representative].name;
    }
  }
}
