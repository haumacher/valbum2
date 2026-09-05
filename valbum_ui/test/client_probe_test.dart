import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/urls.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Probe review for the injectable client and URL derivation (issues #10/#15):
// composes the new mechanisms with edge cases the implementation never saw.
void main() {
  test('deriveDataUrl ignores query, fragment and index.html', () {
    expect(
      deriveDataUrl(
        Uri.parse("http://h:8080/valbum/index.html?x=1#/2005/route"),
        isWeb: true,
      ),
      "http://h:8080/valbum/data",
    );
    expect(
      deriveDataUrl(Uri.parse("https://h/?flutter=1"), isWeb: true),
      "https://h/data",
    );
    expect(
      deriveDataUrl(Uri.parse("http://h/valbum/index.html"), isWeb: false),
      defaultDataUrl,
    );
  });

  test('nested album path with spaces is requested as before', () async {
    var requests = <http.Request>[];
    var client = clientReturning(fixture("album.json"), requests: requests);
    await client.loadResource(const ["2005-08-24 Blumen und Fliegen"]);
    expect(requests, hasLength(1));
    expect(
      requests.single.url.toString(),
      "http://server/valbum/data/2005-08-24%20Blumen%20und%20Fliegen/?type=json",
    );
  });

  testWidgets('headings at both ends and back to back render without images',
      (tester) async {
    const album = '''
["AlbumInfo", {"path": "", "title": "T", "subTitle": "",
 "parts": [
   ["Heading", {"text": "First"}],
   ["Heading", {"text": "Second"}],
   ["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 200, "height": 100, "orientation": "IDENTITY", "rating": 0}],
   ["Heading", {"text": "Last"}]
 ]}]''';
    var client = clientReturning(album);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });
    expect(find.text('First'), findsOneWidget);
    expect(find.text('Second'), findsOneWidget);
    expect(find.text('Last'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
