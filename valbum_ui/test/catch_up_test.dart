/// Tests of the administrator's line showing how far the server has got
/// preparing the albums in the background (issue #236): the progress in one
/// line and the details below it, on the status page since issue #305 — the
/// settings lead there, and show nothing of it to a caller who is no
/// administrator.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/catch_up.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/settings.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// A JSON answer of the server.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "carol"}';

/// A status as the server answers it.
String statusOf({
  int done = 12,
  int total = 40,
  String step = "FACES",
  String folder = "2024/2024-05-01 Zoo",
  int videos = 3,
  String failure = "previews: broken.jpg: Cannot read.",
  String failureFolder = "2023/Broken",
  bool yielding = false,
}) =>
    '{"albumsDone": $done, "albumsTotal": $total, "step": "$step", '
    '"folder": "$folder", "videosRemaining": $videos, '
    '"failure": "$failure", "failureFolder": "$failureFolder", '
    '"yielding": $yielding}';

/// The given status with the content search of issue #257 on.
String withContent(
  String status, {
  required int described,
  required int total,
  required int remaining,
  String waiting = "",
  int retryAt = 0,
}) =>
    status.replaceFirst(
        RegExp(r'\}$'),
        ', "contentEnabled": true, "contentDescribed": $described, '
        '"contentTotal": $total, "contentRemaining": $remaining, '
        '"contentWaiting": "$waiting", "contentRetryAt": $retryAt}');

/// A status of a space whose albums are all prepared, and nothing runs.
String idleStatus() => statusOf(
    done: 40,
    step: "IDLE",
    folder: "",
    videos: 0,
    failure: "",
    failureFolder: "");

/// Pumps the section alone, showing what the given transport answers.
Future<void> pumpSection(WidgetTester tester, http.Client transport) async {
  CatchUpStatus? status;
  String? problem;
  var answer = await transport.get(Uri.parse("${serverUrl}data/?type=status"));
  if (answer.statusCode < 300) {
    status = CatchUpStatus.read(JsonReader.fromString(answer.body));
  } else {
    problem = (Resource.read(JsonReader.fromString(answer.body)) as ErrorInfo)
        .message;
  }
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Scaffold(
        body: ListView(children: [
          CatchUpSection(status: status, problem: problem),
        ]),
      ),
    ),
  );
  await tester.pump();
  await tester.pump();
}

