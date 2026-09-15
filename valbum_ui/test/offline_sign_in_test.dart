/// Tests of the sign-in lock-out of issue #57: what "the server was reached"
/// means, who is allowed to decide it, and that a sign-in at the server in the
/// field is never refused because another server is away.
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The body a refusing server answers with, see `ErrorInfo` in `model.proto`.
String refusal(String message) => '["ErrorInfo",{"message":"$message"}]';

/// The sentence a server started with `--auth all` refuses a stranger with.
const String signInFirst = "Sign in on this device to see this library.";

/// The "Sign in" button; the section carries the same title.
final Finder signInButton = find.widgetWithText(FilledButton, "Sign in");

void main() {
  group('the lock-out of issue #57', () {
    testWidgets(
        'signs in at the entered server while the stored one is unreachable',
        (tester) async {
      // The screen scrolls; a tall surface builds the sign-in section too.
      await tester.binding.setSurfaceSize(const Size(800, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      var store = InMemorySettingsStore("http://stored/valbum/");
      var settings = ServerSettings(store: store, platformDefault: () => null);
      await settings.load();
      var state = OfflineState();

      var transport = MockClient(servingThumbnails((request) async {
        if (request.url.host == "stored") {
          // The stored server does not resolve at all: this is what puts the
          // app into the offline state.
          throw http.ClientException(
            "Failed host lookup: 'stored'",
            request.url,
          );
        }
        if (request.url.query == "action=pair") {
          return http.Response(
            '{"token":"tok-57","deviceName":"Phone","userName":"haui",'
            '"role":"admin","space":""}',
            200,
          );
        }
        // The entered server speaks, and refuses everything anonymous
        // (`--auth all`).
        return http.Response(refusal(signInFirst), 401);
      }));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(
            client: VAlbumClient(
              dataUrl: "http://stored/valbum/data",
              httpClient: transport,
            ),
            settings: settings,
            cache: MemoryOfflineCache(),
            offlineState: state,
          ),
        );
        await tester.pumpAndSettle();
      });

      // Step 1 of the report: the app is offline, because the stored server
      // cannot be reached and nothing is cached.
      expect(state.offline, isTrue);

      await withFakeImageHttp(() async {
        await tester.tap(find.text("Server settings..."));
        await tester.pumpAndSettle();
      });

      // Step 2 and 3: another server is entered and this device signs in at
      // it — the offline flag describes the *stored* server and has no say.
      await tester.enterText(
        find.byKey(serverUrlFieldKey),
        "http://homepi:8082/valbum/",
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
    // The secret names the space's administrator, so a name is required
    // (issue #86).
    await tester.enterText(find.byKey(userNameFieldKey), "haui");
      await tester.enterText(find.byKey(deviceNameFieldKey), "Phone");
      await tester.pumpAndSettle();

      await withFakeImageHttp(() async {
        await tester.ensureVisible(signInButton);
        await tester.pumpAndSettle();
        await tester.tap(signInButton);
        await tester.pumpAndSettle();
      });

      expect(store.token, "tok-57", reason: "The sign-in went through.");
      expect(store.deviceName, "Phone");
      expect(find.text(offlineRefusal), findsNothing);
    });

    testWidgets('the connection test reads a 401 as a server that answered',
        (tester) async {
      var state = OfflineState()..goneOffline(null);
      var client = VAlbumClient(
        dataUrl: "http://homepi:8082/valbum/data",
        offlineState: state,
        httpClient: MockClient((request) async {
          if (request.url.query == "type=auth") {
            return http.Response(
              '{"mode":"all","deviceName":"","writeAllowed":false}',
              200,
            );
          }
          return http.Response(refusal(signInFirst), 401);
        }),
      );

      var result = await testServerConnection(client);

      expect(result.ok, isTrue, reason: "The server answered, so it is there.");
      expect(result.message, contains("Album server reached"));
      expect(result.message, contains(signInFirst));
      expect(
        result.authStatus,
        "Not signed in - this server shows nothing without a sign-in",
      );
      expect(state.offline, isFalse, reason: "An answer is not being offline.");
    });
  });

  group('what counts as reached', () {
    test('a 403 clears the offline state, a transport failure sets it again',
        () async {
      var state = OfflineState()..goneOffline(null);
      var reachable = true;
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "tok",
        cache: MemoryOfflineCache(),
        offlineState: state,
        httpClient: MockClient((request) async {
          if (!reachable) {
            throw const SocketException(
              "Failed host lookup: 'server'",
              osError: OSError("No address associated with hostname", -5),
            );
          }
          return http.Response(refusal("You may not see this folder."), 403);
        }),
      );

      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>().having((e) => e.status, "status", 403)),
      );
      expect(state.offline, isFalse, reason: "The server refused; it is there.");

      reachable = false;
      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>()),
      );
      expect(state.offline, isTrue, reason: "Nothing answered at all.");
    });

    test('a refused write is the server speaking, not being offline', () async {
      var state = OfflineState()..goneOffline(null);
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        offlineState: state,
        httpClient: MockClient(
          (_) async => http.Response(refusal("Sign in first."), 401),
        ),
      );

      await expectLater(
        client.saveAlbum(const ["a"], AlbumInfo(title: "A")),
        throwsA(isA<VAlbumException>()),
      );
      expect(state.offline, isFalse);
    });
  });
}
