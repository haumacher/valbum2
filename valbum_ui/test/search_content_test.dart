/// The AI search of issue #257 in the search view: the switch next to the
/// search field, offered only where the server says the space has content
/// search on; the request it makes (a `SearchContent` with the field's text,
/// the chips narrowing, one page at a time); the result in the server's
/// order of similarity, also when grouped and ungrouped again; the refusal in
/// the server's words; and switching back to the conventional search.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_grouping.dart';
import 'package:valbum_ui/search_query.dart';
import 'package:valbum_ui/search_view.dart' show contentSearchPage;

import 'search_view_test.dart' show Space, chooseAnne, json, pumpRoute, settle;
import 'util/l10n.dart';

/// The options of a space with content search on: Anne alone.
const String contentOptions =
    '{"persons":[{"id":"p1","name":"Anne"}],"content":true}';

/// What an AI search finds: the most similar first, though it was taken
/// last.
const String similar = '["AlbumInfo",{"kind":"SEARCH","parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2021 Sea/late.jpg",'
    '"date":1630000000000,"width":2048,"height":1536,'
    '"orientation":"IDENTITY","score":0.61,'
    '"ref":{"hash":"","path":"2021 Sea/late.jpg"}}],'
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/early.jpg",'
    '"date":1580000000000,"width":2048,"height":1536,'
    '"orientation":"IDENTITY","score":0.32,'
    '"ref":{"hash":"","path":"2020 Trip/early.jpg"}}]],'
    '"rights":[{"name":"view"}]}]';

/// A full page of an AI search: [count] photos, the most similar first.
String page(int count) {
  var parts = [
    for (var n = 0; n < count; n++)
      '["ImagePart",{"kind":"IMAGE","name":"A/p$n.jpg","date":${1600000000000 + n},'
          '"width":4,"height":3,"orientation":"IDENTITY",'
          '"score":${0.9 - n / 1000}}]',
  ];
  return '["AlbumInfo",{"kind":"SEARCH","parts":[${parts.join(",")}],'
      '"rights":[{"name":"view"}]}]';
}

/// A space whose options say whether content search is on, and whose search
/// may refuse.
class ContentSpace extends Space {
  /// Whether the space has content search on.
  final bool content;

  /// The refusal of the next search, `null` for an answer.
  String? refusal;

  ContentSpace({this.content = true});

  @override
  http.Response answer(http.Request request) {
    var parameters = request.url.queryParameters;
    if (parameters["type"] == "search-options") {
      return json(content ? contentOptions : '{"persons":[]}');
    }
    if (request.method == "POST" &&
        parameters["action"] == "search" &&
        refusal != null) {
      return http.Response(
        '["ErrorInfo",{"message":"$refusal"}]',
        503,
        headers: {"content-type": "application/json; charset=utf-8"},
      );
    }
    return super.answer(request);
  }

  /// The query of the last search, as sent.
  SearchQuery get lastQuery =>
      SearchQuery.fromString(posts("search").last.body)!;

  /// The `count` the last search asked for, `null` for none.
  String? get lastCount => posts("search").last.url.queryParameters["count"];
}

/// The names of the photos of the result the view shows, in its order.
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

Future<void> typeAndToggle(WidgetTester tester, String text) async {
  await tester.enterText(find.byKey(const Key("search-text")), text);
  await tester.tap(find.byKey(const Key("search-by-content")));
  await settle(tester);
}

