/// Camera-roll sync (issue #30): new photos on the device flow into the inbox
/// of the space on the server — the one the server names in `?type=auth`
/// (issue #226), never one this app chooses — or, for a while, into the album
/// this device was told to send them to, see `upload_target.dart` (#240).
///
/// The engine is [CameraRollSync]. It is a state machine with exactly one run
/// at a time:
///
/// * a **trigger** (the library's change stream, the periodic scan, app
///   start-up, or the user's "Sync now") asks for a run;
/// * a run asks the library for everything taken at or after the *watermark*
///   ([CameraRollConfig.since]), drops the ids it already handled
///   ([CameraRollConfig.done]), and hands the rest to
///   [VAlbumClient.uploadNew] in batches;
/// * the watermark advances **after** a batch was accepted, never before, so
///   an interrupted run resumes where it stopped;
/// * a failure stops the run, keeps the watermark, says why, and schedules a
///   retry with an exponential back-off (30 s, 1 min, 2 min, … 30 min).
///
/// Nothing here decides whether a photo is a duplicate: the run hashes every
/// file and asks the server, and the server answers `present` for contents it
/// already holds (issue #29). A reinstalled app therefore starts with an empty
/// watermark, re-scans the whole library and transfers nothing.
///
/// A photo is hashed once in its life (issue #299): its hash is remembered
/// under its id, modification time and size ([HashCache]), the server is
/// asked about a few hundred remembered hashes in one request before anything
/// is read, and only what it does not hold is read again, to be uploaded —
/// [defaultParallelUploads] batches at a time. A newly ticked album of photos
/// that were synced already therefore reads no file at all. Every run that had
/// something to look at leaves one timing record in the diagnostics log, see
/// [SyncRunStats].
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'background.dart';
import 'caller.dart';
import 'client.dart';
import 'connectivity.dart';
import 'diagnostics.dart';
import 'hash_cache.dart';
import 'platform.dart' show sha256OfFile;
import 'photo_library.dart';
import 'resource.dart';
import 'notices.dart';
import 'settings.dart';
import 'upload_target.dart';

/// What a guest is answered instead of a camera-roll sync, see issue #54.
///
/// A guest's root is not a library of their own — it is what others shared
/// with them — so there is no space to create an inbox in and no album a photo
/// of theirs could land in. The server refuses it by role
/// (`AuthService.GUEST_SPACE_REFUSED`); this app says so before it asks, and
/// says the one thing that changes it — in the words of the device, see
/// [noticeText] (issue #108).
const AppNotice guestNoSpaceNotice = GuestHasNoSpace();

/// The point one watched album was synced up to, see
/// [CameraRollConfig.markOf].
///
/// Every album has its own, so that ticking a new one does not make the sync
/// skip what the other albums took in the meantime — and so that a newly
/// ticked album is fetched from the beginning rather than from the moment it
/// was ticked (issue #117).
@immutable
class SourceMark {
  /// The taken-at stamp of the newest item of this album that was handled,
  /// `null` while the album was never scanned — which asks for all of it.
  final DateTime? since;

  /// The ids of the handled items that carry exactly [since] as their
  /// taken-at stamp.
  ///
  /// Two photos taken in the same second must not make the sync choose
  /// between uploading one twice and skipping the other, so a mark is a stamp
  /// *plus* the ids at that stamp.
  final List<String> done;

  const SourceMark({this.since, this.done = const []});

  /// The mark of an album nothing was ever synced from.
  static const SourceMark beginning = SourceMark();

  /// This mark as the JSON the configuration keeps.
  Map<String, Object?> toJson() => {
        if (since != null) "since": since!.toUtc().toIso8601String(),
        "done": done,
      };

  /// The mark stored as [json], [beginning] for anything unreadable.
  static SourceMark parse(Object? json) {
    if (json is! Map) {
      return beginning;
    }
    var since = json["since"];
    return SourceMark(
      since: since is String ? DateTime.tryParse(since) : null,
      done: [for (var id in (json["done"] as List? ?? [])) "$id"],
    );
  }

  @override
  bool operator ==(Object other) =>
      other is SourceMark &&
      other.since == since &&
      listEquals(other.done, done);

  @override
  int get hashCode => Object.hash(since, done.length);

  @override
  String toString() => jsonEncode(toJson());
}

/// What the sync does, and what it is configured with.
///
/// Persisted as one JSON blob through the [SettingsStore], see
/// [CameraRollStorage]. A store written before issue #30 holds nothing under
/// that key; it loads as [CameraRollConfig.disabled], so an app that is
/// updated does not suddenly start uploading.
@immutable
class CameraRollConfig {
  /// Whether the app watches the photo library.
  final bool enabled;

  /// Whether a run goes ahead while the server is still building its hash
  /// index (issue #118).
  ///
  /// Off by default, and deliberately: while the index is incomplete the
  /// server cannot say that a photo is already in some album it has not read
  /// yet, so a first sync over an existing library would upload photos it
  /// already has. The run waits instead and says so, and the section offers
  /// *Sync anyway* for whoever would rather have the photos now and the
  /// duplicates later.
  final bool syncWhileIndexing;

  /// Whether the sync only runs on an unmetered network (issue #36).
  ///
  /// On by default, also for a store written before issue #36 that holds no
  /// value for it: uploading a camera roll over a mobile connection can cost
  /// real money, so the safe answer is the one an app that was updated gets.
  final bool wifiOnly;

  /// The taken-at stamp of the newest item that was handled, `null` before the
  /// first run.
  ///
  /// The next run asks the library for everything from here on — inclusively,
  /// so the newest handled item shows up again and is recognised by [done].
  final DateTime? since;

  /// The ids of the items already handled that carry exactly [since] as their
  /// taken-at stamp.
  ///
  /// Two photos taken in the same second must not make the sync choose between
  /// uploading one twice and skipping the other, so the watermark is a stamp
  /// *plus* the ids at that stamp.
  final List<String> done;

  /// The ids of the device albums the sync watches, `null` while the user
  /// never ticked any (issue #117).
  ///
  /// `null` is not "everything": it is the **default**, which is the camera
  /// album alone where the device names one, see [defaultSources] — a store
  /// written before this field therefore starts watching the camera and
  /// nothing else, and an app that is updated never uploads more than it did.
  /// An empty list is the user's own decision to watch nothing; the section
  /// says so and the run finds nothing.
  final List<String>? sources;

  /// How far each watched album was synced, by [PhotoAlbum.id].
  ///
  /// An album with no mark of its own falls back to [since]/[done] — which is
  /// what carries the single watermark of a store written before issue #117
  /// over to the camera album it turns out to have been.
  final Map<String, SourceMark> marks;

  const CameraRollConfig({
    this.enabled = false,
    this.wifiOnly = true,
    this.syncWhileIndexing = false,
    this.since,
    this.done = const [],
    this.sources,
    this.marks = const {},
  });

  /// The configuration of an app that was never told to sync anything.
  static const CameraRollConfig disabled = CameraRollConfig();

  /// The source id standing for the whole photo library.
  ///
  /// The sync falls back to it where the device names no album at all — an
  /// empty library, or a platform whose [PhotoLibrary] does not enumerate
  /// albums. There is nothing to tick there, and scanning everything is what
  /// the sync did before issue #117; its mark is [since]/[done], so nothing
  /// about a library without albums changed.
  static const String wholeLibrary = "";

  /// How far [source] was synced, see [marks].
  SourceMark markOf(String source) =>
      marks[source] ?? SourceMark(since: since, done: done);

