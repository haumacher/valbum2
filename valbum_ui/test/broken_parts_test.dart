/// Tests of issue #253 in the app: a file the server could not read is shown
/// as a square broken tile and a page of its own in the viewer, never asked a
/// rendition of nor decoded; an album the server has not read yet says that it
/// is being prepared, and is asked for again when the user reloads it (#256).
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/not_ready.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache;

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

const String readable =
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]';

const String brokenPhoto =
    '["ImagePart", {"kind": "IMAGE", "name": "bad.jpg", "date": 1015113610000, '
    '"orientation": "IDENTITY", "rating": 0, '
    '"broken": "Not a JPEG file: starts with 0x00 0x00", "fileSize": 123456}]';

const String brokenVideo =
    '["ImagePart", {"kind": "VIDEO", "name": "clip.mp4", '
    '"date": 1015113620000, "orientation": "IDENTITY", "rating": 0, '
    '"broken": "moov atom not found", "fileSize": 4294967296}]';

String album(
  List<String> parts, {
  bool preparing = false,
  List<String> rights = const ["download"],
}) =>
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", '
    '"preparing": $preparing, '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"parts": [${parts.join(", ")}]}]';

/// The server of these tests: the album at the root, answered by [answer],
/// and every request recorded.
class Server {
  final List<http.Request> requests = [];
  String answer;

  Server(this.answer);

  VAlbumClient get client => VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          if (Uri.decodeFull(request.url.path) == "/valbum/data/") {
            return json(answer);
          }
          return http.Response("No such resource", 404);
        }),
      );

  /// Every request naming [name], whatever it asked of it.
  List<http.Request> naming(String name) => [
        for (var request in requests)
          if (Uri.decodeFull(request.url.path).contains(name)) request,
      ];

  /// How often the album was fetched.
  int get albumFetches => requests
      .where((r) => Uri.decodeFull(r.url.path) == "/valbum/data/")
      .length;
}

