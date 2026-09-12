/// Tests of the link entries of issue #50: the badge and the owner on a tile,
/// "Remove from my albums", and a canonical `~owner/…` URL opened at the
/// viewer's own link.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The requests that asked the server for a resource.
List<String> jsonGets(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          pathOf(request),
    ];

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal of the server, with the reason it names.
http.Response refusal(int status, String message) => http.Response(
      '["ErrorInfo", {"message": "$message"}]',
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The rights field as the server spells it, for the given names.
String rightsField(List<String> names) => names.isEmpty
    ? ""
    : '"rights": [${names.map((name) => '{"name": "$name"}').join(", ")}], ';

/// The viewer's root listing: one link to alice's `2024`, one folder of the
/// viewer's own.
String rootListing({List<String> rights = const ["edit"]}) =>
    '["ListingInfo", {"path": "", "title": "My albums", '
    '${rightsField(rights)}'
    '"folders": ['
    '{"name": "2024", "title": "2024", "link": "~alice/2024", '
    '"effectiveDate": 1714521600000}, '
    '{"name": "Mine", "title": "Mine"}'
    ']}]';

/// An album with one image.
String albumNamed(String title) => '["AlbumInfo", {"path": "$title", '
    '"title": "$title", "subTitle": "", '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// The answer of an `unlink` in which every name went.
String removedAll(List<String> names) =>
    '{"outcomes":[${names.map((name) => '{"name":"$name",'
        '"newName":"","message":""}').join(",")}]}';

/// A client answering [handler], recording every request, signed in unless
/// [token] says otherwise.
VAlbumClient linkClient(
  http.Response Function(http.Request request) handler, {
  List<http.Request>? requests,
  String? token = "bob-token",
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: token,
      userName: token == null ? "" : "bob",
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return handler(request);
      })),
    );

/// The settings of a device signed in as `bob`, or of one that is not.
///
/// The app builds its client from the settings, not from the injected one, so
/// this is what makes the caller of these tests a known one.
ServerSettings signedIn({String? token = "bob-token"}) => ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: token,
      userName: token == null ? null : "bob",
      loaded: true,
    );

/// Pumps the app over [client], signed in unless [token] says otherwise.
Future<void> pumpApp(
  WidgetTester tester,
  VAlbumClient client, {
  String? token = "bob-token",
  VAlbumRoute? initialRoute,
}) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: signedIn(token: token),
    initialRoute: initialRoute,
  ));
  await tester.pumpAndSettle();
}

/// Long-presses the tile with the given title.
Future<void> longPressTile(WidgetTester tester, String title) async {
  await tester.longPress(
    find
        .ancestor(
          of: find.text(title),
          matching: find.byType(GestureDetector),
        )
        .first,
  );
  await tester.pumpAndSettle();
}

