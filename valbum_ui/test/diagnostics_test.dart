/// Tests of the diagnostics log of issue #58: what is written down for every
/// request, what is masked, how much is kept, and what the connection test
/// records about a name that will not resolve.
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// The whole log as one block of text, without its header.
String messagesOf(DiagnosticsLog log) =>
    log.entries.map((entry) => entry.message).join("\n");

void main() {
  group('the request log', () {
    test('records method, URL and status of an answer', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient(
          (_) async => http.Response(refusal("Sign in first."), 401),
        ),
      );

      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>()),
      );

      expect(
        messagesOf(log),
        allOf(
          contains("GET"),
          contains("http://server/valbum/data/?type=json"),
          contains("-> 401"),
        ),
      );
    });

    test('records the complete text of a transport failure', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "https://home.haumacher.de/valbum/data",
        log: log,
        httpClient: MockClient((_) async {
          throw const SocketException(
            "Failed host lookup: 'home.haumacher.de'",
            osError: OSError("No address associated with hostname", -5),
          );
        }),
      );

      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>()),
      );

      expect(
        messagesOf(log),
        allOf(
          contains("GET"),
          contains("https://home.haumacher.de/valbum/data/?type=json"),
          contains("!!"),
          // The point of the whole issue: the OS message and its errno.
          contains("Failed host lookup: 'home.haumacher.de'"),
          contains("No address associated with hostname"),
          contains("errno = -5"),
        ),
      );
    });

    test('every request lands in it, not only the listing', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient(
          (_) async => http.Response('{"token":"t","deviceName":"d"}', 200),
        ),
      );

      await client.pair(deviceName: "Phone", deviceCode: "ABCD-EFGH");

      expect(
        messagesOf(log),
        allOf(contains("POST"), contains("action=pair"), contains("-> 200")),
      );
      // Never the body: it carries the sign-in code.
      expect(messagesOf(log), isNot(contains("ABCD-EFGH")));
    });

    test('drops the oldest entries beyond its capacity', () {
      var log = DiagnosticsLog(capacity: 3);

      for (var i = 1; i <= 5; i++) {
        log.add("entry $i");
      }

      expect(log.entries.length, 3);
      expect(
        log.entries.map((entry) => entry.message).toList(),
        ["entry 3", "entry 4", "entry 5"],
      );
    });
  });

  group('the copied text', () {
    test('starts with a header naming app, platform, server and time', () {
      var log = DiagnosticsLog()..add("something happened");

      var text = log.copyText(
        serverUrl: "http://nas.local:8080/valbum/",
        platform: "android 14",
        version: "9.9.9+9",
        at: DateTime.utc(2026, 9, 13, 10, 30),
      );

      expect(text, startsWith("VAlbum diagnostics"));
      expect(text, contains("App: 9.9.9+9"));
      expect(text, contains("Platform: android 14"));
      expect(text, contains("Server: http://nas.local:8080/valbum/"));
      expect(text, contains("Copied: 2026-09-13T10:30:00.000Z"));
      expect(text, contains("something happened"));
    });

    test('never carries a bearer token, an invitation or a share link',
        () async {
      const bearer = "device-token-abcdef";
      const invitation = "invitation-token-123456";
      const share = "share-token-7890ab";

      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: bearer,
        log: log,
        httpClient: MockClient((_) async => http.Response("{}", 200)),
      );
      await client.authInfo();
      // The two URL shapes that carry a secret in their path.
      log.answered("GET", "http://server/valbum/i/$invitation/", status: 200);
      log.answered("GET", "http://server/valbum/s/$share/", status: 200);

      var text = log.copyText(serverUrl: "http://server/valbum/");

      expect(text, isNot(contains(bearer)));
      expect(text, contains("(bearer)"), reason: "That there was one is said.");
      expect(text, isNot(contains(invitation)));
      expect(text, isNot(contains(share)));
      // Masked, not dropped: which link it was is still recognisable.
      expect(text, contains("/valbum/i/in"));
      expect(text, contains("/valbum/s/sh"));
    });
  });

  group('the diagnostics section of the settings', () {
    testWidgets('is collapsed, shows the log and copies it with its header',
        (tester) async {
      var log = DiagnosticsLog()..add("GET http://server/valbum/data -> 401");
      var settings = ServerSettings(
        store: InMemorySettingsStore("http://server/valbum/"),
      );
      await settings.load();

      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == "Clipboard.setData") {
            copied = (call.arguments as Map)["text"] as String;
          }
          return null;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );

      await tester.binding.setSurfaceSize(const Size(800, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          home: ServerSettingsScreen(
            settings: settings,
            diagnostics: log,
            clientFor: (dataUrl) => VAlbumClient(dataUrl: dataUrl, log: log),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Collapsed: the settings screen keeps its shape for everyone else.
      expect(find.text("Diagnostics"), findsOneWidget);
      expect(find.byKey(diagnosticsCopyKey), findsNothing);

      await tester.ensureVisible(find.text("Diagnostics"));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Diagnostics"));
      await tester.pumpAndSettle();

      expect(
        find.textContaining("GET http://server/valbum/data -> 401"),
        findsOneWidget,
      );

      await tester.ensureVisible(find.byKey(diagnosticsCopyKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(diagnosticsCopyKey));
      await tester.pumpAndSettle();

      expect(copied, isNotNull);
      expect(copied, startsWith("VAlbum diagnostics"));
      expect(copied, contains("Server: http://server/valbum/"));
      expect(copied, contains("GET http://server/valbum/data -> 401"));
      expect(
        find.text("The diagnostics log is on the clipboard."),
        findsOneWidget,
      );

      await tester.tap(find.byKey(diagnosticsClearKey));
      await tester.pumpAndSettle();
      expect(log.isEmpty, isTrue);
    });
  });

  group('the connection test', () {
    test('logs the data URL, both lookups, the root and the auth outcome',
        () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://localhost:9090/valbum/data",
        log: log,
        httpClient: MockClient((request) async {
          if (request.url.query == "type=auth") {
            return http.Response(
              '{"mode":"all","deviceName":"","writeAllowed":false}',
              200,
            );
          }
          return http.Response(refusal("Sign in first."), 401);
        }),
      );

      var result = await testServerConnection(client);

      expect(result.ok, isTrue);
      var messages = messagesOf(log);
      expect(
        messages,
        contains("connection test: data URL http://localhost:9090/valbum/data"),
      );
      // What the two lookups answered is what issue #58 is after; whether they
      // succeed depends on the machine, so only that both were asked is
      // asserted here.
      expect(messages, contains("lookup IPv4 localhost"));
      expect(messages, contains("lookup IPv6 localhost"));
      expect(messages, contains("connection test: root reached"));
      expect(
        messages,
        contains("connection test: auth Not signed in - this server shows "
            "nothing without a sign-in"),
      );
    });
  });
}
