/// The crop editor of a photograph (issue #212).
///
/// The whole photograph is shown fitted, upright as the album shows it; the
/// region is a dashed rectangle over it, the outside dimmed. A drag outside
/// the rectangle draws a new one from where it went down, a drag inside moves
/// it, a corner or an edge resizes it — under the chosen constraint (the
/// picture's own ratio by default, free, or one of the common ratios with a
/// portrait/landscape toggle), always inside the picture. A tap inside the
/// rectangle, the Apply button or `Enter` applies; Cancel or `Escape` leaves
/// without a change; Reset takes the crop away.
///
/// One route, opened from the viewer's menu and from the context menu of an
/// edit-mode tile alike ([cropPhoto]), and written at once: the crop is a
/// `?action=crop` of its own, so an edit session holding unsaved changes is
/// neither saved along nor undone by it.
library;

import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';

import 'album_layout.dart' show Orientations;
import 'client.dart';
import 'crop.dart';
import 'l10n/app_localizations.dart';
import 'offline.dart';
import 'oriented_thumbnail.dart';
import 'resource.dart';

/// What the editor answers: the crop to store, `null` for the whole picture.
class CropEditorResult {
  /// The crop in the frame the picture is shown in, `null` for none.
  final Crop? crop;

  const CropEditorResult(this.crop);
}

/// How close to a handle a mouse has to come, in logical pixels.
const double _mouseHandleReach = 12;

/// How close to a handle a finger has to come, in logical pixels: a finger
/// is larger than the handle it means.
const double _touchHandleReach = 24;

/// How large the handle squares are drawn, in logical pixels.
const double _handleSquare = 10;

/// How small a drawn rectangle may be on the screen and still be one; below
/// it the drag was a slip and the rectangle stays as it was.
const double _minDrawn = 8;

/// Opens the editor on [image], whose whole rendition is fetched at
/// [imageUrl], and answers what was applied, `null` where it was cancelled.
Future<CropEditorResult?> openCropEditor(
  BuildContext context, {
  required VAlbumClient client,
  required String imageUrl,
  required ImagePart image,
}) =>
    Navigator.of(context).push<CropEditorResult>(
      MaterialPageRoute<CropEditorResult>(
        fullscreenDialog: true,
        builder: (context) =>
            CropEditor(client: client, imageUrl: imageUrl, image: image),
      ),
    );

/// The one way a crop is made: the editor, then the write (issue #212).
///
/// Written at once, wherever it is asked: refused offline with the usual
/// reason, a refusal of the server said in its own words and nothing
/// changed. What the server stored is put into [image] — the very part the
/// album shows, which inside an edit session is the buffer's copy, so a later
/// Save writes the same crop again and a Cancel, which fetches the album
/// anew, finds it stored. Where the photograph is the album's picture, the
/// framing the server measured anew on the cropped photograph is adopted into
/// [album] as well. Answers whether anything changed.
Future<bool> cropPhoto(
  BuildContext context, {
  required VAlbumClient client,
  required List<String> albumPath,
  required String imageUrl,
  required ImagePart image,
  AlbumInfo? album,
}) async {
  var result = await openCropEditor(
    context,
    client: client,
    imageUrl: imageUrl,
    image: image,
  );
  if (result == null || !context.mounted) {
    return false;
  }
  if (sameCrop(result.crop, effectiveCrop(image))) {
    return false;
  }
  if (refuseWhileOffline(context)) {
    return false;
  }
  var messenger = ScaffoldMessenger.of(context);
  try {
    var answer = await client.cropImage(albumPath, image.name, result.crop);
    var stored = result.crop;
    ThumbnailInfo? cover;
    if (answer != null) {
      for (var part in imagesOfAlbum(answer)) {
        if (part.name == image.name) {
          stored = part.crop;
        }
      }
      cover = answer.indexPicture;
    }
    image.crop = stored;
    if (album != null && cover != null && cover.image == image.name) {
      album.indexPicture = cover;
    }
    return true;
  } catch (error) {
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          error is VAlbumException ? error.message : "$error",
          key: const Key("crop-failed"),
        ),
        backgroundColor: Colors.red.shade700,
        duration: const Duration(seconds: 8),
      ),
    );
    return false;
  }
}

/// Every photograph of [album], the members of a group included.
List<ImagePart> imagesOfAlbum(AlbumInfo album) => [
      for (var part in album.parts)
        if (part is ImagePart) part else if (part is ImageGroup) ...part.images,
    ];

