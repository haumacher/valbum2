/// The select mode of the unsaved search view, issue #243 (as #209 in an
/// album), and the filter of the search's choosers.
///
/// A long press on a result, or "Select" of a tile's menu at the pointer,
/// enters the mode; its bar replaces the view's bar and criteria, downloads
/// exactly the selection (one archive of the folder searched, issue #243) and
/// adds exactly the selection to a collection. An editor gets it as well: the
/// search view has no edit mode. A share link is never in the search view.
///
/// The chooser of persons, places and labels filters a long list as it is
/// typed into, keeping what is ticked in view.
library;

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'download_test.dart'
    show RecordingSaver, installSaver, originalBytes, postedNames, zipBytes;
import 'search_view_test.dart'
    show Space, chooseAnne, json, options, pathOf, pumpRoute, settle;
import 'share_album_chrome_test.dart' as chrome;
import 'util/l10n.dart';

/// A part of a search result named [name], below the folder searched.
String found(String name, int index) =>
    '["ImagePart",{"kind":"IMAGE","name":"$name",'
    '"date":${1015113600000 + index * 10000},'
    '"width":2048,"height":1536,"orientation":"IDENTITY",'
    '"ref":{"hash":"","path":"$name"}}]';

/// Three photographs of two albums, found by a caller holding [rights].
String foundThree(List<String> rights) => '["AlbumInfo",{"kind":"SEARCH",'
    '"parts":[${found("2020 Trip/x.jpg", 0)},${found("2020 Trip/y.jpg", 1)},'
    '${found("2021 Zoo/z.jpg", 2)}],'
    '"rights":[${rights.map((r) => '{"name":"$r"}').join(",")}]}]';

/// The space of `search_view_test.dart`, answering archives and originals of
/// the albums the photographs lie in, and the options it is given.
class SelectSpace extends Space {
  String optionsAnswer = options;

  SelectSpace({List<String> rights = const ["view", "download"]}) {
    searchAnswer = foundThree(rights);
  }

  @override
  http.Response answer(http.Request request) {
    var parameters = request.url.queryParameters;
    if (parameters["type"] == "search-options") {
      return json(optionsAnswer);
    }
    if (request.method == "POST" && parameters["action"] == "zip") {
      return http.Response.bytes(zipBytes, 200,
          headers: {"content-type": "application/zip"});
    }
    var path = pathOf(request);
    if (request.method == "GET" &&
        request.url.query.isEmpty &&
        path.endsWith(".jpg")) {
      return http.Response.bytes(originalBytes, 200,
          headers: {"content-type": "image/jpeg"});
    }
    return super.answer(request);
  }

  /// The originals fetched one by one, by their path.
  List<String> get originals => [
        for (var request in requests)
          if (request.method == "GET" &&
              request.url.query.isEmpty &&
              pathOf(request).endsWith(".jpg"))
            pathOf(request),
      ];
}

Finder tile(String name) => find.byKey(ValueKey(name));

Finder markOf(String name) => find.descendant(
    of: tile(name), matching: find.byKey(const Key("select-mark")));

bool marked(String name) => find
    .descendant(
        of: tile(name), matching: find.byKey(const Key("select-mark-on")))
    .evaluate()
    .isNotEmpty;

String countText(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("select-mode-count"))).data!;

