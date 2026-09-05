/// Widget-free helpers of the single image viewer.
///
/// This library holds the arithmetic of the image viewport ([ImageTransform])
/// and the rating-filter-aware navigation over the images of an album
/// ([nextVisible] and friends), so that both can be unit-tested without pumping
/// a widget tree. It is the Dart port of the GWT client's `TXInfo` and of the
/// `navigate0`/`navigate1` methods of its `ImageDisplay`.
library;

import 'dart:math';

import 'package:flutter/widgets.dart' show Matrix4;

import 'album_layout.dart' show Orientations, ToImage;
import 'resource.dart';

/// The zoom and pan state of the image shown by the viewer.
///
/// All coordinates are page coordinates (pixels of the viewport the image is
/// shown in), the origin being the top left corner of the viewport. The image
/// is placed by scaling it by [scale] and translating it by [tx]/[ty], with the
/// [orientation] applied before that (so [width]/[height] are the dimensions
/// the image occupies on screen at a [scale] of `1.0`).
class ImageTransform {
  /// The orientation to apply to the raw image, see [Orientations].
  final Orientation orientation;

  /// The width of the image in raw pixels, before [orientation] is applied.
  final double rawWidth;

  /// The height of the image in raw pixels, before [orientation] is applied.
  final double rawHeight;

  /// The width of the viewport the image is displayed in.
  final double pageWidth;

  /// The height of the viewport the image is displayed in.
  final double pageHeight;

  /// The scale that fits the image into the viewport, see [reset].
  final double fitScale;

  /// The horizontal translation that centers the fitted image, see [reset].
  final double fitTx;

  /// The vertical translation that centers the fitted image, see [reset].
  final double fitTy;

  double _scale;
  double _tx;
  double _ty;
  bool _initial = true;

  ImageTransform._({
    required this.orientation,
    required this.rawWidth,
    required this.rawHeight,
    required this.pageWidth,
    required this.pageHeight,
    required this.fitScale,
    required this.fitTx,
    required this.fitTy,
  })  : _scale = fitScale,
        _tx = fitTx,
        _ty = fitTy;

  /// The transform showing the given image fitted into the given viewport.
  ///
  /// The image is scaled down to fit, but never scaled up beyond its original
  /// resolution, and it is centered in the viewport.
  factory ImageTransform.fit({
    Orientation orientation = Orientation.identity,
    required double rawWidth,
    required double rawHeight,
    required double pageWidth,
    required double pageHeight,
  }) {
    var width = Orientations.width(orientation, rawWidth, rawHeight);
    var height = Orientations.height(orientation, rawWidth, rawHeight);

    var scale = width <= 0 || height <= 0
        ? 1.0
        : min(pageWidth / width, pageHeight / height);
    if (scale > 1.0) {
      scale = 1.0;
    }

    return ImageTransform._(
      orientation: orientation,
      rawWidth: rawWidth,
      rawHeight: rawHeight,
      pageWidth: pageWidth,
      pageHeight: pageHeight,
      fitScale: scale,
      fitTx: (pageWidth - scale * width) / 2,
      fitTy: (pageHeight - scale * height) / 2,
    );
  }

  /// The transform for the given image part, fitted into the given viewport.
  factory ImageTransform.ofImage(
    ImagePart image, {
    required double pageWidth,
    required double pageHeight,
  }) =>
      ImageTransform.fit(
        orientation: image.orientation,
        rawWidth: image.width.toDouble(),
        rawHeight: image.height.toDouble(),
        pageWidth: pageWidth,
        pageHeight: pageHeight,
      );

  /// The width of the image in image pixels after [orientation] was applied.
  double get width => Orientations.width(orientation, rawWidth, rawHeight);

  /// The height of the image in image pixels after [orientation] was applied.
  double get height => Orientations.height(orientation, rawWidth, rawHeight);

  /// The scale currently applied, `1.0` meaning a pixel-by-pixel display.
  double get scale => _scale;

  /// The horizontal translation currently applied.
  double get tx => _tx;

  /// The vertical translation currently applied.
  double get ty => _ty;

  /// Whether the image is shown fitted and centered, see [reset].
  bool get isInitial => _initial;

  /// Applies a custom zoom and pan, see [isInitial].
  void setCustom(double tx, double ty, double scale) {
    _tx = tx;
    _ty = ty;
    _scale = scale;
    _initial = false;
  }

