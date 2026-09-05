/// The offline cache (issue #31): what the app has already seen, kept on the
/// device so that the library browses without the server.
///
/// Three things live here: the [OfflineCache] interface with its in-memory
/// implementation, the [OfflineState] the app shows the user while it is
/// showing a cached copy, and the [OfflineScope] publishing both to the
/// widget tree.
///
/// The mechanism is **network first, cache second**: every load goes to the
/// server, a successful answer is written through into the cache, and only a
/// *transport* failure — the server cannot be reached at all — falls back to
/// what the cache holds. A server that answers with 401, 404 or 500 is the
/// server speaking; that answer is reported as it always was and is never
/// masked by a cached copy.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// One entry of an [OfflineCache]: the bytes and when they were stored.
@immutable
class CacheEntry {
  /// The cached contents.
  final Uint8List bytes;

  /// When the contents were fetched from the server.
  ///
  /// This is the "last updated" the app shows while it is offline.
  final DateTime storedAt;

  /// When the entry was last read, the order eviction follows.
  final DateTime lastUsed;

  const CacheEntry({
    required this.bytes,
    required this.storedAt,
    required this.lastUsed,
  });

  /// The contents read as UTF-8 text, for a cached resource.
  String get text => utf8.decode(bytes);
}

/// The default bound of a cache: 200 MB.
const int defaultCacheLimit = 200 * 1024 * 1024;

/// What the app has already seen, kept for the times the server cannot be
/// reached.
///
/// Entries are keyed by the *server* and the album path, so pointing the app
/// at another server shows that server's data without anything being cleared,
/// see `ServerSettings`. The cache is bounded: a [put] that exceeds
/// [sizeLimit] evicts the least recently used entries first.
abstract class OfflineCache {
  const OfflineCache();

  /// The number of bytes this cache may hold, see [size].
  int get sizeLimit;

  /// The cached JSON of the resource at [path] on the server at [dataUrl].
  Future<CacheEntry?> getResource(String dataUrl, List<String> path);

  /// Remembers the JSON the server answered for [path].
  Future<void> putResource(String dataUrl, List<String> path, String json);

  /// The cached bytes of the thumbnail at [url].
  Future<CacheEntry?> getThumbnail(String url);

  /// Remembers the bytes the server answered for the thumbnail at [url].
  Future<void> putThumbnail(String url, Uint8List bytes);

  /// The number of bytes currently held.
  Future<int> size();

  /// Forgets everything; [size] is 0 afterwards.
  Future<void> clear();

  /// The cache key of a resource, see [getResource].
  ///
  /// The server is part of the key: two servers may well hold a folder of the
  /// same name, and they are different albums.
  static String resourceKey(String dataUrl, List<String> path) =>
      "resource:$dataUrl|${path.join("/")}";

  /// The cache key of a thumbnail, see [getThumbnail].
  ///
  /// A thumbnail URL is absolute and therefore already names its server.
  static String thumbnailKey(String url) => "thumbnail:$url";
}

/// The bookkeeping every [OfflineCache] does: the keys, the stamps and the
/// least-recently-used eviction.
///
/// Subclasses say where the bytes live ([readBytes], [writeBytes],
/// [deleteBytes]); the order, the sizes and the bound are handled here.
abstract class BoundedOfflineCache extends OfflineCache {
  @override
  final int sizeLimit;

  BoundedOfflineCache({this.sizeLimit = defaultCacheLimit});

  /// The stamps of every entry held, by cache key.
  @protected
  final Map<String, CacheStamp> stamps = {};

  /// Counts the accesses, so that two entries touched within the same
  /// millisecond still have a defined order in [_evict].
  int _clock = 0;

  /// The next access number, see [_clock].
  @protected
  int nextSeq() => ++_clock;

  /// The bytes stored under [key], `null` if there are none.
  @protected
  Future<Uint8List?> readBytes(String key);

  /// Stores [bytes] under [key].
  @protected
  Future<void> writeBytes(String key, Uint8List bytes);

  /// Removes what [key] holds.
  @protected
  Future<void> deleteBytes(String key);

  /// Removes everything, see [clear].
  @protected
  Future<void> deleteAll();

  /// Called after the stamps changed, so that a persistent cache can store
  /// them; does nothing in memory.
  ///
  /// A read only moves an entry in the eviction order, so it may be written
  /// lazily; a write must survive the app being killed, so it is [urgent].
  @protected
  Future<void> flush({bool urgent = false}) async {}

  /// Reads the stamps of a previous run; called before the first access.
  @protected
  Future<void> ensureLoaded() async {}

