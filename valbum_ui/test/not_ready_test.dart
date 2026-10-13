/// Tests of issues #256 and #258 in the app: a picture the server has not
/// made yet is answered `202`; its tile shows the neutral placeholder — no
/// error, no log entry — and the page asks again by itself while something
/// is pending: once for the whole page, honouring the server's `Retry-After`,
/// short right after an upload, never in the way, and no more after
/// [reAskAttempts] re-asks that brought nothing. Issue #256 had it wait for
/// the user's reload ("no retry, no back-off"); issue #258 took that back,
/// because an upload then never showed its pictures by itself.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/not_ready.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache;

const String dataUrl = "http://server/valbum/data";

/// A photograph of the album, named [name].
String photo(String name) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": 1015113600000, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY", "rating": 0}]';

/// The album at the root, of the photographs named [names].
String albumJson(List<String> names) =>
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", '
    '"rights": [{"name": "download"}], "parts": ['
    '${names.map(photo).join(", ")}]}]';

/// The server: the album at the root, the thumbnail of a photograph answered
/// `202` while it is not in [ready].
class Server {
  final List<http.Request> requests = [];
  final List<String> names;
  final Set<String> ready = {};

  /// What the server's `Retry-After` says, in seconds.
  final int retryAfter;

  /// What the root answers instead of the album, where not `null`.
  String? root;

  Server({this.names = const ["a.jpg"], this.retryAfter = 10});

  VAlbumClient client({DiagnosticsLog? log}) => VAlbumClient(
        dataUrl: dataUrl,
        log: log,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return ready.contains(request.url.pathSegments.last)
                ? http.Response.bytes(transparentPixelPng, 200,
                    headers: {"content-type": "image/png"})
                : http.Response(
                    '["ErrorInfo", {"message": "This picture is being '
                    'prepared; please try again shortly."}]',
                    202,
                    headers: {
                        "content-type": "application/json; charset=utf-8",
                        "retry-after": "$retryAfter",
                      });
          }
          if (Uri.decodeFull(request.url.path) == "/valbum/data/") {
            return http.Response(root ?? albumJson(names), 200,
                headers: {"content-type": "application/json; charset=utf-8"});
          }
          return http.Response("No such resource", 404);
        }),
      );

  /// The thumbnail requests of the photograph [name], all of them by default.
  int thumbnails([String? name]) => requests
      .where(isThumbnailRequest)
      .where((r) => name == null || r.url.pathSegments.last == name)
      .length;
}

final Finder placeholder = find.byKey(const Key("thumbnail-not-ready"));

/// The re-ask of the page shown first.
NotReadyReAskState reAskOf(WidgetTester tester) =>
    tester.state<NotReadyReAskState>(find.byType(NotReadyReAsk).first);

Future<void> start(WidgetTester tester, Server server,
    {DiagnosticsLog? log}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(client: server.client(log: log)));
    await tester.pumpAndSettle();
  });
}

/// Lets [time] pass on the test's clock, and what it brings happen.
Future<void> wait(WidgetTester tester, Duration time) async {
  await withFakeImageHttp(() async {
    await tester.pump(time);
    await tester.pumpAndSettle();
  });
}

