/// The [OfflineCache] that survives the app being closed: one file per entry
/// under the application support directory, plus a small index.
///
/// This library uses `dart:io` and is therefore imported only through
/// `platform_io.dart`; the web build never sees it, see [MemoryOfflineCache].
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import 'offline.dart';

/// The name of the index file inside the cache directory.
const String cacheIndexName = "index.json";

/// The version the index is written with, see [FileOfflineCache].
const int cacheIndexVersion = 1;

/// An [OfflineCache] stored in a directory of the device.
///
/// One file per entry, named by the SHA-256 of the cache key — a key contains
/// a URL and a folder name, neither of which is a file name — and one
/// `index.json` holding the sizes and the stamps:
///
/// ```json
/// {"version": 1, "entries": {"<sha256>": {"key": "thumbnail:...", "size": 4711,
///   "storedAt": 1757000000000, "lastUsed": 1757000000000}}}
/// ```
///
/// The index is a convenience, never the authority the cache depends on: an
/// index that cannot be read is rebuilt from the files that are there, because
/// a damaged file must cost the user a refresh, not the app a crash. That
/// rebuild is possible because every entry file names its own key: the
/// contents are preceded by the key (a four-byte length, then the key in
/// UTF-8), so the hash in the file name never has to be inverted. Every file
/// operation is guarded the same way: a cache that cannot be written is a
/// cache miss, never a failed load.
class FileOfflineCache extends BoundedOfflineCache {
  /// The directory the entries live in, resolved on first use.
  final Future<Directory> Function() _directory;

  Directory? _dir;
  bool _loaded = false;
  bool _dirty = false;
  Future<void>? _pending;

  FileOfflineCache._(this._directory, {super.sizeLimit});

  /// The cache in the given directory, used by the tests.
  factory FileOfflineCache.inDirectory(Directory directory, {int? sizeLimit}) =>
      FileOfflineCache._(
        () async => directory,
        sizeLimit: sizeLimit ?? defaultCacheLimit,
      );

  /// The cache of the app: `valbum-cache` under the application support
  /// directory of the platform.
  factory FileOfflineCache.underSupportDirectory({int? sizeLimit}) =>
      FileOfflineCache._(
        () async => Directory(
          "${(await getApplicationSupportDirectory()).path}"
          "${Platform.pathSeparator}valbum-cache",
        ),
        sizeLimit: sizeLimit ?? defaultCacheLimit,
      );

  /// The file name an entry of the given key is stored under.
  static String fileNameOf(String key) =>
      sha256.convert(utf8.encode(key)).toString();

  /// The directory of this cache, `null` if it cannot be used at all.
  Future<Directory?> _open() async {
    var open = _dir;
    if (open != null) {
      return open;
    }
    try {
      var directory = await _directory();
      if (!await directory.exists()) {
        await directory.create(recursive: true);
      }
      return _dir = directory;
    } catch (error) {
      // No writable directory (a platform without `path_provider`, a test
      // binding without the plugin): the app runs without a cache.
      _log("Cannot open the offline cache: $error");
      return null;
    }
  }

  File _fileOf(Directory dir, String key) =>
      File("${dir.path}${Platform.pathSeparator}${fileNameOf(key)}");

  @override
  Future<void> ensureLoaded() async {
    if (_loaded) {
      return;
    }
    _loaded = true;
    var dir = await _open();
    if (dir == null) {
      return;
    }
    var index = File("${dir.path}${Platform.pathSeparator}$cacheIndexName");
    try {
      if (await index.exists()) {
        _readIndex(jsonDecode(await index.readAsString()));
        return;
      }
    } catch (error) {
      _log("The offline cache index is damaged, rebuilding it: $error");
    }
    await _rebuild(dir);
  }

  /// Reads the stamps of the given index contents.
  ///
  /// Throws whatever a damaged index causes; the caller rebuilds then.
  void _readIndex(dynamic content) {
    var version = content["version"];
    if (version != cacheIndexVersion) {
      throw FormatException("Unknown cache index version: $version");
    }
    var entries = content["entries"] as Map<String, dynamic>;
    stamps.clear();
    for (var entry in entries.entries) {
      var value = entry.value as Map<String, dynamic>;
      stamps[_keyOf(entry.key, value)] = CacheStamp(
        size: value["size"] as int,
        storedAt: DateTime.fromMillisecondsSinceEpoch(value["storedAt"] as int),
        lastUsed: DateTime.fromMillisecondsSinceEpoch(value["lastUsed"] as int),
        seq: nextSeq(),
      );
    }
  }

  /// The cache key an index entry belongs to.
  String _keyOf(String fileName, Map<String, dynamic> value) =>
      value["key"] as String? ?? fileName;

