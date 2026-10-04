/// Taking a copy of an original out of the album, issue #164.
///
/// The `download` right was enforced on the server, and the rights sentence
/// promised it, but the app offered no way to use it. The viewer's menu now
/// offers "Download original", the album's menu "Download n originals" for a
/// selection (and, inside a share link, for everything the link shows); the
/// bytes come through the client, bearer and all, and are handed to the
/// platform's [DownloadSaver], which a test replaces.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/downloads.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' show longPressTile, openAlbumMenu, pathOf, treeAnswer;
import 'share_album_chrome_test.dart' as chrome;
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

/// What a [RecordingSaver] was handed and fetched.
class SavedFile {
  final String name;
  final Uint8List bytes;
  final String contentType;

  const SavedFile(this.name, this.bytes, this.contentType);
}

/// A saver of the desktop's and the phone's kind, fetching through the client
/// and recording what it received.
class RecordingSaver extends DownloadSaver {
  final List<SavedFile> saved = [];

  @override
  final bool keepsArchives;

  RecordingSaver({this.keepsArchives = true});

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    var stream = await source.open();
    var bytes = <int>[];
    await for (var chunk in stream.content) {
      bytes.addAll(chunk);
    }
    saved.add(
        SavedFile(source.name, Uint8List.fromList(bytes), stream.contentType));
    return SaveOutcome.saved;
  }
}

/// A saver of the browser's kind (issue #209): it asks for the address the
/// browser would download from and never opens the file itself.
class AddressSaver extends DownloadSaver {
  /// The addresses handed to the "browser", with the file names.
  final List<(String, String)> addresses = [];

  @override
  bool get showsProgress => true;

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    addresses.add((source.name, await source.address()));
    return SaveOutcome.saved;
  }
}

/// The bytes of every original the fake server answers.
final Uint8List originalBytes =
    Uint8List.fromList([0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9]);

/// The bytes of the raw companion the fake server answers (issue #191).
final Uint8List rawBytes = Uint8List.fromList([0x49, 0x49, 0x2A, 0, 1, 2]);

/// The bytes of the archive the fake server answers.
final Uint8List zipBytes = Uint8List.fromList(utf8.encode("PK-archive"));

/// A refusal as the server words it.
http.Response refusal(String message, int status) => http.Response(
      '["ErrorInfo", {"message": "$message"}]',
      status,
      headers: {"content-type": "application/json"},
    );

/// Installs [saver] as the app's saver for the running test.
RecordingSaver installSaver([RecordingSaver? saver]) {
  var result = saver ?? RecordingSaver();
  var before = downloadSaver;
  downloadSaver = result;
  addTearDown(() => downloadSaver = before);
  return result;
}

/// A saver of the desktop's kind that has received half of a file and waits
/// for [release] to go on, see `runDownload`'s progress line (issue #209).
class BlockingSaver extends DownloadSaver {
  final Completer<void> release = Completer<void>();

  @override
  Future<SaveOutcome> save(DownloadSource source,
      {DownloadProgress? progress}) async {
    progress?.announce(2048);
    progress?.add(1024);
    await release.future;
    progress?.check();
    return SaveOutcome.saved;
  }
}

/// Installs a saver of the browser's kind for the running test.
AddressSaver installAddressSaver() {
  var result = AddressSaver();
  var before = downloadSaver;
  downloadSaver = result;
  addTearDown(() => downloadSaver = before);
  return result;
}

// --- The viewer. ---

/// A client carrying a token, answering originals and recording requests.
VAlbumClient viewerClient(
  List<http.Request> requests, {
  http.Response Function(http.Request)? original,
}) =>
    VAlbumClient(
      dataUrl: viewerDataUrl,
      token: "tok-7",
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (request.url.path == "/valbum/data/album/a.jpg" &&
            request.url.query.isEmpty) {
          return original?.call(request) ??
              http.Response.bytes(originalBytes, 200,
                  headers: {"content-type": "image/jpeg"});
        }
        if (request.url.path == "/valbum/data/album/a.CR2" &&
            request.url.query.isEmpty) {
          return http.Response.bytes(rawBytes, 200,
              headers: {"content-type": "image/x-canon-cr2"});
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      })),
    );

