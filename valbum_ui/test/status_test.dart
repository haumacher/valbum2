/// Tests of the status page and its indicator (issue #305): one place for
/// everything that runs, waits or fails — the indicator's three states, the
/// order of the page, "seen" and "Known problems", every folder the server
/// cannot write, this device's upload as a problem, what a member sees, and
/// the ways from the settings to the page.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/catch_up.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/space_settings_view.dart';

import 'camera_roll_test.dart' show Harness, photo;
import 'catch_up_test.dart' show authOfUser, json, statusOf;
import 'space_settings_test.dart' show settingsOf;
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

/// A catch-up of a background that is done with everything.
String idleCatchUp() => statusOf(
    done: 40,
    step: "IDLE",
    folder: "",
    videos: 0,
    failure: "",
    failureFolder: "");

/// A failure of the previews in three files of two albums.
Map<String, Object?> previewsFailure({bool seen = false}) => {
      "id": "failure:previews",
      "kind": "FAILURE",
      "step": "PREVIEWS",
      "stepName": "previews",
      "reason": "unreadable format",
      "files": 3,
      "albums": 2,
      "items": [
        {"folder": "2024/Zoo", "name": "a.jpg"},
        {"folder": "2024/Zoo", "name": "b.jpg"},
        {"folder": "Public", "name": "c.jpg"},
      ],
      "seen": seen,
      "retryable": true,
    };

/// Three folders the server cannot write.
Map<String, Object?> unwritable({bool seen = false}) => {
      "id": "unwritable",
      "kind": "UNWRITABLE",
      "reason": "The server's user 'valbum' cannot write these folders. "
          "Fix: chown -R valbum:valbum <folder>",
      "files": 3,
      "albums": 3,
      "items": [
        {
          "folder": "",
          "path": "/srv/photos",
          "photos": 2,
          "what": "its photo hashes"
        },
        {
          "folder": "2019/Copied",
          "path": "/srv/photos/2019/Copied",
          "photos": 120,
          "what": "its photo hashes, its previews and faces"
        },
        {
          "folder": "2020/Mount",
          "path": "/srv/photos/2020/Mount",
          "photos": 7,
          "what": "the results of 'previews'"
        },
      ],
      "seen": seen,
      "retryable": true,
    };

/// The answer of `?type=status`.
String statusAnswer({
  bool admin = true,
  String? catchUp,
  List<Map<String, Object?>> problems = const [],
}) {
  if (!admin) {
    return '{"admin": false}';
  }
  return '{"admin": true, "catchUp": ${catchUp ?? idleCatchUp()}, '
      '"index": {"done": 10, "total": 10}, "root": "/srv/photos", '
      '"steps": [{"step": "PREVIEWS", "name": "previews", "done": 40, '
      '"total": 40}], "problems": ${jsonEncode(problems)}}';
}

/// A server answering the status with what [answer] says, and the writes of
/// the page as [onPost] says.
class FakeServer {
  String Function() answer;

  String Function(http.Request request)? onPost;

  final List<http.Request> posts = [];

  FakeServer(this.answer, {this.onPost});

  late final VAlbumClient client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient((request) async {
      if (request.method == "POST") {
        posts.add(request);
        return json(onPost == null ? answer() : onPost!(request));
      }
      if (request.url.queryParameters["type"] == "status") {
        return json(answer());
      }
      return json('{"users": []}');
    }),
  ).withToken("token", userName: "alice");
}

/// The app around a widget: the monitor of [server], the camera roll of
/// [sync], the log [log].
Widget appAround(
  Widget home, {
  required StatusMonitor monitor,
  CameraRollSync? sync,
  DiagnosticsLog? log,
  ServerSettings? settings,
}) {
  Widget app = MaterialApp(
    localizationsDelegates: testLocalizationsDelegates,
    supportedLocales: testSupportedLocales,
    home: home,
  );
  app = StatusScope(monitor: monitor, child: app);
  app = ServerSettingsScope(
    settings: settings ?? ServerSettings(store: InMemorySettingsStore()),
    clientFor: (url) => VAlbumClient(dataUrl: url),
    diagnostics: log ?? DiagnosticsLog(),
    child: app,
  );
  if (sync != null) {
    app = CameraRollScope(sync: sync, child: app);
  }
  return app;
}

