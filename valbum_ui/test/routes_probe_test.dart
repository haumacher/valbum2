import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

// Probe review for issue #24: route grammar edge cases and a deep link that
// lands on a group representative.
void main() {
  test('routes survive awkward names, bases and repeated round trips', () {
    var cases = <VAlbumRoute>[
      const ImageRoute(["Tag 1", "Grüße & Co"], "IMG 0001+.JPG"),
      const MemberRoute(["A/B"], "rep.jpg", "alternatives.jpg"),
      const AlternativesRoute(["2005-08-24 Blumen und Fliegen"], "x.mov"),
      const ListingOrAlbumRoute(["50%", "a.b"]),
    ];
    for (var base in ["/", "/valbum/", "/photos/deep/"]) {
      for (var route in cases) {
        var uri = routeToUri(route, basePath: base);
        expect(uri.path.startsWith(base), isTrue, reason: "$route @ $base");
        var back = parseRoute(uri, basePath: base);
        expect(back, route, reason: "$route @ $base → $uri");
        // Parsing the printed string, as a browser hands it over, is stable.
        expect(parseRoute(Uri.parse(uri.toString()), basePath: base), route);
      }
    }
    // A member route's up leads to the alternatives, whose up is the image,
    // whose up is the album, whose up is the parent listing, ending at root.
    VAlbumRoute r = const MemberRoute(["a", "b"], "rep.jpg", "m.jpg");
    var chain = <String>[];
    while (true) {
      chain.add(r.runtimeType.toString());
      var up = r.up;
      if (up == null || up == r) break;
      r = up;
    }
    expect(chain, [
      "MemberRoute",
      "AlternativesRoute",
      "ImageRoute",
      "ListingOrAlbumRoute",
      "ListingOrAlbumRoute",
      "ListingOrAlbumRoute",
    ]);
    expect(r, ListingOrAlbumRoute.root);
  });

  testWidgets('deep link to a group representative opens the group view',
      (tester) async {
    var client = clientReturning(fixture("album.json"));
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ImageRoute([], "group-a.jpg"),
      ));
      await tester.pumpAndSettle();
    });
    // The representative is shown and the chevron to the alternatives exists.
    expect(find.byIcon(Icons.expand_more), findsOneWidget);
    expect(find.textContaining("No such image"), findsNothing);
  });

  testWidgets('deep link to a member that is not in the group speaks',
      (tester) async {
    var client = clientReturning(fixture("album.json"));
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const MemberRoute([], "group-a.jpg", "landscape.jpg"),
      ));
      await tester.pumpAndSettle();
    });
    expect(tester.takeException(), isNull);
    // Either an error message or the album is acceptable; never a crash and
    // never silence: something with text must be on screen.
    expect(find.byType(Text), findsWidgets);
  });
}
