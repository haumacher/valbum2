/// One place for everything that runs, waits or fails (issue #305): the
/// indicator in the app bar and the status page it opens.
///
/// The author: "Wie schaffen wir es denn alles relevante an einer zentralen
/// Stelle anzuzeigen. Das ist doch Mist, wenn man nie weiß wo man hinschauen
/// soll." So the page gathers, by urgency:
///
///  1. **Problems**, each with its reason and a button to where it is fixed:
///     this device's upload that failed, the entries of this device's log not
///     seen yet, a server that cannot be asked — and, for an administrator,
///     the server's own ([StatusInfo.problems]): every outstanding failure of
///     the background merged by cause, every folder the server cannot write,
///     the embedder that does not answer, what this machine cannot do. A
///     problem marked as seen stays, collapsed under "Known problems", and
///     lights nothing; the server keeps the marks of its problems (shared by
///     its administrators), this device the marks of its own ([DeviceSeen]).
///  2. **What runs**: this device's upload, and for an administrator the
///     server's background, step by step.
///  3. **This device's log** ([DiagnosticsPanel]).
///
/// The indicator ([StatusIndicator]) is quiet while all is well, a ring while
/// something runs, a red dot while a problem has not been seen. It asks the
/// server ([StatusMonitor]) every [statusPollInterval] while the app is in
/// the foreground, and every [statusPagePollInterval] while the page is open.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'action_guard.dart';
import 'app.dart';
import 'camera_roll.dart';
import 'camera_roll_view.dart';
import 'catch_up.dart';
import 'client.dart';
import 'diagnostics.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show refusalMessage;
import 'page_insets.dart';
import 'platform.dart';
import 'resource.dart';
import 'routes.dart';
import 'selectable.dart';
import 'notices.dart';
import 'settings.dart';

/// How often the indicator asks the server while the app is in the foreground.
const Duration statusPollInterval = Duration(seconds: 30);

/// How often the status page asks the server while it is open.
const Duration statusPagePollInterval = Duration(seconds: 5);

/// The key of the indicator in an app bar.
const Key statusIndicatorKey = Key("status-indicator");

/// The key of the ring of the indicator while something runs.
const Key statusRingKey = Key("status-ring");

/// The key of the red dot of the indicator while a problem was not seen.
const Key statusDotKey = Key("status-dot");

/// The key of the section of the problems on the page.
const Key statusProblemsKey = Key("status-problems");

/// The key of "Known problems" on the page.
const Key statusKnownKey = Key("status-known");

/// The key of the section of what runs on the page.
const Key statusRunningKey = Key("status-running");

/// The key of the section of this device's log on the page.
const Key statusLogKey = Key("status-log");

/// The key of "Mark all as seen".
const Key statusMarkAllKey = Key("status-mark-all");

/// The key of the problem of [id] on the page.
Key statusProblemKey(String id) => ValueKey("status-problem-$id");

/// The key of the "Seen" button of the problem of [id].
Key statusSeenKey(String id) => ValueKey("status-seen-$id");

/// The key of the "Retry now" button of the problem of [id].
Key statusRetryKey(String id) => ValueKey("status-retry-$id");

/// The key of the button leading to where the problem of [id] is fixed.
Key statusFixKey(String id) => ValueKey("status-fix-$id");

/// The key of "Copy all paths" of the folders the server cannot write.
const Key statusCopyPathsKey = Key("status-copy-paths");

/// The id of this device's upload that failed.
const String deviceSyncId = "device.sync";

/// The id of the entries of this device's log not seen yet.
const String deviceLogId = "device.log";

/// The id of a server that cannot be asked.
const String deviceServerId = "device.server";

/// What this device marked as seen of its own problems, see the library
/// comment: the reason each had then, by id, and up to when the log was
/// seen.
@immutable
class DeviceSeen {
  /// The reason of each problem when it was marked, by its id.
  final Map<String, String> problems;

  /// The moment up to which the entries of the log were seen, `null` for
  /// never.
  final DateTime? logSeenAt;

  const DeviceSeen({this.problems = const {}, this.logSeenAt});

  /// Reads what [toJson] wrote; anything unreadable reads as nothing seen.
  static DeviceSeen fromJson(String? json) {
    if (json == null || json.isEmpty) {
      return const DeviceSeen();
    }
    try {
      var map = jsonDecode(json) as Map<String, Object?>;
      var problems = <String, String>{};
      var stored = map["problems"];
      if (stored is Map) {
        stored.forEach((key, value) {
          if (key is String && value is String) {
            problems[key] = value;
          }
        });
      }
      var log = map["logSeenAt"];
      return DeviceSeen(
        problems: problems,
        logSeenAt: log is int ? DateTime.fromMillisecondsSinceEpoch(log) : null,
      );
    } on Object {
      return const DeviceSeen();
    }
  }

