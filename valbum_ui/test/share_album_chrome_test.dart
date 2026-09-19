/// What a shared album looks like (issue #97).
///
/// A share link used to give the album an app bar of its own, whose title was
/// the link's label with the album's title beneath it — and the album body
/// then painted that same title a third time as its heading. A visitor was
/// shown the name of the album twice, in two styles, before the album had
/// started.
///
/// A link shows the album exactly as its owner sees it: immersive, the album's
/// own heading once, the floating controls with what a visitor may do. The
/// link's label names the link where links are managed; it is not a caption.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The link the session tests are opened at.
const SessionUrl link = SessionUrl(
  kind: SessionKind.share,
  token: "tok-42",
  dataUrl: dataUrl,
  basePath: "/valbum/s/tok-42/",
);

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a link caller, labelled unlike what it opens.
String authOfLink({String path = "~alice/2024/Zoo"}) =>
    '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
    '"userName": "", "role": "", "space": "alice", '
    '"share": {"label": "For grandma", "expires": "", '
    '"rights": [{"name": "view"}], "path": "$path"}}';

/// The album the link opens: one image, and the `view` right.
const String zooAlbum =
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "A day out", '
    '"rights": [{"name": "view"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// The listing the folder link opens, holding the album below.
const String yearListing =
    '["ListingInfo", {"path": "", "title": "2024", '
    '"rights": [{"name": "view"}], '
    '"folders": [{"name": "Zoo", "title": "Zoo"}]}]';

/// Pumps the app as a link session over the given handler.
Future<void> pumpSession(
  WidgetTester tester,
  http.Response Function(http.Request request) handler, {
  SessionUrl session = link,
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    httpClient: MockClient(servingThumbnails((request) async {
      return handler(request);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: ServerSettings(
      store: InMemorySettingsStore(dataUrl, "", "", ""),
      platformDefault: () => dataUrl,
    ),
    session: session,
  ));
  await tester.pumpAndSettle();
}

/// The answers of a link opening one album.
http.Response Function(http.Request) albumLink() => (request) {
      if (request.url.queryParameters["type"] == "auth") {
        return json(authOfLink());
      }
      return json(zooAlbum);
    };

/// The answers of a link opening the folder above the album.
http.Response Function(http.Request) folderLink() => (request) {
      var type = request.url.queryParameters["type"];
      if (type == "auth") {
        return json(authOfLink(path: "~alice/2024"));
      }
      var path = Uri.decodeFull(request.url.path);
      if (path == "/valbum/data/") {
        return json(yearListing);
      }
      if (path == "/valbum/data/Zoo/") {
        return json(zooAlbum);
      }
      return http.Response("No such resource: $path", 404);
    };

void main() {
  testWidgets('a shared album shows its own title once and no app bar',
      (tester) async {
    await pumpSession(tester, albumLink());

    expect(find.byType(AlbumContent), findsOneWidget);
    // The album's own heading, once: not the link's label, and not a second
    // time in an app bar over it.
    expect(find.text("Zoo"), findsOneWidget);
    expect(find.text("A day out"), findsOneWidget);
    expect(find.byType(AppBar), findsNothing);
    expect(find.byKey(const Key("share-label")), findsNothing);
    expect(find.text("For grandma"), findsNothing);
    // And the floating menu is there, as in the owner's own view.
    expect(find.byIcon(Icons.more_vert), findsOneWidget);
  });

  testWidgets('the owner sees the very same album', (tester) async {
    var client = VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        return json(zooAlbum);
      })),
    );
    await tester.pumpWidget(VAlbumApp(client: client));
    await tester.pumpAndSettle();

    expect(find.text("Zoo"), findsOneWidget);
    expect(find.text("A day out"), findsOneWidget);
    expect(find.byType(AppBar), findsNothing);
    expect(find.byIcon(Icons.more_vert), findsOneWidget);
  });

  testWidgets('the way up is offered where the link opens a folder above',
      (tester) async {
    await pumpSession(tester, albumLink());

    // At the root of the link there is nowhere to go: the way up would lead
    // out of what the link opens.
    expect(find.byTooltip("Up"), findsNothing);
  });

  testWidgets('a listing through a link names the folder, once',
      (tester) async {
    await pumpSession(tester, folderLink());

    expect(find.byType(ListingView), findsOneWidget);
    // The listing keeps its app bar — it has no immersive mode — but the
    // label is gone from it.
    expect(find.byType(AppBar), findsOneWidget);
    expect(find.text("2024"), findsOneWidget);
    expect(find.byKey(const Key("share-label")), findsNothing);
    expect(find.text("For grandma"), findsNothing);

    // One folder below, the album is immersive and offers the way back up.
    await tester.tap(find.text("Zoo").first);
    await tester.pumpAndSettle();

    expect(find.byType(AlbumContent), findsOneWidget);
    expect(find.byType(AppBar), findsNothing);
    expect(find.text("Zoo"), findsOneWidget);
    expect(find.byTooltip("Up"), findsOneWidget);
  });
}
