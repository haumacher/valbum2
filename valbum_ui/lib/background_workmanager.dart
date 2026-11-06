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
///    permissions; the periodic work is registered from Dart, once a day
///    ([androidCatchUpInterval]) with the constraints "the device charges"
///    and the network requirement the sync asks for — `unmetered` while the
///    Wi-Fi-only switch is on, `connected` otherwise (issue #43). It is the
///    nightly catch-up of what the trigger missed (issue #300), not a check
///    every quarter of an hour that mostly finds nothing; `WorkManager`
///    keeps the wake-up back instead of starting the app on a mobile
///    connection just to have the run refuse. Registered with policy
///    `update`, which takes the new period and constraints over into the work
///    an older version registered and keeps its timing.
///
///    Beside it three one-off works (issue #300):
///
///     - [backgroundUploadTaskName], the one run that uploads: an
///       *expedited* work ([requestUpload], [WorkmanagerHandover]) with a
///       network constraint. Android grants an expedited job the network —
///       it bypasses the network restrictions of Doze, App Standby and
///       battery saver where the job asks for a network — and Android 15
///       cuts it for every other run of an app that is not on the screen:
///       the lookup of the server fails with errno 7. Out of quota, the
///       platform runs it as an ordinary work
///       (`OutOfQuotaPolicy.runAsNonExpeditedWorkRequest`). It also asks
///       for a foreground work of the type `dataSync`
///       (`android/gradle.properties`:
///       `workmanager.enableDataSyncForegroundService`), which keeps a long
///       upload going past the minute an expedited job is sure of; from
///       Android 12 on the platform refuses one started from the background,
///       and the plugin goes on without it. The app on the screen asks for
///       any network — it refused a mobile connection itself where the sync
///       is limited to Wi-Fi — the other works for what the switch says;
///     - [backgroundTriggerTaskName], with a content trigger on the images
///       and the videos of `MediaStore` and only the network requirement —
///       a photo or two upload at once, on a low battery too: the platform
///       starts it some ten seconds after the library changed — a photo was
///       taken — with the app closed, and its run hands the upload over. A
///       work with a content trigger runs once, so its run registers the next
///       one behind itself ([rearmTrigger], policy `append`, which the plugin
///       passes on as `APPEND_OR_REPLACE`); [schedule] and every other
///       background run ([ensureTrigger]) keep the one that waits or runs and
///       register one where there is none, and [schedule] replaces it where
///       the Wi-Fi-only switch was toggled;
///     - [backgroundRetryTaskName], an ordinary work with the network
///       requirement and a delay ([scheduleRetry]) after a run that could
///       not reach the server: its run hands the upload over afresh, which is
///       expedited again — WorkManager's own retry of the upload would not
///       be, it registers every work that ran before as an ordinary job.
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
      // Android: the daily catch-up, see the library documentation; the first
      // one at the next charge. iOS decides for itself and keeps its value.
      frequency: _android ? androidCatchUpInterval : backgroundSyncInterval,
      initialDelay: _android ? null : backgroundSyncInterval,
      // Uploading photos over a connection that is not there fails; the
      // platform holds the run back until there is one. Which connection
      // counts follows the Wi-Fi-only switch, see the library documentation
      // on why iOS keeps `connected` either way. Android waits for the
      // charger — the catch-up has all night — and iOS keeps "the battery is
      // not low".
      constraints: _android
          ? Constraints(
              networkType: _networkType(network),
              requiresCharging: true,
            )
          : Constraints(
              networkType: _networkType(network),
              requiresBatteryNotLow: true,
            ),
      // Idempotent: registering the same task again updates the one that is
      // there — its constraints and its period too, which moves an older
      // install onto the daily catch-up — instead of adding a second one,
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

  @override
  Future<void> ensureTrigger(BackgroundNetwork network) async {
    if (!_android) {
      return;
    }
    // `keep` is decided by `WorkManager` over every work of the name: one
    // that waits, is blocked or runs is kept, a chain that failed or was
    // cancelled is replaced by a new trigger. No initialize, as above.
    await _registerTrigger(network, ExistingWorkPolicy.keep);
  }

  @override
  Future<bool> requestUpload(BackgroundNetwork network) async {
    if (!_android) {
      return false;
    }
    // No initialize: this runs in the background isolate the plugin started
    // with the dispatcher it has stored already.
    await _registerUpload(_networkType(network));
    return true;
  }

  @override
  Future<void> scheduleRetry(BackgroundNetwork network,
      {required Duration delay}) async {
    if (!_android) {
      return;
    }
    // An ordinary work, delayed — an expedited one cannot be — whose run
    // only hands the upload over afresh. A retry that waits already is the
    // one asked for (`keep`).
    await _workmanager.registerOneOffTask(
      backgroundRetryTaskName,
      backgroundRetryTaskName,
      initialDelay: delay,
      constraints: Constraints(networkType: _networkType(network)),
      existingWorkPolicy: ExistingWorkPolicy.keep,
    );
  }

  /// Registers the upload work, see the library documentation.
  ///
  /// `keep`: an upload that waits or runs is the one asked for — it scans
  /// again before it ends, see `backgroundPasses` — and the app follows it
  /// by the state of the work, which an `append` chain would make the state
  /// of a work long over.
  Future<void> _registerUpload(NetworkType network) =>
      _workmanager.registerOneOffTask(
        backgroundUploadTaskName,
        backgroundUploadTaskName,
        existingWorkPolicy: ExistingWorkPolicy.keep,
        // Only a network constraint: an expedited work may have no other,
        // and the network is what it is granted for.
        constraints: Constraints(networkType: network),
        expedited: true,
        outOfQuotaPolicy: OutOfQuotaPolicy.runAsNonExpeditedWorkRequest,
        foregroundServiceConfig: syncForegroundConfig(backgroundUploadTaskName),
      );

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
          // No battery constraint: a photo or two upload at once, on a low
          // battery too, instead of waiting for the charger unannounced.
          contentUriTriggers: [
            for (var uri in mediaStoreTriggerUris)
              ContentUriTrigger(uri: uri, triggerForDescendants: true),
          ],
        ),
        existingWorkPolicy: policy,
        // No foreground work: the run only hands the upload over.
      );

  @override
  Future<void> cancel() async {
    await _initialize();
    await _workmanager.cancelByUniqueName(backgroundSyncTaskName);
    if (_android) {
      await _workmanager.cancelByUniqueName(backgroundTriggerTaskName);
      await _workmanager.cancelByUniqueName(backgroundUploadTaskName);
      await _workmanager.cancelByUniqueName(backgroundRetryTaskName);
    }
  }

  /// The plugin's constraint for [network].
  ///
  /// iOS reads `unmetered` as "no network needed at all", see the library
  /// documentation; there the requirement stays `connected`. This scheduler
  /// runs on Android and iOS only, so "not Android" is iOS.
  NetworkType _networkType(BackgroundNetwork network) =>
      network == BackgroundNetwork.unmetered && _android
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

/// The [SyncHandover] of Android: the expedited work
/// [backgroundUploadTaskName] (issue #300), see the library documentation.
class WorkmanagerHandover extends SyncHandover {
  final WorkmanagerScheduler scheduler;

  const WorkmanagerHandover(this.scheduler);

  @override
  String get task => backgroundUploadTaskName;

  @override
  Future<bool> request() async {
    await scheduler._initialize();
    // A run that is waiting or going on is the run asked for: the app follows
    // it, and asks again once it is over if something came meanwhile. Any
    // network: the app answered what the device answers on its own before it
    // asked, and a network constraint is what the platform grants the
    // network for.
    await scheduler._registerUpload(NetworkType.connected);
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