  /// Shows the image fitted into the viewport and centered.
  void reset() {
    _tx = fitTx;
    _ty = fitTy;
    _scale = fitScale;
    _initial = true;
  }

  /// Zooms by 20% around the given position in page coordinates.
  ///
  /// A positive [direction] zooms in, a negative one zooms out. Zooming out to
  /// (or below) the fitted scale snaps back to the fitted state, see [reset].
  void zoom(double direction, double posX, double posY) {
    if (direction == 0) {
      return;
    }
    var newScale = _scale * (1 + direction.sign * 0.2);
    if (newScale <= fitScale) {
      reset();
      return;
    }

    // The point of the image that should stay under the given position.
    var imgX = (posX - _tx) / _scale;
    var imgY = (posY - _ty) / _scale;

    var newTx = posX - imgX * newScale;
    var newTy = posY - imgY * newScale;

    if (direction < 0) {
      // When zooming out, distribute the empty space evenly, so that the image
      // approaches its centered position as the fitted scale is reached again.
      newTx = _distribute(newTx, width * newScale, pageWidth);
      newTy = _distribute(newTy, height * newScale, pageHeight);
    }

    setCustom(newTx, newTy, newScale);
  }

  /// Toggles between the fitted state and a pixel-by-pixel display anchored at
  /// the given position in page coordinates.
  void toggle(double posX, double posY) {
    if (!_initial) {
      reset();
      return;
    }

    const newScale = 1.0;
    var imgX = (posX - _tx) / _scale;
    var imgY = (posY - _ty) / _scale;
    setCustom(posX - imgX * newScale, posY - imgY * newScale, newScale);
  }

  /// Shifts the translation so that empty space is distributed evenly.
  ///
  /// [t] is the translation of the image along one axis, [size] its size along
  /// that axis and [pageSize] the size of the viewport along that axis. The
  /// shift is limited so that a side that was filled before does not become
  /// empty.
  static double _distribute(double t, double size, double pageSize) {
    var paddingStart = t;
    var paddingEnd = pageSize - size - t;
    if (paddingStart <= 0 && paddingEnd <= 0) {
      return t;
    }

    var shift = (paddingEnd - paddingStart) / 2;
    var amount = min(shift.abs(), max(paddingStart, paddingEnd));
    return t + amount * shift.sign;
  }

  /// The transformation mapping the raw image onto the viewport.
  ///
  /// It maps the rectangle `(0, 0)` to `(`[rawWidth]`, `[rawHeight]`)` to the
  /// place the image occupies on screen, applying [orientation], [scale],
  /// [tx] and [ty]. Apply it to a box of the raw image size, anchored at the
  /// top left corner of the viewport.
  Matrix4 get matrix {
    var orient = orientationMatrix;
    return _affine(
      orient.entry(0, 0) * _scale,
      orient.entry(0, 1) * _scale,
      orient.entry(1, 0) * _scale,
      orient.entry(1, 1) * _scale,
      orient.entry(0, 3) * _scale + _tx,
      orient.entry(1, 3) * _scale + _ty,
    );
  }

  /// The transformation applying [orientation] to the raw image.
  ///
  /// It maps the raw image onto the rectangle `(0, 0)` to
  /// `(`[width]`, `[height]`)`.
  Matrix4 get orientationMatrix =>
      orientationTransform(orientation, rawWidth, rawHeight);

