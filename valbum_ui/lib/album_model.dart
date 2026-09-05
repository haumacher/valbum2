/// Pure model helpers operating on the generated album model.
///
/// This library holds the logic that is independent of any widget, so that it
/// can be unit-tested without pumping a widget tree.
library;

import 'resource.dart';

/// The direction in which [AlbumInitializer] walks the album parts.
enum Direction { previous, next }

/// Initializes [AbstractImage.next] and [AbstractImage.previous] fields.
class AlbumInitializer implements AlbumPartVisitor<AbstractImage?, Direction> {
  AbstractImage? previous;
  AbstractImage? next;

  void init(AlbumInfo self) {
    for (var part in self.parts) {
      AbstractImage? self = part.visitAlbumPart(this, Direction.previous);
      if (self != null) previous = self;
    }
    for (var part in self.parts.reversed) {
      AbstractImage? self = part.visitAlbumPart(this, Direction.next);
      if (self != null) next = self;
    }
  }

  @override
  AbstractImage? visitHeading(Heading self, Direction arg) {
    return null;
  }

  @override
  AbstractImage? visitImageGroup(ImageGroup self, Direction arg) {
    return initImage(arg, self);
  }

  @override
  AbstractImage? visitImagePart(ImagePart self, Direction arg) {
    return initImage(arg, self);
  }

  AbstractImage initImage(Direction arg, AbstractImage self) {
    if (arg == Direction.previous) {
      self.previous = previous;
    } else {
      self.next = next;
    }
    return self;
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
