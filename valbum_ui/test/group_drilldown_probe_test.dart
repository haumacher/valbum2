/// Probe for the shortened ascent of issue #79: nested album paths, awkward
/// names, the share-link base, and the deep-link round trip of a member whose
/// "up" is now the album.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/routes.dart';

void main() {
  test('up from a member or the alternatives is the album, however deep the album lies', () {
    var deep = ["2024", "Tag 1", "Grüße & Co"];
    expect(MemberRoute(deep, "rep.jpg", "m.jpg").up, ListingOrAlbumRoute(deep));
    expect(AlternativesRoute(deep, "rep.jpg").up, ListingOrAlbumRoute(deep));
    expect(ImageRoute(deep, "rep.jpg").up, ListingOrAlbumRoute(deep));
    // From the album's own level, up keeps going to the parent folder.
    expect(ListingOrAlbumRoute(deep).up, const ListingOrAlbumRoute(["2024", "Tag 1"]));
    // A root album has no group ascent problem either.
    expect(const MemberRoute([], "a.jpg", "b.jpg").up, const ListingOrAlbumRoute([]));
    expect(const ListingOrAlbumRoute([]).up, isNull);
  });

  test('a deep link to a member still parses under every base and ascends to the album', () {
    var route = const MemberRoute(["A/B", "x y"], "rep+.jpg", "m&n.mov");
    for (var base in ["/", "/valbum/", "/valbum/s/TOKEN123/", "/valbum/i/INV/"]) {
      var uri = routeToUri(route, basePath: base);
      var parsed = parseRoute(uri, basePath: base);
      expect(parsed, route, reason: "$uri");
      expect(parsed.up, const ListingOrAlbumRoute(["A/B", "x y"]), reason: "$uri");
      // The URL of the member is unchanged by #79: only its "up" changed.
      expect(uri.path, contains("alternatives"));
    }
  });

  test('the ascent chain from a member has exactly one step before the folders', () {
    var chain = <String>[];
    VAlbumRoute? r = const MemberRoute(["2024", "Trip"], "a.jpg", "b.jpg");
    while (r != null) {
      chain.add(r.runtimeType.toString());
      r = r.up;
    }
    expect(chain, ["MemberRoute", "ListingOrAlbumRoute", "ListingOrAlbumRoute", "ListingOrAlbumRoute"]);
  });
}
