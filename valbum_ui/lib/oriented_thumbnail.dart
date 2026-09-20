/// The one place a server rendition is turned by the orientation stored
/// beside the image (issue #106).
///
/// A rendition the server makes — the thumbnail of a tile, the preview, the
/// poster of a video — is upright by the *file*: `PreviewCache` bakes the
/// orientation the image file carries into it and nothing else.
/// [ImagePart.orientation] is the transform stored beside the image, *in
/// addition* to that one, so every tile of the app applies it on the way to
/// the screen. The album tile and the alternatives of a group share this
/// function so that the two cannot drift apart; the viewer does the same over
/// its own coordinates, see `ImageViewState.pictureLayer`.
library;

import 'dart:math';

import 'package:flutter/material.dart' hide Orientation;

import 'album_edit.dart';
import 'client.dart';
import 'resource.dart';
import 'thumbnails.dart';

/// The thumbnail of the image at [imageUrl], turned by [orientation] and
/// fitted into a tile of [width] x [height].
///
/// [rawWidth]/[rawHeight] are the size the *file* has (`ImagePart.width` and
/// `ImagePart.height`, which the server reports upright by the file); a size
/// that is not known is fitted to the tile box instead.
///
/// The tile box is the layout's, and the layout is computed with the
/// orientation the album was loaded with: rotating a tile must not reflow the
/// album under the user's hands. The turned image is therefore scaled into the
/// box it already occupies. At rest the two agree — the box of a `rotL`
/// landscape file is portrait already — and the turned rendition fills it
/// exactly; only while an image is being rotated in the edit mode is the box
/// the wrong way round, and then the image sits inside it until the album is
/// laid out again.
Widget orientedThumbnail(
  VAlbumClient client,
  String imageUrl, {
  Key? key,
  required Orientation orientation,
  required double rawWidth,
  required double rawHeight,
  required double width,
  required double height,
}) {
  var transform = PlaneTransform.of(orientation);
  if (transform == PlaneTransform.identity) {
    // Nothing to turn: the rendition is the tile, as it always was.
    return thumbnail(
      client,
      imageUrl,
      key: key,
      width: width,
      height: height,
      displayHeight: height,
      fit: BoxFit.contain,
    );
  }

  var fileWidth = rawWidth;
  var fileHeight = rawHeight;
  if (fileWidth <= 0 || fileHeight <= 0) {
    fileWidth = transform.swapsDimensions ? height : width;
    fileHeight = transform.swapsDimensions ? width : height;
  }

  // The turned image fitted into the tile box, measured before the turn: that
  // is the box the rendition is drawn in, and the height it is decoded at,
  // see [thumbnail].
  var turnedWidth = transform.swapsDimensions ? fileHeight : fileWidth;
  var turnedHeight = transform.swapsDimensions ? fileWidth : fileHeight;
  var scale = min(width / turnedWidth, height / turnedHeight);
  var drawnWidth = fileWidth * scale;
  var drawnHeight = fileHeight * scale;

  Widget result = thumbnail(
    client,
    imageUrl,
    key: key,
    width: drawnWidth,
    height: drawnHeight,
    displayHeight: drawnHeight,
    // The box has the aspect ratio of the rendition, so nothing is distorted;
    // filling it keeps the turn free of empty margins.
    fit: BoxFit.fill,
  );

  // [PlaneTransform] mirrors first and turns afterwards, so the mirror is the
  // inner wrapper.
  if (transform.mirrored) {
    result = Transform.scale(scaleX: -1, scaleY: 1, child: result);
  }
  // [PlaneTransform.quarterTurns] counts counter-clockwise, [RotatedBox]
  // clockwise.
  var clockwise = (4 - transform.quarterTurns % 4) % 4;
  if (clockwise != 0) {
    result = RotatedBox(quarterTurns: clockwise, child: result);
  }

  return SizedBox(
    width: width,
    height: height,
    child: Center(child: result),
  );
}

/// The thumbnail of the given image part, turned by its own orientation, see
/// [orientedThumbnail].
Widget orientedImageThumbnail(
  VAlbumClient client,
  String imageUrl,
  ImagePart image, {
  Key? key,
  required double width,
  required double height,
}) =>
    orientedThumbnail(
      client,
      imageUrl,
      key: key,
      orientation: image.orientation,
      rawWidth: image.width.toDouble(),
      rawHeight: image.height.toDouble(),
      width: width,
      height: height,
    );
