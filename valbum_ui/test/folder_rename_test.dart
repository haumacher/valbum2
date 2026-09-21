/// The app half of issue #130: writing a folder's properties renames its
/// folder, and the app goes where the folder now is instead of reloading an
/// address that has just become a 404.
///
/// The server answers every sidecar write with a `CreateResult` — the path the
/// folder really has and, where it was renamed, the sentence that says so.
/// What is tested here is that the app *follows* it: outside an edit session,
/// inside one, and for a folder of folders.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The data root the tests speak to.
const String dataUrl = "http://server/valbum/data";

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// An answer with the JSON content type the app expects.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// An album of the given title holding one picture.
String album(String title) =>
    '["AlbumInfo", {"title": "$title", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

/// A listing of the given title holding the given folders.
String listing(String title, List<String> folders) =>
    '["ListingInfo", {"path": "", "title": "$title", "folders": ['
    '${folders.map((n) => '{"name": "$n", "title": "$n", '
        '"effectiveDate": 0}').join(", ")}]}]';

/// What the server answers a sidecar write with when it renamed the folder.
String renamed(String path) =>
    '{"path":"$path","message":"Renamed to \'${path.split("/").last}\'."}';

/// A client answering [handler], recording every request it is given.
VAlbumClient recordingClient(
  http.Response Function(http.Request request) handler,
  List<http.Request> requests,
) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

Future<void> pump(
  WidgetTester tester,
  VAlbumClient client, {
  List<String> route = const [],
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      initialRoute: ListingOrAlbumRoute(route),
    ));
    await tester.pumpAndSettle();
  });
}

/// Opens the entry of the three-dots menu carrying the given key.
Future<void> tapEntry(WidgetTester tester, String key) =>
    _tapMenu(tester, find.byKey(Key(key)));

/// Opens the entry of the three-dots menu reading the given text.
Future<void> tapEntryText(WidgetTester tester, String text) =>
    _tapMenu(tester, find.text(text));

Future<void> _tapMenu(WidgetTester tester, Finder entry) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert).last);
    await tester.pumpAndSettle();
    await tester.tap(entry);
    await tester.pumpAndSettle();
  });
}

