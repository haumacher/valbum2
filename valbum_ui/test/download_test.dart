/// Taking a copy of an original out of the album, issue #164.
///
/// The `download` right was enforced on the server, and the rights sentence
/// promised it, but the app offered no way to use it. The viewer's menu now
/// offers "Download original", the album's menu "Download n originals" for a
/// selection (and, inside a share link, for everything the link shows); the
/// bytes come through the client, bearer and all, and are handed to the
/// platform's [DownloadSaver], which a test replaces.
library;

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

/// A saver recording what it was handed.
class RecordingSaver extends DownloadSaver {
  final List<DownloadedFile> saved = [];

  @override
  final bool keepsArchives;

  RecordingSaver({this.keepsArchives = true});

  @override
  Future<SaveOutcome> save(DownloadedFile file) async {
    saved.add(file);
    return SaveOutcome.saved;
  }
}

/// The bytes of every original the fake server answers.
final Uint8List originalBytes =
    Uint8List.fromList([0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9]);

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
}) async {
  var image = viewerImagePart("a.jpg", kind: kind);
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

/// Opens the album `Inbox` in the edit mode with `a.jpg` and `b.jpg` selected.
Future<void> pumpSelection(
  WidgetTester tester,
  List<http.Request> requests, {
  List<String> rights = const ["view", "download", "contribute", "edit"],
  OfflineState? offlineState,
  http.Response Function(http.Request)? zip,
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
      if (path == "/valbum/data/Inbox/") {
        return chrome.json(inboxWith(rights));
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
  if (rights.contains("edit")) {
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

    testWidgets('offers nothing to a caller who may only look', (tester) async {
      await pumpViewer(tester, viewerClient([]), rights: const ["view"]);

      // Nothing else is in the menu for a looker, so there is no menu.
      expect(find.byKey(const Key("viewer-menu")), findsNothing);
      expect(find.byKey(const Key("viewer-download")), findsNothing);
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

      // The original, never the playback rendition.
      var originals = [
        for (var request in requests)
          if (request.url.query.isEmpty) request,
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

    testWidgets('is not offered without a selection', (tester) async {
      await withFakeImageHttp(() async {
        await pumpSelection(tester, [], rights: const ["view", "download"]);
        await openAlbumMenu(tester);

        expect(find.byKey(const Key("download-selection")), findsNothing);
      });
    });

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