  /// Builds the stamps from the files that are there.
  ///
  /// Every entry file starts with the key it was stored under, so a rebuilt
  /// index is as good as a written one: the keys, the sizes and — from the
  /// file's modification time — the stamps are all recovered. A file that does
  /// not carry a readable key is dropped; it is not one of ours.
  Future<void> _rebuild(Directory dir) async {
    stamps.clear();
    try {
      await for (var entry in dir.list()) {
        if (entry is! File) {
          continue;
        }
        var name = entry.uri.pathSegments.last;
        if (name == cacheIndexName) {
          continue;
        }
        var stat = await entry.stat();
        var key = await _readKey(entry);
        if (key == null || fileNameOf(key) != name) {
          continue;
        }
        stamps[key] = CacheStamp(
          size: stat.size - _headerLength(key),
          storedAt: stat.modified,
          lastUsed: stat.modified,
          seq: nextSeq(),
        );
      }
    } catch (error) {
      _log("Cannot read the offline cache directory: $error");
    }
    _dirty = true;
    await flush(urgent: true);
  }

  /// The key the given entry file was stored under, `null` if it holds none.
  ///
  /// Only the header is read; the contents may be a video thumbnail and have
  /// no business in memory here.
  Future<String?> _readKey(File file) async {
    RandomAccessFile? handle;
    try {
      handle = await file.open();
      var header = await handle.read(4);
      if (header.length < 4) {
        return null;
      }
      var length = ByteData.view(Uint8List.fromList(header).buffer)
          .getUint32(0, Endian.big);
      if (length == 0 || length > 4096) {
        return null;
      }
      var key = await handle.read(length);
      if (key.length < length) {
        return null;
      }
      return utf8.decode(key);
    } catch (error) {
      _log("Cannot read a cached entry: $error");
      return null;
    } finally {
      await handle?.close();
    }
  }

  /// The number of bytes the key of an entry occupies in front of it.
  static int _headerLength(String key) => 4 + utf8.encode(key).length;

  /// The contents of an entry, preceded by the key it belongs to.
  static Uint8List _framed(String key, Uint8List bytes) {
    var keyBytes = utf8.encode(key);
    var result = BytesBuilder(copy: false);
    var header = ByteData(4)..setUint32(0, keyBytes.length, Endian.big);
    result.add(header.buffer.asUint8List());
    result.add(keyBytes);
    result.add(bytes);
    return result.toBytes();
  }

  /// The contents of a framed entry, `null` if it does not belong to [key].
  static Uint8List? _unframed(Uint8List raw, String key) {
    if (raw.length < 4) {
      return null;
    }
    var length = ByteData.view(raw.buffer, raw.offsetInBytes, 4)
        .getUint32(0, Endian.big);
    var start = 4 + length;
    if (raw.length < start) {
      return null;
    }
    if (utf8.decode(raw.sublist(4, start), allowMalformed: true) != key) {
      return null;
    }
    return Uint8List.sublistView(raw, start);
  }

  @override
  Future<Uint8List?> readBytes(String key) async {
    var dir = await _open();
    if (dir == null) {
      return null;
    }
    try {
      var file = _fileOf(dir, key);
      if (!await file.exists()) {
        return null;
      }
      return _unframed(await file.readAsBytes(), key);
    } catch (error) {
      _log("Cannot read a cached entry: $error");
      return null;
    }
  }

  @override
  Future<void> writeBytes(String key, Uint8List bytes) async {
    var dir = await _open();
    if (dir == null) {
      return;
    }
    try {
      await _fileOf(dir, key).writeAsBytes(_framed(key, bytes), flush: false);
    } catch (error) {
      _log("Cannot write a cached entry: $error");
    }
  }

  @override
  Future<void> deleteBytes(String key) async {
    var dir = await _open();
    if (dir == null) {
      return;
    }
    try {
      var file = _fileOf(dir, key);
      if (await file.exists()) {
        await file.delete();
      }
    } catch (error) {
      _log("Cannot drop a cached entry: $error");
    }
  }

  @override
  Future<void> deleteAll() async {
    var dir = await _open();
    if (dir == null) {
      return;
    }
    try {
      await for (var entry in dir.list()) {
        if (entry is File && entry.uri.pathSegments.last != cacheIndexName) {
          await entry.delete();
        }
      }
    } catch (error) {
      _log("Cannot clear the offline cache: $error");
    }
  }

  @override
  Future<void> flush({bool urgent = false}) async {
    _dirty = true;
    if (!urgent) {
      // A read only moved an entry in the eviction order; that is not worth a
      // file write of its own. It rides on the next write.
      return;
    }
    var running = _pending;
    if (running != null) {
      await running;
    }
    await (_pending = _write());
    _pending = null;
  }

  Future<void> _write() async {
    if (!_dirty) {
      return;
    }
    _dirty = false;
    var dir = await _open();
    if (dir == null) {
      return;
    }
    var entries = <String, dynamic>{};
    for (var stamp in stamps.entries) {
      entries[fileNameOf(stamp.key)] = {
        "key": stamp.key,
        "size": stamp.value.size,
        "storedAt": stamp.value.storedAt.millisecondsSinceEpoch,
        "lastUsed": stamp.value.lastUsed.millisecondsSinceEpoch,
      };
    }
    try {
      await File("${dir.path}${Platform.pathSeparator}$cacheIndexName")
          .writeAsString(
        jsonEncode({"version": cacheIndexVersion, "entries": entries}),
      );
    } catch (error) {
      _log("Cannot write the offline cache index: $error");
    }
  }

  static void _log(String message) {
    if (kDebugMode) {
      print(message);
    }
  }
}
