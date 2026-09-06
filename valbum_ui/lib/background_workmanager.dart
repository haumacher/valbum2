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
///  * Android: nothing in the app's manifest. The plugin's own manifest brings
///    `WorkManager` and its permissions; the periodic work is registered from
///    Dart, with the platform minimum of fifteen minutes and the constraints
///    "the battery is not low" and the network requirement the sync asks for
///    — `unmetered` while the Wi-Fi-only switch is on, `connected` otherwise
///    (issue #43). `WorkManager` then keeps the wake-up back instead of
///    starting the app on a mobile connection just to have the run refuse.
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

/// Periodic background execution through `workmanager`.
class WorkmanagerScheduler extends BackgroundScheduler {
  final Workmanager _workmanager;

  /// Whether the plugin was told about [backgroundSyncDispatcher] already.
  ///
  /// `initialize` hands the platform the entry point it starts the app at; it
  /// is needed before registering and before cancelling, and it costs a
  /// platform round trip, so it happens once.
  bool _initialized = false;

  WorkmanagerScheduler({Workmanager? workmanager})
      : _workmanager = workmanager ?? Workmanager();

  @override
  bool get available => true;

  @override
  String get unavailableReason => "";

  @override
  Future<void> schedule(BackgroundNetwork network) async {
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
  }

  @override
  Future<void> cancel() async {
    await _initialize();
    await _workmanager.cancelByUniqueName(backgroundSyncTaskName);
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

/// Hands [task] to the plugin as the body of the background run, see
/// [backgroundSyncDispatcher].
void runWorkmanagerTask(Future<bool> Function() task) =>
    Workmanager().executeTask((name, input) => task());
