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