/// Whether two crops name the same region, `null` being the whole picture.
bool sameCrop(Crop? one, Crop? two) {
  if (isWholeCrop(one) || isWholeCrop(two)) {
    return isWholeCrop(one) && isWholeCrop(two);
  }
  const eps = 1e-6;
  return (one!.x - two!.x).abs() < eps &&
      (one.y - two.y).abs() < eps &&
      (one.w - two.w).abs() < eps &&
      (one.h - two.h).abs() < eps;
}

/// The crop editor, see the library documentation.
class CropEditor extends StatefulWidget {
  final VAlbumClient client;

  /// The address of the image; its whole rendition is what is shown.
  final String imageUrl;

  final ImagePart image;

  const CropEditor({
    super.key,
    required this.client,
    required this.imageUrl,
    required this.image,
  });

  @override
  State<CropEditor> createState() => CropEditorState();
}

/// What a drag on the picture does.
enum _Gesture { draw, move, resize }

class CropEditorState extends State<CropEditor> {
  /// The region, normalised in the shown picture.
  late Rect rect;

  /// The constraint.
  late CropAspect aspect;

  /// Whether a fixed ratio is meant upright.
  late bool portrait;

  _Gesture? _gesture;
  CropHandle? _handle;
  Rect? _startRect;
  Offset? _startPoint;

  /// The size of the picture as it is shown, in pixels of the file.
  late double shownWidth;
  late double shownHeight;

  /// The size the picture is drawn at on the screen, set by every layout.
  Size _drawn = Size.zero;

  /// The width-to-height ratio of the whole picture, in pixels.
  double get pictureRatio => shownWidth / shownHeight;

  /// The ratio the constraint asks, `null` for none.
  double? get ratio => targetRatio(aspect, portrait, pictureRatio);

  @override
  void initState() {
    super.initState();
    var image = widget.image;
    var width = image.width > 0 ? image.width.toDouble() : 1.0;
    var height = image.height > 0 ? image.height.toDouble() : 1.0;
    shownWidth = Orientations.width(image.orientation, width, height);
    shownHeight = Orientations.height(image.orientation, width, height);
    var crop = effectiveCrop(image);
    if (crop == null) {
      rect = const Rect.fromLTWH(0, 0, 1, 1);
      aspect = CropAspect.image;
      portrait = picturePortrait(pictureRatio);
      return;
    }
    rect = Rect.fromLTWH(crop.x, crop.y, crop.w, crop.h);
    var pixels = rect.width * shownWidth / (rect.height * shownHeight);
    portrait = pixels < 1;
    aspect = CropAspect.free;
    for (var candidate in CropAspect.values) {
      var wanted = targetRatio(candidate, portrait, pictureRatio);
      if (wanted != null && (wanted - pixels).abs() / wanted < 0.01) {
        aspect = candidate;
        break;
      }
    }
  }

  /// Whether there is a stored crop to take away.
  bool get hasCrop => effectiveCrop(widget.image) != null;

  /// Applies the rectangle: the crop, or none where it is the whole picture.
  void apply() {
    var crop = Crop(x: rect.left, y: rect.top, w: rect.width, h: rect.height);
    Navigator.of(context)
        .pop(CropEditorResult(isWholeCrop(crop) ? null : crop));
  }

  /// Leaves without a change.
  void cancel() => Navigator.of(context).pop();

  /// Takes the crop away.
  void reset() => Navigator.of(context).pop(const CropEditorResult(null));

  /// Chooses [value] as the constraint, bringing the rectangle to it.
  void chooseAspect(CropAspect value) {
    setState(() {
      aspect = value;
      if (value == CropAspect.image) {
        // The picture's own ratio, the way the picture stands.
        portrait = picturePortrait(pictureRatio);
      }
      var wanted = ratio;
      if (wanted != null) {
        rect = withRatio(rect, wanted, pictureRatio);
      }
    });
  }

  /// Turns the constraint between portrait and landscape.
  void toggleOrientation() {
    if (!aspect.turnable) {
      return;
    }
    setState(() {
      portrait = !portrait;
      var wanted = ratio;
      if (wanted != null) {
        rect = withRatio(rect, wanted, pictureRatio);
      }
    });
  }

  /// The point of the picture under [local], normalised (unclamped).
  Offset _normalised(Offset local) => Offset(
        _drawn.width > 0 ? local.dx / _drawn.width : 0,
        _drawn.height > 0 ? local.dy / _drawn.height : 0,
      );

  /// The rectangle on the screen.
  Rect get _screenRect => Rect.fromLTRB(
        rect.left * _drawn.width,
        rect.top * _drawn.height,
        rect.right * _drawn.width,
        rect.bottom * _drawn.height,
      );

