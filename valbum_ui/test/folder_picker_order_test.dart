/// The folder picker lists every level the oldest first, the undated behind
/// them by name (issue #279) — whatever order the server sent, and unlike the
/// listing, whose undated levels show the newest first (issue #270).
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/upload_target_view.dart';

import 'upload_target_view_test.dart'
    show json, pumpSection, rightsOf, serverDataUrl, tapKey;

int millis(int year, [int month = 1, int day = 1]) =>
    DateTime(year, month, day).millisecondsSinceEpoch;

String folder(String name, [int date = 0]) =>
    '{"name": "$name", "title": "$name", "kind": "FOLDER"'
    '${date == 0 ? "" : ', "effectiveDate": $date'}}';

String listing(String path, List<String> folders) =>
    '["ListingInfo", {"path": "$path", "title": "$path", '
    '"rights": ${rightsOf(["contribute"])}, '
    '"folders": [${folders.join(", ")}]}]';

/// A space as an older server sends it: every level in the order of the disk.
http.Response scrambled(http.Request request) {
  switch (Uri.decodeFull(request.url.path)) {
    case "/valbum/data/":
      return json(listing("", [
        folder("2024", millis(2024)),
        folder("Trips"),
        folder("2023", millis(2023)),
        folder("2025", millis(2025)),
      ]));
    case "/valbum/data/2025/":
      return json(listing("2025", [
        folder("Dec", millis(2025, 12, 24)),
        folder("Misc"),
        folder("Jan", millis(2025, 1, 6)),
        folder("Jul", millis(2025, 7)),
      ]));
    case "/valbum/data/Trips/":
      return json(listing("Trips", [
        folder("Lake", millis(2019, 7, 4)),
        folder("Somewhere"),
        folder("Snow", millis(2023, 2, 1)),
      ]));
  }
  return http.Response("No such resource", 404);
}

/// The names of the folder rows of the picker's level, top to bottom.
///
/// The list is 420 high and builds only what it shows, so it is scrolled down
/// a row at a time and every row is taken in the order it comes into view.
Future<List<String>> rows(WidgetTester tester) async {
  var seen = <String>[];
  for (var step = 0; step < 10; step++) {
    var visible = [
      for (var element in find.byType(ListTile).evaluate())
        if (element.widget.key case ValueKey<String>(:var value)
            when value.startsWith("picker-folder-"))
          (
            value.substring("picker-folder-".length),
            tester.getTopLeft(find.byWidget(element.widget)).dy,
          ),
    ]..sort((a, b) => a.$2.compareTo(b.$2));
    for (var row in visible) {
      if (!seen.contains(row.$1)) {
        seen.add(row.$1);
      }
    }
    if (visible.isEmpty) {
      break;
    }
    await tester.drag(
      find.byKey(Key("picker-folder-${visible.first.$1}")),
      const Offset(0, -40),
    );
    await tester.pumpAndSettle();
  }
  return seen;
}

void main() {
  testWidgets('the oldest first on every level', (tester) async {
    var client = VAlbumClient(
      dataUrl: serverDataUrl,
      httpClient: MockClient((request) async => scrambled(request)),
    );
    await pumpSection(tester, client: client);
    await tapKey(tester, uploadTargetChooseKey);

    expect(await rows(tester), ["2023", "2024", "2025", "Trips"],
        reason: "the root too, unlike the start page of the listing");

    await tapKey(tester, const Key("picker-folder-2025"));
    expect(await rows(tester), ["Jan", "Jul", "Dec", "Misc"],
        reason: "a year is read through like an album");

    await tapKey(tester, const Key("picker-up"));
    await tapKey(tester, const Key("picker-folder-Trips"));
    expect(await rows(tester), ["Lake", "Snow", "Somewhere"],
        reason: "an undated folder as well");
  });
}
