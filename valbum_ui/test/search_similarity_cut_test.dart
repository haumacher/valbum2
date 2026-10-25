/// The calibration of the content search of issue #302, the app's part: the
/// similarity of a photo found by what it shows in its tile's tooltip, in the
/// language shown; the chip of the cut and its slider in the search view,
/// shown only while the search ranks, which searches again with the cut a
/// moment after the slider rests; a saved search's cut shown when it is
/// edited; and the bar and line of a search on its way (the AI search takes a
/// while), the results before it dimmed, a search replaced by a newer one
/// never shown as failed.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/search_query.dart';
import 'package:valbum_ui/search_view.dart';

import 'search_view_test.dart'
    show Space, chooseAnne, dataUrl, json, pumpRoute, settle;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The options of a space with content search on, and its cuts.
const String cutOptions = '{"persons":[{"id":"p1","name":"Anne"}],'
    '"content":true,"contentThreshold":0.05,"similarThreshold":0.3}';

/// What an AI search finds, each photo with its score.
const String scored = '["AlbumInfo",{"kind":"SEARCH","parts":['
    '["ImagePart",{"kind":"IMAGE","name":"2021 Sea/late.jpg",'
    '"date":1630000000000,"width":2048,"height":1536,'
    '"orientation":"IDENTITY","score":0.6234,"comment":"Waves",'
    '"ref":{"hash":"","path":"2021 Sea/late.jpg"}}],'
    '["ImagePart",{"kind":"IMAGE","name":"2020 Trip/early.jpg",'
    '"date":1580000000000,"width":2048,"height":1536,'
    '"orientation":"IDENTITY","score":0.32,'
    '"ref":{"hash":"","path":"2020 Trip/early.jpg"}}]],'
    '"rights":[{"name":"view"}]}]';

/// A saved AI search stating its own cut.
const String savedWithCut = '["AlbumInfo",{"kind":"SEARCH","title":"Sea",'
    '"query":{"version":2,"root":["SearchAnd",{"criteria":'
    '[["SearchContent",{"text":"sea","minSimilarity":0.15}]]}]},'
    '"rights":[{"name":"view"},{"name":"edit"}],"parts":[]}]';

/// A space with content search on, whose searches may be held back.
class CutSpace extends Space {
  /// Holds the answer of the next searches back until it completes; `null`
  /// to answer at once.
  Completer<void>? hold;

  @override
  http.Response answer(http.Request request) {
    var parameters = request.url.queryParameters;
    if (parameters["type"] == "search-options") {
      return json(cutOptions);
    }
    if (pathOf(request) == "/valbum/data/Sea/") {
      return json(savedWithCut);
    }
    return super.answer(request);
  }

  @override
  VAlbumClient get client => VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          var held = hold;
          if (held != null &&
              request.method == "POST" &&
              request.url.queryParameters["action"] == "search") {
            await held.future;
          }
          return answer(request);
        }),
      );

  /// The query of the last search, as sent.
  SearchQuery get lastQuery =>
      SearchQuery.fromString(posts("search").last.body)!;

  /// The cut the last search stated.
  double get lastCut {
    var root = lastQuery.root as SearchAnd;
    for (var criterion in root.criteria) {
      if (criterion case SearchContent(:var minSimilarity)) {
        return minSimilarity;
      }
      if (criterion case SearchSimilar(:var minSimilarity)) {
        return minSimilarity;
      }
    }
    fail("No ranking criterion in ${posts("search").last.body}");
  }
}

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// Types [text] and switches the AI search on.
Future<void> searchByContent(WidgetTester tester, String text) async {
  await tester.enterText(find.byKey(const Key("search-text")), text);
  await tester.tap(find.byKey(const Key("search-by-content")));
  await settle(tester);
}

Slider slider(WidgetTester tester) =>
    tester.widget<Slider>(find.byKey(const Key("search-similarity-slider")));

