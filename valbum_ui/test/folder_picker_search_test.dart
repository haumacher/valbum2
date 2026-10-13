/// The search field of the folder picker, issue #261: what is typed is looked
/// for in the whole space by the server (`?type=folders&q=`), the matches
/// stand as a flat list with the folder each lies in, picking one shows that
/// folder in the picker — whose confirm keeps every rule it has — and an
/// empty field is the tree again.
///
/// The picker is the upload target's here (issue #240), the one that refuses
/// an album the member may not add photos to.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/upload_target_view.dart';

import 'upload_target_view_test.dart'
    show json, pumpSection, serverDataUrl, tapKey, tree;
import 'util/l10n.dart';

/// A match of the answer: [path] below the root, its tile.
String match(String path, String title, {String kind = "ALBUM"}) =>
    '{"path": "$path", "folder": {"name": "${path.split("/").last}", '
    '"title": "$title", "kind": "$kind"}}';

/// What the server finds for a text: Rome lies in 2026, a trip at the top.
http.Response found(String text) {
  switch (text) {
    case "rom":
      return json('{"folders": ['
          '${match("2026/Rome", "Rome")}, '
          '${match("Rome trip", "Rome trip", kind: "FOLDER")}'
          ']}');
    case "mallorca":
      return json('{"folders": ['
          '${match("2026/Mallorca 2026", "Mallorca 2026")}]}');
    case "many":
      return json('{"folders": ['
          '${match("2026/Rome", "Rome")}], "more": true}');
  }
  return json('{}');
}

/// The tree of the upload target's tests, and the folder search beside it.
class SearchSpace {
  final List<http.Request> searches = [];

  /// Where set, the search waits for it before it answers.
  Completer<void>? slow;

  /// Where set, the search is refused with this sentence.
  String? refusal;

  late final VAlbumClient client = VAlbumClient(
    dataUrl: serverDataUrl,
    httpClient: MockClient((request) async {
      if (request.url.queryParameters["type"] == "folders") {
        searches.add(request);
        await slow?.future;
        var refused = refusal;
        if (refused != null) {
          return http.Response('["ErrorInfo", {"message": "$refused"}]', 403,
              headers: {"content-type": "application/json; charset=utf-8"});
        }
        return found(request.url.queryParameters["q"] ?? "");
      }
      return tree(request);
    }),
  );
}

Finder get field => find.byKey(const Key("picker-search"));

/// Opens the upload target's picker and puts the cursor in its field.
Future<SearchSpace> openPicker(WidgetTester tester) async {
  var space = SearchSpace();
  await pumpSection(tester, client: space.client);
  await tapKey(tester, uploadTargetChooseKey);
  expect(field, findsOneWidget, reason: "the upload target's picker has it");
  await tester.tap(field);
  await tester.pumpAndSettle();
  return space;
}

/// Types [text] and lets the typing rest until the server answered.
Future<void> search(WidgetTester tester, String text) async {
  await tester.enterText(field, text);
  await tester.pump(FolderPickerState.searchDelay);
  await tester.pumpAndSettle();
}

String pathLine(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("picker-path"))).data!;

bool confirmEnabled(WidgetTester tester) =>
    tester
        .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
        .onPressed !=
    null;

bool highlighted(WidgetTester tester, String path) =>
    tester.widget<ListTile>(find.byKey(Key("picker-match-$path"))).selected;

