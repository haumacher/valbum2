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
  String? accessProblem;

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
      accessProblem = "The photo library cannot be opened: $error";
      return false;
    }
    if (state.isAuth || state == PermissionState.limited) {
      // "Limited" is a legitimate answer: the user picked the photos this app
      // may see, and those are the ones that get uploaded.
      accessProblem = null;
      return true;
    }
    accessProblem =
        "Access to the photo library was denied. Allow photo access for "
        "VAlbum in the system settings, then try again.";
    return false;
  }

  @override
  Future<List<PhotoItem>> itemsSince(DateTime? since) async {
    var filter = FilterOptionGroup(
      createTimeCond: DateTimeCond(
        min: since ?? DateTime.fromMillisecondsSinceEpoch(0),
        // The future holds no photos, but a clock that is off by a minute
        // would hide the one just taken.
        max: DateTime.now().add(const Duration(days: 1)),
      ),
      orders: const [OrderOption(type: OrderOptionType.createDate, asc: true)],
    );

    var result = <PhotoItem>[];
    for (var page = 0; result.length < photoScanLimit; page++) {
      var assets = await PhotoManager.getAssetListPaged(
        page: page,
        pageCount: _pageSize,
        filterOption: filter,
        type: RequestType.common,
      );
      for (var asset in assets) {
        result.add(await _item(asset));
        if (result.length >= photoScanLimit) {
          break;
        }
      }
      if (assets.length < _pageSize) {
        break;
      }
    }
    return result;
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
      accessProblem = "The photo library cannot be read: $error";
      return const [];
    }
    var albums = <PhotoAlbum>[];
    for (var path in paths) {
      var count = await path.assetCountAsync;
      if (count == 0) {
        continue;
      }
      albums.add(PhotoAlbum(id: path.id, name: path.name, count: count));
      _paths[path.id] = path;
    }
    albums.sort((a, b) => b.count.compareTo(a.count));
    return albums;
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
      throw StateError(
        "The contents of '${asset.title ?? asset.id}' are not on this device "
        "yet (still in the cloud?).",
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
