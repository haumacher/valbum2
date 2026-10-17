/// Probe of the hash cache file: a write cut off by a crash leaves half a
/// line without its newline; what is appended after it must not be glued to
/// that half and lost.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/hash_cache_file.dart';
import 'package:valbum_ui/photo_library.dart';

import 'camera_roll_speed_test.dart' show hashOf;

void main() {
  late Directory dir;
  setUp(() => dir = Directory.systemTemp.createTempSync("hashes"));
  tearDown(() => dir.deleteSync(recursive: true));

  PhotoItem item(String id) => PhotoItem(
        id: id,
        name: "$id.jpg",
        takenAt: DateTime.utc(2026),
        modifiedAt: DateTime.fromMillisecondsSinceEpoch(1, isUtc: true),
        length: 3,
        openRead: () => const Stream.empty(),
      );

  test('an entry appended after a cut-off line survives', () async {
    var file = File("${dir.path}/h.txt");
    file.writeAsStringSync("$hashCacheHeader\n"
        "1\t3\t${hashOf([1])}\ta\n"
        "1\t3\t${hashOf([9]).substring(0, 20)}");
    var cache = FileHashCache.inFile(file);
    expect(await cache.lookup(item("a")), hashOf([1]));
    cache.put(item("b"), hashOf([2]));
    await cache.flush();

    var again = FileHashCache.inFile(file);
    expect(await again.lookup(item("a")), hashOf([1]));
    expect(await again.lookup(item("b")), hashOf([2]),
        reason: "not glued to the half line before it");
  });

  test('a file of another format is written anew, not appended to', () async {
    var file = File("${dir.path}/h.txt");
    file.writeAsStringSync("other format\n1\t3\t${hashOf([1])}\ta\n");
    var cache = FileHashCache.inFile(file);
    expect(await cache.lookup(item("a")), isNull);
    cache.put(item("b"), hashOf([2]));
    await cache.flush();

    expect(await FileHashCache.inFile(file).lookup(item("b")), hashOf([2]));
  });
}
