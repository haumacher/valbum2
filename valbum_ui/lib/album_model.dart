/// Pure model helpers operating on the generated album model.
///
/// This library holds the logic that is independent of any widget, so that it
/// can be unit-tested without pumping a widget tree.
library;

import 'resource.dart';

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
class AlbumInitializer {
  /// Initializes the transient fields of the given album.
  void init(AlbumInfo self) {
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
    _link(self.parts.whereType<AbstractImage>().toList());
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
