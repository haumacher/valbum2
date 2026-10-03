/// The inbox where it is *not* the screen (issue #226): one inbox per space,
/// reached from an icon with a badge in the app bar of the start page and from
/// the same entry in its menu, never a tile of a listing, never chosen and
/// never made by switching an album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'inbox_view_test.dart' show inboxJson;
import 'move_test.dart' show json;
import 'share_link_test.dart' as share;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The start page as a current server answers it: the inbox is no entry.
const String startListing = '["ListingInfo", {"path": "", '
    '"title": "Library", "folders": ['
    '{"name": "2026-05-01 Trip", "title": "Trip", "kind": "ALBUM", '
    '"effectiveDate": 1777593600000}, '
    '{"name": "Family", "title": "Family", "kind": "FOLDER"}'
    ']}]';

/// A folder of folders below the start page.
const String familyListing = '["ListingInfo", {"path": "Family", '
    '"title": "Family", "folders": []}]';

/// The `?type=auth` answer of a signed-in member, the inbox named where
/// [inbox] is given.
String authAnswer(
        {String role = "edit", String? inbox = "Inbox", int count = 0}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role"'
    '${inbox == null ? "" : ', "inbox": "$inbox", "inboxCount": $count'}}';

/// Pumps the whole app, signed in, at [route]; [auth] answers `?type=auth`
/// and is asked anew on every request, so a test may change what it says.
Future<List<http.Request>> pumpSignedIn(
  WidgetTester tester, {
  required String Function() auth,
  List<String> route = const [],
}) async {
  var requests = <http.Request>[];
  var client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    // Only a signed-in device is asked who it is, see `_syncCaller`.
    token: "dev-1",
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      if (request.url.queryParameters["type"] == "auth") {
        return json(auth());
      }
      var path = request.url.path;
      if (path.endsWith("/Inbox/") || path.endsWith("/Inbox")) {
        return json(inboxJson(rights: const ["view", "contribute", "edit"]));
      }
      if (path.endsWith("/Family/") || path.endsWith("/Family")) {
        return json(familyListing);
      }
      return json(startListing);
    })),
  );
  var settings = ServerSettings(
    store: InMemorySettingsStore(
        "http://server/valbum/", "dev-1", "Phone", "carol"),
  );
  await settings.load();
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: settings,
      photoLibrary: FakePhotoLibrary(),
      initialRoute: ListingOrAlbumRoute(route),
    ));
    await tester.pumpAndSettle();
  });
  return requests;
}

void main() {
  group('the start page', () {
    testWidgets('carries the inbox icon with how much waits there',
        (tester) async {
      await pumpSignedIn(tester, auth: () => authAnswer(count: 12));

      expect(find.byKey(const Key("inbox-button")), findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(const Key("inbox-badge")),
          matching: find.text("12"),
        ),
        findsOneWidget,
      );
      expect(find.byTooltip(testL10n.inboxTooltip(12)), findsOneWidget);
    });

    testWidgets('shows no number for an empty inbox', (tester) async {
      await pumpSignedIn(tester, auth: () => authAnswer(count: 0));

      expect(find.byKey(const Key("inbox-button")), findsOneWidget);
      expect(
        tester
            .widget<Badge>(find.byKey(const Key("inbox-badge")))
            .isLabelVisible,
        isFalse,
      );
    });

    testWidgets('lists no inbox tile, whatever the server holds',
        (tester) async {
      await pumpSignedIn(tester, auth: () => authAnswer(count: 3));

      expect(find.text("Trip"), findsOneWidget);
      expect(find.text("Family"), findsOneWidget);
      // The tile of #136/#137 is gone: no inbox icon on a tile, no count line.
      expect(find.byKey(const Key("inbox-icon")), findsNothing);
      expect(find.byKey(const Key("inbox-count")), findsNothing);
    });

    testWidgets('opens the inbox from the icon', (tester) async {
      await pumpSignedIn(tester, auth: () => authAnswer(count: 2));

      await tester.tap(find.byKey(const Key("inbox-button")));
      await tester.pumpAndSettle();

      expect(find.byType(InboxContent), findsOneWidget);
    });

    testWidgets('opens the inbox from the menu entry', (tester) async {
      await pumpSignedIn(tester, auth: () => authAnswer(count: 2));

      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.inboxMenuEntry(2)), findsOneWidget);
      await tester.tap(find.byKey(const Key("open-inbox")));
      await tester.pumpAndSettle();

      expect(find.byType(InboxContent), findsOneWidget);
    });

    testWidgets('opens an inbox wherever the space names it', (tester) async {
      var requests = await pumpSignedIn(tester,
          auth: () => authAnswer(inbox: "Family/Inbox", count: 1));

      await tester.tap(find.byKey(const Key("inbox-button")));
      await tester.pumpAndSettle();

      expect(find.byType(InboxContent), findsOneWidget);
      expect(
        requests.map((r) => r.url.path),
        contains("/valbum/data/Family/Inbox/"),
      );
    });

    testWidgets('asks the count anew when the inbox is left', (tester) async {
      var count = 12;
      await pumpSignedIn(tester, auth: () => authAnswer(count: count));
      await tester.tap(find.byKey(const Key("inbox-button")));
      await tester.pumpAndSettle();

      // What was sorted in the inbox no longer waits there.
      count = 3;
      await tester.tap(find.byTooltip(testL10n.up));
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byKey(const Key("inbox-badge")),
          matching: find.text("3"),
        ),
        findsOneWidget,
      );
    });

    testWidgets('offers no inbox to a member who may only look',
        (tester) async {
      // The server names the inbox to whoever may put something in it, and
      // to nobody else (issue #226).
      await pumpSignedIn(tester,
          auth: () => authAnswer(role: "view", inbox: null));

      expect(find.byKey(const Key("inbox-button")), findsNothing);
      await tester.tap(find.byIcon(Icons.more_vert));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("open-inbox")), findsNothing);
    });

    testWidgets('a folder below it carries no inbox icon', (tester) async {
      await pumpSignedIn(tester,
          auth: () => authAnswer(count: 5), route: const ["Family"]);

      expect(find.byKey(const Key("inbox-button")), findsNothing);
    });
  });

  group('the album properties', () {
    testWidgets('offer no switch that makes an inbox of an album',
        (tester) async {
      await tester.pumpWidget(localizedApp(const Scaffold(
        body: AlbumPropertiesDialog(
          AlbumProperties(title: "Trip", subTitle: ""),
        ),
      )));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("album-kind")), findsNothing);
      expect(find.byType(Checkbox), findsNothing);
      expect(find.byKey(const Key("album-date")), findsOneWidget);
    });

    testWidgets('of the inbox show neither a date nor a switch',
        (tester) async {
      AlbumProperties? answered;
      await tester.pumpWidget(localizedApp(Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () async =>
                  answered = await showDialog<AlbumProperties>(
                context: context,
                builder: (_) => const AlbumPropertiesDialog(
                  AlbumProperties(
                      title: "Inbox", subTitle: "", kind: AlbumKind.inbox),
                ),
              ),
              child: const Text("open"),
            ),
          ),
        ),
      )));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("album-kind")), findsNothing);
      expect(find.byKey(const Key("album-date")), findsNothing);
      await tester.tap(find.text(testL10n.apply));
      await tester.pumpAndSettle();
      expect(answered?.kind, AlbumKind.inbox,
          reason: "the kind is the server's, carried through untouched");
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
      expect(find.byKey(const Key("inbox-button")), findsNothing);
    });
  });
}
