/// The device's photo library, behind an interface (issue #30).
///
/// Camera-roll sync needs three things from a platform: permission to look at
/// the photos, the items taken since a point in time, and a notification when
/// the library changes. That is all [PhotoLibrary] promises, so the sync
/// engine ([CameraRollSync]) can be tested against a [FakePhotoLibrary] and
/// every platform that has no photo library at all is served by
/// [UnavailablePhotoLibrary] rather than by a stub that pretends.
///
/// The platform-backed implementation is selected by the conditional import in
/// `platform.dart`; see `photo_library_manager.dart` for Android and iOS.
library;

import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/widgets.dart';

import 'client.dart';
import 'notices.dart';

/// A failure of the device's photo library, carrying its own reason.
///
/// The reason as data, never as words ([AppNotice]): what it reads is decided
/// where it is shown — the camera-roll section, in the language of the device
/// — so this exception is the one way a platform library tells the engine why
/// it could not answer, see issue #108.
class PhotoLibraryException implements Exception {
  /// Why the library could not answer.
  final AppNotice notice;

  const PhotoLibraryException(this.notice);

  @override
  String toString() => "PhotoLibraryException($notice)";
}

/// One item of the device's photo library.
///
/// The contents are reached through [openRead] only: a video must never be
/// pulled into memory as a whole, neither for hashing nor for uploading, see
/// [sha256Of] and [VAlbumClient.uploadFiles].
class PhotoItem {
  /// The identity of the item on the device.
  ///
  /// Stable across runs of the app, so that an item already uploaded is
  /// recognised again, see [CameraRollConfig.done].
  final String id;

  /// The file name to announce to the server, e.g. `IMG_0417.JPG`.
  final String name;

  /// When the photo was taken.
  ///
  /// The watermark of the sync is a taken-at stamp, so this is what orders the
  /// items, see [PhotoLibrary.itemsSince].
  final DateTime takenAt;

  /// The number of bytes [openRead] produces.
  final int length;

  /// Opens the contents of the item.
  final Stream<List<int>> Function() openRead;

  const PhotoItem({
    required this.id,
    required this.name,
    required this.takenAt,
    required this.length,
    required this.openRead,
  });

  /// The upload of this item.
  UploadFile get upload =>
      UploadFile(name: name, length: length, openRead: openRead);
}

/// One album of the device's photo library, see [PhotoLibrary.albums].
///
/// What the phone's gallery calls an album: the camera roll, a folder of
/// downloads, an album the user made. The in-app picker of issue #64 lists
/// these and shows the items of the one that was tapped.
@immutable
class PhotoAlbum {
  /// The identity of the album on the device.
  final String id;

  /// What the album is called, as the device calls it.
  final String name;

  /// The number of items in it.
  final int count;

  /// Whether this is the platform's "all photos" pseudo-album.
  ///
  /// Android calls it *Recent*, iOS *Recents*: it is not a place photos are
  /// kept but the whole library under one name. Watching it (issue #117)
  /// would be watching everything, which is the very thing the user asked to
  /// be rid of, so [CameraRollConfig.sources] never offers it — unless it is
  /// also the camera roll, see [isCamera].
  final bool isAll;

  /// Whether this is the album the device's camera writes into.
  ///
  /// The one album the sync watches by default, see
  /// [CameraRollConfig.sources]: what the phone took is what one wants in the
  /// library, and what an app received is not.
  final bool isCamera;

  const PhotoAlbum({
    required this.id,
    required this.name,
    this.count = 0,
    this.isAll = false,
    this.isCamera = false,
  });

  @override
  bool operator ==(Object other) => other is PhotoAlbum && other.id == id;

  @override
  int get hashCode => id.hashCode;

  @override
  String toString() => "PhotoAlbum($id, $name, $count)";
}

/// The photo library of the device the app runs on.
abstract class PhotoLibrary {
  const PhotoLibrary();

  /// Whether this platform has a photo library at all.
  ///
  /// `false` makes the camera-roll section of the settings say so and disable
  /// its switch, rather than offering a sync that could never run.
  bool get available => true;

