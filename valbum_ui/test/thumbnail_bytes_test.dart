/// The encoded thumbnail bytes the decodings of every size are made from
/// (issue #225).
///
/// A tile decodes its thumbnail at the height it is drawn at, through a
/// [ResizeImage] — which resolves its inner [ThumbnailImage] outside the
/// [ImageCache], so a tile that grew used to download its thumbnail again.
library;

import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'album_return_test.dart' show countingClient;
import 'thumbnail_relayout_test.dart' hide main;
import 'util/fake_image_http.dart';

const String imageUrl = "http://server/valbum/data/a.jpg";

Uint8List bytesOf(int length) => Uint8List(length);

void main() {
  setUp(() {
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    forgetDecodedThumbnailHeights();
    forgetThumbnailBytes();
  });

  testWidgets('a tile that grows decodes sharper with no second request', (
    tester,
  ) async {
    var thumbnails = <String>[];
    var host = await pumpHost(tester, thumbnails, 200);
    expect(thumbnails, hasLength(1));

    host.resize(260);
    await withFakeImageHttp(() => tester.pump());
    await decodeAll(tester);

    expect(hostProvider(tester).height, 520, reason: "decoded at its height");
    expect(decodedThumbnailHeight(imageUrl), 520, reason: "and that landed");
    expect(thumbnails, hasLength(1), reason: "from the bytes already there");
  });

  testWidgets('"Refresh previews" forgets the bytes as well', (tester) async {
    var thumbnails = <String>[];
    var host = await pumpHost(tester, thumbnails, 200);
    expect(thumbnails, hasLength(1));

    forgetDecodedThumbnails();
    expect(thumbnailByteCache.length, 0);
    host.resize(260);
    await withFakeImageHttp(() => tester.pump());
    await decodeAll(tester);

    expect(thumbnails, hasLength(2), reason: "the new picture is downloaded");
  });

  testWidgets('two clients never share bytes', (tester) async {
    var member = <String>[];
    var memberClient = countingClient('["AlbumInfo", {}]', member);
    var shared = <String>[];
    // A share session is a client of its own, even on the same server.
    var shareClient = countingClient('["AlbumInfo", {}]', shared);

    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Column(
            children: [
              thumbnail(memberClient, imageUrl,
                  width: 150, height: 100, displayHeight: 100),
              thumbnail(shareClient, imageUrl,
                  width: 300, height: 200, displayHeight: 200),
            ],
          ),
        ),
      );
    });
    await decodeAll(tester);

    expect(member, hasLength(1));
    expect(shared, hasLength(1));
    expect(
      thumbnailBytesKey(memberClient, imageUrl),
      isNot(thumbnailBytesKey(shareClient, imageUrl)),
    );
    expect(
      thumbnailBytesKey(memberClient, imageUrl),
      thumbnailBytesKey(memberClient, imageUrl),
    );
  });

  group('the byte cache', () {
    var a = thumbnailBytesKey(countingClient("", []), "http://s/data/a.jpg");
    var b = thumbnailBytesKey(countingClient("", []), "http://s/data/b.jpg");
    var c = thumbnailBytesKey(countingClient("", []), "http://s/data/c.jpg");

    test('evicts the least recently used by bytes', () async {
      var cache = ThumbnailByteCache(limit: 1000);
      cache.put(a, bytesOf(400));
      cache.put(b, bytesOf(400));
      // Reading a makes b the oldest.
      await cache.fetch(a, () => fail("held"));
      cache.put(c, bytesOf(300));

      expect(cache.contains(a), isTrue);
      expect(cache.contains(b), isFalse, reason: "b was untouched longest");
      expect(cache.contains(c), isTrue);
      expect(cache.size, 700);

      cache.put(b, bytesOf(900));
      expect(cache.size, 900, reason: "everything else had to go");
      expect(cache.length, 1);

      cache.put(c, bytesOf(1001));
      expect(cache.contains(c), isFalse,
          reason: "nothing larger than the bound is kept");
      expect(cache.size, 900);
    });

    test('shares a download on its way and keeps no failure', () async {
      var cache = ThumbnailByteCache(limit: 1000);
      var downloads = 0;
      var arriving = Completer<Uint8List>();
      var first = cache.fetch(a, () {
        downloads++;
        return arriving.future;
      });
      var second = cache.fetch(a, () {
        downloads++;
        return arriving.future;
      });
      arriving.complete(bytesOf(10));
      expect(await first, hasLength(10));
      expect(await second, hasLength(10));
      expect(downloads, 1);
      expect(cache.size, 10);

      await expectLater(
        cache.fetch(b, () async => throw StateError("refused")),
        throwsStateError,
      );
      expect(cache.contains(b), isFalse);
      expect(await cache.fetch(b, () async => bytesOf(5)), hasLength(5));
    });

    test('keeps no download that started before a clear', () async {
      var cache = ThumbnailByteCache(limit: 1000);
      var arriving = Completer<Uint8List>();
      var old = cache.fetch(a, () => arriving.future);
      cache.clear();
      arriving.complete(bytesOf(10));
      await old;
      expect(cache.contains(a), isFalse);
      expect(cache.size, 0);
    });
  });
}
