/// Probe of issue #160 composed with what the inbox had: the album's tap
/// semantics meet the derived headings' check boxes and the newest-first order
/// (a shift range runs across a month line as drawn), and "delete = −2" meets
/// the heading selection, the trash entry and the write-at-once doctrine.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/resource.dart';

import 'inbox_view_test.dart' hide main;
import 'move_test.dart' show json;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

const List<String> editRights = ["view", "download", "contribute", "edit"];

Map<String, int> ratingsOf(http.Request put) {
  var sent = Resource.fromString(put.body) as AlbumInfo;
  return {
    for (var part in sent.parts)
      if (part is ImagePart) part.name: part.rating,
  };
}

void main() {
  testWidgets(
      'a shift range crosses the month line as drawn, and the heading box composes with it',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpInbox(
          tester,
          (request) =>
              inboxTree(request, album: inboxJson(rights: editRights)));

      // Shown: d (April 5), c (March 2), a, b (March 1). Anchor on the newest,
      // shift on the oldest: everything between, across the month line.
      await tapTileOf(tester, "d.jpg");
      await tapTileWith(tester, "b.jpg", LogicalKeyboardKey.shiftLeft);
      expect(selectedNames(tester), ["d.jpg", "c.jpg", "a.jpg", "b.jpg"]);

      // A plain click replaces the whole range by one.
      await tapTileOf(tester, "a.jpg");
      expect(selectedNames(tester), ["a.jpg"]);

      // The day's check box adds the rest of its day, and takes the day back.
      await tapHeading(tester, "inbox-day-2026-03-01");
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);
      await tapHeading(tester, "inbox-day-2026-03-01");
      expect(selectedNames(tester), isEmpty);

      // Ctrl toggles one in and out without touching the others.
      await tapTileOf(tester, "c.jpg");
      await tapTileWith(tester, "d.jpg", LogicalKeyboardKey.controlLeft);
      expect(selectedNames(tester), ["d.jpg", "c.jpg"]);
      await tapTileWith(tester, "c.jpg", LogicalKeyboardKey.controlLeft);
      expect(selectedNames(tester), ["d.jpg"]);
    });
  });

  testWidgets(
      'deleting a day rates exactly its photographs −2 in one write, and the day is gone',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpInbox(
        tester,
        (request) => request.method == "PUT"
            ? json("")
            : inboxTree(request, album: inboxJson(rights: editRights)),
        requests: requests,
      );

      await tapHeading(tester, "inbox-day-2026-03-01");
      expect(selectedNames(tester), ["a.jpg", "b.jpg"]);

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("delete-selection")));
      await tester.pumpAndSettle();

      var puts = requests.where((r) => r.method == "PUT").toList();
      expect(puts, hasLength(1), reason: "one write for the whole day");
      expect(requests.where((r) => r.url.queryParameters["action"] == "delete"),
          isEmpty,
          reason: "an editor's delete is a rating, not a move");
      expect(ratingsOf(puts.single),
          {"d.jpg": 0, "c.jpg": 0, "a.jpg": -2, "b.jpg": -2});
      expect((Resource.fromString(puts.single.body) as AlbumInfo).kind,
          AlbumKind.inbox,
          reason: "the inbox stays an inbox");

      // The day is gone with its photographs, nothing stays selected, and the
      // way to them is the trash entry.
      expect(find.byKey(const Key("inbox-day-2026-03-01")), findsNothing);
      expect(selectedNames(tester), isEmpty);
      expect(find.byKey(const Key("inbox-selection")), findsNothing);
      await openMenu(tester);
      expect(find.byKey(const Key("show-trash")), findsOneWidget);
      expect(find.text(testL10n.showTrash), findsOneWidget);
    });
  });
}