  String toJson() => jsonEncode({
        "problems": problems,
        if (logSeenAt != null) "logSeenAt": logSeenAt!.millisecondsSinceEpoch,
      });

  /// Whether the problem of [id] was seen with [reason].
  bool hasSeen(String id, String reason) => problems[id] == reason;

  /// Whether the given entry of the log was seen.
  bool hasSeenEntry(DiagnosticsEntry entry) =>
      logSeenAt != null && !entry.lastAt.isAfter(logSeenAt!);
}

/// What the server says of itself, for the indicator and the status page:
/// attached to the client of a member ([attach]), asking every [interval]
/// and on resume, faster while the page [watch]es it.
class StatusMonitor extends ChangeNotifier {
  /// How often the server is asked while nobody watches.
  final Duration interval;

  /// How often the server is asked while the page watches.
  final Duration pageInterval;

  /// Where this device's seen marks are kept, `null` for in memory only.
  final SettingsStore? store;

  StatusMonitor({
    this.interval = statusPollInterval,
    this.pageInterval = statusPagePollInterval,
    this.store,
  }) {
    _loadSeen();
  }

  VAlbumClient? _client;

  Timer? _timer;

  AppLifecycleListener? _lifecycle;

  bool _disposed = false;

  bool _asking = false;

  int _watchers = 0;

  /// What the server answered last, `null` before its first answer.
  StatusInfo? server;

  /// Why the server could not be asked last, `null` while it answers.
  String? problem;

  /// What this device marked as seen of its own problems.
  DeviceSeen seen = const DeviceSeen();

  /// The client asked, `null` where nobody is asked.
  VAlbumClient? get client => _client;

  /// Whether the server is asked: the app is signed in as a member.
  bool get active => _client != null;

  Future<void> _loadSeen() async {
    var stored = await store?.loadStatusSeen();
    if (_disposed || stored == null) {
      return;
    }
    seen = DeviceSeen.fromJson(stored);
    notifyListeners();
  }

  /// Starts asking [client], or stops asking where it is `null`.
  void attach(VAlbumClient? client) {
    if (identical(client, _client)) {
      return;
    }
    _client = client;
    server = null;
    problem = null;
    _schedule();
    if (client != null) {
      _lifecycle ??= AppLifecycleListener(
        onResume: () {
          _schedule();
          poll();
        },
        onPause: () {
          // Nothing is asked while the app is not in the foreground.
          _timer?.cancel();
          _timer = null;
        },
      );
      poll();
    }
    notifyListeners();
  }

  void _schedule() {
    _timer?.cancel();
    _timer = null;
    if (_client != null) {
      _timer = Timer.periodic(
          _watchers > 0 ? pageInterval : interval, (_) => poll());
    }
  }

  /// The page is open: asks at once, and every [pageInterval] until the
  /// returned function is called.
  VoidCallback watch() {
    _watchers++;
    _schedule();
    poll();
    var done = false;
    return () {
      if (done || _disposed) {
        return;
      }
      done = true;
      _watchers--;
      _schedule();
    };
  }

  /// Asks the server once.
  Future<void> poll() async {
    var client = _client;
    if (client == null || _asking) {
      return;
    }
    _asking = true;
    try {
      var answer = await client.status();
      if (_disposed || !identical(client, _client)) {
        return;
      }
      server = answer;
      problem = null;
      notifyListeners();
    } catch (error) {
      if (!_disposed && identical(client, _client)) {
        // The last answer stands; the problem says why it is not fresh.
        problem = refusalMessage(error);
        notifyListeners();
      }
    } finally {
      _asking = false;
    }
  }

  /// Takes what the server answered to a write of the page.
  void take(StatusInfo answer) {
    if (_disposed) {
      return;
    }
    server = answer;
    problem = null;
    notifyListeners();
  }

  /// Marks this device's problems of [reasons] (by id) as seen, and the log
  /// as seen up to [logUpTo] where given.
  Future<void> markDeviceSeen(Map<String, String> reasons,
      {DateTime? logUpTo}) async {
    seen = DeviceSeen(
      problems: {...seen.problems, ...reasons},
      logSeenAt: logUpTo ?? seen.logSeenAt,
    );
    notifyListeners();
    await store?.saveStatusSeen(seen.toJson());
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _lifecycle?.dispose();
    super.dispose();
  }
}

/// Publishes the [StatusMonitor] of the app.
class StatusScope extends InheritedNotifier<StatusMonitor> {
  const StatusScope(
      {super.key, required StatusMonitor monitor, required super.child})
      : super(notifier: monitor);

  /// The monitor of the enclosing app, `null` where there is none.
  static StatusMonitor? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<StatusScope>()?.notifier;
}

