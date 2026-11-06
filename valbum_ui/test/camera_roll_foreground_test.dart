/// Tests of the camera-roll sync that goes on with the screen off and starts
/// when a photo is taken (issue #300): the works registered with the
/// platform, the run the app hands over to a foreground work, the one run of
/// the process, the notification and the permission it needs.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/background_workmanager.dart';
import 'package:valbum_ui/locales.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';
import 'package:workmanager/workmanager.dart';

import 'background_test.dart'
    show
        FakeServer,
        deviceToken,
        enabledStore,
        now,
        photo,
        pumpSection,
        refusal;
import 'util/fake_timers.dart';
import 'util/l10n.dart';

/// One call of [FakeWorkmanager.registerOneOffTask].
class OneOff {
  final String uniqueName;
  final String taskName;
  final Constraints? constraints;
  final ExistingWorkPolicy? policy;
  final ForegroundServiceConfig? foreground;
  final Duration? initialDelay;
  final BackoffPolicy? backoffPolicy;
  final Duration? backoffDelay;
  final bool expedited;
  final OutOfQuotaPolicy? outOfQuota;

  OneOff(this.uniqueName, this.taskName, this.constraints, this.policy,
      this.foreground,
      {this.initialDelay,
      this.backoffPolicy,
      this.backoffDelay,
      this.expedited = false,
      this.outOfQuota});
}

/// One call of [FakeWorkmanager.registerPeriodicTask].
class Periodic {
  final String uniqueName;
  final Duration? frequency;
  final Duration? initialDelay;
  final Constraints? constraints;
  final ExistingPeriodicWorkPolicy? policy;

  Periodic(this.uniqueName, this.frequency, this.initialDelay,
      this.constraints, this.policy);
}

/// A `Workmanager` that records what it is asked, and answers the state of a
/// work from [states].
class FakeWorkmanager implements Workmanager {
  final List<OneOff> oneOffs = [];
  final List<Periodic> periodicTasks = [];
  List<String> get periodics => [for (var p in periodicTasks) p.uniqueName];
  final List<String> cancelled = [];
  final Map<String, WorkState> states = {};
  int initialized = 0;

  @override
  Future<void> initialize(Function callbackDispatcher,
      {bool isInDebugMode = false}) async {
    initialized++;
  }

  @override
  Future<void> registerOneOffTask(
    String uniqueName,
    String taskName, {
    Map<String, dynamic>? inputData,
    Duration? initialDelay,
    Constraints? constraints,
    ExistingWorkPolicy? existingWorkPolicy,
    BackoffPolicy? backoffPolicy,
    Duration? backoffPolicyDelay,
    String? tag,
    OutOfQuotaPolicy? outOfQuotaPolicy,
    ForegroundServiceConfig? foregroundServiceConfig,
    bool expedited = false,
  }) async {
    oneOffs.add(OneOff(uniqueName, taskName, constraints, existingWorkPolicy,
        foregroundServiceConfig,
        initialDelay: initialDelay,
        backoffPolicy: backoffPolicy,
        backoffDelay: backoffPolicyDelay,
        expedited: expedited,
        outOfQuota: outOfQuotaPolicy));
  }

  @override
  Future<void> registerPeriodicTask(
    String uniqueName,
    String taskName, {
    Duration? frequency,
    Duration? flexInterval,
    Map<String, dynamic>? inputData,
    Duration? initialDelay,
    Constraints? constraints,
    ExistingPeriodicWorkPolicy? existingWorkPolicy,
    BackoffPolicy? backoffPolicy,
    Duration? backoffPolicyDelay,
    String? tag,
    ForegroundServiceConfig? foregroundServiceConfig,
  }) async {
    periodicTasks.add(Periodic(
        uniqueName, frequency, initialDelay, constraints, existingWorkPolicy));
  }

  @override
  Future<void> cancelByUniqueName(String uniqueName) async {
    cancelled.add(uniqueName);
  }

