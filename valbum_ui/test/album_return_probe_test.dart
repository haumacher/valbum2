/// Probe for issue #93: the page stack composed with a nested album whose
/// name carries blanks, two round trips, paging inside the viewer, a popup
/// menu on the album, and the way up to the listing.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String folder = "Trip 2024";

const String listing = '["ListingInfo", {"path": "", "title": "Albums", '
    '"folders": [{"name": "$folder", "title": "Trip"}]}]';

String album(int count) =>
    '["AlbumInfo", {"path": "$folder", "title": "Trip", "subTitle": "", "parts": ['
    '${[
      for (var i = 0; i < count; i++)
        '["ImagePart", {"kind": "IMAGE", "name": "shot $i.jpg", '
            '"date": ${i + 1}, "width": 2000, "height": 1000, '
            '"orientation": "IDENTITY", "rating": 0}]'
    ].join(",")}'
    ']}]';

VAlbumClient client(List<String> thumbnails) => VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient((request) async {
        if (isThumbnailRequest(request)) {
          thumbnails.add(request.url.path);
          return http.Response.bytes(transparentPixelPng, 200,
              headers: {"content-type": "image/png"});
        }
        var path = Uri.decodeComponent(request.url.path);
        var body = path.contains(folder) ? album(6) : listing;
        return http.Response(body, 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }),
    );

Future<void> settle(WidgetTester tester, Future<void> Function() act) async {
  await withFakeImageHttp(() async {
    await act();
    await tester.pumpAndSettle();
  });
  for (var round = 0; round < 5; round++) {
    await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 50)));
    await withFakeImageHttp(() => tester.pumpAndSettle());
  }
}

AlbumContentState albumState(WidgetTester tester) => tester
    .state<AlbumContentState>(find.byType(AlbumContent, skipOffstage: false));

VAlbumRouterDelegate router(WidgetTester tester) => tester
    .widget<MaterialApp>(find.byType(MaterialApp))
    .routerDelegate! as VAlbumRouterDelegate;

void main() {
  testWidgets(
      'a nested album with blanks survives two round trips, paging, a menu and the way up',
      (tester) async {
    var cache = PaintingBinding.instance.imageCache;
    var before = cache.maximumSizeBytes;
    cache.maximumSizeBytes = 1;
    addTearDown(() {
      cache.maximumSizeBytes = before;
      cache.clear();
      cache.clearLiveImages();
    });
    var thumbnails = <String>[];

    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client(thumbnails))));
    expect(find.byType(ListingView), findsOneWidget);

    // Down into the album by its route, as a listing tile does.
    await settle(tester, () async => router(tester).go(const ListingOrAlbumRoute([folder])));
    expect(find.byType(AlbumContent), findsOneWidget);
    var state = albumState(tester);
    var afterAlbum = thumbnails.length;
    expect(afterAlbum, 6, reason: "six tiles, one thumbnail each");

    for (var trip = 0; trip < 2; trip++) {
      await settle(tester, () => tester.tap(find.byKey(const ValueKey("shot 1.jpg"))));
      expect(find.byType(ImageView), findsOneWidget);
      // Paging inside the viewer replaces the top page only.
      await settle(tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowRight));
      expect(tester.widget<ImageView>(find.byType(ImageView)).image.thumbnailName, "shot 2.jpg");
      await settle(tester, () => tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft));
      expect(tester.widget<ImageView>(find.byType(ImageView)).image.thumbnailName, "shot 1.jpg");
      expect(identical(albumState(tester), state), isTrue, reason: "the album stays mounted beneath");

      await settle(tester, () => tester.binding.handlePopRoute());
      expect(find.byType(ImageView), findsNothing);
      expect(find.byType(AlbumContent), findsOneWidget);
      expect(identical(albumState(tester), state), isTrue, reason: "round trip $trip keeps the state");
      expect(thumbnails.length, afterAlbum, reason: "round trip $trip fetched no thumbnail again");
    }

    // A popup menu is an imperative route: the system back closes it and
    // leaves the album where it is.
    await settle(tester, () => tester.tap(find.byIcon(Icons.more_vert).first));
    expect(find.byType(PopupMenuItem<void Function(BuildContext)>), findsWidgets);
    await settle(tester, () => tester.binding.handlePopRoute());
    expect(find.byType(PopupMenuItem<void Function(BuildContext)>), findsNothing);
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(identical(albumState(tester), state), isTrue);

    // And the next back is the way up to the listing, as before.
    await settle(tester, () => tester.binding.handlePopRoute());
    expect(find.byType(ListingView), findsOneWidget);
    expect(find.byType(AlbumContent), findsNothing);
  });
}
