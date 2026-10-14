/// The shares of a space in Manage (issue #269): what is shared with whom, by
/// album and by person, narrowed by a filter, a switch and the shortcuts from
/// an album's share dialog and from a contact, revoked, changed in place, and
/// the people on a link taken off, shut out or sent a new link.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart' show JsonReader;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/manage_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/shares_view.dart';

import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

/// The moment the tests read "expired" against.
final DateTime now = DateTime.utc(2026, 6, 1);

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

http.Response refusal(int status, String message) =>
    json('["ErrorInfo", {"message": "$message"}]', status);

Map<String, Object> recipient(String id, String name,
        {bool admitted = true, String shutOut = ""}) =>
    {
      "contact": id,
      "name": name,
      "admitted": admitted,
      "shutOut": shutOut,
      "addresses": [
        {"kind": "EMAIL", "value": "${name.toLowerCase()}@example.org"},
      ],
    };

/// An anonymous link and an addressed one on the zoo, an open personal link
/// on the public album with one visitor, an addressed link whose only
/// recipient is shut out of it, and an expired anonymous link.
List<Map<String, Object>> spaceLinks() => [
      {
        "id": "A1",
        "label": "Anyone",
        "path": "2024/Zoo",
        "title": "Zoo",
        "rights": [
          {"name": "view"},
          {"name": "download"},
        ],
        "createdBy": "alice",
        "created": "2026-05-01T10:00:00Z",
        "uploads": 2,
        "albumLabels": [
          {"name": "Elephants"},
        ],
      },
      {
        "id": "L1",
        "label": "Party",
        "path": "2024/Zoo",
        "title": "Zoo",
        "type": "PERSONAL",
        "addressed": true,
        "createdBy": "bob",
        "created": "2026-05-02T10:00:00Z",
        "recipients": [
          recipient("c1", "Petra"),
          recipient("c2", "Klaus"),
        ],
      },
      {
        "id": "O1",
        "label": "Group",
        "path": "Public",
        "title": "Public",
        "type": "PERSONAL",
        "created": "2026-05-03T10:00:00Z",
        "visitors": [
          {
            "contact": "c3",
            "name": "Oma",
            "firstSeen": "2026-05-03T10:00:00Z",
            "lastSeen": "2026-05-09T10:00:00Z",
            "admitted": true,
          },
        ],
      },
      {
        "id": "S1",
        "label": "Shut",
        "path": "Unsorted",
        "title": "Unsorted",
        "type": "PERSONAL",
        "addressed": true,
        "created": "2026-05-04T10:00:00Z",
        "recipients": [
          recipient("c1", "Petra",
              admitted: false, shutOut: "2026-05-05T10:00:00Z"),
        ],
      },
      {
        "id": "X1",
        "label": "Old",
        "path": "2023/Trip",
        "title": "Trip",
        "expires": "2026-01-01T00:00:00Z",
        "created": "2025-12-01T10:00:00Z",
      },
    ];

class SharesServer {
  final requests = <http.Request>[];

  List<Map<String, Object>> links = spaceLinks();

  /// What `?type=space-shares` is refused with, `null` for nothing.
  http.Response? refuseList;

  /// What `?action=edit-share` is refused with, `null` for nothing.
  http.Response? refuseEdit;

