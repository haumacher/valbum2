/// Every failure of a camera-roll run leaves an entry in the diagnostics log
/// (2.15.0: "album sync is now completely broken NPE getClass on null —
/// nothing in the diagnostics log"): each way a run ends without finishing
/// writes exactly one entry, with the sentence the camera-roll section shows
/// and the complete text of what the platform threw; a background run that
/// fails writes it where the app reads it.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/diagnostics_file.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';

import 'background_test.dart' show FakeServer, enabledStore, now;
import 'util/fake_timers.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// The message of the exception the user reported, as R8 words it.
const String npeMessage = "Attempt to invoke virtual method "
    "'java.lang.Class java.lang.Object.getClass()' on a null object reference";

/// The exception as the platform channel delivers it, stack in the details.
PlatformException npe() => PlatformException(
      code: "error",
      message: npeMessage,
      details: "java.lang.NullPointerException: $npeMessage\n"
          "\tat h10.e(r8-map-id-0123456789abcdef:12)",
    );

/// The line of the log that carries the whole of [npe].
const String npeFact = "Platform error: code error, message $npeMessage, "
    "details java.lang.NullPointerException: $npeMessage\n"
    "\tat h10.e(r8-map-id-0123456789abcdef:12)";

/// A photo taken [minute] minutes after noon.
PhotoItem photo(String name, int minute) => fakePhoto(
      name,
      name.codeUnits,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
    );

/// A photo whose contents the platform cannot open.
PhotoItem unreadable(String name, int minute) => PhotoItem(
      id: name,
      name: name,
      takenAt: DateTime.utc(2026, 3, 1, 12, minute),
      length: 3,
      openRead: () => Stream.error(PhotoLibraryException(
        const PhotoLibraryFailed(npeMessage),
        error: npe(),
      )),
    );

/// A library whose listings throw.
class ThrowingLibrary extends FakePhotoLibrary {
  final Object? albumsFailure;
  final Object? scanFailure;

  ThrowingLibrary({this.albumsFailure, this.scanFailure, super.items});

  @override
  Future<List<PhotoAlbum>> albums() async {
    if (albumsFailure != null) {
      // As `PhotoManagerLibrary.albums` does: none, and why.
      accessProblem = PhotoLibraryFailed(platformErrorText(albumsFailure!));
      accessError = albumsFailure;
      return const [];
    }
    return super.albums();
  }

  @override
  Future<List<PhotoItem>> itemsSince(DateTime? since,
      {List<String> sources = const []}) async {
    if (scanFailure != null) {
      throw scanFailure!;
    }
    return super.itemsSince(since, sources: sources);
  }
}

/// A store whose writes of the configuration fail once a run started.
class BrokenStore extends InMemorySettingsStore {
  bool broken = false;

  @override
  Future<void> saveCameraRoll(String json) async {
    if (broken) {
      throw const FileSystemException("No space left on device");
    }
    await super.saveCameraRoll(json);
  }
}

/// One way a run ends without finishing.
class Failure {
  final String name;

  /// The headline the entry carries after "Camera-roll sync did not
  /// finish: ".
  final String reason;

  /// Facts the entry must carry besides "Run" and "Outcome".
  final List<String> facts;

  final bool offline;
  final NetworkKind network;
  final bool withClient;
  final FakePhotoLibrary Function() library;
  final Future<CallerInfo?> Function() caller;
  final Future<http.Response> Function(http.Request request)? upload;
  final bool breakStore;
  final CameraRollConfig config;

  Failure(
    this.name, {
    required this.reason,
    this.facts = const [],
    this.offline = false,
    this.network = NetworkKind.wifi,
    this.withClient = true,
    FakePhotoLibrary Function()? library,
    Future<CallerInfo?> Function()? caller,
    this.upload,
    this.breakStore = false,
    this.config = const CameraRollConfig(enabled: true),
  })  : library =
            library ?? (() => FakePhotoLibrary(items: [photo("a.jpg", 1)])),
        caller = caller ?? (() async => member);
}

const CallerInfo member =
    CallerInfo(role: roleMember, inbox: "Inbox", inboxCount: 0);

/// The English sentence of [notice], as the section shows it in English.
String said(AppNotice notice) => noticeText(notice, testL10n);

