/// "Album properties" and "Move to…" as entries of the album's menu, the app
/// half of issue #121.
///
/// Both lived in the toolbar of the edit mode alone: retitling an album meant
/// entering an edit session first, and the album itself could only be moved
/// from the listing above it. They are entries of the menu now, offered in the
/// view mode as well — the properties writing the album by themselves where
/// there is no edit session to write it, the move acting on the selection
/// where there is one and on the album itself where there is not.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'move_test.dart' hide main;
import 'share_link_test.dart' as share;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The album `Inbox` with the given rights, the empty list saying nothing at
/// all (which reads as every right, see `rights.dart`).
String albumWith({
  List<String> rights = const [],
  String title = "Inbox",
  String path = "Inbox",
}) =>
    '["AlbumInfo", {"path": "$path", "title": "$title", "subTitle": "", '
    '${rights.isEmpty ? "" : '"rights": ['
        '${rights.map((r) => '{"name": "$r"}').join(", ")}], '}'
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// Opens the album at [route] over a server answering [handler].
Future<void> pumpAlbum(
  WidgetTester tester,
  http.Response Function(http.Request) handler, {
  List<String> route = const ["Inbox"],
  List<http.Request>? requests,
  OfflineState? offlineState,
}) async {
  await tester.pumpWidget(VAlbumApp(
    client: VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return handler(request);
      })),
    ),
    offlineState: offlineState,
    initialRoute: ListingOrAlbumRoute(route),
  ));
  await tester.pumpAndSettle();
}

/// Opens the album's menu and taps the entry with the given key.
Future<void> tapEntry(WidgetTester tester, String key) async {
  await openAlbumMenu(tester);
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

/// The text of the menu entry with the given key.
String entryText(WidgetTester tester, String key) => tester
    .widget<Text>(
      find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text)),
    )
    .data!;