  /// The same configuration with the mark of [source] replaced.
  CameraRollConfig withMark(String source, SourceMark mark) =>
      source == wholeLibrary
          ? copyWith(since: mark.since, done: mark.done)
          : copyWith(marks: {...marks, source: mark});

  /// The albums this configuration watches, given what the device holds.
  ///
  /// The user's own choice where they made one, else [defaultSources].
  List<String> sourcesOf(List<PhotoAlbum> albums) =>
      sources ?? defaultSources(albums);

  /// The same configuration with the given values replaced.
  CameraRollConfig copyWith({
    bool? enabled,
    bool? wifiOnly,
    bool? syncWhileIndexing,
    DateTime? since,
    List<String>? done,
    List<String>? sources,
    Map<String, SourceMark>? marks,
  }) =>
      CameraRollConfig(
        enabled: enabled ?? this.enabled,
        wifiOnly: wifiOnly ?? this.wifiOnly,
        syncWhileIndexing: syncWhileIndexing ?? this.syncWhileIndexing,
        since: since ?? this.since,
        done: done ?? this.done,
        sources: sources ?? this.sources,
        marks: marks ?? this.marks,
      );

  /// This configuration as the JSON blob the store keeps.
  String toJson() => jsonEncode({
        "enabled": enabled,
        "wifiOnly": wifiOnly,
        if (syncWhileIndexing) "syncWhileIndexing": true,
        if (since != null) "since": since!.toUtc().toIso8601String(),
        "done": done,
        if (sources != null) "sources": sources,
        if (marks.isNotEmpty)
          "marks": {
            for (var entry in marks.entries) entry.key: entry.value.toJson()
          },
      });