  /// The handle under [local], `null` where there is none.
  CropHandle? handleAt(Offset local, PointerDeviceKind? kind) {
    var reach =
        kind == PointerDeviceKind.touch ? _touchHandleReach : _mouseHandleReach;
    var r = _screenRect;
    bool near(Offset p) => (p - local).distance <= reach;
    if (near(r.topLeft)) return CropHandle.topLeft;
    if (near(r.topRight)) return CropHandle.topRight;
    if (near(r.bottomLeft)) return CropHandle.bottomLeft;
    if (near(r.bottomRight)) return CropHandle.bottomRight;
    var withinX = local.dx > r.left && local.dx < r.right;
    var withinY = local.dy > r.top && local.dy < r.bottom;
    if (withinX && (local.dy - r.top).abs() <= reach) return CropHandle.top;
    if (withinX && (local.dy - r.bottom).abs() <= reach) {
      return CropHandle.bottom;
    }
    if (withinY && (local.dx - r.left).abs() <= reach) return CropHandle.left;
    if (withinY && (local.dx - r.right).abs() <= reach) {
      return CropHandle.right;
    }
    return null;
  }

  void _panStart(DragStartDetails details) {
    var local = details.localPosition;
    _startRect = rect;
    _startPoint = _normalised(local);
    _handle = handleAt(local, details.kind);
    if (_handle != null) {
      _gesture = _Gesture.resize;
    } else if (_screenRect.contains(local) &&
        !isWholeCrop(
            Crop(x: rect.left, y: rect.top, w: rect.width, h: rect.height))) {
      // A frame of the whole picture has nowhere to move: a drag inside it
      // draws a new one, as a drag outside a smaller frame does.
      _gesture = _Gesture.move;
    } else {
      _gesture = _Gesture.draw;
    }
  }

  void _panUpdate(DragUpdateDetails details) {
    var start = _startRect;
    var from = _startPoint;
    if (start == null || from == null) {
      return;
    }
    var point = _normalised(details.localPosition);
    setState(() {
      switch (_gesture) {
        case _Gesture.move:
          rect = movedCrop(start, point - from);
        case _Gesture.resize:
          rect = resizedCrop(start, _handle!, point, ratio, pictureRatio);
        case _Gesture.draw:
          rect = drawnCrop(from, point, ratio, pictureRatio);
        case null:
          break;
      }
    });
  }

