/// Tests of the inbox screen, the app half of issues #131 and #136.
///
/// An inbox is an album of kind `INBOX`: the same resource, another screen.
/// What is pinned here is what makes it another one — the derived day and
/// month headings, the heading that selects everything under it, the actions
/// that write at once (and refuse while the app is offline), and the plain
/// fact that there is no edit session behind it at all.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// A photograph of the inbox, taken at noon of the given day.
String part(String name, DateTime taken) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", '
    '"date": ${taken.millisecondsSinceEpoch}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": 0}]';

DateTime noon(int year, int month, int day) => DateTime(year, month, day, 12);

/// Three days across two months, the order the server answers them in.
const List<(String, int, int, int)> inboxPhotos = [
  ("a.jpg", 2026, 3, 1),
  ("b.jpg", 2026, 3, 1),
  ("c.jpg", 2026, 3, 2),
  ("d.jpg", 2026, 4, 5),
];

/// The inbox as the server answers it, flat and sorted by date.
String inboxJson({
  List<String> rights = const [],
  bool undated = false,
  List<(String, int, int, int)> photos = inboxPhotos,
}) =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", "subTitle": "", '
    '"kind": "INBOX", '
    '${rights.isEmpty ? "" : '"rights": ['
        '${rights.map((r) => '{"name": "$r"}').join(", ")}], '}'
    '"parts": [${[
      for (var (name, y, m, d) in photos) part(name, noon(y, m, d)),
      if (undated)
        '["ImagePart", {"kind": "IMAGE", "name": "z.jpg", "date": 0, '
            '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]',
    ].join(", ")}]}]';

/// The tree the tests browse: the root listing and the inbox in it.
http.Response inboxTree(http.Request request, {String? album}) {
  switch (pathOf(request)) {
    case "/valbum/data/":
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"folders": [{"name": "Inbox", "title": "Inbox", "kind": "INBOX"}]}]');
    case "/valbum/data/Inbox/":
      return json(album ?? inboxJson());
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

/// Opens the inbox over a server answering [handler].
Future<void> pumpInbox(
  WidgetTester tester,
  http.Response Function(http.Request) handler, {
  List<http.Request>? requests,
  OfflineState? offlineState,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: recordingClient(handler, requests ?? []),
      offlineState: offlineState,
      initialRoute: const ListingOrAlbumRoute(["Inbox"]),
    ));
    await tester.pumpAndSettle();
  });
}

/// The state of the inbox screen on the screen.
InboxContentState inboxState(WidgetTester tester) =>
    tester.state<InboxContentState>(find.byType(InboxContent));

/// The file names currently selected.
List<String> selectedNames(WidgetTester tester) =>
    [for (var image in inboxState(tester).selected) image.name];

