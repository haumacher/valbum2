/// The [PhotoLibrary] of Android and iOS, backed by `photo_manager`.
///
/// Reached only through the conditional import in `platform.dart`, and only
/// after the platform was checked: `photo_manager` is a plugin with no
/// implementation on the desktops or in a browser, so nothing but a phone ever
/// constructs this.
library;

import 'dart:async';
import 'package:flutter/services.dart';
import 'package:photo_manager/photo_manager.dart';

import 'photo_library.dart';
import 'notices.dart';

/// The number of items one scan looks at.
///
/// A first run on a full phone must not build ten thousand upload descriptors
/// before it transfers the first photo. The watermark advances with every
/// batch, so the next run continues where this one stopped — and the engine
/// asks for that next run itself as long as a run still transferred something.
const int photoScanLimit = 500;

/// The page size the platform is asked with.
const int _pageSize = 100;

/// The device's photo library on Android and iOS.
class PhotoManagerLibrary extends PhotoLibrary {
  @override
  AppNotice? accessProblem;

  /// The album paths the last [albums] answered, by their id.
  final Map<String, AssetPathEntity> _paths = {};

  final StreamController<void> _changes = StreamController<void>.broadcast();
  bool _watching = false;

  PhotoManagerLibrary();

  @override
  int get scanLimit => photoScanLimit;

  @override
  Future<bool> requestAccess() async {
    PermissionState state;
    try {
      state = await PhotoManager.requestPermissionExtend();
    } catch (error) {
      accessProblem = PhotoLibraryOpenFailed("$error");
      return false;
    }
    if (state.isAuth || state == PermissionState.limited) {
      // "Limited" is a legitimate answer: the user picked the photos this app
      // may see, and those are the ones that get uploaded.
      accessProblem = null;
      return true;
    }
    accessProblem = const PhotoAccessDenied();
    return false;
  }

  @override
  Future<List<PhotoItem>> itemsSince(
    DateTime? since, {
    List<String> sources = const [],
  }) async {
    var filter = FilterOptionGroup(
      createTimeCond: DateTimeCond(
        min: since ?? DateTime.fromMillisecondsSinceEpoch(0),
        // The future holds no photos, but a clock that is off by a minute
        // would hide the one just taken.
        max: DateTime.now().add(const Duration(days: 1)),
      ),
      orders: const [OrderOption(type: OrderOptionType.createDate, asc: true)],
    );

    if (sources.isEmpty) {
      return _paged(
        (page) => PhotoManager.getAssetListPaged(
          page: page,
          pageCount: _pageSize,
          filterOption: filter,
          type: RequestType.common,
        ),
      );
    }

    // One album at a time (issue #117): asking without a path is asking for
    // the whole library — the camera, and every folder any app ever wrote a
    // picture into.
    var result = <PhotoItem>[];
    var seen = <String>{};
    for (var source in sources) {
      var path = await _pathOf(source);
      if (path == null) {
        // An album that is no longer there is not a reason to fail the run:
        // the user removed it on the device, and the section shows the
        // albums it does have.
        continue;
      }
      // The filter of the path decides what a paged request answers, so the
      // date bound and the order are put on the path itself.
      var bounded = await path.fetchPathProperties(filterOptionGroup: filter);
      if (bounded == null) {
        continue;
      }
      for (var item in await _paged(
        (page) => bounded.getAssetListPaged(page: page, size: _pageSize),
        limit: photoScanLimit - result.length,
      )) {
        // A photo can lie in two of the chosen albums; it is one item.
        if (seen.add(item.id)) {
          result.add(item);
        }
      }
      if (result.length >= photoScanLimit) {
        break;
      }
    }
    result.sort((a, b) => a.takenAt.compareTo(b.takenAt));
    return result;
  }

  /// The items of a paged source, at most [limit] of them.
  Future<List<PhotoItem>> _paged(
    Future<List<AssetEntity>> Function(int page) page, {
    int limit = photoScanLimit,
  }) async {
    var result = <PhotoItem>[];
    for (var index = 0; result.length < limit; index++) {
      var assets = await page(index);
      for (var asset in assets) {
        result.add(await _item(asset));
        if (result.length >= limit) {
          break;
        }
      }
      if (assets.length < _pageSize) {
        break;
      }
    }
    return result;
  }

  /// The album of the given [PhotoAlbum.id], `null` where the device has none
  /// of that id any more.
  Future<AssetPathEntity?> _pathOf(String id) async {
    var path = _paths[id];
    if (path != null) {
      return path;
    }
    // The albums were never listed in this process (a background run), or the
    // library changed under the sync: ask again before giving up.
    await albums();
    return _paths[id];
  }

