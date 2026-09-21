/// Tests of the folder placement rule (issue #48): where a created album
/// lands, the folder properties that set the rule, and applying it once.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:intl/intl.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The paths the app asked the server for.
List<String> jsonGets(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          pathOf(request),
    ];

/// An answer with the JSON content type the app expects.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A root listing with the given placement rule and one folder.
String rootListing(String placement) => '''
["ListingInfo", {
  "path": "",
  "title": "Library",
  "placement": "$placement",
  "folders": [
    {"name": "2026", "title": "2026", "effectiveDate": 0}
  ]
}]''';

/// A client answering [handler], recording every request it is given.
VAlbumClient recordingClient(
  http.Response Function(http.Request request) handler,
  List<http.Request> requests,
) =>
    VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

/// Shows the root listing.
Future<void> pumpRoot(WidgetTester tester, VAlbumClient client) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: client));
    await tester.pumpAndSettle();
  });
}

/// Opens the given entry of the listing menu.
Future<void> openMenu(WidgetTester tester, String entry) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    await tester.tap(find.text(entry));
    await tester.pumpAndSettle();
  });
}

/// Fills in the "new album" dialog and creates the album.
Future<void> createAlbum(WidgetTester tester, String title) async {
  await openMenu(tester, "Create album");
  await withFakeImageHttp(() async {
    // The date field: the picker opens on today, and today is taken.
    await tester.tap(find.text(testL10n.dateLabel));
    await tester.pumpAndSettle();
    await tester.tap(find.text("OK"));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).first, title);
    await tester.tap(find.text(testL10n.create));
    await tester.pumpAndSettle();
  });
}

