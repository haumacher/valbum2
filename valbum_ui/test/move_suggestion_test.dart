/// The move dialog opens where the photos belong, issue #279: at the album
/// the server suggests for the day the first of them was taken, selected and
/// scrolled to the middle; where nothing says when they were taken, at the
/// album they come from, selected — from the inbox at the top level, as
/// before. A suggestion that cannot be had opens the picker as before.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';

import 'move_target_kind_test.dart'
    show confirmEnabled, json, openPicker, pathOf, recordingClient;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// Noon of the given day.
int noon(int year, int month, int day) =>
    DateTime(year, month, day, 12).millisecondsSinceEpoch;

/// The albums of the year 2002, one a week, as the server answers them:
/// newest first, so that the picker's own order is really looked at.
final List<(String, int)> albums2002 = [
  for (var week = 0; week < 40; week++)
    (
      "2002-W${week.toString().padLeft(2, "0")} Week",
      DateTime(2002, 1, 6 + 7 * week).millisecondsSinceEpoch,
    ),
].reversed.toList();

/// The album the server suggests: in the middle of the year, far below the
/// first screenful of the list.
const String suggested = "2002-W20 Week";

String photo(String name, int date) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "date": $date, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]';

String album(String path, List<String> parts, {String kind = "ALBUM"}) =>
    '["AlbumInfo", {"path": "$path", "title": "$path", "subTitle": "", '
    '"kind": "$kind", "rights": [{"name": "edit"}], '
    '"parts": [${parts.join(", ")}]}]';

/// The root: the years in no order, a folder nothing dates, and the albums
/// the photos come from.
const String root = '["ListingInfo", {"path": "", "title": "Library", '
    '"rights": [{"name": "edit"}], "folders": ['
    '{"name": "2003", "title": "2003", "kind": "FOLDER", '
    '"effectiveDate": 1041379200000}, '
    '{"name": "Misc", "title": "Misc", "kind": "FOLDER"}, '
    '{"name": "2002", "title": "2002", "kind": "FOLDER", '
    '"effectiveDate": 1009843200000}, '
    '{"name": "Holiday", "title": "Holiday", "effectiveDate": 946684800000}, '
    '{"name": "Scans", "title": "Scans"}'
    ']}]';

/// The library: [suggestion] answers `?type=folders&near=`.
http.Response Function(http.Request) library(
  http.Response Function(http.Request request) suggestion,
) =>
    (request) {
      if (request.url.queryParameters["type"] == "folders") {
        return suggestion(request);
      }
      if (request.url.queryParameters["action"] == "move") {
        return json('{"outcomes":[{"name":"a.jpg","newName":"a.jpg",'
            '"message":""}]}');
      }
      switch (pathOf(request)) {
        case "/valbum/data/":
          return json(root);
        case "/valbum/data/2002/":
          return json('["ListingInfo", {"path": "2002", "title": "2002", '
              '"rights": [{"name": "edit"}], "folders": [${[
            for (var (name, date) in albums2002)
              '{"name": "$name", "title": "${name.substring(5)}", '
                  '"effectiveDate": $date}',
          ].join(", ")}]}]');
        case "/valbum/data/Holiday/":
          return json(album("Holiday", [
            photo("b.jpg", noon(2002, 5, 30)),
            photo("a.jpg", noon(2002, 5, 27)),
            photo("u.jpg", 0),
          ]));
        case "/valbum/data/Scans/":
          return json(album("Scans", [photo("a.jpg", 0), photo("b.jpg", 0)]));
        case "/valbum/data/Inbox/":
          return json(album("Inbox", [photo("a.jpg", 0)], kind: "INBOX"));
      }
      return http.Response("No such resource: ${pathOf(request)}", 404);
    };

/// The server's suggestion: [suggested] in 2002.
http.Response suggesting(http.Request request) =>
    json('{"folders": [{"path": "2002/$suggested", "folder": '
        '{"name": "$suggested", "title": "Week"}}]}');

/// Shows the album at [path] in the edit mode with every photo selected.
Future<void> pumpSelection(
  WidgetTester tester,
  VAlbumClient client,
  String path,
) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: ListingOrAlbumRoute([path]),
  ));
  await tester.pumpAndSettle();
  // A long press selects, see the move tests of issue #113.
  var count = find.byType(Image).evaluate().length;
  for (var n = 0; n < count; n++) {
    var box = tester.getRect(find.byType(Image).at(n));
    await tester.longPressAt(Offset(box.left + 8, box.center.dy));
    await tester.pumpAndSettle();
  }
}

String pathLine(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(const Key("picker-path"))).data!;

ListTile row(WidgetTester tester, String name) => tester.widget<ListTile>(
    find.byKey(Key("picker-folder-$name"), skipOffstage: false));

/// The requests that asked for a suggestion.
Iterable<http.Request> suggestions(List<http.Request> requests) => requests
    .where((request) => request.url.queryParameters.containsKey("near"));

