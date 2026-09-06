import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_layout.dart' as lay;
import 'package:valbum_ui/resource.dart';

/// Regenerates the golden layout fixtures of `test/fixtures/layout` from the
/// Dart implementation in `lib/album_layout.dart`.
///
/// This is a tool, not a test: it is not named `*_test.dart`, so `flutter test`
/// does not pick it up. Run it explicitly and tell it where to write:
///
/// ```
/// GOLDENS_OUT=test/fixtures/layout \
///     flutter test test/tool/regenerate_layout_goldens.dart
/// ```
///
/// Without `GOLDENS_OUT` the fixtures are written to a temporary directory and
/// only compared with the committed ones, which says whether the writer here
/// still reproduces them byte for byte.
///
/// The fixtures were produced by the retired Java implementation, see
/// `test/fixtures/layout/README.md`; only the ones affected by a deliberate
/// change of the layout contract are ever regenerated, and only after the
/// change was verified on its own.
void main() {
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

  test('regenerate the layout goldens', () {
    var fixtures = Directory('test/fixtures/layout');
    var out = Platform.environment['GOLDENS_OUT'];
    var target = out == null
        ? Directory.systemTemp.createTempSync('layout-goldens')
        : Directory(out);

    var files = fixtures
        .listSync()
        .whereType<File>()
        .where((f) => f.path.endsWith('.json'))
        .toList()
      ..sort((a, b) => a.path.compareTo(b.path));

    var written = <String>[];
    var unchanged = <String>[];
    for (var file in files) {
      var name = file.uri.pathSegments.last;
      var fixture = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;

      var pageWidth = (fixture['pageWidth'] as num).toDouble();
      var maxRowHeight = (fixture['maxRowHeight'] as num).toDouble();
      var descriptions = fixture['images'] as List<dynamic>;
      var images = [
        for (var image in descriptions.cast<Map<String, dynamic>>())
          ImagePart(
            name: image['name'] as String,
            width: image['width'] as int,
            height: image['height'] as int,
            orientation: orientationByName[image['orientation'] as String]!,
          ),
      ];

      var layout = lay.AlbumLayout(pageWidth, maxRowHeight, images);
      var writer = ContentWriter(images);
      var regenerated = <String, dynamic>{
        'case': fixture['case'],
        'pageWidth': pageWidth,
        'maxRowHeight': maxRowHeight,
        'images': descriptions,
        'layout': <String, dynamic>{
          'pageWidth': layout.getPageWidth(),
          'rows': [
            for (var row in layout.getRows()) writer.write(row),
          ],
        },
      };

      // The Java generator started every file with an empty line.
      var text =
          '\n${const JsonEncoder.withIndent('  ').convert(regenerated)}\n';
      if (text == file.readAsStringSync()) {
        unchanged.add(name);
      } else {
        written.add(name);
      }
      File('${target.path}/$name').writeAsStringSync(text);
    }

    // ignore: avoid_print
    print('Wrote ${files.length} fixtures to ${target.path}: '
        '${written.length} differ from the committed ones '
        '(${written.join(', ')}), ${unchanged.length} are byte-identical.');
  });
}

/// Writes the layout tree in the JSON format of the fixtures.
class ContentWriter implements lay.ContentVisitor<Map<String, dynamic>, void> {
  final List<AbstractImage> images;

  ContentWriter(this.images);

  Map<String, dynamic> write(lay.Content content) => content.visit(this, null);

  @override
  Map<String, dynamic> visitRow(lay.Row content, void arg) => {
        'type': 'Row',
        'unitWidth': content.getUnitWidth(),
        'contents': [
          for (var element in content) write(element),
        ],
      };

  @override
  Map<String, dynamic> visitImg(lay.Img content, void arg) => {
        'type': 'Img',
        'index': images.indexOf(content.getImage()),
        'unitWidth': content.getUnitWidth(),
      };

  @override
  Map<String, dynamic> visitDoubleRow(lay.DoubleRow content, void arg) => {
        'type': 'DoubleRow',
        'unitWidth': content.getUnitWidth(),
        'h1': content.getH1(),
        'h2': content.getH2(),
        'upper': write(content.getUpper()),
        'lower': write(content.getLower()),
      };

  @override
  Map<String, dynamic> visitPadding(lay.Padding content, void arg) => {
        'type': 'Padding',
        'unitWidth': content.getUnitWidth(),
      };
}
