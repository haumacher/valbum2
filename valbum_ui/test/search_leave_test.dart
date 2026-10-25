/// Leaving the search view by its back arrow, issue #259: every way into the
/// search and through it — criteria chosen, the results scrolled far, a
/// photograph opened and closed, the scope widened to the whole library, the
/// browser's history, the search opened by its address — and the page it is
/// left to shows its content, on a phone and on a desktop.
///
/// After a widening the back arrow leaves the search to the album it was
/// opened from, which stayed beneath it as it was left: the start page was
/// never where the search came from.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/search_query.dart';

import 'search_layout_test.dart' show many, phone, resultsScrollable;
import 'search_scope_test.dart' show ScopeSpace;
import 'search_view_test.dart' show chooseAnne, json, pathOf, pumpRoute, settle;
import 'util/l10n.dart';
import 'util/search_entry.dart';

/// The number of photographs of [bigZoo].
const int zooCount = 60;

/// The album `2024/Zoo` with [zooCount] photographs, more than a screen.
final String bigZoo = '["AlbumInfo",{"path":"","title":"Zoo","subTitle":"",'
    '"parts":[${[
  for (var i = 0; i < zooCount; i++)
    '["ImagePart",{"kind":"IMAGE","name":"a$i.jpg",'
        '"date":${1015113600000 + i * 60000},"width":2048,"height":1536,'
        '"orientation":"IDENTITY","rating":0}]'
].join(",")}]}]';

/// The space of `search_scope_test.dart` with a long album and a search that
/// finds more than a screen.
class LongSpace extends ScopeSpace {
  LongSpace() {
    searchAnswer = many;
  }

  @override
  http.Response answer(http.Request request) {
    if (request.method == "GET" &&
        request.url.queryParameters["type"] == "json" &&
        pathOf(request) == "/valbum/data/2024/Zoo/") {
      return json(bigZoo);
    }
    return super.answer(request);
  }
}

const zoo = ListingOrAlbumRoute(["2024", "Zoo"]);

/// The search of the album for Anne, as its address carries it.
final SearchRoute zooSearch = SearchRoute(
  const ["2024", "Zoo"],
  query: const SearchCriteria(persons: ["p1"]).toAddress(),
);

/// A phone and a desktop: the desktop shows the scrollbar for good.
const platforms = TargetPlatformVariant(
  {TargetPlatform.android, TargetPlatform.linux},
);

/// The browser's back or forward button, landing on [route].
Future<void> browserTo(WidgetTester tester, VAlbumRoute route) async {
  var message = const JSONMethodCodec().encodeMethodCall(
    MethodCall('pushRouteInformation', {
      'location': routeToUri(route).toString(),
      'state': null,
    }),
  );
  await tester.binding.defaultBinaryMessenger
      .handlePlatformMessage('flutter/navigation', message, (_) {});
  await settle(tester);
}

/// The back arrow of the search view, clicked.
Future<void> leave(WidgetTester tester) async {
  await tester.tap(
    find.byKey(const Key("search-up")),
    kind: PointerDeviceKind.mouse,
  );
  await settle(tester);
}

Future<void> openSearch(WidgetTester tester) async {
  await tapOpenSearch(tester);
  await settle(tester);
}

Future<void> widen(WidgetTester tester) async {
  await tester.tap(find.byTooltip(l10nOf().searchEverywhere));
  await settle(tester);
}

/// The scroll position of the album shown.
ScrollPosition albumPosition(WidgetTester tester) => tester
    .state<ScrollableState>(
      find
          .descendant(
            of: find.byType(AlbumContent),
            matching: find.byType(Scrollable),
          )
          .first,
    )
    .position;

/// The album `2024/Zoo` is on the screen with its photographs, within its
/// extent.
void expectZoo(WidgetTester tester, VAlbumRouterDelegate delegate) {
  expect(delegate.route, zoo);
  expect(find.byKey(const Key("search-up")), findsNothing);
  var shown = [
    for (var i = 0; i < zooCount; i++)
      if (find.byKey(ValueKey("a$i.jpg")).hitTestable().evaluate().isNotEmpty)
        i,
  ];
  expect(shown, isNotEmpty, reason: "The album shows its photographs.");
  var position = albumPosition(tester);
  expect(position.pixels, lessThanOrEqualTo(position.maxScrollExtent));
}