  @override
  Future<WorkInfo?> getWorkInfo(String uniqueName) async {
    var state = states[uniqueName];
    return state == null
        ? null
        : WorkInfo(uniqueName: uniqueName, state: state, isPeriodic: false);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// A switched-on store that lets the sync use any network.
InMemorySettingsStore anyNetworkStore() => enabledStore(
    config: const CameraRollConfig(enabled: true, wifiOnly: false));

/// The background run of [task] over the given parts, as the platform starts
/// it.
Future<BackgroundRunResult> backgroundRun(
  InMemorySettingsStore store,
  FakePhotoLibrary library,
  FakeServer server,
  SyncRelay relay, {
  String task = backgroundUploadTaskName,
  SyncNotifier notifier = const NoSyncNotifier(),
  BackgroundScheduler? scheduler,
}) =>
    runBackgroundSync(
      store: store,
      library: library,
      transport: server.transport,
      clock: () => now,
      connectivity: FakeConnectivity(),
      hashCache: MemoryHashCache(),
      task: task,
      relay: relay,
      notifier: notifier,
      scheduler: scheduler,
    );

/// A client of the fake server, for a run the app carries out itself.
VAlbumClient clientOf(FakeServer server) => VAlbumClient(
      dataUrl: "http://server/valbum/data/",
      token: deviceToken,
      httpClient: server.transport,
    );

void main() {
  group('the wire', () {
    test('carries every notice there is', () {
      var notices = <AppNotice>[
        const GuestHasNoSpace(),
        const NoInboxForCaller(),
        const NoServerConfigured(),
        const ServerOffline(),
        const ServerUnreachable("timeout"),
        const PhotoLibraryUnreadable(),
        const PhotoLibraryFailed("broken"),
        const PhotoLibraryOpenFailed("closed"),
        const SyncFailed("disk full"),
        const PhotoAccessDenied(),
        const MediaLocationNotGranted(),
        const PhotoNotOnDevice("a.jpg"),
        const NoPhotoLibraryHere(),
        const NoPhotoLibraryPlatform(),
        const NoPhotoLibraryBrowser(),
        const BackgroundScheduleFailed("no"),
        const BackgroundUnscheduleFailed("no"),
        const NoBackgroundSyncHere(),
        const NoBackgroundSyncInBrowser(),
        const NoBackgroundSyncInApp(),
        const NoBackgroundSyncInTest(),
        const NoNetworkForSync(),
        const NoWifiMobile(),
        const NoWifiOther(),
        UploadTargetExpired("Holidays", DateTime(2026, 8, 31)),
        const UploadTargetGone("Holidays"),
        const UploadTargetRefused("Holidays"),
        const UploadTargetNotAlbum("Holidays"),
        const UploadTargetFollowed("Holidays", "Trip"),
      ];
      for (var notice in notices) {
        expect(noticeFromWire(noticeToWire(notice)), notice);
      }
      expect(noticeFromWire(["from a later version"]), isNull);
      expect(noticeFromWire("garbage"), isNull);
    });

    test('carries a status', () {
      var status = CameraRollStatus(
        phase: CameraRollPhase.waiting,
        done: 3,
        total: 7,
        notice: const NoWifiMobile(),
        nextAttempt: DateTime.utc(2026, 3, 1, 13),
        lastSuccess: DateTime.utc(2026, 3, 1, 12),
        lastStored: 2,
        lastPresent: 1,
        lastPresentIn: const ["Trips/Rome"],
        indexingDone: 10,
        indexingTotal: 20,
        lastSkipped: const ["clip.mkv"],
        targetNotice: const UploadTargetGone("Holidays"),
      );
      var back = CameraRollStatus.fromWire(status.toWire());
      expect(back.phase, CameraRollPhase.waiting);
      expect([back.done, back.total], [3, 7]);
      expect(back.notice, const NoWifiMobile());
      expect(back.nextAttempt, status.nextAttempt);
      expect(back.lastSuccess, status.lastSuccess);
      expect([back.lastStored, back.lastPresent], [2, 1]);
      expect(back.lastPresentIn, ["Trips/Rome"]);
      expect([back.indexingDone, back.indexingTotal], [10, 20]);
      expect(back.lastSkipped, ["clip.mkv"]);
      expect(back.targetNotice, const UploadTargetGone("Holidays"));
      // What a relay message carries is plain values only.
      var message = RelayMessage(task: "t", status: status.toWire());
      expect(RelayMessage.fromWire(message.toWire())?.status["done"], 3);
    });
  });

  group('the works registered on Android', () {
    test('a switched-on sync arms the trigger on new photos', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.schedule(BackgroundNetwork.unmetered);

      expect(workmanager.periodics, [backgroundSyncTaskName]);
      var trigger = workmanager.oneOffs.single;
      expect(trigger.uniqueName, backgroundTriggerTaskName);
      expect(trigger.taskName, backgroundTriggerTaskName);
      // A start of the app keeps the trigger that waits or runs.
      expect(trigger.policy, ExistingWorkPolicy.keep);
      var constraints = trigger.constraints!;
      expect(constraints.networkType, NetworkType.unmetered);
      // A photo or two upload at once, on a low battery too.
      expect(constraints.requiresBatteryNotLow, isNot(isTrue));
      expect(constraints.requiresCharging, isNot(isTrue));
      expect(
        [
          for (var uri in constraints.contentUriTriggers!)
            (uri!.uri, uri.triggerForDescendants)
        ],
        [
          ("content://media/external/images/media", true),
          ("content://media/external/video/media", true),
        ],
      );
      // Its run only hands the upload over: no foreground work, and it is
      // no expedited work — a content trigger is a constraint an expedited
      // work may not have.
      expect(trigger.foreground, isNull);
      expect(trigger.expedited, isFalse);
    });

    test('the upload is an expedited work with a network constraint',
        () async {
      for (var (network, type) in [
        (BackgroundNetwork.unmetered, NetworkType.unmetered),
        (BackgroundNetwork.connected, NetworkType.connected),
      ]) {
        var workmanager = FakeWorkmanager();
        var scheduler =
            WorkmanagerScheduler(workmanager: workmanager, android: true);

        expect(await scheduler.requestUpload(network), isTrue);

        var upload = workmanager.oneOffs.single;
        expect(upload.uniqueName, backgroundUploadTaskName);
        expect(upload.taskName, backgroundUploadTaskName);
        // Expedited: the background run Android grants the network to, for
        // the network it asks for.
        expect(upload.expedited, isTrue);
        expect(upload.outOfQuota, OutOfQuotaPolicy.runAsNonExpeditedWorkRequest);
        expect(upload.constraints!.networkType, type);
        // An expedited work may not have any other constraint, nor a delay.
        expect(upload.constraints!.requiresBatteryNotLow, isNot(isTrue));
        expect(upload.constraints!.requiresCharging, isNot(isTrue));
        expect(upload.constraints!.contentUriTriggers, isNull);
        expect(upload.initialDelay, isNull);
        expect(upload.policy, ExistingWorkPolicy.keep);
        var foreground = upload.foreground!;
        expect(
            foreground.foregroundServiceType, ForegroundServiceType.dataSync);
        expect(foreground.notificationId,
            syncNotificationIdOf(backgroundUploadTaskName));
        expect(foreground.notificationChannelId, syncNotificationChannelId);
        expect(foreground.notificationText, platformMessages.cameraRollLooking);
      }
    });

    test('the periodic work is the daily catch-up of a charging device',
        () async {
      for (var (network, type) in [
        (BackgroundNetwork.unmetered, NetworkType.unmetered),
        (BackgroundNetwork.connected, NetworkType.connected),
      ]) {
        var workmanager = FakeWorkmanager();
        var scheduler =
            WorkmanagerScheduler(workmanager: workmanager, android: true);

        await scheduler.schedule(network);

        var periodic = workmanager.periodicTasks.single;
        expect(periodic.uniqueName, backgroundSyncTaskName);
        expect(periodic.frequency, const Duration(days: 1));
        expect(periodic.constraints!.requiresCharging, isTrue);
        expect(periodic.constraints!.networkType, type);
        // `update` takes period and constraints over into the work an older
        // version registered every quarter of an hour.
        expect(periodic.policy, ExistingPeriodicWorkPolicy.update);
      }
    });

    test('the trigger asks for any network while Wi-Fi-only is off', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.schedule(BackgroundNetwork.connected);

      expect(workmanager.oneOffs.single.constraints!.networkType,
          NetworkType.connected);
    });

    test('a toggled switch replaces the trigger, so the constraint follows',
        () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.schedule(BackgroundNetwork.connected, changed: true);

      expect(workmanager.oneOffs.single.policy, ExistingWorkPolicy.replace);
    });

    test('a trigger run arms the next one behind itself', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.rearmTrigger(BackgroundNetwork.connected);
      var next = workmanager.oneOffs.single;
      expect(next.uniqueName, backgroundTriggerTaskName);
      // Behind the running one: replacing it would cancel the run itself.
      expect(next.policy, ExistingWorkPolicy.append);
      expect(next.constraints!.networkType, NetworkType.connected);
    });