/// What a problem of the page is, see [StatusEntry].
enum StatusSource {
  /// This device's upload failed.
  deviceSync,

  /// This device's log holds entries not seen yet.
  deviceLog,

  /// The server cannot be asked.
  deviceServer,

  /// One of the server's own, see [StatusEntry.problem].
  server,
}

/// One problem of the status page.
@immutable
class StatusEntry {
  /// What identifies it: the server's [StatusProblem.id], or one of this
  /// device's ids ([deviceSyncId], [deviceLogId], [deviceServerId]).
  final String id;

  final StatusSource source;

  /// Why, in the words of whoever said it.
  final String reason;

  /// Whether it was marked as seen and did not change since.
  final bool seen;

  /// The server's problem, for [StatusSource.server].
  final StatusProblem? problem;

  /// The entries of the log not seen yet, for [StatusSource.deviceLog].
  final int newEntries;

  /// The newest entry of the log, for [StatusSource.deviceLog].
  final DateTime? newest;

  const StatusEntry({
    required this.id,
    required this.source,
    required this.reason,
    required this.seen,
    this.problem,
    this.newEntries = 0,
    this.newest,
  });
}

/// What the status page and the indicator show, see [summarizeStatus].
@immutable
class StatusSummary {
  /// Every problem, this device's first, in the order they are shown.
  final List<StatusEntry> problems;

  /// Whether something runs: this device's upload, the server's background.
  final bool running;

  /// How far what runs has got, from 0 to 1: this device's upload, else the
  /// server's albums or the content search, see [serverProgress].
  final double progress;

  /// Whether this device has a problem of its own: its upload, its log, the
  /// server it cannot reach.
  final bool deviceProblem;

  const StatusSummary(
      {required this.problems,
      required this.running,
      required this.deviceProblem,
      this.progress = 0});

  /// The problems not seen yet.
  List<StatusEntry> get unseen => [
        for (var entry in problems)
          if (!entry.seen) entry
      ];

  /// The problems seen.
  List<StatusEntry> get known => [
        for (var entry in problems)
          if (entry.seen) entry
      ];
}

/// The reason of this device's upload that failed, `null` where it did not:
/// what the server said, else the app's own reason, see [cameraRollLine].
String? cameraRollFailure(CameraRollStatus? status, AppLocalizations l10n) {
  if (status == null) {
    return null;
  }
  var failed = status.phase == CameraRollPhase.failed ||
      status.phase == CameraRollPhase.unavailable ||
      (status.phase == CameraRollPhase.waiting && !status.indexing);
  if (!failed) {
    return null;
  }
  return status.message ??
      (status.notice == null
          ? l10n.cameraRollUnknownReason
          : noticeText(status.notice!, l10n));
}

/// Whether the server's background is at work, see [StatusSummary.running].
bool serverRunning(StatusInfo? server) {
  if (server == null || !server.admin) {
    return false;
  }
  var catchUp = server.catchUp;
  var index = server.index;
  return (catchUp != null &&
          (catchUp.step != CatchUpStep.idle ||
              catchUp.albumsDone < catchUp.albumsTotal ||
              catchUp.videosRemaining > 0 ||
              (catchUp.contentWaiting.isEmpty &&
                  catchUp.contentRemaining > 0))) ||
      (index != null && index.done < index.total) ||
      server.placesLoading.isNotEmpty;
}

/// How far the server's background has got, from 0 to 1: the albums while
/// they are prepared, then the photos described for the content search.
double serverProgress(StatusInfo? server) {
  var catchUp = server?.catchUp;
  if (catchUp == null) {
    return 0;
  }
  if (catchUp.albumsDone < catchUp.albumsTotal && catchUp.albumsTotal > 0) {
    return catchUp.albumsDone / catchUp.albumsTotal;
  }
  if (catchUp.contentEnabled &&
      catchUp.contentTotal > 0 &&
      catchUp.contentDescribed < catchUp.contentTotal) {
    return catchUp.contentDescribed / catchUp.contentTotal;
  }
  var index = server!.index;
  if (index != null && index.total > 0 && index.done < index.total) {
    return index.done / index.total;
  }
  return 0;
}

