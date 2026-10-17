/// The device's memory of the content hashes of its photos (issue #299).
///
/// The camera-roll sync asks the server about a photo by the SHA-256 of its
/// contents, and computing that reads the whole file. Every scan that starts
/// from the beginning — the first run, a newly ticked album, a forgotten
/// progress — used to read and hash every photo again before anything moved,
/// which on a phone of five hundred photos is gigabytes read for a check that
/// answers "already there". A photo is hashed once in its life now: the hash
/// is kept under the photo's id, its modification time and its size, and used
/// without reading the file as long as neither changed.
///
/// The cache is an optimisation and never an authority: an entry that is
/// lost, damaged or pruned costs a re-hash, nothing else. A photo whose
/// platform names no modification time is never cached — its id and size
/// alone do not say that its contents are the same.
library;

import 'photo_library.dart';
import 'client.dart';

/// The key a photo's hash is kept under: what tells that its contents did not
/// change since the hash was computed.
class HashKey {
  final int modified;
  final int length;

  const HashKey(this.modified, this.length);

  /// The key of [item], `null` where its platform names no modification time.
  static HashKey? of(PhotoItem item) {
    var modified = item.modifiedAt;
    if (modified == null || item.id.contains("\n")) {
      return null;
    }
    return HashKey(modified.millisecondsSinceEpoch, item.length);
  }

  @override
  bool operator ==(Object other) =>
      other is HashKey && other.modified == modified && other.length == length;

  @override
  int get hashCode => Object.hash(modified, length);
}

/// One remembered hash.
class HashEntry {
  final HashKey key;
  final String hash;

  const HashEntry(this.key, this.hash);
}

/// Whether [text] is a hash as [sha256Of] spells it: 64 lower-case hex digits.
bool isContentHash(String text) =>
    text.length == 64 && RegExp(r'^[0-9a-f]{64}$').hasMatch(text);

/// The hashes of the device's photos, by [PhotoItem.id].
///
/// [MemoryHashCache] forgets with the process; the app keeps a file, see
/// `hash_cache_file.dart`.
abstract class HashCache {
  /// The remembered hash of [item], `null` where there is none, or where it
  /// was computed for another modification time or size.
  Future<String?> lookup(PhotoItem item);

  /// Remembers [hash] as the hash of [item]'s contents; kept in memory until
  /// [flush].
  void put(PhotoItem item, String hash);

  /// Writes what was [put] since the last flush, in one write.
  Future<void> flush();

  /// Forgets every photo but those of [ids]: called after a scan that saw
  /// everything the sync watches, see [CameraRollSync].
  Future<void> retainOnly(Set<String> ids);

  /// The number of photos remembered.
  int get length;
}

/// A [HashCache] in memory only: what a test and the web build use.
class MemoryHashCache implements HashCache {
  /// The entries, by photo id.
  final Map<String, HashEntry> entries = {};

  @override
  Future<String?> lookup(PhotoItem item) async {
    var key = HashKey.of(item);
    if (key == null) {
      // Never remembered, so the file is not even read for it.
      return null;
    }
    await ensureLoaded();
    var entry = entries[item.id];
    return entry != null && entry.key == key ? entry.hash : null;
  }

  @override
  void put(PhotoItem item, String hash) {
    var key = HashKey.of(item);
    if (key == null || !isContentHash(hash)) {
      return;
    }
    var entry = HashEntry(key, hash);
    entries[item.id] = entry;
    added(item.id, entry);
  }

  /// Reads what is persisted, once; nothing for a cache in memory.
  Future<void> ensureLoaded() async {}

  /// Called by [put] for every new entry; nothing for a cache in memory.
  void added(String id, HashEntry entry) {}

  @override
  Future<void> flush() async {}

  @override
  Future<void> retainOnly(Set<String> ids) async {
    entries.removeWhere((id, _) => !ids.contains(id));
  }

  @override
  int get length => entries.length;
}
