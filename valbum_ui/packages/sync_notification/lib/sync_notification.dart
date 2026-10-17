/// The notification of a camera-roll upload (issue #300): its progress while
/// a run uploads, and the permission to show it.
///
/// Android only: no other platform implements the channel, and the app asks
/// only there, see `lib/sync_notifier.dart`. Registered like every other
/// plugin through the generated plugin registrant, so it reaches the engine
/// WorkManager starts for a background run as well as the Activity's — all
/// but [requestNotifications], which needs the Activity.
library;

import 'package:flutter/services.dart';

/// The channel the Android side answers on.
const MethodChannel syncNotificationChannel =
    MethodChannel("de.haumacher.valbum/sync_notification");

/// Whether the app's notifications are shown.
Future<bool> notificationsAllowed() async =>
    await syncNotificationChannel.invokeMethod<bool>("allowed") ?? false;

/// Whether the system can still be asked to show them: Android 13 and newer,
/// and not granted yet.
Future<bool> notificationsAskable() async =>
    await syncNotificationChannel.invokeMethod<bool>("askable") ?? false;

/// Asks the system to show them; answers whether it does now.
Future<bool> requestNotifications() async =>
    await syncNotificationChannel.invokeMethod<bool>("request") ?? false;

/// Shows (or updates) notification [id] in [channel]: [title], [text] and a
/// progress of [done] out of [total] — an indefinite one while [total] is 0.
Future<void> showSyncNotification({
  required int id,
  required String channel,
  required String channelName,
  required String title,
  required String text,
  int done = 0,
  int total = 0,
}) =>
    syncNotificationChannel.invokeMethod<void>("show", {
      "id": id,
      "channel": channel,
      "channelName": channelName,
      "title": title,
      "text": text,
      "done": done,
      "total": total,
    });

/// Removes notification [id]; the one of a running foreground service stays
/// until the service ends.
Future<void> clearSyncNotification(int id) =>
    syncNotificationChannel.invokeMethod<void>("clear", {"id": id});
