/// The notification of a camera-roll upload, behind an interface (issue
/// #300).
///
/// A run that uploads with the screen off runs as an Android foreground work,
/// and a foreground work shows a notification: "Uploading 3 photos…" with its
/// progress. What it says is [syncNoticeOf] — a plain function of the run's
/// status, so that a test reads the sentence without a phone. Where it goes
/// is a [SyncNotifier]: the plugin on Android ([PluginSyncNotifier], chosen
/// in `platform_io.dart`), nothing anywhere else ([NoSyncNotifier]), and a
/// recording one in the tests ([FakeSyncNotifier]).
///
/// From Android 13 on the notification needs a runtime permission. The app
/// asks for it when the sync is switched on, and says what a refusal costs:
/// the upload runs all the same, only its progress is not seen.
library;

import 'package:flutter/foundation.dart';
import 'package:sync_notification/sync_notification.dart' as plugin;

import 'camera_roll.dart';
import 'diagnostics.dart';
import 'l10n/app_localizations.dart';

/// The notification channel of the camera-roll upload.
///
/// A constant of this app: Android keeps a channel, and the settings the user
/// made for it, under its id.
const String syncNotificationChannelId = "de.haumacher.valbum.upload";

/// What the notification of a run says.
@immutable
class SyncNotice {
  final String title;
  final String text;

  /// The progress, [done] out of [total]; an indefinite one while [total] is
  /// 0.
  final int done;
  final int total;

  const SyncNotice({
    required this.title,
    this.text = "",
    this.done = 0,
    this.total = 0,
  });

  @override
  bool operator ==(Object other) =>
      other is SyncNotice &&
      other.title == title &&
      other.text == text &&
      other.done == done &&
      other.total == total;

  @override
  int get hashCode => Object.hash(title, text, done, total);

  @override
  String toString() => "$title | $text ($done/$total)";
}

/// What the notification of a run in [status] says; `null` where the run
/// uploads nothing and the notification has nothing to say.
///
/// Only a run that transfers photos is worth a line in the notification
/// shade: one that finds nothing new ends within a moment, and one that fails
/// says why in the app, where the user can do something about it.
SyncNotice? syncNoticeOf(CameraRollStatus status, AppLocalizations l10n) {
  // Nothing left to go is a run that is about to end: its notification goes
  // with it.
  if (!status.running || status.total <= 0 || status.done >= status.total) {
    return null;
  }
  return SyncNotice(
    title: l10n.syncNotificationTitle,
    text: l10n.syncNotificationUploading(status.total - status.done),
    done: status.done,
    total: status.total,
  );
}

/// Where the notification of a run goes, see the library comment.
abstract class SyncNotifier {
  const SyncNotifier();

  /// Whether this platform shows the notification at all.
  bool get available;

  /// Whether the app's notifications are shown.
  Future<bool> allowed();

  /// Whether the system can still be asked to show them.
  Future<bool> askable();

  /// Asks the system to show them; answers whether it does now.
  Future<bool> request();

  /// Shows [notice] as notification [id], or updates it.
  Future<void> show(int id, SyncNotice notice, {required String channelName});

  /// Removes notification [id].
  Future<void> clear(int id);
}

/// The [SyncNotifier] of a platform that shows none.
class NoSyncNotifier extends SyncNotifier {
  const NoSyncNotifier();

  @override
  bool get available => false;

  @override
  Future<bool> allowed() async => true;

  @override
  Future<bool> askable() async => false;

  @override
  Future<bool> request() async => true;

  @override
  Future<void> show(int id, SyncNotice notice,
      {required String channelName}) async {}

  @override
  Future<void> clear(int id) async {}
}

/// The [SyncNotifier] of Android, through `packages/sync_notification`.
///
/// A plugin that fails is never a reason for a run to fail: the notification
/// is a courtesy, the upload is the point. It is written into [log], with the
/// complete text of the failure.
class PluginSyncNotifier extends SyncNotifier {
  /// Where a failure of the plugin is written, `null` for nowhere.
  final DiagnosticsLog? log;

  const PluginSyncNotifier({this.log});

  /// Writes the failure of [what] into [log].
  void _failed(String what, Object error) =>
      syncNotificationFailed(log, what, error);

  @override
  bool get available => true;

  @override
  Future<bool> allowed() async {
    try {
      return await plugin.notificationsAllowed();
    } catch (error) {
      _failed("ask whether notifications are shown", error);
      return true;
    }
  }

  @override
  Future<bool> askable() async {
    try {
      return await plugin.notificationsAskable();
    } catch (error) {
      _failed("ask whether notifications may be asked for", error);
      return false;
    }
  }

  @override
  Future<bool> request() async {
    try {
      return await plugin.requestNotifications();
    } catch (error) {
      _failed("ask for the permission to show notifications", error);
      return false;
    }
  }

  @override
  Future<void> show(int id, SyncNotice notice,
      {required String channelName}) async {
    try {
      await plugin.showSyncNotification(
        id: id,
        channel: syncNotificationChannelId,
        channelName: channelName,
        title: notice.title,
        text: notice.text,
        done: notice.done,
        total: notice.total,
      );
    } catch (error) {
      _failed("show the upload notification", error);
    }
  }

  @override
  Future<void> clear(int id) async {
    try {
      await plugin.clearSyncNotification(id);
    } catch (error) {
      _failed("remove the upload notification", error);
    }
  }
}

/// Writes into [log] that the notification plugin failed to [what], with the
/// complete text of [error].
void syncNotificationFailed(DiagnosticsLog? log, String what, Object error) {
  log?.record(
    "Could not $what: the camera-roll sync goes on without it",
    facts: [platformErrorFact(error)],
    cause: failureCause(
      attempt: "notification $what",
      platformError: errorCause(error),
    ),
    window: const Duration(hours: 1),
  );
}

/// A [SyncNotifier] that records what it was asked, used by tests.
class FakeSyncNotifier extends SyncNotifier {
  @override
  final bool available;

  /// Whether the notifications are shown; [request] sets it to [grants].
  bool isAllowed;

  /// Whether the system can still be asked.
  bool isAskable;

  /// What [request] makes of [isAllowed].
  bool grants;

  /// The number of [request] calls.
  int requests = 0;

  /// What was shown, by notification id, in order.
  final List<(int, SyncNotice)> shown = [];

  /// The ids that were cleared, in order.
  final List<int> cleared = [];

  FakeSyncNotifier({
    this.available = true,
    this.isAllowed = false,
    this.isAskable = true,
    this.grants = true,
  });

  @override
  Future<bool> allowed() async => isAllowed;

  @override
  Future<bool> askable() async => isAskable;

  @override
  Future<bool> request() async {
    requests++;
    isAllowed = grants;
    isAskable = false;
    return isAllowed;
  }

  @override
  Future<void> show(int id, SyncNotice notice,
      {required String channelName}) async {
    shown.add((id, notice));
  }

  @override
  Future<void> clear(int id) async {
    cleared.add(id);
  }
}