  /// The configuration stored as [text], [disabled] if there is none.
  ///
  /// A blob this version cannot read is treated as absent rather than as a
  /// reason to fail: nothing is lost but the watermark — the server answers
  /// `present` for what it already holds.
  ///
  /// The `inbox` an app before issue #226 stored is ignored: the server names
  /// the one inbox of the space, see [CallerInfo.inbox].
  static CameraRollConfig parse(String? text) {
    if (text == null || text.trim().isEmpty) {
      return disabled;
    }
    try {
      var json = jsonDecode(text);
      if (json is! Map) {
        return disabled;
      }
      var since = json["since"];
      return CameraRollConfig(
        enabled: json["enabled"] == true,
        // Absent means on: a store from before issue #36 must not start
        // uploading over a mobile connection because it was silent.
        wifiOnly: json["wifiOnly"] != false,
        // Absent means off: a store written before issue #118 must not sync
        // against an index that cannot yet answer for the whole library.
        syncWhileIndexing: json["syncWhileIndexing"] == true,
        since: since is String ? DateTime.tryParse(since) : null,
        done: [for (var id in (json["done"] as List? ?? [])) "$id"],
        // Absent means "the camera album" (issue #117), which only the device
        // can name; a missing list is therefore kept missing rather than read
        // as "nothing" or as "everything", see [sourcesOf].
        sources: json["sources"] is List
            ? [for (var id in (json["sources"] as List)) "$id"]
            : null,
        marks: {
          for (var entry in (json["marks"] as Map? ?? {}).entries)
            "${entry.key}": SourceMark.parse(entry.value)
        },
      );
    } catch (_) {
      return disabled;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is CameraRollConfig &&
      other.enabled == enabled &&
      other.wifiOnly == wifiOnly &&
      other.syncWhileIndexing == syncWhileIndexing &&
      other.since == since &&
      listEquals(other.done, done) &&
      listEquals(other.sources, sources) &&
      mapEquals(other.marks, marks);

  @override
  int get hashCode => Object.hash(
        enabled,
        wifiOnly,
        since,
        done.length,
        sources?.length,
        marks.length,
      );

  @override
  String toString() => toJson();
}

/// The albums a user may tick, out of what the device holds (issue #117).
///
/// Everything but the platform's "all photos" pseudo-album, see
/// [PhotoAlbum.isAll]: watching *Recents* is watching the whole library,
/// which is the thing the tick boxes exist to end. On iOS that pseudo-album
/// *is* the camera roll — there is no `DCIM/Camera` there — so where it is
/// also the camera it stays in the list and is named like any other album.
List<PhotoAlbum> selectableSources(List<PhotoAlbum> albums) => [
      for (var album in albums)
        if (!album.isAll || album.isCamera) album
    ];

/// The albums a sync watches while the user ticked none (issue #117).
///
/// The camera album where the device names one, and nothing at all where it
/// does not: an app must never start uploading an album nobody chose. Where
/// the device names no album whatsoever — an empty library, or a platform
/// that does not enumerate albums — the whole library is scanned as before,
/// see [CameraRollConfig.wholeLibrary].
List<String> defaultSources(List<PhotoAlbum> albums) {
  if (albums.isEmpty) {
    return const [CameraRollConfig.wholeLibrary];
  }
  return [
    for (var album in selectableSources(albums))
      if (album.isCamera) album.id
  ];
}

/// Typed access to the camera-roll configuration of a [SettingsStore].
extension CameraRollStorage on SettingsStore {
  /// The stored configuration, [CameraRollConfig.disabled] if there is none.
  Future<CameraRollConfig> loadCameraRollConfig() async =>
      CameraRollConfig.parse(await loadCameraRoll());

  /// Stores the given configuration.
  Future<void> saveCameraRollConfig(CameraRollConfig config) =>
      saveCameraRoll(config.toJson());
}

/// What the sync is doing right now, see [CameraRollStatus].
enum CameraRollPhase {
  /// The user has not switched the sync on.
  disabled,

  /// There is no photo library on this platform, or access was refused.
  unavailable,

  /// Switched on, nothing to do at the moment.
  idle,

  /// A run is transferring items.
  running,

  /// A run failed; the next attempt is scheduled, see
  /// [CameraRollStatus.nextAttempt].
  waiting,

  /// A run failed and nothing is scheduled — the sync was switched off, or the
  /// reason is not one that retrying can fix.
  failed,
}

/// What the sync is doing, and what the last run did.
@immutable
class CameraRollStatus {
  final CameraRollPhase phase;

  /// The number of items transferred so far in the running run.
  final int done;

  /// The number of items the running run set out to transfer.
  final int total;

  /// What the **server** said about the run, `null` where it said nothing.
  ///
  /// Verbatim, and never translated: the server spells its own refusals, and
  /// the app neither paraphrases nor re-words them, see issue #108.
  final String? message;

  /// The app's own reason, `null` where the app has none to give.
  ///
  /// Data, not words: the section and the tooltip turn it into a sentence in
  /// the language of the device, see [noticeText] and [AppNotice].
  final AppNotice? notice;

  /// When the next attempt is due, while [phase] is
  /// [CameraRollPhase.waiting].
  final DateTime? nextAttempt;

  /// When the last run finished successfully, `null` if none ever did.
  final DateTime? lastSuccess;

  /// What the last successful run uploaded, and what the server already had.
  final int lastStored;
  final int lastPresent;

  /// Where the photos the server already had are, when that is not the inbox
  /// itself (issue #118); empty otherwise.
  final List<String> lastPresentIn;

  /// How far the server's hash index had got when a run deferred (issue #118);
  /// `null` when the run is not waiting for it.
  ///
  /// While this is set, [phase] is [CameraRollPhase.waiting]: the run is
  /// postponed, not failed, and the section says so and offers *Sync anyway*.
  final int? indexingDone;
  final int? indexingTotal;

  /// The items the last successful run skipped because the server does not
  /// take their format (issue #186), by name; empty otherwise.
  ///
  /// A skipped item is not tried again: the watermark moved past it with its
  /// batch, exactly as past a photo the server already had.
  final List<String> lastSkipped;

  /// What the last run has to say about where the photos went (issue #240):
  /// the album they were meant for ended or was followed; `null` otherwise.
  ///
  /// Said by the run that found out, and by that run only: the target is
  /// cleared or updated with it, so the next run has nothing to say.
  final AppNotice? targetNotice;

  /// The album the last successful run sent the photos to, `null` for the
  /// inbox (issue #240).
  final String? lastTarget;

  const CameraRollStatus({
    this.phase = CameraRollPhase.disabled,
    this.done = 0,
    this.total = 0,
    this.message,
    this.notice,
    this.nextAttempt,
    this.lastSuccess,
    this.lastStored = 0,
    this.lastPresent = 0,
    this.lastPresentIn = const [],
    this.indexingDone,
    this.indexingTotal,
    this.lastSkipped = const [],
    this.targetNotice,
    this.lastTarget,
  });

  /// Whether the run is waiting for the server to finish indexing (issue #118).
  bool get indexing => indexingDone != null && indexingTotal != null;

  /// Whether a run is transferring items right now.
  bool get running => phase == CameraRollPhase.running;

  /// The share of the running run that is done, `null` while nothing runs.
  double? get progress =>
      phase == CameraRollPhase.running && total > 0 ? done / total : null;

  /// The same status with the given values replaced.
  ///
  /// The nullable fields are replaced wholesale (a `null` clears them), which
  /// is what every transition here wants; the counts of the last run are
  /// carried over unless they are given.
  CameraRollStatus copyWith({
    CameraRollPhase? phase,
    int? done,
    int? total,
    String? message,
    AppNotice? notice,
    DateTime? nextAttempt,
    DateTime? lastSuccess,
    int? lastStored,
    int? lastPresent,
    List<String>? lastPresentIn,
    int? indexingDone,
    int? indexingTotal,
    List<String>? lastSkipped,
    AppNotice? targetNotice,
  }) =>
      CameraRollStatus(
        phase: phase ?? this.phase,
        done: done ?? this.done,
        total: total ?? this.total,
        message: message,
        notice: notice,
        nextAttempt: nextAttempt,
        lastSuccess: lastSuccess ?? this.lastSuccess,
        lastStored: lastStored ?? this.lastStored,
        lastPresent: lastPresent ?? this.lastPresent,
        lastPresentIn: lastPresentIn ?? this.lastPresentIn,
        // Like [message]: what a run is waiting for belongs to that run, so the
        // next transition clears it unless it says otherwise.
        indexingDone: indexingDone,
        indexingTotal: indexingTotal,
        lastSkipped: lastSkipped ?? this.lastSkipped,
        // Like [message]: what the last run said belongs to that run.
        targetNotice: targetNotice,
        lastTarget: lastTarget,
      );
}

/// Builds a timer, so that a test does not have to wait for one.
typedef TimerFactory = Timer Function(Duration delay, void Function() callback);

/// The number of bounded scans one run may do before it leaves the rest to the
/// next trigger; a guard against a library that keeps answering with items the
/// server never accepts.
const int maxRounds = 50;

/// The greatest number of hashes one check asks the server about (issue
/// #299).
///
/// The server sets no bound of its own (`ImageServlet.checkUploads` reads the
/// whole body); 500 hashes are a request of some 40 KB, and as many as one
/// platform scan answers, see `photoScanLimit`.
const int checkBatchHashes = 500;

/// The greatest number of photos a chunk hashes before the server is asked
/// about it (issue #299): a phone that hashes a camera roll for the first
/// time starts uploading after fifty photos, not after five hundred.
const int hashedPerCheck = 50;

/// How many upload batches a run has in flight at once (issue #299).
///
/// Two: a second request fills the time the first one waits for the server's
/// answer, and more would only share the same uplink.
const int defaultParallelUploads = 2;

/// What one run did and where its time went (issue #299), see
/// [CameraRollSync.lastRun] — one entry of the diagnostics log per run that
/// had anything to look at.
class SyncRunStats {
  /// The photos the scans answered that were not handled before.
  int seen = 0;

  /// The hashes taken from the [HashCache], and the ones computed by reading.
  int cached = 0;
  int hashed = 0;

  /// The bytes read to compute [hashed].
  int hashedBytes = 0;

  /// The check requests, and the hashes they asked about.
  int checks = 0;
  int checkedHashes = 0;

  /// The upload batches the server accepted, their photos and bytes.
  int uploads = 0;
  int uploadedPhotos = 0;
  int uploadedBytes = 0;

  Duration scanTime = Duration.zero;
  Duration hashTime = Duration.zero;
  Duration checkTime = Duration.zero;
  Duration uploadTime = Duration.zero;
  Duration totalTime = Duration.zero;

  static String _seconds(Duration time) =>
      "${(time.inMilliseconds / 1000).toStringAsFixed(1)} s";

  /// The headline of the diagnostics entry.
  String get headline => "Camera-roll sync looked at $seen photos "
      "in ${_seconds(totalTime)}";

  /// The facts of the diagnostics entry; [outcome] is how the run ended.
  List<String> facts(String outcome) => [
        "Hashes: $cached remembered, $hashed computed "
            "($hashedBytes bytes read)",
        "Checks: $checks ($checkedHashes hashes)",
        "Uploads: $uploads batches, $uploadedPhotos photos "
            "($uploadedBytes bytes)",
        "Time: scan ${_seconds(scanTime)}, hash ${_seconds(hashTime)}, "
            "check ${_seconds(checkTime)}, upload ${_seconds(uploadTime)}",
        "Outcome: $outcome",
      ];
}

/// What a run collected over all its albums, see [CameraRollSync._transfer].
class _Tally {
  int stored = 0;
  int present = 0;
  final List<String> presentIn = [];
  final List<String> skipped = [];
  int transferred = 0;

  /// An item that lies in two watched albums is uploaded once; the mark of
  /// both albums still moves past it.
  final Set<String> uploaded = {};

  void add(UploadSummary summary) {
    stored += summary.stored;
    present += summary.present;
    for (var refused in summary.refused) {
      if (!skipped.contains(refused.name)) {
        skipped.add(refused.name);
      }
    }
    for (var where in summary.presentIn) {
      if (!presentIn.contains(where)) {
        presentIn.add(where);
      }
    }
  }
}

/// A stretch of a chunk the watermark moves over at once: one upload batch
/// ([send], possibly empty) and every photo of the stretch ([items]).
class _Segment {
  final List<PhotoItem> items = [];
  final List<PhotoItem> send = [];
}

/// How the upload of a [_Segment] ended.
class _Outcome {
  /// Whether it was started at all; one queued behind a failure is not.
  final bool started;
  final UploadSummary? summary;
  final Object? error;

  const _Outcome({this.started = true, this.summary, this.error});
}

/// At most [size] tasks at a time; the others wait their turn, in order.
class _Pool {
  final int size;
  int _running = 0;
  final List<Completer<void>> _waiting = [];

  _Pool(int size) : size = size < 1 ? 1 : size;

  Future<T> run<T>(Future<T> Function() task) async {
    if (_running < size) {
      _running++;
    } else {
      var turn = Completer<void>();
      _waiting.add(turn);
      // The finishing task hands its place over, see below.
      await turn.future;
    }
    try {
      return await task();
    } finally {
      if (_waiting.isNotEmpty) {
        _waiting.removeAt(0).complete();
      } else {
        _running--;
      }
    }
  }
}

/// The delays a failed run is retried with: 30 s, doubling, capped at 30 min.
const List<Duration> retryDelays = [
  Duration(seconds: 30),
  Duration(minutes: 1),
  Duration(minutes: 2),
  Duration(minutes: 4),
  Duration(minutes: 8),
  Duration(minutes: 16),
  Duration(minutes: 30),
];

/// The engine watching the device's photo library and filling the inbox album.
///
/// Every moving part is injected: the [library] the photos come from, the
/// [clientOf] the upload goes through, the [clock] and the [timerFactory] —
/// so a test drives a whole day of back-off in a millisecond.
class CameraRollSync extends ChangeNotifier {
  /// Where the configuration is persisted.
  final SettingsStore store;

  /// The device's photo library.
  final PhotoLibrary library;

  /// The client to upload with, `null` while no server is configured.
  ///
  /// A function, not a value: the app builds a new client whenever the server
  /// URL or the device token changes, and the sync must use the current one.
  final VAlbumClient? Function() clientOf;

  /// Who the app talks to the server as, `null` while nobody said (issue #54).
  ///
  /// Asked, not stored: the role of a device can change between two runs — a
  /// member is demoted to a guest, a guest is promoted — and a run that syncs
  /// because the *configuration* was written when the device was a member
  /// would upload into a space that is no longer there. A caller nobody named
  /// (an anonymous device, a server that did not answer) leaves the run
  /// exactly as it was before this existed, see [CallerInfo].
  ///
  /// A future, because the answer is not always at hand: the app has it from
  /// its own `?type=auth` question, a background isolate has to ask, see
  /// [runBackgroundSync].
  final Future<CallerInfo?> Function() callerOf;

  /// Whether the app currently cannot reach the server.
  ///
  /// A sync while the server is away would only produce failures; it is
  /// refused with that reason instead, see the "refusals speak" rule.
  final bool Function() isOffline;

  /// The network the device is on (issue #36).
  ///
  /// Asked before every run while [CameraRollConfig.wifiOnly] is on; a run on
  /// a metered network is refused with that reason instead of quietly costing
  /// the user money, see the "refusals speak" rule.
  final ConnectivitySource connectivity;

  /// The number of items handed to the server in one request.
  final int batchSize;

  /// How many upload requests a run has in flight at once, see
  /// [defaultParallelUploads].
  final int parallelUploads;

  /// The content hashes of the photos, remembered across runs (issue #299).
  final HashCache hashCache;

  /// Hashes a file by its path off the UI isolate, see [PhotoItem.filePath].
  final Future<({String hash, int bytes})> Function(String path) hashFile;

  /// How often the library is scanned while the app runs.
  final Duration interval;

  /// The platform's periodic background execution (issue #32).
  ///
  /// Switching the sync on registers the task, switching it off removes it,
  /// and every app start with a switched-on configuration registers it again
  /// — an app that was updated must not lose it. A platform that has none
  /// says so, see [BackgroundScheduler.available].
  final BackgroundScheduler scheduler;

  /// The current time, injected so that tests are instant.
  final DateTime Function() clock;

  /// Builds the delay timers, injected for the same reason.
  final TimerFactory timerFactory;

  /// Where the photos go besides the inbox (issue #240): the upload target of
  /// this device, read from the same [store] before every run.
  final UploadTargets targets;

  CameraRollConfig _config = CameraRollConfig.disabled;
  CameraRollStatus _status = const CameraRollStatus();
  bool _loaded = false;
  BackgroundRunRecord? _lastBackgroundRun;
  SyncRunStats _stats = SyncRunStats();
  SyncRunStats? _lastRun;
  AppNotice? _backgroundProblem;

  /// Whether a failed run may arm a retry timer.
  ///
  /// A background run must not: the isolate it runs in is torn down when it
  /// returns, so a timer there would either die unfired or hold the isolate
  /// open. The platform runs the task again anyway, see [runOnce].
  bool _armRetries = true;

  bool _running = false;
  bool _pending = false;
  bool _stopRequested = false;
  int _attempt = 0;

  Timer? _retryTimer;
  Timer? _scanTimer;
  StreamSubscription<void>? _watch;
  StreamSubscription<NetworkKind>? _network;
  bool _disposed = false;

  CameraRollSync({
    required this.store,
    required this.library,
    required this.clientOf,
    Future<CallerInfo?> Function()? callerOf,
    bool Function()? isOffline,
    BackgroundScheduler? scheduler,
    ConnectivitySource? connectivity,
    this.batchSize = 10,
    this.parallelUploads = defaultParallelUploads,
    HashCache? hashCache,
    Future<({String hash, int bytes})> Function(String path)? hashFile,
    this.interval = const Duration(minutes: 15),
    DateTime Function()? clock,
    TimerFactory? timerFactory,
    UploadTargets? targets,
  })  : hashCache = hashCache ?? MemoryHashCache(),
        hashFile = hashFile ?? sha256OfFile,
        targets =
            targets ?? UploadTargets(store: store, clock: clock ?? DateTime.now),
        callerOf = callerOf ?? _nobody,
        isOffline = isOffline ?? _never,
        scheduler = scheduler ?? const UnavailableBackgroundScheduler(),
        connectivity = connectivity ?? const UnknownConnectivity(),
        clock = clock ?? DateTime.now,
        timerFactory = timerFactory ?? _realTimer;

  static bool _never() => false;

  static Future<CallerInfo?> _nobody() async => null;

  static Timer _realTimer(Duration delay, void Function() callback) =>
      Timer(delay, callback);

  /// What the sync is configured with, see [CameraRollConfig].
  CameraRollConfig get config => _config;

  /// What the sync is doing, see [CameraRollStatus].
  CameraRollStatus get status => _status;

  /// What the last run that had anything to look at did, `null` before one
  /// (issue #299); the same numbers its diagnostics entry says.
  SyncRunStats? get lastRun => _lastRun;

  /// The numbers of the run going on, see [lastRun].
  SyncRunStats get stats => _stats;

  /// Whether [load] has finished.
  bool get loaded => _loaded;

  /// Reads the stored configuration; called once when the app starts.
  Future<void> load() async {
    _config = await store.loadCameraRollConfig();
    _lastBackgroundRun = await store.loadBackgroundRunRecord();
    _loaded = true;
    _publish(_restingStatus());
  }

  /// What the last background run did, `null` if none ever ran (issue #32).
  ///
  /// Read once by [load]; the run itself happens in another isolate while this
  /// app is closed, so there is nothing to listen to — the next start reads
  /// what it left behind.
  BackgroundRunRecord? get lastBackgroundRun => _lastBackgroundRun;

  /// Why the background task could not be registered or removed, `null` while
  /// the platform did what it was asked.
  ///
  /// A plugin that throws is not swallowed: the settings section shows this.
  AppNotice? get backgroundProblem => _backgroundProblem;

  /// Arms the triggers: the library's change stream and the periodic scan.
  ///
  /// Does nothing while the sync is switched off — an app that is not syncing
  /// must not hold a timer, let alone a subscription to the photo library.
  void start() {
    if (_disposed) {
      return;
    }
    if (!_config.enabled) {
      _disarm();
      return;
    }
    _watch ??= library.changes.listen((_) => trigger());
    // A run refused for the lack of Wi-Fi must start again when Wi-Fi is
    // back, without the user waiting for the next periodic scan (issue #36).
    _network ??= connectivity.changes.listen((kind) {
      if (kind.unmetered) {
        trigger();
      }
    });
    _armScan();
    // Idempotent, so every start may ask: an app that was updated registers
    // the periodic task again this way (issue #32).
    unawaited(_arrangeBackground(true));
    trigger();
  }

  void _armScan() {
    _scanTimer?.cancel();
    _scanTimer = timerFactory(interval, () {
      _scanTimer = null;
      if (_config.enabled) {
        _armScan();
        trigger();
      }
    });
  }

  void _disarm() {
    _watch?.cancel();
    _watch = null;
    _network?.cancel();
    _network = null;
    _scanTimer?.cancel();
    _scanTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;
  }

  /// Asks for a run, unless one is already going on.
  ///
  /// A trigger arriving during a run is remembered: the change that caused it
  /// is picked up by one more run when the current one is through, and never
  /// by a second run alongside it.
  void trigger() {
    if (_running) {
      _pending = true;
      return;
    }
    unawaited(_run());
  }

  /// Runs one sync now, forgetting the back-off of previous failures.
  ///
  /// This is the "Sync now" of the settings screen: the user asked, so the
  /// next attempt is not in eight minutes but immediately.
  Future<void> syncNow() async {
    _attempt = 0;
    _retryTimer?.cancel();
    _retryTimer = null;
    _stopRequested = false;
    if (_running) {
      _pending = true;
      return;
    }
    await _run();
  }

  /// Runs exactly one sync and answers what it did, arming nothing.
  ///
  /// This is the entry a background run uses (issue #32): no change stream, no
  /// periodic scan, and no retry timer — the isolate ends when this future
  /// completes, and the platform schedules the next run itself. The state
  /// machine, the watermark and every refusal are the ones of a foreground
  /// run, because it is the same run.
  Future<CameraRollStatus> runOnce() async {
    if (_disposed || _running) {
      return _status;
    }
    if (!_config.enabled) {
      _publish(_restingStatus());
      return _status;
    }
    _running = true;
    _stopRequested = false;
    _armRetries = false;
    try {
      await _runOnce();
    } finally {
      _armRetries = true;
      _running = false;
      _pending = false;
    }
    return _status;
  }

  /// Asks the running sync to stop after the batches it is transferring.
  void stop() {
    _stopRequested = true;
    _pending = false;
  }

  /// Switches the sync on or off.
  ///
  /// Returns the reason it refused, `null` when it did what it was asked; a
  /// switch that flips back on its own would leave the user guessing. The
  /// reason as data, which the section turns into words, see [noticeText].
  ///
  /// Where the photos go is not the sync's to decide: the server names the
  /// one inbox of the space (issue #226), and a run asks it.
  Future<AppNotice?> setEnabled(bool value) async {
    if (value && !await library.requestAccess()) {
      return library.accessProblem ?? const PhotoLibraryUnreadable();
    }
    await _store(_config.copyWith(enabled: value));
    if (value) {
      start();
    } else {
      stop();
      _disarm();
      await _arrangeBackground(false);
      _publish(_restingStatus());
    }
    return null;
  }

  /// Registers or removes the platform's periodic background task.
  ///
  /// Never throws: a plugin that refuses is a reason to show, not a reason to
  /// keep the user from switching the sync on — the foreground sync works
  /// either way, see [backgroundProblem].
  Future<void> _arrangeBackground(bool enabled) async {
    if (!scheduler.available) {
      return;
    }
    try {
      if (enabled) {
        await scheduler.schedule(BackgroundNetwork.of(_config.wifiOnly));
      } else {
        await scheduler.cancel();
      }
      _backgroundProblem = null;
    } catch (error) {
      _backgroundProblem = enabled
          ? BackgroundScheduleFailed(platformErrorText(error))
          : BackgroundUnscheduleFailed(platformErrorText(error));
    }
    if (!_disposed) {
      notifyListeners();
    }
  }

  /// Limits the sync to an unmetered network, or lifts that limit (issue #36).
  ///
  /// Lifting it starts a run right away where one was refused for it: the user
  /// just said that a mobile connection is fine, and waiting a quarter of an
  /// hour for the periodic scan would look like nothing happened.
  ///
  /// The platform's periodic task carries the same limit as a constraint, so
  /// a toggle re-registers it while the sync is on — otherwise the platform
  /// would keep waking the app on a mobile connection only for the run to
  /// refuse (issue #43). A switched-off sync has no task to re-register, and
  /// registering one here would switch the background sync on behind the
  /// user's back.
  Future<void> setWifiOnly(bool value) async {
    if (value == _config.wifiOnly) {
      return;
    }
    await _store(_config.copyWith(wifiOnly: value));
    if (_config.enabled) {
      await _arrangeBackground(true);
    }
    if (!value && _config.enabled) {
      trigger();
    } else {
      _publish(_status);
    }
  }

  /// Chooses the device albums the sync watches (issue #117).
  ///
  /// [albums] is what the device holds, needed to know which albums were
  /// watched **before** this choice: while the user never ticked anything,
  /// that is the default — the camera album — and the camera must not be
  /// re-scanned from the beginning merely because the user ticked a second
  /// album beside it.
  ///
  /// A newly ticked album is scanned **from the beginning**, not from the
  /// moment it was ticked: somebody who ticks "WhatsApp Images" wants the
  /// pictures that are in it, not the ones it will receive from now on. An
  /// album that was ticked before, unticked and ticked again keeps the mark
  /// it had — everything below it was offered once already, and the server
  /// would only answer `present` for it, see [SourceMark].
  Future<void> chooseSources(
    List<String> chosen, {
    List<PhotoAlbum> albums = const [],
  }) async {
    var watched = _config.sourcesOf(albums).toSet();
    var marks = {..._config.marks};
    for (var source in chosen) {
      if (!watched.contains(source) && !marks.containsKey(source)) {
        marks[source] = SourceMark.beginning;
      }
    }
    await _store(_config.copyWith(sources: [...chosen], marks: marks));
    _publish(_restingStatus());
    if (_config.enabled) {
      trigger();
    }
  }

  /// Forgets what was uploaded, so that the next run re-scans the library.
  ///
  /// Nothing is uploaded twice by this: the server is asked for every hash.
  /// What is watched is not forgotten — that is a decision of the user, not
  /// progress.
  Future<void> forgetProgress() async {
    await _store(CameraRollConfig(
      enabled: _config.enabled,
      wifiOnly: _config.wifiOnly,
      sources: _config.sources,
    ));
    _publish(_restingStatus());
  }

  Future<void> _store(CameraRollConfig config) async {
    _config = config;
    await store.saveCameraRollConfig(config);
  }

  /// The status of a sync that is not doing anything at the moment.
  CameraRollStatus _restingStatus() => _status.copyWith(
        phase:
            _config.enabled ? CameraRollPhase.idle : CameraRollPhase.disabled,
        done: 0,
        total: 0,
      );

  void _publish(CameraRollStatus status) {
    _status = status;
    if (!_disposed) {
      notifyListeners();
    }
  }

  /// One run, see the library documentation for the state machine.
  Future<void> _run() async {
    if (_disposed || _running) {
      return;
    }
    if (!_config.enabled) {
      _publish(_restingStatus());
      return;
    }
    _running = true;
    _stopRequested = false;
    try {
      await _runOnce();
    } finally {
      _running = false;
    }
    if (_pending && !_stopRequested && _config.enabled) {
      _pending = false;
      await _run();
    } else {
      _pending = false;
    }
  }

  /// [_runInner], and the timing record of it, see [SyncRunStats].
  Future<void> _runOnce() async {
    _stats = SyncRunStats();
    var watch = Stopwatch()..start();
    try {
      await _runInner();
    } finally {
      _stats.totalTime = watch.elapsed;
      if (_stats.seen > 0) {
        _lastRun = _stats;
        // The one entry of the log that is not a failure (issue #299): a
        // slow sync on a device says where its time went.
        _logOf()?.add(
          _stats.headline,
          _stats.facts(_status.phase.name),
        );
      }
    }
  }

  DiagnosticsLog? _logOf() {
    try {
      return clientOf()?.log;
    } catch (_) {
      return null;
    }
  }

  Future<void> _runInner() async {
    if (isOffline()) {
      _fail(notice: const ServerOffline());
      return;
    }
    if (_config.wifiOnly) {
      var kind = await connectivity.current();
      if (!kind.unmetered) {
        // Retried, not abandoned: the device moves back onto a Wi-Fi sooner
        // or later, and the change stream starts a run right then, see
        // [start].
        _fail(notice: kind.refusal);
        return;
      }
    }
    var client = clientOf();
    if (client == null) {
      _fail(notice: const NoServerConfigured(), retry: false);
      return;
    }
    if (!await library.requestAccess()) {
      _publish(_status.copyWith(
        phase: CameraRollPhase.unavailable,
        notice: library.accessProblem ?? const PhotoLibraryUnreadable(),
      ));
      return;
    }
    // Everything the device can answer on its own is answered; now the one
    // question for the server: a guest has no space of their own, and no
    // retry ever changes that — only the admin does, see [guestNoSpaceNotice]
    // (issue #54). Asked here, so that a run which cannot read a photo anyway
    // asks the server nothing at all.
    CallerInfo? caller;
    try {
      caller = await callerOf();
    } on VAlbumException catch (error) {
      // The server answered and refused to say; its own sentence is the
      // reason, and the run is tried again.
      _fail(message: error.message);
      return;
    } catch (error) {
      // The server could not be asked where the inbox is (issue #226): the
      // transport's reason, retried like any failed transfer.
      _fail(notice: _reasonOf(error));
      return;
    }
    if (caller?.isGuest ?? false) {
      _fail(notice: guestNoSpaceNotice, retry: false);
      return;
    }
    // The one inbox of the space, as the server names it (issue #226): the
    // sync never chooses, guesses or creates one, and the server makes the
    // folder with the first upload into it. A caller it names none for may
    // put nothing there, which no retry changes; a server that did not answer
    // is asked again.
    var inbox = caller?.inboxPath ?? const <String>[];
    if (inbox.isEmpty) {
      _fail(notice: const NoInboxForCaller(), retry: caller == null);
      return;
    }
    // Where the photos go: the inbox, or the album this device was told to
    // send them to for a while (issue #240). Read from the store, never asked
    // of the server — a run with nothing new still asks the server nothing;
    // a target that is gone is found out by the upload's own requests.
    var route = await UploadRoute.open(
      targets: targets,
      client: client,
      inbox: inbox,
    );
    List<String> sources;
    try {
      sources = await _sources();
    } catch (error) {
      _fail(notice: PhotoLibraryFailed(platformErrorText(error)));
      return;
    }
    if (sources.isEmpty) {
      _succeed(0, 0, const [], const [], route);
      return;
    }
    var tally = _Tally();
    // Whether every watched album is scanned from its very beginning in this
    // run — then the ids it saw are every photo the sync watches, and the
    // hash cache may forget the rest, see [HashCache.retainOnly].
    var fullScan = true;
    var seen = <String>{};
    // Every watched album has its own watermark, so a newly ticked one is
    // fetched from the beginning without the others losing their place
    // (issue #117).
    for (var source in sources) {
      if (_config.markOf(source).since != null) {
        fullScan = false;
      }
      // A platform scan is bounded (see [PhotoLibrary.scanLimit]), so a full
      // answer may have left items behind; the watermark has advanced by
      // then, so the next round picks exactly those up. A round that was not
      // full — and every round of a library that answers with everything —
      // ends this album.
      var finished = false;
      for (var round = 0; round < maxRounds; round++) {
        var mark = _config.markOf(source);
        List<PhotoItem> items;
        var scanning = Stopwatch()..start();
        try {
          items = await library.itemsSince(
            mark.since,
            sources:
                source == CameraRollConfig.wholeLibrary ? const [] : [source],
          );
        } catch (error) {
          _fail(notice: PhotoLibraryFailed(platformErrorText(error)));
          return;
        } finally {
          stats.scanTime += scanning.elapsed;
        }
        seen.addAll([for (var item in items) item.id]);

        var alreadyDone = mark.done.toSet();
        var pending = [
          for (var item in items)
            if (!alreadyDone.contains(item.id)) item
        ];
        if (pending.isEmpty) {
          finished = true;
          break;
        }
        stats.seen += pending.length;
        var truncated =
            library.scanLimit > 0 && items.length >= library.scanLimit;

        var total = tally.transferred + pending.length;
        _publish(_status.copyWith(
          phase: CameraRollPhase.running,
          done: tally.transferred,
          total: total,
        ));

        if (!await _transfer(source, pending, client, route, tally, total)) {
          return;
        }
        if (!truncated) {
          finished = true;
          break;
        }
      }
      if (!finished) {
        fullScan = false;
      }
    }
    if (fullScan) {
      await hashCache.retainOnly(seen);
    }
    _succeed(
        tally.stored, tally.present, tally.presentIn, tally.skipped, route);
  }

  /// Transfers [pending], scanned from [source], and moves the album's
  /// watermark over what the server accepted (issue #299).
  ///
  /// [pending] is taken in chunks. A chunk is the photos up to the next
  /// [checkBatchHashes], or up to the [hashedPerCheck]-th photo whose hash is
  /// not remembered: a remembered hash is taken from the [hashCache] without
  /// reading the photo, the others are hashed (off the UI isolate where the
  /// platform names a file) and remembered. The server is asked about the
  /// whole chunk in **one** request, and only what it does not hold is read
  /// again, to be uploaded — in [batchSize] batches, [parallelUploads] of them
  /// at once.
  ///
  /// The watermark moves exactly as it did when the batches went one after
  /// the other: the chunk is cut into segments in order, each segment one
  /// upload batch and the photos the server already holds around it, and the
  /// watermark moves over a segment only once it and every segment before it
  /// were accepted. A batch that fails stops the run: no further batch is
  /// started, the ones in flight are waited for, and the watermark stays
  /// before the failed one — a batch after it that did arrive is offered
  /// again by the next run, which finds its hashes remembered and the server
  /// holding them, and uploads nothing.
  ///
  /// Answers whether the run goes on; where it does not, it said why.
  Future<bool> _transfer(
    String source,
    List<PhotoItem> pending,
    VAlbumClient client,
    UploadRoute route,
    _Tally tally,
    int total,
  ) async {
    var next = 0;
    while (next < pending.length) {
      if (_stopRequested) {
        _publish(_restingStatus());
        return false;
      }
      // The hashes of the chunk; what another watched album already handed
      // over in this run is neither hashed nor handed over a second time —
      // but this album's mark still moves past it, because the server has it.
      var chunk = <PhotoItem>[];
      var hashes = <String, String>{};
      Object? unreadable;
      var computed = 0;
      var hashing = Stopwatch()..start();
      while (next < pending.length &&
          chunk.length < checkBatchHashes &&
          computed < hashedPerCheck) {
        var item = pending[next];
        if (!tally.uploaded.contains(item.id)) {
          var hash = await hashCache.lookup(item);
          if (hash != null) {
            stats.cached++;
          } else {
            try {
              hash = await _hash(item);
            } catch (error) {
              // What comes before it is still transferred; the run then
              // fails with this reason and keeps the watermark before it.
              unreadable = error;
              break;
            }
            computed++;
            stats.hashed++;
            hashCache.put(item, hash);
          }
          hashes[item.id] = hash;
        }
        chunk.add(item);
        next++;
      }
      await hashCache.flush();
      stats.hashTime += hashing.elapsed;
      if (chunk.isEmpty) {
        _failWith(unreadable!);
        return false;
      }

      // One question for the whole chunk. Where the server cannot answer
      // it — an older server, a target album that is gone, a lost
      // connection — every photo is sent through [VAlbumClient.uploadNew]
      // as before this existed, which asks for its own batch and tells each
      // of those apart.
      var fresh = {
        for (var item in chunk)
          if (hashes.containsKey(item.id)) item.id
      };
      Set<String>? known;
      var checkedPath = route.path;
      if (fresh.isNotEmpty) {
        var checking = Stopwatch()..start();
        try {
          var check = await client.checkUploads(checkedPath, [
            for (var item in chunk)
              if (fresh.contains(item.id)) hashes[item.id]!
          ]);
          stats.checks++;
          stats.checkedHashes += fresh.length;
          var indexed = check.indexed;
          if (!_config.syncWhileIndexing &&
              indexed != null &&
              indexed.done < indexed.total) {
            // See [VAlbumClient.uploadNew] and issue #118: the server cannot
            // yet say which of these it holds somewhere else.
            _defer(indexed.done, indexed.total);
            return false;
          }
          known = {for (var present in check.present) present.hash};
          for (var present in check.present) {
            if (present.name.contains("/") &&
                !tally.presentIn.contains(present.name)) {
              tally.presentIn.add(present.name);
            }
          }
        } catch (_) {
          known = null;
        } finally {
          stats.checkTime += checking.elapsed;
        }
      }

      // The segments, in order: each one upload batch, bounded like the
      // explicit upload's (see [UploadBatching] and issue #63), and the
      // photos the server holds that come before the next batch.
      var segments = <_Segment>[];
      var current = _Segment();
      var bytes = 0;
      for (var item in chunk) {
        var send = fresh.contains(item.id) &&
            (known == null || !known.contains(hashes[item.id]));
        if (send) {
          if (current.items.isNotEmpty &&
              (current.send.isEmpty ||
                  current.send.length >= batchSize ||
                  bytes + item.length > uploadBatchBytes)) {
            segments.add(current);
            current = _Segment();
            bytes = 0;
          }
          current.send.add(item);
          bytes += item.length;
        }
        current.items.add(item);
      }
      segments.add(current);

      Future<UploadSummary> sendItems(List<PhotoItem> items) =>
          route.send((path) => client.uploadNew(
                path,
                [
                  for (var item in items)
                    item.upload.withHash(hashes[item.id]!),
                ],
                // While the server is still reading the library it cannot
                // say that a photo is already in some album, and a first sync
                // over an existing library would upload what is there. The
                // run waits -- unless the user said otherwise, see issue
                // #118.
                waitForIndex: !_config.syncWhileIndexing,
                // Asked already, for this very path; a send that falls back
                // to the inbox asks the inbox, see [UploadRoute.send].
                checked: known != null && listEquals(path, checkedPath),
              ));

      var uploading = Stopwatch()..start();
      var halt = false;
      var pool = _Pool(parallelUploads);
      var outcomes = [
        for (var segment in segments)
          segment.send.isEmpty
              ? Future.value(const _Outcome())
              : pool.run(() async {
                  if (halt || _stopRequested) {
                    return const _Outcome(started: false);
                  }
                  try {
                    var summary = await _sendSkippingUnsupported(
                        segment.send, sendItems);
                    if (summary.deferred) {
                      halt = true;
                    } else {
                      stats.uploads++;
                      stats.uploadedPhotos += segment.send.length;
                      stats.uploadedBytes += [
                        for (var item in segment.send) item.length
                      ].fold(0, (a, b) => a + b);
                    }
                    return _Outcome(summary: summary);
                  } catch (error) {
                    halt = true;
                    return _Outcome(error: error);
                  }
                }),
      ];

      _Outcome? stopped;
      for (var index = 0; index < segments.length; index++) {
        var segment = segments[index];
        var outcome = await outcomes[index];
        var summary = outcome.summary;
        if (!outcome.started ||
            outcome.error != null ||
            (summary != null && summary.deferred)) {
          stopped = outcome;
          break;
        }
        var sent = {for (var item in segment.send) item.id};
        tally.present += [
          for (var item in segment.items)
            if (fresh.contains(item.id) && !sent.contains(item.id)) item
        ].length;
        if (summary != null) {
          tally.add(summary);
        }
        tally.uploaded.addAll([
          for (var item in segment.items)
            if (fresh.contains(item.id)) item.id
        ]);
        tally.transferred += segment.items.length;
        // Only now: what was accepted is what the watermark may cover.
        await _advance(source, segment.items);
        _publish(_status.copyWith(
          phase: CameraRollPhase.running,
          done: tally.transferred,
          total: total,
        ));
      }
      if (stopped != null) {
        // Nothing of this run is still on its way when it ends.
        await Future.wait(outcomes);
        stats.uploadTime += uploading.elapsed;
        var error = stopped.error;
        var summary = stopped.summary;
        if (error != null) {
          _failWith(error);
        } else if (summary != null && summary.deferred) {
          var indexed = summary.indexed!;
          _defer(indexed.done, indexed.total);
        } else {
          _publish(_restingStatus());
        }
        return false;
      }
      stats.uploadTime += uploading.elapsed;
      if (unreadable != null) {
        _failWith(unreadable);
        return false;
      }
    }
    return true;
  }

  /// The SHA-256 of [item]'s contents: from its file in an isolate of its
  /// own where the platform names one, see [PhotoItem.filePath], else read
  /// through [PhotoItem.openRead].
  Future<String> _hash(PhotoItem item) async {
    var file = item.filePath;
    var path = file == null ? null : await file();
    if (path != null) {
      try {
        var result = await hashFile(path);
        stats.hashedBytes += result.bytes;
        return result.hash;
      } on PhotoLibraryException {
        rethrow;
      } catch (error) {
        // The file could not be read: the device's library failed, not the
        // server, see [_reasonOf].
        throw PhotoLibraryException(
            PhotoLibraryFailed(platformErrorText(error)));
      }
    }
    var read = 0;
    var hash = await sha256Of(item.openRead().map((chunk) {
      read += chunk.length;
      return chunk;
    }));
    stats.hashedBytes += read;
    return hash;
  }

  /// Ends the run with the reason [error] gives, see [_fail].
  void _failWith(Object error) {
    if (error is VAlbumException) {
      _fail(message: error.message);
    } else {
      _fail(notice: _reasonOf(error));
    }
  }

  /// Sends [items] by [send], and where the server refuses the batch as
  /// unsupported media (415) sends them one by one, skipping exactly the items
  /// it refuses for their own format (issue #186).
  ///
  /// A current server stores the rest of a batch and names a refused file in
  /// [UploadSummary.refused]; only a batch holding nothing it takes is a 415,
  /// and a server built before #186 answers 415 for the whole batch. Either
  /// way, an item is skipped only where the refusal is about *it* — the
  /// server's sentence names it, or the server said nothing and its extension
  /// is none this app knows a server to take — so a 415 that is about
  /// anything else still fails the run and keeps the watermark: no supported
  /// photo is ever passed over.
  Future<UploadSummary> _sendSkippingUnsupported(
    List<PhotoItem> items,
    Future<UploadSummary> Function(List<PhotoItem>) send,
  ) async {
    try {
      return await send(items);
    } on VAlbumException catch (error) {
      if (error.status != 415) {
        rethrow;
      }
      if (items.length == 1) {
        if (!_refusesItself(error, items.single)) {
          rethrow;
        }
        return UploadSummary(
          stored: 0,
          present: 0,
          refused: [
            RefusedFile(name: items.single.name, reason: error.message),
          ],
        );
      }
      var stored = 0;
      var present = 0;
      var presentIn = <String>[];
      var refused = <RefusedFile>[];
      var storedHashes = <String>[];
      IndexProgress? indexed;
      for (var item in items) {
        var summary = await _sendSkippingUnsupported([item], send);
        if (summary.deferred) {
          return summary;
        }
        stored += summary.stored;
        present += summary.present;
        presentIn
            .addAll(summary.presentIn.where((p) => !presentIn.contains(p)));
        refused.addAll(summary.refused);
        storedHashes.addAll(summary.storedHashes);
        indexed = summary.indexed ?? indexed;
      }
      return UploadSummary(
        stored: stored,
        present: present,
        presentIn: presentIn,
        indexed: indexed,
        refused: refused,
        storedHashes: storedHashes,
      );
    }
  }

  /// Whether the 415 [error] refuses [item] for what it is, see
  /// [_sendSkippingUnsupported].
  ///
  /// A current server names the file it refuses (issue #186); a 415 without
  /// a reason comes from a server built before that, which takes exactly
  /// [olderServerExtensions] — so a bare 415 refuses an item of any other
  /// extension for what it is: a HEIC, and an iPhone's `.MOV`, an `.m4v` or a
  /// `.3gp` (issue #189), which such a server never took. The sync itself
  /// offers every photograph and video the device library holds, whatever
  /// its extension; the server decides.
  static bool _refusesItself(VAlbumException error, PhotoItem item) {
    var reason = error.reason;
    if (reason != null) {
      return reason.contains(item.name);
    }
    return !olderServerExtensions.contains(extensionOf(item.name));
  }

  /// The device albums this run scans, see [CameraRollConfig.sources].
  ///
  /// The user's choice where they made one; else the default, which needs the
  /// device's albums — asked here, after the library granted access, and
  /// never stored: an album that appears on the device later is picked up by
  /// the next run, and a tick the user never made is not written behind their
  /// back.
  Future<List<String>> _sources() async {
    var chosen = _config.sources;
    if (chosen != null) {
      return chosen;
    }
    return defaultSources(await library.albums());
  }

  /// Records that [batch], scanned from [source], was accepted by the server.
  ///
  /// The album's watermark becomes the newest taken-at stamp handled so far;
  /// the ids kept beside it are exactly those at that stamp — the older ones
  /// can never be offered again, because the next scan of this album starts
  /// at its watermark.
  Future<void> _advance(String source, List<PhotoItem> batch) async {
    var previous = _config.markOf(source);
    var stamp = previous.since;
    for (var item in batch) {
      if (stamp == null || item.takenAt.isAfter(stamp)) {
        stamp = item.takenAt;
      }
    }
    var kept = <String>{
      // The previously recorded ids belong to the previous watermark; they
      // survive only while that watermark is still the current one.
      if (stamp == previous.since) ...previous.done,
      for (var item in batch)
        if (item.takenAt == stamp) item.id,
    };
    await _store(_config.withMark(
      source,
      SourceMark(since: stamp, done: kept.toList()),
    ));
  }

  void _succeed(
    int stored,
    int present,
    List<String> presentIn,
    List<String> skipped,
    UploadRoute route,
  ) {
    _attempt = 0;
    _retryTimer?.cancel();
    _retryTimer = null;
    _publish(CameraRollStatus(
      phase: CameraRollPhase.idle,
      lastSuccess: clock(),
      lastStored: stored,
      lastPresent: present,
      lastPresentIn: presentIn,
      lastSkipped: skipped,
      targetNotice: route.notice,
      lastTarget: route.target?.name,
    ));
  }

  /// Postpones the run until the server has finished indexing (issue #118).
  ///
  /// Not a failure: nothing went wrong, the server is simply not ready to
  /// answer the one question this sync rests on. It is retried like a failure
  /// — the index finishes on its own — and the attempt counter is left alone,
  /// so a real failure afterwards still starts its backoff at the beginning.
  void _defer(int done, int total) {
    var delay = nextDelay;
    _retryTimer?.cancel();
    _retryTimer = timerFactory(delay, () {
      _retryTimer = null;
      trigger();
    });
    _publish(_status.copyWith(
      phase: CameraRollPhase.waiting,
      nextAttempt: clock().add(delay),
      done: 0,
      total: 0,
      indexingDone: done,
      indexingTotal: total,
    ));
  }

  /// Lets the runs of this device go ahead while the server indexes, or not,
  /// see [CameraRollConfig.syncWhileIndexing] and issue #118.
  ///
  /// Turning it on starts a run at once: it is the answer to a section that is
  /// saying the sync is waiting.
  Future<void> setSyncWhileIndexing(bool value) async {
    if (_config.syncWhileIndexing == value) {
      return;
    }
    await _store(_config.copyWith(syncWhileIndexing: value));
    if (value && _config.enabled) {
      trigger();
    }
  }

  /// Ends the run with a reason, and schedules the next attempt.
  ///
  /// Exactly one of the two is given: [message] is what the **server** said,
  /// verbatim, and [notice] is the app's own reason as data, see issue #108.
  void _fail({String? message, AppNotice? notice, bool retry = true}) {
    if (!retry || !_armRetries) {
      _publish(_status.copyWith(
        phase: CameraRollPhase.failed,
        message: message,
        notice: notice,
        done: 0,
        total: 0,
      ));
      return;
    }
    var delay = retryDelays[
        _attempt < retryDelays.length ? _attempt : retryDelays.length - 1];
    _attempt++;
    _retryTimer?.cancel();
    _retryTimer = timerFactory(delay, () {
      _retryTimer = null;
      trigger();
    });
    _publish(_status.copyWith(
      phase: CameraRollPhase.waiting,
      message: message,
      notice: notice,
      nextAttempt: clock().add(delay),
      done: 0,
      total: 0,
    ));
  }

  /// Why a run could not finish, as data (issue #108).
  ///
  /// The library's own reason where the device's library refused
  /// ([PhotoLibraryException]), the transport's where the server could not be
  /// reached, and the exception's own text otherwise — which is a last resort,
  /// not a sentence this app composed.
  static AppNotice _reasonOf(Object error) {
    if (error is PhotoLibraryException) {
      return error.notice;
    }
    if (error is PlatformException) {
      // The device's own platform refused, not the server; and its message,
      // never its Java stack (issue #169).
      return PhotoLibraryFailed(platformErrorText(error));
    }
    return ServerUnreachable(
      VAlbumClient.isTransportFailure(error)
          ? VAlbumClient.transportMessage(error)
          : error.toString(),
    );
  }

  /// The delay the next failure will be retried after, for the tests and the
  /// status line.
  Duration get nextDelay => retryDelays[
      _attempt < retryDelays.length ? _attempt : retryDelays.length - 1];

  @override
  void dispose() {
    _disposed = true;
    _disarm();
    super.dispose();
  }
}
