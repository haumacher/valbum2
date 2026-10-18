/// "More like this" of issue #257 (package 5): a photo as the query of the
/// content search. Offered by the viewer's menu and a tile's menu alike —
/// the catalogue's one action — exactly where the space has content search
/// on, on photos and videos, to a view-only member too, never through a link;
/// it opens the search with the photo's thumbnail as a chip, the request
/// carrying a `SearchSimilar`, the answer shown nearest first. Removing the
/// chip leaves the conventional search of the other chips, a person narrows
/// it, and a photo that is not there or not analysed yet is said in the
/// app's words.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_actions.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';
import 'package:valbum_ui/search_view.dart' show contentSearchPage;
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_video_player.dart';
import 'util/l10n.dart';
import 'util/photo_space.dart';

/// What "More like this" of `trip/a.jpg` finds: the most similar first,
/// though it was taken last.
const String alike = '["AlbumInfo",{"kind":"SEARCH","parts":['
    '["ImagePart",{"kind":"IMAGE","name":"trip/c.jpg","date":1015113620000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY","score":0.91,'
    '"ref":{"hash":"","path":"trip/c.jpg"}}],'
    '["ImagePart",{"kind":"IMAGE","name":"trip/b.jpg","date":1015113610000,'
    '"width":2048,"height":1536,"orientation":"IDENTITY","score":0.47,'
    '"ref":{"hash":"","path":"trip/b.jpg"}}]],'
    '"rights":[{"name":"view"}]}]';

/// A search that found nothing, and why.
String problem(String name) =>
    '["AlbumInfo",{"kind":"SEARCH","searchProblem":"$name","parts":[],'
    '"rights":[{"name":"view"}]}]';

/// A view-only member's space, content search on or off.
PhotoSpace viewer({bool content = true, String kind = "IMAGE"}) => PhotoSpace(
      role: "view",
      rights: const ["view"],
      content: content,
      aKind: kind,
      searchAnswer: alike,
    );

/// Opens the viewer's menu.
Future<void> openViewerMenu(WidgetTester tester) async {
  await tester.tap(find.descendant(
      of: find.byKey(const Key("viewer-menu")),
      matching: find.byType(PopupMenuButton<void Function(BuildContext)>)));
  await settle(tester);
}

/// The query of the last search, as sent.
SearchQuery lastQuery(PhotoSpace space) =>
    SearchQuery.fromString(space.posts("search").last.body)!;

/// The names of the photos of the result of the search view, in its order.
Future<List<String>> shown(WidgetTester tester) async {
  var delegate = tester
      .widget<MaterialApp>(find.byType(MaterialApp))
      .routerDelegate! as VAlbumRouterDelegate;
  var album = await delegate.searchSession(const []).result!;
  return [
    for (var part in album.parts)
      if (part is ImagePart) part.name,
  ];
}

/// Opens "More like this" of `a.jpg` from the viewer, opened on its album.
Future<void> moreLikeA(WidgetTester tester, PhotoSpace space) async {
  await openSpace(tester, space, const ImageRoute(["trip"], "a.jpg"));
  await openViewerMenu(tester);
  await tester.tap(find.byKey(const Key("viewer-more-like-this")));
  await settle(tester);
}