final List<Failure> failures = [
  Failure(
    "offline",
    reason: said(const ServerOffline()),
    offline: true,
  ),
  Failure(
    "no Wi-Fi",
    reason: said(const NoWifiMobile()),
    network: NetworkKind.mobile,
    config: const CameraRollConfig(enabled: true, wifiOnly: true),
  ),
  Failure(
    "no server configured",
    reason: said(const NoServerConfigured()),
    withClient: false,
  ),
  Failure(
    "no access to the photos",
    reason: said(const PhotoAccessDenied()),
    library: () => FakePhotoLibrary(
        granted: false, accessProblem: const PhotoAccessDenied()),
  ),
  Failure(
    "the access check threw the getClass NPE",
    reason: said(const PhotoLibraryOpenFailed(npeMessage)),
    facts: [npeFact],
    library: () => FakePhotoLibrary(
      granted: false,
      accessProblem: const PhotoLibraryOpenFailed(npeMessage),
      accessError: npe(),
    ),
  ),
  Failure(
    "the server refused to say who the device is",
    reason: "Not signed in.",
    caller: () async => throw const VAlbumException("Not signed in.",
        status: 401, url: "$serverDataUrl/?type=auth"),
    facts: ["Server answered: 401, $serverDataUrl/?type=auth"],
  ),
  Failure(
    "the server could not be asked who the device is",
    reason: said(const ServerUnreachable("no route")),
    caller: () async => throw http.ClientException("no route"),
    facts: ["Platform error: ClientException: no route"],
  ),
  Failure(
    "a guest",
    reason: said(guestNoSpaceNotice),
    caller: () async => const CallerInfo(role: roleGuest),
  ),
  Failure(
    "no inbox",
    reason: said(const NoInboxForCaller()),
    caller: () async => const CallerInfo(role: roleMember),
  ),
  Failure(
    "the albums cannot be listed",
    reason: said(const PhotoLibraryFailed(npeMessage)),
    facts: [npeFact],
    library: () => ThrowingLibrary(albumsFailure: npe()),
  ),
  Failure(
    "the scan threw the getClass NPE",
    reason: said(const PhotoLibraryFailed(npeMessage)),
    facts: [npeFact],
    library: () => ThrowingLibrary(scanFailure: npe()),
    config: const CameraRollConfig(enabled: true, sources: ["camera"]),
  ),
  Failure(
    "a photo cannot be read",
    reason: said(const PhotoLibraryFailed(npeMessage)),
    facts: [npeFact],
    library: () => FakePhotoLibrary(items: [unreadable("a.jpg", 1)]),
  ),
  Failure(
    "the server refuses the upload",
    reason: "The disk is full.",
    upload: (_) async =>
        http.Response('["ErrorInfo",{"message":"The disk is full."}]', 507),
    facts: ["Server answered: 507"],
  ),
  Failure(
    "the upload loses its connection",
    reason: said(const ServerUnreachable("connection reset")),
    upload: (_) async => throw http.ClientException("connection reset"),
    facts: ["Platform error: ClientException: connection reset"],
  ),
  Failure(
    "the watermark cannot be stored",
    reason: said(const SyncFailed(
        "FileSystemException: No space left on device, path = ''")),
    breakStore: true,
    facts: [
      "Platform error: FileSystemException: No space left on device, "
          "path = ''"
    ],
  ),
];

/// The entries of [log] that tell a run did not finish.
List<DiagnosticsEntry> runFailures(DiagnosticsLog log) => [
      for (var entry in log.entries)
        if (entry.headline.startsWith("Camera-roll sync did not finish")) entry
    ];

/// The entries of [log] that are not the timing record of a run.
List<DiagnosticsEntry> problems(DiagnosticsLog log) => [
      for (var entry in log.entries)
        if (!entry.headline.startsWith("Camera-roll sync looked at")) entry
    ];

