import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_layout.dart';
import 'package:valbum_ui/resource.dart';

/// Maximum accepted deviation of any double in the layout.
const double tolerance = 1e-9;

const Map<String, Orientation> orientationByName = {
  'IDENTITY': Orientation.identity,
  'FLIP_H': Orientation.flipH,
  'ROT_180': Orientation.rot180,
  'FLIP_V': Orientation.flipV,
  'ROT_L_FLIP_V': Orientation.rotLFlipV,
  'ROT_L': Orientation.rotL,
  'ROT_L_FLIP_H': Orientation.rotLFlipH,
  'ROT_R': Orientation.rotR,
};

void main() {
  final Directory fixtures = Directory('test/fixtures/layout');

  final List<File> files = fixtures
      .listSync()
      .whereType<File>()
      .where((f) => f.path.endsWith('.json'))
      .toList()
    ..sort((a, b) => a.path.compareTo(b.path));

  test('golden fixtures exist', () {
    expect(files, isNotEmpty,
        reason: 'No layout fixtures found in ${fixtures.path}');
  });

  for (final File file in files) {
    final String caseName = file.uri.pathSegments.last;

    test('layout matches golden $caseName', () {
      final Map<String, dynamic> fixture =
          jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;

      final double pageWidth = (fixture['pageWidth'] as num).toDouble();
      final double maxRowHeight = (fixture['maxRowHeight'] as num).toDouble();

      final List<AbstractImage> images = [
        for (final dynamic image in fixture['images'] as List<dynamic>)
          ImagePart(
            name: (image as Map<String, dynamic>)['name'] as String,
            width: image['width'] as int,
            height: image['height'] as int,
            orientation: orientationByName[image['orientation'] as String]!,
          ),
      ];

      final AlbumLayout layout = AlbumLayout(pageWidth, maxRowHeight, images);
      final Map<String, dynamic> expected =
          fixture['layout'] as Map<String, dynamic>;

      final Comparison comparison = Comparison(caseName, images);
      comparison.checkDouble(
        'layout.pageWidth',
        expected['pageWidth'] as num,
        layout.getPageWidth(),
      );

      final List<dynamic> expectedRows = expected['rows'] as List<dynamic>;
      final List<Row> actualRows = layout.getRows();
      comparison.checkInt(
        'layout.rows.length',
        expectedRows.length,
        actualRows.length,
      );
      for (int i = 0; i < expectedRows.length; i++) {
        comparison.checkContent(
          'row[$i]',
          expectedRows[i] as Map<String, dynamic>,
          actualRows[i],
        );
      }
    });
  }
}

/// Structural comparison of an expected (JSON) row tree with the computed one.
class Comparison {
  final String caseName;
  final List<AbstractImage> images;

  Comparison(this.caseName, this.images);

  void fail(String path, Object? expected, Object? actual) {
    throw TestFailure(
      'Layout mismatch in fixture "$caseName" at $path: '
      'expected <$expected>, but was <$actual>.',
    );
  }

  void checkDouble(String path, num expected, double actual) {
    if ((expected.toDouble() - actual).abs() > tolerance) {
      fail(path, expected, actual);
    }
  }

  void checkInt(String path, int expected, int actual) {
    if (expected != actual) {
      fail(path, expected, actual);
    }
  }

  void checkString(String path, String expected, String actual) {
    if (expected != actual) {
      fail(path, expected, actual);
    }
  }

  void checkContent(
      String path, Map<String, dynamic> expected, Content actual) {
    final String type = expected['type'] as String;
    checkString('$path.type', type, typeName(actual));
    checkDouble(
      '$path.unitWidth',
      expected['unitWidth'] as num,
      actual.getUnitWidth(),
    );

    switch (type) {
      case 'Row':
        final Row row = actual as Row;
        final List<dynamic> contents = expected['contents'] as List<dynamic>;
        final List<Content> actualContents = row.toList();
        checkInt(
          '$path.contents.length',
          contents.length,
          actualContents.length,
        );
        for (int i = 0; i < contents.length; i++) {
          checkContent(
            '$path.contents[$i]',
            contents[i] as Map<String, dynamic>,
            actualContents[i],
          );
        }
        break;
      case 'Img':
        final Img img = actual as Img;
        final int index = images.indexOf(img.getImage());
        checkInt('$path.index', expected['index'] as int, index);
        break;
      case 'DoubleRow':
        final DoubleRow doubleRow = actual as DoubleRow;
        checkDouble('$path.h1', expected['h1'] as num, doubleRow.getH1());
        checkDouble('$path.h2', expected['h2'] as num, doubleRow.getH2());
        checkContent(
          '$path.upper',
          expected['upper'] as Map<String, dynamic>,
          doubleRow.getUpper(),
        );
        checkContent(
          '$path.lower',
          expected['lower'] as Map<String, dynamic>,
          doubleRow.getLower(),
        );
        break;
      case 'Padding':
        break;
      default:
        throw StateError('Unknown content type in fixture: $type');
    }
  }

  String typeName(Content content) {
    if (content is Row) return 'Row';
    if (content is Img) return 'Img';
    if (content is DoubleRow) return 'DoubleRow';
    if (content is Padding) return 'Padding';
    return content.runtimeType.toString();
  }
}