  /// Asks the platform for access to the photo library.
  ///
  /// Returns whether the app may read the library; when it may not,
  /// [accessProblem] says why — the user is shown that reason, never an empty
  /// screen, see the "refusals speak" rule.
  Future<bool> requestAccess();

  /// Why the library cannot be read, `null` while nothing refused it.
  ///
  /// The reason as data, never as words: it is shown by a view, which turns
  /// it into a sentence in the language of the device, see [noticeText]
  /// (issue #108).
  AppNotice? get accessProblem;

  /// The items taken at or after [since], oldest first.
  ///
  /// `null` asks from the beginning of time: that is what a freshly installed
  /// app does. The bound is inclusive, so the newest item of the previous run
  /// shows up again; the sync knows it by its [PhotoItem.id] and does not
  /// offer it a second time.
  ///
  /// [sources] names the albums to look in, by [PhotoAlbum.id] (issue #117):
  /// the camera-roll sync watches the albums the user ticked, not the whole
  /// library. Each named album is asked with the same bound and the same
  /// order, and an item that lies in two of them is answered once. An empty
  /// list asks the whole library, which is what every caller but the sync
  /// wants — and what the sync did before there was anything to tick.
  Future<List<PhotoItem>> itemsSince(
    DateTime? since, {
    List<String> sources = const [],
  });

  /// The greatest number of items one [itemsSince] may answer with, `0` when
  /// it always answers with everything.
  ///
  /// A platform library is scanned in bounded chunks so that a first run on a
  /// full phone starts transferring before it has described ten thousand
  /// photos; the sync then knows that a full answer may have left more behind
  /// and scans again after the watermark advanced.
  int get scanLimit => 0;

  /// The albums of the device, the fullest one first (issue #64).
  ///
  /// Empty where the platform has none, so that the picker says "nothing to
  /// choose from" rather than failing: a library that cannot be read is a
  /// refusal, and it speaks through [accessProblem].
  Future<List<PhotoAlbum>> albums() async => const [];

  /// The items of one album, newest first (issue #64).
  Future<List<PhotoItem>> itemsOf(PhotoAlbum album) async => const [];

  /// A small preview of [item], `null` where the platform has none.
  ///
  /// The default reads the whole item, which is what a test's fake library
  /// holds anyway; a real library answers a thumbnail the platform has
  /// already made, see `photo_library_manager.dart`.
  Future<Uint8List?> thumbnail(PhotoItem item, {int size = 256}) async {
    var bytes = <int>[];
    await for (var chunk in item.openRead()) {
      bytes.addAll(chunk);
    }
    return Uint8List.fromList(bytes);
  }

  /// Fires whenever the library changed.
  ///
  /// May never fire — a platform without change notifications simply relies on
  /// the periodic scan of the sync engine.
  Stream<void> get changes;

  /// Releases whatever the platform holds.
  void dispose() {}
}

/// The [PhotoLibrary] of a platform that has none.
///
/// The web build and the desktop builds get this: there is no camera roll to
/// watch, and saying so plainly is better than a switch that never does
/// anything.
class UnavailablePhotoLibrary extends PhotoLibrary {
  @override
  final AppNotice accessProblem;

  @override
  bool get available => false;

  const UnavailablePhotoLibrary([
    this.accessProblem = const NoPhotoLibraryHere(),
  ]);

  @override
  Future<bool> requestAccess() async => false;

  @override
  Future<List<PhotoItem>> itemsSince(
    DateTime? since, {
    List<String> sources = const [],
  }) async =>
      const [];

  @override
  Future<Uint8List?> thumbnail(PhotoItem item, {int size = 256}) async => null;

  @override
  Stream<void> get changes => const Stream<void>.empty();
}

/// A [PhotoLibrary] the test (or an embedder) fills itself.
///
/// Lives in `lib/` rather than in `test/` on purpose: an app embedding VAlbum
/// can hand the sync engine its own source of photos this way.
class FakePhotoLibrary extends PhotoLibrary {
  /// What the library holds, in any order.
  final List<PhotoItem> items;

