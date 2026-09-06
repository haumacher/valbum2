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

import 'client.dart';

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
  String? get accessProblem;

  /// The items taken at or after [since], oldest first.
  ///
  /// `null` asks for the whole library: that is what a freshly installed app
  /// does. The bound is inclusive, so the newest item of the previous run
  /// shows up again; the sync knows it by its [PhotoItem.id] and does not
  /// offer it a second time.
  Future<List<PhotoItem>> itemsSince(DateTime? since);

  /// The greatest number of items one [itemsSince] may answer with, `0` when
  /// it always answers with everything.
  ///
  /// A platform library is scanned in bounded chunks so that a first run on a
  /// full phone starts transferring before it has described ten thousand
  /// photos; the sync then knows that a full answer may have left more behind
  /// and scans again after the watermark advanced.
  int get scanLimit => 0;

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
  final String accessProblem;

  @override
  bool get available => false;

  const UnavailablePhotoLibrary([
    this.accessProblem = "No photo library on this platform",
  ]);

  @override
  Future<bool> requestAccess() async => false;

  @override
  Future<List<PhotoItem>> itemsSince(DateTime? since) async => const [];

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
  String? accessProblem;

  /// The number of times [itemsSince] was asked, and with which bound.
  final List<DateTime?> scans = [];

  final StreamController<void> _changes = StreamController<void>.broadcast();

  FakePhotoLibrary({
    List<PhotoItem>? items,
    this.granted = true,
    this.accessProblem,
  }) : items = [...?items];

  @override
  Future<bool> requestAccess() async => granted;

  @override
  Future<List<PhotoItem>> itemsSince(DateTime? since) async {
    scans.add(since);
    var result = [
      for (var item in items)
        if (since == null || !item.takenAt.isBefore(since)) item
    ];
    result.sort((a, b) => a.takenAt.compareTo(b.takenAt));
    return result;
  }

  @override
  Stream<void> get changes => _changes.stream;

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
