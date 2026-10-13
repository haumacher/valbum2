/// Tests of what a move tells the server about the cover (issues #273, #277):
/// a move into an album the folder picker creates in the same dialog says so
/// (`created`), from the context menu of a photo with that photo as the hint
/// and from the selection bar without one. A move into an album that already
/// exists says neither, and an existing album's cover is never touched.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'inbox_view_test.dart'
    show inboxJson, openMenu, pumpInbox, reveal, tapHeading;
import 'move_test.dart'
    show
        confirmPicker,
        enterFolder,
        json,
        longPressTile,
        movedAll,
        pathOf,
        pumpAlbumEditMode,
        recordingClient,
        tile,
        treeAnswer;
import 'persons_not_a_face_test.dart' show RecordingBrowserMenu;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// Clicks the tile of [name] with the secondary mouse button, beside its
/// toolbars.
Future<void> secondaryClick(WidgetTester tester, String name) async {
  await reveal(tester, tile(name));
  var box = tester.getRect(tile(name));
  await tester.tapAt(
    Offset(box.left + 8, box.center.dy),
    buttons: kSecondaryMouseButton,
    kind: PointerDeviceKind.mouse,
  );
  await tester.pumpAndSettle();
}

/// Opens "Create new album…" in the open picker and creates [title].
Future<void> createAlbumNamed(WidgetTester tester, String title) async {
  await tester.tap(find.byKey(const Key("picker-create-album")));
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextFormField).first, title);
  await tester.pumpAndSettle();
  await tester.tap(find.text(testL10n.create));
  await tester.pumpAndSettle();
}

/// The one move the app posted.
http.Request movePost(List<http.Request> requests) =>
    requests.singleWhere((r) => r.url.queryParameters["action"] == "move");

/// The inbox, beside it the album `Trip`, every write accepted.
http.Response inboxServer(http.Request request) {
  if (request.method == "PUT") {
    return json('{"path":"2026-03-01 Ausflug","message":""}');
  }
  if (request.url.queryParameters["action"] == "move") {
    return json(movedAll(["a.jpg", "b.jpg"]));
  }
  switch (pathOf(request)) {
    case "/valbum/data/":
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"folders": [{"name": "Inbox", "title": "Inbox", "kind": "INBOX"}, '
          '{"name": "Trip", "title": "Trip"}]}]');
    case "/valbum/data/Inbox/":
      return json(inboxJson());
    case "/valbum/data/Trip/":
      return json('["AlbumInfo", {"path": "Trip", "title": "Trip", '
          '"parts": []}]');
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

void main() {
  late RecordingBrowserMenu browser;
  late BrowserMenu before;

  setUp(() {
    before = browserMenu;
    browser = RecordingBrowserMenu();
    browserMenu = browser;
  });

  tearDown(() => browserMenu = before);

  group('the inbox', () {
    testWidgets(
        'the context menu of a photo, into a new album: that photo is the '
        'cover hint', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, inboxServer, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await secondaryClick(tester, "b.jpg");
        await tester.tap(find.byKey(const Key("inbox-context-move")));
        await tester.pumpAndSettle();
        await createAlbumNamed(tester, "Ausflug");
      });

      expect(
        movePost(requests).body,
        '{"target":"2026-03-01 Ausflug",'
        '"names":[{"name":"a.jpg"},{"name":"b.jpg"}],'
        '"created":{"cover":"b.jpg"}}',
      );
    });

    testWidgets('the selection bar, into a new album: created, without a hint',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, inboxServer, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await openMenu(tester);
        await tester.tap(find.byKey(const Key("move-to")));
        await tester.pumpAndSettle();
        await createAlbumNamed(tester, "Ausflug");
      });

      expect(
        movePost(requests).body,
        '{"target":"2026-03-01 Ausflug",'
        '"names":[{"name":"a.jpg"},{"name":"b.jpg"}],'
        '"created":{"cover":""}}',
      );
    });

    testWidgets(
        'the context menu of a photo, into an existing album: neither created '
        'nor a hint', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpInbox(tester, inboxServer, requests: requests);
        await tapHeading(tester, "inbox-day-2026-03-01");
        await secondaryClick(tester, "b.jpg");
        await tester.tap(find.byKey(const Key("inbox-context-move")));
        await tester.pumpAndSettle();
        await enterFolder(tester, "Trip");
        await confirmPicker(tester);
      });

      expect(requests.where((r) => r.method == "PUT"), isEmpty);
      expect(
        movePost(requests).body,
        '{"target":"Trip","names":[{"name":"a.jpg"},{"name":"b.jpg"}]}',
      );
    });
  });

  group('the edit mode of an album', () {
    http.Response albumServer(http.Request request) {
      if (request.method == "PUT") {
        return json('{"path":"2020-01-01 Neu","message":""}');
      }
      if (request.method == "POST") {
        return json(movedAll(["a.jpg", "b.jpg"]));
      }
      return treeAnswer(request);
    }

    testWidgets(
        'the context menu of a tile, into a new album: that tile\'s photo is '
        'the cover hint', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, recordingClient(albumServer, requests));
        await longPressTile(tester, "b.jpg");
        await secondaryClick(tester, "b.jpg");
        await tester.tap(find.byKey(const Key("tile-context-move")));
        await tester.pumpAndSettle();
        await createAlbumNamed(tester, "Neu");
      });

      var body = movePost(requests).body;
      expect(body, contains('"names":[{"name":"a.jpg"},{"name":"b.jpg"}]'));
      expect(body, endsWith(',"created":{"cover":"b.jpg"}}'));
    });

    testWidgets(
        'the context menu of a tile, into an existing album: neither created '
        'nor a hint', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpAlbumEditMode(tester, recordingClient(albumServer, requests));
        await secondaryClick(tester, "a.jpg");
        await tester.tap(find.byKey(const Key("tile-context-move")));
        await tester.pumpAndSettle();
        await enterFolder(tester, "2020 Trip");
        await confirmPicker(tester);
      });

      expect(movePost(requests).body, isNot(contains('"created"')));
    });
  });
}