void main() {
  testWidgets('opens at the suggested album, selected and in the middle',
      (tester) async {
    var requests = <http.Request>[];
    var client = recordingClient(library(suggesting), requests);

    await withFakeImageHttp(() async {
      await pumpSelection(tester, client, "Holiday");
      await openPicker(tester);

      var asked = suggestions(requests).single.url;
      expect(Uri.decodeFull(asked.path), "/valbum/data/");
      expect(asked.queryParameters["near"], "${noon(2002, 5, 27)}",
          reason: "the earliest photo that says when it was taken");
      expect(asked.queryParameters["from"], "Holiday",
          reason: "the album they come from is no suggestion");

      expect(pathLine(tester), "2002 / $suggested",
          reason: "the confirm acts on the album selected");
      expect(row(tester, suggested).selected, isTrue);
      expect(find.byKey(const Key("picker-selected")), findsOneWidget);
      expect(find.byKey(const Key("picker-needs-album")), findsNothing);
      expect(confirmEnabled(tester), isTrue);
      expect(find.text("Move 3 images to '2002/$suggested'"), findsOneWidget);

      // In the middle of the list, a row's height either way at most.
      var list = tester.getRect(find.descendant(
          of: find.byKey(const Key("folder-picker")),
          matching: find.byType(ListView)));
      var selected =
          tester.getRect(find.byKey(const Key("picker-folder-$suggested")));
      expect((selected.center.dy - list.center.dy).abs(),
          lessThan(selected.height),
          reason: "centred: ${selected.center.dy} in $list");

      // The oldest first in the year as well.
      var first =
          tester.getRect(find.byKey(const Key("picker-folder-2002-W19 Week")));
      expect(first.top, lessThan(selected.top));

      await tester.tap(find.byKey(const Key("picker-confirm")));
      await tester.pumpAndSettle();
    });

    var move =
        requests.singleWhere((r) => r.url.queryParameters["action"] == "move");
    expect(Uri.decodeFull(move.url.path), "/valbum/data/Holiday/");
    expect(move.body, startsWith('{"target":"2002/$suggested",'));
  });

  testWidgets('going anywhere leaves the selection behind', (tester) async {
    var client = recordingClient(library(suggesting), []);

    await withFakeImageHttp(() async {
      await pumpSelection(tester, client, "Holiday");
      await openPicker(tester);
      expect(pathLine(tester), "2002 / $suggested");

      await tester.tap(find.byKey(const Key("picker-up")));
      await tester.pumpAndSettle();
      expect(pathLine(tester), testL10n.pickerTopLevel);
      expect(find.byKey(const Key("picker-selected")), findsNothing);
      expect(confirmEnabled(tester), isFalse,
          reason: "the top level is no album");
    });
  });

  testWidgets('photos that do not say when: the album they come from',
      (tester) async {
    var requests = <http.Request>[];
    var client = recordingClient(library(suggesting), requests);

    await withFakeImageHttp(() async {
      await pumpSelection(tester, client, "Scans");
      await openPicker(tester);

      expect(suggestions(requests), isEmpty, reason: "no day to look near");
      expect(pathLine(tester), "Scans");
      expect(row(tester, "Scans").selected, isTrue);
      expect(confirmEnabled(tester), isTrue);

      // The top level the oldest first, the undated behind by name.
      double top(String name) =>
          tester.getRect(find.byKey(Key("picker-folder-$name"))).top;
      expect(top("Holiday"), lessThan(top("2002")));
      expect(top("2002"), lessThan(top("2003")));
      expect(top("2003"), lessThan(top("Misc")));
      expect(top("Misc"), lessThan(top("Scans")));
    });
  });

  testWidgets('from the inbox, photos that do not say when: the top level',
      (tester) async {
    var requests = <http.Request>[];
    var client = recordingClient(library(suggesting), requests);

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
      ));
      await tester.pumpAndSettle();
      var inbox = tester.state<InboxContentState>(find.byType(InboxContent));
      inbox.toggleSection(inbox.images);
      await tester.pumpAndSettle();
      unawaited(inbox.moveSelection());
      await tester.pumpAndSettle();

      expect(suggestions(requests), isEmpty);
      expect(pathLine(tester), testL10n.pickerTopLevel);
      expect(find.byKey(const Key("picker-selected")), findsNothing);
      expect(confirmEnabled(tester), isFalse);
    });
  });

  testWidgets('a suggestion that cannot be had opens the picker as before',
      (tester) async {
    var requests = <http.Request>[];
    var client = recordingClient(
      library((_) => http.Response("Gone fishing", 500)),
      requests,
    );

    await withFakeImageHttp(() async {
      await pumpSelection(tester, client, "Holiday");
      await openPicker(tester);

      expect(suggestions(requests), hasLength(1));
      expect(pathLine(tester), testL10n.pickerTopLevel,
          reason: "the folder the album lies in, as before");
      expect(find.byKey(const Key("picker-selected")), findsNothing);
      expect(find.byKey(const Key("picker-error")), findsNothing,
          reason: "a suggestion is no step that failed");
      expect(find.text("Gone fishing"), findsNothing);
    });
  });

  testWidgets('a server that suggests nothing opens the picker as before',
      (tester) async {
    var client = recordingClient(library((_) => json('{}')), []);

    await withFakeImageHttp(() async {
      await pumpSelection(tester, client, "Holiday");
      await openPicker(tester);
      expect(pathLine(tester), testL10n.pickerTopLevel);
      expect(find.byKey(const Key("picker-selected")), findsNothing);
    });
  });
}