  /// The transformation applying the given [Orientation] to an image of the
  /// given raw size, see [orientationMatrix].
  static Matrix4 orientationTransform(
    Orientation orientation,
    double rawWidth,
    double rawHeight,
  ) {
    // x' = a * x + b * y + e, y' = c * x + d * y + f.
    double a, b, c, d, e, f;
    switch (orientation) {
      case Orientation.identity:
        a = 1;
        b = 0;
        c = 0;
        d = 1;
        e = 0;
        f = 0;
        break;
      case Orientation.flipH:
        a = -1;
        b = 0;
        c = 0;
        d = 1;
        e = rawWidth;
        f = 0;
        break;
      case Orientation.rot180:
        a = -1;
        b = 0;
        c = 0;
        d = -1;
        e = rawWidth;
        f = rawHeight;
        break;
      case Orientation.flipV:
        a = 1;
        b = 0;
        c = 0;
        d = -1;
        e = 0;
        f = rawHeight;
        break;
      case Orientation.rotLFlipV:
        // Rotate left, then mirror vertically: the transposition.
        a = 0;
        b = 1;
        c = 1;
        d = 0;
        e = 0;
        f = 0;
        break;
      case Orientation.rotL:
        // (x, y) -> (y, rawWidth - x).
        a = 0;
        b = 1;
        c = -1;
        d = 0;
        e = 0;
        f = rawWidth;
        break;
      case Orientation.rotLFlipH:
        // Rotate left, then mirror horizontally.
        a = 0;
        b = -1;
        c = -1;
        d = 0;
        e = rawHeight;
        f = rawWidth;
        break;
      case Orientation.rotR:
        // (x, y) -> (rawHeight - y, x).
        a = 0;
        b = -1;
        c = 1;
        d = 0;
        e = rawHeight;
        f = 0;
        break;
    }

    return _affine(a, b, c, d, e, f);
  }

  /// The [Matrix4] of the affine transformation
  /// `x' = a * x + b * y + e`, `y' = c * x + d * y + f`.
  static Matrix4 _affine(
    double a,
    double b,
    double c,
    double d,
    double e,
    double f,
  ) {
    var result = Matrix4.identity();
    result.setEntry(0, 0, a);
    result.setEntry(0, 1, b);
    result.setEntry(0, 3, e);
    result.setEntry(1, 0, c);
    result.setEntry(1, 1, d);
    result.setEntry(1, 3, f);
    return result;
  }
}

/// The rating of the given image, that of its representative for a group.
int ratingOf(AbstractImage image) => ToImage.toImage(image).rating;

/// Whether the given image passes the album's rating filter.
bool isVisible(AbstractImage image, int minRating) =>
    ratingOf(image) >= minRating;

/// The next image passing the rating filter, `null` if there is none.
AbstractImage? nextVisible(AbstractImage image, int minRating) =>
    _navigate(image, minRating, (current) => current.next);

/// The previous image passing the rating filter, `null` if there is none.
AbstractImage? previousVisible(AbstractImage image, int minRating) =>
    _navigate(image, minRating, (current) => current.previous);

/// The first image of the album passing the rating filter.
///
/// Starts at [home] itself (the first image of the album, see [homeOf]) and
/// steps forwards until an image passes the filter.
AbstractImage? firstVisible(AbstractImage home, int minRating) =>
    isVisible(home, minRating) ? home : nextVisible(home, minRating);

/// The last image of the album passing the rating filter.
///
/// Starts at [end] itself (the last image of the album, see [endOf]) and steps
/// backwards until an image passes the filter.
AbstractImage? lastVisible(AbstractImage end, int minRating) =>
    isVisible(end, minRating) ? end : previousVisible(end, minRating);

/// The first image of the album the given image belongs to.
///
/// Uses [AbstractImage.home] if it was initialized, and walks the
/// [AbstractImage.previous] links otherwise.
AbstractImage homeOf(AbstractImage image) {
  var home = image.home;
  if (home != null) {
    return home;
  }
  var result = image;
  for (var previous = result.previous;
      previous != null;
      previous = result.previous) {
    result = previous;
  }
  return result;
}

/// The last image of the album the given image belongs to, see [homeOf].
AbstractImage endOf(AbstractImage image) {
  var end = image.end;
  if (end != null) {
    return end;
  }
  var result = image;
  for (var next = result.next; next != null; next = result.next) {
    result = next;
  }
  return result;
}

AbstractImage? _navigate(
  AbstractImage image,
  int minRating,
  AbstractImage? Function(AbstractImage current) step,
) {
  var current = image;
  while (true) {
    var next = step(current);
    if (next == null) {
      return null;
    }
    if (isVisible(next, minRating)) {
      return next;
    }
    current = next;
  }
}

/// Splits an [ImagePart.comment] into its paragraphs.
///
/// Paragraphs are separated by (whitespace-surrounded) line breaks, empty ones
/// are dropped.
List<String> commentParagraphs(String comment) => comment
    .split(RegExp(r"\s*\r?\n\s*"))
    .map((paragraph) => paragraph.trim())
    .where((paragraph) => paragraph.isNotEmpty)
    .toList();