void main() {
  group('linkOwnerOf', () {
    test('names the owner of the album a tile links to', () {
      expect(
        linkOwnerOf(FolderInfo(name: "Zoo", link: "~alice/2024/Zoo")),
        "alice",
      );
      expect(linkOwnerOf(FolderInfo(name: "Zoo", link: "~alice")), "alice");
    });

    test('answers nothing for an ordinary folder', () {
      expect(linkOwnerOf(FolderInfo(name: "2024")), isNull);
      expect(linkOwnerOf(FolderInfo(name: "2024", link: "")), isNull);
    });

    test('answers nothing for a link naming nobody', () {
      expect(linkOwnerOf(FolderInfo(name: "x", link: "~/x")), isNull);
      expect(linkOwnerOf(FolderInfo(name: "x", link: "2024/Zoo")), isNull);
    });
  });

  group('viewerPathFor', () {
    /// A root listing holding the given tiles, as `name: link` pairs.
    ListingInfo rootWith(Map<String, String> tiles) => ListingInfo(
          folders: [
            for (var tile in tiles.entries)
              FolderInfo(name: tile.key, title: tile.key, link: tile.value),
          ],
        );

    test('appends the rest of the path to the tile of the link', () {
      expect(
        viewerPathFor(
          const ["~alice", "2024", "2024-05-01 Zoo"],
          rootWith({"Alices year": "~alice/2024"}),
        ),
        const ["Alices year", "2024-05-01 Zoo"],
      );
    });

    test('answers the tile itself for a link to the album asked for', () {
      expect(
        viewerPathFor(
          const ["~alice", "2024", "2024-05-01 Zoo"],
          rootWith({"Zoo": "~alice/2024/2024-05-01 Zoo"}),
        ),
        const ["Zoo"],
      );
    });

    test('takes the longest link that matches', () {
      expect(
        viewerPathFor(
          const ["~alice", "2024", "2024-05-01 Zoo"],
          rootWith({
            "Alices year": "~alice/2024",
            "Zoo": "~alice/2024/2024-05-01 Zoo",
          }),
        ),
        const ["Zoo"],
      );
    });

    test('answers nothing without a matching tile', () {
      expect(
        viewerPathFor(
          const ["~alice", "2024", "2024-05-01 Zoo"],
          rootWith({"Bobs year": "~bob/2024", "Mine": ""}),
        ),
        isNull,
      );
      // A link is matched segment by segment, not as a string.
      expect(
        viewerPathFor(
          const ["~alice", "2024x"],
          rootWith({"Alices year": "~alice/2024"}),
        ),
        isNull,
      );
    });

    test('is not asked about a path of the viewer\'s own', () {
      expect(
        viewerPathFor(
          const ["2024", "Zoo"],
          rootWith({"Alices year": "~alice/2024"}),
        ),
        isNull,
      );
    });
  });

  group('the link tile', () {
    testWidgets('carries a badge and the name of its owner', (tester) async {
      var client = linkClient(
        (request) => pathOf(request) == "/valbum/data/"
            ? json(rootListing())
            : refusal(403, "Not yours."),
      );

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
      });

      // The link tile says whose album it shows; the folder of the viewer's
      // own does not.
      expect(find.byKey(const Key("link-badge-2024")), findsOneWidget);
      expect(find.byKey(const Key("link-owner-2024")), findsOneWidget);
      expect(find.text("from alice"), findsOneWidget);
      expect(find.byKey(const Key("link-badge-Mine")), findsNothing);
      expect(find.byKey(const Key("link-owner-Mine")), findsNothing);
      expect(find.byIcon(Icons.link), findsOneWidget);
    });

    testWidgets('is navigated into on the viewer\'s own path', (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(
        (request) => switch (pathOf(request)) {
          "/valbum/data/" => json(rootListing()),
          "/valbum/data/2024/" => json(albumNamed("Zoo")),
          _ => refusal(404, "No such resource."),
        },
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);

        await tester.tap(find.text("2024"));
        await tester.pumpAndSettle();
      });

      // The viewer's own path, not the canonical one: the server resolves the
      // link, the app does not follow it.
      expect(jsonGets(requests), contains("/valbum/data/2024/"));
      expect(
        jsonGets(requests).where((path) => path.contains("~alice")),
        isEmpty,
      );
    });
  });

  group('remove from my albums', () {
    /// The server of the tests below: the root listing, and whatever the
    /// `unlink` is answered with.
    http.Response Function(http.Request) server({
      List<String> rights = const ["edit"],
      http.Response Function(http.Request)? onUnlink,
    }) =>
        (request) {
          if (request.url.queryParameters["type"] != null &&
              request.url.queryParameters["type"] != "json") {
            return refusal(403, "Not yours.");
          }
          if (request.method == "POST") {
            return (onUnlink ?? (r) => json(removedAll(["2024"])))(request);
          }
          if (pathOf(request) == "/valbum/data/") {
            return json(rootListing(rights: rights));
          }
          return refusal(403, "Not yours.");
        };

    testWidgets('is offered on a link tile of a folder one may edit',
        (tester) async {
      var client = linkClient(server());

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");
      });

      expect(find.text("Remove from my albums"), findsOneWidget);
      expect(find.text("Move to…"), findsOneWidget);
    });

    testWidgets('is not offered on an ordinary folder', (tester) async {
      var client = linkClient(server());

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "Mine");
      });

      expect(find.text("Remove from my albums"), findsNothing);
      expect(find.text("Move to…"), findsOneWidget);
    });

    testWidgets('is not offered without the right to edit the folder',
        (tester) async {
      var client = linkClient(server(rights: const ["view"]));

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");
      });

      // Nothing this caller may do with the tile: no menu rather than an
      // empty one.
      expect(find.text("Remove from my albums"), findsNothing);
      expect(find.text("Move to…"), findsNothing);
    });

    testWidgets('asks first, then posts the name and reloads the listing',
        (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(server(), requests: requests);

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");

        await tester.tap(find.text("Remove from my albums"));
        await tester.pumpAndSettle();

        // What is being thrown away, before anything is sent.
        expect(find.byKey(const Key("unlink-confirm")), findsOneWidget);
        expect(
          find.text("The album stays with alice; only your entry is "
              "removed. It does not come back on its own."),
          findsOneWidget,
        );
        expect(requests.where((r) => r.method == "POST"), isEmpty);

        await tester.tap(find.byKey(const Key("unlink-confirmed")));
        await tester.pumpAndSettle();
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(Uri.decodeFull(post.url.toString()), "$dataUrl/?action=unlink");
      expect(post.body, '{"target":"","names":[{"name":"2024"}]}');

      expect(find.text("Removed from my albums."), findsOneWidget);
      // The tile is gone from the folder: the listing is fetched again.
      expect(
        jsonGets(requests.sublist(requests.indexOf(post))),
        contains("/valbum/data/"),
      );
    });

    testWidgets('sends nothing when the question is answered with cancel',
        (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(server(), requests: requests);

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");

        await tester.tap(find.text("Remove from my albums"));
        await tester.pumpAndSettle();
        await tester.tap(find.text("Cancel"));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(const Key("unlink-confirm")), findsNothing);
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });

    testWidgets('says what the server answered for an entry that stayed',
        (tester) async {
      var client = linkClient(server(
        onUnlink: (_) => json('{"outcomes":[{"name":"2024","newName":"",'
            '"message":"This is no link."}]}'),
      ));

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");
        await tester.tap(find.text("Remove from my albums"));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("unlink-confirmed")));
        await tester.pumpAndSettle();

        // A dialog, not a snack bar: a refusal must not scroll past.
        expect(find.byKey(const Key("move-outcome")), findsOneWidget);
        expect(find.text("Nothing removed."), findsOneWidget);
        expect(find.text("'2024': This is no link."), findsOneWidget);

        await tester.tap(find.text("OK"));
        await tester.pumpAndSettle();
      });
    });

    testWidgets('shows the reason the server refuses the whole request',
        (tester) async {
      var client = linkClient(server(
        onUnlink: (_) => refusal(403, "You may not change this folder."),
      ));

      await withFakeImageHttp(() async {
        await pumpApp(tester, client);
        await longPressTile(tester, "2024");
        await tester.tap(find.text("Remove from my albums"));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("unlink-confirmed")));
        await tester.pumpAndSettle();

        expect(find.text("You may not change this folder."), findsOneWidget);
        var bar = tester.widget<SnackBar>(find.byType(SnackBar));
        expect(bar.backgroundColor, Colors.red.shade700);
        // Nothing was removed, so the tile is still there.
        expect(find.byKey(const Key("link-badge-2024")), findsOneWidget);
      });
    });
  });

  group('a canonical URL', () {
    /// The route a pasted `~alice/…` URL denotes.
    const canonical = ListingOrAlbumRoute(["~alice", "2024", "2024-05-01 Zoo"]);

    /// A server answering the root listing, the album through the link and the
    /// album canonically.
    http.Response Function(http.Request) tree({bool linked = true}) =>
        (request) => switch (pathOf(request)) {
              "/valbum/data/" => json(linked
                  ? '["ListingInfo", {"path": "", "title": "My albums", '
                      '"folders": [{"name": "Alices year", '
                      '"title": "2024", "link": "~alice/2024"}]}]'
                  : '["ListingInfo", {"path": "", "title": "My albums", '
                      '"folders": []}]'),
              "/valbum/data/Alices year/2024-05-01 Zoo/" =>
                json(albumNamed("Zoo")),
              "/valbum/data/~alice/2024/2024-05-01 Zoo/" =>
                json(albumNamed("Zoo")),
              _ => refusal(404, "No such resource."),
            };

    testWidgets('is opened at the viewer\'s own link to it', (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(tree(), requests: requests);

      await withFakeImageHttp(() async {
        await pumpApp(tester, client, initialRoute: canonical);
      });

      expect(
        jsonGets(requests),
        contains("/valbum/data/Alices year/2024-05-01 Zoo/"),
      );
      // The canonical path is not asked for at all: the view that follows is
      // the viewer's own.
      expect(
        jsonGets(requests),
        isNot(contains("/valbum/data/~alice/2024/2024-05-01 Zoo/")),
      );
      expect(find.text("Zoo"), findsWidgets);
    });

    testWidgets('is opened canonically without a link to it', (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(tree(linked: false), requests: requests);

      await withFakeImageHttp(() async {
        await pumpApp(tester, client, initialRoute: canonical);
      });

      expect(
        jsonGets(requests),
        contains("/valbum/data/~alice/2024/2024-05-01 Zoo/"),
      );
      expect(find.text("Zoo"), findsWidgets);
    });

    testWidgets('is opened canonically by a caller who is not signed in',
        (tester) async {
      var requests = <http.Request>[];
      var client = linkClient(tree(), requests: requests, token: null);

      await withFakeImageHttp(() async {
        await pumpApp(tester, client, token: null, initialRoute: canonical);
      });

      expect(
        jsonGets(requests),
        contains("/valbum/data/~alice/2024/2024-05-01 Zoo/"),
      );
      // An anonymous caller has no space and therefore no links: the root
      // listing is not troubled with the question.
      expect(jsonGets(requests), isNot(contains("/valbum/data/")));
    });
  });
}