  @override
  Future<CacheEntry?> getResource(String dataUrl, List<String> path) =>
      _get(OfflineCache.resourceKey(dataUrl, path));

  @override
  Future<void> putResource(String dataUrl, List<String> path, String json) =>
      _put(
        OfflineCache.resourceKey(dataUrl, path),
        Uint8List.fromList(utf8.encode(json)),
      );

  @override
  Future<CacheEntry?> getThumbnail(String url) =>
      _get(OfflineCache.thumbnailKey(url));

  @override
  Future<void> putThumbnail(String url, Uint8List bytes) =>
      _put(OfflineCache.thumbnailKey(url), bytes);

  Future<CacheEntry?> _get(String key) async {
    await ensureLoaded();
    var stamp = stamps[key];
    if (stamp == null) {
      return null;
    }
    var bytes = await readBytes(key);
    if (bytes == null) {
      // The index knows the entry but its contents are gone; forget it rather
      // than answering with nothing forever.
      stamps.remove(key);
      await flush(urgent: true);
      return null;
    }
    var used = DateTime.now();
    stamps[key] = stamp.usedAt(used, nextSeq());
    await flush();
    return CacheEntry(
      bytes: bytes,
      storedAt: stamp.storedAt,
      lastUsed: used,
    );
  }

  Future<void> _put(String key, Uint8List bytes) async {
    await ensureLoaded();
    if (bytes.length > sizeLimit) {
      // A single entry larger than the whole cache is not stored; keeping it
      // would evict everything else for nothing.
      return;
    }
    var now = DateTime.now();
    await writeBytes(key, bytes);
    stamps[key] = CacheStamp(
      size: bytes.length,
      storedAt: now,
      lastUsed: now,
      seq: nextSeq(),
    );
    await _evict();
    await flush(urgent: true);
  }

  /// Drops the least recently used entries until the bound holds again.
  Future<void> _evict() async {
    var total = _total();
    if (total <= sizeLimit) {
      return;
    }
    var order = stamps.entries.toList()
      ..sort((a, b) {
        var byTime = a.value.lastUsed.compareTo(b.value.lastUsed);
        return byTime != 0 ? byTime : a.value.seq.compareTo(b.value.seq);
      });
    for (var entry in order) {
      if (total <= sizeLimit) {
        return;
      }
      stamps.remove(entry.key);
      await deleteBytes(entry.key);
      total -= entry.value.size;
    }
  }

  int _total() => stamps.values.fold(0, (sum, stamp) => sum + stamp.size);

  @override
  Future<int> size() async {
    await ensureLoaded();
    return _total();
  }

  @override
  Future<void> clear() async {
    await ensureLoaded();
    stamps.clear();
    await deleteAll();
    await flush(urgent: true);
  }
}

/// The bookkeeping of one cache entry: how large it is and when it was stored
/// and last used.
@immutable
class CacheStamp {
  final int size;
  final DateTime storedAt;
  final DateTime lastUsed;

  /// The access this entry was last touched by, see
  /// [BoundedOfflineCache.nextSeq]; breaks the tie when two entries carry the
  /// same [lastUsed].
  final int seq;

  const CacheStamp({
    required this.size,
    required this.storedAt,
    required this.lastUsed,
    this.seq = 0,
  });

  /// The same entry, read now.
  CacheStamp usedAt(DateTime now, int seq) => CacheStamp(
        size: size,
        storedAt: storedAt,
        lastUsed: now,
        seq: seq,
      );
}

/// An [OfflineCache] that keeps everything in memory only.
///
/// Used by the tests, and it **is** the cache of the web build: in a browser
/// the page is served by the very server the data comes from, and the
/// browser's own HTTP cache is the offline story there. Persisting a second
/// copy into `IndexedDB` would duplicate what the browser already does, so the
/// web app deliberately keeps nothing across a reload.
class MemoryOfflineCache extends BoundedOfflineCache {
  final Map<String, Uint8List> _contents = {};

  MemoryOfflineCache({super.sizeLimit});

  @override
  Future<Uint8List?> readBytes(String key) async => _contents[key];

  @override
  Future<void> writeBytes(String key, Uint8List bytes) async =>
      _contents[key] = bytes;

  @override
  Future<void> deleteBytes(String key) async => _contents.remove(key);

  @override
  Future<void> deleteAll() async => _contents.clear();
}

/// Whether the app is currently showing what the cache holds, and how old that
/// is.
///
/// Set by [VAlbumClient.loadResource] when the server cannot be reached and a
/// cached copy answered instead; cleared by the next load that reaches the
/// server. The views listen to it: a banner says what is shown, and every
/// change is refused while it is [offline].
class OfflineState extends ChangeNotifier {
  bool _offline = false;
  DateTime? _lastUpdated;

