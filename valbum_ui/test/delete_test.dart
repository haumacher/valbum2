/// Tests of "Delete…" and "Delete album…" (issue #109): where the two entries
/// are offered, what the confirmation says, what is posted and where the app
/// stands afterwards.
///
/// The app cannot know beforehand which of the two things the server will do —
/// whether anything below the folder is a photograph is a question about the
/// disk — so the confirmation says both, and what the server did is read out
/// in the server's own words.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'album_menu_actions_test.dart' hide main;
import 'move_test.dart' hide main;
import 'share_link_test.dart' as share;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// What the server answers for an entry it moved into the trash.
String trashed(String name, String asName) =>
    '{"outcomes":[{"name":"$name","newName":"$asName","message":'
    '"Moved to the trash of the space (\'.valbum/trash\') as \'$asName\'."}]}';

/// What the server answers for an entry it removed for good.
String removed(String name) => '{"outcomes":[{"name":"$name","newName":"",'
    '"message":"Removed: it held no image."}]}';

/// Long-presses the listing tile of the given name and opens its menu.
Future<void> longPressFolder(WidgetTester tester, String name) async {
  await tester.longPress(
    find
        .ancestor(
          of: find.text(name),
          matching: find.byType(GestureDetector),
        )
        .first,
  );
  await tester.pumpAndSettle();
}

/// Confirms the open delete dialog.
Future<void> confirmDelete(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("delete-confirm")));
  await tester.pumpAndSettle();
}

void main() {
  group('the delete entry of a listing tile', () {
    testWidgets('says what will happen and posts it', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(trashed("2020 Trip", "2020 Trip"))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        await longPressFolder(tester, "2020 Trip");
        expect(find.byKey(const Key("delete-entry")), findsOneWidget);
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();

        // Both outcomes are named, and the sentence that matters most last.
        expect(find.text("Delete '2020 Trip'?"), findsOneWidget);
        expect(find.text(testL10n.deleteExplanation), findsOneWidget);
        await confirmDelete(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      // Posted to the folder the entry lives in, as a move out of it is.
      expect(Uri.decodeFull(post.url.toString()), "$dataUrl/?action=delete");
      expect(post.body, '{"target":"","names":[{"name":"2020 Trip"}]}');

      // The listing was fetched again, and the server's own words are shown.
      expect(
        jsonGets(requests.sublist(requests.indexOf(post))),
        contains("/valbum/data/"),
      );
      expect(
        find.textContaining("Moved to the trash of the space"),
        findsOneWidget,
      );
    });

    testWidgets('reads out a removal in the server\'s own words',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => request.method == "POST"
            ? json(removed("2020 Trip"))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await longPressFolder(tester, "2020 Trip");
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();
        await confirmDelete(tester);
      });

      expect(find.text("Removed: it held no image."), findsOneWidget);
    });

    testWidgets('sends nothing when the question is answered with Cancel',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await longPressFolder(tester, "2020 Trip");
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();
        await tester.tap(find.text("Cancel"));
        await tester.pumpAndSettle();
      });

      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });

    testWidgets('shows the server\'s reason when the delete is refused',
        (tester) async {
      var client = recordingClient(
        (request) => request.method == "POST"
            ? http.Response(
                '["ErrorInfo", {"message": "You may not change the folder '
                'you are deleting from."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : treeAnswer(request),
        <http.Request>[],
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await longPressFolder(tester, "2020 Trip");
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();
        await confirmDelete(tester);
      });

      expect(
        find.text("You may not change the folder you are deleting from."),
        findsOneWidget,
      );
      // Still on the listing, with the entry the server kept.
      expect(find.text("2020 Trip"), findsOneWidget);
    });

    testWidgets('refuses the delete while the app is offline', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client, offlineState: state));
        await tester.pumpAndSettle();
        state.goneOffline(null);
        await tester.pumpAndSettle();

        await longPressFolder(tester, "2020 Trip");
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();
      });

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(find.byKey(const Key("delete-dialog")), findsNothing);
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  group('the delete entry of the album menu', () {
    testWidgets('posts the album to the folder above and ascends',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => request.method == "POST"
              ? json(trashed("Inbox", "Inbox"))
              : treeAnswer(request),
          requests: requests,
        );

        await openAlbumMenu(tester);
        expect(entryText(tester, "delete-album"), "Delete album…");
        await tester.tap(find.byKey(const Key("delete-album")));
        await tester.pumpAndSettle();

        expect(find.text("Delete 'Inbox'?"), findsOneWidget);
        expect(find.text(testL10n.deleteExplanation), findsOneWidget);
        await confirmDelete(tester);
      });

      var post = requests.singleWhere((r) => r.method == "POST");
      // The delete is posted to the folder the album lives in, naming it.
      expect(Uri.decodeFull(post.url.toString()), "$dataUrl/?action=delete");
      expect(post.body, '{"target":"","names":[{"name":"Inbox"}]}');

      // The album is no longer where the address said it was: the listing
      // above is on the screen.
      expect(find.byType(AlbumContent), findsNothing);
      expect(find.text("Test-album"), findsOneWidget);
    });

    testWidgets('stays in the album when the server refuses', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => request.method == "POST"
              ? http.Response(
                  '["ErrorInfo", {"message": "Not your album."}]',
                  403,
                  headers: {"content-type": "application/json"},
                )
              : treeAnswer(request),
        );

        await tapEntry(tester, "delete-album");
        await confirmDelete(tester);
      });

      expect(find.text("Not your album."), findsOneWidget);
      expect(find.byType(AlbumContent), findsOneWidget);
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
        // is no listing to take it out of.
        expect(find.byKey(const Key("delete-album")), findsNothing);
      });
    });
  });

  group('a caller who may not edit', () {
    testWidgets('is offered no delete in the album menu', (tester) async {
      await withFakeImageHttp(() async {
        await pumpAlbum(
          tester,
          (request) => json(albumWith(rights: const ["view", "download"])),
        );

        await openAlbumMenu(tester);

        expect(find.byKey(const Key("delete-album")), findsNothing);
        // The menu itself is there: the reload is.
        expect(find.text("Reload"), findsOneWidget);
      });
    });

    testWidgets('and none on a listing tile', (tester) async {
      var client = recordingClient(
        (request) => pathOf(request) == "/valbum/data/"
            ? json(listingWith(const ["view", "download"]))
            : treeAnswer(request),
        <http.Request>[],
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await longPressFolder(tester, "2020 Trip");
      });

      // Nothing this caller may do here: no menu at all rather than an empty
      // one, which is what the move of issue #47 already established.
      expect(find.text("Delete…"), findsNothing);
      expect(find.text("Move to…"), findsNothing);
    });

    testWidgets('and neither is the visitor of a share link', (tester) async {
      await share.pumpLinkSession(
        tester,
        share.liveLink(
          rights: const ["view", "download", "contribute", "edit"],
          writeAllowed: true,
        ),
      );
      await openAlbumMenu(tester);

      expect(find.byKey(const Key("delete-album")), findsNothing);
    });
  });
}

/// The listing of the tree with the given rights on it.
String listingWith(List<String> rights) =>
    '["ListingInfo", {"path": "", "title": "Test-album", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"folders": [{"name": "2020 Trip", "title": "2020 Trip"}]}]';
