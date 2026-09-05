/// Tests of the per-device token authentication (issue #28): the bearer header
/// the client sends, the reason a refusal carries into the app, the pairing
/// section of the server settings and the way a refused save or a refused root
/// load reaches the user.
library;

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

/// The message the server refuses an anonymous write with.
const String writeRefused =
    "This server requires a paired device for changes. "
    "Pair this device in the server settings.";

/// A client over a transport recording every request it is given.
VAlbumClient recording(
  List<http.BaseRequest> requests,
  http.Response Function(http.Request request) answer, {
  String? token,
}) =>
    VAlbumClient(
      dataUrl: "http://server/valbum/data",
      token: token,
      httpClient: MockClient((request) async {
        requests.add(request);
        return answer(request);
      }),
    );

/// An upload of a tiny file, so that the streamed request has a body.
UploadFile someFile() => UploadFile(
      name: "photo.jpg",
      length: 3,
      openRead: () => Stream.value([1, 2, 3]),
    );

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  // The screen scrolls; a tall surface builds the pairing section as well.
  await tester.binding.setSurfaceSize(const Size(800, 2000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
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

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  group('the bearer header', () {
    test('is sent on a GET when a token is set', () async {
      var requests = <http.BaseRequest>[];
      await recording(
        requests,
        (_) => http.Response(fixture("listing.json"), 200),
        token: "tok-1",
      ).loadResource([]);

      expect(requests.single.headers["Authorization"], "Bearer tok-1");
    });

    test('is absent while the app is unpaired', () async {
      var requests = <http.BaseRequest>[];
      await recording(
        requests,
        (_) => http.Response(fixture("listing.json"), 200),
      ).loadResource([]);

      expect(requests.single.headers.containsKey("Authorization"), isFalse);
    });

    test('is sent on a PUT beside the content type', () async {
      var requests = <http.BaseRequest>[];
      await recording(
        requests,
        (_) => http.Response("", 200),
        token: "tok-2",
      ).saveAlbum([], AlbumInfo(title: "Album"));

      expect(requests.single.headers["Authorization"], "Bearer tok-2");
      expect(
        requests.single.headers["content-type"],
        startsWith("application/json"),
      );
    });

    test('is sent on the streamed upload', () async {
      var requests = <http.BaseRequest>[];
      await recording(
        requests,
        (_) => http.Response("", 200),
        token: "tok-3",
      ).uploadFiles("http://server/valbum/data/album/", [someFile()]);

      expect(requests.single.headers["Authorization"], "Bearer tok-3");
    });

    test('is absent on an anonymous upload', () async {
      var requests = <http.BaseRequest>[];
      await recording(requests, (_) => http.Response("", 200))
          .uploadFiles("http://server/valbum/data/album/", [someFile()]);

      expect(requests.single.headers.containsKey("Authorization"), isFalse);
    });

    test('withToken keeps the data URL, withDataUrl keeps the token', () {
      var client = VAlbumClient(dataUrl: "http://a/data", token: "t");

      expect(client.withToken(null).token, isNull);
      expect(client.withToken(null).dataUrl, "http://a/data");
      expect(client.withDataUrl("http://b/data").token, "t");
    });
  });

  group('a refusal', () {
    test('surfaces the server message of a 401 on a read', () {
      expect(
        () => clientReturning(refusal(writeRefused), status: 401)
            .loadResource([]),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', writeRefused),
        ),
      );
    });

    test('surfaces the server message of a 401 on a write', () {
      expect(
        () => clientReturning(refusal(writeRefused), status: 401)
            .saveAlbum([], AlbumInfo()),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', writeRefused),
        ),
      );
    });

    test('surfaces the server message of a refused upload', () {
      expect(
        () => clientReturning(refusal(writeRefused), status: 401).uploadFiles(
          "http://server/valbum/data/album/",
          [someFile()],
        ),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', writeRefused),
        ),
      );
    });

    test('falls back to the status where there is no reason', () {
      expect(
        () => clientReturning("plain text", status: 403).loadResource([]),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', contains("HTTP 403")),
        ),
      );
    });
  });

  group('pair and authInfo', () {
    test('pairing posts the secret and returns the token', () async {
      var requests = <http.BaseRequest>[];
      var response = await recording(
        requests,
        (_) => http.Response('{"token":"abc","deviceName":"Phone"}', 200),
      ).pair("s3cret", "Phone");

      expect(response.token, "abc");
      expect(response.deviceName, "Phone");
      var request = requests.single as http.Request;
      expect(
        request.url.toString(),
        "http://server/valbum/data/?action=pair",
      );
      expect(request.method, "POST");
      expect(request.body, '{"secret":"s3cret","deviceName":"Phone"}');
    });

    test('a wrong secret carries the server message', () {
      expect(
        () => clientReturning(refusal("Wrong pairing secret."), status: 403)
            .pair("nope", "Phone"),
        throwsA(
          isA<VAlbumException>()
              .having((e) => e.message, 'message', "Wrong pairing secret."),
        ),
      );
    });

    test('authInfo asks the auth endpoint with the token', () async {
      var requests = <http.BaseRequest>[];
      var info = await recording(
        requests,
        (_) => http.Response(
          '{"mode":"writes","deviceName":"Phone","writeAllowed":true}',
          200,
        ),
        token: "tok",
      ).authInfo();

      expect(info.mode, "writes");
      expect(info.deviceName, "Phone");
      expect(info.writeAllowed, isTrue);
      expect(
        requests.single.url.toString(),
        "http://server/valbum/data/?type=auth",
      );
      expect(requests.single.headers["Authorization"], "Bearer tok");
    });
  });

  group('the settings store', () {
    test('keeps the token beside the URL', () async {
      var store = InMemorySettingsStore();
      var settings = ServerSettings(store: store);
      await settings.load();

      expect(settings.paired, isFalse);

      await settings.pairedAs("tok", "Phone");
      expect(store.token, "tok");
      expect(store.deviceName, "Phone");

      var reloaded = ServerSettings(store: store);
      await reloaded.load();
      expect(reloaded.token, "tok");
      expect(reloaded.deviceName, "Phone");
      expect(reloaded.paired, isTrue);
    });

    test('a URL stored before issue #28 loads without a token', () async {
      var settings = ServerSettings(
        store: InMemorySettingsStore("http://nas.local:8080/valbum/"),
      );
      await settings.load();

      expect(settings.serverUrl, "http://nas.local:8080/valbum/");
      expect(settings.token, isNull);
      expect(settings.paired, isFalse);
    });

    test('another server forgets the token, the same one keeps it', () async {
      var store = InMemorySettingsStore("http://a/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();
      await settings.pairedAs("tok", "Phone");

      await settings.save("http://a/valbum/");
      expect(settings.token, "tok", reason: "the same server keeps its token");

      await settings.save("http://b/valbum/");
      expect(settings.token, isNull);
      expect(store.token, isNull);
    });

    test('a reset of the server forgets its token', () async {
      var store = InMemorySettingsStore("http://a/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();
      await settings.pairedAs("tok", "Phone");

      await settings.reset();

      expect(settings.serverUrl, isNull);
      expect(settings.token, isNull);
      expect(store.token, isNull);
    });
  });

  group('the pairing section of the settings screen', () {
    testWidgets('pairs the device and shows it', (tester) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();

      var requests = <http.BaseRequest>[];
      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          requests.add(request);
          return http.Response(
            '{"token":"tok-42","deviceName":"Kamera"}',
            200,
          );
        }),
      );

      expect(find.text("Not paired"), findsOneWidget);

      await tester.enterText(find.byKey(deviceNameFieldKey), "Kamera");
      await tester.enterText(find.byKey(pairingSecretFieldKey), "demo");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.text("Pair this device"));

      expect(store.token, "tok-42");
      expect(store.deviceName, "Kamera");
      expect(find.text("Paired as Kamera"), findsWidgets);
      expect(
        (requests.single as http.Request).body,
        '{"secret":"demo","deviceName":"Kamera"}',
      );
      // The secret is never kept on the device.
      expect(
        tester
            .widget<TextField>(find.byKey(pairingSecretFieldKey))
            .controller!
            .text,
        "",
      );
    });

    testWidgets('shows the server message for a wrong secret', (tester) async {
      var store = InMemorySettingsStore("http://server/valbum/");
      var settings = ServerSettings(store: store);
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient(
          (_) async => http.Response(refusal("Wrong pairing secret."), 403),
        ),
      );

      await tester.enterText(find.byKey(pairingSecretFieldKey), "guess");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.text("Pair this device"));

      expect(find.text("Wrong pairing secret."), findsOneWidget);
      expect(store.token, isNull);
      expect(find.text("Not paired"), findsOneWidget);
    });

    testWidgets('unpair forgets the token', (tester) async {
      var store = InMemorySettingsStore("http://server/valbum/", "tok", "Phone");
      var settings = ServerSettings(store: store);
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((_) async => http.Response("", 200)),
      );

      expect(find.text("Paired as Phone"), findsOneWidget);

      await tapVisible(tester, find.text("Unpair"));

      expect(store.token, isNull);
      expect(settings.paired, isFalse);
      expect(find.text("Not paired"), findsOneWidget);
    });

    testWidgets('the connection test reports the pairing status',
        (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore("http://server/valbum/", "tok", "Phone"),
      );
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          if (request.url.query == "type=auth") {
            expect(request.headers["Authorization"], "Bearer tok");
            return http.Response(
              '{"mode":"writes","deviceName":"Phone","writeAllowed":true}',
              200,
            );
          }
          return http.Response(fixture("listing.json"), 200);
        }),
      );

      await tapVisible(tester, find.text("Test connection"));

      expect(find.text("Test-album"), findsOneWidget);
      expect(find.text("Paired as Phone"), findsWidgets);
    });

    testWidgets('the connection test says when pairing is missing',
        (tester) async {
      var settings = ServerSettings(
        store: InMemorySettingsStore("http://server/valbum/"),
      );
      await settings.load();

      await pumpSettings(
        tester,
        settings,
        MockClient((request) async {
          if (request.url.query == "type=auth") {
            return http.Response(
              '{"mode":"writes","deviceName":"","writeAllowed":false}',
              200,
            );
          }
          return http.Response(fixture("listing.json"), 200);
        }),
      );

      await tapVisible(tester, find.text("Test connection"));

      expect(
        find.text("Not paired - changes need pairing"),
        findsOneWidget,
      );
    });
  });

  group('the app shows a refusal', () {
    testWidgets('a refused save names the server reason', (tester) async {
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response(refusal(writeRefused), 401)
            : http.Response(fixture("album.json"), 200),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      expect(find.byType(SnackBar), findsOneWidget);
      expect(find.textContaining(writeRefused), findsOneWidget);
      // The edit mode stays open: nothing was saved.
      expect(find.byIcon(Icons.save), findsOneWidget);
    });

    testWidgets('a 401 on the root load offers the server settings',
        (tester) async {
      var client = clientReturning(
        refusal("This server requires a paired device."),
        status: 401,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
      });

      expect(
        find.textContaining("This server requires a paired device."),
        findsOneWidget,
      );

      await withFakeImageHttp(() async {
        await tester.tap(find.text("Server settings..."));
        await tester.pumpAndSettle();
      });

      expect(find.text("Album server"), findsOneWidget);
      expect(find.byKey(serverUrlFieldKey), findsOneWidget);
    });
  });
}
