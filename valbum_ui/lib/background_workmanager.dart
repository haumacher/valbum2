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
///    "a network is connected" and "the battery is not low".
///  * iOS: the [backgroundSyncTaskName] listed under
///    `BGTaskSchedulerPermittedIdentifiers` and `fetch` under
///    `UIBackgroundModes` in `Runner/Info.plist`. The plugin registers the
///    launch handler itself, so `AppDelegate.swift` stays as it is.
library;

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
  Future<void> schedule() async {
    await _initialize();
    await _workmanager.registerPeriodicTask(
      backgroundSyncTaskName,
      backgroundSyncTaskName,
      frequency: backgroundSyncInterval,
      initialDelay: backgroundSyncInterval,
      // Uploading photos over a connection that is not there fails, and doing
      // it on a nearly empty battery is not what anybody wants from a photo
      // album; the platform holds the run back until both are true.
      constraints: Constraints(
        networkType: NetworkType.connected,
        requiresBatteryNotLow: true,
      ),
      // Idempotent: registering the same task again updates the one that is
      // there instead of adding a second one or resetting its timing.
      existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
    );
  }

  @override
  Future<void> cancel() async {
    await _initialize();
    await _workmanager.cancelByUniqueName(backgroundSyncTaskName);
  }

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