/// Everything the page and the indicator show, from what is known now.
StatusSummary summarizeStatus({
  required AppLocalizations l10n,
  StatusInfo? server,
  String? serverProblem,
  CameraRollStatus? cameraRoll,
  List<DiagnosticsEntry> log = const [],
  DeviceSeen seen = const DeviceSeen(),
}) {
  var problems = <StatusEntry>[];
  var sync = cameraRollFailure(cameraRoll, l10n);
  if (sync != null) {
    problems.add(StatusEntry(
      id: deviceSyncId,
      source: StatusSource.deviceSync,
      reason: sync,
      seen: seen.hasSeen(deviceSyncId, sync),
    ));
  }
  if (serverProblem != null) {
    problems.add(StatusEntry(
      id: deviceServerId,
      source: StatusSource.deviceServer,
      reason: serverProblem,
      seen: seen.hasSeen(deviceServerId, serverProblem),
    ));
  }
  if (log.isNotEmpty) {
    var unseen = [
      for (var entry in log)
        if (!seen.hasSeenEntry(entry)) entry
    ];
    var newest = log
        .map((entry) => entry.lastAt)
        .reduce((left, right) => left.isAfter(right) ? left : right);
    var latest = unseen.isEmpty ? log.last : unseen.last;
    problems.add(StatusEntry(
      id: deviceLogId,
      source: StatusSource.deviceLog,
      reason: latest.headline,
      seen: unseen.isEmpty,
      newEntries: unseen.length,
      newest: newest,
    ));
  }
  var deviceProblem = problems.any((entry) => !entry.seen);
  if (server != null && server.admin) {
    for (var problem in server.problems) {
      problems.add(StatusEntry(
        id: problem.id,
        source: StatusSource.server,
        reason: problem.reason,
        seen: problem.seen,
        problem: problem,
      ));
    }
  }
  var uploading = cameraRoll?.running ?? false;
  return StatusSummary(
    problems: problems,
    running: uploading || serverRunning(server),
    deviceProblem: deviceProblem,
    progress:
        uploading ? (cameraRoll!.progress ?? 0) : serverProgress(server),
  );
}

/// The summary of the app as the given context sees it.
StatusSummary statusOfContext(BuildContext context, {DiagnosticsLog? log}) {
  var monitor = StatusScope.maybeOf(context);
  log ??= ServerSettingsScope.maybeOf(context)?.diagnostics;
  return summarizeStatus(
    l10n: AppLocalizations.of(context)!,
    server: monitor?.server,
    serverProblem: monitor?.problem,
    cameraRoll: CameraRollScope.maybeOf(context)?.status,
    log: log?.entries ?? const [],
    seen: monitor?.seen ?? const DeviceSeen(),
  );
}

/// The indicator in an app bar, see the library comment: quiet, a ring, or a
/// red dot. Shown to a member, and to anybody while this device uploads or
/// its upload failed; tapped, it opens the status page.
class StatusIndicator extends StatelessWidget {
  const StatusIndicator({super.key});

  @override
  Widget build(BuildContext context) {
    var monitor = StatusScope.maybeOf(context);
    var sync = CameraRollScope.maybeOf(context);
    var diagnostics = ServerSettingsScope.maybeOf(context)?.diagnostics;
    if (diagnostics != null) {
      // The log is part of the problems: a new entry may light the dot.
      return ListenableBuilder(
        listenable: diagnostics,
        builder: (context, _) => _build(context, monitor, sync),
      );
    }
    return _build(context, monitor, sync);
  }

  Widget _build(
      BuildContext context, StatusMonitor? monitor, CameraRollSync? sync) {
    var l10n = AppLocalizations.of(context)!;
    var summary = statusOfContext(context);
    var shown = (monitor?.active ?? false) ||
        (sync?.status.running ?? false) ||
        cameraRollFailure(sync?.status, l10n) != null;
    if (!shown) {
      return const SizedBox.shrink();
    }
    var unseen = summary.unseen.length;
    String tooltip;
    if (unseen > 0) {
      tooltip = l10n.statusTooltipProblem(unseen);
    } else if (sync != null && sync.status.running) {
      tooltip = cameraRollLine(sync.status, l10n);
    } else if (summary.running) {
      tooltip = l10n.statusTooltipRunning;
    } else {
      tooltip = l10n.statusTooltipQuiet;
    }
    var color = Theme.of(context).colorScheme;
    Widget icon = Icon(sync != null && sync.status.running
        ? Icons.cloud_upload
        : Icons.monitor_heart_outlined);
    if (summary.running) {
      icon = Stack(
        alignment: Alignment.center,
        children: [
          // Determinate, so that it says how far, and never spins for
          // nothing: "x of y" as a ring.
          SizedBox(
            key: statusRingKey,
            width: 28,
            height: 28,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              value: summary.progress,
              backgroundColor: color.surfaceContainerHighest,
            ),
          ),
          SizedBox(width: 18, height: 18, child: FittedBox(child: icon)),
        ],
      );
    }
    if (unseen > 0) {
      icon = Badge(
        key: statusDotKey,
        backgroundColor: color.error,
        smallSize: 10,
        child: icon,
      );
    }
    return IconButton(
      key: statusIndicatorKey,
      tooltip: tooltip,
      onPressed: () => openStatusPage(context),
      icon: icon,
    );
  }
}