Future<void> pumpViewer(
  WidgetTester tester,
  VAlbumClient client, {
  List<String> rights = const ["view", "download"],
  ShareSession? share,
  bool offline = false,
  ImageKind kind = ImageKind.image,
  String raw = "",
}) async {
  var image = viewerImagePart("a.jpg", kind: kind, raw: raw);
  viewerAlbum([image], rights: rights);
  fakeImageRequests();
  await pumpViewerHarness(
    tester,
    image,
    client: client,
    caller: share == null ? viewerMember("anna") : null,
    share: share,
    offline: offline,
  );
}

Future<void> tapViewerDownload(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("viewer-menu")));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("viewer-download")));
  await tester.pumpAndSettle();
}

// --- The album. ---

/// The album `Inbox` of `move_test.dart` carrying the given rights.
String inboxWith(List<String> rights) {
  var album = fixture("album-move.json");
  return album.replaceFirst(
    '"subTitle": "",',
    '"subTitle": "", "rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}],',
  );
}

/// An album `Inbox` carrying [rights] whose photographs are rated: `a.jpg`
/// at 0, `b.jpg` at 1, the group `g1.jpg`/`g2.mp4` (represented by `g2.mp4`)
/// at 0 and `c.jpg` at -1,
/// which the standing filter (0) hides (issue #209).
String ratedAlbum(List<String> rights) =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", "subTitle": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}], '
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 1015113610000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 1}], '
    '["ImageGroup", {"representative": 1, "images": ['
    '{"kind": "IMAGE", "name": "g1.jpg", "date": 1015113620000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}, '
    '{"kind": "VIDEO", "name": "g2.mp4", "date": 1015113630000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]}], '
    '["ImagePart", {"kind": "IMAGE", "name": "c.jpg", "date": 1015113640000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": -1}]'
    ']}]';

/// The names a download request posted.
List<String> postedNames(http.Request request) => [
      for (var name in (jsonDecode(request.body) as Map)["names"] as List)
        (name as Map)["name"] as String,
    ];

/// Opens the album `Inbox` in the edit mode with `a.jpg` and `b.jpg` selected.
///
/// Without `edit` in [rights] the album is only opened; [album] is the
/// album's answer for [rights], `album-move.json` by default.
Future<void> pumpSelection(
  WidgetTester tester,
  List<http.Request> requests, {
  List<String> rights = const ["view", "download", "contribute", "edit"],
  OfflineState? offlineState,
  http.Response Function(http.Request)? zip,
  String Function(List<String> rights) album = inboxWith,
  bool select = true,
}) async {
  var client = VAlbumClient(
    dataUrl: chrome.dataUrl,
    token: "tok-9",
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      var path = pathOf(request);
      if (path == "/valbum/data/Inbox/" &&
          request.url.queryParameters["action"] == "zip") {
        return zip?.call(request) ??
            http.Response.bytes(zipBytes, 200,
                headers: {"content-type": "application/zip"});
      }
      if (path == "/valbum/data/Inbox/" &&
          request.url.queryParameters["action"] == "zip-ticket") {
        return zip?.call(request) ??
            chrome.json('{"url": "/valbum/data/Inbox/?action=zip&ticket=T1'
                '&media=d.dev.99.mac", "media": "d.dev.99.mac", '
                '"expires": "2026-10-03T12:10:00Z"}');
      }
      if (path == "/valbum/data/Inbox/a.jpg" &&
          request.url.queryParameters["type"] == "media-url") {
        return chrome.json('{"url": "/valbum/data/Inbox/a.jpg?media=d.dev.99.m",'
            ' "media": "d.dev.99.m", "expires": "2026-10-03T12:10:00Z"}');
      }
      if (path == "/valbum/data/Inbox/") {
        return chrome.json(album(rights));
      }
      if (path.startsWith("/valbum/data/Inbox/") && request.url.query.isEmpty) {
        return http.Response.bytes(originalBytes, 200,
            headers: {"content-type": "image/jpeg"});
      }
      return treeAnswer(request);
    })),
  );
  // A signed-in device: the app takes the token from its settings.
  var settings = ServerSettings(
    store: InMemorySettingsStore(
        "http://server/valbum/", "tok-9", "Phone", "anna"),
  );
  await settings.load();
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: settings,
    offlineState: offlineState,
    initialRoute: const ListingOrAlbumRoute(["Inbox"]),
  ));
  await tester.pumpAndSettle();
  if (select && rights.contains("edit")) {
    await tester.longPress(find.byType(Image).first);
    await tester.pumpAndSettle();
    await longPressTile(tester, "b.jpg");
  }
}