void main() {
  group('the menu of an album the caller may edit', () {
    testWidgets('offers the properties and the move of the album itself',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);

        // Neither is a toolbar icon any more — in the view mode the album
        // shows no bar at all, and in the edit mode only Save and Cancel.
        expect(find.byIcon(Icons.tune), findsNothing);
        expect(find.byIcon(Icons.drive_file_move), findsNothing);

        await openAlbumMenu(tester);

        expect(find.byKey(const Key("album-properties")), findsOneWidget);
        expect(find.text("Album properties"), findsOneWidget);
        // Nothing is selected — nothing is being edited at all — so the move
        // is the move of the album itself.
        expect(entryText(tester, "move-to"), "Move album to…");
      });
    });

    testWidgets('writes the album by itself outside an edit session',
        (tester) async {
      var requests = <http.Request>[];
      var renamed = false;
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) {
            if (request.method == "PUT") {
              renamed = true;
              return json("");
            }
            if (pathOf(request) == "/valbum/data/Inbox/" && renamed) {
              return json(albumWith(title: "Zoo"));
            }
            return treeAnswer(request);
          },
          requests: requests,
        );

        await tapEntry(tester, "album-properties");
        expect(find.text(testL10n.titleLabel), findsOneWidget);
        await tester.enterText(find.byType(TextField).first, "Zoo");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
      });

      var puts = requests.where((r) => r.method == "PUT").toList();
      expect(puts, hasLength(1));
      expect(pathOf(puts.single), "/valbum/data/Inbox/");
      expect(puts.single.body, contains('"title":"Zoo"'));
      // And the album on the screen is the one the server now has.
      expect(find.text("Zoo"), findsOneWidget);
      expect(find.text("Inbox"), findsNothing);
    });

    testWidgets('takes the change back when the server refuses the write',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => request.method == "PUT"
              ? http.Response(
                  '["ErrorInfo", {"message": "Not your album."}]',
                  403,
                  headers: {"content-type": "application/json"},
                )
              : treeAnswer(request),
        );

        await tapEntry(tester, "album-properties");
        await tester.enterText(find.byType(TextField).first, "Zoo");
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();

        // The server's own words, and the album as the server still has it.
        expect(find.text("Not your album."), findsOneWidget);
        expect(find.text("Zoo"), findsNothing);
      });
    });

    testWidgets('refuses the properties while the app is offline',
        (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          treeAnswer,
          requests: requests,
          offlineState: state,
        );
        state.goneOffline(null);
        await tester.pumpAndSettle();

        await tapEntry(tester, "album-properties");

        expect(find.text(testL10n.offlineRefusal), findsOneWidget);
        expect(find.text(testL10n.titleLabel), findsNothing);
      });

      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  group('the move entry', () {
    testWidgets('names the selection in the edit mode', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer, route: const ["Inbox"]);
        // The long press enters the edit mode with that tile selected, the
        // second long press adds the next one.
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        await longPressTile(tester, "b.jpg");

        await openAlbumMenu(tester);
        expect(entryText(tester, "move-to"), "Move 2 images to…");

        await tester.tap(find.byKey(const Key("move-to")));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("folder-picker")), findsOneWidget);
      });
    });

    testWidgets('names one image where one is selected', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();

        await openAlbumMenu(tester);
        expect(entryText(tester, "move-to"), "Move 1 image to…");
      });
    });

    testWidgets('is the album itself where nothing is selected',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, treeAnswer);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        // Taking the one tile out of the selection leaves the edit mode on
        // with nothing to move but the album.
        await tapTile(tester, "a.jpg");

        await openAlbumMenu(tester);
        expect(entryText(tester, "move-to"), "Move album to…");
      });
    });

    testWidgets('posts the album to the folder above and ascends',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => request.method == "POST"
              ? json(movedAll(["Inbox"]))
              : treeAnswer(request),
          requests: requests,
        );

        await tapEntry(tester, "move-to");
        await enterFolder(tester, "2021");
        expect(find.text("Move 'Inbox' to '2021'"), findsOneWidget);
        await confirmPicker(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      // Posted to the folder the album lives in, naming the album's folder.
      expect(Uri.decodeFull(post.url.toString()), "$dataUrl/?action=move");
      expect(post.body, '{"target":"2021","names":[{"name":"Inbox"}]}');
      expect(find.text("Moved 'Inbox' to '2021'."), findsOneWidget);

      // The album is no longer where the address said it was: the listing
      // above is on the screen.
      expect(find.byType(AlbumContent), findsNothing);
      expect(find.text("Test-album"), findsOneWidget);
    });

    testWidgets('is not offered on an album at the root of the space',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => json(albumWith(path: "", title: "Everything")),
          route: const [],
        );

        await openAlbumMenu(tester);

        // An album is an entry of the listing above it; above the root there
        // is no listing to move it into.
        expect(find.byKey(const Key("move-to")), findsNothing);
        // Its properties are its own, and are offered here as everywhere.
        expect(find.byKey(const Key("album-properties")), findsOneWidget);
      });
    });
  });

  group('a caller who may not edit', () {
    testWidgets('is offered neither entry', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => json(albumWith(rights: const ["view", "download"])),
        );

        await openAlbumMenu(tester);

        expect(find.byKey(const Key("album-properties")), findsNothing);
        expect(find.byKey(const Key("move-to")), findsNothing);
        // The menu itself is there: the rating filter and the reload are.
        expect(find.text("Reload"), findsOneWidget);
      });
    });

    testWidgets('and neither is the visitor of a share link', (tester) async {
      // Even where the answer claims `edit`, which the server never grants a
      // link: a link is not an account, and the app offers nothing it would
      // have to refuse.
      await share.pumpLinkSession(
        tester,
        share.liveLink(
          rights: const ["view", "download", "contribute", "edit"],
          writeAllowed: true,
        ),
      );
      await openAlbumMenu(tester);

      expect(find.byKey(const Key("album-properties")), findsNothing);
      expect(find.byKey(const Key("move-to")), findsNothing);
    });
  });
}