/// A screen tall enough for every result: a new search shows the album
/// headings (issue #289), which push the last photograph out of a small one.
void tall(WidgetTester tester) {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// Opens the search view, finds the three photographs and long-presses [first].
Future<SelectSpace> pumpSelected(WidgetTester tester, String first,
    {List<String> rights = const ["view", "download"]}) async {
  tall(tester);
  var space = SelectSpace(rights: rights);
  await pumpRoute(tester, space, const SearchRoute([]));
  await chooseAnne(tester);
  await tester.longPress(tile(first));
  await settle(tester);
  return space;
}

/// Taps the mark of the tile [name].
Future<void> tapMark(WidgetTester tester, String name) async {
  await tester.tap(markOf(name));
  await settle(tester);
}

/// Clicks the tile [name] with the secondary mouse button.
Future<void> rightClick(WidgetTester tester, String name) async {
  await tester.tap(tile(name),
      buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
  await settle(tester);
}

/// Many persons and places, for the filter of the chooser.
String manyOptions() {
  var persons = [
    for (var name in [
      "Anne", "Bernd", "Clara", "Dieter", "Eva", "Franz", "Gina", "Hans",
      "Ines", "Jonas", //
    ])
      '{"id":"p-$name","name":"$name"}',
  ];
  var places = [
    for (var (n, name) in [
      "Berlin", "Bonn", "Bremen", "Dresden", "Essen", "Freiburg", "Hamburg",
      "Kiel", "Köln", "München", //
    ].indexed)
      '{"geonameId":${100 + n},"name":"$name","kind":"PPL","country":"DE"}',
  ];
  return '{"persons":[${persons.join(",")}],"places":[${places.join(",")}],'
      '"labels":[{"name":"Holiday"}],"cameras":[]}';
}

/// The choices the open chooser lists, by their keys.
List<String> listedChoices(WidgetTester tester) => [
      for (var widget
          in tester.widgetList<CheckboxListTile>(find.byType(CheckboxListTile)))
        (widget.key as ValueKey<String>).value,
    ];

void main() {
  var l10n = l10nOf();

  group('the select mode of the search view', () {
    testWidgets('a long press enters it, a mark adds, × leaves',
        (tester) async {
      await pumpSelected(tester, "2020 Trip/x.jpg");

      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(countText(tester), l10n.selectedCount(1));
      expect(marked("2020 Trip/x.jpg"), isTrue);
      expect(find.byKey(const Key("search-criteria")), findsNothing,
          reason: "The mode's bar replaces the view's bar and criteria.");
      expect(find.byKey(const Key("search-up")), findsNothing);

      await tapMark(tester, "2021 Zoo/z.jpg");
      expect(countText(tester), l10n.selectedCount(2));
      expect(marked("2021 Zoo/z.jpg"), isTrue);
      expect(marked("2020 Trip/y.jpg"), isFalse);

      await tester.tap(find.byKey(const Key("select-mode-leave")));
      await settle(tester);
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
      expect(find.byKey(const Key("search-criteria")), findsOneWidget);
      expect(find.byKey(const Key("search-person-p1")), findsOneWidget);
      expect(tile("2020 Trip/y.jpg"), findsOneWidget,
          reason: "The results are unchanged.");
    });

    testWidgets('Escape leaves it', (tester) async {
      await pumpSelected(tester, "2020 Trip/x.jpg");
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
      expect(find.byKey(const Key("search-criteria")), findsOneWidget);
    });

    testWidgets('a tap outside the mode opens the viewer', (tester) async {
      var space = SelectSpace();
      var delegate = await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);
      await tester.tap(tile("2020 Trip/y.jpg"));
      await settle(tester);
      expect(delegate.route, isA<ImageRoute>());
    });

    testWidgets(
        'the actions are disabled, not taken away, with nothing '
        'selected', (tester) async {
      await pumpSelected(tester, "2020 Trip/x.jpg");
      await tapMark(tester, "2020 Trip/x.jpg");
      expect(countText(tester), l10n.selectedCount(0));
      expect(
          tester
              .widget<TextButton>(find.byKey(const Key("select-mode-download")))
              .onPressed,
          isNull);
      expect(
          tester
              .widget<IconButton>(find.byKey(const Key("select-mode-collect")))
              .onPressed,
          isNull);
    });

    testWidgets(
        '"Download n originals" takes exactly the selection of one '
        'album as one archive of its paths', (tester) async {
      var saver = installSaver();
      var space = await pumpSelected(tester, "2020 Trip/x.jpg");
      await tapMark(tester, "2020 Trip/y.jpg");

      expect(find.text(l10n.downloadSelection(2)), findsOneWidget);
      await tester.tap(find.byKey(const Key("select-mode-download")));
      await settle(tester);

      var zip = space.posts("zip").single;
      expect(pathOf(zip), "/valbum/data/",
          reason: "The archive is asked of the folder searched (#243).");
      expect(postedNames(zip), ["2020 Trip/x.jpg", "2020 Trip/y.jpg"]);
      expect(space.originals, isEmpty);
      expect(saver.saved.single.name, "Anne.zip",
          reason: "The archive is named after the search.");
    });

    testWidgets('a selection of several albums is one archive', (tester) async {
      var saver = installSaver();
      var space = await pumpSelected(tester, "2020 Trip/x.jpg");
      await tapMark(tester, "2021 Zoo/z.jpg");

      await tester.tap(find.byKey(const Key("select-mode-download")));
      await settle(tester);

      var zip = space.posts("zip").single;
      expect(pathOf(zip), "/valbum/data/");
      expect(postedNames(zip), ["2020 Trip/x.jpg", "2021 Zoo/z.jpg"],
          reason: "Exactly the selection, by its paths across both albums.");
      expect(space.originals, isEmpty);
      expect(saver.saved.single.name, "Anne.zip");
    });

    testWidgets('one photograph selected is its own original', (tester) async {
      var saver = installSaver();
      var space = await pumpSelected(tester, "2021 Zoo/z.jpg");

      await tester.tap(find.byKey(const Key("select-mode-download")));
      await settle(tester);

      expect(space.posts("zip"), isEmpty);
      expect(space.originals, ["/valbum/data/2021 Zoo/z.jpg"]);
      expect([for (var file in saver.saved) file.name], ["z.jpg"]);
    });

    testWidgets('a phone takes the selection original by original',
        (tester) async {
      var saver = installSaver(RecordingSaver(keepsArchives: false));
      var space = await pumpSelected(tester, "2020 Trip/x.jpg");
      await tapMark(tester, "2020 Trip/y.jpg");

      await tester.tap(find.byKey(const Key("select-mode-download")));
      await settle(tester);

      expect(space.posts("zip"), isEmpty);
      expect(space.originals,
          ["/valbum/data/2020 Trip/x.jpg", "/valbum/data/2020 Trip/y.jpg"]);
      expect(saver.saved, hasLength(2));
    });

    testWidgets('"Add to collection…" collects exactly the selection',
        (tester) async {
      var space = await pumpSelected(tester, "2020 Trip/x.jpg");
      await tapMark(tester, "2021 Zoo/z.jpg");

      await tester.tap(find.byKey(const Key("select-mode-collect")));
      await settle(tester);
      expect(find.byKey(const Key("collection-picker")), findsOneWidget);
      await tapPickerRow(tester, "Best");
      await settle(tester);
      await tester.tap(find.byKey(const Key("picker-confirm")));
      await settle(tester);

      var collect = space.posts("collect").single;
      expect(pathOf(collect), "/valbum/data/");
      var body = jsonDecode(collect.body) as Map<String, dynamic>;
      expect(body["target"], "Best");
      expect(postedNames(collect), ["2020 Trip/x.jpg", "2021 Zoo/z.jpg"]);
    });

    testWidgets('an editor gets it as well: the view has no edit mode',
        (tester) async {
      await pumpSelected(tester, "2020 Trip/x.jpg",
          rights: const ["view", "download", "contribute", "edit"]);
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(countText(tester), l10n.selectedCount(1));
    });

    testWidgets(
        'without download the mode gathers for a collection alone '
        '(issue #290)', (tester) async {
      await pumpSelected(tester, "2020 Trip/x.jpg", rights: const ["view"]);
      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(find.byKey(const Key("select-mode-collect")), findsOneWidget);
      expect(find.byKey(const Key("select-mode-download")), findsNothing);
      expect(find.byKey(const Key("edit-cancel")), findsNothing,
          reason: "Not the edit mode either: the view has none.");
    });

    testWidgets('a share link is not offered the search view at all',
        (tester) async {
      await chrome.pumpSession(tester, chrome.folderLink());
      expect(find.byKey(const Key("open-search")), findsNothing);
    });
  });

  group('the menu of a result tile at the pointer', () {
    testWidgets('"Select" enters the mode with that tile', (tester) async {
      var space = SelectSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);

      await rightClick(tester, "2020 Trip/y.jpg");
      expect(find.byKey(const Key("tile-context-select")), findsOneWidget);
      expect(find.byKey(const Key("tile-context-collect")), findsOneWidget);
      await tester.tap(find.byKey(const Key("tile-context-select")));
      await settle(tester);

      expect(find.byKey(const Key("select-mode-bar")), findsOneWidget);
      expect(countText(tester), l10n.selectedCount(1));
      expect(marked("2020 Trip/y.jpg"), isTrue);
    });

    testWidgets('"Add to collection…" collects that one photograph',
        (tester) async {
      tall(tester);
      var space = SelectSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      await chooseAnne(tester);

      await rightClick(tester, "2021 Zoo/z.jpg");
      await tester.tap(find.byKey(const Key("tile-context-collect")));
      await settle(tester);
      await tapPickerRow(tester, "Best");
      await settle(tester);
      await tester.tap(find.byKey(const Key("picker-confirm")));
      await settle(tester);

      expect(postedNames(space.posts("collect").single), ["2021 Zoo/z.jpg"]);
      expect(find.byKey(const Key("select-mode-bar")), findsNothing);
    });
  });

  group('the filter of the chooser', () {
    Future<SelectSpace> pumpMany(WidgetTester tester) async {
      // Tall enough for the whole list: the list builds what it shows.
      tester.view.physicalSize = const Size(1200, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var space = SelectSpace()..optionsAnswer = manyOptions();
      await pumpRoute(tester, space, const SearchRoute([]));
      return space;
    }

    Future<void> filter(WidgetTester tester, String text) async {
      await tester.enterText(
          find.byKey(const Key("search-choose-filter")), text);
      await settle(tester);
    }

    testWidgets('typing narrows the persons, ignoring case', (tester) async {
      await pumpMany(tester);
      await tester.tap(find.byKey(const Key("search-add-persons")));
      await settle(tester);
      expect(listedChoices(tester), hasLength(10));

      await filter(tester, "AN");
      expect(listedChoices(tester), [
        "search-choice-p-Anne",
        "search-choice-p-Franz",
        "search-choice-p-Hans",
      ]);

      await filter(tester, "");
      expect(listedChoices(tester), hasLength(10));
    });

    testWidgets('typing narrows the places, and Escape shows all again',
        (tester) async {
      await pumpMany(tester);
      await tester.tap(find.byKey(const Key("search-add-places")));
      await settle(tester);

      await filter(tester, "b");
      expect(listedChoices(tester), [
        "search-choice-100",
        "search-choice-101",
        "search-choice-102",
        "search-choice-105",
        "search-choice-106",
      ]);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.byKey(const Key("search-choose-dialog")), findsOneWidget,
          reason: "Escape in a filled filter clears it, the dialog stays.");
      expect(listedChoices(tester), hasLength(10));
    });

    testWidgets(
        'a ticked person survives a filter it does not match, and the '
        'answer carries ticks made under different filters', (tester) async {
      var space = await pumpMany(tester);
      await tester.tap(find.byKey(const Key("search-add-persons")));
      await settle(tester);

      await filter(tester, "ann");
      await tester.tap(find.byKey(const Key("search-choice-p-Anne")));
      await settle(tester);
      await filter(tester, "jon");
      expect(listedChoices(tester),
          ["search-choice-p-Anne", "search-choice-p-Jonas"],
          reason: "What is ticked stays in view.");
      await tester.tap(find.byKey(const Key("search-choice-p-Jonas")));
      await settle(tester);
      await filter(tester, "zzz");
      expect(listedChoices(tester),
          ["search-choice-p-Anne", "search-choice-p-Jonas"]);

      await tester.tap(find.byKey(const Key("search-choose-done")));
      await settle(tester);
      expect(find.byKey(const Key("search-person-p-Anne")), findsOneWidget);
      expect(find.byKey(const Key("search-person-p-Jonas")), findsOneWidget);
      var query = space.posts("search").last.body;
      expect(query, contains('"person":"p-Anne"'));
      expect(query, contains('"person":"p-Jonas"'));
    });

    testWidgets('a ticked place survives a filter it does not match',
        (tester) async {
      await pumpMany(tester);
      await tester.tap(find.byKey(const Key("search-add-places")));
      await settle(tester);
      await tester.tap(find.byKey(const Key("search-choice-108")));
      await settle(tester);
      await filter(tester, "ham");
      expect(listedChoices(tester), ["search-choice-106", "search-choice-108"]);
    });

    testWidgets('a short list shows no field', (tester) async {
      var space = SelectSpace();
      await pumpRoute(tester, space, const SearchRoute([]));
      for (var chooser in ["search-add-persons", "search-add-places"]) {
        await tester.tap(find.byKey(Key(chooser)));
        await settle(tester);
        expect(find.byKey(const Key("search-choose-dialog")), findsOneWidget);
        expect(find.byKey(const Key("search-choose-filter")), findsNothing);
        await tester.tap(find.text(l10n.cancel));
        await settle(tester);
      }
    });
  });
}

/// Taps the picker's row of [name] near its top edge: the picker lists the
/// undated by name (issue #270), and Best, the third, stands at the foot of
/// the dialog at the test size, its lower half under the dialog's buttons.
Future<void> tapPickerRow(WidgetTester tester, String name) =>
    tester.tapAt(tester.getTopLeft(find.byKey(Key("picker-folder-$name"))) +
        const Offset(24, 8));