void main() {
  testWidgets('the search of an album is left to the album', variant: platforms,
      (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zoo);
    await openSearch(tester);
    await chooseAnne(tester);

    await leave(tester);

    expectZoo(tester, delegate);
  });

  testWidgets('results scrolled far leave the album where it was',
      variant: platforms, (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zoo);
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -1500));
    await settle(tester);
    var offset = albumPosition(tester).pixels;
    expect(offset, greaterThan(0));
    await openSearch(tester);
    await chooseAnne(tester);
    await tester.drag(resultsScrollable, const Offset(0, -3000));
    await settle(tester);

    await leave(tester);

    expectZoo(tester, delegate);
    expect(albumPosition(tester).pixels, offset,
        reason: "The search scrolls a page of its own.");
  });

  testWidgets('a photograph of the results opened and closed',
      variant: platforms, (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zoo);
    await openSearch(tester);
    await chooseAnne(tester);
    await tester.tap(find.byKey(const ValueKey("2020 Trip/x0.jpg")));
    await settle(tester);
    expect(delegate.route, isA<ImageRoute>());
    await tester.binding.handlePopRoute();
    await settle(tester);

    await leave(tester);

    expectZoo(tester, delegate);
  });

  testWidgets('a search opened by its address is left to its album',
      variant: platforms, (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zooSearch);
    expect(delegate.route, const SearchRoute(["2024", "Zoo"]));
    await tester.drag(resultsScrollable, const Offset(0, -3000));
    await settle(tester);

    await leave(tester);

    expectZoo(tester, delegate);
  });

  testWidgets('left while the search is still being opened', variant: platforms,
      (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zoo);
    await tapOpenSearch(tester);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    delegate.goUp();
    await settle(tester);

    expectZoo(tester, delegate);
  });

  testWidgets('the browser returns to the search just left', variant: platforms,
      (tester) async {
    phone(tester);
    var delegate = await pumpRoute(tester, LongSpace(), zoo);
    await openSearch(tester);
    await chooseAnne(tester);
    await leave(tester);
    expectZoo(tester, delegate);

    await browserTo(tester, zooSearch);
    expect(delegate.route, const SearchRoute(["2024", "Zoo"]));
    expect(find.byKey(const Key("search-up")).hitTestable(), findsOneWidget);
    await leave(tester);

    expectZoo(tester, delegate);
  });

  group('after the search was widened to the whole library', () {
    testWidgets('it is left to the album it was opened from',
        variant: platforms, (tester) async {
      phone(tester);
      var delegate = await pumpRoute(tester, LongSpace(), zoo);
      await tester.drag(find.byType(Scrollable).first, const Offset(0, -1500));
      await settle(tester);
      var offset = albumPosition(tester).pixels;
      await openSearch(tester);
      await chooseAnne(tester);
      await widen(tester);
      expect(delegate.route, const SearchRoute([]));

      await leave(tester);

      expectZoo(tester, delegate);
      expect(albumPosition(tester).pixels, offset,
          reason: "The album stayed beneath the search as it was left.");
    });

    testWidgets('a photograph opened and closed leads back the same way',
        variant: platforms, (tester) async {
      phone(tester);
      var delegate = await pumpRoute(tester, LongSpace(), zoo);
      await openSearch(tester);
      await chooseAnne(tester);
      await widen(tester);
      await tester.tap(find.byKey(const ValueKey("2020 Trip/x0.jpg")));
      await settle(tester);
      expect(delegate.route,
          const ImageRoute([], "2020 Trip/x0.jpg", fromSearch: true));
      await tester.binding.handlePopRoute();
      await settle(tester);
      expect(delegate.route, const SearchRoute([]));

      await leave(tester);

      expectZoo(tester, delegate);
    });

    testWidgets('the browser\'s back button returns to the album\'s search',
        variant: platforms, (tester) async {
      phone(tester);
      var delegate = await pumpRoute(tester, LongSpace(), zoo);
      await openSearch(tester);
      await chooseAnne(tester);
      await widen(tester);

      await browserTo(tester, zooSearch);
      expect(delegate.route, const SearchRoute(["2024", "Zoo"]));
      await leave(tester);

      expectZoo(tester, delegate);
    });

    testWidgets(
        'the search of the library entered anew sits on the start '
        'page',
        variant: platforms, (tester) async {
      phone(tester);
      var delegate = await pumpRoute(tester, LongSpace(), zoo);
      await openSearch(tester);
      await chooseAnne(tester);
      await widen(tester);
      await leave(tester);
      expectZoo(tester, delegate);

      await browserTo(tester, ListingOrAlbumRoute.root);
      await openSearch(tester);
      expect(delegate.route, const SearchRoute([]));
      await leave(tester);

      expect(delegate.route, ListingOrAlbumRoute.root);
      expect(
          find.byKey(const Key("open-search")).hitTestable(), findsOneWidget);
    });
  });

  testWidgets('the search of the start page is left to the start page',
      variant: platforms, (tester) async {
    phone(tester);
    var delegate =
        await pumpRoute(tester, LongSpace(), ListingOrAlbumRoute.root);
    await openSearch(tester);
    await chooseAnne(tester);
    await tester.drag(resultsScrollable, const Offset(0, -3000));
    await settle(tester);

    await leave(tester);

    expect(delegate.route, ListingOrAlbumRoute.root);
    expect(find.byKey(const Key("open-search")).hitTestable(), findsOneWidget);
  });

  test('the levels of a widened search stand on its origin', () {
    const origin = ["2024", "Zoo"];
    expect(
      VAlbumRouterDelegate.levelsOf(
        const SearchRoute([]),
        searchOrigin: origin,
      ),
      [const ListingOrAlbumRoute(origin), const SearchRoute([])],
    );
    expect(
      VAlbumRouterDelegate.levelsOf(
        const ImageRoute([], "x.jpg", fromSearch: true),
        searchOrigin: origin,
      ),
      [
        const ListingOrAlbumRoute(origin),
        const SearchRoute([]),
        const ImageRoute([], "x.jpg", fromSearch: true),
      ],
    );
  });
}