/// Opens the status page: as a level of the router where the context is in
/// one ([StatusRoute], so that its address is `/.status/`), else on top of
/// whatever is shown (the settings, the start screen).
///
/// [log] is the log of a screen that keeps its own, see
/// [ServerSettingsScreen.diagnostics]; the app's own is found by itself.
void openStatusPage(BuildContext context,
    {DiagnosticsLog? log, ServerSettings? settings}) {
  var navigator = VAlbumNavigator.maybeOf(context);
  var route = ModalRoute.of(context);
  if (log == null && navigator != null && route != null && route.settings is Page) {
    navigator.go(const StatusRoute());
    return;
  }
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) =>
          SelectableArea(child: StatusPage(log: log, settings: settings)),
    ),
  );
}

/// The status page, see the library comment.
class StatusPage extends StatefulWidget {
  /// Leaves the page, `null` for the platform's back.
  final VoidCallback? onUp;

  /// This device's log, `null` for the app's own.
  final DiagnosticsLog? log;

  /// The server settings the copied log names, `null` for the app's own.
  final ServerSettings? settings;

  const StatusPage({super.key, this.onUp, this.log, this.settings});

  @override
  State<StatusPage> createState() => StatusPageState();
}

class StatusPageState extends State<StatusPage> {
  VoidCallback? _unwatch;

  StatusMonitor? _monitor;

  final GlobalKey _logKey = GlobalKey();

