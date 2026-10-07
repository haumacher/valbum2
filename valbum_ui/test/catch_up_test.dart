/// Tests of the administrator's line showing how far the server has got
/// preparing the albums in the background (issue #236): the progress in one
/// line, the details below it, a refresh while the screen is open, and nothing
/// at all for a caller who is no administrator.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/catch_up.dart';
import 'package:valbum_ui/client.dart';
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

/// Pumps the section alone, asking the given transport.
Future<void> pumpSection(WidgetTester tester, http.Client transport,
    {Duration interval = const Duration(seconds: 5)}) async {
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Scaffold(
        body: ListView(children: [
          CatchUpSection(
            client: VAlbumClient(
                dataUrl: "${serverUrl}data", httpClient: transport),
            interval: interval,
          ),
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

  testWidgets('asks again while it is on screen, and stops when it is gone',
      (tester) async {
    var asked = 0;
    await pumpSection(
      tester,
      MockClient((request) async {
        asked++;
        return json(statusOf(done: asked, total: 10));
      }),
      interval: const Duration(seconds: 2),
    );
    expect(find.text("Albums prepared: 1 of 10"), findsOneWidget);

    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(find.text("Albums prepared: 2 of 10"), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    var before = asked;
    await tester.pump(const Duration(seconds: 10));
    expect(asked, before, reason: "Nothing is asked once the screen is left.");
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

  testWidgets('an administrator finds it in the server settings',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "admin", requests);
    expect(find.byKey(catchUpLineKey), findsOneWidget);
    expect(find.text("Albums prepared: 12 of 40"), findsOneWidget);
    expect(requests.where((r) => r.url.queryParameters["type"] == "catch-up"),
        isNotEmpty);
  });

  testWidgets('a member sees nothing of it, and nothing is asked',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "edit", requests);
    expect(find.byKey(catchUpLineKey), findsNothing);
    expect(find.text("Preparing the albums"), findsNothing);
    expect(requests.where((r) => r.url.queryParameters["type"] == "catch-up"),
        isEmpty);
  });
}