void main() {
  testWidgets('asks the server once the typing rests, for the whole space',
      (tester) async {
    var space = await openPicker(tester);
    // Somewhere down the tree: the search looks everywhere all the same.
    await tapKey(tester, const Key("picker-folder-2026"));

    await tester.enterText(field, "ro");
    await tester.pump(const Duration(milliseconds: 100));
    await tester.enterText(field, "rom");
    await tester.pump(const Duration(milliseconds: 299));
    expect(space.searches, isEmpty, reason: "the typing has not rested yet");
    await tester.pump(const Duration(milliseconds: 1));
    await tester.pumpAndSettle();

    expect(space.searches, hasLength(1), reason: "one search, for 'rom'");
    var asked = space.searches.single.url;
    expect(Uri.decodeFull(asked.path), "/valbum/data/");
    expect(asked.queryParameters["q"], "rom");

    // Each match by the line the tree names it with, and where it lies.
    expect(find.text("Rome"), findsOneWidget);
    expect(
        tester
            .widget<Text>(find.byKey(const Key("picker-match-in-2026/Rome")))
            .data,
        testL10n.folderSearchIn("2026"));
    expect(
        tester
            .widget<Text>(find.byKey(const Key("picker-match-in-Rome trip")))
            .data,
        testL10n.folderSearchTopLevel);
    expect(find.byKey(const Key("picker-folder-Rome")), findsNothing,
        reason: "the tree gives way to the matches");
  });

  testWidgets('a picked match is shown, and the confirm keeps its rules',
      (tester) async {
    await openPicker(tester);
    await search(tester, "rom");

    await tapKey(tester, const Key("picker-match-2026/Rome"));
    expect(pathLine(tester), "2026 / Rome");
    expect(tester.widget<TextField>(field).controller!.text, isEmpty,
        reason: "the tree again, at the folder picked");
    // The member may only look at Rome: no upload target, and it says why.
    expect(find.byKey(const Key("picker-no-contribute")), findsOneWidget);
    expect(confirmEnabled(tester), isFalse);

    await search(tester, "mallorca");
    await tapKey(tester, const Key("picker-match-2026/Mallorca 2026"));
    expect(pathLine(tester), "2026 / Mallorca 2026");
    expect(find.byKey(const Key("picker-no-contribute")), findsNothing);
    expect(confirmEnabled(tester), isTrue);
    // The way up is the tree's, as after browsing there.
    await tapKey(tester, const Key("picker-up"));
    expect(pathLine(tester), "2026");
  });

  testWidgets('the arrows walk the matches and Enter shows the one picked',
      (tester) async {
    await openPicker(tester);
    await search(tester, "rom");
    expect(highlighted(tester, "2026/Rome"), isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pumpAndSettle();
    expect(highlighted(tester, "Rome trip"), isTrue);

    // Another text, other matches: the highlight stands on none of them.
    await search(tester, "mallorca");
    expect(highlighted(tester, "2026/Mallorca 2026"), isFalse);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(pathLine(tester), "2026 / Mallorca 2026");
  });

  testWidgets('the arrows walk the tree where nothing is typed',
      (tester) async {
    await openPicker(tester);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(pathLine(tester), "2026");
  });

  testWidgets('an emptied field, or Escape, is the tree again', (tester) async {
    await openPicker(tester);
    await search(tester, "rom");
    expect(find.byKey(const Key("picker-folder-2026")), findsNothing);

    await search(tester, "");
    expect(find.byKey(const Key("picker-folder-2026")), findsOneWidget);
    expect(find.byKey(const Key("picker-match-2026/Rome")), findsNothing);

    await search(tester, "rom");
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("picker-folder-2026")), findsOneWidget);
    expect(find.byKey(const Key("folder-picker")), findsOneWidget,
        reason: "the first Escape only clears the field");
  });

  testWidgets('says that it is searching as long as the server takes',
      (tester) async {
    var space = await openPicker(tester);
    space.slow = Completer<void>();
    await tester.enterText(field, "rom");
    await tester.pump(FolderPickerState.searchDelay);
    await tester.pump(const Duration(seconds: 5));
    expect(find.text(testL10n.folderSearchSearching), findsOneWidget);

    space.slow!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("picker-searching")), findsNothing);
    expect(find.byKey(const Key("picker-match-2026/Rome")), findsOneWidget);
  });

  testWidgets('a refused search says why, in the server\'s words',
      (tester) async {
    var space = await openPicker(tester);
    space.refusal = "Searching is for the members of this space.";
    await search(tester, "rom");
    expect(
        find.text(testL10n
            .folderSearchFailed("Searching is for the members of this space.")),
        findsOneWidget);
    expect(find.byKey(const Key("picker-searching")), findsNothing);
  });

  testWidgets('says when nothing matches, and when there is more',
      (tester) async {
    await openPicker(tester);
    await search(tester, "nowhere");
    expect(find.text(testL10n.folderSearchNothing("nowhere")), findsOneWidget);

    await search(tester, "many");
    expect(find.byKey(const Key("picker-search-nothing")), findsNothing);
    expect(find.text(testL10n.folderSearchMore(1)), findsOneWidget);
  });

  test('a match is placed by the folders above it', () {
    expect(matchLocation(testL10n, "Rome trip"), testL10n.folderSearchTopLevel);
    expect(matchLocation(testL10n, "2026/Italy/Rome"),
        testL10n.folderSearchIn("2026 / Italy"));
  });
}