  /// The problems a write is under way for, by id.
  final Set<String> _busy = {};

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    var monitor = StatusScope.maybeOf(context);
    if (!identical(monitor, _monitor)) {
      _unwatch?.call();
      _monitor = monitor;
      _unwatch = monitor?.watch();
    }
  }

  @override
  void dispose() {
    _unwatch?.call();
    super.dispose();
  }

  AppLocalizations get _l10n => AppLocalizations.of(context)!;

  @override
  Widget build(BuildContext context) {
    var l10n = _l10n;
    var diagnostics =
        widget.log ?? ServerSettingsScope.maybeOf(context)?.diagnostics;
    var body = ListenableBuilder(
      listenable: diagnostics ?? ChangeNotifier(),
      builder: (context, _) => _content(context, l10n, diagnostics),
    );
    return Scaffold(
      appBar: AppBar(
        leading: widget.onUp == null
            ? null
            : IconButton(
                key: const Key("status-up"),
                icon: const Icon(Icons.arrow_back),
                tooltip: l10n.up,
                onPressed: widget.onUp,
              ),
        title: Text(l10n.statusTitle),
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: body,
        ),
      ),
    );
  }

  Widget _content(
      BuildContext context, AppLocalizations l10n, DiagnosticsLog? log) {
    var monitor = _monitor;
    var summary = statusOfContext(context, log: log);
    var server = monitor?.server;
    var unseen = summary.unseen;
    var known = summary.known;
    var theme = Theme.of(context);
    return ListView(
      padding: pagePadding(context, const EdgeInsets.all(16)),
      children: [
        Column(
          key: statusProblemsKey,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(l10n.statusProblemsHeading,
                      style: theme.textTheme.titleMedium),
                ),
                if (unseen.isNotEmpty)
                  TextButton(
                    key: statusMarkAllKey,
                    onPressed: () => _markSeen(unseen),
                    child: Text(l10n.statusMarkAllSeen),
                  ),
              ],
            ),
            if (unseen.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(l10n.statusNoProblems,
                    key: const Key("status-no-problems")),
              ),
            for (var entry in unseen) _problem(context, l10n, entry, server),
            if (known.isNotEmpty)
              ExpansionTile(
                key: statusKnownKey,
                tilePadding: EdgeInsets.zero,
                title: Text(l10n.statusKnownProblems(known.length)),
                childrenPadding: const EdgeInsets.only(bottom: 8),
                children: [
                  for (var entry in known)
                    _problem(context, l10n, entry, server),
                ],
              ),
          ],
        ),
        const SizedBox(height: 16),
        const Divider(),
        Column(
          key: statusRunningKey,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: _running(context, l10n, monitor),
        ),
        const SizedBox(height: 16),
        const Divider(),
        Text(l10n.statusLogHeading,
            key: _logKey, style: theme.textTheme.titleMedium),
        const SizedBox(height: 4),
        // The version the copied log names, as the build says it.
        Text(l10n.statusAppVersion(appVersion),
            key: const Key("status-app-version")),
        const SizedBox(height: 8),
        KeyedSubtree(
          key: statusLogKey,
          child: DiagnosticsPanel(
            log: log ?? DiagnosticsLog(),
            settings: widget.settings,
          ),
        ),
      ],
    );
  }

  /// What runs: this device's upload, the server's background.
  List<Widget> _running(
      BuildContext context, AppLocalizations l10n, StatusMonitor? monitor) {
    var theme = Theme.of(context);
    var sync = CameraRollScope.maybeOf(context);
    var server = monitor?.server;
    var lines = <Widget>[
      Text(l10n.statusRunningHeading, style: theme.textTheme.titleMedium),
      const SizedBox(height: 8),
    ];
    var roll = sync?.status;
    if (roll != null && roll.phase != CameraRollPhase.disabled) {
      lines.add(Text(l10n.statusDeviceUpload(cameraRollLine(roll, l10n)),
          key: const Key("status-device-upload")));
      if (roll.running) {
        lines.add(Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 4),
          child: LinearProgressIndicator(value: roll.progress),
        ));
      }
    }
    if (monitor != null && monitor.active) {
      if (server == null) {
        lines.add(Text(monitor.problem == null
            ? l10n.catchUpAsking
            : l10n.catchUpUnavailable(monitor.problem!)));
      } else if (!server.admin) {
        lines.add(
            Text(l10n.statusMemberNote, key: const Key("status-member-note")));
      } else {
        var catchUp = server.catchUp;
        if (catchUp != null) {
          lines.add(CatchUpSection(status: catchUp));
        }
        for (var step in server.steps) {
          lines.add(Text(l10n.statusStepProgress(
              catchUpStepName(l10n, step.step, step.name),
              step.done,
              step.total)));
        }
        var index = server.index;
        if (index != null) {
          lines.add(Text(l10n.statusIndexProgress(index.done, index.total)));
        }
        if (server.placesLoading.isNotEmpty) {
          lines.add(Text(l10n.statusPlacesLoading(
              server.placesLoading.map((key) => key.id).join(", "))));
        }
      }
    }
    if (lines.length == 2) {
      lines.add(Text(l10n.statusNothingRuns));
    }
    return lines;
  }

  /// The title of a problem.
  String _title(AppLocalizations l10n, StatusEntry entry) {
    switch (entry.source) {
      case StatusSource.deviceSync:
        return l10n.statusSyncFailed;
      case StatusSource.deviceLog:
        return l10n.statusLogNew(entry.newEntries);
      case StatusSource.deviceServer:
        return l10n.statusServerUnreachable;
      case StatusSource.server:
        var problem = entry.problem!;
        switch (problem.kind) {
          case StatusProblemKind.failure:
            return l10n.statusFailure(
              catchUpStepName(l10n, problem.step, problem.stepName),
              l10n.statusFileCount(problem.files),
              l10n.statusAlbumCount(problem.albums),
            );
          case StatusProblemKind.unwritable:
            return l10n.statusUnwritable(problem.items.length);
          case StatusProblemKind.embedder:
            return l10n.statusEmbedder;
          case StatusProblemKind.faces:
            return l10n.statusFaces;
          case StatusProblemKind.videos:
            return l10n.statusVideos;
          case StatusProblemKind.places:
            return l10n.statusPlaces;
        }
    }
  }

  Widget _problem(BuildContext context, AppLocalizations l10n,
      StatusEntry entry, StatusInfo? server) {
    var theme = Theme.of(context);
    var problem = entry.problem;
    var admin = server?.admin ?? false;
    var busy = _busy.contains(entry.id);
    var reason = entry.source == StatusSource.deviceLog
        ? (entry.seen ? l10n.statusLogSeen : l10n.statusLogNewReason)
        : entry.reason;
    return Card(
      key: statusProblemKey(entry.id),
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(right: 8, top: 2),
                  child: Icon(
                    entry.seen ? Icons.check_circle_outline : Icons.error,
                    size: 20,
                    color: entry.seen ? null : theme.colorScheme.error,
                  ),
                ),
                Expanded(
                  child: Text(_title(l10n, entry),
                      style: theme.textTheme.titleSmall),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(reason),
            if (problem != null && problem.retryAt > 0)
              Text(l10n.statusRetryAt(_minutesUntil(problem.retryAt)),
                  style: theme.textTheme.bodySmall),
            if (problem != null && problem.items.isNotEmpty)
              _items(context, l10n, problem, server),
            const SizedBox(height: 4),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                ..._fixes(context, l10n, entry),
                if (problem != null && problem.retryable && admin)
                  OutlinedButton.icon(
                    key: statusRetryKey(entry.id),
                    onPressed: busy ? null : () => _retry(problem),
                    icon: const Icon(Icons.refresh),
                    label: Text(l10n.statusRetryNow),
                  ),
                if (!entry.seen && (problem == null || admin))
                  TextButton.icon(
                    key: statusSeenKey(entry.id),
                    onPressed: busy ? null : () => _markSeen([entry]),
                    icon: const Icon(Icons.check),
                    label: Text(l10n.statusMarkSeen),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// The buttons leading to where a problem is fixed.
  List<Widget> _fixes(
      BuildContext context, AppLocalizations l10n, StatusEntry entry) {
    Widget button(String label, VoidCallback onPressed) => FilledButton.tonal(
          key: statusFixKey(entry.id),
          onPressed: onPressed,
          child: Text(label),
        );
    switch (entry.source) {
      case StatusSource.deviceSync:
        return [
          button(
              l10n.statusOpenCameraRoll,
              () =>
                  openServerSettings(context, focus: SettingsFocus.cameraRoll)),
        ];
      case StatusSource.deviceLog:
        return [button(l10n.statusShowLog, _showLog)];
      case StatusSource.deviceServer:
        return [
          button(
              l10n.statusOpenServerSettings, () => openServerSettings(context)),
        ];
      case StatusSource.server:
        if (entry.problem!.kind == StatusProblemKind.embedder) {
          return [
            button(
                l10n.statusOpenSpaceSettings,
                () => openServerSettings(context,
                    focus: SettingsFocus.spaceSettings)),
          ];
        }
        return const [];
    }
  }

  /// Every file or folder of a problem of the server, expandable; every
  /// folder the server cannot write, shown at once and copyable as a whole.
  Widget _items(BuildContext context, AppLocalizations l10n,
      StatusProblem problem, StatusInfo? server) {
    var unwritable = problem.kind == StatusProblemKind.unwritable;
    var theme = Theme.of(context);
    var navigator = VAlbumNavigator.maybeOf(context);
    String folderName(String folder) =>
        folder.isEmpty ? l10n.catchUpRoot : folder;
    Widget open(String folder) => navigator == null
        ? const SizedBox.shrink()
        : IconButton(
            tooltip: l10n.statusOpenAlbum,
            icon: const Icon(Icons.photo_album_outlined, size: 20),
            onPressed: () => navigator.go(ListingOrAlbumRoute([
              for (var segment in folder.split("/"))
                if (segment.isNotEmpty) segment
            ])),
          );
    var rows = <Widget>[
      for (var item in problem.items)
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: unwritable
                      ? [
                          Text(folderName(item.folder),
                              style:
                                  const TextStyle(fontWeight: FontWeight.bold)),
                          Text(item.path,
                              style: const TextStyle(
                                  fontFamily: "monospace", fontSize: 12)),
                          Text(l10n.statusFolderPhotos(item.photos, item.what),
                              style: theme.textTheme.bodySmall),
                        ]
                      : [
                          Text(item.name.isEmpty
                              ? folderName(item.folder)
                              : item.folder.isEmpty
                                  ? item.name
                                  : "${item.folder}/${item.name}"),
                        ],
                ),
              ),
            ),
            if (problem.kind == StatusProblemKind.failure || unwritable)
              open(item.folder),
          ],
        ),
    ];
    if (problem.kind != StatusProblemKind.failure && !unwritable) {
      return Column(
          crossAxisAlignment: CrossAxisAlignment.start, children: rows);
    }
    return ExpansionTile(
      key: ValueKey("status-items-${problem.id}"),
      tilePadding: EdgeInsets.zero,
      // Every folder the server cannot write is named at once: "insbesondere
      // möchte man wissen welche Verzeichnisse das sind".
      initiallyExpanded: unwritable,
      title: Text(unwritable
          ? l10n.statusFolders(problem.items.length)
          : l10n.statusFiles(problem.items.length)),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...rows,
        if (unwritable)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: OutlinedButton.icon(
              key: statusCopyPathsKey,
              onPressed: () => _copyPaths(problem),
              icon: const Icon(Icons.copy_all),
              label: Text(l10n.statusCopyPaths),
            ),
          ),
      ],
    );
  }

  /// Puts the path of every folder the server cannot write on the clipboard,
  /// one per line, as the command line takes them.
  Future<void> _copyPaths(StatusProblem problem) async {
    var copied = _l10n.statusPathsCopied;
    await Clipboard.setData(ClipboardData(
        text: [for (var item in problem.items) item.path].join("\n")));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(copied),
        duration: const Duration(seconds: 4),
      ));
    }
  }

  void _showLog() {
    var target = _logKey.currentContext;
    if (target != null) {
      Scrollable.ensureVisible(target,
          duration: const Duration(milliseconds: 300));
    }
  }

  /// Marks the given problems as seen: this device's here, the server's on
  /// the server (an administrator's write).
  Future<void> _markSeen(List<StatusEntry> entries) async {
    var monitor = _monitor;
    var device = <String, String>{};
    DateTime? logUpTo;
    var server = <String>[];
    for (var entry in entries) {
      switch (entry.source) {
        case StatusSource.deviceLog:
          logUpTo = entry.newest;
        case StatusSource.deviceSync:
        case StatusSource.deviceServer:
          device[entry.id] = entry.reason;
        case StatusSource.server:
          server.add(entry.id);
      }
    }
    if (monitor != null && (device.isNotEmpty || logUpTo != null)) {
      await monitor.markDeviceSeen(device, logUpTo: logUpTo);
    }
    var client = monitor?.client;
    if (server.isEmpty || client == null || !mounted) {
      return;
    }
    setState(() => _busy.addAll(server));
    var all = monitor!.server?.problems
            .every((problem) => problem.seen || server.contains(problem.id)) ??
        false;
    var written = await writeAction(
      context,
      () => client.markStatusSeen(ids: all ? const [] : server, all: all),
    );
    if (written != null) {
      monitor.take(written.value);
    }
    if (mounted) {
      setState(() => _busy.removeAll(server));
    }
  }

  /// Tries the given problem of the server again (an administrator's write).
  Future<void> _retry(StatusProblem problem) async {
    var monitor = _monitor;
    var client = monitor?.client;
    if (client == null) {
      return;
    }
    var messenger = ScaffoldMessenger.maybeOf(context);
    var started = _l10n.statusRetryStarted;
    setState(() => _busy.add(problem.id));
    var written =
        await writeAction(context, () => client.retryStatus(problem.id));
    if (written != null) {
      monitor!.take(written.value);
      messenger?.showSnackBar(SnackBar(content: Text(started)));
    }
    if (mounted) {
      setState(() => _busy.remove(problem.id));
    }
  }

  /// The whole minutes from now to the given moment, rounded up.
  static int _minutesUntil(int millis) {
    var left = millis - DateTime.now().millisecondsSinceEpoch;
    return left <= 0 ? 0 : (left + 59999) ~/ 60000;
  }
}