void main() {
  var l10n = testL10n;

  group('"More like this" is offered', () {
    for (var kind in ["IMAGE", "VIDEO"]) {
      for (var content in [true, false]) {
        testWidgets(
            'in the viewer\'s menu of a ${kind.toLowerCase()} '
            '${content ? "where" : "never where"} content search is '
            '${content ? "on" : "off"}', (tester) async {
          if (kind == "VIDEO") {
            VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
          }
          await openSpace(tester, viewer(content: content, kind: kind),
              const ImageRoute(["trip"], "a.jpg"));
          await openViewerMenu(tester);
          expect(find.byKey(const Key("viewer-more-like-this")),
              content ? findsOneWidget : findsNothing);
          if (content) {
            expect(
                find.descendant(
                    of: find.byKey(const Key("viewer-more-like-this")),
                    matching: find.text(l10n.moreLikeThis)),
                findsOneWidget);
          }
        });

        testWidgets(
            'in a tile\'s menu of a ${kind.toLowerCase()} '
            '${content ? "where" : "never where"} content search is '
            '${content ? "on" : "off"}', (tester) async {
          await openSpace(tester, viewer(content: content, kind: kind),
              const ListingOrAlbumRoute(["trip"]));
          await rightClick(tester, "a.jpg");
          expect(menuOpen(), isTrue);
          expect(find.byKey(const Key("tile-context-more-like-this")),
              content ? findsOneWidget : findsNothing);
        });
      }
    }

    testWidgets('on a search result that names its album, and not otherwise',
        (tester) async {
      await openSpace(tester, viewer(), const SearchRoute([]));
      await chooseAnne(tester);
      await rightClick(tester, "trip/c.jpg");
      expect(find.byKey(const Key("tile-context-more-like-this")),
          findsOneWidget);
    });

    testWidgets('never where the search does not name the album',
        (tester) async {
      var space = viewer()..refs = false;
      space.searchAnswer = null;
      await openSpace(tester, space, const SearchRoute([]));
      await chooseAnne(tester);
      await rightClick(tester, "trip/a.jpg");
      expect(menuOpen(), isTrue);
      expect(
          find.byKey(const Key("tile-context-more-like-this")), findsNothing);
    });

    test('never through a link, never on a preview, never without search',
        () {
      var image = ImagePart(name: "a.jpg");
      PhotoFacts facts({
        bool content = true,
        bool shareLink = false,
        bool previewing = false,
        bool signedIn = true,
        List<String> rights = const [rightView],
      }) =>
          PhotoFacts(
            view: PhotoView.album,
            mode: PhotoMode.view,
            rights: Rights.ofNames(rights),
            signedIn: signedIn,
            shareLink: shareLink,
            previewing: previewing,
            searchesContent: content,
          );
      var action = PhotoAction.moreLikeThis;
      expect(action.offeredOn(image, facts()), isTrue,
          reason: "A read: a view-only member is offered it.");
      expect(action.offeredOn(image, facts(content: false)), isFalse);
      expect(action.offeredOn(image, facts(shareLink: true)), isFalse);
      expect(action.offeredOn(image, facts(previewing: true)), isFalse);
      expect(action.offeredOn(image, facts(signedIn: false)), isFalse,
          reason: "An anonymous visitor may not search.");
      expect(
          action.offeredOn(image,
              facts(signedIn: false, rights: const [rightView, rightEdit])),
          isTrue,
          reason: "An editor of the folder may search it.");
      expect(action.offeredOn(ImagePart(name: "a.jpg", missing: true), facts()),
          isFalse);
    });
  });

  group('choosing it', () {
    testWidgets('opens the search with the photo\'s chip, the request carrying '
        'it, the answer nearest first', (tester) async {
      var space = viewer();
      await moreLikeA(tester, space);

      expect(routeOf(tester), isA<SearchRoute>());
      expect(find.byKey(const Key("search-similar")), findsOneWidget);
      expect(find.text(l10n.searchSimilarChip), findsOneWidget);
      var avatar = tester.widget<CircleAvatar>(
          find.byKey(const Key("search-similar-thumbnail")));
      var picture = avatar.backgroundImage as ThumbnailImage;
      expect(Uri.decodeFull(picture.url), endsWith("/trip/a.jpg?type=tn"));

      var query = lastQuery(space);
      var root = query.root as SearchAnd;
      var similar = root.criteria.whereType<SearchSimilar>().single;
      expect(similar.ref!.path, "trip/a.jpg");
      expect(root.criteria.length, 1);
      expect(query.groupBy, isEmpty, reason: "Nearest first, ungrouped.");
      expect(space.posts("search").last.url.queryParameters["count"],
          "$contentSearchPage");
      expect(await shown(tester), ["trip/c.jpg", "trip/b.jpg"],
          reason: "The server's order, never by date.");

      // A search by what the photos show: the switch says so, and is left by
      // removing the photo.
      var aiSwitch =
          tester.widget<FilterChip>(find.byKey(const Key("search-by-content")));
      expect(aiSwitch.selected, isTrue);
      expect(aiSwitch.onSelected, isNull);
    });

    testWidgets('from a tile is the same action', (tester) async {
      var space = viewer();
      await openSpace(tester, space, const ListingOrAlbumRoute(["trip"]));
      await rightClick(tester, "b.jpg");
      await tester.tap(find.byKey(const Key("tile-context-more-like-this")));
      await settle(tester);
      expect(find.byKey(const Key("search-similar")), findsOneWidget);
      expect(
          (lastQuery(space).root as SearchAnd)
              .criteria
              .whereType<SearchSimilar>()
              .single
              .ref!
              .path,
          "trip/b.jpg");
    });

    testWidgets('a person narrows it; removing the chip leaves a normal search',
        (tester) async {
      var space = viewer();
      await moreLikeA(tester, space);
      await chooseAnne(tester);
      var root = lastQuery(space).root as SearchAnd;
      expect(root.criteria.whereType<SearchSimilar>(), hasLength(1));
      expect(root.criteria.whereType<SearchPerson>().single.person, "p1");

      await tester.tap(find.descendant(
          of: find.byKey(const Key("search-similar")),
          matching: find.byIcon(Icons.clear)));
      await settle(tester);
      expect(find.byKey(const Key("search-similar")), findsNothing);
      var after = lastQuery(space);
      var rows = (after.root as SearchAnd).criteria;
      expect(rows.whereType<SearchSimilar>(), isEmpty);
      expect(rows.whereType<SearchPerson>().single.person, "p1");
      expect(space.posts("search").last.url.queryParameters["count"], isNull,
          reason: "The conventional search answers everything, by date.");
      expect(find.byKey(const Key("search-by-content")), findsNothing,
          reason: "The space's options offer no AI search here.");
    });

    testWidgets('a photo not analysed yet is said', (tester) async {
      var space = viewer()..searchAnswer = problem("PHOTO_NOT_DESCRIBED");
      await moreLikeA(tester, space);
      expect(find.byKey(const Key("search-problem")), findsOneWidget);
      expect(find.text(l10n.searchSimilarNotDescribed), findsOneWidget);
      expect(find.byKey(const Key("search-empty")), findsNothing);
    });

    testWidgets('a photo that is not there is said', (tester) async {
      var space = viewer()..searchAnswer = problem("PHOTO_NOT_FOUND");
      await moreLikeA(tester, space);
      expect(find.text(l10n.searchSimilarNotFound), findsOneWidget);
    });
  });

  testWidgets('a saved "More like this" whose photo is gone opens and says so',
      (tester) async {
    var space = _LikedSpace();
    await openSpace(tester, space, const ListingOrAlbumRoute(["Liked"]));
    expect(find.byKey(const Key("search-problem")), findsOneWidget);
    expect(find.text(l10n.searchSimilarNotFound), findsOneWidget);
    expect(find.text("Liked"), findsWidgets, reason: "Its title, no error.");
  });

  group('the criteria of "More like this"', () {
    test('rank with AND around the photo, the text a conventional one', () {
      var criteria = const SearchCriteria(
        similar: "trip/a.jpg",
        persons: ["p1"],
        text: "sea",
        anyOf: true,
        byContent: true,
      );
      var root = criteria.toQuery().root as SearchAnd;
      expect(root.criteria.first, isA<SearchSimilar>());
      expect(root.criteria.whereType<SearchText>().single.text, "sea",
          reason: "A query ranks once.");
      expect(root.criteria.whereType<SearchContent>(), isEmpty);
      expect(criteria.ranks, isTrue);
      expect(criteria.byWhatTheyShow, isTrue);
      expect(
          criteria.title(fallback: "Search", similarTo: "Like this photo"),
          "Like this photo, p1, “sea”");

      var removed = criteria.copyWith(clearSimilar: true);
      expect(removed.similar, isNull);
      expect(removed.toQuery().root, isA<SearchAnd>(),
          reason: "Back to the AI search of the text, as it was.");
    });

    test('read back from the stored query, its hash kept', () {
      var stored = SearchQuery(version: 1, root: SearchAnd(criteria: [
        SearchSimilar(ref: PhotoRef(hash: "abc", path: "trip/a.jpg")),
        SearchPerson(person: "p1"),
      ]));
      var read = SearchCriteria.fromQuery(stored)!;
      expect(read.similar, "trip/a.jpg");
      expect(read.similarHash, "abc");
      var again = (read.toQuery().root as SearchAnd)
          .criteria
          .whereType<SearchSimilar>()
          .single
          .ref!;
      expect((again.hash, again.path), ("abc", "trip/a.jpg"));
      expect(SearchCriteria.fromAddress(read.toAddress()), read);

      expect(
        SearchCriteria.fromQuery(SearchQuery(version: 1, root: SearchAnd(
            criteria: [
              SearchSimilar(ref: PhotoRef(path: "trip/a.jpg")),
              SearchContent(text: "sea"),
            ]))),
        isNull,
        reason: "A text and a photo do not rank at once.",
      );
      expect(
        SearchCriteria.fromQuery(SearchQuery(version: 1, root: SearchOr(
            criteria: [SearchSimilar(ref: PhotoRef(path: "trip/a.jpg"))]))),
        isNull,
      );
    });
  });
}

/// A space with a saved "More like this" `Liked` whose photo is gone.
class _LikedSpace extends PhotoSpace {
  _LikedSpace() : super(content: true);

  @override
  http.Response answer(http.Request request) {
    if (Uri.decodeFull(request.url.path) == "/valbum/data/Liked/" &&
        request.url.queryParameters["type"] != "auth") {
      return json('["AlbumInfo", {"path": "Liked", "kind": "SEARCH", '
          '"title": "Liked", "subTitle": "", "rights": [{"name": "view"}], '
          '"searchProblem": "PHOTO_NOT_FOUND", '
          '"query": {"version": 1, "root": ["SearchAnd", {"criteria": '
          '[["SearchSimilar", {"ref": {"hash": "", "path": ""}}]]}]}, '
          '"parts": []}]');
    }
    return super.answer(request);
  }
}
