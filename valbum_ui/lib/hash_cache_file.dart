/// The [HashCache] that survives the app being closed, and the hashing of a
/// photo off the UI isolate (issue #299).
///
/// This library uses `dart:io` and `dart:isolate` and is therefore imported
/// only through `platform_io.dart`; the web build never sees it.
library;

import 'dart:io';
import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import 'client.dart';
import 'hash_cache.dart';

/// The first line of a hash cache file, naming its format.
const String hashCacheHeader = "valbum-hashes 1";

/// The name of the file under the application support directory.
const String hashCacheFileName = "valbum-hashes.txt";

/// A [HashCache] kept in one text file of the device.
///
/// One line per photo — `modified<TAB>size<TAB>sha256<TAB>id`, the id last so
/// that it may hold anything but a line break — read once, when the first
/// photo is looked up. What is [put] is **appended** at [flush], one write per
/// flush, never the whole file per photo: a library of tens of thousands of
/// photos is a file of a few megabytes, read in a fraction of a second, and a
/// sync that hashes a hundred photos adds a hundred lines.
///
/// A photo hashed again (it was edited) appends a second line; the later one
/// wins when the file is read. The file is rewritten only when such dead
/// lines make up the larger part of it, and when [retainOnly] drops the
/// photos that are gone.
///
/// Two isolates may use the file at once (the app and a background run): an
/// append of one is a single write, and a line the other's rewrite loses is a
/// photo hashed once more, never a wrong answer. A line that does not read
/// back as an entry is skipped.
class FileHashCache extends MemoryHashCache {
  final Future<File> Function() _file;

  bool _loaded = false;
  File? _open;
  int _lines = 0;
  final StringBuffer _pending = StringBuffer();

  FileHashCache._(this._file);

  /// The cache in the given file, used by the tests.
  factory FileHashCache.inFile(File file) => FileHashCache._(() async => file);

  /// The cache of the app, under the application support directory.
  ///
  /// A reinstall removes it with the app's data — the next sync hashes every
  /// photo once more and uploads nothing the server has. Where the platform
  /// restores the app's data from a backup, the file comes back with it,
  /// which is harmless: a photo of another id, time or size is not found in
  /// it.
  factory FileHashCache.underSupportDirectory() => FileHashCache._(
        () async => File(
          "${(await getApplicationSupportDirectory()).path}"
          "${Platform.pathSeparator}$hashCacheFileName",
        ),
      );

  /// Whether the file read ends in the middle of a line, see [ensureLoaded].
  bool _cutOff = false;

  /// Whether the file read is of another format, and is to be written anew.
  bool _foreign = false;

  @override
  Future<void> ensureLoaded() async {
    if (_loaded) {
      return;
    }
    _loaded = true;
    try {
      var file = await _file();
      _open = file;
      if (!await file.exists()) {
        return;
      }
      var text = await file.readAsString();
      // A write cut off by a crash leaves half a line: what is appended next
      // must start on a line of its own, or it is glued to the half and lost.
      _cutOff = text.isNotEmpty && !text.endsWith("\n");
      var lines = text.split("\n");
      if (lines.isEmpty || lines.first != hashCacheHeader) {
        // Another format, or a damaged file: start over — the next flush
        // writes the file anew, appending to it would never be read again.
        _lines = 0;
        _foreign = true;
        return;
      }
      for (var line in lines.skip(1)) {
        if (line.isEmpty) {
          continue;
        }
        _lines++;
        var parts = line.split("\t");
        if (parts.length < 4) {
          continue;
        }
        var modified = int.tryParse(parts[0]);
        var length = int.tryParse(parts[1]);
        var hash = parts[2];
        var id = parts.sublist(3).join("\t");
        if (modified == null || length == null || !isContentHash(hash)) {
          continue;
        }
        entries[id] = HashEntry(HashKey(modified, length), hash);
      }
    } catch (error) {
      // No readable file (a platform without `path_provider`, a test binding
      // without the plugin): the sync runs with a cache in memory.
      _log("Cannot read the hash cache: $error");
    }
  }

  static String _line(String id, HashEntry entry) =>
      "${entry.key.modified}\t${entry.key.length}\t${entry.hash}\t$id\n";

  @override
  void added(String id, HashEntry entry) {
    _pending.write(_line(id, entry));
    _lines++;
  }

  @override
  Future<void> flush() async {
    var file = _open;
    if (file == null || _pending.isEmpty) {
      return;
    }
    if (_foreign || _lines > 2 * entries.length + 1000) {
      _foreign = false;
      _cutOff = false;
      await _rewrite(file);
      return;
    }
    var text = _pending.toString();
    _pending.clear();
    try {
      if (!await file.exists()) {
        await file.parent.create(recursive: true);
        text = "$hashCacheHeader\n$text";
      } else if (_cutOff) {
        text = "\n$text";
      }
      _cutOff = false;
      await file.writeAsString(text, mode: FileMode.append, flush: true);
    } catch (error) {
      _log("Cannot write the hash cache: $error");
    }
  }

  @override
  Future<void> retainOnly(Set<String> ids) async {
    if (!_loaded) {
      // Nothing was looked up in this run, so nothing is known to be gone.
      return;
    }
    var before = entries.length;
    await super.retainOnly(ids);
    var file = _open;
    if (file != null && (entries.length < before || _pending.isNotEmpty)) {
      await _rewrite(file);
    }
  }

  /// Writes the whole file anew: through a second file renamed over it, so
  /// that a write that is cut short leaves the old file.
  Future<void> _rewrite(File file) async {
    _pending.clear();
    var text = StringBuffer(hashCacheHeader)..write("\n");
    for (var entry in entries.entries) {
      text.write(_line(entry.key, entry.value));
    }
    _lines = entries.length;
    try {
      await file.parent.create(recursive: true);
      var next = File("${file.path}.next");
      await next.writeAsString(text.toString(), flush: true);
      await next.rename(file.path);
    } catch (error) {
      _log("Cannot write the hash cache: $error");
    }
  }

  static void _log(String message) {
    if (kDebugMode) {
      print(message);
    }
  }
}

/// The SHA-256 of the file at [path] and the number of bytes read, computed
/// in an isolate of its own (issue #299).
///
/// The file is streamed there, never loaded whole, and the UI isolate only
/// waits: hashing a camera roll no longer stutters the screen it is shown on.
Future<({String hash, int bytes})> sha256OfFileInIsolate(String path) =>
    Isolate.run(() => _hashFile(path));

Future<({String hash, int bytes})> _hashFile(String path) async {
  var bytes = 0;
  var hash = await sha256Of(File(path).openRead().map((chunk) {
    bytes += chunk.length;
    return chunk;
  }));
  return (hash: hash, bytes: bytes);
}