/// A line leading to the status page from where a status used to be (the
/// settings of the camera roll, of the space, the background, the log), see
/// issue #305: "Settings keep settings".
class StatusPageLink extends StatelessWidget {
  /// What the line is about, `null` for "Status".
  final String? title;

  /// What the page shows of it, `null` for the generic sentence.
  final String? subtitle;

  /// The log of a screen that keeps its own, see [openStatusPage].
  final DiagnosticsLog? log;

  /// The settings of that screen, see [openStatusPage].
  final ServerSettings? settings;

  const StatusPageLink(
      {super.key, this.title, this.subtitle, this.log, this.settings});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.monitor_heart_outlined),
      title: Text(title ?? l10n.statusTitle),
      subtitle: Text(subtitle ?? l10n.statusSeeStatusPage),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => openStatusPage(context, log: log, settings: settings),
    );
  }
}

/// This device's log (issues #58, #184, #303): monospaced, newest last, with
/// "Copy" and "Clear". It lived at the bottom of the settings before the
/// status page (issue #305).
class DiagnosticsPanel extends StatefulWidget {
  final DiagnosticsLog log;

  /// The settings whose server the copied log names, `null` for the app's.
  final ServerSettings? settings;

  const DiagnosticsPanel({super.key, required this.log, this.settings});