  @override
  Future<List<PhotoAlbum>> albums() async {
    List<AssetPathEntity> paths;
    try {
      paths = await PhotoManager.getAssetPathList(
        type: RequestType.common,
        filterOption: FilterOptionGroup(
          orders: const [
            OrderOption(type: OrderOptionType.createDate, asc: false),
          ],
        ),
      );
    } catch (error) {
      accessProblem = PhotoLibraryFailed("$error");
      return const [];
    }
    var albums = <PhotoAlbum>[];
    for (var path in paths) {
      var count = await path.assetCountAsync;
      if (count == 0) {
        continue;
      }
      albums.add(PhotoAlbum(
        id: path.id,
        name: path.name,
        count: count,
        isAll: path.isAll,
        isCamera: isCameraPath(path),
      ));
      _paths[path.id] = path;
    }
    albums.sort((a, b) => b.count.compareTo(a.count));
    return albums;
  }

  /// Whether [path] is the album the device's camera writes into (issue
  /// #117).
  ///
  /// The two platforms name it differently, and `photo_manager` reports what
  /// each of them says:
  ///
  /// * **Android** keeps the camera's pictures in `DCIM/Camera`, whose
  ///   `MediaStore` bucket is reported with the display name of the directory
  ///   — `Camera`. It is an ordinary album beside `WhatsApp Images`,
  ///   `Screenshots` and the rest, and it is never the `isAll` pseudo-album.
  /// * **iOS/macOS** has no such folder: the camera writes into the *user
  ///   library*, the smart album the Photos app shows as *Recents*
  ///   ([PMDarwinAssetCollectionSubtype.smartAlbumUserLibrary]). That album
  ///   *is* everything the device holds — which is why it is also the
  ///   `isAll` album there — and it is exactly what the sync uploaded before
  ///   issue #117, so an iPhone that is updated keeps doing what it did and
  ///   the section says so. Ticking a narrower album instead is one tap away.
  static bool isCameraPath(AssetPathEntity path) {
    if (path.albumTypeEx?.darwin?.subtype ==
        PMDarwinAssetCollectionSubtype.smartAlbumUserLibrary) {
      return true;
    }
    return !path.isAll && path.name.trim().toLowerCase() == "camera";
  }

  @override
  Future<List<PhotoItem>> itemsOf(PhotoAlbum album) async {
    var path = _paths[album.id];
    if (path == null) {
      // The albums were never listed (or the library changed under the
      // picker): ask again rather than answering an empty album.
      await albums();
      path = _paths[album.id];
      if (path == null) {
        return const [];
      }
    }
    var result = <PhotoItem>[];
    var total = await path.assetCountAsync;
    for (var start = 0; start < total; start += _pageSize) {
      var end = start + _pageSize > total ? total : start + _pageSize;
      var assets = await path.getAssetListRange(start: start, end: end);
      for (var asset in assets) {
        result.add(await _item(asset));
      }
      if (assets.isEmpty) {
        break;
      }
    }
    result.sort((a, b) => b.takenAt.compareTo(a.takenAt));
    return result;
  }

  @override
  Future<Uint8List?> thumbnail(PhotoItem item, {int size = 256}) async {
    try {
      var asset = await AssetEntity.fromId(item.id);
      return await asset?.thumbnailDataWithSize(ThumbnailSize.square(size));
    } catch (_) {
      // A tile without a picture is a tile the user can still select; the
      // picker draws a placeholder for it.
      return null;
    }
  }

  /// The upload description of one asset.
  ///
  /// The contents are opened lazily: an asset that lives in iCloud is fetched
  /// when it is transferred, not while the library is scanned.
  Future<PhotoItem> _item(AssetEntity asset) async {
    var name = asset.title ?? await asset.titleAsync;
    return PhotoItem(
      id: asset.id,
      name: name.isEmpty ? "${asset.id}.jpg" : name,
      takenAt: asset.createDateTime,
      length: await asset.fileSize,
      openRead: () => _read(asset),
    );
  }

  Stream<List<int>> _read(AssetEntity asset) async* {
    var file = await asset.originFile;
    if (file == null) {
      // Not a reason to skip it: skipping would advance the watermark past a
      // photo that was never uploaded. The run fails, says so, and retries.
      throw PhotoLibraryException(
        PhotoNotOnDevice("'${asset.title ?? asset.id}'"),
      );
    }
    yield* file.openRead();
  }

  @override
  Stream<void> get changes {
    if (!_watching) {
      _watching = true;
      PhotoManager.addChangeCallback(_changed);
      unawaited(PhotoManager.startChangeNotify());
    }
    return _changes.stream;
  }

  void _changed(MethodCall call) {
    if (!_changes.isClosed) {
      _changes.add(null);
    }
  }

  @override
  void dispose() {
    if (_watching) {
      PhotoManager.removeChangeCallback(_changed);
      unawaited(PhotoManager.stopChangeNotify());
      _watching = false;
    }
    _changes.close();
  }
}
