/// The platform pieces of the offline support on everything but the web.
library;

import 'dart:io';

import 'package:flutter/foundation.dart';

import 'background.dart';
import 'background_workmanager.dart';
import 'offline.dart';
import 'offline_file.dart';
import 'photo_library.dart';
import 'photo_library_manager.dart';

/// The cache the app uses when it is not told otherwise: a directory on the
/// device, so that what was seen survives the app being closed.
OfflineCache defaultOfflineCache() => FileOfflineCache.underSupportDirectory();

/// Whether the given error is the network itself failing.
///
/// `package:http` wraps most of these in a `ClientException`, but not all of
/// them (a failed DNS lookup surfaces as a plain `SocketException` on some
/// platforms), so the `dart:io` type is checked as well.
bool isSocketError(Object error) => error is SocketException;

/// The photo library of the device, see [PhotoLibrary].
///
/// Only Android and iOS have a camera roll to watch; a desktop has a file
/// system and no library, and says so rather than offering a switch that
/// would never do anything (issue #30).
PhotoLibrary defaultPhotoLibrary() => Platform.isAndroid || Platform.isIOS
    ? PhotoManagerLibrary()
    : const UnavailablePhotoLibrary(
        "No photo library on this platform - camera-roll sync runs on "
        "Android and iOS.",
      );

/// The platform's periodic background execution, see [BackgroundScheduler].
///
/// Only Android and iOS run background work this app can use; a desktop says
/// so instead of promising a sync that never happens (issue #32).
///
/// `defaultTargetPlatform` rather than `Platform.isAndroid`: this is the same
/// question `photo_manager` is asked, and the Flutter constant is the one a
/// test can override.
BackgroundScheduler defaultBackgroundScheduler() =>
    defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS
        ? WorkmanagerScheduler()
        : const UnavailableBackgroundScheduler(
            "Background sync is not available on this platform; the camera "
            "roll syncs while the app is open.",
          );

/// Runs [task] as the platform's background task, see
/// [backgroundSyncDispatcher].
///
/// Named here rather than imported from `workmanager` directly, so that the
/// web build never sees the plugin's Dart code.
void executeBackgroundTask(Future<bool> Function() task) =>
    runWorkmanagerTask(task);
