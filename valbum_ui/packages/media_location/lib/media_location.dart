/// Which media permissions this app holds: whether it may read where the
/// photos were taken (issue #169), and whether it may read them at all.
///
/// Since Android 10 the media provider hands an app without
/// `ACCESS_MEDIA_LOCATION` a copy of every photograph with its position
/// zero-filled (issue #166). `photo_manager` reports the photo permission
/// without it — its `getPermissionState` ignores `mediaLocation` on Android —
/// and asking through `requestPermissionExtend` needs an Activity, which a
/// background run does not have. This plugin answers the one question
/// `Context.checkSelfPermission` answers, which needs none.
///
/// It is registered like every other plugin through the generated plugin
/// registrant, so it reaches the engine WorkManager starts for a background
/// run as well as the Activity's.
library;

import 'package:flutter/services.dart';

/// The channel the Android side answers on.
const MethodChannel mediaLocationChannel =
    MethodChannel("de.haumacher.valbum/media_location");

/// The one method of [mediaLocationChannel].
const String mediaLocationHeldMethod = "isGranted";

/// Whether the app holds `ACCESS_MEDIA_LOCATION`.
///
/// Answered `true` below Android 10, where there is no such permission and
/// nothing is redacted. Only to be asked on Android: no other platform
/// implements the channel.
Future<bool> isMediaLocationGranted() async =>
    await mediaLocationChannel.invokeMethod<bool>(mediaLocationHeldMethod) ??
    false;

/// The method of [mediaLocationChannel] that answers [MediaAccess].
const String mediaAccessMethod = "mediaAccess";

/// How much of the photos and videos the app may read, see [readMediaAccess].
enum MediaAccess {
  /// Every photo and video.
  full,

  /// The ones the user picked (Android 14 and newer).
  limited,

  /// None of them.
  none,
}

/// Which photos and videos this app may read, asked of the platform directly
/// through `Context.checkSelfPermission` of the application context.
///
/// `photo_manager`'s own `getPermissionState` cannot be asked in the engine
/// WorkManager starts for a background run: it reads through the Activity it
/// was bound to, which that engine has none of, and answers a
/// `NullPointerException` ("… getClass() on a null object reference").
///
/// Throws what the platform threw; an answer this version does not know is a
/// [FormatException] — never is an unknown answer read as "granted". Only to
/// be asked on Android: no other platform implements the channel.
Future<MediaAccess> readMediaAccess() async {
  var answer = await mediaLocationChannel.invokeMethod<String>(
    mediaAccessMethod,
  );
  for (var access in MediaAccess.values) {
    if (access.name == answer) {
      return access;
    }
  }
  throw FormatException(
    "media_location answered '$answer' to $mediaAccessMethod",
  );
}
