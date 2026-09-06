import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/offline.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';

void main() {
  var client = VAlbumClient(dataUrl: "http://server/valbum/data");

  group('URL construction', () {
    test('base URL of a path', () {
      expect(client.baseUrl([]), "http://server/valbum/data");
      expect(
        client.baseUrl(["2002-03-03 Schlosspark", "sub"]),
        "http://server/valbum/data/2002-03-03 Schlosspark/sub",
      );
    });

    test('JSON URL of a path', () {
      expect(client.jsonUrl([]), "http://server/valbum/data/?type=json");
      expect(
        client.jsonUrl(["album"]),
        "http://server/valbum/data/album/?type=json",
      );
    });

    test('thumbnail and original URL', () {
      var image = "${client.baseUrl(["album"])}/image.jpg";
      expect(
        client.thumbnailUrl(image),
        "http://server/valbum/data/album/image.jpg?type=tn",
      );
      expect(
        client.originalUrl(image),
        "http://server/valbum/data/album/image.jpg",
      );
    });
  });

  group('loadResource', () {
    test('parses a listing', () async {
      var resource = await clientReturning(
        fixture("listing.json"),
      ).loadResource([]);

      expect(resource, isA<ListingInfo>());
      expect((resource as ListingInfo).folders, hasLength(2));
    });

    test('reports the status of a failed request', () async {
      expect(
        () => clientReturning("Not found", status: 404).loadResource([]),
        throwsA(
          isA<VAlbumException>().having(
            (e) => e.message,
            'message',
            contains("HTTP 404"),
          ),
        ),
      );
    });

    test('names the address when a 200 is no album data', () async {
      // The album server answers an unknown extension-less path with the
      // `index.html` of the app, so a wrong data URL delivers HTML with a 200.
      // The user saw a raw `FormatException` quoting that HTML, see issue #35.
      expect(
        () =>
            clientReturning("<!DOCTYPE html><html>...</html>").loadResource([]),
        throwsA(
          isA<VAlbumException>()
              .having(
                (e) => e.message,
                'message',
                contains("did not answer with album data"),
              )
              .having(
                (e) => e.message,
                'message',
                contains("http://server/valbum/data/?type=json"),
              ),
        ),
      );
    });

    test('names the address when the answer is empty', () async {
      expect(
        () => clientReturning("").loadResource([]),
        throwsA(
          isA<VAlbumException>().having(
            (e) => e.message,
            'message',
            contains("did not answer with album data"),
          ),
        ),
      );
    });

    test('an answer that is no album data is not cached', () async {
      var cache = MemoryOfflineCache();
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient(
          (request) async => http.Response("<!DOCTYPE html>", 200),
        ),
        cache: cache,
      );

      await expectLater(
        client.loadResource([]),
        throwsA(isA<VAlbumException>()),
      );
      expect(await cache.getResource("http://server/valbum/data", []), isNull);
    });

    test('the auth query names the address when it is answered with HTML',
        () async {
      expect(
        () => clientReturning("<!DOCTYPE html>").authInfo(),
        throwsA(
          isA<VAlbumException>().having(
            (e) => e.message,
            'message',
            contains("did not answer with album data"),
          ),
        ),
      );
    });
  });
}