void main() {
  group('every way a run fails writes one entry', () {
    for (var failure in failures) {
      test(failure.name, () async {
        var store = BrokenStore()..cameraRoll = failure.config.toJson();
        var timers = FakeTimers();
        var log = DiagnosticsLog(now: () => timers.now);
        var client = VAlbumClient(
          dataUrl: serverDataUrl,
          httpClient: MockClient((request) async {
            if (request.method == "POST") {
              return http.Response('{"present":[]}', 200);
            }
            if (request.method == "PUT") {
              var upload = failure.upload;
              return upload == null
                  ? http.Response("", 200)
                  : await upload(request);
            }
            return http.Response("", 404);
          }),
        );
        var library = failure.library();
        var sync = CameraRollSync(
          store: store,
          library: library,
          clientOf: () => failure.withClient ? client : null,
          callerOf: failure.caller,
          isOffline: () => failure.offline,
          connectivity: FakeConnectivity(failure.network),
          clock: () => timers.now,
          timerFactory: timers.create,
          log: log,
        );
        addTearDown(sync.dispose);
        await sync.load();
        store.broken = failure.breakStore;

        await sync.syncNow();

        expect(sync.status.phase, isNot(CameraRollPhase.idle));
        var entries = problems(log);
        expect(entries, hasLength(1), reason: "$entries");
        var entry = entries.single;
        expect(entry.headline,
            "Camera-roll sync did not finish: ${failure.reason}");
        for (var fact in failure.facts) {
          expect(entry.facts, contains(fact));
        }
        expect(entry.facts, contains("Run: $appRunTask"));
        expect(entry.facts.last, startsWith("Outcome: "));
        // The section tells the same story: its sentence is the headline's.
        var shown =
            sync.status.message ?? noticeText(sync.status.notice!, testL10n);
        expect(entry.headline, endsWith(shown));
        // No Java stack, no secret: the details are the platform's, once.
        expect(entry.toString(), isNot(contains("token")));
      });
    }

    test('a failure that comes back with the retries stays one entry',
        () async {
      var store = InMemorySettingsStore()
        ..cameraRoll = const CameraRollConfig(enabled: true).toJson();
      var timers = FakeTimers();
      var log = DiagnosticsLog(now: () => timers.now);
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        isOffline: () => true,
        clock: () => timers.now,
        timerFactory: timers.create,
        log: log,
      );
      addTearDown(sync.dispose);
      await sync.load();
      sync.start();
      await pumpEventQueue();
      for (var delay in retryDelays) {
        timers.now = timers.now.add(delay);
        expect(timers.fire(delay), 1);
        await pumpEventQueue();
      }

      var entry = runFailures(log).single;
      expect(entry.count, retryDelays.length + 1);
      expect(entry.facts.last, startsWith("Outcome: retried in "));
    });

    test('a scheduler that refuses is written down', () async {
      var store = InMemorySettingsStore()
        ..cameraRoll = const CameraRollConfig(enabled: true).toJson();
      var log = DiagnosticsLog();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(),
        clientOf: () => null,
        scheduler: FakeBackgroundScheduler(
            problem: PlatformException(code: "x", message: "no work")),
        log: log,
      );
      addTearDown(sync.dispose);
      await sync.load();

      sync.start();
      await pumpEventQueue();

      var entry = log.entries
          .firstWhere((e) => e.headline.startsWith("Could not register"));
      expect(
          entry.headline,
          "Could not register the background sync: "
          "${said(const BackgroundScheduleFailed("no work"))}");
      expect(entry.facts.first, "Platform error: code x, message no work");
    });

    test('a handover WorkManager refuses is written down, and run here',
        () async {
      var store = enabledStore(
          config: const CameraRollConfig(enabled: true, wifiOnly: false));
      var log = DiagnosticsLog();
      var server = FakeServer();
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => VAlbumClient(
            dataUrl: "http://server/valbum/data/",
            httpClient: server.transport),
        callerOf: () async => member,
        relay: MemorySyncRelay(),
        handover: _RefusingHandover(),
        log: log,
      );
      addTearDown(sync.dispose);
      await sync.load();

      await sync.syncNow();

      expect(sync.status.phase, CameraRollPhase.idle);
      var entry = problems(log).single;
      expect(
          entry.headline,
          "Could not hand the camera-roll run to WorkManager: it runs in the "
          "app");
      expect(
          entry.facts.first,
          "Platform error: code error, message $npeMessage, details "
          "java.lang.NullPointerException: $npeMessage\n"
          "\tat h10.e(r8-map-id-0123456789abcdef:12)");
    });
  });

  group('a background run that fails', () {
    test('writes its entry where the app reads it, with the whole NPE',
        () async {
      var directory =
          Directory.systemTemp.createTempSync("valbum-background-log-");
      addTearDown(() => directory.deleteSync(recursive: true));
      var store = enabledStore(
          config: const CameraRollConfig(enabled: true, wifiOnly: false));
      var library = FakePhotoLibrary(
        items: [photo("a.jpg", 1)],
        granted: false,
        accessProblem: const PhotoLibraryOpenFailed(npeMessage),
        accessError: npe(),
      );

      // The background engine: a log of its own over the shared file.
      var result = await runBackgroundSync(
        store: store,
        library: library,
        transport: FakeServer().transport,
        connectivity: FakeConnectivity(),
        clock: () => now,
        hashCache: MemoryHashCache(),
        task: backgroundUploadTaskName,
        log: DiagnosticsLog(
          now: () => now,
          store: FileDiagnosticsStore.inDirectory(directory),
        ),
      );
      expect(result.ok, isFalse);

      // The app, started afterwards or told that the run ended.
      var app = DiagnosticsLog(
        store: FileDiagnosticsStore.inDirectory(directory),
      );
      await app.reload();

      var entry = runFailures(app).single;
      expect(entry.headline,
          "Camera-roll sync did not finish: ${said(const PhotoLibraryOpenFailed(npeMessage))}");
      expect(entry.facts, contains(npeFact));
      expect(entry.facts, contains("Run: $backgroundUploadTaskName"));
      // The report the section shows tells the same story, in the device's
      // language — English here.
      expect(result.record?.message,
          said(const PhotoLibraryOpenFailed(npeMessage)));
    });

    test('that the app handed over is shown by the app once, not twice',
        () async {
      var shared = MemoryDiagnosticsStore();
      var store = enabledStore(
          config: const CameraRollConfig(enabled: true, wifiOnly: false));
      var relay = MemorySyncRelay();
      var server = FakeServer()
        ..upload = (_) => http.Response("down for maintenance", 503);
      var background = FakePhotoLibrary(items: [photo("a.jpg", 1)]);
      Future<BackgroundRunResult>? work;
      var handover = FakeSyncHandover(
        onRequest: () => work = runBackgroundSync(
          store: store,
          library: background,
          transport: server.transport,
          connectivity: FakeConnectivity(),
          clock: () => now,
          hashCache: MemoryHashCache(),
          task: backgroundUploadTaskName,
          relay: relay,
          log: DiagnosticsLog(now: () => now, store: shared),
        ),
      );
      var timers = FakeTimers();
      var log = DiagnosticsLog(store: shared);
      var sync = CameraRollSync(
        store: store,
        library: FakePhotoLibrary(items: [photo("a.jpg", 1)]),
        clientOf: () => null,
        relay: relay,
        handover: handover,
        timerFactory: timers.create,
        clock: () => now,
        log: log,
      );
      addTearDown(sync.dispose);
      await sync.load();

      sync.start();
      await pumpEventQueue();
      await work;
      await pumpEventQueue();
      await log.flush();

      // The app retries, with its own back-off — and writes nothing of its
      // own about it: the run that failed wrote it.
      expect(sync.status.phase, CameraRollPhase.waiting);
      var entry = runFailures(log).single;
      expect(entry.facts, contains("Run: $backgroundUploadTaskName"));
      expect(entry.count, 1);
    });

    test('a task that throws is written down with its stack', () {
      var log = DiagnosticsLog();
      try {
        throw StateError("no engine");
      } catch (error, stack) {
        logBackgroundTaskFailure(log, backgroundTriggerTaskName, error, stack);
      }

      var entry = log.entries.single;
      expect(
          entry.headline,
          "The background task '$backgroundTriggerTaskName' failed: "
          "Bad state: no engine");
      expect(entry.facts.first, "Platform error: Bad state: no engine");
      expect(entry.facts[1], startsWith("Stack: "));
    });

    test('a notification plugin that fails is written down', () async {
      var log = DiagnosticsLog();
      var messenger =
          TestWidgetsFlutterBinding.ensureInitialized().defaultBinaryMessenger;
      const channel = MethodChannel("de.haumacher.valbum/sync_notification");
      messenger.setMockMethodCallHandler(channel, (call) async => throw npe());
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      var notifier = PluginSyncNotifier(log: log);

      await notifier.show(
        7,
        const SyncNotice(title: "t", text: "x", done: 0, total: 1),
        channelName: "c",
      );

      expect(
          log.entries.single.headline,
          "Could not show the upload notification: the camera-roll sync goes "
          "on without it");
      expect(log.entries.single.facts.single, npeFact);
    });
  });
}

/// A handover the platform refuses with the NPE.
class _RefusingHandover extends SyncHandover {
  @override
  String get task => backgroundUploadTaskName;

  @override
  Future<bool> request() async => throw npe();

  @override
  Future<bool> pending() async => false;

  @override
  Future<void> cancel() async {}
}