    test('switching the sync off removes every work', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.cancel();

      expect(workmanager.cancelled, [
        backgroundSyncTaskName,
        backgroundTriggerTaskName,
        backgroundUploadTaskName,
        backgroundRetryTaskName,
      ]);
    });

    test('another run registers a trigger where none waits, keeping one that '
        'does', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.ensureTrigger(BackgroundNetwork.unmetered);

      var trigger = workmanager.oneOffs.single;
      expect(trigger.uniqueName, backgroundTriggerTaskName);
      // Decided by the platform over the whole chain, never by asking for
      // the state of its first work.
      expect(trigger.policy, ExistingWorkPolicy.keep);
      expect(trigger.constraints!.networkType, NetworkType.unmetered);
      expect(trigger.constraints!.contentUriTriggers, hasLength(2));
      expect(trigger.constraints!.requiresBatteryNotLow, isNot(isTrue));
    });

    test('a run that could not reach the server is handed over again later, '
        'by an ordinary delayed work', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: true);

      await scheduler.scheduleRetry(BackgroundNetwork.connected,
          delay: const Duration(minutes: 2));

      var retry = workmanager.oneOffs.single;
      expect(retry.uniqueName, backgroundRetryTaskName);
      expect(retry.taskName, backgroundRetryTaskName);
      expect(retry.policy, ExistingWorkPolicy.keep);
      expect(retry.constraints!.networkType, NetworkType.connected);
      expect(retry.constraints!.contentUriTriggers, isNull);
      expect(retry.initialDelay, const Duration(minutes: 2));
      // Its run only hands the upload over afresh, which is expedited again.
      expect(retry.expedited, isFalse);
      expect(retry.backoffPolicy, isNull);
      expect(retry.foreground, isNull);
    });

    test('the wait of the retry doubles, up to five hours', () {
      expect(backgroundRetryDelay(1), const Duration(seconds: 30));
      expect(backgroundRetryDelay(2), const Duration(minutes: 1));
      expect(backgroundRetryDelay(3), const Duration(minutes: 2));
      expect(backgroundRetryDelay(10), const Duration(hours: 4, minutes: 16));
      expect(backgroundRetryDelay(11), const Duration(hours: 5));
      expect(backgroundRetryDelay(1000), const Duration(hours: 5));
    });

    test('iOS keeps its one periodic task', () async {
      var workmanager = FakeWorkmanager();
      var scheduler =
          WorkmanagerScheduler(workmanager: workmanager, android: false);

      await scheduler.schedule(BackgroundNetwork.unmetered);
      await scheduler.rearmTrigger(BackgroundNetwork.unmetered);
      await scheduler.ensureTrigger(BackgroundNetwork.unmetered);
      await scheduler.scheduleRetry(BackgroundNetwork.unmetered,
          delay: const Duration(minutes: 1));
      // No upload work: the periodic task uploads itself.
      expect(await scheduler.requestUpload(BackgroundNetwork.unmetered),
          isFalse);
      await scheduler.cancel();

      expect(workmanager.periodics, [backgroundSyncTaskName]);
      // As before: every quarter of an hour, as far as iOS listens, with the
      // battery not low and any network.
      var periodic = workmanager.periodicTasks.single;
      expect(periodic.frequency, const Duration(minutes: 15));
      expect(periodic.initialDelay, const Duration(minutes: 15));
      expect(periodic.constraints!.requiresBatteryNotLow, isTrue);
      expect(periodic.constraints!.requiresCharging, isNot(isTrue));
      expect(periodic.constraints!.networkType, NetworkType.connected);
      expect(workmanager.oneOffs, isEmpty);
      expect(workmanager.cancelled, [backgroundSyncTaskName]);
    });

    test('the run of the app goes to a foreground work, joined if it waits',
        () async {
      var workmanager = FakeWorkmanager();
      var handover = WorkmanagerHandover(
          WorkmanagerScheduler(workmanager: workmanager, android: true));

      expect(await handover.request(), isTrue);

      var upload = workmanager.oneOffs.single;
      expect(upload.uniqueName, backgroundUploadTaskName);
      expect(upload.policy, ExistingWorkPolicy.keep);
      // Any network: the app answered what the device answers on its own,
      // and a network constraint is what the platform grants it for.
      expect(upload.constraints!.networkType, NetworkType.connected);
      expect(upload.expedited, isTrue);
      expect(upload.constraints!.contentUriTriggers, isNull);
      expect(upload.foreground!.foregroundServiceType,
          ForegroundServiceType.dataSync);
      expect(upload.foreground!.notificationId,
          syncNotificationIdOf(backgroundUploadTaskName));
      expect(workmanager.initialized, 1);

      expect(await handover.pending(), isFalse);
      workmanager.states[backgroundUploadTaskName] = WorkState.scheduled;
      expect(await handover.pending(), isTrue);
      workmanager.states[backgroundUploadTaskName] = WorkState.running;
      expect(await handover.pending(), isTrue);
      workmanager.states[backgroundUploadTaskName] = WorkState.succeeded;
      expect(await handover.pending(), isFalse);

      await handover.cancel();
      expect(workmanager.cancelled, [backgroundUploadTaskName]);
    });

    test('each foreground work has a notification of its own', () {
      var ids = {
        syncNotificationIdOf(backgroundUploadTaskName),
        syncNotificationIdOf(backgroundTriggerTaskName),
        syncNotificationIdOf(backgroundSyncTaskName),
      };
      expect(ids, hasLength(3));
      expect(ids, isNot(contains(0)));
      expect(foregroundSyncTask(backgroundUploadTaskName), isTrue);
      // The others hand the upload over in a moment: no notification.
      expect(foregroundSyncTask(backgroundTriggerTaskName), isFalse);
      expect(foregroundSyncTask(backgroundRetryTaskName), isFalse);
      expect(foregroundSyncTask(backgroundSyncTaskName), isFalse);
    });
  });

  group('the engine and the works', () {
    test('a toggled Wi-Fi-only switch says that the requirement changed',
        () async {
      var scheduler = FakeBackgroundScheduler();
      var sync = CameraRollSync(
        store: enabledStore(config: const CameraRollConfig()),
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: scheduler,
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await sync.setEnabled(true);
      await pumpEventQueue();
      await sync.setWifiOnly(false);

      expect(scheduler.requests,
          [BackgroundNetwork.unmetered, BackgroundNetwork.connected]);
      expect(scheduler.changes, [false, true]);
    });

    test('a run is not asked for while the one before is still finishing',
        () async {
      var timers = FakeTimers();
      var handover = FakeSyncHandover()..isPending = true;
      var sync = CameraRollSync(
        store: anyNetworkStore(),
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        relay: MemorySyncRelay(),
        handover: handover,
        timerFactory: timers.create,
      );
      addTearDown(sync.dispose);
      await sync.load();

      unawaited(sync.syncNow());
      await pumpEventQueue();
      expect(handover.requests, 0);

      handover.isPending = false;
      expect(timers.fire(const Duration(seconds: 5)), 1);
      await pumpEventQueue();
      expect(handover.requests, 1);
      handover.isPending = false;
      timers.fire(const Duration(seconds: 5));
      await pumpEventQueue();
    });
  });

  group('a run started by a new photo', () {
    test('arms the next trigger first, with the Wi-Fi-only setting', () async {
      var scheduler = FakeBackgroundScheduler();
      var result = await backgroundRun(
        enabledStore(),
        FakePhotoLibrary(),
        FakeServer(),
        MemorySyncRelay(),
        task: backgroundTriggerTaskName,
        scheduler: scheduler,
      );

      expect(result.ran, isTrue);
      expect(scheduler.rearms, [BackgroundNetwork.unmetered]);
    });

    test('arms nothing once the sync is off', () async {
      var scheduler = FakeBackgroundScheduler();
      await backgroundRun(
        enabledStore(config: const CameraRollConfig()),
        FakePhotoLibrary(),
        FakeServer(),
        MemorySyncRelay(),
        task: backgroundTriggerTaskName,
        scheduler: scheduler,
      );

      expect(scheduler.rearms, isEmpty);
    });

    test('the periodic run appends no trigger, it registers one where none '
        'waits', () async {
      var scheduler = FakeBackgroundScheduler();
      await backgroundRun(
        enabledStore(),
        FakePhotoLibrary(),
        FakeServer(),
        MemorySyncRelay(),
        task: backgroundSyncTaskName,
        scheduler: scheduler,
      );

      expect(scheduler.rearms, isEmpty);
      expect(scheduler.ensures, [BackgroundNetwork.unmetered]);
    });

    test('every other run registers one where none waits, the trigger run '
        'does not', () async {
      for (var task in [
        backgroundUploadTaskName,
        backgroundRetryTaskName,
        backgroundTriggerTaskName,
      ]) {
        var scheduler = FakeBackgroundScheduler();
        await backgroundRun(
          anyNetworkStore(),
          FakePhotoLibrary(),
          FakeServer(),
          MemorySyncRelay(),
          task: task,
          scheduler: scheduler,
        );

        expect(scheduler.ensures,
            task == backgroundTriggerTaskName ? isEmpty : [BackgroundNetwork.connected],
            reason: task);
      }
    });

    test('a switched-off sync registers nothing at all', () async {
      var scheduler = FakeBackgroundScheduler();
      await backgroundRun(
        enabledStore(config: const CameraRollConfig()),
        FakePhotoLibrary(),
        FakeServer(),
        MemorySyncRelay(),
        task: backgroundSyncTaskName,
        scheduler: scheduler,
      );

      expect(scheduler.ensures, isEmpty);
      expect(scheduler.retries, isEmpty);
    });

    test('picks up a photo taken while it uploads', () async {
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      var server = FakeServer();
      var taken = false;
      server.upload = (request) {
        if (!taken) {
          taken = true;
          library.items.add(photo("b.jpg", 2));
        }
        return http.Response("", 200);
      };

      var result = await backgroundRun(
          enabledStore(), library, server, MemorySyncRelay());

      expect(server.uploadedNames, [
        ["a.jpg"],
        ["b.jpg"],
      ]);
      expect(result.record?.stored, 2);
    });
  });

  group('a run on Android hands the upload over', () {
    for (var task in [
      backgroundTriggerTaskName,
      backgroundSyncTaskName,
      backgroundRetryTaskName,
    ]) {
      test('$task uploads nothing itself', () async {
        var scheduler = FakeBackgroundScheduler(acceptsUploads: true);
        var server = FakeServer();
        var result = await backgroundRun(
          enabledStore(),
          FakePhotoLibrary(items: [photo("a.jpg", 1)]),
          server,
          MemorySyncRelay(),
          task: task,
          scheduler: scheduler,
        );

        expect(result.handedOver, isTrue);
        expect(result.ran, isFalse);
        // With the Wi-Fi-only setting: the upload waits for a Wi-Fi.
        expect(scheduler.uploads, [BackgroundNetwork.unmetered]);
        expect(server.requests, isEmpty);
        // The trigger still arms its successor first; the others make sure
        // a trigger waits.
        if (task == backgroundTriggerTaskName) {
          expect(scheduler.rearms, [BackgroundNetwork.unmetered]);
          expect(scheduler.ensures, isEmpty);
        } else {
          expect(scheduler.ensures, [BackgroundNetwork.unmetered]);
        }
      });
    }

    test('the upload uploads, and hands nothing over', () async {
      var scheduler = FakeBackgroundScheduler(acceptsUploads: true);
      var server = FakeServer();
      var result = await backgroundRun(
        anyNetworkStore(),
        FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        server,
        MemorySyncRelay(),
        task: backgroundUploadTaskName,
        scheduler: scheduler,
      );

      expect(result.ok, isTrue);
      expect(scheduler.uploads, isEmpty);
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
    });

    test('a switched-off sync hands nothing over', () async {
      var scheduler = FakeBackgroundScheduler(acceptsUploads: true);
      await backgroundRun(
        enabledStore(config: const CameraRollConfig()),
        FakePhotoLibrary(),
        FakeServer(),
        MemorySyncRelay(),
        task: backgroundTriggerTaskName,
        scheduler: scheduler,
      );

      expect(scheduler.uploads, isEmpty);
    });
  });

  group('a run that could not reach the server', () {
    /// The upload run against [transport], with one new photo.
    Future<BackgroundRunResult> runAgainst(
      http.Client transport, {
      String task = backgroundUploadTaskName,
      FakeBackgroundScheduler? scheduler,
      InMemorySettingsStore? store,
      ConnectivitySource? connectivity,
      HostLookup? lookup,
    }) =>
        runBackgroundSync(
          store: store ?? anyNetworkStore(),
          library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
          transport: transport,
          clock: () => now,
          connectivity: connectivity ?? FakeConnectivity(),
          hashCache: MemoryHashCache(),
          task: task,
          relay: MemorySyncRelay(),
          scheduler: scheduler,
          lookup: lookup,
        );

    /// A server that is not there: every request fails in the transport.
    http.Client unreachable() => MockClient(
        (_) async => throw http.ClientException("Connection refused"));

    /// A server that answers every request with "not signed in".
    http.Client signedOut() => MockClient((_) async =>
        http.Response(refusal("This device is not signed in."), 401));

    test('is handed over again after half a minute, then a minute', () async {
      var store = anyNetworkStore();
      var scheduler = FakeBackgroundScheduler();
      var first =
          await runAgainst(unreachable(), scheduler: scheduler, store: store);
      var second =
          await runAgainst(unreachable(), scheduler: scheduler, store: store);

      expect(first.ok, isFalse);
      expect(first.transient, isTrue);
      expect(first.record?.retries, 1);
      expect(second.record?.retries, 2);
      expect(scheduler.retries,
          [BackgroundNetwork.connected, BackgroundNetwork.connected]);
      expect(scheduler.retryDelays,
          [const Duration(seconds: 30), const Duration(minutes: 1)]);
      // Read back by the next engine, which counts on from it.
      expect((await store.loadBackgroundRunRecord())?.retries, 2);
    });

    test('a run that reaches the server again starts the count afresh',
        () async {
      var store = anyNetworkStore();
      var scheduler = FakeBackgroundScheduler();
      await runAgainst(unreachable(), scheduler: scheduler, store: store);
      var back = await runAgainst(FakeServer().transport,
          scheduler: scheduler, store: store);
      await runAgainst(unreachable(), scheduler: scheduler, store: store);

      expect(back.ok, isTrue);
      expect(back.record?.retries, 0);
      expect(scheduler.retryDelays,
          [const Duration(seconds: 30), const Duration(seconds: 30)]);
    });

    test('the run a trigger started itself, where there was no upload work, '
        'still arms the next trigger', () async {
      var scheduler = FakeBackgroundScheduler();
      var result = await runAgainst(unreachable(),
          task: backgroundTriggerTaskName, scheduler: scheduler);

      expect(result.transient, isTrue);
      expect(scheduler.retries, [BackgroundNetwork.connected]);
      expect(scheduler.rearms, [BackgroundNetwork.connected]);
    });

    test('a host that does not resolve is a server not reached', () async {
      var scheduler = FakeBackgroundScheduler();
      var result = await runAgainst(
          MockClient((_) async => throw const SocketException(
              "Failed host lookup: 'home.example'",
              osError: OSError("No address associated with hostname", 7))),
          scheduler: scheduler);

      expect(result.transient, isTrue);
      expect(scheduler.retries, [BackgroundNetwork.connected]);
    });

    test('a refusal no retry cures is not retried, and the next trigger is '
        'armed', () async {
      for (var (what, store, transport) in [
        ("not signed in", anyNetworkStore(), signedOut()),
        (
          "no server",
          enabledStore(
              url: null,
              token: null,
              config: const CameraRollConfig(enabled: true, wifiOnly: false)),
          unreachable(),
        ),
      ]) {
        for (var task in [backgroundTriggerTaskName, backgroundUploadTaskName]) {
          var scheduler = FakeBackgroundScheduler();
          var result = await runAgainst(transport,
              task: task, scheduler: scheduler, store: store);

          expect(result.ran, isTrue, reason: "$what, $task");
          expect(result.ok, isFalse, reason: "$what, $task");
          expect(result.transient, isFalse, reason: "$what, $task");
          expect(result.record?.retries, 0, reason: "$what, $task");
          expect(scheduler.retries, isEmpty, reason: "$what, $task");
          if (task == backgroundTriggerTaskName) {
            expect(scheduler.rearms, [BackgroundNetwork.connected],
                reason: what);
          }
        }
      }
    });

    test('a switched-off sync is not retried, with nothing registered',
        () async {
      for (var task in [backgroundTriggerTaskName, backgroundUploadTaskName]) {
        var scheduler = FakeBackgroundScheduler();
        var result = await runAgainst(unreachable(),
            task: task,
            scheduler: scheduler,
            store: enabledStore(config: const CameraRollConfig()));

        expect(result.ran, isFalse);
        expect(scheduler.retries, isEmpty);
        expect(scheduler.rearms, isEmpty);
        expect(scheduler.ensures, isEmpty);
      }
    });

    test('Wi-Fi-only on a mobile connection is not retried: the constraint of '
        'the work waits for the Wi-Fi', () async {
      var scheduler = FakeBackgroundScheduler();
      var result = await runAgainst(unreachable(),
          store: enabledStore(),
          connectivity: FakeConnectivity(NetworkKind.mobile),
          scheduler: scheduler);

      expect(result.ok, isFalse);
      expect(result.transient, isFalse);
      expect(scheduler.retries, isEmpty);
    });

    test('only the reasons of a server not reached pass on their own', () {
      expect(transientNotice(const ServerOffline()), isTrue);
      expect(transientNotice(const ServerUnreachable("timeout")), isTrue);
      expect(transientNotice(const NoNetworkForSync()), isTrue);
      expect(transientNotice(const NoServerConfigured()), isFalse);
      expect(transientNotice(const NoWifiMobile()), isFalse);
      expect(transientNotice(const PhotoAccessDenied()), isFalse);
      expect(transientNotice(null), isFalse);
    });
  });

  group('a network Android blocked', () {
    /// A lookup that fails the way Android 15 fails one of a process it cut
    /// off the network.
    Future<Object?> blocked(String host) async => SocketException(
        "Failed host lookup: '$host'",
        osError: const OSError("No address associated with hostname", 7));

    test('is said as such before the server is asked, and not retried',
        () async {
      var store = anyNetworkStore();
      var scheduler = FakeBackgroundScheduler();
      var server = FakeServer();
      var log = DiagnosticsLog(now: () => now);
      var hosts = <String>[];
      var result = await runBackgroundSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        transport: server.transport,
        clock: () => now,
        connectivity: FakeConnectivity(),
        hashCache: MemoryHashCache(),
        task: backgroundUploadTaskName,
        relay: MemorySyncRelay(),
        scheduler: scheduler,
        log: log,
        lookup: (host) {
          hosts.add(host);
          return blocked(host);
        },
      );

      expect(hosts, ["server"]);
      expect(result.blocked, isTrue);
      expect(result.transient, isFalse);
      expect(result.record?.message,
          "Android blocked the network for the background run");
      expect(await store.loadBackgroundRunRecord(), result.record);
      expect(server.requests, isEmpty);
      // Retried, it would be blocked alike.
      expect(scheduler.retries, isEmpty);
      var entry = log.entries.single;
      expect(entry.headline,
          startsWith("Android blocked the network for the background run"));
      expect(entry.facts, contains(contains("errno = 7")));
      expect(entry.facts, contains("Host: server"));
      expect(entry.facts, contains("Run: $backgroundUploadTaskName"));
    });

    test('is not claimed where the device has no network at all', () async {
      var asked = 0;
      var result = await runBackgroundSync(
        store: anyNetworkStore(),
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        transport: FakeServer().transport,
        clock: () => now,
        connectivity: FakeConnectivity(NetworkKind.none),
        hashCache: MemoryHashCache(),
        task: backgroundUploadTaskName,
        relay: MemorySyncRelay(),
        lookup: (host) {
          asked++;
          return blocked(host);
        },
      );

      expect(asked, 0);
      expect(result.blocked, isFalse);
    });

    test('is not claimed where the name resolves', () async {
      var server = FakeServer();
      var result = await runBackgroundSync(
        store: anyNetworkStore(),
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        transport: server.transport,
        clock: () => now,
        connectivity: FakeConnectivity(),
        hashCache: MemoryHashCache(),
        task: backgroundUploadTaskName,
        relay: MemorySyncRelay(),
        lookup: (_) async => null,
      );

      expect(result.blocked, isFalse);
      expect(result.ok, isTrue);
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
    });
  });

  group('one run at a time', () {
    test('a background run waits for the one that holds the claim', () async {
      var relay = MemorySyncRelay();
      var held = await relay.claim(
          task: backgroundTriggerTaskName, patience: Duration.zero);
      var server = FakeServer();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);

      var run = backgroundRun(enabledStore(), library, server, relay);
      await pumpEventQueue();
      expect(server.requests, isEmpty);

      await held!.release();
      var result = await run;
      expect(result.record?.stored, 1);
      expect(
          relay.claims, [backgroundTriggerTaskName, backgroundUploadTaskName]);
      expect(relay.busy, isFalse);
    });

    test('a run that does not get the claim leaves the photos to that one',
        () async {
      var relay = MemorySyncRelay(waits: false);
      await relay.claim(
          task: backgroundUploadTaskName, patience: Duration.zero);
      var store = enabledStore();
      var server = FakeServer();

      var result = await backgroundRun(
          store, FakePhotoLibrary(items: [photo("a.jpg", 1)]), server, relay,
          task: backgroundSyncTaskName);

      expect(result.skipped, platformMessages.backgroundSyncBusy);
      expect(server.requests, isEmpty);
      // The run that holds the claim reports; this one wrote no report.
      expect(await store.loadBackgroundRunRecord(), isNull);
      expect(relay.missed, [backgroundSyncTaskName]);
    });

    test('the run reports every status and its end through the relay',
        () async {
      var relay = MemorySyncRelay();
      await backgroundRun(enabledStore(),
          FakePhotoLibrary(items: [photo("a.jpg", 1)]), FakeServer(), relay);

      var last = relay.published.last;
      expect(last.task, backgroundUploadTaskName);
      expect(last.finished, isTrue);
      expect(last.retryable, isFalse);
      expect(CameraRollStatus.fromWire(last.status).lastStored, 1);
      expect(
        [
          for (var message in relay.published)
            if (!message.finished)
              CameraRollStatus.fromWire(message.status).phase
        ],
        contains(CameraRollPhase.running),
      );
    });
  });

  group('the app hands its run over', () {
    test('Sync now is carried out by the foreground work, not by the app',
        () async {
      var store = anyNetworkStore();
      var library = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      var server = FakeServer();
      var relay = MemorySyncRelay();
      var timers = FakeTimers();
      Future<BackgroundRunResult>? work;
      var handover = FakeSyncHandover(
        onRequest: () => work = backgroundRun(store, library, server, relay),
      );
      var sync = CameraRollSync(
        store: store,
        library: library,
        // The app has no client to upload with: whatever is uploaded, the
        // work uploaded.
        clientOf: () => null,
        relay: relay,
        handover: handover,
        timerFactory: timers.create,
        clock: () => now,
      );
      addTearDown(sync.dispose);
      var seen = <String>[];
      sync.addListener(
          () => seen.add("${sync.status.phase.name} ${sync.status.done}/"
              "${sync.status.total}"));
      await sync.load();

      sync.start();
      await pumpEventQueue();
      await work;
      await pumpEventQueue();

      expect(handover.requests, 1);
      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
      // The section said at once that something happens, then followed the
      // work's own progress.
      expect(seen, contains("running 0/0"));
      expect(seen, contains("running 0/1"));
      expect(
          seen.indexOf("running 0/0"), lessThan(seen.indexOf("running 0/1")));
      expect(sync.status.phase, CameraRollPhase.idle);
      expect(sync.status.lastStored, 1);
      // What the work wrote is what the app reads now.
      expect(sync.config.since, DateTime.utc(2026, 3, 1, 12, 1));
      expect(sync.lastBackgroundRun?.stored, 1);
      // Only the work held the claim; the app never ran beside it.
      expect(relay.claims, [backgroundUploadTaskName]);
      expect(timers.pending, isNot(contains(const Duration(seconds: 5))));
    });

    test('a run that finds nothing new is no foreground work', () async {
      var store = anyNetworkStore();
      var server = FakeServer();
      var client = clientOf(server);
      var handover = FakeSyncHandover();
      var relay = MemorySyncRelay();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => client,
        callerOf: () async => CallerInfo.of(await client.authInfo()),
        relay: relay,
        handover: handover,
        timerFactory: FakeTimers().create,
        clock: () => now,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(handover.requests, 0);
      expect(server.uploads, isEmpty);
      expect(relay.claims, [appRunTask]);
      expect(sync.status.phase, CameraRollPhase.idle);
      expect(sync.status.lastSuccess, now);
    });

    test('a refusal the device answers itself asks the platform for nothing',
        () async {
      var store = enabledStore();
      var handover = FakeSyncHandover();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        relay: MemorySyncRelay(),
        handover: handover,
        connectivity: FakeConnectivity(NetworkKind.mobile),
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(handover.requests, 0);
      expect(sync.status.phase, CameraRollPhase.waiting);
      expect(sync.status.notice, const NoWifiMobile());
    });

    test('a refused handover is run by the app itself, holding the claim',
        () async {
      var store = anyNetworkStore();
      var server = FakeServer();
      var client = clientOf(server);
      var relay = MemorySyncRelay();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => client,
        callerOf: () async => CallerInfo.of(await client.authInfo()),
        relay: relay,
        handover: FakeSyncHandover(accepts: false),
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(server.uploadedNames, [
        ["a.jpg"]
      ]);
      expect(relay.claims, [appRunTask]);
      expect(relay.busy, isFalse);
    });

    test('a work that ends without a word ends the wait', () async {
      var timers = FakeTimers();
      var handover = FakeSyncHandover();
      var sync = CameraRollSync(
        store: anyNetworkStore(),
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        relay: MemorySyncRelay(),
        handover: handover,
        timerFactory: timers.create,
      );
      addTearDown(sync.dispose);
      await sync.load();
      var run = sync.syncNow();
      await pumpEventQueue();
      expect(sync.status.running, isTrue);

      // Still waiting: looked after again.
      expect(timers.fire(const Duration(seconds: 5)), 1);
      await pumpEventQueue();
      expect(sync.status.running, isTrue);

      // Gone — stopped, cut off, crashed.
      handover.isPending = false;
      expect(timers.fire(const Duration(seconds: 5)), 1);
      await run;
      expect(sync.status.phase, CameraRollPhase.idle);
    });

    test('Stop reaches the run elsewhere, and drops the one still to come',
        () async {
      var relay = MemorySyncRelay();
      var held = await relay.claim(
          task: backgroundTriggerTaskName, patience: Duration.zero);
      var handover = FakeSyncHandover();
      var sync = CameraRollSync(
        store: anyNetworkStore(),
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        relay: relay,
        handover: handover,
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();
      sync.start();
      await pumpEventQueue();
      held!.publish(const CameraRollStatus(
        phase: CameraRollPhase.running,
        done: 1,
        total: 4,
      ).toWire());
      await pumpEventQueue();
      expect(sync.status.done, 1);

      sync.stop();
      await pumpEventQueue();

      expect(relay.stops, 1);
      expect(handover.cancelled, 1);
      await held.release();
    });
  });

  group('the app watches the runs it did not start', () {
    test('shows what a new photo uploaded while it looks', () async {
      var store = anyNetworkStore();
      var library = FakePhotoLibrary();
      var relay = MemorySyncRelay();
      var sync = CameraRollSync(
        store: store,
        library: library,
        clientOf: () => null,
        relay: relay,
        timerFactory: FakeTimers().create,
        clock: () => now,
      );
      addTearDown(sync.dispose);
      await sync.load();
      sync.start();
      await pumpEventQueue();

      var phases = <CameraRollPhase>[];
      sync.addListener(() => phases.add(sync.status.phase));
      library.items.add(photo("a.jpg", 1));
      await backgroundRun(store, library, FakeServer(), relay,
          task: backgroundTriggerTaskName);
      await pumpEventQueue();

      expect(phases, contains(CameraRollPhase.running));
      expect(sync.status.phase, CameraRollPhase.idle);
      expect(sync.status.lastStored, 1);
      expect(sync.config.since, DateTime.utc(2026, 3, 1, 12, 1));
    });

    test('retries a run elsewhere that failed for a reason a retry fixes',
        () async {
      var store = anyNetworkStore();
      var library = FakePhotoLibrary();
      var relay = MemorySyncRelay();
      var timers = FakeTimers();
      var sync = CameraRollSync(
        store: store,
        library: library,
        clientOf: () => null,
        relay: relay,
        timerFactory: timers.create,
      );
      addTearDown(sync.dispose);
      await sync.load();
      sync.start();
      await pumpEventQueue();

      library.items.add(photo("a.jpg", 1));
      var server = FakeServer()
        ..upload = (_) => http.Response("down for maintenance", 503);
      await backgroundRun(store, library, server, relay,
          task: backgroundTriggerTaskName);
      await pumpEventQueue();

      expect(sync.status.phase, CameraRollPhase.waiting);
      expect(timers.pending, contains(retryDelays.first));
    });
  });

  group('the configuration two isolates write', () {
    test('keeps the watermark another run moved when a switch is toggled',
        () async {
      var store = anyNetworkStore();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        relay: MemorySyncRelay(),
        timerFactory: FakeTimers().create,
      );
      addTearDown(sync.dispose);
      await sync.load();

      // A background run moved the watermark meanwhile.
      var moved = DateTime.utc(2026, 3, 1, 12, 5);
      store.cameraRoll =
          (await store.loadCameraRollConfig()).copyWith(since: moved).toJson();

      await sync.setWifiOnly(true);

      var stored = await store.loadCameraRollConfig();
      expect(stored.wifiOnly, isTrue);
      expect(stored.since, moved);
    });
  });

  group('the notification', () {
    test('says how many photos are still to go while a run uploads', () {
      expect(
        syncNoticeOf(
          const CameraRollStatus(
              phase: CameraRollPhase.running, done: 2, total: 5),
          testL10n,
        ),
        SyncNotice(
          title: testL10n.syncNotificationTitle,
          text: "Uploading 3 photos...",
          done: 2,
          total: 5,
        ),
      );
      expect(
        syncNoticeOf(
          const CameraRollStatus(
              phase: CameraRollPhase.running, done: 4, total: 5),
          testL10n,
        )?.text,
        "Uploading 1 photo...",
      );
    });

    test('has nothing to say while nothing is uploaded', () {
      for (var status in const [
        CameraRollStatus(phase: CameraRollPhase.idle),
        CameraRollStatus(phase: CameraRollPhase.waiting),
        CameraRollStatus(phase: CameraRollPhase.running),
        CameraRollStatus(phase: CameraRollPhase.running, done: 3, total: 3),
      ]) {
        expect(syncNoticeOf(status, testL10n), isNull);
      }
    });

    test('follows the background run and goes with it', () async {
      var notifier = FakeSyncNotifier(isAllowed: true);
      await backgroundRun(
        enabledStore(),
        FakePhotoLibrary(items: [photo("a.jpg", 1), photo("b.jpg", 2)]),
        FakeServer(),
        MemorySyncRelay(),
        notifier: notifier,
      );

      var id = syncNotificationIdOf(backgroundUploadTaskName);
      expect(notifier.shown, [
        (
          id,
          SyncNotice(
            title: platformMessages.syncNotificationTitle,
            text: platformMessages.syncNotificationUploading(2),
            done: 0,
            total: 2,
          )
        ),
      ]);
      expect(notifier.cleared, [id]);
    });

    test('a run that uploads nothing shows nothing', () async {
      var notifier = FakeSyncNotifier(isAllowed: true);
      await backgroundRun(
          enabledStore(), FakePhotoLibrary(), FakeServer(), MemorySyncRelay(),
          notifier: notifier);

      expect(notifier.shown, isEmpty);
      expect(notifier.cleared, isEmpty);
    });

    test('the status line of a run that has not reported yet', () {
      expect(
        cameraRollLine(
            const CameraRollStatus(phase: CameraRollPhase.running), testL10n),
        testL10n.cameraRollLooking,
      );
    });
  });

  group('the permission to show it', () {
    CameraRollSync engine(SyncNotifier notifier) => CameraRollSync(
          store: enabledStore(config: const CameraRollConfig()),
          library: FakePhotoLibrary(),
          clientOf: () => null,
          notifier: notifier,
          timerFactory: FakeTimers().create,
        );

    testWidgets('is asked when the sync is switched on, saying why',
        (tester) async {
      var notifier = FakeSyncNotifier();
      var sync = engine(notifier);
      addTearDown(sync.dispose);
      await sync.load();
      await pumpSection(tester, sync);
      expect(find.byKey(cameraRollNotificationsOffKey), findsNothing);

      await tester.tap(find.byKey(cameraRollSwitchKey));
      await tester.pumpAndSettle();
      expect(find.byKey(cameraRollNotificationsWhyKey), findsOneWidget);
      expect(find.text(testL10n.notificationsWhyBody), findsOneWidget);

      await tester.tap(find.byKey(cameraRollNotificationsAllowKey));
      await tester.pumpAndSettle();
      expect(notifier.requests, 1);
      expect(find.byKey(cameraRollNotificationsWhyKey), findsNothing);
      expect(find.byKey(cameraRollNotificationsOffKey), findsNothing);
    });

    testWidgets('a refusal says what it costs', (tester) async {
      var notifier = FakeSyncNotifier(grants: false);
      var sync = engine(notifier);
      addTearDown(sync.dispose);
      await sync.load();
      await pumpSection(tester, sync);

      await tester.tap(find.byKey(cameraRollSwitchKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(cameraRollNotificationsAllowKey));
      await tester.pumpAndSettle();

      expect(find.byKey(cameraRollNotificationsOffKey), findsOneWidget);
      expect(find.text(testL10n.cameraRollNotificationsOff), findsOneWidget);
    });

    testWidgets('"Not now" asks the system nothing, and says so too',
        (tester) async {
      var notifier = FakeSyncNotifier();
      var sync = engine(notifier);
      addTearDown(sync.dispose);
      await sync.load();
      await pumpSection(tester, sync);

      await tester.tap(find.byKey(cameraRollSwitchKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(cameraRollNotificationsNotNowKey));
      await tester.pumpAndSettle();

      expect(notifier.requests, 0);
      expect(find.byKey(cameraRollNotificationsOffKey), findsOneWidget);
    });

    testWidgets('is not asked where it is granted, or cannot be asked',
        (tester) async {
      for (var notifier in [
        FakeSyncNotifier(isAllowed: true),
        FakeSyncNotifier(isAskable: false),
        FakeSyncNotifier(available: false),
      ]) {
        var sync = engine(notifier);
        await sync.load();
        await pumpSection(tester, sync);

        await tester.tap(find.byKey(cameraRollSwitchKey));
        await tester.pumpAndSettle();

        expect(find.byKey(cameraRollNotificationsWhyKey), findsNothing);
        expect(notifier.requests, 0);
        // Blocked in the system settings: the section says so.
        expect(
            find.byKey(cameraRollNotificationsOffKey),
            notifier.available && !notifier.isAllowed
                ? findsOneWidget
                : findsNothing);
        await tester.pumpWidget(const SizedBox());
        sync.dispose();
      }
    });
  });
}