  /// Whether the server could not be reached the last time the app asked.
  bool get offline => _offline;

  /// When the data currently shown was fetched, `null` if nothing is shown
  /// from the cache.
  DateTime? get lastUpdated => _lastUpdated;

  /// The server answered: the app is showing live data again.
  void online() {
    if (!_offline && _lastUpdated == null) {
      return;
    }
    _offline = false;
    _lastUpdated = null;
    notifyListeners();
  }

  /// The server could not be reached; [lastUpdated] dates what is shown
  /// instead, `null` if there was nothing to show.
  void goneOffline(DateTime? lastUpdated) {
    if (_offline && _lastUpdated == lastUpdated) {
      return;
    }
    _offline = true;
    _lastUpdated = lastUpdated;
    notifyListeners();
  }
}

/// The one message every refused change carries.
///
/// Changes need the server; while it cannot be reached there is nothing to
/// write to, and an edit that silently did nothing would be worse than a
/// refusal, see the "refusals speak" rule.
const String offlineRefusal =
    "Offline: changes need the server. Retry when it is reachable again.";

/// Publishes the [OfflineCache] and the [OfflineState] to the widget tree.
class OfflineScope extends InheritedNotifier<OfflineState> {
  /// What the app has seen, see [OfflineCache].
  final OfflineCache cache;

  const OfflineScope({
    super.key,
    required OfflineState state,
    required this.cache,
    required super.child,
  }) : super(notifier: state);

  /// The offline state and cache of the enclosing app.
  static OfflineScope of(BuildContext context) {
    var scope = maybeOf(context);
    assert(scope != null, "No OfflineScope found in the widget tree.");
    return scope!;
  }

  /// The offline state and cache of the enclosing app, `null` if the widget is
  /// shown outside one (a view pumped on its own in a test).
  static OfflineScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<OfflineScope>();

  /// Whether the app is currently showing cached data.
  static bool isOffline(BuildContext context) =>
      maybeOf(context)?.state.offline ?? false;

  OfflineState get state => notifier!;

  @override
  bool updateShouldNotify(OfflineScope oldWidget) =>
      super.updateShouldNotify(oldWidget) || cache != oldWidget.cache;
}

/// Refuses a change while the app is offline, saying so on the screen.
///
/// Returns whether the change was refused; every caller that would talk to the
/// server asks this first, so the refusal reads the same everywhere.
bool refuseWhileOffline(BuildContext context) {
  if (!OfflineScope.isOffline(context)) {
    return false;
  }
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: const Text(offlineRefusal),
      backgroundColor: Colors.red.shade700,
      duration: const Duration(seconds: 6),
    ),
  );
  return true;
}

/// The bar the listing and the album show while the app is offline.
///
/// Nothing at all while the server is reachable, so it can be placed above
/// every view without changing its layout.
class OfflineBanner extends StatelessWidget {
  /// Loads the view again, in the hope that the server answers this time.
  final VoidCallback onRetry;

  const OfflineBanner({super.key, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    var scope = OfflineScope.maybeOf(context);
    if (scope == null || !scope.state.offline) {
      return const SizedBox.shrink();
    }
    return Material(
      color: Colors.amber.shade800,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            const Icon(Icons.cloud_off, size: 18, color: Colors.white),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                offlineMessage(scope.state.lastUpdated),
                style: const TextStyle(color: Colors.white),
              ),
            ),
            TextButton(
              onPressed: onRetry,
              style: TextButton.styleFrom(foregroundColor: Colors.white),
              child: const Text("Retry"),
            ),
          ],
        ),
      ),
    );
  }
}

/// What the [OfflineBanner] says for data fetched at [lastUpdated].
String offlineMessage(DateTime? lastUpdated) => lastUpdated == null
    ? "Offline - the server cannot be reached."
    : "Offline - showing the copy from ${formatStamp(lastUpdated)}";

/// A date and time in the form the banner shows, without a locale dependency.
String formatStamp(DateTime when) {
  var local = when.toLocal();
  String two(int value) => value.toString().padLeft(2, "0");
  return "${local.year}-${two(local.month)}-${two(local.day)} "
      "${two(local.hour)}:${two(local.minute)}";
}

/// A byte count in the form the settings screen shows.
String formatBytes(int bytes) {
  if (bytes < 1024) {
    return "$bytes B";
  }
  const units = ["kB", "MB", "GB"];
  var value = bytes / 1024;
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return "${value.toStringAsFixed(value < 10 ? 1 : 0)} ${units[unit]}";
}