  /// Whether [requestAccess] grants access.
  bool granted;

  @override
  AppNotice? accessProblem;

  /// The number of times [itemsSince] was asked, and with which bound.
  final List<DateTime?> scans = [];

  /// The albums every [itemsSince] was asked for, in the same order as
  /// [scans]; empty where the whole library was asked for (issue #117).
  final List<List<String>> scannedSources = [];

  /// The albums this library holds, with their items, in order.
  final Map<PhotoAlbum, List<PhotoItem>> albumItems = {};

  final StreamController<void> _changes = StreamController<void>.broadcast();

  FakePhotoLibrary({
    List<PhotoItem>? items,
    this.granted = true,
    this.accessProblem,
  }) : items = [...?items];

  @override
  Future<bool> requestAccess() async => granted;

  @override
  Future<List<PhotoItem>> itemsSince(
    DateTime? since, {
    List<String> sources = const [],
  }) async {
    scans.add(since);
    scannedSources.add([...sources]);
    var candidates = items;
    if (sources.isNotEmpty) {
      // The same item may lie in two of the named albums; it is answered
      // once, as the platform-backed library answers it once.
      var seen = <String>{};
      candidates = [
        for (var id in sources)
          for (var item in albumItems[PhotoAlbum(id: id, name: id)] ?? const [])
            if (seen.add(item.id)) item
      ];
    }
    var result = [
      for (var item in candidates)
        if (since == null || !item.takenAt.isBefore(since)) item
    ];
    result.sort((a, b) => a.takenAt.compareTo(b.takenAt));
    return result;
  }

  @override
  Stream<void> get changes => _changes.stream;

  @override
  Future<List<PhotoAlbum>> albums() async => albumItems.keys.toList();

  @override
  Future<List<PhotoItem>> itemsOf(PhotoAlbum album) async =>
      [...?albumItems[album]];

  /// Adds an album holding the given items, and the items themselves.
  ///
  /// An item that is already there (the same [PhotoItem.id]) is not added a
  /// second time: a photo can lie in two albums, and the library holds it
  /// once.
  PhotoAlbum addAlbum(
    String name,
    List<PhotoItem> contents, {
    String? id,
    bool camera = false,
    bool all = false,
  }) {
    var album = PhotoAlbum(
      id: id ?? name,
      name: name,
      count: contents.length,
      isCamera: camera,
      isAll: all,
    );
    albumItems[album] = [...contents];
    var known = {for (var item in items) item.id};
    items.addAll([
      for (var item in contents)
        if (known.add(item.id)) item
    ]);
    return album;
  }

  /// Adds an item and announces the change, as the device would.
  void add(PhotoItem item) {
    items.add(item);
    announceChange();
  }

  /// Announces that the library changed, without adding anything.
  void announceChange() {
    if (!_changes.isClosed) {
      _changes.add(null);
    }
  }

  @override
  void dispose() => _changes.close();
}

/// A photo of [contents] bytes, for tests and demos.
PhotoItem fakePhoto(
  String name,
  List<int> contents, {
  required DateTime takenAt,
  String? id,
}) =>
    PhotoItem(
      id: id ?? name,
      name: name,
      takenAt: takenAt,
      length: contents.length,
      openRead: () => Stream.value(contents),
    );

/// Makes the device's [PhotoLibrary] available to the widget tree.
///
/// The camera-roll sync reaches it through its engine; the in-app picker of
/// issue #64 is a screen and reaches it here, so that a test can pump the app
/// with a [FakePhotoLibrary] and drive the picker.
class PhotoLibraryScope extends InheritedWidget {
  /// The library of the device the app runs on.
  final PhotoLibrary library;

  const PhotoLibraryScope({
    super.key,
    required this.library,
    required super.child,
  });

  /// The library of the enclosing app, `null` outside one (a view pumped on
  /// its own in a test).
  static PhotoLibrary? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<PhotoLibraryScope>()?.library;

  @override
  bool updateShouldNotify(PhotoLibraryScope oldWidget) =>
      library != oldWidget.library;
}
