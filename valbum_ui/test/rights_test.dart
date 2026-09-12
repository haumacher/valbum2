/// Tests of the rights the server answers with every folder (issue #49): how
/// they are read, how the offline cache is keyed by the signed-in user, and
/// how a path into another user's space is routed.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fixtures.dart';

/// A listing carrying the given rights, as the server answers it.
String listingWith(String rights) => '["ListingInfo", {"path": "", '
    '"title": "Test-album", $rights"folders": []}]';

void main() {
  group('Rights.of', () {
    test('reads all four names as everything', () {
      var listing = FolderResource.fromString(listingWith(
        '"rights": [{"name": "view"}, {"name": "download"}, '
        '{"name": "contribute"}, {"name": "edit"}], ',
      ))!;

      var rights = Rights.of(listing);

      expect(rights.mayView, isTrue);
      expect(rights.mayDownload, isTrue);
      expect(rights.mayContribute, isTrue);
      expect(rights.mayEdit, isTrue);
      expect(rights.complete, isTrue);
    });

    test('reads a single view right as view alone', () {
      var listing =
          FolderResource.fromString(listingWith('"rights": [{"name": "view"}], '))!;

      var rights = Rights.of(listing);

      expect(rights.mayView, isTrue);
      expect(rights.mayDownload, isFalse);
      expect(rights.mayContribute, isFalse);
      expect(rights.mayEdit, isFalse);
      expect(rights.complete, isFalse);
      expect(rights.phrase, "you may look");
    });

    test('reads an answer without the field as every right', () {
      // A server from before issue #49 does not send it; the app must keep
      // working against it, unchanged.
      var listing = FolderResource.fromString(listingWith(""))!;

      expect(Rights.of(listing).complete, isTrue);
    });

    test('applies the implications of the stronger rights', () {
      expect(Rights.ofNames(const ["edit"]).mayView, isTrue);
      expect(Rights.ofNames(const ["contribute"]).mayView, isTrue);
      expect(Rights.ofNames(const ["contribute"]).mayDownload, isFalse);
      expect(Rights.ofNames(const ["download"]).mayView, isTrue);
    });
  });

  group('the sharing notice', () {
    test('says nothing to the owner of a folder in their own space', () {
      expect(sharingNotice(const ["2024"], Rights.everything), isNull);
    });

    test('names the owner of the space a path reaches into', () {
      var notice = sharingNotice(
        const ["~alice", "2024", "Zoo"],
        Rights.ofNames(const ["view", "download"]),
      );

      expect(notice, "Shared by alice — you may look and download");
    });

    test('says "shared with you" where the rights are cut in one own space',
        () {
      var notice = sharingNotice(
        const ["2024"],
        Rights.ofNames(const ["contribute"]),
      );

      expect(notice, "Shared with you — you may add photos");
    });

    test('reads the owner path of a canonical path', () {
      expect(ownerPathOf(const ["~alice", "2024", "Zoo"]), "2024/Zoo");
      expect(ownerPathOf(const ["2024", "Zoo"]), "2024/Zoo");
      expect(ownerPathOf(const ["~alice"]), "");
      expect(spaceOwnerOf(const ["2024"]), isNull);
      expect(spaceOwnerOf(const ["~alice", "x"]), "alice");
    });
  });

  group('the offline cache key', () {
    test('tells two users on one device apart', () {
      const url = "http://server/valbum/data";

      expect(
        OfflineCache.resourceKey(url, const ["a"], user: "@bob"),
        isNot(OfflineCache.resourceKey(url, const ["a"], user: "@carol")),
      );
      expect(
        OfflineCache.thumbnailKey("$url/a/x.jpg?type=tn", user: "@bob"),
        isNot(
          OfflineCache.thumbnailKey("$url/a/x.jpg?type=tn", user: "@carol"),
        ),
      );
    });

    test('tells the signed-in owner from an anonymous caller', () {
      // The server calls the unnamed owner and an anonymous caller both "";
      // the token's presence is the difference, see [VAlbumClient.cacheUser].
      var owner = VAlbumClient(dataUrl: "http://server", token: "t");
      var anonymous = VAlbumClient(dataUrl: "http://server");

      expect(owner.cacheUser, "@");
      expect(anonymous.cacheUser, "");
      expect(VAlbumClient(dataUrl: "x", token: "t", userName: "bob").cacheUser,
          "@bob");
    });

    test('does not serve one user the copy of another', () async {
      var cache = MemoryOfflineCache();
      var listing = fixture("listing.json");
      var reachable = true;
      http.Client transport() => MockClient((request) async {
            if (!reachable) {
              throw http.ClientException("Connection refused", request.url);
            }
            return http.Response(listing, 200);
          });

      var bob = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "bob-token",
        userName: "bob",
        cache: cache,
        httpClient: transport(),
      );
      await bob.loadResource(const []);

      var carol = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "carol-token",
        userName: "carol",
        cache: cache,
        httpClient: transport(),
      );

      // The server is away: whatever answers now comes from the cache — and
      // for carol there is nothing there, although bob's copy is.
      reachable = false;
      await expectLater(
        carol.loadResource(const []),
        throwsA(isA<VAlbumException>()),
      );
      expect(
        await cache.getResource(
          "http://server/valbum/data",
          const [],
          user: "@bob",
        ),
        isNotNull,
      );
    });
  });

  group('a path into another space', () {
    test('is routed as an ordinary first segment', () {
      var route = parseRoute(Uri.parse("/~alice/2024/Zoo/"));

      expect(route, isA<ListingOrAlbumRoute>());
      expect(route.albumPath, const ["~alice", "2024", "Zoo"]);
      expect(routeToUri(route).toString(), "/~alice/2024/Zoo/");
    });

    testWidgets('is asked for in the coordinates it was given in',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => http.Response(
          '["AlbumInfo", {"path": "Zoo", "title": "Zoo", '
          '"rights": [{"name": "view"}], "parts": []}]',
          200,
          headers: {"content-type": "application/json; charset=utf-8"},
        ),
        requests: requests,
      );

      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute(["~alice", "2024", "Zoo"]),
      ));
      await tester.pumpAndSettle();

      expect(
        [
          for (var request in requests) Uri.decodeFull(request.url.path),
        ],
        contains("/valbum/data/~alice/2024/Zoo/"),
      );
    });
  });
}
