/// The crop of a photograph, widget-free (issue #212).
///
/// A crop is stored on the part ([ImagePart.crop]) in the frame the picture
/// is *shown* in: the file upright and [ImagePart.orientation] applied — the
/// frame the crop editor draws. Every rendition of the server is upright by
/// the file alone and is turned by [ImagePart.orientation] on the way to the
/// screen, so the region a rendition is cut to is the stored crop taken back
/// through that orientation, [cropToRendition]. The server does the same
/// (`Crops.toRendition`); both read the shared table
/// `image-server/src/test/fixtures/crop-orientations.json`.
library;

import 'dart:math' as math;

import 'package:flutter/painting.dart' show Rect, Offset, Size;

import 'image_transform.dart';
import 'resource.dart';

/// The smallest side a crop may have, as a fraction of the picture's side;
/// the server refuses less (`Crops.MIN_SIDE`).
const double minCropSide = 0.01;

/// How close to the whole picture a crop may be and still be none.
const double _wholeTolerance = 1e-4;

/// Whether [crop] shows the whole picture, which is stored as no crop.
bool isWholeCrop(Crop? crop) =>
    crop == null ||
    (crop.x.abs() <= _wholeTolerance &&
        crop.y.abs() <= _wholeTolerance &&
        (crop.w - 1).abs() <= _wholeTolerance &&
        (crop.h - 1).abs() <= _wholeTolerance);

/// The crop [image] is shown with, `null` for the whole picture or a video.
Crop? effectiveCrop(ImagePart image) {
  var crop = image.crop;
  if (image.kind != ImageKind.image || isWholeCrop(crop)) {
    return null;
  }
  return crop;
}

/// The normalised transform [orientation] applies to the unit square: where
/// a point of the rendition is shown. The very matrix the viewer draws with,
/// [ImageTransform.orientationTransform], at a size of one by one.
Offset _shownOf(Orientation orientation, Offset point) {
  var m = ImageTransform.orientationTransform(orientation, 1, 1);
  return Offset(
    m.entry(0, 0) * point.dx + m.entry(0, 1) * point.dy + m.entry(0, 3),
    m.entry(1, 0) * point.dx + m.entry(1, 1) * point.dy + m.entry(1, 3),
  );
}

/// Where a shown point lies in the rendition, the inverse of [_shownOf].
Offset _renditionOf(Orientation orientation, Offset point) {
  var m = ImageTransform.orientationTransform(orientation, 1, 1);
  var a = m.entry(0, 0), b = m.entry(0, 1), e = m.entry(0, 3);
  var c = m.entry(1, 0), d = m.entry(1, 1), f = m.entry(1, 3);
  var det = a * d - b * c;
  var px = point.dx - e, py = point.dy - f;
  return Offset((d * px - b * py) / det, (-c * px + a * py) / det);
}

Rect _box(Offset one, Offset two) => Rect.fromPoints(one, two);

/// The crop [crop] of the shown picture as a box of the server's rendition,
/// which [orientation] turns into the shown picture.
Rect cropToRendition(Orientation orientation, Crop crop) => _box(
  _renditionOf(orientation, Offset(crop.x, crop.y)),
  _renditionOf(orientation, Offset(crop.x + crop.w, crop.y + crop.h)),
);

/// The inverse of [cropToRendition].
Crop cropToShown(Orientation orientation, Rect region) {
  var box = _box(
    _shownOf(orientation, region.topLeft),
    _shownOf(orientation, region.bottomRight),
  );
  return Crop(x: box.left, y: box.top, w: box.width, h: box.height);
}

/// The region of the rendition [image] is cut to, `null` for the whole one.
Rect? renditionRegion(ImagePart image) {
  var crop = effectiveCrop(image);
  return crop == null ? null : cropToRendition(image.orientation, crop);
}

/// The same region of the picture after its orientation changed from [from]
/// to [to] (issue #212): what was selected stays selected, in the new frame —
/// as `syncIndexPictureOrientation` keeps the album picture's frame (#115).
Crop? carryCrop(Crop? crop, Orientation from, Orientation to) {
  if (crop == null) {
    return null;
  }
  return cropToShown(to, cropToRendition(from, crop));
}

/// Gives [image] the orientation [to] and carries its crop along.
void turnImage(ImagePart image, Orientation to) {
  image.crop = carryCrop(image.crop, image.orientation, to);
  image.orientation = to;
}

/// The region spelled as the `crop` parameter of a `?type=tn` spells it.
String cropToken(Rect region) => [
  region.left,
  region.top,
  region.width,
  region.height,
].map((value) => value.toStringAsFixed(4)).join(",");

/// The address that stands for the cut picture of [image] wherever the app
/// asks for a thumbnail by an image address (`thumbnails.dart`): the image's
/// own address with the region of its crop, `?crop=x,y,w,h` — so that two
/// crops are two cache keys, offline as well as in Flutter's image cache —
/// and the plain address for a picture shown whole.
String croppedImageUrl(String imageUrl, ImagePart image) {
  var region = renditionRegion(image);
  return withRegion(imageUrl, region);
}