void main() {
  var en = l10nOf();
  var de = l10nOf(const Locale("de"));

  group('the similarity in the tooltip', () {
    var image = ImagePart(name: "a.jpg", score: 0.6234);

    test('says the score with two decimals in the language shown', () {
      expect(tileTooltipText(en, image, null), "Similarity 0.62");
      expect(tileTooltipText(de, image, null), "Ähnlichkeit 0,62");
    });

    test('follows the caption', () {
      var described = ImagePart(name: "a.jpg", score: 0.5, comment: "Waves");
      expect(tileTooltipText(en, described, null), "Waves\nSimilarity 0.50");
    });

    test('says nothing of a photo found by no similarity', () {
      expect(tileTooltipText(en, ImagePart(name: "a.jpg"), null), isNull);
      expect(
        tileTooltipText(en, ImagePart(name: "a.jpg", comment: "Waves"), null),
        "Waves",
      );
    });

    testWidgets('is the tooltip of the tiles of an AI search', (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      await pumpRoute(tester, space, const SearchRoute([]));
      await searchByContent(tester, "sea");
      var messages = [
        for (var tooltip in tester.widgetList<Tooltip>(
          find.byKey(const Key("tile-caption")),
        ))
          tooltip.message,
      ];
      expect(messages, ["Waves\nSimilarity 0.62", "Similarity 0.32"]);
    });
  });

  group('the cut of the search', () {
    testWidgets('a chip says it only while the search ranks', (tester) async {
      var space = CutSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);
      expect(find.byKey(const Key("search-similarity")), findsNothing,
          reason: "A conventional search has no cut.");

      space.searchAnswer = scored;
      await searchByContent(tester, "sea");
      expect(find.byKey(const Key("search-similarity")), findsOneWidget);
      expect(find.text(en.searchSimilarityAtLeast("0.05")), findsOneWidget,
          reason: "Without a cut of its own, the space's.");
      expect(space.lastCut, 0, reason: "The space's cut is not sent.");
      expect(space.lastQuery.version, searchQueryFirstVersion);

      await tester.tap(find.byKey(const Key("search-by-content")));
      await settle(tester);
      expect(find.byKey(const Key("search-similarity")), findsNothing);
    });

    testWidgets('the slider searches with its cut once it rests',
        (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      await pumpRoute(tester, space, const SearchRoute([]));
      await searchByContent(tester, "sea");
      await tester.tap(find.byKey(const Key("search-similarity")));
      await settle(tester);
      expect(find.byKey(const Key("search-similarity-panel")), findsOneWidget);
      expect(slider(tester).min, 0.01);
      expect(slider(tester).max, 1.0,
          reason: "A reasonable text match lies above 0.7.");
      var searches = space.posts("search").length;

      slider(tester).onChanged!(0.2);
      await tester.pump(const Duration(milliseconds: 100));
      slider(tester).onChanged!(0.25);
      await tester.pump(const Duration(milliseconds: 100));
      slider(tester).onChanged!(0.3);
      await tester.pump(const Duration(milliseconds: 100));
      expect(space.posts("search").length, searches,
          reason: "Not a search per position passed.");
      expect(find.text(en.searchSimilarityAtLeast("0.30")), findsOneWidget,
          reason: "The chip follows the slider at once.");

      await tester.pump(cutDelay);
      await settle(tester);
      expect(space.posts("search").length, searches + 1);
      expect(space.lastCut, 0.3);
      expect(space.lastQuery.version, searchQueryVersion,
          reason: "A stated cut is what an older build must refuse.");
      expect(find.text(en.searchSimilarityAtLeast("0.30")), findsOneWidget);

      // Let go: at once.
      slider(tester).onChangeEnd!(0.12);
      await settle(tester);
      expect(space.posts("search").length, searches + 2);
      expect(space.lastCut, 0.12);

      // A high cut, where reasonable text matches lie, reaches the server.
      slider(tester).onChangeEnd!(0.75);
      await settle(tester);
      expect(space.posts("search").length, searches + 3);
      expect(space.lastCut, 0.75);
      expect(find.text(en.searchSimilarityAtLeast("0.75")), findsOneWidget);

      // Back to the space's cut.
      await tester.tap(find.byKey(const Key("search-similarity-reset")));
      await settle(tester);
      expect(space.lastCut, 0);
      expect(find.text(en.searchSimilarityAtLeast("0.05")), findsOneWidget);
    });

    testWidgets('"More like this" offers the range of two photos',
        (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      var delegate = await pumpRoute(tester, space, const SearchRoute([]));
      var session = delegate.searchSession(const []);
      session.choose(
        const SearchCriteria(similar: "2021 Sea/late.jpg"),
        space.client,
        const [],
      );
      await settle(tester);
      expect(find.text(en.searchSimilarityAtLeast("0.30")), findsOneWidget);
      await tester.tap(find.byKey(const Key("search-similarity")));
      await settle(tester);
      expect(slider(tester).min, 0.2);
      expect(slider(tester).max, 1.0);
      slider(tester).onChangeEnd!(0.8);
      await settle(tester);
      expect(space.lastQuery.root, isA<SearchAnd>());
      expect(
        (space.lastQuery.root as SearchAnd)
            .criteria
            .whereType<SearchSimilar>()
            .single
            .minSimilarity,
        0.8,
      );
    });

    test('a cut goes with the kind it was set for', () {
      var text = const SearchCriteria(text: "sea", byContent: true)
          .copyWith(minSimilarity: 0.2);
      expect(text.toQuery().version, searchQueryVersion);
      var photo = text.copyWith(similar: "a.jpg");
      expect(photo.minSimilarity, 0,
          reason: "A photo is compared on another scale than a text.");
      expect(photo.toQuery().version, searchQueryFirstVersion);
      var read = SearchCriteria.fromQuery(text.toQuery())!;
      expect(read.minSimilarity, 0.2);
      expect(read, text);
    });

    testWidgets('a saved search shows its cut when it is edited',
        (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      await pumpRoute(tester, space, const ListingOrAlbumRoute(["Sea"]));
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await settle(tester);
      await tester.tap(find.byKey(const Key("edit-search")));
      await settle(tester);
      expect(find.text(en.searchSimilarityAtLeast("0.15")), findsOneWidget);

      await tester.tap(find.byKey(const Key("search-save-edit")));
      await settle(tester);
      var stored = Resource.fromString(space.puts.single.body) as AlbumInfo;
      var content = (stored.query!.root as SearchAnd)
          .criteria
          .whereType<SearchContent>()
          .single;
      expect(content.minSimilarity, 0.15, reason: "Saved with its cut.");
      expect(stored.query!.version, searchQueryVersion);
    });
  });

  group('a search on its way', () {
    testWidgets('shows a bar, dims what was found, and counts the seconds',
        (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      await pumpRoute(tester, space, const SearchRoute([]));
      await searchByContent(tester, "sea");
      expect(find.byKey(const Key("search-progress")), findsNothing);

      var hold = Completer<void>();
      space.hold = hold;
      await tester.enterText(find.byKey(const Key("search-text")), "rain");
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const Key("search-progress")), findsOneWidget);
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      expect(find.byKey(const Key("search-progress-line")), findsNothing,
          reason: "A quick search is not worth a sentence.");
      expect(find.byKey(const ValueKey("2021 Sea/late.jpg")), findsOneWidget,
          reason: "What was found stays in view.");
      expect(find.byType(SliverOpacity), findsWidgets);
      expect(
        tester
            .widgetList<SliverOpacity>(find.byType(SliverOpacity))
            .every((sliver) => sliver.opacity == albumDimmedOpacity),
        isTrue,
        reason: "Dimmed: it is what the search before found.",
      );

      await tester.pump(const Duration(seconds: 2));
      expect(find.text(en.searchContentRunning(2)), findsOneWidget);
      await tester.pump(const Duration(seconds: 10));
      expect(find.text(en.searchContentRunning(12)), findsOneWidget);

      hold.complete();
      space.hold = null;
      await settle(tester);
      expect(find.byKey(const Key("search-progress")), findsNothing);
      expect(
        tester
            .widgetList<SliverOpacity>(find.byType(SliverOpacity))
            .every((sliver) => sliver.opacity == 1),
        isTrue,
      );
    });

    testWidgets('a conventional search says it plainly', (tester) async {
      var space = CutSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      var hold = Completer<void>();
      space.hold = hold;
      await tester.tap(find.byKey(const Key("search-add-persons")));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.tap(find.byKey(const Key("search-choice-p1")));
      await tester.pump();
      await tester.tap(find.byKey(const Key("search-choose-done")));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pump(const Duration(seconds: 3));
      expect(find.text(en.searchRunning), findsOneWidget);
      hold.complete();
      space.hold = null;
      await settle(tester);
      expect(find.text(en.searchRunning), findsNothing);
    });

    testWidgets('a search replaced by a newer one is never shown as failed',
        (tester) async {
      var space = CutSpace()..searchAnswer = scored;
      await pumpRoute(tester, space, const SearchRoute([]));
      await searchByContent(tester, "sea");

      // The first search fails late, after a newer one was answered.
      var first = Completer<void>();
      space.hold = first;
      await tester.enterText(find.byKey(const Key("search-text")), "rain");
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pump();
      space.hold = null;
      await tester.enterText(find.byKey(const Key("search-text")), "snow");
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await settle(tester);
      space.searchAnswer = "not json at all";
      first.complete();
      await settle(tester);

      expect(find.byKey(const Key("search-error")), findsNothing);
      expect(find.byKey(const Key("search-progress")), findsNothing);
      expect(find.byKey(const ValueKey("2021 Sea/late.jpg")), findsOneWidget);
    });
  });
}
