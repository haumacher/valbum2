/// The choosers of the search view while the options of the folder are on
/// their way, after they failed, and where there is genuinely nothing to
/// choose: never an empty list as if there were nothing (the persons chooser
/// at the top level stood empty while the server read the library).
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'search_view_test.dart'
    show Space, dataUrl, json, options, pathOf, settle;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The space of the search view test, its options answered by [optionsAnswer].
class SlowSpace extends Space {
  /// How often the options were asked.
  int optionsAsked = 0;

  /// The answer to the next request of the options.
  Future<http.Response> Function() optionsAnswer = () async => json(options);

  @override
  VAlbumClient get client => VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          if (request.url.queryParameters["type"] == "search-options" &&
              pathOf(request) == "/valbum/data/") {
            optionsAsked++;
            return optionsAnswer();
          }
          return answer(request);
        }),
      );
}

Future<void> pumpSearch(WidgetTester tester, Space space) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: space.client,
      initialRoute: const SearchRoute([]),
    ));
    // Not settled: a progress indicator spins while the options are awaited.
    for (var n = 0; n < 5; n++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  });
}

Future<void> pumpFew(WidgetTester tester) => withFakeImageHttp(() async {
      for (var n = 0; n < 5; n++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
    });

void main() {
  var l10n = l10nOf();

  testWidgets('a chooser opened before the options arrived waits for them',
      (tester) async {
    var space = SlowSpace();
    var arrival = Completer<http.Response>();
    space.optionsAnswer = () => arrival.future;
    await pumpSearch(tester, space);

    await tester.tap(find.byKey(const Key("search-add-persons")));
    await pumpFew(tester);

    expect(find.byKey(const Key("search-options-loading")), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byKey(const Key("search-nothing-to-choose")), findsNothing,
        reason: "Nothing is said to be missing before the options arrived.");
    expect(find.text(l10n.searchNothingToChoose), findsNothing);

    arrival.complete(json(options));
    await settle(tester);

    expect(find.byKey(const Key("search-options-loading")), findsNothing);
    expect(find.byKey(const Key("search-choose-dialog")), findsOneWidget,
        reason: "The click is not lost: the chooser fills.");
    expect(find.byKey(const Key("search-choice-p1")), findsOneWidget);
    expect(find.text("Philipp"), findsOneWidget);

    await tester.tap(find.byKey(const Key("search-choice-p1")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-choose-done")));
    await settle(tester);
    expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
    expect(find.text("Anne"), findsOneWidget);
    expect(space.optionsAsked, 1);
  });

  for (var (status, message) in [
    (403, "Searching is for the members of this space."),
    (500, "The index cannot be read."),
  ]) {
    testWidgets(
        'a refused options answer ($status) says why, and a retry '
        'asks again', (tester) async {
      var space = SlowSpace();
      space.optionsAnswer = () async => http.Response(
              '["ErrorInfo",{"message":"$message"}]', status,
              headers: {
                "content-type": "application/json; charset=utf-8",
              });
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: space.client,
          initialRoute: const SearchRoute([]),
        ));
      });
      await settle(tester);

      await tester.tap(find.byKey(const Key("search-add-persons")));
      await settle(tester);

      expect(find.byKey(const Key("search-options-failed")), findsOneWidget);
      expect(find.textContaining(message), findsOneWidget,
          reason: "The server's own words.");
      expect(find.byKey(const Key("search-choose-dialog")), findsNothing);
      expect(space.optionsAsked, 1);

      space.optionsAnswer = () async => json(options);
      await tester.tap(find.byKey(const Key("search-options-retry")));
      await settle(tester);

      expect(space.optionsAsked, 2, reason: "The retry asks again.");
      expect(find.byKey(const Key("search-options-failed")), findsNothing);
      expect(find.byKey(const Key("search-choice-p1")), findsOneWidget);
      expect(find.byKey(const Key("search-choice-p2")), findsOneWidget);
    });
  }

  testWidgets('a retry waits again before it shows the persons',
      (tester) async {
    var space = SlowSpace();
    space.optionsAnswer = () async =>
        http.Response('["ErrorInfo",{"message":"Busy."}]', 503, headers: {
          "content-type": "application/json; charset=utf-8",
        });
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: space.client,
        initialRoute: const SearchRoute([]),
      ));
    });
    await settle(tester);
    await tester.tap(find.byKey(const Key("search-add-places")));
    await settle(tester);
    expect(find.byKey(const Key("search-options-failed")), findsOneWidget);

    var arrival = Completer<http.Response>();
    space.optionsAnswer = () => arrival.future;
    await tester.tap(find.byKey(const Key("search-options-retry")));
    await pumpFew(tester);
    expect(find.byKey(const Key("search-options-loading")), findsOneWidget,
        reason: "Not the old failure while the retry is on its way.");

    arrival.complete(json(options));
    await settle(tester);
    expect(find.text("Baden-Württemberg (DE)"), findsOneWidget);
  });

  testWidgets('a failed load is asked again when the search is opened again',
      (tester) async {
    var space = SlowSpace();
    space.optionsAnswer = () async => http.Response("down", 500);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: space.client,
        initialRoute: const SearchRoute([]),
      ));
    });
    await settle(tester);
    expect(space.optionsAsked, 1);

    space.optionsAnswer = () async => json(options);
    await tester.tap(find.byKey(const Key("search-up")));
    await settle(tester);
    await tester.tap(find.byKey(const Key("open-search")));
    await settle(tester);

    expect(space.optionsAsked, 2);
    await tester.tap(find.byKey(const Key("search-add-persons")));
    await settle(tester);
    expect(find.byKey(const Key("search-choice-p1")), findsOneWidget);
  });

  testWidgets('options that are genuinely empty say so', (tester) async {
    var space = SlowSpace();
    space.optionsAnswer =
        () async => json('{"persons":[],"places":[],"labels":[],"cameras":[]}');
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: space.client,
        initialRoute: const SearchRoute([]),
      ));
    });
    await settle(tester);

    await tester.tap(find.byKey(const Key("search-add-persons")));
    await settle(tester);
    expect(find.byKey(const Key("search-choose-dialog")), findsOneWidget);
    expect(find.text(l10n.searchNothingToChoose), findsOneWidget);
    await tester.tap(find.text(l10n.cancel));
    await settle(tester);

    await tester.tap(find.byKey(const Key("search-camera")));
    await settle(tester);
    expect(find.byKey(const Key("search-camera-dialog")), findsOneWidget);
    expect(find.text(l10n.searchNothingToChoose), findsOneWidget);
  });
}