/// The keys of the entries of the open menu, in their order.
List<String> menuKeys(WidgetTester tester) => [
      for (var item in tester.widgetList<PopupMenuItem<dynamic>>(
          find.byWidgetPredicate((w) => w is PopupMenuItem)))
        if (item.key is ValueKey<String>) (item.key as ValueKey<String>).value,
    ];

String entryText(WidgetTester tester, String key) => tester
    .widget<Text>(
      find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text)),
    )
    .data!;

void main() {
  setUp(withEmptyImageCache);

  group('the viewer', () {
    testWidgets('offers the original to a caller with download',
        (tester) async {
      await pumpViewer(tester, viewerClient([]));

      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download")), findsOneWidget);
      expect(find.text(testL10n.viewerDownload), findsOneWidget);
    });

    testWidgets('offers the raw beside the photograph as a second entry',
        (tester) async {
      // A raw and the JPEG of its name are one photograph (issue #191).
      var saver = installSaver();
      var requests = <http.Request>[];
      await pumpViewer(tester, viewerClient(requests), raw: "a.CR2");

      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download")), findsOneWidget);
      expect(find.text(testL10n.viewerDownloadRaw), findsOneWidget);
      await tester.tap(find.byKey(const Key("viewer-download-raw")));
      await tester.pumpAndSettle();

      var fetch = requests.single;
      expect(fetch.url.toString(), "$viewerBaseUrl/a.CR2");
      expect(fetch.headers["Authorization"], "Bearer tok-7");
      var file = saver.saved.single;
      expect(file.name, "a.CR2");
      expect(file.bytes, rawBytes);
      expect(file.contentType, "image/x-canon-cr2");
    });

    testWidgets('offers no raw where the photograph has none', (tester) async {
      await pumpViewer(tester, viewerClient([]));

      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download-raw")), findsNothing);
    });

    testWidgets('offers no raw to a caller who may only look', (tester) async {
      await pumpViewer(tester, viewerClient([]),
          rights: const ["view"], raw: "a.CR2");

      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download-raw")), findsNothing);
    });

    testWidgets('offers nothing to a caller who may only look', (tester) async {
      await pumpViewer(tester, viewerClient([]), rights: const ["view"]);

      // The menu holds nothing but the About entry for a looker (#187).
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download")), findsNothing);
      expect(menuKeys(tester), ["about"]);
    });

    testWidgets('offers the original inside a share link made with download',
        (tester) async {
      await pumpViewer(
        tester,
        viewerClient([]),
        share: viewerShareSession(rights: const ["view", "download"]),
      );

      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-download")), findsOneWidget);
    });

    testWidgets('fetches the original with the bearer and hands it over',
        (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await pumpViewer(tester, viewerClient(requests));

      await tapViewerDownload(tester);

      var fetch = requests.single;
      expect(fetch.method, "GET");
      expect(fetch.url.toString(), "$viewerBaseUrl/a.jpg");
      expect(fetch.headers["Authorization"], "Bearer tok-7");
      var file = saver.saved.single;
      expect(file.name, "a.jpg");
      expect(file.bytes, originalBytes);
      expect(file.contentType, "image/jpeg");
      expect(find.text(testL10n.downloadSaved("a.jpg")), findsOneWidget);
    });

    testWidgets('downloads a video the same way', (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await pumpViewer(tester, viewerClient(requests), kind: ImageKind.video);

      await tapViewerDownload(tester);

      // The original, never the playback rendition — and in one piece. A
      // ranged request of one byte is the player's diagnostic probe of issue
      // #184 (the test's player cannot play), never the download.
      var originals = [
        for (var request in requests)
          if (request.url.query.isEmpty &&
              request.headers["Range"] != "bytes=0-0")
            request,
      ];
      expect(originals, hasLength(1));
      expect(originals.single.headers.containsKey("Range"), isFalse);
      expect(saver.saved.single.name, "a.jpg");
    });

    testWidgets('says why a refused download failed', (tester) async {
      var saver = installSaver();
      await pumpViewer(
        tester,
        viewerClient(
          [],
          original: (_) => refusal("You may not download this.", 403),
        ),
      );

      await tapViewerDownload(tester);

      expect(saver.saved, isEmpty);
      expect(
        find.text(testL10n.downloadFailed("You may not download this.")),
        findsOneWidget,
      );
    });

    testWidgets('is refused while the app is offline', (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await pumpViewer(tester, viewerClient(requests), offline: true);

      await tapViewerDownload(tester);

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(requests, isEmpty);
      expect(saver.saved, isEmpty);
    });
  });

  group('a selection', () {
    testWidgets('is offered directly after the move', (tester) async {
      await withFakeImageHttp(() async {
        await pumpSelection(tester, []);
        await openAlbumMenu(tester);

        var keys = menuKeys(tester);
        expect(keys.indexOf("download-selection"), keys.indexOf("move-to") + 1);
        expect(entryText(tester, "download-selection"), "Download 2 originals");
      });
    });

    testWidgets(
        'is one archive, fetched with the bearer and named by the album',
        (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      expect(post.method, "POST");
      expect(post.headers["Authorization"], "Bearer tok-9");
      var names = [
        for (var name in (jsonDecode(post.body) as Map)["names"] as List)
          (name as Map)["name"],
      ];
      expect(names, ["a.jpg", "b.jpg"]);
      var file = saver.saved.single;
      expect(file.name, "Inbox.zip");
      expect(file.bytes, zipBytes);
      expect(file.contentType, "application/zip");
      expect(find.text(testL10n.downloadSaved("Inbox.zip")), findsOneWidget);
    });

    testWidgets('is saved original by original where no archive is kept',
        (tester) async {
      var saver = installSaver(RecordingSaver(keepsArchives: false));
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      expect(requests.where((r) => r.url.queryParameters["action"] == "zip"),
          isEmpty);
      expect([for (var file in saver.saved) file.name], ["a.jpg", "b.jpg"]);
      expect(find.text(testL10n.downloadSavedCount(2)), findsOneWidget);
    });

    testWidgets('says the server\'s refusal', (tester) async {
      var saver = installSaver();
      await withFakeImageHttp(() async {
        await pumpSelection(
          tester,
          [],
          zip: (_) => refusal("This image is not available to you.", 403),
        );
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      expect(saver.saved, isEmpty);
      expect(
        find.text(
            testL10n.downloadFailed("This image is not available to you.")),
        findsOneWidget,
      );
    });

    // No test "without the download right": a selection needs the edit mode,
    // the edit mode needs `edit`, and `edit` implies `download` on the server
    // and in `Rights.ofNames` alike. A share link without it is below.

    testWidgets('is refused while the app is offline', (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      var state = OfflineState();
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests, offlineState: state);
        state.goneOffline(null);
        await tester.pumpAndSettle();
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(requests.where((r) => r.method != "GET"), isEmpty);
      expect(saver.saved, isEmpty);
    });
  });

  group('the progress (issue #209)', () {
    Future<BlockingSaver> startBlocked(WidgetTester tester) async {
      var saver = BlockingSaver();
      var before = downloadSaver;
      downloadSaver = saver;
      addTearDown(() => downloadSaver = before);
      await pumpViewer(tester, viewerClient([]));
      await tapViewerDownload(tester);
      await tester.pump(downloadProgressDelay);
      await tester.pumpAndSettle();
      return saver;
    }

    testWidgets('says how far it has come, and goes when it is done',
        (tester) async {
      var saver = await startBlocked(tester);

      expect(find.byKey(const Key("download-progress")), findsOneWidget);
      var line = tester
          .widget<Text>(find.byKey(const Key("download-progress-line")))
          .data!;
      expect(line, startsWith("File 1 of 1: "));
      expect(line, endsWith(" downloaded"));
      expect(
          tester
              .widget<LinearProgressIndicator>(
                  find.byType(LinearProgressIndicator))
              .value,
          0.5);

      saver.release.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("download-progress")), findsNothing);
      expect(find.text(testL10n.downloadSaved("a.jpg")), findsOneWidget);
    });

    testWidgets('is cancelled from its line', (tester) async {
      var saver = await startBlocked(tester);

      await tester.tap(find.byKey(const Key("download-cancel")));
      saver.release.complete();
      await tester.pumpAndSettle();

      expect(find.text(testL10n.downloadCancelled), findsOneWidget);
      expect(find.byKey(const Key("download-saved")), findsNothing);
    });
  });

  group('the view (issue #209)', () {
    Future<String> openEntry(WidgetTester tester) async {
      await openAlbumMenu(tester);
      return entryText(tester, "download-selection");
    }

    testWidgets(
        'is offered to a member without the edit mode, at the filter set',
        (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests,
            rights: const ["view", "download"], album: ratedAlbum);
        expect(await openEntry(tester), "Download 3 originals");
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      // Photographs and videos alike, a group as its representative alone
      // (#230), never the photograph the filter hides.
      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      expect(postedNames(post), ["a.jpg", "b.jpg", "g2.mp4"]);
      expect(saver.saved.single.name, "Inbox.zip");
    });

    testWidgets('raising the filter lowers the count', (tester) async {
      await withFakeImageHttp(() async {
        await pumpSelection(tester, [],
            rights: const ["view", "download"], album: ratedAlbum);
        await openAlbumMenu(tester);
        await tester.tap(find.text(testL10n.showFewerImages));
        await tester.pumpAndSettle();
        expect(await openEntry(tester), "Download 1 original");
        await tester.tap(find.text(testL10n.showMoreImages));
        await tester.pumpAndSettle();
        await openAlbumMenu(tester);
        await tester.tap(find.text(testL10n.showMoreImages));
        await tester.pumpAndSettle();
        expect(await openEntry(tester), "Download 4 originals");
      });
    });

    testWidgets('is offered to an editor without a selection', (tester) async {
      await withFakeImageHttp(() async {
        await pumpSelection(tester, [], album: ratedAlbum, select: false);
        expect(await openEntry(tester), "Download 3 originals");
      });
    });

    testWidgets('gives way to the selection of the edit mode', (tester) async {
      var requests = <http.Request>[];
      installSaver();
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests, album: ratedAlbum);
        expect(await openEntry(tester), "Download 2 originals");
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });
      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      expect(postedNames(post), ["a.jpg", "b.jpg"]);
    });

    testWidgets('is not offered without the download right', (tester) async {
      await withFakeImageHttp(() async {
        await pumpSelection(tester, [],
            rights: const ["view"], album: ratedAlbum);
        await openAlbumMenu(tester);
        expect(find.byKey(const Key("download-selection")), findsNothing);
      });
    });
  });

  group('in a browser (issue #209)', () {
    testWidgets('an archive is an address the browser fetches by itself',
        (tester) async {
      var saver = installAddressSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests,
            rights: const ["view", "download"], album: ratedAlbum);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      // The ticket is asked with the bearer and the names; the archive itself
      // is never fetched through the app.
      var ticket = requests
          .singleWhere((r) => r.url.queryParameters["action"] == "zip-ticket");
      expect(ticket.method, "POST");
      expect(ticket.headers["Authorization"], "Bearer tok-9");
      expect(postedNames(ticket), ["a.jpg", "b.jpg", "g2.mp4"]);
      expect(requests.where((r) => r.url.queryParameters["action"] == "zip"),
          isEmpty);
      var (name, address) = saver.addresses.single;
      expect(name, "Inbox.zip");
      expect(address,
          "http://server/valbum/data/Inbox/?action=zip&ticket=T1&media=d.dev.99.mac");
      expect(address, isNot(contains("tok-9")));
      // The browser shows its own progress, and the app its result.
      expect(find.byKey(const Key("download-progress")), findsNothing);
      expect(find.text(testL10n.downloadSaved("Inbox.zip")), findsOneWidget);
    });

    testWidgets('a refused ticket says the server\'s sentence',
        (tester) async {
      var saver = installAddressSaver();
      await withFakeImageHttp(() async {
        await pumpSelection(
          tester,
          [],
          rights: const ["view", "download"],
          album: ratedAlbum,
          zip: (_) => refusal("This image is not available to you.", 403),
        );
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      expect(saver.addresses, isEmpty);
      expect(
          find.text(
              testL10n.downloadFailed("This image is not available to you.")),
          findsOneWidget);
    });

    test('a single original is a signed address asking for an attachment',
        () async {
      var requests = <http.Request>[];
      var client = VAlbumClient(
        dataUrl: chrome.dataUrl,
        token: "tok-9",
        httpClient: MockClient((request) async {
          requests.add(request);
          return chrome.json('{"url": "/x", "media": "d.dev.99.m", '
              '"expires": "2026-10-03T12:10:00Z"}');
        }),
      );
      var saver = AddressSaver();

      var result = await downloadOne(
          client, "${chrome.dataUrl}/Inbox/a.jpg", saver: saver);

      expect(result.name, "a.jpg");
      expect(requests.single.url.queryParameters,
          {"type": "media-url", "for": "original"});
      expect(saver.addresses.single.$2,
          "${chrome.dataUrl}/Inbox/a.jpg?media=d.dev.99.m&download=1");
    });

    test('an anonymous caller is handed the plain address', () async {
      var requests = <http.Request>[];
      var client = VAlbumClient(
        dataUrl: chrome.dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          return http.Response("", 500);
        }),
      );
      var saver = AddressSaver();

      await downloadOne(client, "${chrome.dataUrl}/Inbox/a.jpg", saver: saver);

      expect(requests, isEmpty);
      expect(
          saver.addresses.single.$2, "${chrome.dataUrl}/Inbox/a.jpg?download=1");
    });
  });

  group('a share link', () {
    /// The link's album carrying [rights], answered to a link with them.
    http.Response Function(http.Request) linkWith(
      List<String> rights,
      List<http.Request> requests,
    ) =>
        (request) {
          requests.add(request);
          var rightList = rights.map((r) => '{"name": "$r"}').join(", ");
          if (request.url.queryParameters["type"] == "auth") {
            return chrome.json(chrome
                .authOfLink()
                .replaceFirst('[{"name": "view"}]', "[$rightList]"));
          }
          if (request.url.queryParameters["action"] == "zip") {
            return http.Response.bytes(zipBytes, 200,
                headers: {"content-type": "application/zip"});
          }
          return chrome.json(chrome.zooAlbum
              .replaceFirst('[{"name": "view"}]', "[$rightList]"));
        };

    testWidgets('offers everything it shows where it carries download',
        (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await chrome.pumpSession(
            tester, linkWith(const ["view", "download"], requests));
        await openAlbumMenu(tester);
        expect(entryText(tester, "download-selection"), "Download 1 original");
        await tester.tap(find.byKey(const Key("download-selection")));
        await tester.pumpAndSettle();
      });

      // One original is that original, under its own name.
      expect(saver.saved.single.name, "a.jpg");
      expect(requests.where((r) => r.url.queryParameters["action"] == "zip"),
          isEmpty);
    });

    testWidgets('offers nothing where it carries no download', (tester) async {
      await withFakeImageHttp(() async {
        await chrome.pumpSession(tester, linkWith(const ["view"], []));
        await openAlbumMenu(tester);
        expect(find.byKey(const Key("download-selection")), findsNothing);
      });
    });
  });

  test('an archive is named by the album title', () {
    expect(archiveNameOf("Zoo"), "Zoo.zip");
    expect(archiveNameOf("2024/05: Zoo?"), "2024_05_ Zoo_.zip");
    expect(archiveNameOf("  "), "album.zip");
  });
}