  http.Response answer(http.Request request) {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "space-shares") {
      return refuseList ?? json(jsonEncode({"links": links}));
    }
    var action = query["action"];
    if (action == null) {
      return refusal(404, "No such resource");
    }
    var body = jsonDecode(request.body) as Map<String, dynamic>;
    Map<String, Object> linkOf(String id) =>
        links.firstWhere((link) => link["id"] == id);
    switch (action) {
      case "unshare":
        var gone = linkOf(body["id"] as String);
        links = [
          for (var link in links)
            if (link != gone) link
        ];
        return json(jsonEncode({
          "links": [gone]
        }));
      case "remove-recipient":
        var link = Map<String, Object>.of(linkOf(body["link"] as String));
        link["recipients"] = [
          for (var r in link["recipients"] as List)
            if (r["contact"] != body["contact"]) r,
        ];
        links = [
          for (var l in links) l["id"] == link["id"] ? link : l,
        ];
        return json(jsonEncode({
          "links": [link]
        }));
      case "shut-out":
        var link = Map<String, Object>.of(linkOf(body["link"] as String));
        var shut = body["shutOut"] == true;
        link["recipients"] = [
          for (var r in (link["recipients"] as List? ?? const []))
            if (r["contact"] == body["contact"])
              {
                ...r as Map<String, Object>,
                "shutOut": shut ? "2026-05-10T10:00:00Z" : "",
                "admitted": !shut,
              }
            else
              r,
        ];
        links = [
          for (var l in links) l["id"] == link["id"] ? link : l,
        ];
        return json(jsonEncode({
          "links": [link]
        }));
      case "resend":
        return json(jsonEncode({
          "link": linkOf(body["link"] as String),
          "recipients": [
            {
              "contact": body["contact"],
              "name": "Petra",
              "addresses": [
                {"kind": "EMAIL", "value": "petra@example.org"},
              ],
              "token": "fresh",
              "url": "/valbum/s/fresh/",
            },
          ],
        }));
      case "edit-share":
        var stop = refuseEdit;
        if (stop != null) {
          return stop;
        }
        var link = {
          ...linkOf(body["id"] as String),
          for (var field in const [
            "label",
            "expires",
            "maxPrivacy",
            "minRating",
            "photoLabel",
            "rights",
          ])
            if (body.containsKey(field)) field: body[field] as Object,
        };
        links = [
          for (var l in links) l["id"] == link["id"] ? link : l,
        ];
        return json(jsonEncode({
          "links": [link]
        }));
    }
    return refusal(400, "Unknown action");
  }

  Map<String, dynamic> bodyOf(String action) => jsonDecode(requests
      .lastWhere((request) => request.url.queryParameters["action"] == action)
      .body) as Map<String, dynamic>;

  http.Request lastOf(String action) => requests
      .lastWhere((request) => request.url.queryParameters["action"] == action);

  int count(String action) => requests
      .where((request) => request.url.queryParameters["action"] == action)
      .length;
}

VAlbumClient clientOf(SharesServer server) => VAlbumClient(
      dataUrl: dataUrl,
      token: "device",
      httpClient: MockClient((request) async => server.answer(request)),
    );

Future<void> pumpShares(
  WidgetTester tester,
  SharesServer server, {
  String? album,
  String? albumName,
  String? person,
  String? personName,
  Locale locale = const Locale("en"),
  CallerInfo? caller,
}) async {
  tester.view.physicalSize = const Size(1000, 3000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  Widget screen = SharesScreen(
    client: clientOf(server),
    album: album,
    albumName: albumName,
    person: person,
    personName: personName,
    isWeb: true,
    now: now,
  );
  await tester.pumpWidget(localizedApp(
    caller == null ? screen : CallerScope(caller: caller, child: screen),
    locale: locale,
  ));
  await tester.pumpAndSettle();
}

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)).first);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)).first);
  await tester.pumpAndSettle();
}

/// The ids of the link cards shown, in order, each once per group it stands
/// in.
List<String> cards(WidgetTester tester) => [
      for (var card in tester.widgetList<Card>(find.byType(Card)))
        if (card.key is ValueKey<String> &&
            (card.key as ValueKey<String>).value.startsWith("shares-link-"))
          (card.key as ValueKey<String>).value.split("-").last,
    ];

/// The keys of the group headings shown, in order.
List<String> groups(WidgetTester tester) => [
      for (var tile in tester.widgetList<ListTile>(find.byType(ListTile)))
        if (tile.key is ValueKey<String> &&
            (tile.key as ValueKey<String>).value.startsWith("shares-group-"))
          (tile.key as ValueKey<String>)
              .value
              .substring("shares-group-".length),
    ];

