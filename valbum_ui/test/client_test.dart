import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
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
  });
}
