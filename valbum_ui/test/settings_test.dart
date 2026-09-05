/// Tests of the server settings (issue #27): the screen editing the URL of
/// the album server, its connection test, the persistence of the value and
/// the client swap that follows saving it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The data URL the injected clients of these tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// Pumps the app with the given settings.
Future<void> pumpApp(
  WidgetTester tester, {
  VAlbumClient? client,
  ServerSettings? settings,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: client, settings: settings));
    await tester.pumpAndSettle();
  });
}

/// Settings with an in-memory store, as every test here uses them.
ServerSettings settingsWith(
  InMemorySettingsStore store, {
  String? platformDefault = serverDataUrl,
}) =>
    ServerSettings(store: store, platformDefault: () => platformDefault);

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpScreen(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.pumpWidget(
    MaterialApp(
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// Enters [url] into the server URL field.
Future<void> enter(WidgetTester tester, String url) async {
  await tester.enterText(find.byType(TextField), url);
  await tester.pumpAndSettle();
}

/// Taps the button carrying the given label.
Future<void> tapButton(WidgetTester tester, String label) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.text(label));
    await tester.pumpAndSettle();
  });
}

/// Opens the settings from the app-bar menu of the view shown.
Future<void> openSettingsFromMenu(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    await tester.tap(find.text("Server..."));
    await tester.pumpAndSettle();
  });
}

