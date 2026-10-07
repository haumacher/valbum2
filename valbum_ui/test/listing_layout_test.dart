/// The grid of a folder listing with its starred entries 2×2 (issue #239),
/// pinned cell by cell: [tileCells] is a pure function of which tiles are
/// starred and how many columns there are.
library;

import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/listing_layout.dart';

/// A plain tile.
const s = false;

/// A starred tile.
const b = true;

/// A starred tile's block at [row], [column].
TileCell big(int row, int column) => TileCell(row, column, 2, 2);

void main() {
  group('without a star', () {
    test('the tiles fill the rows in reading order', () {
      expect(tileCells([s, s, s, s, s, s], 4), const [
        TileCell(0, 0), TileCell(0, 1), TileCell(0, 2), TileCell(0, 3), //
        TileCell(1, 0), TileCell(1, 1),
      ]);
    });
  });

  group('a starred tile at the end of a row', () {
    test('2 columns: it starts the next row, full width', () {
      expect(tileCells([s, b, s, s], 2), [
        const TileCell(0, 0),
        big(1, 0),
        // The gap it left, filled by the tile after it.
        const TileCell(0, 1),
        const TileCell(3, 0),
      ]);
    });

    test('3 columns: it starts the next row, the followers fill the gap', () {
      expect(tileCells([s, s, b, s, s, s], 3), [
        const TileCell(0, 0),
        const TileCell(0, 1),
        big(1, 0),
        const TileCell(0, 2),
        const TileCell(1, 2),
        const TileCell(2, 2),
      ]);
    });

    test('4 columns', () {
      expect(tileCells([s, s, s, b, s, s], 4), [
        const TileCell(0, 0),
        const TileCell(0, 1),
        const TileCell(0, 2),
        big(1, 0),
        const TileCell(0, 3),
        const TileCell(1, 2),
      ]);
    });

    test('5 columns', () {
      expect(tileCells([s, s, s, s, b, s, s, s], 5), [
        const TileCell(0, 0),
        const TileCell(0, 1),
        const TileCell(0, 2),
        const TileCell(0, 3),
        big(1, 0),
        const TileCell(0, 4),
        const TileCell(1, 2),
        const TileCell(1, 3),
      ]);
    });

    test('7 columns', () {
      expect(tileCells([s, s, s, s, s, s, b, s, s], 7), [
        for (var column = 0; column < 6; column++) TileCell(0, column),
        big(1, 0),
        const TileCell(0, 6),
        const TileCell(1, 2),
      ]);
    });

    test('with nothing after it the gap stays', () {
      expect(tileCells([s, s, b], 3), [
        const TileCell(0, 0),
        const TileCell(0, 1),
        big(1, 0),
      ]);
    });
  });

  group('two starred tiles in a row', () {
    test('2 columns: one under the other', () {
      expect(tileCells([b, b, s], 2), [big(0, 0), big(2, 0), const TileCell(4, 0)]);
    });

    test('3 columns: the second starts below the first; no tile moves '
        'ahead of it by more than a row', () {
      expect(tileCells([b, b, s, s], 3), [
        big(0, 0),
        big(2, 0),
        // (0,2) stays free: a tile there would stand two rows ahead of the
        // starred tile it follows.
        const TileCell(1, 2),
        const TileCell(2, 2),
      ]);
    });

    test('4 columns: side by side', () {
      expect(tileCells([b, b, s], 4), [big(0, 0), big(0, 2), const TileCell(2, 0)]);
    });

    test('5 columns', () {
      expect(tileCells([b, b, s, s, s], 5), [
        big(0, 0),
        big(0, 2),
        const TileCell(0, 4),
        const TileCell(1, 4),
        const TileCell(2, 0),
      ]);
    });

    test('7 columns', () {
      expect(tileCells([b, b, b, s], 7), [
        big(0, 0),
        big(0, 2),
        big(0, 4),
        const TileCell(0, 6),
      ]);
    });
  });

  group('a starred tile first and last', () {
    test('2 columns', () {
      expect(tileCells([b, s, s, b], 2), [
        big(0, 0),
        const TileCell(2, 0),
        const TileCell(2, 1),
        big(3, 0),
      ]);
    });

    test('3 columns', () {
      expect(tileCells([b, s, s, s, b], 3), [
        big(0, 0),
        const TileCell(0, 2),
        const TileCell(1, 2),
        const TileCell(2, 0),
        big(2, 1),
      ]);
    });

    test('4 columns', () {
      expect(tileCells([b, s, s, s, s, s, b], 4), [
        big(0, 0),
        const TileCell(0, 2),
        const TileCell(0, 3),
        const TileCell(1, 2),
        const TileCell(1, 3),
        const TileCell(2, 0),
        big(2, 1),
      ]);
    });

    test('5 columns', () {
      expect(tileCells([b, s, s, s, s, s, s, b], 5), [
        big(0, 0),
        const TileCell(0, 2),
        const TileCell(0, 3),
        const TileCell(0, 4),
        const TileCell(1, 2),
        const TileCell(1, 3),
        const TileCell(1, 4),
        big(2, 0),
      ]);
    });

    test('7 columns', () {
      expect(tileCells([b, s, s, s, s, s, b], 7), [
        big(0, 0),
        for (var column = 2; column < 7; column++) TileCell(0, column),
        big(1, 2),
      ]);
    });
  });

  test('a single column has no room for a block', () {
    expect(tileCells([b, s], 1), const [TileCell(0, 0), TileCell(1, 0)]);
    expect(tileCells([b, s], 0), const [TileCell(0, 0), TileCell(1, 0)]);
  });

  test('at any width, no tile overlaps another, and none moves ahead of an '
      'earlier one by more than a row', () {
    var random = Random(239);
    for (var columns = 1; columns <= 9; columns++) {
      for (var round = 0; round < 2000; round++) {
        var starred = [
          for (var n = random.nextInt(16); n >= 0; n--) random.nextInt(3) == 0,
        ];
        var cells = tileCells(starred, columns);
        var taken = <String>{};
        for (var cell in cells) {
          expect(cell.column + cell.width, lessThanOrEqualTo(columns));
          for (var r = cell.row; r < cell.row + cell.height; r++) {
            for (var c = cell.column; c < cell.column + cell.width; c++) {
              expect(taken.add("$r/$c"), isTrue, reason: "$starred at $columns");
            }
          }
        }
        for (var i = 0; i < cells.length; i++) {
          for (var j = i + 1; j < cells.length; j++) {
            expect(cells[i].row - cells[j].row, lessThanOrEqualTo(1),
                reason: "$starred at $columns: $cells");
            if (starred[i] == starred[j]) {
              // Tiles of one kind keep their order exactly.
              var before = cells[i].row * columns + cells[i].column;
              var after = cells[j].row * columns + cells[j].column;
              expect(before, lessThan(after), reason: "$starred at $columns: $cells");
            }
          }
        }
      }
    }
  });
}