void main() {
  group('the client', () {
    test('saveAlbum answers where the album is now', () async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => json(renamed("A/Journey")),
        requests,
      );

      var result =
          await client.saveAlbum(const ["A", "Trip"], AlbumInfo(title: "x"));

      expect(requests.single.method, "PUT");
      expect(pathOf(requests.single), "/valbum/data/A/Trip/");
      expect(result.path, "A/Journey");
      expect(result.message, "Renamed to 'Journey'.");
    });

    test('saveAlbum reads an older server\'s empty answer as "here"',
        () async {
      var requests = <http.Request>[];
      var client = recordingClient((request) => json(""), requests);

      var result =
          await client.saveAlbum(const ["A", "Trip"], AlbumInfo(title: "x"));

      expect(result.path, "A/Trip");
      expect(result.message, isEmpty);
    });

    test('saveListing answers where the folder is now', () async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => json(renamed("Travels")),
        requests,
      );

      var result = await client
          .saveListing(const ["Reisen"], ListingInfo(title: "Travels"));

      expect(result.path, "Travels");
      expect(result.message, "Renamed to 'Travels'.");
    });

    test('a refused write is the server\'s own refusal', () async {
      var client = recordingClient(
        (request) => json('["ErrorInfo",{"message":"Taken."}]', status: 409),
        <http.Request>[],
      );

      expect(
        () => client.saveAlbum(const ["A", "Trip"], AlbumInfo(title: "x")),
        throwsA(isA<VAlbumException>()
            .having((e) => e.status, "status", 409)
            .having((e) => e.message, "message", "Taken.")),
      );
    });
  });

  group('the album properties outside an edit session', () {
    testWidgets('land on the new path and say the new name', (tester) async {
      var requests = <http.Request>[];
      var written = false;
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          written = true;
          return json(renamed("Zoo"));
        }
        switch (pathOf(request)) {
          case "/valbum/data/Zoo/":
            return json(album("Zoo"));
          case "/valbum/data/Trip/":
            return written
                ? json('["ErrorInfo",{"message":"Gone."}]', status: 404)
                : json(album("Trip"));
          case "/valbum/data/":
            return json(listing("Library", written ? ["Zoo"] : ["Trip"]));
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      }, requests);
      await pump(tester, client, route: const ["Trip"]);

      await tapEntry(tester, "album-properties");
      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextField).first, "Zoo");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      // The album the app now shows is the one at the new address.
      expect(
        [
          for (var request in requests)
            if (request.method == "GET" &&
                request.url.queryParameters["type"] == "json")
              pathOf(request),
        ],
        contains("/valbum/data/Zoo/"),
      );
      expect(find.text("Renamed to 'Zoo'."), findsOneWidget);
      expect(find.text("Zoo"), findsWidgets);
    });

    testWidgets('only reload where nothing was renamed', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          // The composition did not change: no rename, no message.
          return json('{"path":"Trip","message":""}');
        }
        if (pathOf(request) == "/valbum/data/Trip/") {
          return json(album("Trip"));
        }
        return json(listing("Library", const ["Trip"]));
      }, requests);
      await pump(tester, client, route: const ["Trip"]);

      await tapEntry(tester, "album-properties");
      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextField).last, "A subtitle");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(find.byType(SnackBar), findsNothing,
          reason: "nothing happened, so nothing is said");
    });
  });

  group('saving an edit session', () {
    testWidgets('lands on the new path when the title changed',
        (tester) async {
      var requests = <http.Request>[];
      var written = false;
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          written = true;
          return json(renamed("Zoo"));
        }
        switch (pathOf(request)) {
          case "/valbum/data/Zoo/":
            return json(album("Zoo"));
          case "/valbum/data/Trip/":
            return written
                ? json('["ErrorInfo",{"message":"Gone."}]', status: 404)
                : json(album("Trip"));
          case "/valbum/data/":
            return json(listing("Library", written ? ["Zoo"] : ["Trip"]));
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      }, requests);
      await pump(tester, client, route: const ["Trip"]);

      // The long press opens the edit session; the properties then go into
      // its buffer and are written when the session is saved.
      await withFakeImageHttp(() async {
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
      });
      await tapEntry(tester, "album-properties");
      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextField).first, "Zoo");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      expect(requests.where((r) => r.method == "PUT"), hasLength(1));
      expect(
        [
          for (var request in requests)
            if (request.method == "GET" &&
                request.url.queryParameters["type"] == "json")
              pathOf(request),
        ],
        contains("/valbum/data/Zoo/"),
      );
      expect(find.text("Renamed to 'Zoo'."), findsOneWidget);
    });
  });

  group('the folder properties of a listing', () {
    testWidgets('land on the new path and say the new name', (tester) async {
      var requests = <http.Request>[];
      var written = false;
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          written = true;
          return json(renamed("Travels"));
        }
        switch (pathOf(request)) {
          case "/valbum/data/Travels/":
            return json(listing("Travels", const []));
          case "/valbum/data/Reisen/":
            return written
                ? json('["ErrorInfo",{"message":"Gone."}]', status: 404)
                : json(listing("Reisen", const []));
          case "/valbum/data/":
            return json(listing("Library", written ? ["Travels"] : ["Reisen"]));
        }
        return http.Response("No such resource: ${pathOf(request)}", 404);
      }, requests);
      await pump(tester, client, route: const ["Reisen"]);

      await tapEntryText(tester, "Folder properties");
      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextField).first, "Travels");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(
        [
          for (var request in requests)
            if (request.method == "GET" &&
                request.url.queryParameters["type"] == "json")
              pathOf(request),
        ],
        contains("/valbum/data/Travels/"),
      );
      expect(find.text("Renamed to 'Travels'."), findsOneWidget);
    });
  });
}
