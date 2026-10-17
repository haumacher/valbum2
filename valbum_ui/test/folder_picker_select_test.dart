/// The folder picker selects an album by a tap and opens a folder by one: an
/// album holds no folders, so descending into it was never of use, and the
/// album the confirm acts on is the one tapped. What the picker's purpose
/// cannot take says why on its row. "Create new album…" stands in the
/// dialog's title, at every level it is allowed on, never scrolled away by a
/// long list (issue #279 opens the picker on an album far down one).
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/form_dialog.dart';
import 'package:valbum_ui/main.dart';

import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(body, 200,
    headers: {"content-type": "application/json; charset=utf-8"});

String rightsOf(List<String> rights) =>
    '[${rights.map((r) => '{"name": "$r"}').join(", ")}]';

/// Noon of a day of 2002, the [n]th week.
int week(int n) => DateTime(2002, 1, 6 + 7 * n, 12).millisecondsSinceEpoch;

String albumName(int n) => "2002-W${n.toString().padLeft(2, "0")} Week";

/// The library: a year folder of 60 weekly albums, a folder of folders
/// `Trips` holding `Rome` (no contribute) and `Paris` (contribute), and a
/// collection.
http.Response library(http.Request request) {
  if (request.url.queryParameters.containsKey("q")) {
    // A match the server names by its path alone.
    return json('{"folders": [{"path": "Trips/Paris"}]}');
  }
  if (request.url.queryParameters["type"] == "folders") {
    // The suggestion of issue #279: far down the year.
    return json('{"folders": [{"path": "2002/${albumName(50)}", "folder": '
        '{"name": "${albumName(50)}", "title": "Week"}}]}');
  }
  switch (Uri.decodeFull(request.url.path)) {
    case "/valbum/data/":
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"rights": ${rightsOf(["edit"])}, "folders": ['
          '{"name": "2002", "title": "2002", "kind": "FOLDER"}, '
          '{"name": "Trips", "title": "Trips", "kind": "FOLDER"}, '
          '{"name": "Best", "title": "Best", "kind": "COLLECTION"}, '
          '{"name": "Loose", "title": "Loose", "kind": "ALBUM"}'
          ']}]');
    case "/valbum/data/2002/":
      return json('["ListingInfo", {"path": "2002", "title": "2002", '
          '"rights": ${rightsOf(["edit"])}, "folders": [${[
        for (var n = 0; n < 60; n++)
          '{"name": "${albumName(n)}", "title": "Week", "kind": "ALBUM", '
              '"effectiveDate": ${week(n)}}',
      ].join(", ")}]}]');
    case "/valbum/data/Trips/":
      return json('["ListingInfo", {"path": "Trips", "title": "Trips", '
          '"rights": ${rightsOf(["contribute"])}, "folders": ['
          '{"name": "Paris", "title": "Paris", "kind": "ALBUM"}, '
          '{"name": "Rome", "title": "Rome", "kind": "ALBUM"}'
          ']}]');
    case "/valbum/data/Trips/Paris/":
      return json('["AlbumInfo", {"path": "Trips/Paris", "title": "Paris", '
          '"rights": ${rightsOf(["contribute"])}, "parts": []}]');
    case "/valbum/data/Trips/Rome/":
      return json('["AlbumInfo", {"path": "Trips/Rome", "title": "Rome", '
          '"rights": ${rightsOf(["view"])}, "parts": []}]');
  }
  return http.Response("No such resource", 404);
}

VAlbumClient client() => VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient((request) async => library(request)),
    );

/// What the picker was left with, once it is.
class Outcome {
  bool closed = false;
  PickedTarget? picked;
}

/// Opens [picker] in a dialog, as the move does.
Future<Outcome> openPicker(
  WidgetTester tester,
  FolderPicker Function(VAlbumClient client) picker,
) async {
  var outcome = Outcome();
  var api = client();
  await tester.pumpWidget(localizedApp(Scaffold(
    body: Builder(
      builder: (context) => TextButton(
        onPressed: () async {
          outcome.picked = await showFormDialog<PickedTarget>(
            context: context,
            builder: (context) => picker(api),
          );
          outcome.closed = true;
        },
        child: const Text("open"),
      ),
    ),
  )));
  await tester.tap(find.text("open"));
  await tester.pumpAndSettle();
  return outcome;
}

/// The picker of a move of images, opening at [at].
FolderPicker imageMove(
  VAlbumClient client, {
  List<String> at = const [],
  DateTime? near,
}) =>
    FolderPicker(
      client: client,
      initialPath: at,
      confirmLabel: (path) => "Move to ${path.join("/")}",
      targetIsAlbum: true,
      mayCreateAlbum: true,
      suggestNear: near,
    );

/// A phone, its keyboard down unless [keyboard].
void phone(WidgetTester tester, {bool keyboard = false}) {
  tester.view.physicalSize = const Size(360, 640);
  tester.view.devicePixelRatio = 1;
  if (keyboard) {
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
  }
  addTearDown(tester.view.reset);
}