void main() {
  setUp(() {
    withEmptyImageCache();
    forgetThumbnailBytes();
    resetNotReady();
  });

  testWidgets(
      "a picture not made yet is a placeholder, and shows by itself once the "
      "server made it, after the server's Retry-After", (tester) async {
    var server = Server();
    var log = DiagnosticsLog();
    await start(tester, server, log: log);

    expect(placeholder, findsOneWidget);
    expect(tester.takeException(), isNull, reason: "no failure");
    expect(find.byIcon(Icons.broken_image), findsNothing);
    expect(find.byType(ErrorWidget), findsNothing);
    expect(log.entries, isEmpty, reason: "nothing written down");
    expect(server.thumbnails(), 1);
    expect(hasNotReadyImages, isTrue);

    // Not before the server's Retry-After.
    await wait(tester, const Duration(seconds: 9));
    expect(server.thumbnails(), 1);

    // The background made it meanwhile; nobody reloads.
    server.ready.add("a.jpg");
    await wait(tester, const Duration(seconds: 1));
    expect(server.thumbnails(), 2, reason: "asked again by itself");
    expect(placeholder, findsNothing);
    expect(find.byType(Image), findsOneWidget);
    expect(tester.takeException(), isNull);
    expect(log.entries, isEmpty);

    // Nothing pending: nothing is asked any more.
    await wait(tester, const Duration(minutes: 5));
    expect(server.thumbnails(), 2);
  });

  testWidgets(
      "one re-ask covers every tile not made yet, and a tile that is shown "
      "is not fetched again", (tester) async {
    var server = Server(names: const ["a.jpg", "b.jpg", "c.jpg", "d.jpg"])
      ..ready.add("a.jpg");
    await start(tester, server);
    expect(placeholder, findsNWidgets(3));
    for (var name in const ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]) {
      expect(server.thumbnails(name), 1, reason: name);
    }

    server.ready.addAll(const ["b.jpg", "c.jpg", "d.jpg"]);
    await wait(tester, const Duration(seconds: 10));
    expect(reAskOf(tester).reAsked, 1, reason: "one re-ask for the page");
    expect(server.thumbnails("a.jpg"), 1, reason: "shown: not asked again");
    for (var name in const ["b.jpg", "c.jpg", "d.jpg"]) {
      expect(server.thumbnails(name), 2, reason: name);
    }
    expect(placeholder, findsNothing);
    expect(find.byType(Image), findsNWidgets(4));
  });

  testWidgets("nothing is asked again where nothing is pending",
      (tester) async {
    var server = Server(names: const ["a.jpg", "b.jpg"])
      ..ready.addAll(const ["a.jpg", "b.jpg"]);
    await start(tester, server);
    var asked = server.requests.length;
    await wait(tester, const Duration(minutes: 5));
    expect(server.requests.length, asked);
    expect(reAskOf(tester).reAsked, 0);
  });

  testWidgets("the re-asks stop after the cap, and a reload asks again",
      (tester) async {
    var server = Server();
    await start(tester, server);
    for (var n = 0; n < reAskAttempts + 5; n++) {
      await wait(tester, const Duration(seconds: 10));
    }
    expect(server.thumbnails(), 1 + reAskAttempts);
    expect(placeholder, findsOneWidget);

    // The user's reload.
    server.ready.add("a.jpg");
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text(testL10n.reload).last);
      await tester.pumpAndSettle();
    });
    expect(server.thumbnails(), 2 + reAskAttempts);
    expect(placeholder, findsNothing);
  });

  testWidgets("nothing is asked again in the select mode", (tester) async {
    var server = Server(names: const ["a.jpg", "b.jpg"])..ready.add("a.jpg");
    await start(tester, server);
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("select-mode")));
      await tester.pumpAndSettle();
    });
    expect(
        tester.state<AlbumContentState>(find.byType(AlbumContent)).selectMode,
        isTrue,
        reason: "in the select mode");
    var asked = server.thumbnails("b.jpg");

    await wait(tester, const Duration(minutes: 1));
    expect(reAskOf(tester).reAsked, 0);
    expect(server.thumbnails("b.jpg"), asked);
    expect(
        tester.state<AlbumContentState>(find.byType(AlbumContent)).selectMode,
        isTrue,
        reason: "still in the select mode");
  });

  testWidgets("the album is not asked again while the viewer covers it",
      (tester) async {
    var server = Server(names: const ["a.jpg", "b.jpg"])..ready.add("a.jpg");
    await start(tester, server);
    await withFakeImageHttp(() async {
      await tester.tap(find.byType(Image).first);
      await tester.pumpAndSettle();
    });
    expect(find.byType(ImageView), findsOneWidget);
    var asked = server.thumbnails("b.jpg");

    await wait(tester, const Duration(minutes: 1));
    expect(server.thumbnails("b.jpg"), asked);
    expect(reAskOf(tester).reAsked, 0);
  });

  testWidgets(
      "after an upload of this app the waits start at a second and double "
      "up to the server's Retry-After", (tester) async {
    var delays = <Duration>[];
    reAskTimer = (delay, callback) {
      delays.add(delay);
      return Timer(delay, callback);
    };
    addTearDown(() => reAskTimer = Timer.new);
    var server = Server();
    await start(tester, server);
    expect(delays, [const Duration(seconds: 10)]);

    // What the end of an upload says, see VAlbumState.
    noteUpload();
    for (var n = 0; n < 5; n++) {
      await wait(tester, delays.last);
    }
    expect(delays.skip(1).take(5), const [
      Duration(seconds: 1),
      Duration(seconds: 2),
      Duration(seconds: 4),
      Duration(seconds: 8),
      Duration(seconds: 10),
    ]);
  });

  testWidgets(
      "the tile of a listing whose large or cut preview is not made yet shows "
      "it by itself (issue #260)", (tester) async {
    await tester.binding.setSurfaceSize(const Size(1000, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    var server = Server()
      ..root = '["ListingInfo", {"path": "", "title": "Library", "folders": ['
          '{"name": "A", "title": "Holiday", "kind": "ALBUM", '
          '"starred": true, "indexPicture": {"image": "a.jpg", "scale": 1, '
          '"tx": 0, "ty": 0, "crop": {"x": 0.25, "y": 0.25, "w": 0.5, '
          '"h": 0.5}}}]}]';
    await start(tester, server);
    expect(placeholder, findsOneWidget);
    var asked = server.requests.where(isThumbnailRequest).single.url;
    expect(asked.queryParameters.containsKey("crop"), isTrue,
        reason: "the cut preview of the cover: $asked");

    server.ready.add("a.jpg");
    await wait(tester, const Duration(seconds: 10));
    expect(server.thumbnails(), 2);
    expect(placeholder, findsNothing);
    expect(find.byType(Image), findsOneWidget);
  });

  test("a 202 of a picture is no failure of the client", () async {
    var server = Server();
    var log = DiagnosticsLog();
    var client = server.client(log: log);
    await expectLater(
        client.thumbnailBytes("$dataUrl/a.jpg"),
        throwsA(isA<ImageNotReady>().having(
            (e) => e.retryAfter, "retryAfter", const Duration(seconds: 10))));
    expect(
        isImageNotReady(const ImageNotReady("$dataUrl/a.jpg?type=tn")), isTrue);
    expect(
        isImageNotReady(NetworkImageLoadException(
            statusCode: 202, uri: Uri.parse("$dataUrl/a.heic?type=display"))),
        isTrue);
    expect(
        isImageNotReady(NetworkImageLoadException(
            statusCode: 500, uri: Uri.parse("$dataUrl/a.heic?type=display"))),
        isFalse);
    expect(log.entries, isEmpty);
  });
}