ShareLink linkOf(Map<String, Object> json) =>
    ShareLink.read(JsonReader.fromString(jsonEncode(json)));

void main() {
  group('the grouping', () {
    test('by album and by person hold the same links', () {
      var links = [for (var link in spaceLinks()) linkOf(link)];
      Set<String> idsOf(List<ShareGroup> groups) => {
            for (var group in groups)
              for (var link in group.links) link.id,
          };
      expect(idsOf(groupByAlbum(links)), idsOf(groupByPerson(links)));
      expect(idsOf(groupByAlbum(links)), {"A1", "L1", "O1", "S1", "X1"});
    });

    test(
        'a contact stands under every link admitting them, never under one '
        'they are shut out of', () {
      var links = [for (var link in spaceLinks()) linkOf(link)];
      var byPerson = groupByPerson(links);
      ShareGroup groupOf(String key) =>
          byPerson.firstWhere((group) => group.key == key);
      expect([for (var link in groupOf("c1").links) link.id], ["L1"]);
      expect([for (var link in groupOf("c3").links) link.id], ["O1"]);
      var anyone =
          byPerson.firstWhere((group) => group.kind == ShareGroupKind.anyone);
      expect([for (var link in anyone.links) link.id], ["A1", "O1", "X1"]);
      var nobody =
          byPerson.firstWhere((group) => group.kind == ShareGroupKind.nobody);
      expect([for (var link in nobody.links) link.id], ["S1"]);
      expect([
        for (var group in byPerson) group.kind.name
      ], [
        "anyone",
        "person",
        "person",
        "person",
        "nobody",
      ]);
      expect([for (var group in byPerson) group.name],
          ["", "Klaus", "Oma", "Petra", ""]);
    });

    test('an album is shared by links on it, above it and below it', () {
      ShareLink on(String path) => ShareLink(path: path);
      expect(sharesAlbum(on("2024/Zoo"), "2024/Zoo"), isTrue);
      expect(sharesAlbum(on("2024"), "2024/Zoo"), isTrue);
      expect(sharesAlbum(on(""), "2024/Zoo"), isTrue);
      expect(sharesAlbum(on("2024/Zoo/Day 2"), "2024/Zoo"), isTrue);
      expect(sharesAlbum(on("2024/Zoo 2"), "2024/Zoo"), isFalse);
      expect(sharesAlbum(on("Public"), "2024/Zoo"), isFalse);
    });

    test('what takes something away is told apart from what does not', () {
      var before = ShareLink(
        rights: [RightName(name: "view"), RightName(name: "download")],
        maxPrivacy: 1,
        minRating: 0,
        expires: "2026-07-01T00:00:00Z",
      );
      ShareLink after({
        List<String> rights = const ["view", "download"],
        int maxPrivacy = 1,
        int minRating = 0,
        String expires = "2026-07-01T00:00:00Z",
        String photoLabel = "",
        String label = "",
      }) =>
          ShareLink(
            rights: [for (var right in rights) RightName(name: right)],
            maxPrivacy: maxPrivacy,
            minRating: minRating,
            expires: expires,
            photoLabel: photoLabel,
            label: label,
          );
      expect(takesAway(before, after(label: "renamed")), isFalse);
      expect(takesAway(before, after(expires: "")), isFalse);
      expect(
          takesAway(before, after(expires: "2026-08-01T00:00:00Z")), isFalse);
      expect(takesAway(before, after(minRating: -2)), isFalse);
      expect(
          takesAway(before, after(rights: ["view", "download", "contribute"])),
          isFalse);
      expect(takesAway(before, after(rights: ["view"])), isTrue);
      expect(takesAway(before, after(maxPrivacy: 0)), isTrue);
      expect(takesAway(before, after(minRating: 1)), isTrue);
      expect(takesAway(before, after(photoLabel: "Elephants")), isTrue);
      expect(takesAway(before, after(expires: "2026-06-15T00:00:00Z")), isTrue);
    });
  });

  group('the screen', () {
    testWidgets('lists every live link by album, the expired ones on demand',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      expect(
          server.requests.single.url.toString(), "$dataUrl/?type=space-shares");
      expect(
          groups(tester), ["album-2024/Zoo", "album-Public", "album-Unsorted"]);
      expect(cards(tester), ["A1", "L1", "O1", "S1"]);
      expect(find.text(testL10n.sharesExpiredHidden(1)), findsOneWidget);

      var summary = tester
          .widget<Text>(
              find.byKey(const Key("shares-summary-album-2024/Zoo-A1")))
          .data!;
      expect(summary, contains(testL10n.linkAnonymous));
      var made = tester
          .widget<Text>(find.byKey(const Key("shares-made-album-2024/Zoo-A1")))
          .data!;
      expect(
          made,
          contains(
              testL10n.linkSharedBy("alice", dayOf("2026-05-01T10:00:00Z"))));
      expect(made, contains(testL10n.photosAdded(2)));
      expect(find.byKey(const Key("shares-recipient-L1-c1")), findsOneWidget);
      expect(find.byKey(const Key("shares-visitor-O1-c3")), findsOneWidget);

      await tapKey(tester, "shares-expired");
      expect(cards(tester), ["X1", "A1", "L1", "O1", "S1"]);
      expect(
          tester
              .widget<Text>(
                  find.byKey(const Key("shares-summary-album-2023/Trip-X1")))
              .data,
          contains(testL10n.linkExpiredOn(dayOf("2026-01-01T00:00:00Z"))));
    });

    testWidgets('by person: anyone, each contact, and nobody any more',
        (tester) async {
      await pumpShares(tester, SharesServer());
      await tapKey(tester, "shares-by-person");
      expect(groups(tester), [
        "anyone-",
        "person-c2",
        "person-c3",
        "person-c1",
        "nobody-",
      ]);
      expect(cards(tester), ["A1", "O1", "L1", "O1", "L1", "S1"]);
      expect(
          tester
              .widget<Text>(find.byKey(const Key("shares-album-person-c1-L1")))
              .data,
          "Zoo · 2024/Zoo");
    });

    testWidgets('the filter narrows by album, person and label',
        (tester) async {
      await pumpShares(tester, SharesServer());
      await tester.enterText(find.byKey(const Key("shares-filter")), "public");
      await tester.pumpAndSettle();
      expect(cards(tester), ["O1"]);

      await tester.enterText(find.byKey(const Key("shares-filter")), "party");
      await tester.pumpAndSettle();
      expect(cards(tester), ["L1"]);

      await tapKey(tester, "shares-by-person");
      await tester.enterText(find.byKey(const Key("shares-filter")), "petra");
      await tester.pumpAndSettle();
      expect(groups(tester), ["person-c1", "nobody-"],
          reason: "Petra's group, and the link she is shut out of.");
      expect(cards(tester), ["L1", "S1"]);

      await tester.enterText(find.byKey(const Key("shares-filter")), "zzz");
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("shares-no-match")), findsOneWidget);
    });

    testWidgets('a member without the right reads the server\'s reason',
        (tester) async {
      var server = SharesServer()
        ..refuseList = refusal(403, "You may not hand out share links here.");
      await pumpShares(tester, server);
      expect(
          find.text("You may not hand out share links here."), findsOneWidget);
      expect(find.byKey(const Key("shares-none")), findsNothing);
    });

    testWidgets('narrowed to an album, until the chip is removed',
        (tester) async {
      await pumpShares(tester, SharesServer(),
          album: "2024/Zoo", albumName: "Zoo");
      expect(find.text(testL10n.sharesScopeAlbum("Zoo")), findsOneWidget);
      expect(cards(tester), ["A1", "L1"]);
      await tester.tap(find.descendant(
          of: find.byKey(const Key("shares-scope-album")),
          matching: find.byIcon(Icons.clear)));
      await tester.pumpAndSettle();
      expect(cards(tester), ["A1", "L1", "O1", "S1"]);
    });

    testWidgets('narrowed to a person, grouped by person', (tester) async {
      await pumpShares(tester, SharesServer(),
          person: "c1", personName: "Petra");
      expect(find.text(testL10n.sharesScopePerson("Petra")), findsOneWidget);
      expect(groups(tester), ["person-c1"]);
      expect(cards(tester), ["L1"]);
      await tapKey(tester, "shares-by-album");
      expect(cards(tester), ["L1"]);
    });
  });

  group('the actions', () {
    testWidgets('deleting asks, deletes at the root and says so',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-delete-A1");
      expect(find.byKey(const Key("shares-delete-confirm")), findsOneWidget);
      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();
      expect(server.count("unshare"), 0);

      await tapKey(tester, "shares-delete-A1");
      await tapKey(tester, "shares-delete-confirmed");
      expect(server.bodyOf("unshare")["id"], "A1");
      expect(server.lastOf("unshare").url.path, "/valbum/data/");
      expect(cards(tester), ["L1", "O1", "S1"]);
      expect(find.text(testL10n.linkDeletedDone), findsOneWidget);
    });

    testWidgets('a recipient is taken off after asking', (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-recipient-menu-L1-c2");
      await tapKey(tester, "shares-remove-L1-c2");
      expect(find.text(testL10n.recipientRemoveTitle("Klaus")), findsOneWidget);
      await tapKey(tester, "shares-remove-confirmed");
      expect(
          server.bodyOf("remove-recipient"), {"link": "L1", "contact": "c2"});
      expect(find.byKey(const Key("shares-recipient-L1-c2")), findsNothing);
      expect(find.byKey(const Key("shares-recipient-L1-c1")), findsOneWidget);
      expect(find.text(testL10n.recipientRemoved("Klaus")), findsOneWidget);
    });

    testWidgets('shutting out asks, letting in again does not', (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-recipient-menu-L1-c1");
      await tapKey(tester, "shares-shut-out-L1-c1");
      expect(find.byKey(const Key("shares-shut-out-confirm")), findsOneWidget);
      await tapKey(tester, "shares-shut-out-confirmed");
      expect(server.bodyOf("shut-out"),
          {"link": "L1", "contact": "c1", "shutOut": true});
      expect(find.text(testL10n.shutOutDone("Petra")), findsOneWidget);

      await tapKey(tester, "shares-recipient-menu-L1-c1");
      await tapKey(tester, "shares-shut-out-L1-c1");
      expect(find.byKey(const Key("shares-shut-out-confirm")), findsNothing);
      expect(server.bodyOf("shut-out"),
          {"link": "L1", "contact": "c1", "shutOut": false});
    });

    testWidgets('a new link for a recipient is shown once, ready to send',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-recipient-menu-L1-c1");
      await tapKey(tester, "shares-renew-L1-c1");
      expect(find.text(testL10n.recipientRenewTitle("Petra")), findsOneWidget);
      await tapKey(tester, "shares-renew-confirmed");
      expect(server.bodyOf("resend"), {"link": "L1", "contact": "c1"});
      expect(find.byKey(const Key("shares-renewed")), findsOneWidget);
      expect(find.byKey(const Key("send-email-c1-0")), findsOneWidget);
      expect(find.byKey(const Key("send-copy-c1")), findsOneWidget);
      await tapKey(tester, "shares-renewed-done");
      expect(find.byKey(const Key("shares-renewed")), findsNothing);
    });
  });

  group('the edit dialog', () {
    testWidgets('asks before showing less and sends every option',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-edit-A1");
      expect(find.byKey(const Key("edit-link-dialog")), findsOneWidget);
      await tapKey(tester, "edit-link-right-download");
      await tapKey(tester, "edit-link-save");
      expect(find.byKey(const Key("edit-link-less")), findsOneWidget);
      await tapKey(tester, "edit-link-less-confirmed");

      var body = server.bodyOf("edit-share");
      expect(body["id"], "A1");
      expect(body["label"], "Anyone");
      expect(body["expires"] ?? "", "");
      expect(body["maxPrivacy"] ?? 0, 0);
      expect(body["minRating"] ?? 0, 0);
      expect(body["photoLabel"] ?? "", "");
      expect(body["rights"], [
        {"name": "view"},
      ]);
      expect(server.lastOf("edit-share").url.path, "/valbum/data/");
      expect(find.byKey(const Key("edit-link-dialog")), findsNothing);
      expect(find.text(testL10n.linkChanged), findsOneWidget);
      expect(
          tester
              .widget<Text>(
                  find.byKey(const Key("shares-summary-album-2024/Zoo-A1")))
              .data,
          isNot(contains(testL10n.rightLabelDownload)));
    });

    testWidgets('a change taking nothing away is not asked about',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-edit-A1");
      await tester.enterText(find.byKey(const Key("edit-link-label")), "All");
      await tapKey(tester, "edit-link-save");
      expect(find.byKey(const Key("edit-link-less")), findsNothing);
      expect(server.bodyOf("edit-share")["label"], "All");
      expect(find.text("All"), findsOneWidget);
    });

    testWidgets('offers the labels of the album and a new expiry',
        (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server);
      await tapKey(tester, "shares-edit-A1");
      await tapKey(tester, "edit-link-photo-label");
      await tester.tap(find.text(testL10n.labelFilterOnly("Elephants")).last);
      await tester.pumpAndSettle();
      await tapKey(tester, "edit-link-expiry");
      await tester.tap(find.text(testL10n.expiryOneWeek).last);
      await tester.pumpAndSettle();
      await tapKey(tester, "edit-link-save");
      await tapKey(tester, "edit-link-less-confirmed");
      var body = server.bodyOf("edit-share");
      expect(body["photoLabel"], "Elephants");
      expect(DateTime.parse(body["expires"] as String).isAfter(now), isTrue);
    });

    // Issue #282: the choices of the share dialog for the editor, and what
    // the link already has stays offered and kept.
    const viewer = CallerInfo(
        userName: "dave",
        role: roleView,
        clearance: clearancePublic,
        mayShare: true);

    List<int> ceilings(WidgetTester tester) => [
          for (var item in tester
              .widget<DropdownButton<int>>(
                  find.byKey(const Key("edit-link-shows")))
              .items!)
            item.value!,
        ];

    testWidgets('offers an editor only what they may give', (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server, caller: viewer);
      await tapKey(tester, "shares-edit-A1");
      expect(find.byKey(const Key("edit-link-right-download")), findsOneWidget);
      expect(find.byKey(const Key("edit-link-right-contribute")), findsNothing);
      expect(ceilings(tester), [0]);
    });

    testWidgets('keeps what the link already has, beyond the editor\'s own',
        (tester) async {
      var server = SharesServer();
      server.links[0]["rights"] = [
        {"name": "view"},
        {"name": "contribute"},
      ];
      server.links[0]["maxPrivacy"] = 1;
      await pumpShares(tester, server, caller: viewer);
      await tapKey(tester, "shares-edit-A1");
      expect(
          tester
              .widget<FilterChip>(
                  find.byKey(const Key("edit-link-right-contribute")))
              .selected,
          isTrue);
      expect(ceilings(tester), [1, 0]);

      // An edit of something else sends what the link had.
      await tester.enterText(find.byKey(const Key("edit-link-label")), "All");
      await tapKey(tester, "edit-link-save");
      expect(find.byKey(const Key("edit-link-less")), findsNothing);
      var body = server.bodyOf("edit-share");
      expect(body["rights"], [
        {"name": "view"},
        {"name": "contribute"},
      ]);
      expect(body["maxPrivacy"], 1);
    });

    testWidgets('an administrator is offered everything', (tester) async {
      var server = SharesServer();
      await pumpShares(tester, server,
          caller: const CallerInfo(userName: "alice", role: roleAdmin));
      await tapKey(tester, "shares-edit-A1");
      expect(
          find.byKey(const Key("edit-link-right-contribute")), findsOneWidget);
      expect(ceilings(tester), [1, 0]);
    });

    testWidgets('a refusal is said in the dialog, which stays open',
        (tester) async {
      var server = SharesServer()
        ..refuseEdit = refusal(400,
            "You cannot give this link 'contribute': you may not do that yourself.");
      await pumpShares(tester, server);
      await tapKey(tester, "shares-edit-A1");
      await tapKey(tester, "edit-link-right-contribute");
      await tapKey(tester, "edit-link-save");
      expect(find.byKey(const Key("edit-link-dialog")), findsOneWidget);
      expect(
          find.text(
              "You cannot give this link 'contribute': you may not do that yourself."),
          findsOneWidget);
      await tapKey(tester, "edit-link-cancel");
      expect(find.byKey(const Key("edit-link-dialog")), findsNothing);
      expect(find.text(testL10n.linkChanged), findsNothing);
    });
  });

  group('the shortcuts', () {
    testWidgets('the share dialog of an album opens its shares',
        (tester) async {
      var server = SharesServer();
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var client = VAlbumClient(
        dataUrl: dataUrl,
        token: "device",
        httpClient: MockClient((request) async {
          if (request.url.queryParameters["type"] == "shares") {
            return json('{"links": []}');
          }
          return server.answer(request);
        }),
      );
      await tester.pumpWidget(OfflineScope(
        state: OfflineState(),
        cache: MemoryOfflineCache(),
        child: localizedApp(
          Scaffold(
            body: ShareLinkDialog(
              client: client,
              path: const ["2024", "Zoo"],
              label: "'Zoo'",
              isWeb: true,
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tapKey(tester, "share-link-all");
      expect(find.byType(SharesScreen), findsOneWidget);
      expect(find.text(testL10n.sharesScopeAlbum("Zoo")), findsOneWidget);
      expect(cards(tester), ["A1", "L1"]);
    });

    testWidgets('a contact opens what is shared with them', (tester) async {
      var server = SharesServer();
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      var client = VAlbumClient(
        dataUrl: dataUrl,
        token: "device",
        httpClient: MockClient((request) async {
          if (request.url.queryParameters["type"] == "contacts") {
            return json(jsonEncode({
              "contacts": [
                {"id": "c1", "name": "Petra"},
              ],
            }));
          }
          return server.answer(request);
        }),
      );
      await tester.pumpWidget(localizedApp(
        Scaffold(
          body: SingleChildScrollView(
            child: ContactsSection(client: client, mayManage: true),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tapKey(tester, "contact-menu-c1");
      await tapKey(tester, "contact-shares-c1");
      expect(find.byType(SharesScreen), findsOneWidget);
      expect(find.text(testL10n.sharesScopePerson("Petra")), findsOneWidget);
      expect(groups(tester), ["person-c1"]);
    });

    testWidgets('the settings section opens the screen', (tester) async {
      var server = SharesServer();
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(localizedApp(
        Scaffold(body: SharesSection(client: clientOf(server))),
      ));
      await tapKey(tester, "settings.shares.open");
      expect(find.byType(SharesScreen), findsOneWidget);
      expect(cards(tester), ["A1", "L1", "O1", "S1"]);
    });
  });

  testWidgets('speaks German', (tester) async {
    await pumpShares(tester, SharesServer(), locale: const Locale("de"));
    expect(find.text(l10nOf(const Locale("de")).sharesHeading), findsWidgets);
    expect(
        find.text(l10nOf(const Locale("de")).sharesByPerson), findsOneWidget);
  });
}