void main() {
  group("a broken file in the album", () {
    testWidgets("is a square tile with its icon and name, asked nothing of",
        (tester) async {
      var server = Server(album([readable, brokenPhoto, brokenVideo]));
      var originals = <String>[];
      fakeImageRequests(requested: originals);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
      });

      var photo = find.byKey(const ValueKey("broken-tile-bad.jpg"));
      var video = find.byKey(const ValueKey("broken-tile-clip.mp4"));
      expect(photo, findsOneWidget);
      expect(video, findsOneWidget);
      var size = tester.getSize(photo);
      expect(size.width, greaterThan(0));
      expect(size.width, closeTo(size.height, 0.01), reason: "square");
      expect(find.descendant(of: photo, matching: find.text("bad.jpg")),
          findsOneWidget);
      expect(
          find.descendant(
              of: photo, matching: find.byKey(const Key("broken-icon-image"))),
          findsOneWidget);
      expect(
          find.descendant(of: photo, matching: find.byIcon(Icons.broken_image)),
          findsOneWidget);

      // A broken video shows the broken-video icon, and no play mark.
      expect(
          find.descendant(
              of: video, matching: find.byKey(const Key("broken-icon-video"))),
          findsOneWidget);
      expect(
          find.descendant(of: video, matching: find.byIcon(Icons.videocam_off)),
          findsOneWidget);
      expect(find.byKey(const Key("video-indicator")), findsNothing);
      expect(find.byType(VideoTeaser), findsNothing);

      // The readable photo is asked its thumbnail, the broken files nothing.
      expect(server.naming("a.jpg").where(isThumbnailRequest), isNotEmpty);
      expect(server.naming("bad.jpg"), isEmpty);
      expect(server.naming("clip.mp4"), isEmpty);
      expect(originals.where((url) => url.contains("bad.jpg")), isEmpty);
      expect(originals.where((url) => url.contains("clip.mp4")), isEmpty);
    });

    testWidgets("opens the viewer on a page saying why it cannot be shown",
        (tester) async {
      var server = Server(album([readable, brokenPhoto, brokenVideo]));
      var originals = <String>[];
      fakeImageRequests(requested: originals);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey("broken-tile-bad.jpg")));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(const Key("broken-viewer")), findsOneWidget);
      expect(find.byKey(const Key("broken-viewer-icon-image")), findsOneWidget);
      expect(find.byKey(const Key("broken-viewer-name")), findsOneWidget);
      expect(find.text("bad.jpg"), findsWidgets);
      // The server's reason, verbatim, and the size in a word.
      expect(
          find.text(testL10n
              .brokenFileReason("Not a JPEG file: starts with 0x00 0x00")),
          findsOneWidget);
      expect(find.text(testL10n.brokenFileSize("121 kB")), findsOneWidget);
      expect(find.text(testL10n.brokenImageExplanation), findsOneWidget);

      // Nothing of it was asked for or decoded: no thumbnail, no preview, no
      // original.
      expect(server.naming("bad.jpg"), isEmpty);
      expect(originals.where((url) => url.contains("bad.jpg")), isEmpty);

      // The original can still be downloaded.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("viewer-menu")));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("viewer-download")), findsOneWidget);
      expect(find.byKey(const Key("viewer-crop")), findsNothing);
    });

    testWidgets("a broken video starts no player", (tester) async {
      var server = Server(album([readable, brokenVideo]));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey("broken-tile-clip.mp4")));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(const Key("broken-viewer-icon-video")), findsOneWidget);
      expect(find.text(testL10n.brokenVideoExplanation), findsOneWidget);
      expect(find.text(testL10n.brokenFileReason("moov atom not found")),
          findsOneWidget);
      expect(find.text(testL10n.brokenFileSize("4.0 GB")), findsOneWidget);
      expect(find.byType(VideoView), findsNothing);
      expect(server.naming("clip.mp4"), isEmpty);
    });

    testWidgets("is paged past from a neighbour without being decoded",
        (tester) async {
      // A neighbour decoded by an earlier test would never be asked again.
      withEmptyImageCache();
      var server = Server(album([readable, brokenPhoto]));
      var originals = <String>[];
      fakeImageRequests(requested: originals);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
        await tester.tap(find.byType(Image).first);
        await tester.pumpAndSettle();
      });
      // The readable photo is shown, and its broken neighbour not prefetched.
      expect(originals.where((url) => url.contains("a.jpg")), isNotEmpty);
      expect(originals.where((url) => url.contains("bad.jpg")), isEmpty);

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.chevron_right));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("broken-viewer")), findsOneWidget);
      expect(server.naming("bad.jpg"), isEmpty);
      expect(originals.where((url) => url.contains("bad.jpg")), isEmpty);
    });
  });

  testWidgets(
      "in the edit mode offers the rating but nothing that needs a "
      "picture", (tester) async {
    var server = Server(album([readable, brokenPhoto],
        rights: const ["view", "download", "edit"]));
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: server.client));
      await tester.pumpAndSettle();
      await tester.longPress(find.byKey(const ValueKey("broken-tile-bad.jpg")));
      await tester.pumpAndSettle();
    });
    var tile = find.ancestor(
      of: find.byKey(const ValueKey("broken-tile-bad.jpg")),
      matching: find.byKey(const ValueKey("bad.jpg")),
    );
    expect(tile, findsOneWidget);
    Finder onTile(IconData icon) =>
        find.descendant(of: tile, matching: find.byIcon(icon));
    // Selected, and rated like any other file: "Trash" is how it goes.
    expect(onTile(Icons.check_box), findsOneWidget);
    expect(
        find.descendant(
            of: tile, matching: find.byKey(const Key("rating-trash"))),
        findsOneWidget);
    // Nothing to turn, to date by its camera or to show for the album.
    expect(onTile(Icons.rotate_right), findsNothing);
    expect(onTile(Icons.more_time), findsNothing);
    expect(onTile(Icons.photo_album), findsNothing);
    expect(server.naming("bad.jpg"), isEmpty);
  });

  group("an album being prepared", () {
    /// The reload of the album's menu, the one way it is asked for again.
    Future<void> reload(WidgetTester tester) async {
      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.reload).last);
      await tester.pumpAndSettle();
    }

    testWidgets(
        "says so, asks again by itself, and shows its photos once "
        "the server has read them", (tester) async {
      var server = Server(album(const [], preparing: true));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("album-preparing")), findsOneWidget);
      expect(find.text(testL10n.albumPreparing), findsOneWidget);
      var fetches = server.albumFetches;

      // Asked again by itself while it is being prepared (issue #258, which
      // took back the "press reload" of issue #256), once per wait.
      await withFakeImageHttp(() async {
        await tester.pump(reAskDefault);
        await tester.pumpAndSettle();
      });
      expect(server.albumFetches, fetches + 1);
      expect(find.byKey(const Key("album-preparing")), findsOneWidget);

      // The background has read the folder: the next re-ask shows it.
      server.answer = album([readable, brokenPhoto]);
      await withFakeImageHttp(() async {
        await tester.pump(reAskDefault);
        await tester.pumpAndSettle();
      });
      expect(server.albumFetches, fetches + 2);
      expect(find.byKey(const Key("album-preparing")), findsNothing);
      expect(find.byKey(const Key("album-still-preparing")), findsNothing);
      expect(find.byType(Image), findsOneWidget);
      expect(find.byKey(const ValueKey("broken-tile-bad.jpg")), findsOneWidget);

      // Nothing is pending any more: nothing asks again.
      await withFakeImageHttp(() async {
        await tester.pump(const Duration(minutes: 5));
        await tester.pumpAndSettle();
      });
      expect(server.albumFetches, fetches + 2);
    });

    testWidgets("is not asked again in the select mode", (tester) async {
      var server = Server(album([readable], preparing: true));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert).last);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("select-mode")));
        await tester.pumpAndSettle();
      });
      expect(
          tester.state<AlbumContentState>(find.byType(AlbumContent)).selectMode,
          isTrue,
          reason: "in the select mode");
      var fetches = server.albumFetches;
      await withFakeImageHttp(() async {
        await tester.pump(const Duration(minutes: 1));
        await tester.pumpAndSettle();
      });
      expect(server.albumFetches, fetches,
          reason: "a re-read album would take the selection away");
      expect(
          tester.state<AlbumContentState>(find.byType(AlbumContent)).selectMode,
          isTrue);
    });

    testWidgets("with photos of an older build shows them and a note",
        (tester) async {
      var server = Server(album([readable], preparing: true));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: server.client));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("album-still-preparing")), findsOneWidget);
      expect(find.text(testL10n.albumStillPreparing), findsOneWidget);
      expect(find.byKey(const Key("album-preparing")), findsNothing);
      expect(find.byType(Image), findsOneWidget);

      server.answer = album([readable, brokenPhoto]);
      await withFakeImageHttp(() async {
        await reload(tester);
      });
      expect(find.byKey(const Key("album-still-preparing")), findsNothing);
      expect(find.byKey(const ValueKey("broken-tile-bad.jpg")), findsOneWidget);
    });
  });
}
