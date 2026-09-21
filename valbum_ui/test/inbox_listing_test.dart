/// The inbox where it is *not* the screen, the app half of issue #136: the
/// listing tile, the create dialog and the switch that turns an album into an
/// inbox and back.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'inbox_view_test.dart' show inboxJson;
import 'move_test.dart' hide main;
import 'share_link_test.dart' as share;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// A listing holding an inbox and a dated album: the inbox is undated, so the
/// date order alone would drop it to the end.
const String listingWithInbox = '["ListingInfo", {"path": "", '
    '"title": "Library", "folders": ['
    '{"name": "2026-05-01 Trip", "title": "Trip", "kind": "ALBUM", '
    '"effectiveDate": 1777593600000}, '
    '{"name": "Inbox", "title": "Inbox", "kind": "INBOX"}'
    ']}]';

/// An ordinary album, which the kind switch turns into an inbox.
const String plainAlbum = '["AlbumInfo", {"path": "Album", '
    '"title": "Album", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]]}]';

Future<void> pumpAt(
  WidgetTester tester,
  http.Response Function(http.Request) handler, {
  List<String> route = const [],
  List<http.Request>? requests,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: recordingClient(handler, requests ?? []),
      initialRoute: ListingOrAlbumRoute(route),
    ));
    await tester.pumpAndSettle();
  });
}

void main() {
  group('the listing tile of an inbox', () {
    testWidgets('stands first, carries the inbox icon and no date',
        (tester) async {
      await pumpAt(tester, (_) => json(listingWithInbox));

      // First, although it is the undated one: an inbox is what wants doing.
      // The tiles wrap into one row here, so "first" reads left to right.
      var inbox = tester.getTopLeft(find.text("Inbox"));
      var trip = tester.getTopLeft(find.text("Trip"));
      expect(inbox.dy, trip.dy);
      expect(inbox.dx, lessThan(trip.dx));
      // Its own icon; the album beside it keeps the folder icon it had.
      expect(find.byKey(const Key("inbox-icon")), findsOneWidget);
      expect(find.byIcon(Icons.inbox), findsOneWidget);
      expect(find.byIcon(Icons.folder), findsOneWidget);
      // One date line, and it belongs to the album — an inbox has no date.
      expect(find.byKey(const Key("folder-date")), findsOneWidget);
      expect(find.text("May 1, 2026"), findsOneWidget);
    });

    testWidgets('is never offered a share link', (tester) async {
      await pumpAt(tester, (_) => json(listingWithInbox));

      await tester.longPress(
        find
            .ancestor(
                of: find.text("Inbox"), matching: find.byType(GestureDetector))
            .first,
      );
      await tester.pumpAndSettle();

      // The server refuses `?action=share` on an inbox, so nothing offers it.
      expect(find.text("Share link…"), findsNothing);
      // What does make sense is there.
      expect(find.text("Move to…"), findsOneWidget);
      expect(find.byKey(const Key("delete-entry")), findsOneWidget);
    });
  });

  group('the create dialog', () {
    testWidgets('names an inbox by its title alone and sends the kind',
        (tester) async {
      var requests = <http.Request>[];
      await pumpAt(
        tester,
        (request) => request.method == "PUT"
            ? json('{"path":"Holiday"}')
            : json(listingWithInbox),
        requests: requests,
      );

      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text("Create album"));
      await tester.pumpAndSettle();

      // Ticking the box takes the date field away: an inbox has no date.
      expect(find.text(testL10n.dateLabel), findsOneWidget);
      await tester.tap(find.byKey(const Key("create-kind-inbox")));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.dateLabel), findsNothing);
      expect(find.byKey(const Key("create-album-date-hint")), findsNothing);

      await tester.enterText(find.byType(TextFormField).first, "Holiday");
      await tester.tap(find.text(testL10n.create));
      await tester.pumpAndSettle();

      var put = requests.singleWhere((r) => r.method == "PUT");
      // The title alone, with no date in front of it.
      expect(pathOf(put), "/valbum/data/Holiday/");
      expect(put.body, contains('"kind":"INBOX"'));
      expect(put.body, contains('"date":0'));
    });
  });

  group('the kind switch of the album properties', () {
    testWidgets('writes the kind and shows the inbox screen without leaving',
        (tester) async {
      var requests = <http.Request>[];
      var turned = false;
      await pumpAt(
        tester,
        (request) {
          if (request.method == "PUT") {
            turned = true;
            return json("");
          }
          return json(turned ? inboxJson() : plainAlbum);
        },
        route: const ["Album"],
        requests: requests,
      );

      expect(find.byType(AlbumContent), findsOneWidget);

      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("album-properties")));
      await tester.pumpAndSettle();
      expect(find.text(albumKindActionLabel(testL10n, AlbumKind.album)), findsOneWidget);

      await tester.tap(find.byKey(const Key("album-kind")));
      await tester.pumpAndSettle();
      // The dialog now shows an inbox: no date, no album picture.
      expect(find.byKey(const Key("album-date")), findsNothing);
      expect(find.text(albumKindActionLabel(testL10n, AlbumKind.inbox)), findsOneWidget);
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();

      var put = requests.singleWhere((r) => r.method == "PUT");
      expect(pathOf(put), "/valbum/data/Album/");
      expect(put.body, contains('"kind":"INBOX"'));

      // The same address, another screen: nobody left the folder.
      expect(find.byType(InboxContent), findsOneWidget);
      expect(find.byType(AlbumContent), findsNothing);
    });
  });

  group('inside a share link', () {
    testWidgets('an album the server hides is the plain refusal it always was',
        (tester) async {
      // The server answers `404` for an inbox to a link (issue #135), so the
      // app never sees one there — and a 404 inside a link is the page it has
      // always been, with no way out but the link's own root.
      await share.pumpLinkSession(tester, (request) {
        if (request.url.queryParameters["type"] == "auth") {
          return json(share.authOfLink());
        }
        return http.Response(
          '["ErrorInfo", {"message": "No such album."}]',
          404,
          headers: {"content-type": "application/json"},
        );
      });

      expect(find.byType(ShareConfinedScreen), findsOneWidget);
      expect(find.text("No such album."), findsOneWidget);
      expect(find.byType(InboxContent), findsNothing);
    });
  });
}