void main() {
  group('ServerSettings', () {
    test('the platform default applies while nothing is stored', () {
      var settings = settingsWith(InMemorySettingsStore());
      expect(settings.serverUrl, isNull);
      expect(settings.dataUrl, serverDataUrl);
      expect(settings.configured, isTrue);
    });

    test('no default and no stored value means no server', () {
      var settings = settingsWith(
        InMemorySettingsStore(),
        platformDefault: null,
      );
      expect(settings.dataUrl, isNull);
      expect(settings.configured, isFalse);
    });

    test('a stored value overrides the default and survives a reset', () async {
      var store = InMemorySettingsStore();
      var settings = settingsWith(store);

      await settings.load();
      expect(settings.dataUrl, serverDataUrl);

      await settings.save("http://nas.local:8080/valbum/");
      expect(store.value, "http://nas.local:8080/valbum/");
      expect(settings.dataUrl, "http://nas.local:8080/valbum/data");

      await settings.reset();
      expect(store.value, isNull);
      expect(settings.dataUrl, serverDataUrl);
    });
  });

  group('the settings screen', () {
    testWidgets('opens from the app-bar menu of the root listing',
        (tester) async {
      await pumpApp(
        tester,
        client: clientReturning(fixture("listing.json")),
        settings: settingsWith(InMemorySettingsStore()),
      );

      // The listing loads from the client injected into the app.
      expect(find.text("Test-album"), findsOneWidget);

      await openSettingsFromMenu(tester);

      expect(find.text("Album server"), findsOneWidget);
      expect(find.byType(TextField), findsOneWidget);
    });

    testWidgets('pre-fills the server the app talks to', (tester) async {
      await pumpScreen(
        tester,
        settingsWith(InMemorySettingsStore()),
        MockClient((request) async => http.Response("", 200)),
      );

      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        "http://server/valbum/",
      );
    });

    testWidgets('refuses a URL that is not a server address', (tester) async {
      var store = InMemorySettingsStore();
      await pumpScreen(
        tester,
        settingsWith(store),
        MockClient((request) async => http.Response("", 200)),
      );

      await enter(tester, "nas.local");
      await tapButton(tester, "Save");

      expect(find.textContaining("absolute server URL"), findsOneWidget);
      expect(store.value, isNull);
    });
  });

  group('the connection test', () {
    /// Runs a connection test against a transport answering with [answer].
    Future<InMemorySettingsStore> runTest(
      WidgetTester tester,
      Future<http.Response> Function(http.Request request) answer, {
      List<http.Request>? requests,
    }) async {
      var store = InMemorySettingsStore();
      await pumpScreen(
        tester,
        settingsWith(store),
        MockClient((request) async {
          requests?.add(request);
          return answer(request);
        }),
      );

      await enter(tester, "http://probe.host:8080/valbum/");
      await tapButton(tester, "Test connection");
      return store;
    }

    testWidgets('shows the title of the root listing on success',
        (tester) async {
      var requests = <http.Request>[];
      var store = await runTest(
        tester,
        (_) async => http.Response(
          fixture("listing.json"),
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        ),
        requests: requests,
      );

      expect(find.text("Test-album"), findsOneWidget);
      // The entered server was asked, and nothing was saved.
      expect(
        requests.single.url.toString(),
        "http://probe.host:8080/valbum/data/?type=json",
      );
      expect(store.value, isNull);
    });

    testWidgets('shows "Album server reached" for an untitled root',
        (tester) async {
      await runTest(
        tester,
        (_) async => http.Response('["ListingInfo", {"path": ""}]', 200),
      );

      expect(find.text("Album server reached"), findsOneWidget);
    });

    testWidgets('names the HTTP status of a failing server', (tester) async {
      var store = await runTest(
        tester,
        (_) async => http.Response("not found", 404),
      );

      expect(find.textContaining("404"), findsOneWidget);
      expect(store.value, isNull);
    });

    testWidgets('shows the message of a connection failure', (tester) async {
      await runTest(
        tester,
        (_) async => throw http.ClientException("Connection refused"),
      );

      expect(find.textContaining("Connection refused"), findsOneWidget);
    });

    testWidgets('reports a server that is not a VAlbum server',
        (tester) async {
      await runTest(
        tester,
        (_) async => http.Response("<html>Hello</html>", 200),
      );

      expect(find.textContaining("not a VAlbum server"), findsOneWidget);
    });

    testWidgets('reports JSON that is no album resource', (tester) async {
      await runTest(
        tester,
        (_) async => http.Response('["Nonsense", {}]', 200),
      );

      expect(find.textContaining("not a VAlbum server"), findsOneWidget);
    });
  });

  group('saving', () {
    testWidgets('points the app at the new server and reloads the view',
        (tester) async {
      var requests = <http.Request>[];
      var store = InMemorySettingsStore();

      await pumpApp(
        tester,
        client: clientReturning(fixture("listing.json"), requests: requests),
        settings: settingsWith(store),
      );

      expect(requests.single.url.toString(), "$serverDataUrl/?type=json");

      await openSettingsFromMenu(tester);
      await enter(tester, "http://nas.local:8080/valbum/");
      await tapButton(tester, "Save");

      // The screen closed, the listing is shown again...
      expect(find.text("Album server"), findsNothing);
      expect(find.text("Test-album"), findsOneWidget);

      // ... loaded from the new server.
      expect(store.value, "http://nas.local:8080/valbum/");
      expect(
        requests.last.url.toString(),
        "http://nas.local:8080/valbum/data/?type=json",
      );
      expect(requests.length, 2);
    });
  });

  group('start-up', () {
    testWidgets('opens the settings when no server is configured',
        (tester) async {
      await pumpApp(
        tester,
        settings: settingsWith(
          InMemorySettingsStore(),
          platformDefault: null,
        ),
      );

      expect(find.text("Album server"), findsOneWidget);
      // Nothing to go back to: the screen is the app.
      expect(find.byType(BackButton), findsNothing);
    });

    testWidgets('loads the root listing from the stored server',
        (tester) async {
      var requests = <http.Request>[];

      await pumpApp(
        tester,
        client: clientReturning(fixture("listing.json"), requests: requests),
        settings: settingsWith(
          InMemorySettingsStore("http://stored.host:8080/valbum/"),
          platformDefault: null,
        ),
      );

      expect(find.text("Test-album"), findsOneWidget);
      expect(
        requests.single.url.toString(),
        "http://stored.host:8080/valbum/data/?type=json",
      );
    });
  });

  group('the reset action', () {
    testWidgets('clears the store and returns to the default', (tester) async {
      var store = InMemorySettingsStore("http://nas.local:8080/valbum/");
      var settings = settingsWith(store);
      await settings.load();

      await pumpScreen(
        tester,
        settings,
        MockClient((request) async => http.Response("", 200)),
      );

      await tapButton(tester, "Use the server this app was loaded from");

      expect(store.value, isNull);
      expect(settings.serverUrl, isNull);
      expect(settings.dataUrl, serverDataUrl);
    });

    testWidgets('is offered as "forget" where there is no default',
        (tester) async {
      var store = InMemorySettingsStore("http://nas.local:8080/valbum/");
      var settings = settingsWith(store, platformDefault: null);
      await settings.load();

      await pumpScreen(
        tester,
        settings,
        MockClient((request) async => http.Response("", 200)),
      );

      await tapButton(tester, "Forget this server");

      expect(store.value, isNull);
      expect(settings.configured, isFalse);
    });
  });
}
