/// Tests of the speed of the camera-roll sync (issue #299): what a run reads,
/// hashes and asks when it starts from the beginning over photos the server
/// already holds, the hash cache it remembers them in, the bounded parallel
/// uploads, and the timing record of a run.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/hash_cache_file.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// What a run read and asked, counted by the fake library and the server.
class Counts {
  int reads = 0;
  int bytes = 0;
  int checks = 0;
  int uploads = 0;

  void clear() {
    reads = 0;
    bytes = 0;
    checks = 0;
    uploads = 0;
  }

  @override
  String toString() =>
      "reads=$reads bytes=$bytes checks=$checks uploads=$uploads";
}

/// The hash of [contents] as the server computes it.
String hashOf(List<int> contents) => sha256.convert(contents).toString();

/// A device with photos in its camera album, every read counted.
class Device {
  final Counts counts = Counts();
  final FakePhotoLibrary library = FakePhotoLibrary();
  late final PhotoAlbum camera;
  late final List<PhotoItem> photos;

  /// The contents of every photo, by file name.
  final Map<String, List<int>> contents = {};

  Device(int count) {
    photos = [for (var i = 0; i < count; i++) photo(i)];
    camera = library.addAlbum("Camera", photos, camera: true);
  }

  /// Photo [i]; [edit] makes it the edited version of itself: other
  /// contents, a later modification time.
  PhotoItem photo(int i, {int edit = 0}) {
    var bytes = utf8.encode("photo $i edit $edit ${"x" * 1000}");
    var name = "IMG_$i.jpg";
    contents[name] = bytes;
    return PhotoItem(
      id: "id$i",
      name: name,
      takenAt: DateTime.utc(2026, 1, 1).add(Duration(minutes: i)),
      modifiedAt: DateTime.utc(2026, 2, 1).add(Duration(hours: edit)),
      length: bytes.length,
      openRead: () {
        counts.reads++;
        counts.bytes += bytes.length;
        return Stream.value(bytes);
      },
    );
  }

  /// Replaces photo [i] by its edited version, in the library and its album.
  void edit(int i) {
    var edited = photo(i, edit: 1);
    library.items[library.items.indexWhere((p) => p.id == edited.id)] = edited;
    var album = library.albumItems[camera]!;
    album[album.indexWhere((p) => p.id == edited.id)] = edited;
  }
}

/// A server that holds what it was given, counting what it is asked.
class Server {
  final Device device;

  /// The hashes the server holds.
  final Set<String> known = {};

  /// The file names of every accepted upload, one list per request.
  final List<List<String>> uploads = [];

  /// The number of uploads in flight now, and the most there ever were.
  int inFlight = 0;
  int mostInFlight = 0;

  /// How long an upload takes to answer.
  Duration delay = Duration.zero;

  /// Fails the upload holding this file name, once.
  String? failOn;

  Server(this.device);

  /// Holds every photo of the device.
  void holdAll() => known.addAll(device.contents.values.map(hashOf));

  http.Client get client => MockClient((request) async {
        if (request.url.queryParameters["type"] == "auth") {
          return http.Response(
              '{"mode":"writes","role":"admin","inbox":"Inbox"}', 200);
        }
        if (request.method == "POST") {
          device.counts.checks++;
          var asked = [
            for (var entry in (jsonDecode(request.body)["hashes"] as List))
              "${entry["hash"]}"
          ];
          return http.Response(
              '{"present":[${asked.where(known.contains).map((hash) => '{"hash":"$hash","name":"x.jpg"}').join(",")}]}',
              200);
        }
        if (request.method == "PUT") {
          device.counts.uploads++;
          inFlight++;
          if (inFlight > mostInFlight) {
            mostInFlight = inFlight;
          }
          try {
            await Future<void>.delayed(delay);
            var names = [
              for (var match
                  in RegExp('filename="([^"]+)"').allMatches(request.body))
                match.group(1)!
            ];
            if (failOn != null && names.contains(failOn)) {
              failOn = null;
              return http.Response(
                  '["ErrorInfo",{"message":"Disk full."}]', 500);
            }
            uploads.add(names);
            known.addAll(
                [for (var name in names) hashOf(device.contents[name]!)]);
          } finally {
            inFlight--;
          }
        }
        return http.Response("", 200);
      });
}

