/// The way back from a viewer opened by its address (issue #272): the album
/// beneath it, never shown before, scrolls to the photo the viewer showed
/// last.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart' show RenderSliver;
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import 'album_layout.dart' as layouter;
import 'app.dart';
import 'resource.dart';
import 'routes.dart';

/// How many frames a reveal waits for the album to be laid out, see
/// [ReturnReveal].
const int _revealFrames = 30;

/// How long the photo is held in place after the reveal, see
/// [ReturnReveal._hold].
const Duration _revealHold = Duration(seconds: 1);

/// The page of an album (or of the results of a search) that, coming up from
/// a viewer it had never been shown under, scrolls to the photo last shown
/// there (issue #272).
///
/// The album stays mounted beneath its viewer (issue #93). Where the user
/// descended from it, it was on the screen and is found exactly as it was
/// left: its offset is the user's, nothing moves. Where the viewer was opened
/// by its address — a reload, a bookmark, a link, an entry of the bell or of
/// the browser's history landing on the photo — the album was built beneath
/// it without ever being the page on top, and the way back would land at the
/// album's top (or at an offset remembered from long before), far from the
/// photo the user was looking at. Such an album, the first time it becomes
/// the page on top, scrolls so that the tile of that photo — the one paged
/// to, the group of a member shown — stands in the middle of the screen, or
/// as near as the ends of the album let it.
///
/// The page records the photo while it is beneath ([watchReturn], from
/// `didChangeDependencies`: the route and whether the page is the current
/// one are both dependencies). The rows are laid out in blocks between the
/// headings; each block's sliver carries the key [rowBlock] gives it, so
/// that the scroll offset of a row is the block's place in the scroll view
/// plus the extents of the rows above it in the block — the extents the
/// scroll view itself is told, see `AlbumContentState.buildSlivers`. The
/// jump happens right after the first frame the page is on top, which the
/// viewer popping over it still covers: no animation, no flicker.
mixin ReturnReveal<T extends StatefulWidget> on State<T> {
  /// The view the page is shown in: its route and its state path.
  VAlbumState get revealView;

  /// Whether the page has been the page on top.
  bool _shown = false;

  /// The names of the photo the viewer above shows (the shown one first),
  /// recorded while the page has never been on top.
  List<String>? _shownAbove;

  /// The blocks of rows of the last layout, see [rowBlock].
  final List<_RowBlock> _blocks = [];

  /// The key of every block of rows, by its index; kept, so that a block is
  /// the same sliver across layouts.
  final List<GlobalKey> _blockKeys = [];

  /// Records the photo the viewer above shows, or scrolls to it the first
  /// time the page is on top, see [ReturnReveal].
  void watchReturn() {
    if (_shown) {
      return;
    }
    var route = VAlbumNavigator.of(context).route;
    if (!(ModalRoute.isCurrentOf(context) ?? true)) {
      var above = _photoAbove(route);
      if (above != null) {
        _shownAbove = above;
      }
      return;
    }
    _shown = true;
    var names = _shownAbove;
    _shownAbove = null;
    if (names != null) {
      _revealLater(names, _revealFrames);
    }
  }

  /// The names of the photo [route] shows above this page, `null` where it
  /// shows none of this page's photos.
  List<String>? _photoAbove(VAlbumRoute route) {
    if (route.fromDuplicates ||
        !listEquals(
          VAlbumRouterDelegate.statePathOf(route),
          revealView.statePath,
        )) {
      return null;
    }
    return switch (route) {
      ImageRoute(:var name) => [name],
      AlternativesRoute(:var name) => [name],
      MemberRoute(:var name, :var member) => [member, name],
      _ => null,
    };
  }

  /// Forgets the blocks of the previous layout; called first by the layout.
  void beginRowBlocks() => _blocks.clear();

  /// The key of the sliver of the next block of [rows], see [ReturnReveal]:
  /// [extentOf] is the extent of a row as the scroll view is told it, the
  /// gap above it ([topGap]) included.
  Key rowBlock(
    List<layouter.Row> rows,
    double Function(int index) extentOf,
    double Function(int index) topGap,
  ) {
    var index = _blocks.length;
    while (_blockKeys.length <= index) {
      _blockKeys.add(GlobalKey(debugLabel: "row-block-${_blockKeys.length}"));
    }
    var key = _blockKeys[index];
    _blocks.add(_RowBlock(key, rows, extentOf, topGap));
    return key;
  }

  void _revealLater(List<String> names, int frames) {
    SchedulerBinding.instance.addPostFrameCallback((timeStamp) {
      if (!mounted) {
        return;
      }
      var (:laidOut, :position) = _reveal(names);
      if (position != null) {
        _hold(names, position, timeStamp + _revealHold);
      } else if (!laidOut && frames > 0) {
        // Not laid out yet: the page is about to come up from beneath.
        _revealLater(names, frames - 1);
        SchedulerBinding.instance.scheduleFrame();
      }
    });
  }

  /// Keeps the photo where [_reveal] put it while what stands above it is
  /// still settling — the criteria of a search, laid out for the first time
  /// with the page, fill in what they load a few frames later — until
  /// [until], or until the page is scrolled by anybody else.
  void _hold(List<String> names, ScrollPosition position, Duration until) {
    var pixels = position.pixels;
    SchedulerBinding.instance.addPostFrameCallback((timeStamp) {
      if (!mounted || position.pixels != pixels || timeStamp > until) {
        return;
      }
      var again = _reveal(names).position;
      if (again != null) {
        _hold(names, again, until);
      }
    });
  }

  /// Scrolls to the tile of the photo named [names]; whether the layout was
  /// there to do so, and the position scrolled where it was — none where the
  /// album does not show the photo, which is no reason to wait either.
  ({bool laidOut, ScrollPosition? position}) _reveal(List<String> names) {
    for (var block in _blocks) {
      var row = block.rowOf(names);
      if (row < 0) {
        continue;
      }
      var context = block.key.currentContext;
      var sliver = context?.findRenderObject();
      if (context == null ||
          sliver is! RenderSliver ||
          !sliver.attached ||
          sliver.geometry == null) {
        return (laidOut: false, position: null);
      }
      var position =
          context.findAncestorStateOfType<ScrollableState>()?.position;
      if (position == null ||
          !position.hasContentDimensions ||
          !position.hasViewportDimension) {
        return (laidOut: false, position: null);
      }
      var top = sliver.constraints.precedingScrollExtent +
          block.startOf(row) +
          block.topGap(row);
      var extent = block.extentOf(row) - block.topGap(row);
      var target = top + extent / 2 - position.viewportDimension / 2;
      var clamped =
          target.clamp(position.minScrollExtent, position.maxScrollExtent);
      if (clamped != position.pixels) {
        position.jumpTo(clamped);
      }
      return (laidOut: true, position: position);
    }
    return (laidOut: true, position: null);
  }
}

/// One block of rows between two headings, see [ReturnReveal.rowBlock].
class _RowBlock {
  final GlobalKey key;
  final List<layouter.Row> rows;
  final double Function(int index) extentOf;
  final double Function(int index) topGap;

  _RowBlock(this.key, this.rows, this.extentOf, this.topGap);

  /// The index of the row showing the photo named by one of [names], `-1`
  /// where none does.
  int rowOf(List<String> names) {
    for (var index = 0; index < rows.length; index++) {
      var collector = layouter.Collector();
      rows[index].visit(collector, null);
      for (var image in collector.getImages()) {
        if (_shows(image, names)) {
          return index;
        }
      }
    }
    return -1;
  }

  /// Where the row at [index] starts within the block.
  double startOf(int index) {
    var start = 0.0;
    for (var above = 0; above < index; above++) {
      start += extentOf(above);
    }
    return start;
  }

  static bool _shows(AbstractImage image, List<String> names) =>
      switch (image) {
        ImagePart(:var name) => names.contains(name),
        ImageGroup(:var images) =>
          images.any((member) => names.contains(member.name)),
        _ => false,
      };
}
