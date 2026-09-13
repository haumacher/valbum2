/// Tests of what an empty listing says (issue #56).
///
/// A folder with no tiles used to be a black page with nothing but the app
/// bar on it — which hits a freshly joined guest hardest, whose library is
/// *correctly* empty until somebody shares an album with them.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// A JSON answer of the server.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "carol"}';

/// The rights field as the server spells it.
String rightsField(List<String> names) =>
    '"rights": [${names.map((name) => '{"name": "$name"}').join(", ")}], ';

/// A listing of the given folders, with the given rights.
String listing(
  String title,
  List<String> folders, {
  List<String> rights = const ["edit"],
}) =>
    '["ListingInfo", {"path": "", "title": "$title", '
    '${rightsField(rights)}"folders": ['
    '${folders.map((name) => '{"name": "$name", "title": "$name"}').join(", ")}'
    ']}]';

/// Pumps the app of a caller of the given role, over a handler answering the
/// root and (where it is asked for) the folder below it.
Future<void> pumpLibrary(
  WidgetTester tester, {
  required String role,
  required String root,
  String? child,
  VAlbumRoute? at,
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "dev-9",
    userName: "carol",
    httpClient: MockClient(servingThumbnails((request) async {
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfUser(role));
      }
      if (request.url.queryParameters["type"] == "json") {
        var path = Uri.decodeFull(request.url.path);
        return json(path.endsWith("/Trip/") ? (child ?? root) : root);
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    })),
  );
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      initialRoute: at,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('a guest with nothing shared is told exactly that',
      (tester) async {
    await pumpLibrary(
      tester,
      role: "guest",
      root: listing("carol", const []),
    );

    expect(find.byKey(const Key("listing-empty")), findsOneWidget);
    expect(
      find.text(
        "Nothing has been shared with you yet. Albums others share with you "
        "appear here.",
      ),
      findsOneWidget,
    );
    // The app bar is still there, naming whose library this is.
    expect(find.text("carol"), findsOneWidget);
  });

  testWidgets('an empty library says so, and how it is filled', (tester) async {
    await pumpLibrary(
      tester,
      role: "member",
      root: listing("My albums", const []),
    );

    expect(find.byKey(const Key("listing-empty")), findsOneWidget);
    expect(
        find.textContaining("There are no albums here yet."), findsOneWidget);
    expect(
      find.textContaining("Create the first one from the menu"),
      findsOneWidget,
    );
  });

  testWidgets('a caller who may not create is not told to create',
      (tester) async {
    await pumpLibrary(
      tester,
      role: "member",
      root: listing("Shared", const [], rights: const ["view"]),
    );

    expect(
        find.textContaining("There are no albums here yet."), findsOneWidget);
    expect(
      find.textContaining("Create the first one from the menu"),
      findsNothing,
    );
  });

  testWidgets('an empty folder below the root reads as a folder',
      (tester) async {
    await pumpLibrary(
      tester,
      role: "member",
      root: listing("My albums", const ["Trip"]),
      child: listing("Trip", const []),
      at: const ListingOrAlbumRoute(["Trip"]),
    );

    expect(find.byKey(const Key("listing-empty")), findsOneWidget);
    expect(find.text("This folder has no albums yet."), findsOneWidget);
  });

  testWidgets('a listing that has tiles says nothing of the sort',
      (tester) async {
    await pumpLibrary(
      tester,
      role: "member",
      root: listing("My albums", const ["Trip"]),
    );

    expect(find.byKey(const Key("listing-empty")), findsNothing);
    expect(find.text("Trip"), findsOneWidget);
  });
}
