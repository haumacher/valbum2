/// The [BackgroundScheduler] of Android and iOS, backed by `workmanager`
/// (issue #32).
///
/// Reached only through the conditional import in `platform.dart`, and only
/// after the platform was checked — exactly as `photo_manager` is, see
/// `photo_library_manager.dart`: `workmanager` has no implementation this app
/// uses on a desktop, and none at all in a browser.
///
/// What the platform needs beside this file:
///
///  * Android: the plugin's own manifest brings `WorkManager` and its
///    permissions; the periodic work is registered from Dart, with the
///    platform minimum of fifteen minutes and the constraints "the battery is
///    not low" and the network requirement the sync asks for — `unmetered`
///    while the Wi-Fi-only switch is on, `connected` otherwise (issue #43).
///    `WorkManager` then keeps the wake-up back instead of starting the app on
///    a mobile connection just to have the run refuse.
///
///    Beside it two one-off works (issue #300), both run as foreground works
///    of the type `dataSync`, which `android/gradle.properties` switches on
///    for the plugin (`workmanager.enableDataSyncForegroundService`, which
///    brings `FOREGROUND_SERVICE_DATA_SYNC`):
///
///     - [backgroundTriggerTaskName], with a content trigger on the images
///       and the videos of `MediaStore` and the same constraints as the
///       periodic work: the platform starts it some ten seconds after the
///       library changed — a photo was taken — with the app closed. A work
///       with a content trigger runs once, so its run registers the next one
///       behind itself ([rearmTrigger], policy `append`); [schedule] keeps
///       the one that waits or runs, and replaces it where the Wi-Fi-only
///       switch was toggled;
///     - [backgroundUploadTaskName], the run the app on the screen hands over
///       ([WorkmanagerHandover]), without constraints: the app answered what
///       the device answers on its own before it asked, and the run says why
///       it refuses the rest instead of waiting silently for a network.
///
///    A foreground work shows a notification for as long as it runs; the
///    platform does not freeze an app that has one and keeps the device awake
///    for it, so an upload goes on with the screen off. From Android 12 on the
///    platform may refuse a foreground work an app starts from the background
///    — a new photo, with the app closed; the plugin then runs the work as an
///    ordinary one, which the platform gives some minutes, enough for a photo
///    or two, and the next trigger or the app picks up the rest.
///  * iOS: the [backgroundSyncTaskName] listed under
///    `BGTaskSchedulerPermittedIdentifiers` and `fetch` under
///    `UIBackgroundModes` in `Runner/Info.plist`. The plugin registers the
///    launch handler itself, so `AppDelegate.swift` stays as it is.
///
/// A word on the network constraint and iOS: `BGTaskScheduler` has no notion
/// of an unmetered network. Its only network knob is
/// `BGProcessingTaskRequest.requiresNetworkConnectivity`, and the plugin sets
/// it from `networkType == connected || networkType == metered` — asking for
/// `unmetered` there would not narrow the constraint, it would *drop* it, and
/// the app would be woken with no network at all. So iOS keeps `connected`
/// whatever the switch says, and the Dart-side refusal in `runBackgroundSync`
/// (via [CameraRollSync]) is what limits an iOS run to Wi-Fi. That refusal
/// stays the second line of defence on Android too: the constraint decides
/// when the app is started, the run itself decides whether to upload.
library;

import 'dart:io';

import 'package:workmanager/workmanager.dart';

import 'background.dart';
import 'locales.dart';
import 'notices.dart';
import 'sync_notifier.dart';

/// The library of `MediaStore` a new photo or video lands in, observed by the
/// trigger work (issue #300). The sync uploads both, see
/// `photo_library_manager.dart`.
const List<String> mediaStoreTriggerUris = [
  "content://media/external/images/media",
  "content://media/external/video/media",
];

/// Periodic background execution through `workmanager`.
class WorkmanagerScheduler extends BackgroundScheduler {
  final Workmanager _workmanager;

  /// Whether the plugin was told about [backgroundSyncDispatcher] already.
  ///
  /// `initialize` hands the platform the entry point it starts the app at; it
  /// is needed before registering and before cancelling, and it costs a
  /// platform round trip, so it happens once.
  bool _initialized = false;

  /// Whether this is Android, which has the content trigger and the
  /// foreground works (issue #300); iOS keeps its one periodic task.
  final bool _android;

  WorkmanagerScheduler({Workmanager? workmanager, bool? android})
      : _workmanager = workmanager ?? Workmanager(),
        _android = android ?? Platform.isAndroid;

  /// The workmanager this scheduler registers with, for [WorkmanagerHandover].
  Workmanager get workmanager => _workmanager;

  @override
  bool get available => true;

  @override
  AppNotice? get unavailableReason => null;

