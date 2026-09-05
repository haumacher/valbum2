/// Tests of the offline cache (issue #31): what the client does when the
/// server cannot be reached, what the app then says, what it refuses, how the
/// cache is bounded and cleared, and how a thumbnail finds its bytes.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/offline_file.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String serverDataUrl = "http://server/valbum/data";

/// Answers a request the way the test's [answer] says, but serves thumbnails.
VAlbumClient cachingClient(
  Future<http.Response> Function(http.Request request) answer, {
  required OfflineCache cache,
  OfflineState? offlineState,
  String? token,
  List<http.BaseRequest>? requests,
}) =>
    VAlbumClient(
      dataUrl: serverDataUrl,
      token: token,
      cache: cache,
      offlineState: offlineState,
      httpClient: MockClient((request) async {
        requests?.add(request);
        return answer(request);
      }),
    );

/// A transport that is simply not there.
Never unreachable(http.Request request) =>
    throw http.ClientException("Connection refused", request.url);

/// The listing the fixture serves.
String get listingJson => fixture("listing.json");

/// The album the fixture serves.
String get albumJson => fixture("album.json");

void main() {
  group('loadResource', () {
    test('writes the server answer through into the cache', () async {
      var cache = MemoryOfflineCache();
      var client = cachingClient(
        (_) async => http.Response(listingJson, 200),
        cache: cache,
      );

      var resource = await client.loadResource(const []);

      expect(resource, isA<ListingInfo>());
      var entry = await cache.getResource(serverDataUrl, const []);
      expect(entry, isNotNull);
      expect(entry!.text, listingJson);
      expect(await cache.size(), greaterThan(0));
    });

    test('answers from the cache when the server cannot be reached', () async {
      var cache = MemoryOfflineCache();
      var state = OfflineState();
      var reachable = true;
      var client = cachingClient(
        (request) async =>
            reachable ? http.Response(listingJson, 200) : unreachable(request),
        cache: cache,
        offlineState: state,
      );

      await client.loadResource(const []);
      expect(state.offline, isFalse);
      var stored = (await cache.getResource(serverDataUrl, const []))!.storedAt;

      reachable = false;
      var again = await client.loadResource(const []);

      expect((again as ListingInfo).title, "Test-album");
      expect(state.offline, isTrue);
      expect(state.lastUpdated, stored);

      // And the server coming back clears the flag again.
      reachable = true;
      await client.loadResource(const []);
      expect(state.offline, isFalse);
      expect(state.lastUpdated, isNull);
    });

    test('says the server is unreachable when nothing is cached', () async {
      var state = OfflineState();
      var client = cachingClient(
        unreachable,
        cache: MemoryOfflineCache(),
        offlineState: state,
      );

      await expectLater(
        client.loadResource(const []),
        throwsA(
          isA<VAlbumException>().having(
            (error) => error.message,
            "message",
            allOf(
              contains("cannot be reached"),
              contains("nothing is cached"),
              // The transport's own reason is kept, not swallowed.
              contains("Connection refused"),
            ),
          ),
        ),
      );
      expect(state.offline, isTrue);
      expect(state.lastUpdated, isNull);
    });

    test('never masks what the server itself answers', () async {
      var cache = MemoryOfflineCache();
      var state = OfflineState();
      var found = true;
      var client = cachingClient(
        (_) async =>
            found ? http.Response(listingJson, 200) : http.Response("", 404),
        cache: cache,
        offlineState: state,
      );

      await client.loadResource(const []);
      found = false;

      // A 404 with a cache hit still throws: the server is speaking.
      await expectLater(
        client.loadResource(const []),
        throwsA(isA<VAlbumException>()),
      );
      expect(state.offline, isFalse);
    });

    test('keeps the servers apart', () async {
      var cache = MemoryOfflineCache();
      var one = cachingClient(
        (_) async => http.Response(listingJson, 200),
        cache: cache,
      );
      await one.loadResource(const []);

      var other = one.withDataUrl("http://other/valbum/data");
      expect(await cache.getResource(other.dataUrl, const []), isNull);
      expect(await cache.getResource(serverDataUrl, const []), isNotNull);
    });
  });

  group('the cache bound', () {
    test('evicts the least recently used entry first', () async {
      var cache = MemoryOfflineCache(sizeLimit: 300);
      var bytes = Uint8List(100);

      await cache.putThumbnail("http://server/a?type=tn", bytes);
      await cache.putThumbnail("http://server/b?type=tn", bytes);
      await cache.putThumbnail("http://server/c?type=tn", bytes);
      expect(await cache.size(), 300);

      // Touching a and c makes b the least recently used one.
      await cache.getThumbnail("http://server/a?type=tn");
      await cache.getThumbnail("http://server/c?type=tn");

      await cache.putThumbnail("http://server/d?type=tn", bytes);

      expect(await cache.size(), 300);
      expect(await cache.getThumbnail("http://server/b?type=tn"), isNull);
      expect(await cache.getThumbnail("http://server/a?type=tn"), isNotNull);
      expect(await cache.getThumbnail("http://server/c?type=tn"), isNotNull);
      expect(await cache.getThumbnail("http://server/d?type=tn"), isNotNull);
    });

    test('is emptied by clear()', () async {
      var cache = MemoryOfflineCache();
      await cache.putResource(serverDataUrl, const [], listingJson);
      await cache.putThumbnail("http://server/a?type=tn", Uint8List(64));
      expect(await cache.size(), greaterThan(0));

      await cache.clear();

      expect(await cache.size(), 0);
      expect(await cache.getResource(serverDataUrl, const []), isNull);
      expect(await cache.getThumbnail("http://server/a?type=tn"), isNull);
    });
  });

  group('FileOfflineCache', () {
    late Directory dir;

    setUp(() async {
      dir = await Directory.systemTemp.createTemp("valbum-cache-test");
    });

    tearDown(() async {
      if (await dir.exists()) {
        await dir.delete(recursive: true);
      }
    });

    test('round-trips a resource and a thumbnail through a directory',
        () async {
      var cache = FileOfflineCache.inDirectory(dir);
      var pixels = Uint8List.fromList([1, 2, 3, 4, 5]);

      await cache.putResource(serverDataUrl, const ["a b", "c"], albumJson);
      await cache.putThumbnail("$serverDataUrl/x.jpg?type=tn", pixels);

      // A second cache over the same directory: what was stored is found by
      // the index the first one wrote.
      var reopened = FileOfflineCache.inDirectory(dir);
      var resource =
          await reopened.getResource(serverDataUrl, const ["a b", "c"]);
      expect(resource, isNotNull);
      expect(resource!.text, albumJson);
      var thumbnail =
          await reopened.getThumbnail("$serverDataUrl/x.jpg?type=tn");
      expect(thumbnail!.bytes, pixels);
      expect(await reopened.size(), albumJson.length + pixels.length);

      await reopened.clear();
      expect(await reopened.size(), 0);
      expect(
        await FileOfflineCache.inDirectory(dir)
            .getResource(serverDataUrl, const ["a b", "c"]),
        isNull,
      );
    });

    test('rebuilds a damaged index from the files that are there', () async {
      var cache = FileOfflineCache.inDirectory(dir);
      var pixels = Uint8List.fromList([9, 8, 7]);
      await cache.putResource(serverDataUrl, const [], listingJson);
      await cache.putThumbnail("$serverDataUrl/x.jpg?type=tn", pixels);

      // Something ate the index.
      await File("${dir.path}${Platform.pathSeparator}$cacheIndexName")
          .writeAsString("{not json at all");

      var reopened = FileOfflineCache.inDirectory(dir);
      var resource = await reopened.getResource(serverDataUrl, const []);
      expect(resource, isNotNull, reason: "The entry files are still there.");
      expect(resource!.text, listingJson);
      expect(
        (await reopened.getThumbnail("$serverDataUrl/x.jpg?type=tn"))!.bytes,
        pixels,
      );
      expect(await reopened.size(), listingJson.length + pixels.length);

      // The rebuilt index is written back, so the next run reads it.
      var index = jsonDecode(
        await File("${dir.path}${Platform.pathSeparator}$cacheIndexName")
            .readAsString(),
      );
      expect(index["version"], cacheIndexVersion);
      expect((index["entries"] as Map).length, 2);
    });
  });

  group('the thumbnail provider', () {
    test('fetches through the client, carrying the device token', () async {
      var requests = <http.BaseRequest>[];
      var cache = MemoryOfflineCache();
      var client = cachingClient(
        (_) async => http.Response.bytes(transparentPixelPng, 200),
        cache: cache,
        token: "secret-token",
        requests: requests,
      );

      var bytes = await client.thumbnailBytes("$serverDataUrl/album/x.jpg");

      expect(bytes, transparentPixelPng);
      expect(
          requests.single.url.toString(), "$serverDataUrl/album/x.jpg?type=tn");
      expect(requests.single.headers["Authorization"], "Bearer secret-token");
      expect(
        await cache.getThumbnail("$serverDataUrl/album/x.jpg?type=tn"),
        isNotNull,
      );
    });

    test('serves the cached bytes when the transport fails', () async {
      var cache = MemoryOfflineCache();
      var reachable = true;
      var client = cachingClient(
        (request) async => reachable
            ? http.Response.bytes(transparentPixelPng, 200)
            : unreachable(request),
        cache: cache,
      );

      await client.thumbnailBytes("$serverDataUrl/album/x.jpg");
      reachable = false;

      expect(
        await client.thumbnailBytes("$serverDataUrl/album/x.jpg"),
        transparentPixelPng,
      );
      // Nothing cached for this one: the failure is reported.
      await expectLater(
        client.thumbnailBytes("$serverDataUrl/album/y.jpg"),
        throwsA(isA<http.ClientException>()),
      );
    });

    test('names the URL it fetches, so a tile can be found by it', () {
      var client = cachingClient(
        (_) async => http.Response("", 200),
        cache: MemoryOfflineCache(),
      );
      var provider = ThumbnailImage(client, "$serverDataUrl/album/x.jpg");

      expect(provider.url, "$serverDataUrl/album/x.jpg?type=tn");
      expect(provider, ThumbnailImage(client, "$serverDataUrl/album/x.jpg"));
      expect(
        provider,
        isNot(ThumbnailImage(
          client.withToken("other"),
          "$serverDataUrl/album/x.jpg",
        )),
      );
    });
  });

  group('the app', () {
    testWidgets('shows the offline banner with the timestamp, and retries',
        (tester) async {
      var cache = MemoryOfflineCache();
      var state = OfflineState();
      var reachable = true;
      var client = VAlbumClient(
        dataUrl: serverDataUrl,
        httpClient: MockClient(servingThumbnails(
          (request) async => reachable
              ? http.Response(listingJson, 200)
              : unreachable(request),
        )),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, cache: cache, offlineState: state),
        );
        await tester.pumpAndSettle();
      });

      expect(find.text("Test-album"), findsOneWidget);
      expect(find.textContaining("Offline"), findsNothing);

      // The server goes away and the view is loaded again.
      reachable = false;
      await withFakeImageHttp(() async {
        VAlbumRouterDelegate delegate = tester
            .widget<MaterialApp>(find.byType(MaterialApp))
            .routerDelegate! as VAlbumRouterDelegate;
        delegate.reload();
        await tester.pumpAndSettle();
      });

      expect(state.offline, isTrue);
      expect(
        find.textContaining(offlineMessage(state.lastUpdated)),
        findsOneWidget,
      );
      // What is shown is the copy: the listing is still there.
      expect(find.text("Test-album"), findsOneWidget);

      // Retry, with the server back.
      reachable = true;
      await withFakeImageHttp(() async {
        await tester.tap(find.text("Retry"));
        await tester.pumpAndSettle();
      });

      expect(state.offline, isFalse);
      expect(find.textContaining("Offline"), findsNothing);
    });

    testWidgets('refuses to edit and to save while offline', (tester) async {
      var cache = MemoryOfflineCache();
      var state = OfflineState();
      var requests = <http.BaseRequest>[];
      var reachable = true;
      var client = VAlbumClient(
        dataUrl: serverDataUrl,
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          return reachable
              ? http.Response(albumJson, 200)
              : unreachable(request);
        })),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, cache: cache, offlineState: state),
        );
        await tester.pumpAndSettle();
      });
      expect(find.byType(Image), findsWidgets);

      reachable = false;
      await withFakeImageHttp(() async {
        var delegate = tester
            .widget<MaterialApp>(find.byType(MaterialApp))
            .routerDelegate! as VAlbumRouterDelegate;
        delegate.reload();
        await tester.pumpAndSettle();
      });
      expect(state.offline, isTrue);

      requests.clear();
      await withFakeImageHttp(() async {
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
      });

      // The edit mode is refused, with the reason on the screen, and the
      // "Save" the edit mode offers never appears.
      expect(find.text(offlineRefusal), findsOneWidget);
      expect(find.byTooltip("Save"), findsNothing);
      expect(
        requests.where((request) => request.method != "GET"),
        isEmpty,
        reason: "Nothing may be sent while the server is away.",
      );
    });
  });

  group('the settings screen', () {
    testWidgets('shows the size of the cache and clears it', (tester) async {
      var cache = MemoryOfflineCache();
      await cache.putThumbnail(
        "$serverDataUrl/x.jpg?type=tn",
        Uint8List(4096),
      );

      var settings = ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => serverDataUrl,
        loaded: true,
      );
      await tester.pumpWidget(
        OfflineScope(
          state: OfflineState(),
          cache: cache,
          child: MaterialApp(
            home: ServerSettingsScreen(
              settings: settings,
              clientFor: (dataUrl) => VAlbumClient(
                dataUrl: dataUrl,
                httpClient: MockClient((_) async => http.Response("", 200)),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(clearCacheButtonKey),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text("Currently cached: 4.0 kB"), findsOneWidget);

      await tester.tap(find.byKey(clearCacheButtonKey));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Clear"));
      await tester.pumpAndSettle();

      expect(await cache.size(), 0);
      await tester.scrollUntilVisible(
        find.byKey(clearCacheButtonKey),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text("Currently cached: 0 B"), findsOneWidget);
      expect(find.textContaining("Cache cleared"), findsOneWidget);
    });
  });
}