  void _panEnd() {
    if (_gesture == _Gesture.draw) {
      var drawn = _screenRect;
      if (drawn.width < _minDrawn || drawn.height < _minDrawn) {
        // A slip, not a rectangle: the one there was stays.
        setState(() => rect = _startRect ?? rect);
      }
    }
    _gesture = null;
    _handle = null;
    _startRect = null;
    _startPoint = null;
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return CallbackShortcuts(
      bindings: <ShortcutActivator, VoidCallback>{
        const SingleActivator(LogicalKeyboardKey.enter): apply,
        const SingleActivator(LogicalKeyboardKey.numpadEnter): apply,
        const SingleActivator(LogicalKeyboardKey.escape): cancel,
      },
      child: Focus(
        autofocus: true,
        child: Scaffold(
          key: const Key("crop-editor"),
          backgroundColor: Colors.black,
          appBar: AppBar(
            backgroundColor: Colors.black,
            foregroundColor: Colors.white,
            leading: IconButton(
              key: const Key("crop-cancel"),
              icon: const Icon(Icons.close),
              tooltip: l10n.cancel,
              onPressed: cancel,
            ),
            title: Text(l10n.cropTitle),
            actions: [
              if (hasCrop)
                TextButton(
                  key: const Key("crop-reset"),
                  onPressed: reset,
                  child: Text(l10n.cropReset),
                ),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: FilledButton(
                  key: const Key("crop-apply"),
                  onPressed: apply,
                  child: Text(l10n.apply),
                ),
              ),
            ],
          ),
          body: SafeArea(
            child: Column(
              children: [
                Expanded(child: buildPicture(context)),
                buildControls(context, l10n),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// The picture, fitted, with the rectangle over it.
  Widget buildPicture(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          const margin = 24.0;
          var width = math.max(1.0, constraints.maxWidth - 2 * margin);
          var height = math.max(1.0, constraints.maxHeight - 2 * margin);
          var scale = math.min(width / shownWidth, height / shownHeight);
          _drawn = Size(shownWidth * scale, shownHeight * scale);
          var image = widget.image;
          return Center(
            child: SizedBox(
              width: _drawn.width,
              height: _drawn.height,
              child: Stack(
                children: [
                  Positioned.fill(
                    child: orientedThumbnail(
                      widget.client,
                      widget.imageUrl,
                      key: const Key("crop-picture"),
                      orientation: image.orientation,
                      rawWidth: image.width.toDouble(),
                      rawHeight: image.height.toDouble(),
                      width: _drawn.width,
                      height: _drawn.height,
                    ),
                  ),
                  Positioned.fill(
                    child: Semantics(
                      label: AppLocalizations.of(context)!.cropAreaHint,
                      child: GestureDetector(
                        key: const Key("crop-area"),
                        behavior: HitTestBehavior.opaque,
                        dragStartBehavior: DragStartBehavior.down,
                        onPanStart: _panStart,
                        onPanUpdate: _panUpdate,
                        onPanEnd: (_) => _panEnd(),
                        onPanCancel: _panEnd,
                        onTapUp: (details) {
                          if (_screenRect.contains(details.localPosition)) {
                            apply();
                          }
                        },
                        child: CustomPaint(
                          painter: CropPainter(_screenRect),
                          child: const SizedBox.expand(),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      );

  /// The constraints, the toggle and the note.
  Widget buildControls(BuildContext context, AppLocalizations l10n) {
    String label(CropAspect value) => switch (value) {
          CropAspect.image => l10n.cropAspectImage,
          CropAspect.free => l10n.cropAspectFree,
          CropAspect.square => "1:1",
          CropAspect.fourThree => portrait ? "3:4" : "4:3",
          CropAspect.threeTwo => portrait ? "2:3" : "3:2",
          CropAspect.sixteenNine => portrait ? "9:16" : "16:9",
          CropAspect.fiveFour => portrait ? "4:5" : "5:4",
        };
    return Container(
      color: Colors.grey.shade900,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: [
                    for (var value in CropAspect.values)
                      ChoiceChip(
                        key: Key("crop-aspect-${value.name}"),
                        label: Text(label(value)),
                        selected: aspect == value,
                        onSelected: (_) => chooseAspect(value),
                      ),
                  ],
                ),
              ),
              IconButton(
                key: const Key("crop-orientation"),
                color: Colors.white,
                disabledColor: Colors.white24,
                icon: Icon(
                  portrait ? Icons.crop_portrait : Icons.crop_landscape,
                ),
                // Says what the tap does.
                tooltip: portrait ? l10n.cropLandscape : l10n.cropPortrait,
                onPressed: aspect.turnable ? toggleOrientation : null,
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              l10n.cropNote,
              key: const Key("crop-note"),
              style: const TextStyle(color: Colors.white70, fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

/// Paints the dimmed outside, the dashed rectangle and its handles.
class CropPainter extends CustomPainter {
  /// The rectangle in the painter's coordinates.
  final Rect rect;

  CropPainter(this.rect);

  @override
  void paint(Canvas canvas, Size size) {
    var outside = Path()
      ..fillType = PathFillType.evenOdd
      ..addRect(Offset.zero & size)
      ..addRect(rect);
    canvas.drawPath(outside, Paint()..color = Colors.black54);

    var dark = Paint()
      ..color = Colors.black
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    var light = Paint()
      ..color = Colors.white
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    canvas.drawRect(rect, dark);
    _dashed(canvas, rect.topLeft, rect.topRight, light);
    _dashed(canvas, rect.topRight, rect.bottomRight, light);
    _dashed(canvas, rect.bottomRight, rect.bottomLeft, light);
    _dashed(canvas, rect.bottomLeft, rect.topLeft, light);

    var fill = Paint()..color = Colors.white;
    var border = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke;
    for (var point in [
      rect.topLeft,
      rect.topCenter,
      rect.topRight,
      rect.centerRight,
      rect.bottomRight,
      rect.bottomCenter,
      rect.bottomLeft,
      rect.centerLeft,
    ]) {
      var square = Rect.fromCenter(
        center: point,
        width: _handleSquare,
        height: _handleSquare,
      );
      canvas.drawRect(square, fill);
      canvas.drawRect(square, border);
    }
  }

  static void _dashed(Canvas canvas, Offset from, Offset to, Paint paint) {
    const dash = 6.0;
    var length = (to - from).distance;
    if (length <= 0) {
      return;
    }
    var step = (to - from) / length;
    for (var at = 0.0; at < length; at += 2 * dash) {
      var end = math.min(at + dash, length);
      canvas.drawLine(from + step * at, from + step * end, paint);
    }
  }

  @override
  bool shouldRepaint(CropPainter oldDelegate) => oldDelegate.rect != rect;
}