void main() {
  group('the client', () {
    test('createAlbum answers where the album landed', () async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => json('{"path":"2026/Lake","message":"filed in 2026/"}'),
        requests,
      );

      var result =
          await client.createAlbum(const ["a"], AlbumInfo(path: "Lake"));

      expect(requests.single.method, "PUT");
      expect(
          requests.single.url.toString(), "http://server/valbum/data/a/Lake/");
      expect(result.path, "2026/Lake");
      expect(result.message, "filed in 2026/");
    });

    test('createAlbum takes an empty answer for the asked path', () async {
      var client = recordingClient((request) => http.Response("", 200), []);

      var result =
          await client.createAlbum(const ["a"], AlbumInfo(path: "Lake"));

      expect(result.path, "a/Lake");
      expect(result.message, isEmpty);
    });

    test('place posts to the folder and reads the outcomes', () async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => json('{"outcomes":['
            '{"name":"Lake","newName":"2026/Lake","message":""}]}'),
        requests,
      );

      var result = await client.place(const ["a"]);

      expect(requests.single.method, "POST");
      expect(requests.single.url.toString(),
          "http://server/valbum/data/a/?action=place");
      expect(result.outcomes.single.newName, "2026/Lake");
    });

    test('place reports the server\'s reason for a refusal', () {
      var client = recordingClient(
        (request) => json('["ErrorInfo",{"message":"Nope."}]', status: 403),
        [],
      );

      expect(
        () => client.place(const []),
        throwsA(isA<VAlbumException>()
            .having((e) => e.message, "message", "Nope.")
            .having((e) => e.status, "status", 403)),
      );
    });
  });

  group('creating an album', () {
    testWidgets('goes to where the server filed it and says so',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          return json('{"path":"2026/2026-09-06 Lake",'
              '"message":"filed in 2026/"}');
        }
        if (pathOf(request) == "/valbum/data/2026/2026-09-06 Lake/") {
          return json(fixture("album-target.json"));
        }
        return json(rootListing("BY_YEAR"));
      }, requests);
      await pumpRoot(tester, client);

      await createAlbum(tester, "Lake");

      var put = requests.singleWhere((request) => request.method == "PUT");
      expect(put.body, contains('"title":"Lake"'));
      // The album is filed by the date the request carries.
      expect(put.body, isNot(contains('"date":0')));

      // The app went where the album landed, not where it asked for it.
      expect(
        jsonGets(requests),
        contains("/valbum/data/2026/2026-09-06 Lake/"),
      );
      // ... and that is the view on the screen.
      expect(find.text("2020 Trip"), findsOneWidget);
      // Nothing happens silently.
      expect(find.text("filed in 2026/"), findsOneWidget);
    });

    testWidgets('keeps working against a server that answers nothing',
        (tester) async {
      // The dialog names the folder after the day it is opened on.
      var askedName = "${DateFormat("yyyy-MM-dd").format(DateTime.now())} Lake";
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        if (pathOf(request) == "/valbum/data/$askedName/") {
          return json(fixture("album-target.json"));
        }
        return json(rootListing("NONE"));
      }, requests);
      await pumpRoot(tester, client);

      await createAlbum(tester, "Lake");

      // The album was created where it was asked for, so that is where the
      // app goes.
      expect(jsonGets(requests), contains("/valbum/data/$askedName/"));
      expect(find.text("2020 Trip"), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('shows the server\'s reason for a refused creation',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          return json('["ErrorInfo",{"message":"The name is taken."}]',
              status: 409);
        }
        return json(rootListing("NONE"));
      }, requests);
      await pumpRoot(tester, client);

      await createAlbum(tester, "Lake");

      expect(find.text("The name is taken."), findsOneWidget);
      // Nowhere was navigated to: the album was not created.
      expect(jsonGets(requests), ["/valbum/data/"]);
    });
  });

  group('creating an album without a date', () {
    testWidgets('asks for the title alone and is left where it is made',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          // The server files nothing that has no date, whatever the rule.
          return json('{"path":"Inbox","message":""}');
        }
        if (pathOf(request) == "/valbum/data/Inbox/") {
          return json(fixture("album-target.json"));
        }
        return json(rootListing("BY_YEAR"));
      }, requests);
      await pumpRoot(tester, client);

      // The dialog says what an undated album means, and does not insist.
      await openMenu(tester, "Create album");
      expect(find.byKey(const Key("create-album-date-hint")), findsOneWidget);
      expect(find.text("Muss angegeben werden."), findsNothing);
      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextFormField).first, "Inbox");
        await tester.tap(find.text(testL10n.create));
        await tester.pumpAndSettle();
      });

      var put = requests.singleWhere((request) => request.method == "PUT");
      // The folder name is the title alone -- no date in front of it.
      expect(put.url.toString(), "http://server/valbum/data/Inbox/");
      expect(put.body, contains('"title":"Inbox"'));
      var asked = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      // The path travels in the URL; the body says the date, and it is none.
      expect(asked.date, 0);

      // Where the server put it is where the app goes.
      expect(jsonGets(requests), contains("/valbum/data/Inbox/"));
    });

    testWidgets('still spells the folder name with the date that was picked',
        (tester) async {
      var askedName = "${DateFormat("yyyy-MM-dd").format(DateTime.now())} Lake";
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        if (pathOf(request) == "/valbum/data/$askedName/") {
          return json(fixture("album-target.json"));
        }
        return json(rootListing("BY_YEAR"));
      }, requests);
      await pumpRoot(tester, client);

      await createAlbum(tester, "Lake");

      var put = requests.singleWhere((request) => request.method == "PUT");
      expect(put.url.toString(),
          "http://server/valbum/data/${Uri.encodeComponent(askedName)}/");
      var asked = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect(asked.date, isNot(0));
    });
  });

  group('the folder properties', () {
    testWidgets('store the title and the placement rule', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => json(rootListing("NONE")),
        requests,
      );
      await pumpRoot(tester, client);

      await openMenu(tester, "Folder properties");
      expect(find.text(testL10n.folderProperties), findsOneWidget);
      // What the rule does is said, not guessed.
      expect(find.byKey(const Key("placement-explanation")), findsOneWidget);

      await withFakeImageHttp(() async {
        await tester.enterText(find.byType(TextField).first, "My library");
        await tester.tap(find.byKey(const Key("placement-byYear")));
        await tester.pumpAndSettle();
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var put = requests.singleWhere((request) => request.method == "PUT");
      expect(put.url.toString(), "http://server/valbum/data/");
      expect(put.body, contains('"placement":"BY_YEAR"'));
      expect(put.body, contains('"title":"My library"'));
      // The listing was fetched again after the write.
      expect(requests.last.method, "GET");
    });

    testWidgets('show the server\'s reason for a refused write',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "PUT") {
          return json('["ErrorInfo",{"message":"Not your folder."}]',
              status: 403);
        }
        return json(rootListing("NONE"));
      }, requests);
      await pumpRoot(tester, client);

      await openMenu(tester, "Folder properties");
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("placement-byYearMonth")));
        await tester.pumpAndSettle();
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      expect(find.text("Not your folder."), findsOneWidget);
      var snackBar = tester.widget<SnackBar>(find.byType(SnackBar));
      expect(snackBar.backgroundColor, Colors.red.shade700);
    });
  });

  group('applying the rule', () {
    testWidgets('is not offered by a folder without one', (tester) async {
      var client = recordingClient(
        (request) => json(rootListing("NONE")),
        [],
      );
      await pumpRoot(tester, client);

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
      });

      expect(find.text("Apply rule"), findsNothing);
      expect(find.text("Folder properties"), findsOneWidget);
    });

    testWidgets('files what it can and says what stayed', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "POST") {
          return json('{"outcomes":['
              '{"name":"2026-09-06 Lake","newName":"2026/2026-09-06 Lake",'
              '"message":""},'
              '{"name":"Trip","newName":"","message":"No date."}'
              ']}');
        }
        return json(rootListing("BY_YEAR"));
      }, requests);
      await pumpRoot(tester, client);

      await openMenu(tester, "Apply rule");

      var post = requests.singleWhere((request) => request.method == "POST");
      expect(post.url.toString(), "http://server/valbum/data/?action=place");

      // The refusal opens the dialog naming the entry and the server's reason.
      expect(find.byKey(const Key("move-outcome")), findsOneWidget);
      expect(find.text("Apply rule"), findsOneWidget);
      expect(find.text("Filed 1 album."), findsOneWidget);
      expect(find.text("'Trip': No date."), findsOneWidget);
    });

    testWidgets('says it plainly when everything was filed', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient((request) {
        if (request.method == "POST") {
          return json('{"outcomes":['
              '{"name":"a","newName":"2026/a","message":""},'
              '{"name":"b","newName":"2026/b","message":""}'
              ']}');
        }
        return json(rootListing("BY_YEAR_MONTH"));
      }, requests);
      await pumpRoot(tester, client);

      await openMenu(tester, "Apply rule");

      expect(find.byKey(const Key("move-outcome")), findsNothing);
      expect(find.text("Filed 2 albums."), findsOneWidget);
      // The listing was fetched again: what was filed is no longer where it
      // was.
      expect(requests.last.method, "GET");
    });

    testWidgets('shows the server\'s reason for a refused request',
        (tester) async {
      var client = recordingClient((request) {
        if (request.method == "POST") {
          return json('["ErrorInfo",{"message":"Not your folder."}]',
              status: 403);
        }
        return json(rootListing("BY_YEAR"));
      }, []);
      await pumpRoot(tester, client);

      await openMenu(tester, "Apply rule");

      expect(find.text("Not your folder."), findsOneWidget);
      expect(find.byKey(const Key("move-outcome")), findsNothing);
    });

    testWidgets('says when there was nothing to file', (tester) async {
      var client = recordingClient((request) {
        if (request.method == "POST") {
          return json('{"outcomes":[]}');
        }
        return json(rootListing("BY_YEAR"));
      }, []);
      await pumpRoot(tester, client);

      await openMenu(tester, "Apply rule");

      expect(find.text("Nothing to file."), findsOneWidget);
    });
  });
}