  @override
  State<DiagnosticsPanel> createState() => _DiagnosticsPanelState();
}

class _DiagnosticsPanelState extends State<DiagnosticsPanel> {
  @override
  void initState() {
    super.initState();
    // What a background run wrote in its own engine since the app read the
    // log last, see [DiagnosticsLog.reload].
    unawaited(widget.log.reload());
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    var log = widget.log;
    return AnimatedBuilder(
      animation: log,
      builder: (context, _) => Column(
        key: diagnosticsSectionKey,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.diagnosticsLead),
          const SizedBox(height: 8),
          Container(
            width: double.infinity,
            constraints: const BoxConstraints(maxHeight: 240),
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              border: Border.all(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(4),
            ),
            child: SingleChildScrollView(
              // Anchored at the end: the newest line is the one the person
              // came here to read.
              reverse: true,
              child: SelectableText(
                log.entries.isEmpty
                    ? l10n.diagnosticsEmpty
                    : log.entries.join("\n"),
                style: const TextStyle(fontFamily: "monospace", fontSize: 11),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton.icon(
                key: diagnosticsCopyKey,
                onPressed: _copy,
                icon: const Icon(Icons.copy_all),
                label: Text(l10n.copy),
              ),
              TextButton.icon(
                key: diagnosticsClearKey,
                onPressed: log.isEmpty ? null : log.clear,
                icon: const Icon(Icons.delete_outline),
                label: Text(l10n.clear),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Puts the whole log, header and all, on the clipboard and says so.
  Future<void> _copy() async {
    var copied = AppLocalizations.of(context)!.diagnosticsCopied;
    var settings =
        widget.settings ?? ServerSettingsScope.maybeOf(context)?.settings;
    var text = widget.log.copyText(
      serverUrl: settings?.serverUrl ?? settings?.dataUrl,
      platform: platformDescription(),
    );
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(copied), duration: const Duration(seconds: 4)),
    );
  }
}