/// Pumps the whole settings screen of a device signed in as the given role.
Future<void> pumpSettings(
    WidgetTester tester, String role, List<http.Request> requests) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var settings = ServerSettings(
      store: InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol"));
  await settings.load();
  var transport = MockClient((request) async {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "auth") {
      return json(authOfUser(role));
    }
    if (query["type"] == "catch-up") {
      return json(statusOf());
    }
    if (query["type"] == "invitations") {
      return json('{"invitations": []}');
    }
    if (query["type"] == "devices") {
      return json('{"devices": []}');
    }
    if (query["type"] == "contacts") {
      return json('{"contacts": []}');
    }
    return json('{"users": []}');
  });
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('shows the progress in one line and the details on demand',
      (tester) async {
    await pumpSection(
        tester, MockClient((request) async => json(statusOf(yielding: true))));

    expect(find.text("Preparing the albums"), findsOneWidget);
    expect(find.text("Albums prepared: 12 of 40"), findsOneWidget);
    expect(find.byKey(catchUpDetailsKey), findsNothing);

    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.byKey(catchUpDetailsKey), findsOneWidget);
    expect(find.text("Now: Faces in 2024/2024-05-01 Zoo"), findsOneWidget);
    expect(find.text("Paused while photos are being shown to somebody."),
        findsOneWidget);
    expect(find.text("3 videos waiting"), findsOneWidget);
    expect(
        find.text(
            "Last failure in 2023/Broken: previews: broken.jpg: Cannot read."),
        findsOneWidget);
  });

  testWidgets('says when everything is prepared', (tester) async {
    await pumpSection(
        tester,
        MockClient((request) async => json(statusOf(
            done: 40,
            step: "IDLE",
            folder: "",
            videos: 0,
            failure: "",
            failureFolder: ""))));
    expect(find.text("All 40 albums are prepared"), findsOneWidget);
    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.text("Nothing to do right now."), findsOneWidget);
    expect(find.text("No video waiting"), findsOneWidget);
    expect(find.text("Nothing failed."), findsOneWidget);
  });

  testWidgets('names the content search and what waits for it (issue #257)',
      (tester) async {
    await pumpSection(
        tester,
        MockClient((request) async => json(
            statusOf(done: 40, step: "CONTENT", videos: 0).replaceFirst(
                '"videosRemaining": 0',
                '"videosRemaining": 0, "contentRemaining": 7'))));
    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.text("Now: Content search in 2024/2024-05-01 Zoo"),
        findsOneWidget);
    expect(
        find.text("7 photos waiting for the content search"), findsOneWidget);
    expect(find.text("All 40 albums are prepared"), findsNothing,
        reason: "Photos still wait to be described.");
  });

  testWidgets(
      'never says there is nothing to do while the content search waits for '
      'its embedder, and says why (issue #257)', (tester) async {
    var retryAt = DateTime.now()
        .add(const Duration(minutes: 3, seconds: 30))
        .millisecondsSinceEpoch;
    const reason = "the embedder service is being prepared: the model (190 MB) "
        "is being downloaded: 12 MB so far";
    await pumpSection(
        tester,
        MockClient((request) async => json(withContent(idleStatus(),
            described: 0,
            total: 18000,
            remaining: 18000,
            waiting: reason,
            retryAt: retryAt))));
    expect(find.text("All 40 albums are prepared"), findsNothing);
    expect(find.text("Content search: 0 of 18000 photos and videos described"),
        findsOneWidget);
    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.text("Nothing to do right now."), findsNothing);
    expect(
        find.text("Nothing runs right now; work is waiting."), findsOneWidget);
    expect(find.text("Content search: 0 of 18000 photos and videos described"),
        findsNWidgets(2));
    expect(find.text("18000 photos waiting for the content search"),
        findsOneWidget);
    expect(find.text("The content search waits: $reason"), findsOneWidget);
    expect(
        find.text("The embedder is asked again in 4 minutes."), findsOneWidget);
  });

  testWidgets('shows how far the content search has got while it runs',
      (tester) async {
    await pumpSection(
        tester,
        MockClient((request) async => json(withContent(
            statusOf(done: 40, step: "CONTENT", videos: 0, failure: ""),
            described: 1234,
            total: 18000,
            remaining: 16766))));
    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.text("Now: Content search in 2024/2024-05-01 Zoo"),
        findsOneWidget);
    expect(
        find.text("Content search: 1234 of 18000 photos and videos described"),
        findsWidgets);
    expect(find.text("16766 photos waiting for the content search"),
        findsOneWidget);
    expect(find.textContaining("The content search waits"), findsNothing);
    expect(find.textContaining("asked again"), findsNothing);
  });

  testWidgets('says everything is prepared once every photo is described',
      (tester) async {
    await pumpSection(
        tester,
        MockClient((request) async => json(
            withContent(idleStatus(), described: 5, total: 5, remaining: 0))));
    expect(find.text("All 40 albums are prepared"), findsOneWidget);
    await tester.tap(find.byKey(catchUpLineKey));
    await tester.pumpAndSettle();
    expect(find.text("Nothing to do right now."), findsOneWidget);
    expect(find.text("Content search: 5 of 5 photos and videos described"),
        findsOneWidget);
    expect(find.textContaining("waiting for the content search"), findsNothing);
  });

  testWidgets('says the server\'s refusal', (tester) async {
    await pumpSection(
        tester,
        MockClient((request) async => json(
            '["ErrorInfo", {"message": "Shown to the administrator only."}]',
            status: 403)));
    expect(
        find.text(
            "The progress cannot be read: Shown to the administrator only."),
        findsOneWidget);
  });

  testWidgets(
      'an administrator finds the way to it in the server settings '
      '(issue #305)', (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "admin", requests);
    expect(find.byKey(catchUpLinkKey), findsOneWidget);
    expect(
        find.descendant(
            of: find.byKey(catchUpLinkKey),
            matching: find.text("Preparing the albums")),
        findsOneWidget);
    expect(find.text("Details on the status page"), findsWidgets);
    expect(requests.where((r) => r.url.queryParameters["type"] == "catch-up"),
        isEmpty,
        reason: "The settings ask nothing of it any more.");
  });

  testWidgets('a member sees nothing of it, and nothing is asked',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "edit", requests);
    expect(find.byKey(catchUpLinkKey), findsNothing);
    expect(find.text("Preparing the albums"), findsNothing);
    expect(requests.where((r) => r.url.queryParameters["type"] == "catch-up"),
        isEmpty);
  });
}
