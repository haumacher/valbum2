/// Review probes of the inbox screen (issue #136), composed with what the
/// package did not look at: a year boundary in the headings, a group a stale
/// cache might still hold, a member who may only contribute, and the way out
/// with a selection standing.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/inbox_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'inbox_view_test.dart' hide main;
import 'move_test.dart' hide main;

void main() {
  test('the headings of a group a stale cache still holds are its members', () {
    var album = AlbumInfo.fromString(
        '{"kind": "INBOX", "parts": ['
        '["ImageGroup", {"representative": 0, "images": ['
        '{"name": "g1.jpg", "kind": "IMAGE", "date": ${noon(2025, 12, 31).millisecondsSinceEpoch}},'
        '{"name": "g2.jpg", "kind": "IMAGE", "date": ${noon(2026, 1, 1).millisecondsSinceEpoch}}]}],'
        '${part("a.jpg", noon(2026, 1, 1))}]}');
    var days = inboxDays(album!.parts);
    expect(days.map((d) => d.images.map((i) => i.name).toList()).toList(),
        [["g1.jpg"], ["g2.jpg", "a.jpg"]]);
  });

  testWidgets('a month line per month across a year boundary, and the way out asks nothing with a selection standing',
      (tester) async {
    var photos = <(String, int, int, int)>[
      ("x.jpg", 2025, 12, 31),
      ("y.jpg", 2026, 1, 1),
      ("z.jpg", 2026, 1, 2),
    ];
    await pumpInbox(
      tester,
      (request) => inboxTree(request, album: inboxJson(photos: photos)),
    );
    expect(find.byKey(inboxMonthKey(DateTime(2025, 12))), findsOneWidget);
    expect(find.byKey(inboxMonthKey(DateTime(2026, 1))), findsOneWidget);

    await tester.tap(find.byKey(inboxMonthKey(DateTime(2026, 1))));
    await tester.pumpAndSettle();
    expect(selectedNames(tester), ["y.jpg", "z.jpg"]);

    var delegate = tester
        .state<VAlbumState>(find.byType(VAlbumView).first)
        .navigator
        .delegate;
    expect(await delegate.leaveAlbum(const ["Inbox"]), isTrue,
        reason: "A selection is no edit; nothing is buffered.");
    expect(find.byKey(const Key("leave-dialog")), findsNothing);
  });

  testWidgets('a member who may only contribute gets the ways out and no sidecar write',
      (tester) async {
    await pumpInbox(
      tester,
      (request) => inboxTree(
        request,
        album: inboxJson(rights: ["view", "download", "contribute"]),
      ),
    );
    await tapTileOf(tester, "a.jpg");
    expect(find.byKey(const Key("inbox-rotate-right")), findsNothing,
        reason: "A sidecar write needs edit.");
    expect(find.byKey(const Key("privacy-control")), findsNothing);
    await openMenu(tester);
    expect(find.byKey(const Key("move-to")), findsOneWidget);
    expect(find.byKey(const Key("delete-selection")), findsOneWidget);
    expect(find.byKey(const Key("album-properties")), findsNothing);
  });
}