/// Whether [finder] is on the screen and a tap at its centre reaches it.
bool tappable(WidgetTester tester, Finder finder) {
  if (finder.evaluate().isEmpty) {
    return false;
  }
  var screen = Offset.zero & tester.view.physicalSize;
  var centre = tester.getCenter(finder);
  if (!screen.contains(centre)) {
    return false;
  }
  return finder.hitTestable().evaluate().isNotEmpty;
}

String pathLine(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("picker-path"))).data!;

ElevatedButton confirm(WidgetTester tester) =>
    tester.widget<ElevatedButton>(find.byKey(const Key("picker-confirm")));

String? refusal(WidgetTester tester, String name) {
  var line = find.byKey(Key("picker-refused-$name"));
  return line.evaluate().isEmpty ? null : tester.widget<Text>(line).data;
}

ListTile row(WidgetTester tester, String name) =>
    tester.widget<ListTile>(find.byKey(Key("picker-folder-$name")));

Future<void> tapRow(WidgetTester tester, String name) async {
  var entry = find.byKey(Key("picker-folder-$name"), skipOffstage: false);
  await tester.ensureVisible(entry);
  await tester.pumpAndSettle();
  await tester.tap(entry);
  await tester.pumpAndSettle();
}

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  group('"Create new album…" is never scrolled away', () {
    for (var keyboard in [false, true]) {
      testWidgets(
          'with a suggestion far down 60 albums on a phone'
          '${keyboard ? ", the keyboard up" : ""}', (tester) async {
        phone(tester, keyboard: keyboard);
        await openPicker(
            tester, (api) => imageMove(api, near: DateTime(2002, 12, 20)));

        expect(find.byKey(const Key("picker-selected")), findsOneWidget,
            reason: "the suggestion is selected");
        var create = find.byKey(const Key("picker-create-album"));
        expect(tappable(tester, create), isTrue,
            reason: "on the screen without scrolling");
      });
    }
  });

  group('moving images', () {
    testWidgets('a tap on an album chooses it, the level stays',
        (tester) async {
      var outcome = await openPicker(tester, (api) => imageMove(api, at: ["2002"]));
      var x = albumName(3);
      expect(confirm(tester).onPressed, isNull);

      await tapRow(tester, x);
      expect(pathLine(tester), "2002 / $x");
      expect(find.byKey(const Key("picker-up")), findsOneWidget,
          reason: "still the year, not inside the album");
      expect(row(tester, x).selected, isTrue);
      expect(
          find.descendant(
              of: find.byKey(Key("picker-folder-$x")),
              matching: find.byKey(const Key("picker-selected"))),
          findsOneWidget);
      expect(confirm(tester).onPressed, isNotNull);

      // A second tap keeps the choice.
      await tapRow(tester, x);
      expect(pathLine(tester), "2002 / $x");
      expect(find.byKey(const Key("picker-selected")), findsOneWidget);

      // Another album takes its place.
      var y = albumName(4);
      await tapRow(tester, y);
      expect(pathLine(tester), "2002 / $y");
      expect(find.byKey(const Key("picker-selected")), findsOneWidget);

      await tapRow(tester, x);
      await tapKey(tester, "picker-confirm");
      expect(outcome.closed, isTrue);
      expect(outcome.picked!.path, ["2002", x]);
      expect(outcome.picked!.newAlbum, isNull);
      expect(outcome.picked!.title, "Week");
    });

    testWidgets('a tap on a folder of folders opens it', (tester) async {
      await openPicker(tester, (api) => imageMove(api));
      expect(pathLine(tester), testL10n.pickerTopLevel);

      await tapRow(tester, "Trips");
      expect(pathLine(tester), "Trips");
      expect(find.byKey(const Key("picker-folder-Paris")), findsOneWidget);
      expect(find.byKey(const Key("picker-up")), findsOneWidget);
    });

    testWidgets('Enter on the highlight does what a tap does', (tester) async {
      await openPicker(tester, (api) => imageMove(api));
      // The keys act while the cursor is in the search field.
      await tester.tap(find.byKey(const Key("picker-search")));
      await tester.pumpAndSettle();
      // The top level, the oldest first: 2002, Best, Loose, Trips; the
      // highlight walks to the album.
      var order = [
        for (var element in find
            .byWidgetPredicate((widget) =>
                widget is ListTile &&
                widget.key is ValueKey<String> &&
                (widget.key as ValueKey<String>)
                    .value
                    .startsWith("picker-folder-"))
            .evaluate())
          ((element.widget.key as ValueKey<String>).value)
              .substring("picker-folder-".length),
      ];
      var steps = order.indexOf("Loose") + 1;
      for (var n = 0; n < steps; n++) {
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
      }
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(pathLine(tester), "Loose");
      expect(find.byKey(const Key("picker-up")), findsNothing);
      expect(confirm(tester).onPressed, isNotNull);

      // Enter on a folder of folders opens it.
      for (var n = 0; n < steps; n++) {
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      }
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(pathLine(tester), order.first);
      expect(find.byKey(const Key("picker-up")), findsOneWidget);
    });

    testWidgets('a collection is refused, and says why', (tester) async {
      await openPicker(tester, (api) => imageMove(api));
      expect(refusal(tester, "Best"), testL10n.collectionTakesNoMove);
      expect(row(tester, "Best").enabled, isFalse);
      await tapRow(tester, "Best");
      expect(find.byKey(const Key("picker-selected")), findsNothing);
      expect(confirm(tester).onPressed, isNull);
      expect(refusal(tester, "Loose"), isNull);
    });

    testWidgets('a match without its tile is shown where it lies, chosen',
        (tester) async {
      await openPicker(tester, (api) => imageMove(api));
      await tester.enterText(find.byKey(const Key("picker-search")), "par");
      await tester.pump(FolderPickerState.searchDelay);
      await tester.pumpAndSettle();
      await tapKey(tester, "picker-match-Trips/Paris");
      expect(pathLine(tester), "Trips / Paris");
      expect(find.byKey(const Key("picker-up")), findsOneWidget);
      expect(row(tester, "Paris").selected, isTrue);
      expect(confirm(tester).onPressed, isNotNull);
    });

    testWidgets('the top level says nothing about albums', (tester) async {
      await openPicker(tester, (api) => imageMove(api));
      expect(find.byKey(const Key("picker-needs-album")), findsNothing);
      expect(confirm(tester).onPressed, isNull,
          reason: "the disabled confirm is the signal");
    });
  });

  testWidgets('moving an album: an album is no target, and says why',
      (tester) async {
    var outcome = await openPicker(
        tester,
        (api) => FolderPicker(
              client: api,
              initialPath: const [],
              confirmLabel: (path) => "Move to ${path.join("/")}",
              targetIsAlbum: false,
            ));
    expect(confirm(tester).onPressed, isNotNull,
        reason: "a folder of folders takes an album");
    expect(refusal(tester, "Loose"), testL10n.albumHoldsNoFolders);
    expect(row(tester, "Loose").enabled, isFalse);

    await tapRow(tester, "Loose");
    expect(find.byKey(const Key("picker-selected")), findsNothing);
    expect(pathLine(tester), testL10n.pickerTopLevel);
    expect(confirm(tester).onPressed, isNotNull,
        reason: "the confirm stays on the level shown");
    expect(find.byKey(const Key("picker-create-album")), findsNothing);

    await tapRow(tester, "Trips");
    expect(refusal(tester, "Paris"), testL10n.albumHoldsNoFolders);
    await tapKey(tester, "picker-confirm");
    expect(outcome.picked!.path, ["Trips"]);
  });

  testWidgets('the upload target: an album without contribute is refused',
      (tester) async {
    var outcome = await openPicker(
        tester,
        (api) => FolderPicker(
              client: api,
              initialPath: const ["Trips"],
              confirmLabel: (_) => "Here",
              targetIsAlbum: true,
              mayCreateAlbum: true,
              requireContribute: true,
            ));
    // Only the server knows: nothing is refused before it is asked.
    expect(refusal(tester, "Rome"), isNull);

    await tapRow(tester, "Rome");
    expect(refusal(tester, "Rome"), testL10n.uploadTargetNoContribute);
    expect(row(tester, "Rome").enabled, isFalse);
    expect(find.byKey(const Key("picker-selected")), findsNothing);
    expect(confirm(tester).onPressed, isNull);

    await tapRow(tester, "Paris");
    expect(refusal(tester, "Paris"), isNull);
    expect(pathLine(tester), "Trips / Paris");
    expect(confirm(tester).onPressed, isNotNull);
    await tapKey(tester, "picker-confirm");
    expect(outcome.picked!.path, ["Trips", "Paris"]);
    expect(outcome.picked!.title, "Paris");
  });

  testWidgets('"Create new album…" stands at a sub-level of a phone too',
      (tester) async {
    phone(tester, keyboard: true);
    var outcome = await openPicker(tester, (api) => imageMove(api));
    await tapRow(tester, "Trips");
    var create = find.byKey(const Key("picker-create-album"));
    expect(tappable(tester, create), isTrue);

    await tester.tap(create);
    await tester.pumpAndSettle();
    expect(find.byType(CreateAlbumDialog), findsOneWidget);
    await tester.enterText(find.byType(TextFormField).first, "Louvre");
    await tester.pumpAndSettle();
    await tester.tap(find.text(testL10n.create));
    await tester.pumpAndSettle();
    expect(outcome.picked!.path, ["Trips"], reason: "made in the level shown");
    expect(outcome.picked!.newAlbum!.title, "Louvre");
  });
}