/// The indicator in an app bar, with a monitor asking [server].
Future<StatusMonitor> pumpIndicator(WidgetTester tester, FakeServer server,
    {DiagnosticsLog? log, CameraRollSync? sync}) async {
  var monitor = StatusMonitor();
  monitor.attach(server.client);
  await tester.pumpWidget(appAround(
    Scaffold(appBar: AppBar(actions: const [StatusIndicator()])),
    monitor: monitor,
    log: log,
    sync: sync,
  ));
  await tester.pumpAndSettle();
  return monitor;
}

/// The status page, opened from a button, with a monitor asking [server].
Future<StatusMonitor> pumpPage(WidgetTester tester, FakeServer server,
    {DiagnosticsLog? log,
    CameraRollSync? sync,
    ServerSettings? settings}) async {
  await tester.binding.setSurfaceSize(const Size(800, 3000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var monitor = StatusMonitor();
  monitor.attach(server.client);
  await tester.pumpWidget(appAround(
    Builder(
      builder: (context) => Scaffold(
        appBar: AppBar(actions: const [StatusIndicator()]),
        body: TextButton(
          key: const Key("open-status"),
          onPressed: () => openStatusPage(context),
          child: const Text("Status"),
        ),
      ),
    ),
    monitor: monitor,
    log: log,
    sync: sync,
    settings: settings,
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("open-status")));
  await tester.pumpAndSettle();
  expect(find.byType(StatusPage), findsOneWidget);
  return monitor;
}

/// Ends a test: nothing keeps asking.
Future<void> finish(WidgetTester tester, StatusMonitor monitor) async {
  await tester.pumpWidget(const SizedBox());
  monitor.dispose();
}

void main() {
  group('the indicator', () {
    testWidgets('is quiet while all is well', (tester) async {
      var monitor = await pumpIndicator(tester, FakeServer(statusAnswer));
      expect(find.byKey(statusIndicatorKey), findsOneWidget);
      expect(find.byKey(statusRingKey), findsNothing);
      expect(find.byKey(statusDotKey), findsNothing);
      expect(tester.widget<IconButton>(find.byKey(statusIndicatorKey)).tooltip,
          "Status: all is well");
      await finish(tester, monitor);
    });

    testWidgets('shows a ring while the server prepares albums',
        (tester) async {
      var monitor = await pumpIndicator(
          tester, FakeServer(() => statusAnswer(catchUp: statusOf())));
      expect(find.byKey(statusRingKey), findsOneWidget);
      expect(find.byKey(statusDotKey), findsNothing);
      var ring = tester.widget<CircularProgressIndicator>(find.descendant(
          of: find.byKey(statusRingKey),
          matching: find.byType(CircularProgressIndicator)));
      expect(ring.value, closeTo(12 / 40, 1e-9), reason: "12 of 40 albums");
      await finish(tester, monitor);
    });

    testWidgets('shows a red dot for a problem not seen, and none once seen',
        (tester) async {
      var seen = false;
      var monitor = await pumpIndicator(
          tester,
          FakeServer(
              () => statusAnswer(problems: [previewsFailure(seen: seen)])));
      expect(find.byKey(statusDotKey), findsOneWidget);
      expect(tester.widget<IconButton>(find.byKey(statusIndicatorKey)).tooltip,
          "Status: 1 new problem");

      seen = true;
      await monitor.poll();
      await tester.pumpAndSettle();
      expect(find.byKey(statusDotKey), findsNothing,
          reason: "A problem seen lights nothing.");
      expect(find.byKey(statusIndicatorKey), findsOneWidget);
      await finish(tester, monitor);
    });

    testWidgets('a new entry of the log lights the dot until it is seen',
        (tester) async {
      var log = DiagnosticsLog();
      var monitor =
          await pumpIndicator(tester, FakeServer(statusAnswer), log: log);
      expect(find.byKey(statusDotKey), findsNothing);
      log.add("Could not load the album listing: the server answered 500");
      await tester.pumpAndSettle();
      expect(find.byKey(statusDotKey), findsOneWidget);

      await monitor.markDeviceSeen(const {}, logUpTo: log.entries.last.lastAt);
      await tester.pumpAndSettle();
      expect(find.byKey(statusDotKey), findsNothing);
      await finish(tester, monitor);
    });

    testWidgets('opens the status page', (tester) async {
      var monitor = await pumpIndicator(tester, FakeServer(statusAnswer));
      await tester.tap(find.byKey(statusIndicatorKey));
      await tester.pumpAndSettle();
      expect(find.byType(StatusPage), findsOneWidget);
      await finish(tester, monitor);
    });
  });

  group('the page', () {
    testWidgets('shows the problems first, then what runs, then the log',
        (tester) async {
      var log = DiagnosticsLog()..add("Something went wrong");
      var monitor = await pumpPage(
          tester,
          FakeServer(() =>
              statusAnswer(catchUp: statusOf(), problems: [previewsFailure()])),
          log: log);
      double top(Key key) => tester.getTopLeft(find.byKey(key)).dy;
      expect(top(statusProblemsKey), lessThan(top(statusRunningKey)));
      expect(top(statusRunningKey), lessThan(top(statusLogKey)));

      // The failure, merged by cause, with its files.
      expect(find.text("Step Previews: 3 files in 2 albums"), findsOneWidget);
      expect(find.text("unreadable format"), findsOneWidget);
      await tester.tap(find.text("Files (3)"));
      await tester.pumpAndSettle();
      expect(find.text("2024/Zoo/a.jpg"), findsOneWidget);
      expect(find.text("Public/c.jpg"), findsOneWidget);
      // The log as a problem of this device.
      expect(find.byKey(statusProblemKey(deviceLogId)), findsOneWidget);
      expect(find.text("This device noted 1 problem"), findsOneWidget);
      // What runs.
      expect(find.byKey(catchUpLineKey), findsOneWidget);
      expect(find.text("Step Previews: 40 of 40 albums"), findsOneWidget);
      expect(find.text("Photo index: 10 of 10 folders"), findsOneWidget);
      // The log itself, with the version its copy names.
      expect(find.text("This app: version $appVersion"), findsOneWidget);
      expect(find.byKey(diagnosticsCopyKey), findsOneWidget);
      expect(find.textContaining("Something went wrong"), findsWidgets);
      await finish(tester, monitor);
    });

    testWidgets('"Mark all as seen" collapses every problem under "Known"',
        (tester) async {
      var seen = false;
      var server = FakeServer(
          () => statusAnswer(problems: [
                previewsFailure(seen: seen),
                unwritable(seen: seen),
              ]), onPost: (request) {
        seen = true;
        return statusAnswer(
            problems: [previewsFailure(seen: true), unwritable(seen: true)]);
      });
      var monitor = await pumpPage(tester, server);
      expect(find.byKey(statusProblemKey("failure:previews")), findsOneWidget);
      expect(find.byKey(statusKnownKey), findsNothing);

      await tester.tap(find.byKey(statusMarkAllKey));
      await tester.pumpAndSettle();

      expect(server.posts, hasLength(1));
      expect(server.posts.single.url.queryParameters["action"], "status-seen");
      expect(jsonDecode(server.posts.single.body)["all"], isTrue);
      expect(find.text("No problems."), findsOneWidget);
      expect(find.text("Known problems (2)"), findsOneWidget);
      expect(find.byKey(statusProblemKey("failure:previews")), findsNothing,
          reason: "Collapsed.");
      expect(find.byKey(statusMarkAllKey), findsNothing);

      await tester.tap(find.byKey(statusKnownKey));
      await tester.pumpAndSettle();
      expect(find.byKey(statusProblemKey("failure:previews")), findsOneWidget,
          reason: "Seen, not deleted.");
      await finish(tester, monitor);
    });

    testWidgets(
        'names every folder the server cannot write, and copies all their '
        'paths at once', (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == "Clipboard.setData") {
          copied = (call.arguments as Map)["text"] as String;
        }
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));
      var monitor = await pumpPage(
          tester, FakeServer(() => statusAnswer(problems: [unwritable()])));

      expect(find.text("The server cannot write 3 folders"), findsOneWidget);
      // Shown at once, every one: never only "n folders".
      expect(find.text("Folders (3)"), findsOneWidget);
      expect(find.text("the top folder"), findsOneWidget);
      expect(find.text("2019/Copied"), findsOneWidget);
      expect(find.text("2020/Mount"), findsOneWidget);
      expect(find.text("/srv/photos"), findsOneWidget);
      expect(find.text("/srv/photos/2019/Copied"), findsOneWidget);
      expect(find.text("/srv/photos/2020/Mount"), findsOneWidget);
      expect(
          find.text(
              "Photos: 120; not stored: its photo hashes, its previews and faces"),
          findsOneWidget);
      expect(find.textContaining("chown"), findsOneWidget);

      await tester.tap(find.byKey(statusCopyPathsKey));
      await tester.pumpAndSettle();
      expect(copied,
          "/srv/photos\n/srv/photos/2019/Copied\n/srv/photos/2020/Mount");
      expect(find.text("The paths are on the clipboard."), findsOneWidget);
      await finish(tester, monitor);
    });

    testWidgets('"Retry now" asks the server to try the cause again',
        (tester) async {
      var server = FakeServer(() => statusAnswer(problems: [previewsFailure()]),
          onPost: (request) => statusAnswer());
      var monitor = await pumpPage(tester, server);
      await tester.tap(find.byKey(statusRetryKey("failure:previews")));
      await tester.pumpAndSettle();
      expect(server.posts.single.url.queryParameters["action"], "status-retry");
      expect(jsonDecode(server.posts.single.body)["id"], "failure:previews");
      expect(find.byKey(statusProblemKey("failure:previews")), findsNothing,
          reason: "Fixed: it leaves the list.");
      await finish(tester, monitor);
    });

    testWidgets(
        'shows a failed upload of this device as a problem leading to the '
        'camera-roll settings', (tester) async {
      var harness = Harness(items: [photo("a.jpg", 1)]);
      addTearDown(harness.dispose);
      harness.upload = (_) async => http.Response(
            '["ErrorInfo",{"message":"Pair this device."}]',
            401,
          );
      await harness.sync.load();
      await harness.sync.syncNow();
      expect(harness.sync.status.phase, CameraRollPhase.waiting);

      var settings = ServerSettings(
          store: InMemorySettingsStore(
              "http://server/valbum/", "dev-2", "Phone", "carol"));
      await settings.load();
      var monitor = await pumpPage(tester, FakeServer(statusAnswer),
          sync: harness.sync, settings: settings);
      expect(find.byKey(statusProblemKey(deviceSyncId)), findsOneWidget);
      expect(find.text("This device's upload failed"), findsOneWidget);
      expect(find.text("Pair this device."), findsOneWidget);
      expect(find.byKey(statusDotKey), findsNothing,
          reason: "The page is on top of the indicator.");

      await tester.tap(find.byKey(statusFixKey(deviceSyncId)));
      await tester.pumpAndSettle();
      expect(find.byType(ServerSettingsScreen), findsOneWidget);
      expect(find.byType(CameraRollSection), findsOneWidget);
      expect(find.byKey(cameraRollStatusLinkKey), findsOneWidget,
          reason: "The camera roll points back to the status page.");

      // Seen on the device: not new any more.
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(statusSeenKey(deviceSyncId)));
      await tester.pumpAndSettle();
      expect(monitor.seen.hasSeen(deviceSyncId, "Pair this device."), isTrue);
      expect(find.text("Known problems (1)"), findsOneWidget);
      await finish(tester, monitor);
    });

    testWidgets('a member sees nothing of the administrator\'s part',
        (tester) async {
      var monitor =
          await pumpPage(tester, FakeServer(() => statusAnswer(admin: false)));
      expect(
          find.text("What the server works on and what fails there is shown "
              "to the administrator of this space."),
          findsOneWidget);
      expect(find.byKey(catchUpLineKey), findsNothing);
      expect(find.textContaining("Step "), findsNothing);
      expect(find.byType(OutlinedButton), findsNothing,
          reason: "No retry for a member.");
      expect(find.text("No problems."), findsOneWidget);
      await finish(tester, monitor);
    });

    testWidgets('says a server it cannot ask as a problem', (tester) async {
      var monitor = StatusMonitor();
      monitor.attach(VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async => json(
            '["ErrorInfo", {"message": "The server is down."}]',
            status: 503)),
      ));
      await tester.pumpWidget(appAround(const StatusPage(), monitor: monitor));
      await tester.pumpAndSettle();
      expect(find.text("The server cannot be asked"), findsOneWidget);
      expect(find.byKey(statusFixKey(deviceServerId)), findsOneWidget);
      await finish(tester, monitor);
    });

    testWidgets('asks the server faster while it is open, and no more after',
        (tester) async {
      var asked = 0;
      var server = FakeServer(() {
        asked++;
        return statusAnswer();
      });
      var monitor = await pumpPage(tester, server);
      var before = asked;
      await tester.pump(statusPagePollInterval);
      await tester.pump();
      expect(asked, greaterThan(before));
      await tester.pageBack();
      await tester.pumpAndSettle();
      var left = asked;
      await tester.pump(statusPagePollInterval * 2);
      expect(asked, left, reason: "Back to the slow pace of the indicator.");
      await finish(tester, monitor);
    });
  });

  group('the device\'s seen marks', () {
    test('read back what was written, and survive a new monitor', () async {
      var store = InMemorySettingsStore();
      var monitor = StatusMonitor(store: store);
      var at = DateTime.utc(2026, 10, 18, 12);
      await monitor
          .markDeviceSeen({deviceSyncId: "Pair this device."}, logUpTo: at);
      monitor.dispose();
      var again = StatusMonitor(store: store);
      await Future<void>.delayed(Duration.zero);
      expect(again.seen.hasSeen(deviceSyncId, "Pair this device."), isTrue);
      expect(again.seen.hasSeen(deviceSyncId, "Another reason."), isFalse,
          reason: "Another reason is new again.");
      expect(again.seen.logSeenAt, at.toLocal());
      again.dispose();
    });
  });

  group('the ways there', () {
    test('the page has an address of its own', () {
      expect(parseRoute(Uri.parse("/.status/")), const StatusRoute());
      expect(routeToUri(const StatusRoute()).path, "/.status/");
      expect(const StatusRoute().up, ListingOrAlbumRoute.root);
    });

    testWidgets(
        'the old places in the settings lead to the page: the background, '
        'the log, the content search', (tester) async {
      await tester.binding.setSurfaceSize(const Size(800, 4000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      var settings = ServerSettings(
          store: InMemorySettingsStore(
              "http://server/valbum/", "dev-2", "Phone", "alice"));
      await settings.load();
      var transport = MockClient((request) async {
        var query = request.url.queryParameters;
        return switch (query["type"]) {
          "auth" => json(authOfUser("admin")),
          "space-settings" => json('{"name": "Alice", "content": "ON", '
              '"contentUnavailable": "the embedder does not answer"}'),
          "invitations" => json('{"invitations": []}'),
          "devices" => json('{"devices": []}'),
          "contacts" => json('{"contacts": []}'),
          _ => json('{"users": []}'),
        };
      });
      var log = DiagnosticsLog();
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: ServerSettingsScreen(
          settings: settings,
          diagnostics: log,
          clientFor: (url) => VAlbumClient(dataUrl: url, httpClient: transport),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(catchUpLinkKey), findsOneWidget);
      expect(find.byKey(diagnosticsSectionKey), findsOneWidget);
      await tester.tap(find.byKey(catchUpLinkKey));
      await tester.pumpAndSettle();
      expect(find.byType(StatusPage), findsOneWidget);
    });

    testWidgets(
        'the content search of the space settings keeps its line and leads '
        'to the page', (tester) async {
      await tester.binding.setSurfaceSize(const Size(800, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: SpaceSettingsScreen(
          client: VAlbumClient(
              dataUrl: dataUrl,
              httpClient: MockClient((request) async => json(settingsOf(
                  content: "ON",
                  contentUnavailable: "the embedder does not answer")))),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(spaceSettingsContentStatusKey), findsOneWidget,
          reason: "The availability line stays beside the switch.");
      expect(find.byKey(spaceSettingsStatusLinkKey), findsOneWidget);
      await tester.ensureVisible(find.byKey(spaceSettingsStatusLinkKey));
      await tester.tap(find.byKey(spaceSettingsStatusLinkKey));
      await tester.pumpAndSettle();
      expect(find.byType(StatusPage), findsOneWidget);
    });
  });
}
