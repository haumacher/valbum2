/// Tests of the URL grammar: [parseRoute] and [routeToUri].
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/routes.dart';

/// The route the given location denotes, below the given app base.
VAlbumRoute parse(String location, {String basePath = "/"}) =>
    parseRoute(Uri.parse(location), basePath: basePath);

/// Checks that the route is written as [location] and read back unchanged.
void roundTrip(VAlbumRoute route, String location, {String basePath = "/"}) {
  expect(
    routeToUri(route, basePath: basePath).toString(),
    location,
    reason: "$route is written as $location",
  );
  expect(
    parse(location, basePath: basePath),
    route,
    reason: "$location is read as $route",
  );
}

void main() {
  group('the four route kinds', () {
    test('the root listing is the empty path', () {
      roundTrip(ListingOrAlbumRoute.root, "/");
    });

    test('a listing or album keeps its trailing slash', () {
      roundTrip(const ListingOrAlbumRoute(["a", "b"]), "/a/b/");
    });

    test('an image is the album path plus its file name', () {
      roundTrip(const ImageRoute(["a"], "IMG_0417.JPG"), "/a/IMG_0417.JPG");
    });

    test('an image of the root album needs no album path', () {
      roundTrip(const ImageRoute([], "IMG_0417.JPG"), "/IMG_0417.JPG");
    });

    test('the alternatives view is the image plus the marker', () {
      roundTrip(
        const AlternativesRoute(["a"], "IMG_0417.JPG"),
        "/a/IMG_0417.JPG/alternatives/",
      );
    });

    test('a group member is named behind the marker', () {
      roundTrip(
        const MemberRoute(["a"], "IMG_0417.JPG", "IMG_0418.JPG"),
        "/a/IMG_0417.JPG/alternatives/IMG_0418.JPG",
      );
    });
  });

  group('names with spaces and umlauts', () {
    test('the album path is percent-encoded', () {
      roundTrip(
        const ListingOrAlbumRoute(["2005-08-24 Blumen und Fliegen"]),
        "/2005-08-24%20Blumen%20und%20Fliegen/",
      );
    });

    test('an image name is percent-encoded', () {
      roundTrip(
        const ImageRoute(["Grün & Blüten"], "Föhr, Süden.jpg"),
        "/Gr%C3%BCn%20&%20Bl%C3%BCten/F%C3%B6hr,%20S%C3%BCden.jpg",
      );
    });

    test('the alternatives of an encoded name round-trip', () {
      roundTrip(
        const MemberRoute(["Ostern 2011"], "Öl.jpg", "Öl 2.jpg"),
        "/Ostern%202011/%C3%96l.jpg/alternatives/%C3%96l%202.jpg",
      );
    });
  });

  group('the app base', () {
    test('is stripped from the location', () {
      expect(
        parse("/valbum/x/", basePath: "/valbum/"),
        const ListingOrAlbumRoute(["x"]),
      );
    });

    test('is written in front of the route', () {
      roundTrip(
        const ImageRoute(["x"], "a b.jpg"),
        "/valbum/x/a%20b.jpg",
        basePath: "/valbum/",
      );
    });

    test('a nested base is stripped as a whole', () {
      expect(
        parse("/apps/valbum/x/y.jpg", basePath: "/apps/valbum/"),
        const ImageRoute(["x"], "y.jpg"),
      );
    });

    test('the base itself is the root listing', () {
      roundTrip(
        ListingOrAlbumRoute.root,
        "/valbum/",
        basePath: "/valbum/",
      );
    });

    test('a location outside the base is read as it stands', () {
      expect(
          parse("/x/", basePath: "/valbum/"), const ListingOrAlbumRoute(["x"]));
    });

    test('a full URL is parsed by its path', () {
      expect(
        parseRoute(
          Uri.parse("http://localhost:9090/valbum/a/b.jpg"),
          basePath: "/valbum/",
        ),
        const ImageRoute(["a"], "b.jpg"),
      );
    });
  });

  group('corner cases', () {
    test('the empty location is the root', () {
      expect(parse(""), ListingOrAlbumRoute.root);
    });

    test('a query and a fragment are ignored', () {
      expect(parse("/a/?x=1#y"), const ListingOrAlbumRoute(["a"]));
    });

    test('a folder named "alternatives" is read as the marker', () {
      // The segment is reserved, see the library documentation.
      expect(
        parse("/a/b/alternatives/"),
        const AlternativesRoute(["a"], "b"),
      );
    });

    test('a top-level folder named "alternatives" stays a listing', () {
      expect(
          parse("/alternatives/"), const ListingOrAlbumRoute(["alternatives"]));
    });
  });

  group('the app base of a web location', () {
    test('is the location itself at the start page', () {
      expect(appBasePath("/valbum/", "/"), "/valbum/");
    });

    test('is the directory of an index.html location', () {
      expect(appBasePath("/valbum/index.html", "/"), "/valbum/");
    });

    test('is the location minus the route it shows', () {
      expect(
        appBasePath("/valbum/2005-08-24%20Blumen/IMG_0417.JPG",
            "/2005-08-24%20Blumen/IMG_0417.JPG"),
        "/valbum/",
      );
      expect(
        appBasePath("/valbum/a/b/", "/a/b/"),
        "/valbum/",
      );
    });

    test('is the root when the app is served from it', () {
      expect(appBasePath("/a/b.jpg", "/a/b.jpg"), "/");
      expect(appBasePath("/", "/"), "/");
    });

    test('falls back to the directory of a location that does not fit', () {
      expect(appBasePath("/valbum/x/", "/other/"), "/valbum/x/");
    });

    test('compares the decoded location with the decoded route', () {
      // The browser hands the location over percent-encoded, the engine hands
      // the route name over decoded: a comparison of the raw strings fails on
      // every folder name that is not plain ASCII, see issue #35.
      expect(
        appBasePath("/valbum/Haui's%20inbox/", "/Haui's inbox/"),
        "/valbum/",
      );
      expect(
        appBasePath("/valbum/Gr%C3%BC%C3%9Fe/", "/Grüße/"),
        "/valbum/",
      );
      expect(
        appBasePath("/valbum/a%20b/c%20d.jpg", "/a b/c d.jpg"),
        "/valbum/",
      );
    });

    test('is robust to an apostrophe encoded on one side only', () {
      // `Uri` leaves `'` alone, a browser may not.
      expect(
        appBasePath("/valbum/Haui%27s%20inbox/", "/Haui's inbox/"),
        "/valbum/",
      );
      expect(
        appBasePath("/valbum/Haui's inbox/", "/Haui%27s%20inbox/"),
        "/valbum/",
      );
    });

    test('is the decoded base of an app served from an encoded path', () {
      expect(appBasePath("/mein%20album/a%20b/", "/a b/"), "/mein album/");
    });

    test('is the root for the plain root location', () {
      expect(appBasePath("/", "/"), "/");
      expect(appBasePath("", "/"), "/");
      expect(appBasePath("/index.html", "/"), "/");
    });
  });

  group('up', () {
    test('leads from the member to the album', () {
      VAlbumRoute? route = const MemberRoute(["a"], "b.jpg", "c.jpg");
      expect(route.up, const AlternativesRoute(["a"], "b.jpg"));
      expect(route.up?.up, const ImageRoute(["a"], "b.jpg"));
      expect(route.up?.up?.up, const ListingOrAlbumRoute(["a"]));
      expect(route.up?.up?.up?.up, ListingOrAlbumRoute.root);
      expect(route.up?.up?.up?.up?.up, isNull);
    });
  });
}
