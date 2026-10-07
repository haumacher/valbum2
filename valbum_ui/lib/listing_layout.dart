/// The grid the tiles of a folder listing are laid out in, with the starred
/// entries twice as wide and twice as high as the others, see issue #239.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Where one tile of a listing lies, in cells of the grid.
///
/// A plain tile takes one cell; a starred one takes a block of 2×2, see
/// [tileCells].
@immutable
class TileCell {
  /// The row of the tile's top left cell, counted from `0`.
  final int row;

  /// The column of the tile's top left cell, counted from `0`.
  final int column;

  /// How many columns the tile spans.
  final int width;

  /// How many rows the tile spans.
  final int height;

  const TileCell(this.row, this.column, [this.width = 1, this.height = 1]);

  /// Whether this is the tile of a starred entry, drawn larger.
  bool get isLarge => width > 1 || height > 1;

  @override
  bool operator ==(Object other) =>
      other is TileCell &&
      other.row == row &&
      other.column == column &&
      other.width == width &&
      other.height == height;

  @override
  int get hashCode => Object.hash(row, column, width, height);

  @override
  String toString() => width == 1 && height == 1
      ? "($row,$column)"
      : "($row,$column,${width}x$height)";
}

/// Where each tile of a listing lies in a grid of [columns] columns, in the
/// order the tiles are given, a starred one ([starred]) as a block of 2×2.
///
/// The tiles are given in reading order -- the date order of the listing --
/// and they stay in it as far as a grid allows:
///
/// * A tile takes the first free cell, in reading order.
/// * A starred tile takes the first free 2×2 block from there. Where the rest
///   of the row has no room for it, it starts the next row, and the cells it
///   leaves free are taken by the *following* plain tiles.
/// * A plain tile never stands more than one row above a starred tile before
///   it: a free cell further up stays free. (Three columns and two starred
///   tiles in a row would otherwise pull every later tile up into the gaps
///   beside them, across the whole year.)
///
/// So nothing moves by more than one row: plain tiles keep their order among
/// themselves, starred ones theirs, and a plain tile moves ahead only of
/// starred tiles, by one row at most.
/// * With fewer than two columns there is no room for a block: a starred
///   tile takes one cell like any other (it is still drawn as starred).
///
/// With two columns -- a phone -- a starred tile is as wide as the screen and
/// twice as high as a plain one.
///
/// A pure function of its arguments, so that the rule can be pinned cell by
/// cell, see `test/listing_layout_test.dart`.
List<TileCell> tileCells(List<bool> starred, int columns) {
  var cols = math.max(1, columns);
  var span = cols >= 2 ? 2 : 1;
  var taken = <int>{};
  bool free(int row, int column) => !taken.contains(row * cols + column);
  // The earliest free cell, in reading order.
  var first = 0;
  // The earliest cell a plain tile may take: one row above the last starred
  // tile, so that no tile moves ahead of a starred one by more than a row.
  var floor = 0;
  var result = <TileCell>[];
  for (var large in starred) {
    while (taken.contains(first)) {
      first++;
    }
    if (!large || span == 1) {
      var at = math.max(first, floor);
      while (taken.contains(at)) {
        at++;
      }
      taken.add(at);
      result.add(TileCell(at ~/ cols, at % cols));
      continue;
    }
    var at = first;
    while (true) {
      var row = at ~/ cols;
      var column = at % cols;
      if (column + span <= cols &&
          free(row, column) &&
          free(row, column + 1) &&
          free(row + 1, column) &&
          free(row + 1, column + 1)) {
        break;
      }
      at++;
    }
    var row = at ~/ cols;
    var column = at % cols;
    for (var r = row; r < row + span; r++) {
      for (var c = column; c < column + span; c++) {
        taken.add(r * cols + c);
      }
    }
    result.add(TileCell(row, column, span, span));
    floor = math.max(floor, (row - 1) * cols);
  }
  return result;
}