/// [imageUrl] carrying [region], see [croppedImageUrl].
String withRegion(String imageUrl, Rect? region) =>
    region == null ? imageUrl : "$imageUrl?crop=${cropToken(region)}";

/// The region a listing tile's cover asks for (`ThumbnailInfo.crop`, which
/// the server derives in the frame of the rendition).
Rect? coverRegion(ThumbnailInfo info) {
  var crop = info.crop;
  if (crop == null || isWholeCrop(crop)) {
    return null;
  }
  return Rect.fromLTWH(crop.x, crop.y, crop.w, crop.h);
}

/// The size of the rendition's region of [image], in pixels of the file
/// before [ImagePart.orientation]: [ImagePart.width] × [ImagePart.height]
/// where it is shown whole.
Size renditionSize(ImagePart image) {
  var width = image.width.toDouble();
  var height = image.height.toDouble();
  var region = renditionRegion(image);
  if (region == null) {
    return Size(width, height);
  }
  return Size(width * region.width, height * region.height);
}

/// Whether [face] — a box of the rendition — is seen at all through
/// [region]: a face entirely outside the crop is not drawn.
bool faceInRegion(FaceInfo face, Rect? region) {
  if (region == null) {
    return true;
  }
  return Rect.fromLTWH(face.x, face.y, face.w, face.h).overlaps(region);
}

// --- The editor's arithmetic. ---

/// The aspect constraints of the crop editor.
enum CropAspect {
  /// The aspect of the whole picture, the default.
  image,

  /// No constraint.
  free,

  /// 1:1.
  square,

  /// 4:3.
  fourThree,

  /// 3:2.
  threeTwo,

  /// 16:9.
  sixteenNine,

  /// 5:4.
  fiveFour;

  /// The ratio of the longer to the shorter side, `null` for none; the
  /// picture's own for [image], given by the caller.
  double? ratio(double pictureRatio) => switch (this) {
    CropAspect.image => pictureRatio >= 1 ? pictureRatio : 1 / pictureRatio,
    CropAspect.free => null,
    CropAspect.square => 1,
    CropAspect.fourThree => 4 / 3,
    CropAspect.threeTwo => 3 / 2,
    CropAspect.sixteenNine => 16 / 9,
    CropAspect.fiveFour => 5 / 4,
  };

  /// Whether the portrait/landscape toggle means anything for it.
  bool get turnable => this != CropAspect.free && this != CropAspect.square;
}

/// The width-to-height ratio (in pixels of the shown picture) a crop must
/// have, `null` for none: [aspect] in the orientation [portrait] names.
double? targetRatio(CropAspect aspect, bool portrait, double pictureRatio) {
  var ratio = aspect.ratio(pictureRatio);
  if (ratio == null) {
    return null;
  }
  return portrait ? 1 / ratio : ratio;
}

/// Whether a crop of the whole picture is portrait under the image ratio.
bool picturePortrait(double pictureRatio) => pictureRatio < 1;

/// The largest rectangle of [ratio] (width : height in pixels of the
/// picture, which is [pictureRatio] wide for one high) centred on [centre]
/// and inside the picture, in normalised coordinates of the picture.
Rect fitRatio(double ratio, double pictureRatio, Offset centre) {
  // In normalised units, a box w × h has the pixel ratio
  // (w * pictureRatio) / h; so h = w * pictureRatio / ratio.
  var w = 1.0;
  var h = w * pictureRatio / ratio;
  if (h > 1) {
    h = 1;
    w = h * ratio / pictureRatio;
  }
  var left = (centre.dx - w / 2).clamp(0.0, 1 - w);
  var top = (centre.dy - h / 2).clamp(0.0, 1 - h);
  return Rect.fromLTWH(left, top, w, h);
}

/// [rect] brought to [ratio], keeping its centre and its area as far as the
/// picture allows (the toggle and a change of constraint).
Rect withRatio(Rect rect, double ratio, double pictureRatio) {
  var area = rect.width * rect.height;
  // w * h = area and (w * pictureRatio) / h = ratio.
  var w = math.sqrt(area * ratio / pictureRatio);
  var h = area / w;
  var scale = math.min(1.0, math.min(1 / w, 1 / h));
  w *= scale;
  h *= scale;
  var centre = rect.center;
  var left = (centre.dx - w / 2).clamp(0.0, 1 - w);
  var top = (centre.dy - h / 2).clamp(0.0, 1 - h);
  return Rect.fromLTWH(left, top, w, h);
}

/// Which part of a crop rectangle a pointer took.
enum CropHandle {
  topLeft,
  top,
  topRight,
  right,
  bottomRight,
  bottom,
  bottomLeft,
  left;

  /// Whether this handle moves the left edge.
  bool get movesLeft => this == topLeft || this == left || this == bottomLeft;

