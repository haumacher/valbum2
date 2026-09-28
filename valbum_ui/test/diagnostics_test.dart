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
import 'util/l10n.dart';

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// The whole log as one block of text, without its header.
String messagesOf(DiagnosticsLog log) =>
    log.entries.map((entry) => entry.message).join("\n");

void main() {
  group('the request log', () {
    test('records nothing for a request that succeeds (issue #184)', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient(
          (_) async => http.Response('["ListingInfo",{"title":"A"}]', 200),
        ),
      );

      await client.loadResource(const []);
      try {
        await client.authInfo();
      } catch (_) {
        // Refused or unreadable; what the log holds is the question.
      }

      expect(log.isEmpty, isTrue);
      expect(log.entries, isEmpty);
    });

    test('records a refusal as one entry with all its facts', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "secret-token",
        log: log,
        httpClient: MockClient(
          (_) async => http.Response(
            refusal("Sign in first."),
            401,
            headers: const {"content-type": "application/json"},
            reasonPhrase: "Unauthorized",
          ),
        ),
      );

      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>()
            // The refusal still reaches the caller, body and all.
            .having((e) => e.message, "message", contains("Sign in first."))),
      );

      expect(log.entries, hasLength(1));
      var entry = log.entries.single;
      expect(
        entry.headline,
        "Could not load the album listing: the server answered "
        "401 Unauthorized",
      );
      expect(entry.facts, [
        "Request: GET http://server/valbum/data/?type=json (with bearer)",
        "Server answered: 401 Unauthorized, Content-Type application/json, "
            "Content-Length ${refusal("Sign in first.").length}",
        "Server said: Sign in first.",
      ]);
      expect(entry.message, isNot(contains("secret-token")));
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

      expect(log.entries, hasLength(1));
      expect(
        messagesOf(log),
        allOf(
          contains("Could not load the album listing: no answer from the "
              "server"),
          contains("Request: GET "
              "https://home.haumacher.de/valbum/data/?type=json"),
          // The point of issue #58: the OS message and its errno.
          contains("Failed host lookup: 'home.haumacher.de'"),
          contains("No address associated with hostname"),
          contains("errno = -5"),
        ),
      );
    });

    test('never records a request body', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient(
          (_) async => http.Response(refusal("That code is not known."), 401),
        ),
      );

      await expectLater(
        client.pair(deviceName: "Phone", deviceCode: "ABCD-EFGH"),
        throwsA(anything),
      );

      expect(
        messagesOf(log),
        allOf(
          contains("Could not sign this device in"),
          contains("action=pair"),
          contains("Server said: That code is not known."),
        ),
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

    test('writes the facts of an entry under its headline', () {
      var log = DiagnosticsLog(now: () => DateTime(2026, 9, 28, 10, 30, 5, 7))
        ..add("Something failed", ["Fact one", "Fact two"]);

      expect(
        log.entries.single.toString(),
        "10:30:05.007  Something failed\n"
        "              Fact one\n"
        "              Fact two",
      );
    });
  });

  group('a storm of one cause (issue #184)', () {
    /// A client whose server is away, over a log on a clock the test moves.
    ({VAlbumClient client, DiagnosticsLog log, void Function(Duration) tick})
        away() {
      var time = DateTime(2026, 9, 28, 19, 4, 11);
      var log = DiagnosticsLog(now: () => time);
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient((request) async =>
            throw http.ClientException("Connection refused", request.url)),
      );
      return (
        client: client,
        log: log,
        tick: (Duration step) => time = time.add(step),
      );
    }

    /// Asks for the thumbnails `p<from>.jpg` … `p<to - 1>.jpg`, 30 ms apart.
    Future<void> thumbnails(
      ({
        VAlbumClient client,
        DiagnosticsLog log,
        void Function(Duration) tick
      }) setup,
      int from,
      int to,
    ) async {
      for (var i = from; i < to; i++) {
        try {
          await setup.client.thumbnailBytes(
            "http://server/valbum/data/album/p$i.jpg",
          );
        } catch (_) {
          // What the log keeps is the point.
        }
        setup.tick(const Duration(milliseconds: 30));
      }
    }

    test('sixty thumbnails without an answer are one entry', () async {
      var setup = away();

      await thumbnails(setup, 0, 60);

      expect(setup.log.entries, hasLength(1));
      var entry = setup.log.entries.single;
      expect(entry.count, 60);
      expect(entry.headline,
          "Could not load the thumbnail of 'p0.jpg': no answer from the server");
      expect(entry.repetition, [
        "Repeated: 60 times, 19:04:11–19:04:12",
        "Affected: p0.jpg, p1.jpg, p2.jpg and 57 more",
      ]);
      // The first facts stay as they were.
      expect(entry.facts.first,
          "Request: GET http://server/valbum/data/album/p0.jpg?type=tn");
    });

    test('a video failure in the middle of it keeps its own entry', () async {
      var setup = away();

      await thumbnails(setup, 0, 30);
      setup.log.add(
        "Video could not be played: clip.mp4 in 'album'",
        const ["Platform error: code VideoError"],
        failureCause(
          attempt: "video rendition format",
          answer: "206 video/mp4",
          platformError: "VideoError",
        ),
        "clip.mp4",
      );
      await thumbnails(setup, 30, 60);

      var entries = setup.log.entries;
      expect(entries, hasLength(2));
      expect(entries[0].count, 60, reason: "the storm is still one entry");
      expect(entries[1].headline, startsWith("Video could not be played"));
      expect(entries[1].count, 1);
    });

    test('two different causes stay two entries', () async {
      var time = DateTime(2026, 9, 28, 19, 4, 11);
      var log = DiagnosticsLog(now: () => time);
      var refused = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient((_) async => http.Response(refusal("No."), 403)),
      );
      var away = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        log: log,
        httpClient: MockClient((request) async =>
            throw http.ClientException("Connection refused", request.url)),
      );

      for (var client in [refused, away, refused, away]) {
        try {
          await client.thumbnailBytes("http://server/valbum/data/album/a.jpg");
        } catch (_) {
          // What the log keeps is the point.
        }
      }

      expect(log.entries, hasLength(2));
      expect(log.entries[0].headline, contains("answered 403"));
      expect(log.entries[0].count, 2);
      expect(log.entries[1].headline, contains("no answer"));
      expect(log.entries[1].count, 2);
    });

    test('the same cause after the window is a new entry', () async {
      var setup = away();

      await thumbnails(setup, 0, 3);
      setup.tick(diagnosticsMergeWindow + const Duration(seconds: 1));
      await thumbnails(setup, 3, 5);

      expect(setup.log.entries.map((entry) => entry.count), [3, 2]);
    });

    test('the copy says how often and for what', () async {
      var setup = away();

      await thumbnails(setup, 0, 60);
      var text = setup.log.copyText(serverUrl: "http://server/valbum/");

      expect(text, contains("Problems: 1"));
      expect(
        text,
        contains("              Repeated: 60 times, 19:04:11–19:04:12\n"
            "              Affected: p0.jpg, p1.jpg, p2.jpg and 57 more"),
      );
    });
  });

  group('the copied text', () {
    test('starts with a header naming app, platform, server and time', () {
      var log = DiagnosticsLog()..add("something failed");

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
      expect(text, contains("Problems: 1"));
      expect(text, contains("something failed"));
    });

    test('says in one line that there are no problems', () {
      var text = DiagnosticsLog().copyText(
        serverUrl: "http://nas.local:8080/valbum/",
        platform: "web, Mozilla/5.0",
      );

      expect(text, contains("Platform: web, Mozilla/5.0"));
      expect(text, contains("Problems: 0"));
      expect(text.trimRight(), endsWith("No problems recorded."));
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
        httpClient: MockClient((_) async => http.Response(refusal("No."), 403)),
      );
      try {
        await client.authInfo();
      } catch (_) {
        // Refused or unreadable; what the log holds is the question.
      }
      // The two URL shapes that carry a secret in their path.
      log.refused("GET", "http://server/valbum/i/$invitation/", status: 410);
      log.failed(
        "GET",
        "http://server/valbum/s/$share/",
        error: "Connection refused",
      );

      var text = log.copyText(serverUrl: "http://server/valbum/");

      expect(text, isNot(contains(bearer)));
      expect(text, contains("(with bearer)"),
          reason: "That there was one is said.");
      expect(text, isNot(contains(invitation)));
      expect(text, isNot(contains(share)));
      // Masked, not dropped: which link it was is still recognisable.
      expect(text, contains("/valbum/i/in"));
      expect(text, contains("/valbum/s/sh"));
    });
  });

  group('what a request was for', () {
    test('is read off the query and the method', () {
      expect(requestPurpose("GET", "http://h/v/data/a/b.jpg?type=tn"),
          "load the thumbnail of 'b.jpg'");
      expect(requestPurpose("GET", "http://h/v/data/a/clip.mp4?type=video"),
          "load the playable version of 'clip.mp4'");
      expect(requestPurpose("GET", "http://h/v/data/a/b%20c.jpg"),
          "load the original of 'b c.jpg'");
      expect(requestPurpose("PUT", "http://h/v/data/Trip/"),
          "upload to or save the folder 'Trip'");
      expect(requestPurpose("POST", "http://h/v/data/Trip/?action=check"),
          "ask which photos the server already has");
      expect(requestPurpose("POST", "http://h/v/data/Trip/?action=purge"),
          "carry out 'purge'");
      expect(requestPurpose("GET", "http://h/v/s/secrettoken/"),
          isNot(contains("secrettoken")));
    });
  });

  group('the diagnostics section of the settings', () {
    testWidgets('is collapsed, shows the log and copies it with its header',
        (tester) async {
      var log = DiagnosticsLog();
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
          localizationsDelegates: testLocalizationsDelegates,
          supportedLocales: testSupportedLocales,
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

      // Empty: one line saying so (issue #184).
      expect(find.text("No problems recorded."), findsOneWidget);

      log.add("Could not load the album listing: the server answered 401", [
        "Request: GET http://server/valbum/data/?type=json",
      ]);
      await tester.pumpAndSettle();

      expect(find.text("No problems recorded."), findsNothing);
      expect(
        find.textContaining("Could not load the album listing"),
        findsOneWidget,
      );

      await tester.ensureVisible(find.byKey(diagnosticsCopyKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(diagnosticsCopyKey));
      await tester.pumpAndSettle();

      expect(copied, isNotNull);
      expect(copied, startsWith("VAlbum diagnostics"));
      expect(copied, contains("Server: http://server/valbum/"));
      expect(copied, contains("Could not load the album listing"));
      expect(copied, contains("Request: GET http://server/valbum/data/"));
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
    test('writes nothing where the server is reached', () async {
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

      var result = await testServerConnection(testL10n, client);

      expect(result.ok, isTrue);
      // The 401 of the root listing is the transport's one refusal; the
      // test itself, which succeeded, adds nothing.
      expect(log.entries, hasLength(1));
      expect(messagesOf(log), isNot(contains("Connection test")));
    });

    test('writes one entry with the data URL, both lookups, the root and the '
        'auth outcome where it fails', () async {
      var log = DiagnosticsLog();
      var client = VAlbumClient(
        dataUrl: "http://localhost:9090/valbum/data",
        log: log,
        httpClient: MockClient((request) async {
          throw const SocketException(
            "Connection refused",
            osError: OSError("Connection refused", 111),
          );
        }),
      );

      var result = await testServerConnection(testL10n, client);

      expect(result.ok, isFalse);
      var tests = log.entries
          .where((entry) => entry.headline.startsWith("Connection test failed"))
          .toList();
      expect(tests, hasLength(1));
      var facts = tests.single.facts.join("\n");
      expect(facts, contains("Data URL: http://localhost:9090/valbum/data"));
      // What the two lookups answered is what issue #58 is after; whether they
      // succeed depends on the machine, so only that both were asked is
      // asserted here.
      expect(facts, contains("Lookup IPv4 localhost"));
      expect(facts, contains("Lookup IPv6 localhost"));
      expect(facts, contains("Root listing: "));
      expect(facts, contains("Sign-in: no answer"));
      expect(facts, contains("Platform: "));
    });
  });
}