/// Lays out its [children] in the [cells] given for them, [cellWidth] wide
/// each, see [tileCells].
///
/// Each child is as wide as the cells it spans and as high as it needs to be.
/// A row is as high as its highest plain tile, as the rows of the `Wrap` the
/// listing used before were; a starred tile spans two rows, and where it needs
/// more than the two together, the lower one grows by what is missing. The
/// children are hit-tested and read in the order given, which is the date
/// order of the listing, wherever the grid has placed them.
class TileGrid extends MultiChildRenderObjectWidget {
  /// Where each child lies, one entry per child.
  final List<TileCell> cells;

  /// The width of one column.
  final double cellWidth;

  const TileGrid({
    super.key,
    required this.cells,
    required this.cellWidth,
    required super.children,
  }) : assert(cells.length == children.length);

  @override
  RenderTileGrid createRenderObject(BuildContext context) =>
      RenderTileGrid(cells: cells, cellWidth: cellWidth);

  @override
  void updateRenderObject(BuildContext context, RenderTileGrid renderObject) {
    renderObject
      ..cells = cells
      ..cellWidth = cellWidth;
  }
}

/// The parent data of a child of a [RenderTileGrid].
class TileGridParentData extends ContainerBoxParentData<RenderBox> {}

/// The render object of a [TileGrid].
class RenderTileGrid extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, TileGridParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, TileGridParentData> {
  RenderTileGrid({required List<TileCell> cells, required double cellWidth})
      : _cells = cells,
        _cellWidth = cellWidth;

  List<TileCell> _cells;

  List<TileCell> get cells => _cells;

  set cells(List<TileCell> value) {
    if (listEquals(_cells, value)) {
      return;
    }
    _cells = value;
    markNeedsLayout();
  }

  double _cellWidth;

  double get cellWidth => _cellWidth;

  set cellWidth(double value) {
    if (_cellWidth == value) {
      return;
    }
    _cellWidth = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! TileGridParentData) {
      child.parentData = TileGridParentData();
    }
  }

  @override
  void performLayout() {
    var children = getChildrenAsList();
    var rows = 0;
    var columns = 0;
    for (var cell in _cells) {
      rows = math.max(rows, cell.row + cell.height);
      columns = math.max(columns, cell.column + cell.width);
    }
    var heights = List<double>.filled(rows, 0);
    var large = <int>[];
    for (var n = 0; n < children.length; n++) {
      var cell = _cells[n];
      var child = children[n];
      child.layout(
        BoxConstraints.tightFor(width: cell.width * _cellWidth),
        parentUsesSize: true,
      );
      if (cell.height == 1) {
        heights[cell.row] = math.max(heights[cell.row], child.size.height);
      } else {
        large.add(n);
      }
    }
    // Top down, so that a block below another one sees what the upper one
    // added to the row they share.
    large.sort((a, b) => _cells[a].row.compareTo(_cells[b].row));
    for (var n in large) {
      var cell = _cells[n];
      var spanned = 0.0;
      for (var row = cell.row; row < cell.row + cell.height; row++) {
        spanned += heights[row];
      }
      var missing = children[n].size.height - spanned;
      if (missing > 0) {
        heights[cell.row + cell.height - 1] += missing;
      }
    }
    var tops = List<double>.filled(rows + 1, 0);
    for (var row = 0; row < rows; row++) {
      tops[row + 1] = tops[row] + heights[row];
    }
    for (var n = 0; n < children.length; n++) {
      var cell = _cells[n];
      (children[n].parentData as TileGridParentData).offset =
          Offset(cell.column * _cellWidth, tops[cell.row]);
    }
    var width = constraints.hasBoundedWidth
        ? constraints.maxWidth
        : columns * _cellWidth;
    size = constraints.constrain(Size(width, tops[rows]));
  }

  @override
  void paint(PaintingContext context, Offset offset) =>
      defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}
