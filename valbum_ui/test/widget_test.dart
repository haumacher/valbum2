import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

void main() {
  testWidgets('listing shows a tile per folder', (tester) async {
    var requests = <http.Request>[];
    var client = clientReturning(fixture("listing.json"), requests: requests);

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });

    // The listing title is the app bar title.
    expect(find.text('Test-album'), findsOneWidget);

    // One tile per folder, with title and subtitle.
    expect(find.text('Schlosspark Karlsruhe'), findsOneWidget);
    expect(find.text('March 3, 2002'), findsOneWidget);
    expect(find.text('Ausflug'), findsOneWidget);
    expect(find.text('June 21, 2003'), findsOneWidget);

    // The folder with an index picture shows a thumbnail, the other one an
    // icon.
    expect(find.byType(Image), findsOneWidget);
    expect(find.byIcon(Icons.folder), findsOneWidget);

    // The listing was requested through the injected client.
    expect(requests, hasLength(1));
    expect(
      requests.single.url.toString(),
      "http://server/valbum/data/?type=json",
    );
  });

  testWidgets('album shows headings and image tiles', (tester) async {
    var client = clientReturning(fixture("album.json"));

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });

    expect(find.text('Schlosspark Karlsruhe'), findsOneWidget);
    expect(find.text('March 3, 2002'), findsOneWidget);

    // The heading between the album parts is rendered.
    expect(find.text('Am Morgen'), findsOneWidget);

    // One tile per image part: two ImageParts (landscape and portrait) plus
    // the representative of the ImageGroup.
    expect(find.byType(Image), findsNWidgets(3));
  });

  testWidgets('a failed request shows the error view', (tester) async {
    var client = clientReturning("Not found", status: 404);

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });

    expect(find.textContaining('Loading failed'), findsOneWidget);
    expect(find.textContaining('HTTP 404'), findsOneWidget);
  });

  testWidgets('an ErrorInfo resource shows its message', (tester) async {
    var client = clientReturning(fixture("error.json"));

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();
    });

    expect(find.text('Loading failed: No such album.'), findsOneWidget);
  });
}
