/// The edge scrolling of a drag (issue #42): the view scrolls while something
/// carried rests near the top or the bottom edge of its viewport.
///
/// Its own library since issue #139, because it is no longer the album's
/// alone: the face editor of issue #126 carries faces the same way and had
/// the same dead end — a group heading below the fold could not be reached
/// without dropping the face first.
library;

import 'dart:math';

import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

/// The band along the top and the bottom edge of the album's viewport in which
/// a carried tile makes the album scroll, in logical pixels (issue #42).
const double dragScrollZone = 64;

/// The share of the viewport the edge band may take at most.
///
/// A phone in landscape, or a small window, is not much higher than two
/// [dragScrollZone] bands; without this the two would meet in the middle and
/// there would be no place left to hold a tile still.
const double dragScrollZoneFraction = 0.25;

/// How fast the album scrolls while a tile is carried at the very edge of the
/// viewport, in logical pixels per second.
const double dragScrollMaxSpeed = 800;

/// Scrolls the album while a tile is carried near the top or the bottom edge
/// of its viewport (issue #42).
///
/// A drop target outside the viewport used to be out of reach: the album
/// stands still while a tile is carried, so it had to be scrolled to the right
/// place first, or the tile had to be dropped and picked up again. Resting the
/// pointer in a band of [dragScrollZone] along an edge now scrolls the album
/// that way, the faster the closer the pointer is to the edge — the behaviour
/// of the usual reorderable list.
///
/// It is driven by a [Ticker] rather than by the pointer moves, so that a
/// pointer that *rests* in the band keeps scrolling; the ticker runs only
/// while there is something to scroll and is stopped when the pointer leaves
/// the band, when the drag ends and when the album is disposed.
///
/// Flutter's own [EdgeDraggingAutoScroller] was the model but not the means:
/// it scrolls by how far a dragged *rectangle* sticks out of the viewport,
/// capped at 20 pixels of overdrag, which turns the speed curve flat over most
/// of a band and would have had to be faked with a rectangle around the
/// pointer.
class DragEdgeScroller {
  /// Called after every scroll step, so that the drag can look again at what
  /// is under the resting pointer (`_followScrolledContent` of the album and
  /// of the face editor).
  final VoidCallback? onScrolled;

  late final Ticker _ticker;

  /// The position scrolled while a tile is carried, `null` while none is.
  ScrollPosition? _position;

  /// Where the pointer of the drag was seen last, in global coordinates.
  Offset? _pointer;

  /// The tick the last step was computed for, to measure a step's duration.
  Duration _lastTick = Duration.zero;

  /// A scroll step smaller than this is no step at all: the end of the range.
  static const double _scrollEpsilon = 0.001;

  DragEdgeScroller(TickerProvider vsync, {this.onScrolled}) {
    _ticker = vsync.createTicker(_tick);
  }

  /// Whether the album is scrolling under the pointer right now.
  bool get scrolling => _ticker.isActive;

  /// Starts to watch the pointer of a drag that has just begun.
  ///
  /// [position] is the album's own scroll position, `null` if it has none —
  /// then nothing scrolls.
  void begin(ScrollPosition? position, Offset? pointer) {
    _position = position;
    _pointer = pointer;
    _startIfNeeded();
  }

  /// Follows the pointer of the drag in progress.
  void update(Offset pointer) {
    _pointer = pointer;
    _startIfNeeded();
  }

  /// Ends the scrolling of a drag that was dropped or cancelled.
  void end() {
    _stop();
    _position = null;
    _pointer = null;
  }

  void dispose() {
    _ticker.dispose();
  }

  void _startIfNeeded() {
    if (!_ticker.isActive && _speed() != 0) {
      _lastTick = Duration.zero;
      _ticker.start();
    }
  }

  void _stop() {
    if (_ticker.isActive) {
      _ticker.stop();
    }
  }

  void _tick(Duration elapsed) {
    var seconds =
        (elapsed - _lastTick).inMicroseconds / Duration.microsecondsPerSecond;
    _lastTick = elapsed;
    if (seconds <= 0) {
      // The first tick of a ticker starting within a frame.
      return;
    }
    var speed = _speed();
    if (speed == 0) {
      // The pointer has left the band, or there is nothing to scroll.
      _stop();
      return;
    }
    var position = _position!;
    var target = min(
      max(position.pixels + speed * seconds, position.minScrollExtent),
      position.maxScrollExtent,
    );
    if ((target - position.pixels).abs() < _scrollEpsilon) {
      // The end of the range: there is nothing more to scroll that way.
      _stop();
      return;
    }
    position.jumpTo(target);
    onScrolled?.call();
  }

  /// How fast and which way the album should scroll for the pointer's place,
  /// in logical pixels per second; zero while it rests outside both bands.
  ///
  /// The speed grows linearly with the proximity to the edge, from nothing at
  /// the inner boundary of the band to [dragScrollMaxSpeed] at the edge itself
  /// (and no faster beyond it, where a pointer dragged out of the album ends
  /// up).
  double _speed() {
    var position = _position;
    var pointer = _pointer;
    if (position == null || pointer == null || !position.hasContentDimensions) {
      return 0;
    }
    if (position.maxScrollExtent <= position.minScrollExtent) {
      // The album fits its viewport: there is nothing to scroll.
      return 0;
    }
    var box = position.context.storageContext.findRenderObject();
    if (box is! RenderBox || !box.hasSize) {
      return 0;
    }
    var height = box.size.height;
    var zone = min(dragScrollZone, height * dragScrollZoneFraction);
    if (zone <= 0) {
      return 0;
    }
    var depth = box.globalToLocal(pointer).dy;
    if (depth < zone) {
      return -dragScrollMaxSpeed * min((zone - depth) / zone, 1);
    }
    if (depth > height - zone) {
      return dragScrollMaxSpeed * min((depth - (height - zone)) / zone, 1);
    }
    return 0;
  }
}