void main() {
  var l10n = l10nOf();

  testWidgets('no switch where content search is off', (tester) async {
    await pumpRoute(
      tester,
      ContentSpace(content: false),
      const SearchRoute([]),
    );
    expect(find.byKey(const Key("search-text")), findsOneWidget);
    expect(find.byKey(const Key("search-by-content")), findsNothing);
  });

  testWidgets('the switch is offered where content search is on', (
    tester,
  ) async {
    await pumpRoute(tester, ContentSpace(), const SearchRoute([]));
    expect(find.byKey(const Key("search-by-content")), findsOneWidget);
    expect(find.text(l10n.searchByContent), findsOneWidget);
    expect(find.text(l10n.searchContentHint), findsNothing);

    await tester.tap(find.byKey(const Key("search-by-content")));
    await settle(tester);
    expect(find.text(l10n.searchContentHint), findsOneWidget,
        reason: "The field asks for a description.");
  });

  testWidgets('the AI search sends the text as content, the chips narrowing',
      (tester) async {
    var space = ContentSpace()..searchAnswer = similar;
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    await typeAndToggle(tester, "sunset over the sea");

    var query = space.lastQuery;
    var root = query.root as SearchAnd;
    expect(
      root.criteria.whereType<SearchContent>().single.text,
      "sunset over the sea",
    );
    expect(root.criteria.whereType<SearchPerson>().single.person, "p1");
    expect(root.criteria.whereType<SearchText>(), isEmpty);
    expect(query.groupBy, isEmpty, reason: "Similarity order, ungrouped.");
    expect(space.lastCount, "$contentSearchPage");
  });

  testWidgets('the result keeps the order of similarity', (tester) async {
    var space = ContentSpace()..searchAnswer = similar;
    await pumpRoute(tester, space, const SearchRoute([]));
    await typeAndToggle(tester, "sea");

    expect(await shown(tester), ["2021 Sea/late.jpg", "2020 Trip/early.jpg"],
        reason: "Never sorted by date.");

    // Grouped and ungrouped again: still by similarity.
    var parts = groupSearchParts(
      (Resource.fromString(similar) as AlbumInfo).parts,
      const [SearchGroup.year],
      texts: SearchGroupTexts(
        withoutDate: "-",
        withoutPlace: "-",
        withoutCamera: "-",
        monthText: (date) => "$date",
      ),
    );
    var ungrouped = groupSearchParts(
      parts,
      const [],
      texts: SearchGroupTexts(
        withoutDate: "-",
        withoutPlace: "-",
        withoutCamera: "-",
        monthText: (date) => "$date",
      ),
    );
    expect(
      [for (var part in ungrouped) (part as ImagePart).name],
      ["2021 Sea/late.jpg", "2020 Trip/early.jpg"],
    );
  });

  testWidgets('a full page offers more, which asks for the next page',
      (tester) async {
    var space = ContentSpace()..searchAnswer = page(contentSearchPage);
    await pumpRoute(tester, space, const SearchRoute([]));
    await typeAndToggle(tester, "sea");
    expect(find.byKey(const Key("search-more")), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-more")));
    await settle(tester);
    expect(space.lastCount, "${2 * contentSearchPage}");
    expect(space.lastQuery.root, isA<SearchAnd>());

    space.searchAnswer = similar;
    await tester.enterText(find.byKey(const Key("search-text")), "rain");
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await settle(tester);
    expect(space.lastCount, "$contentSearchPage",
        reason: "A new search starts with one page.");
    expect(find.byKey(const Key("search-more")), findsNothing,
        reason: "A page that came back short has no more.");
  });

  testWidgets('a refusal is said in the server\'s words', (tester) async {
    var space = ContentSpace()
      ..refusal = "Content search is not available now: the embedder service "
          "is starting.";
    await pumpRoute(tester, space, const SearchRoute([]));
    await typeAndToggle(tester, "sea");

    expect(find.byKey(const Key("search-error")), findsOneWidget);
    expect(
      find.textContaining("the embedder service is starting"),
      findsOneWidget,
    );
  });

  testWidgets('switching back restores the conventional search',
      (tester) async {
    var space = ContentSpace()..searchAnswer = similar;
    await pumpRoute(tester, space, const SearchRoute([]));
    await chooseAnne(tester);
    var before = space.lastQuery;
    expect(before.groupBy, [SearchGroup.album]);
    await typeAndToggle(tester, "sea");
    expect(space.lastQuery.root, isA<SearchAnd>());

    await tester.tap(find.byKey(const Key("search-by-content")));
    await settle(tester);
    var after = space.lastQuery;
    var root = after.root as SearchAnd;
    expect(root.criteria.whereType<SearchContent>(), isEmpty);
    expect(root.criteria.whereType<SearchText>().single.text, "sea");
    expect(root.criteria.whereType<SearchPerson>().single.person, "p1");
    expect(after.groupBy, [SearchGroup.album],
        reason: "The grouping of the conventional search comes back.");
    expect(space.lastCount, isNull);
  });

  group('the criteria of an AI search', () {
    test('rank with AND, the text never excluded', () {
      var criteria = const SearchCriteria(
        persons: ["p1"],
        text: "sea",
        excluded: {SearchKind.text},
        anyOf: true,
        byContent: true,
      );
      var query = criteria.toQuery();
      var root = query.root as SearchAnd;
      expect(root.criteria, [isA<SearchPerson>(), isA<SearchContent>()]);
      expect(criteria.excludes(SearchKind.text), isFalse);
      expect(criteria.ranks, isTrue);

      var back = criteria.copyWith(byContent: false).toQuery();
      expect(back.root, isA<SearchOr>(), reason: "What was chosen is kept.");
      expect((back.root as SearchOr).criteria.last, isA<SearchNot>());
    });

    test('read back from the stored query', () {
      var stored = const SearchCriteria(text: "sea", byContent: true).toQuery();
      var read = SearchCriteria.fromQuery(stored)!;
      expect(read.byContent, isTrue);
      expect(read.text, "sea");
      expect(read.toQuery().root, isA<SearchAnd>());

      var alone = SearchCriteria.fromQuery(
        SearchQuery(version: 1, root: SearchContent(text: "sea")),
      )!;
      expect(alone.byContent, isTrue);

      expect(
        SearchCriteria.fromQuery(SearchQuery(
          version: 1,
          root: SearchOr(criteria: [SearchContent(text: "sea")]),
        )),
        isNull,
        reason: "Content is never one of any of these.",
      );
      expect(
        const SearchCriteria(text: "sea", byContent: true).title(
          fallback: "Search",
        ),
        "“sea”",
      );
    });
  });
}