  /// Whether this handle moves the right edge.
  bool get movesRight =>
      this == topRight || this == right || this == bottomRight;

  /// Whether this handle moves the top edge.
  bool get movesTop => this == topLeft || this == top || this == topRight;

  /// Whether this handle moves the bottom edge.
  bool get movesBottom =>
      this == bottomLeft || this == bottom || this == bottomRight;

  /// Whether this is a corner.
  bool get isCorner => (movesLeft || movesRight) && (movesTop || movesBottom);
}

/// [rect] moved by [delta], kept inside the picture whole.
Rect movedCrop(Rect rect, Offset delta) {
  var left = (rect.left + delta.dx).clamp(0.0, 1 - rect.width);
  var top = (rect.top + delta.dy).clamp(0.0, 1 - rect.height);
  return Rect.fromLTWH(left, top, rect.width, rect.height);
}

/// [start] resized by dragging [handle] to [point], under [ratio] (width :
/// height in pixels of the picture, `null` for free), inside the picture and
/// never smaller than [minSide] on either side.
///
/// The edge (or the corner) opposite the handle stays where it is. Under a
/// ratio a corner follows the pointer along the axis that asks for the
/// larger rectangle, and an edge handle keeps the rectangle centred on the
/// other axis; whatever would leave the picture shrinks the rectangle until
/// it fits.
Rect resizedCrop(
  Rect start,
  CropHandle handle,
  Offset point,
  double? ratio,
  double pictureRatio, {
  double minSide = minCropSide,
}) {
  var px = point.dx.clamp(0.0, 1.0);
  var py = point.dy.clamp(0.0, 1.0);
  var left = start.left, top = start.top;
  var right = start.right, bottom = start.bottom;
  if (handle.movesLeft) left = math.min(px, right - minSide);
  if (handle.movesRight) right = math.max(px, left + minSide);
  if (handle.movesTop) top = math.min(py, bottom - minSide);
  if (handle.movesBottom) bottom = math.max(py, top + minSide);
  if (ratio == null) {
    return Rect.fromLTRB(
      left.clamp(0.0, 1.0),
      top.clamp(0.0, 1.0),
      right.clamp(0.0, 1.0),
      bottom.clamp(0.0, 1.0),
    );
  }
  // In normalised units the ratio of width to height is k = ratio / pictureRatio.
  var k = ratio / pictureRatio;
  var w = right - left;
  var h = bottom - top;
  // The anchor: the side (or corner) that does not move.
  double anchorX = handle.movesLeft
      ? start.right
      : handle.movesRight
      ? start.left
      : start.center.dx;
  double anchorY = handle.movesTop
      ? start.bottom
      : handle.movesBottom
      ? start.top
      : start.center.dy;
  if (handle.isCorner) {
    // The larger of the two the pointer asks for.
    if (w / k >= h) {
      h = w / k;
    } else {
      w = h * k;
    }
  } else if (handle.movesLeft || handle.movesRight) {
    h = w / k;
  } else {
    w = h * k;
  }
  // The room there is from the anchor in the directions the box grows.
  double roomX = handle.movesLeft
      ? anchorX
      : handle.movesRight
      ? 1 - anchorX
      : 2 * math.min(anchorX, 1 - anchorX);
  double roomY = handle.movesTop
      ? anchorY
      : handle.movesBottom
      ? 1 - anchorY
      : 2 * math.min(anchorY, 1 - anchorY);
  if (w > 0 && h > 0) {
    var scale = math.min(1.0, math.min(roomX / w, roomY / h));
    w *= scale;
    h *= scale;
  }
  var minW = math.max(minSide, minSide * k);
  if (w < minW) {
    w = minW;
    h = w / k;
  }
  double newLeft = handle.movesLeft
      ? anchorX - w
      : handle.movesRight
      ? anchorX
      : anchorX - w / 2;
  double newTop = handle.movesTop
      ? anchorY - h
      : handle.movesBottom
      ? anchorY
      : anchorY - h / 2;
  return Rect.fromLTWH(
    newLeft.clamp(0.0, math.max(0.0, 1 - w)),
    newTop.clamp(0.0, math.max(0.0, 1 - h)),
    w,
    h,
  );
}

/// A rectangle drawn from [start] to [point] under [ratio], inside the
/// picture: the corner opposite [start] follows the pointer.
Rect drawnCrop(Offset start, Offset point, double? ratio, double pictureRatio) {
  var sx = start.dx.clamp(0.0, 1.0);
  var sy = start.dy.clamp(0.0, 1.0);
  var handle = point.dx >= sx
      ? (point.dy >= sy ? CropHandle.bottomRight : CropHandle.topRight)
      : (point.dy >= sy ? CropHandle.bottomLeft : CropHandle.topLeft);
  return resizedCrop(
    Rect.fromLTWH(sx, sy, 0, 0),
    handle,
    point,
    ratio,
    pictureRatio,
    minSide: 0,
  );
}