/// Taps the heading with the given key.
Future<void> tapHeading(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

/// Taps the middle of the tile of [name], which selects it.
Future<void> tapTileOf(WidgetTester tester, String name) async {
  await tester.tapAt(tester.getRect(tile(name)).center);
  await tester.pumpAndSettle();
}

/// Opens the inbox menu.
Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

void main() {
  group('the derived headings', () {
    test('open a day per date and put the undated last', () {
      var album = Resource.fromString(inboxJson(undated: true)) as AlbumInfo;
      var days = inboxDays(album.parts);

      expect(days, hasLength(4));
      expect([
        for (var day in days) day.images.map((i) => i.name).toList()
      ], [
        ["a.jpg", "b.jpg"],
        ["c.jpg"],
        ["d.jpg"],
        ["z.jpg"],
      ]);
      expect(days.last.undated, isTrue);
      expect(days.last.heading(testL10n), inboxUndatedHeading(testL10n));
      expect(days.first.month, DateTime(2026, 3));
      expect(days.last.month, isNull);
    });
  });

  group('the inbox screen', () {
    testWidgets('draws a month line and a heading per day', (tester) async {
      await pumpInbox(tester, inboxTree);

      // Two months, three days. The rows of an inbox are built on demand
      // like an album's (issue #111), so the far end is scrolled to.
      expect(find.byKey(inboxMonthKey(DateTime(2026, 3))), findsOneWidget);
      expect(find.byKey(const Key("inbox-day-2026-03-01")), findsOneWidget);
      expect(find.byKey(const Key("inbox-day-2026-03-02")), findsOneWidget);
      // Written out in the locale's own words, never as a stored heading.
      expect(
        find.text(inboxDayFormat(testL10n).format(DateTime(2026, 3, 1))),
        findsOneWidget,
      );

      await tester.drag(find.byType(CustomScrollView), const Offset(0, -1200));
      await tester.pumpAndSettle();

      expect(find.byKey(inboxMonthKey(DateTime(2026, 4))), findsOneWidget);
      expect(find.byKey(const Key("inbox-day-2026-04-05")), findsOneWidget);
      expect(
        find.text(inboxMonthFormat(testL10n).format(DateTime(2026, 4))),
        findsOneWidget,
      );
    });

    testWidgets('left-aligns the headings, check box first', (tester) async {
      await pumpInbox(tester, inboxTree);

      var heading = find.byKey(const Key("inbox-day-2026-03-01"));
      var box = find.descendant(
          of: heading, matching: find.byIcon(Icons.check_box_outline_blank));
      var text = find.descendant(
          of: heading,
          matching:
              find.text(inboxDayFormat(testL10n).format(DateTime(2026, 3, 1))));
      var row = tester.widget<Row>(
          find.descendant(of: heading, matching: find.byType(Row)));

      expect(row.mainAxisAlignment, MainAxisAlignment.start);
      // The check box starts at the 16 lp gutter, the text right behind it.
      expect(tester.getTopLeft(box).dx, 16);
      expect(tester.getTopLeft(text).dx, lessThan(80));
    });

    testWidgets('selects a day by its heading, and unselects it again',
        (tester) async {
      await pumpInbox(tester, inboxTree);

      await tapHeading(tester, "inbox-day-2026-03-01");
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
      expect(find.byKey(const Key("inbox-selection")), findsOneWidget);

      // The second tap takes exactly those back out.
      await tapHeading(tester, "inbox-day-2026-03-01");
      expect(selectedNames(tester), isEmpty);
    });

    testWidgets('selects both days of a month by its line', (tester) async {
      await pumpInbox(tester, inboxTree);

      await tester.tap(find.byKey(inboxMonthKey(DateTime(2026, 3))));
      await tester.pumpAndSettle();

      // March, and nothing of April.
      expect(selectedNames(tester), ["a.jpg", "b.jpg", "c.jpg"]);
    });

    testWidgets('offers nothing an album offers and an inbox has not',
        (tester) async {
      await pumpInbox(tester, inboxTree);

      // No edit session: no Save, no Cancel, no drag handles, no reordering.
      expect(find.byKey(const Key("edit-cancel")), findsNothing);
      expect(find.byIcon(Icons.save), findsNothing);
      expect(find.byKey(const Key("drag-handle")), findsNothing);

      await openMenu(tester);
      // No "view as": an inbox is shown to nobody else, see issue #135.
      for (var view in ["owner", "members", "public"]) {
        expect(find.byKey(Key("view-as-$view")), findsNothing);
      }
      // The properties are there — they carry the kind switch and nothing
      // else an inbox has any use for.
      expect(find.byKey(const Key("album-properties")), findsOneWidget);
    });
  });

  group('deleting photographs of the inbox', () {
    testWidgets('posts the selected names and reads the outcome out',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(
        tester,
        (request) => request.method == "POST"
            ? json('{"outcomes":[{"name":"a.jpg","newName":"a.jpg",'
                '"message":"Moved to the trash of the space."},'
                '{"name":"b.jpg","newName":"b.jpg","message":""}]}')
            : inboxTree(request),
        requests: requests,
      );

      await tapHeading(tester, "inbox-day-2026-03-01");
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("delete-selection")));
      await tester.pumpAndSettle();
      // Nothing is deleted from disk, and the question says so.
      expect(find.text(testL10n.inboxDeleteExplanation), findsOneWidget);
      await tester.tap(find.byKey(const Key("inbox-delete-confirm")));
      await tester.pumpAndSettle();

      var post = requests.singleWhere((r) => r.method == "POST");
      expect(
        Uri.decodeFull(post.url.toString()),
        "$dataUrl/Inbox/?action=delete",
      );
      expect(
        post.body,
        '{"target":"","names":[{"name":"a.jpg"},{"name":"b.jpg"}]}',
      );
      // The server's own words, verbatim.
      expect(
        find.textContaining("Moved to the trash of the space."),
        findsOneWidget,
      );
    });

    testWidgets('is refused while the app is offline, and posts nothing',
        (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await pumpInbox(tester, inboxTree,
          requests: requests, offlineState: state);
      state.goneOffline(null);
      await tester.pumpAndSettle();

      await tapHeading(tester, "inbox-day-2026-03-01");
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("delete-selection")));
      await tester.pumpAndSettle();

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(find.byKey(const Key("inbox-delete-dialog")), findsNothing);
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  group('a rotation in the inbox', () {
    testWidgets('writes the sidecar at once and leaves no edit behind',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(
        tester,
        (request) => request.method == "PUT" ? json("") : inboxTree(request),
        requests: requests,
      );

      // The tools of a tile show while it is selected; a tap selects.
      await tapTileOf(tester, "a.jpg");
      await tester.tap(find.byKey(const Key("inbox-rotate-right")).first);
      await tester.pumpAndSettle();

      var puts = requests.where((r) => r.method == "PUT").toList();
      expect(puts, hasLength(1));
      expect(pathOf(puts.single), "/valbum/data/Inbox/");
      expect(puts.single.body, contains('"orientation":"ROT_R"'));

      // No edit session was ever opened, so the way out asks nothing.
      var delegate = tester
          .state<VAlbumState>(find.byType(VAlbumView).first)
          .navigator
          .delegate;
      expect(delegate.editSession(const ["Inbox"]).editMode, isFalse);
      expect(await delegate.leaveAlbum(const ["Inbox"]), isTrue);
      expect(find.byKey(const Key("leave-dialog")), findsNothing);
    });

    testWidgets('takes the turn back when the server refuses it',
        (tester) async {
      await pumpInbox(
        tester,
        (request) => request.method == "PUT"
            ? http.Response(
                '["ErrorInfo", {"message": "Not your inbox."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : inboxTree(request),
      );

      await tapTileOf(tester, "a.jpg");
      await tester.tap(find.byKey(const Key("inbox-rotate-right")).first);
      await tester.pumpAndSettle();

      expect(find.text("Not your inbox."), findsOneWidget);
      var shown = inboxState(tester).images.first;
      expect(shown.orientation, Orientation.identity);
    });
  });

  group('what a photograph of an inbox says', () {
    testWidgets('shows the details and no description field', (tester) async {
      await pumpInbox(tester, inboxTree);

      await tapTileOf(tester, "a.jpg");
      await tester.tap(find.byKey(const Key("inbox-properties")).first);
      await tester.pumpAndSettle();

      // The details block, as everywhere else (issue #122) ...
      expect(find.byKey(const Key("properties-details")), findsOneWidget);
      expect(find.text("File: a.jpg"), findsOneWidget);
      // ... and nothing to type in: a description belongs to the album the
      // photograph ends up in, see issue #136.
      expect(
        find.byKey(const Key("image-properties-read-only")),
        findsOneWidget,
      );
      expect(find.byType(TextField), findsNothing);
    });
  });

  group('moving a day out of the inbox', () {
    testWidgets('offers the picker with the images of the selection',
        (tester) async {
      var requests = <http.Request>[];
      await pumpInbox(
        tester,
        (request) {
          if (request.method == "POST") {
            return json(movedAll(["a.jpg", "b.jpg"]));
          }
          if (pathOf(request) == "/valbum/data/2021/") {
            return json('["ListingInfo", {"path": "2021", "title": "2021", '
                '"folders": []}]');
          }
          return inboxTree(request);
        },
        requests: requests,
      );

      await tapHeading(tester, "inbox-day-2026-03-01");
      await openMenu(tester);
      expect(find.text(inboxMoveLabel(testL10n, 2)), findsOneWidget);
      await tester.tap(find.byKey(const Key("move-to")));
      await tester.pumpAndSettle();
      // An image lives in an album, so a folder of folders cannot be picked
      // and the picker says why, see issue #113.
      expect(find.byKey(const Key("picker-needs-album")), findsOneWidget);
      // And the album that does not exist yet is offered, see issue #114.
      expect(find.byKey(const Key("picker-create-album")), findsOneWidget);
    });
  });

  group('a caller who may only look', () {
    testWidgets('is offered no write and no take-back', (tester) async {
      await pumpInbox(
        tester,
        (request) => inboxTree(
          request,
          album: inboxJson(rights: const ["view", "download"]),
        ),
      );

      await tapTileOf(tester, "a.jpg");
      expect(find.byKey(const Key("inbox-rotate-right")), findsNothing);
      expect(find.byKey(const Key("privacy-control")), findsNothing);

      await openMenu(tester);
      expect(find.byKey(const Key("move-to")), findsNothing);
      expect(find.byKey(const Key("delete-selection")), findsNothing);
      expect(find.byKey(const Key("album-properties")), findsNothing);
    });
  });
}