  @override
  Future<void> schedule(BackgroundNetwork network,
      {bool changed = false}) async {
    await _initialize();
    await _workmanager.registerPeriodicTask(
      backgroundSyncTaskName,
      backgroundSyncTaskName,
      frequency: backgroundSyncInterval,
      initialDelay: backgroundSyncInterval,
      // Uploading photos over a connection that is not there fails, and doing
      // it on a nearly empty battery is not what anybody wants from a photo
      // album; the platform holds the run back until both are true. Which
      // connection counts follows the Wi-Fi-only switch, see the library
      // documentation on why iOS keeps `connected` either way.
      constraints: Constraints(
        networkType: _networkType(network),
        requiresBatteryNotLow: true,
      ),
      // Idempotent: registering the same task again updates the one that is
      // there — including its constraints — instead of adding a second one,
      // and `update` keeps the timing of the task that is already waiting.
      existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
    );
    if (_android) {
      // `keep` leaves a trigger that waits or runs as it is — a running one
      // registers its successor itself — and registers one anew where there
      // is none; a changed requirement replaces it, so that the constraint
      // follows the switch. The state of the work is not asked: the plugin
      // answers it for the first work of the name, which for a chain of
      // triggers is one that finished long ago.
      await _registerTrigger(
        network,
        changed ? ExistingWorkPolicy.replace : ExistingWorkPolicy.keep,
      );
    }
  }

  @override
  Future<void> rearmTrigger(BackgroundNetwork network) async {
    if (!_android) {
      return;
    }
    // No initialize: this runs in the background isolate the plugin started
    // with the dispatcher it has stored already.
    await _registerTrigger(network, ExistingWorkPolicy.append);
  }

  /// Registers the work started by a change of the photo library, see the
  /// library comment.
  Future<void> _registerTrigger(
    BackgroundNetwork network,
    ExistingWorkPolicy policy,
  ) =>
      _workmanager.registerOneOffTask(
        backgroundTriggerTaskName,
        backgroundTriggerTaskName,
        constraints: Constraints(
          networkType: _networkType(network),
          // Like the periodic work: a nearly empty battery waits for the
          // charger, and the app on the screen still syncs on request.
          requiresBatteryNotLow: true,
          contentUriTriggers: [
            for (var uri in mediaStoreTriggerUris)
              ContentUriTrigger(uri: uri, triggerForDescendants: true),
          ],
        ),
        existingWorkPolicy: policy,
        foregroundServiceConfig:
            syncForegroundConfig(backgroundTriggerTaskName),
      );

  @override
  Future<void> cancel() async {
    await _initialize();
    await _workmanager.cancelByUniqueName(backgroundSyncTaskName);
    if (_android) {
      await _workmanager.cancelByUniqueName(backgroundTriggerTaskName);
      await _workmanager.cancelByUniqueName(backgroundUploadTaskName);
    }
  }

  /// The plugin's constraint for [network].
  ///
  /// iOS reads `unmetered` as "no network needed at all", see the library
  /// documentation; there the requirement stays `connected`.
  static NetworkType _networkType(BackgroundNetwork network) =>
      network == BackgroundNetwork.unmetered && !Platform.isIOS
          ? NetworkType.unmetered
          : NetworkType.connected;

  Future<void> _initialize() async {
    if (_initialized) {
      return;
    }
    await _workmanager.initialize(backgroundSyncDispatcher);
    _initialized = true;
  }
}

/// The foreground work of a run of [task] (issue #300): the notification the
/// platform shows from the moment the work starts, until the run reports what
/// it uploads, see `sync_notifier.dart`.
///
/// Worded with [platformMessages]: the work is registered from whichever
/// isolate, and the notification is read on the device, in its language.
ForegroundServiceConfig syncForegroundConfig(String task) {
  var messages = platformMessages;
  return ForegroundServiceConfig(
    notificationTitle: messages.syncNotificationTitle,
    notificationText: messages.cameraRollLooking,
    notificationChannelId: syncNotificationChannelId,
    notificationChannelName: messages.syncNotificationChannel,
    notificationId: syncNotificationIdOf(task),
    foregroundServiceType: ForegroundServiceType.dataSync,
  );
}

/// The [SyncHandover] of Android: the foreground work
/// [backgroundUploadTaskName] (issue #300).
class WorkmanagerHandover extends SyncHandover {
  final WorkmanagerScheduler scheduler;

  const WorkmanagerHandover(this.scheduler);

  @override
  String get task => backgroundUploadTaskName;

  @override
  Future<bool> request() async {
    await scheduler._initialize();
    await scheduler.workmanager.registerOneOffTask(
      backgroundUploadTaskName,
      backgroundUploadTaskName,
      // A run that is waiting or going on is the run asked for: the app
      // follows it, and asks again once it is over if something came
      // meanwhile.
      existingWorkPolicy: ExistingWorkPolicy.keep,
      constraints: Constraints(networkType: NetworkType.notRequired),
      foregroundServiceConfig: syncForegroundConfig(backgroundUploadTaskName),
    );
    return true;
  }

  @override
  Future<bool> pending() async {
    var info =
        await scheduler.workmanager.getWorkInfo(backgroundUploadTaskName);
    return info?.state == WorkState.scheduled ||
        info?.state == WorkState.running;
  }

  @override
  Future<void> cancel() =>
      scheduler.workmanager.cancelByUniqueName(backgroundUploadTaskName);
}

/// Hands [task] to the plugin as the body of the background run, see
/// [backgroundSyncDispatcher]; it is handed the name of the task.
void runWorkmanagerTask(Future<bool> Function(String task) task) =>
    Workmanager().executeTask((name, input) => task(name));