CameraRollSync engine(
  Device device,
  Server server,
  InMemorySettingsStore store, {
  HashCache? cache,
  int batchSize = 10,
  int parallel = defaultParallelUploads,
  DiagnosticsLog? log,
}) {
  var client = VAlbumClient(
    dataUrl: serverDataUrl,
    httpClient: server.client,
    log: log,
  );
  return CameraRollSync(
    store: store,
    library: device.library,
    clientOf: () => client,
    callerOf: () async =>
        const CallerInfo(role: roleMember, inbox: "Inbox", inboxCount: 0),
    hashCache: cache,
    batchSize: batchSize,
    parallelUploads: parallel,
  );
}

InMemorySettingsStore freshStore() => InMemorySettingsStore()
  ..cameraRoll = const CameraRollConfig(enabled: true).toJson();

/// Completes when [sync] is idle again after a run it is about to start.
Future<void> idleAgain(CameraRollSync sync) {
  var done = Completer<void>();
  var ran = false;
  void listen() {
    if (sync.status.running) {
      ran = true;
    } else if (ran && !done.isCompleted) {
      sync.removeListener(listen);
      done.complete();
    }
  }

  sync.addListener(listen);
  return done.future;
}

void main() {
  group('a scan from the beginning over photos the server holds', () {
    test('reads no file where the hashes are remembered', () async {
      var device = Device(500);
      var server = Server(device)..holdAll();
      var cache = MemoryHashCache();
      var sync = engine(device, server, freshStore(), cache: cache);
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();
      // ignore: avoid_print
      print("first run: ${device.counts}");
      expect(device.counts.reads, 500, reason: "hashed once, in its life");
      expect(sync.lastRun!.hashed, 500);
      expect(device.counts.checks, 500 ~/ hashedPerCheck);
      expect(device.counts.uploads, 0);
      expect(sync.status.lastPresent, 500);
      device.counts.clear();

      await sync.syncNow();
      // ignore: avoid_print
      print("second run: ${device.counts}");
      expect(device.counts.reads, 0);
      expect(device.counts.checks, 0);
      device.counts.clear();

      // A newly ticked album holding the same photos is scanned from the
      // beginning (issue #117), and reads none of them.
      var other = device.library.addAlbum("Shared", device.photos);
      var finished = idleAgain(sync);
      await sync.chooseSources(
        [device.camera.id, other.id],
        albums: await device.library.albums(),
      );
      await finished;
      // ignore: avoid_print
      print("newly ticked album: ${device.counts}");
      expect(device.counts.reads, 0);
      expect(device.counts.bytes, 0);
      expect(sync.lastRun!.hashed, 0);
      expect(sync.lastRun!.cached, 500);
      expect(device.counts.checks, 1, reason: "one question for 500 hashes");
      expect(device.counts.uploads, 0);
      expect(sync.status.lastPresent, 500);
      device.counts.clear();

      // Forgetting the progress is a scan from the beginning as well.
      await sync.forgetProgress();
      await sync.syncNow();
      // ignore: avoid_print
      print("forgotten progress: ${device.counts}");
      expect(device.counts.reads, 0);
      // The second album's photos were handed over by the first in this run.
      expect(device.counts.checks, 1);
      device.counts.clear();

      // A reinstall: no configuration, no cache — every photo is hashed once
      // more, and nothing is uploaded.
      var reinstalled = engine(device, server, freshStore());
      addTearDown(reinstalled.dispose);
      await reinstalled.load();
      await reinstalled.syncNow();
      // ignore: avoid_print
      print("reinstall: ${device.counts}");
      expect(device.counts.reads, 500);
      expect(reinstalled.lastRun!.hashed, 500);
      expect(device.counts.uploads, 0);
      expect(reinstalled.status.lastPresent, 500);
    });

    test('hashes an edited photo again and uploads it', () async {
      var device = Device(20);
      var server = Server(device)..holdAll();
      var sync = engine(device, server, freshStore());
      addTearDown(sync.dispose);
      await sync.load();
      await sync.syncNow();
      device.counts.clear();

      device.edit(7);
      await sync.forgetProgress();
      await sync.syncNow();

      expect(device.counts.reads, 2, reason: "hashed, then uploaded");
      expect(sync.lastRun!.hashed, 1);
      expect(sync.lastRun!.cached, 19);
      expect(server.uploads, [
        ["IMG_7.jpg"],
      ]);
      expect(sync.status.lastStored, 1);
      expect(sync.status.lastPresent, 19);
    });
  });

  group('an interrupted run', () {
    test('keeps the watermark before the failed batch and resumes', () async {
      var device = Device(30);
      var server = Server(device)
        // The third batch: photos 10 to 14.
        ..failOn = "IMG_12.jpg";
      var store = freshStore();
      var sync = engine(device, server, store, batchSize: 5);
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(sync.status.phase, CameraRollPhase.waiting);
      expect(sync.status.message, "Disk full.");
      // Exactly where the batches one after the other left it: past the two
      // accepted batches, the last photo of the second one at the stamp.
      var config = await store.loadCameraRollConfig();
      var mark = config.markOf(device.camera.id);
      expect(mark.since, device.photos[9].takenAt);
      expect(mark.done, ["id9"]);
      // No batch is started after the failure; the one that was in flight
      // beside it may have arrived.
      var arrived = server.uploads.expand((names) => names).toSet();
      expect(arrived.containsAll([for (var i = 0; i < 10; i++) "IMG_$i.jpg"]),
          isTrue);
      expect(arrived.length, lessThanOrEqualTo(15));
      var before = {...arrived};
      server.uploads.clear();

      await sync.syncNow();

      expect(sync.status.phase, CameraRollPhase.idle);
      var resent = server.uploads.expand((names) => names).toList();
      expect(resent.toSet().intersection(before), isEmpty,
          reason: "nothing accepted is uploaded again");
      expect(
          {...before, ...resent}, {for (var i = 0; i < 30; i++) "IMG_$i.jpg"});
      mark = (await store.loadCameraRollConfig()).markOf(device.camera.id);
      expect(mark.since, device.photos[29].takenAt);
    });

    test('fails on an unreadable photo after transferring those before it',
        () async {
      var device = Device(8);
      var broken = PhotoItem(
        id: "id5",
        name: "IMG_5.jpg",
        takenAt: device.photos[5].takenAt,
        modifiedAt: DateTime.utc(2026, 2, 1),
        length: 10,
        openRead: () => Stream.error(
            const PhotoLibraryException(PhotoNotOnDevice("'IMG_5.jpg'"))),
      );
      device.library.items[5] = broken;
      device.library.albumItems[device.camera]![5] = broken;
      var server = Server(device);
      var store = freshStore();
      var sync = engine(device, server, store, batchSize: 2);
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(sync.status.phase, CameraRollPhase.waiting);
      expect(sync.status.notice, isA<PhotoNotOnDevice>());
      expect(server.uploads.expand((names) => names).toSet(),
          {for (var i = 0; i < 5; i++) "IMG_$i.jpg"});
      var mark = (await store.loadCameraRollConfig()).markOf(device.camera.id);
      expect(mark.since, device.photos[4].takenAt);
    });
  });

  test('never has more uploads in flight than the bound', () async {
    var device = Device(40);
    var server = Server(device)..delay = const Duration(milliseconds: 5);
    var sync = engine(device, server, freshStore(), batchSize: 2, parallel: 3);
    addTearDown(sync.dispose);
    await sync.load();

    await sync.syncNow();

    expect(sync.status.phase, CameraRollPhase.idle);
    expect(sync.status.lastStored, 40);
    expect(server.uploads, hasLength(20));
    expect(server.mostInFlight, lessThanOrEqualTo(3));
    expect(server.mostInFlight, greaterThan(1), reason: "they do overlap");
  });

  test('leaves one timing record per run in the diagnostics log', () async {
    var device = Device(30);
    var server = Server(device)
      ..known.addAll([
        for (var i = 0; i < 20; i++) hashOf(device.contents["IMG_$i.jpg"]!)
      ]);
    var log = DiagnosticsLog();
    var sync = engine(device, server, freshStore(), log: log);
    addTearDown(sync.dispose);
    await sync.load();

    await sync.syncNow();

    int bytesOf(Iterable<int> photos) => [
          for (var i in photos) device.contents["IMG_$i.jpg"]!.length
        ].fold(0, (a, b) => a + b);
    expect(log.entries, hasLength(1));
    var entry = log.entries.single;
    expect(entry.headline, startsWith("Camera-roll sync looked at 30 photos"));
    expect(entry.facts, [
      "Hashes: 0 remembered, 30 computed (${bytesOf([
            for (var i = 0; i < 30; i++) i
          ])} bytes read)",
      "Checks: 1 (30 hashes)",
      "Uploads: 1 batches, 10 photos (${bytesOf([
            for (var i = 20; i < 30; i++) i
          ])} bytes)",
      startsWith("Time: scan "),
      "Outcome: idle",
    ]);

    // A run with nothing new to look at writes nothing.
    await sync.syncNow();
    expect(log.entries, hasLength(1));
  });

  test('forgets the photos a full scan no longer finds', () async {
    var device = Device(10);
    var server = Server(device)..holdAll();
    var cache = MemoryHashCache();
    var sync = engine(device, server, freshStore(), cache: cache);
    addTearDown(sync.dispose);
    await sync.load();
    await sync.syncNow();
    expect(cache.length, 10);

    device.library.items.removeWhere((p) => p.id == "id3");
    device.library.albumItems[device.camera]!.removeWhere((p) => p.id == "id3");
    await sync.forgetProgress();
    await sync.syncNow();

    expect(cache.length, 9);
    expect(cache.entries.containsKey("id3"), isFalse);
  });

  group('FileHashCache', () {
    late Directory dir;
    setUp(() => dir = Directory.systemTemp.createTempSync("hashes"));
    tearDown(() => dir.deleteSync(recursive: true));

    PhotoItem item(String id, {int modified = 1, int length = 3}) => PhotoItem(
          id: id,
          name: "$id.jpg",
          takenAt: DateTime.utc(2026),
          modifiedAt:
              DateTime.fromMillisecondsSinceEpoch(modified, isUtc: true),
          length: length,
          openRead: () => const Stream.empty(),
        );
    String hash(int n) => hashOf([n]);

    test('survives a restart, and misses a changed photo', () async {
      var file = File("${dir.path}/h.txt");
      var cache = FileHashCache.inFile(file);
      expect(await cache.lookup(item("a")), isNull);
      cache.put(item("a"), hash(1));
      cache.put(item("b/with\ttab"), hash(2));
      await cache.flush();

      var again = FileHashCache.inFile(file);
      expect(await again.lookup(item("a")), hash(1));
      expect(await again.lookup(item("b/with\ttab")), hash(2));
      expect(await again.lookup(item("a", modified: 2)), isNull);
      expect(await again.lookup(item("a", length: 4)), isNull);
    });

    test('appends one write per flush and the later line wins', () async {
      var file = File("${dir.path}/h.txt");
      var cache = FileHashCache.inFile(file);
      await cache.lookup(item("a"));
      cache.put(item("a"), hash(1));
      await cache.flush();
      cache.put(item("a", modified: 2), hash(2));
      await cache.flush();

      expect(file.readAsLinesSync(), hasLength(3));
      var again = FileHashCache.inFile(file);
      expect(await again.lookup(item("a", modified: 2)), hash(2));
      expect(again.length, 1);
    });

    test('skips a damaged line and starts over on another format', () async {
      var file = File("${dir.path}/h.txt");
      file.writeAsStringSync("$hashCacheHeader\n"
          "1\t3\t${hash(1)}\ta\n"
          "garbage\n"
          "1\t3\tnot-a-hash\tb\n");
      var cache = FileHashCache.inFile(file);
      expect(await cache.lookup(item("a")), hash(1));
      expect(await cache.lookup(item("b")), isNull);

      file.writeAsStringSync("other format\n1\t3\t${hash(1)}\ta\n");
      expect(await FileHashCache.inFile(file).lookup(item("a")), isNull);
    });

    test('rewrites the file without the photos that are gone', () async {
      var file = File("${dir.path}/h.txt");
      var cache = FileHashCache.inFile(file);
      await cache.lookup(item("a"));
      for (var id in ["a", "b", "c"]) {
        cache.put(item(id), hash(id.codeUnitAt(0)));
      }
      await cache.flush();

      await cache.retainOnly({"a", "c"});

      expect(file.readAsLinesSync(), hasLength(3));
      var again = FileHashCache.inFile(file);
      expect(await again.lookup(item("b")), isNull);
      expect(await again.lookup(item("c")), hash("c".codeUnitAt(0)));
    });

    test('never remembers a photo without a modification time', () async {
      var cache = FileHashCache.inFile(File("${dir.path}/h.txt"));
      var plain = fakePhoto("p.jpg", [1, 2, 3], takenAt: DateTime.utc(2026));
      cache.put(plain, hash(1));
      expect(await cache.lookup(plain), isNull);
      expect(cache.length, 0);
    });
  });

  test('hashes a file in an isolate of its own, as the stream does', () async {
    var dir = Directory.systemTemp.createTempSync("hashfile");
    addTearDown(() => dir.deleteSync(recursive: true));
    var file = File("${dir.path}/photo.jpg");
    var bytes = List<int>.generate(300000, (i) => i % 251);
    file.writeAsBytesSync(bytes);

    var result = await sha256OfFileInIsolate(file.path);

    expect(result.hash, await sha256Of(Stream.value(bytes)));
    expect(result.bytes, bytes.length);
  });

  test('hashes an item that names its file through the injected hasher',
      () async {
    var device = Device(3);
    var paths = <String>[];
    var withFiles = [
      for (var photo in device.photos)
        PhotoItem(
          id: photo.id,
          name: photo.name,
          takenAt: photo.takenAt,
          modifiedAt: photo.modifiedAt,
          length: photo.length,
          openRead: photo.openRead,
          filePath: () async => "/files/${photo.name}",
        ),
    ];
    var library = FakePhotoLibrary()
      ..addAlbum("Camera", withFiles, camera: true);
    var server = Server(device)..holdAll();
    var client =
        VAlbumClient(dataUrl: serverDataUrl, httpClient: server.client);
    var sync = CameraRollSync(
      store: freshStore(),
      library: library,
      clientOf: () => client,
      callerOf: () async =>
          const CallerInfo(role: roleMember, inbox: "Inbox", inboxCount: 0),
      hashFile: (path) async {
        paths.add(path);
        var name = path.substring("/files/".length);
        return (
          hash: hashOf(device.contents[name]!),
          bytes: device.contents[name]!.length,
        );
      },
    );
    addTearDown(sync.dispose);
    await sync.load();

    await sync.syncNow();

    expect(paths, ["/files/IMG_0.jpg", "/files/IMG_1.jpg", "/files/IMG_2.jpg"]);
    expect(device.counts.reads, 0, reason: "nothing read on this isolate");
    expect(sync.status.lastPresent, 3);
  });
}
