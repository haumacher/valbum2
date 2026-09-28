/// Whether this app may read where the photos were taken (issue #169).
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
