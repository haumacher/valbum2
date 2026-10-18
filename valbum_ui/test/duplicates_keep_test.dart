/// Tests of keeping the copies of a photo in several albums as references to
/// one of them, issue #307: the overview offers it per copy and for the whole
/// group, asks the server what it would do, names in its question how many
/// files go into the trash and which copies stay and why, and says afterwards
/// what happened.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'duplicates_actions_test.dart' show bestFolder, tripFolder;
import 'duplicates_view_test.dart'
    show authOf, copy, dataUrl, editRights, groupJson, json, list, pump, root;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String tripPath = "$tripFolder/IMG_1.jpg";
const String bestPath = "$bestFolder/beach.jpg";
const String inboxPath = "Inbox/IMG_1.jpg";

/// One photo in three albums: Trip (the first, so the original), Best of and
/// the inbox.
String overview() => list([
      groupJson("h1", [
        copy(tripFolder, "Trip", "IMG_1.jpg"),
        copy(bestFolder, "Best of", "beach.jpg"),
        copy("Inbox", "Inbox", "IMG_1.jpg"),
      ]),
    ]);

/// The refusal the server answers for the copy in the inbox.
const String inboxRefusal =
    "Only a copy in an album can be kept as a reference; sort it out of the inbox first.";

/// What the server answers: the copy in the inbox refused, every other one
/// kept ([dryRun]: would be kept) as the reference `x-2.jpg`.
String answer(Map<String, dynamic> request, {required bool dryRun}) {
  var outcomes = [
    for (var entry in request["copies"] as List)
      {
        "name": (entry as Map)["name"],
        "newName": entry["name"] == inboxPath || dryRun ? "" : "x-2.jpg",
        "message": entry["name"] == inboxPath
            ? inboxRefusal
            : dryRun
                ? ""
                : "Kept as a reference.",
      }
  ];
  return jsonEncode({"outcomes": outcomes});
}

VAlbumClient server({
  required List<http.Request> requests,
  String role = "edit",
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        var path = Uri.decodeComponent(request.url.path);
        if (isThumbnailRequest(request) || path.endsWith(".jpg")) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf(role));
        }
        if (query["type"] == "duplicates") {
          return json(overview());
        }
        var action = query["action"];
        if (action == "keep-as-references-check" ||
            action == "keep-as-references") {
          return json(answer(jsonDecode(request.body) as Map<String, dynamic>,
              dryRun: action == "keep-as-references-check"));
        }
        return json(root(editRights));
      }),
    );

/// The keep requests sent, by their action.
List<Map<String, dynamic>> sent(List<http.Request> requests, String action) => [
      for (var request in requests)
        if (request.method == "POST" &&
            request.url.queryParameters["action"] == action)
          jsonDecode(request.body) as Map<String, dynamic>,
    ];

List<String> copiesOf(Map<String, dynamic> request) => [
      for (var entry in request["copies"] as List)
        (entry as Map)["name"] as String,
    ];

int duplicatesAsked(List<http.Request> requests) => requests
    .where((request) => request.url.queryParameters["type"] == "duplicates")
    .length;

void main() {
  testWidgets('is offered to an editor per copy and for the group',
      (tester) async {
    await pump(tester, server(requests: []));

    expect(
        find.byKey(const ValueKey("duplicates-keep-$tripPath")), findsNothing,
        reason: "The original is no copy to replace.");
    expect(find.byKey(const ValueKey("duplicates-keep-$bestPath")),
        findsOneWidget);
    expect(find.byKey(const ValueKey("duplicates-keep-$inboxPath")),
        findsOneWidget);
    expect(
        find.byTooltip(testL10n.duplicatesKeepAsReference), findsNWidgets(2));
    expect(find.byTooltip(testL10n.duplicatesOriginal), findsOneWidget);
    expect(
        find.byKey(const ValueKey("duplicates-keep-all-h1")), findsOneWidget);
    expect(find.text(testL10n.duplicatesKeepAllAsReferences), findsOneWidget);
  });

  testWidgets('is not offered to a viewer', (tester) async {
    await pump(tester, server(requests: [], role: "view"));

    expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
    expect(find.byTooltip(testL10n.duplicatesKeepAsReference), findsNothing);
    expect(find.byKey(const ValueKey("duplicates-keep-all-h1")), findsNothing);
  });

  testWidgets(
      'one copy: asks the server, names the file, sends nothing on '
      'Cancel', (tester) async {
    var requests = <http.Request>[];
    await pump(tester, server(requests: requests));

    await tester.tap(find.byKey(const ValueKey("duplicates-keep-$bestPath")));
    await tester.pumpAndSettle();

    var check = sent(requests, "keep-as-references-check").single;
    expect(check["original"], tripPath);
    expect(copiesOf(check), [bestPath]);
    expect(find.byKey(const Key("duplicates-keep-dialog")), findsOneWidget);
    expect(
        find.text(testL10n.duplicatesKeepQuestion(
            testL10n.keptAlbumName("Best of"), "Trip", 1)),
        findsOneWidget);
    expect(find.byKey(const Key("duplicates-keep-refused")), findsNothing);

    await tester.tap(find.byKey(const Key("duplicates-keep-cancel")));
    await tester.pumpAndSettle();

    expect(sent(requests, "keep-as-references"), isEmpty);
    expect(duplicatesAsked(requests), 1);
  });

  testWidgets('one copy: keeps it on the word and says so', (tester) async {
    var requests = <http.Request>[];
    await pump(tester, server(requests: requests));

    await tester.tap(find.byKey(const ValueKey("duplicates-keep-$bestPath")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("duplicates-keep-confirm")));
    await tester.pumpAndSettle();

    var done = sent(requests, "keep-as-references").single;
    expect(done["original"], tripPath);
    expect(copiesOf(done), [bestPath]);
    expect(find.text(testL10n.duplicatesKept(1)), findsOneWidget);
    expect(duplicatesAsked(requests), 2,
        reason: "The overview is asked again.");
  });

  testWidgets('all others: says which copy stays and why, and keeps the rest',
      (tester) async {
    var requests = <http.Request>[];
    await pump(tester, server(requests: requests));

    await tester.tap(find.byKey(const ValueKey("duplicates-keep-all-h1")));
    await tester.pumpAndSettle();

    expect(copiesOf(sent(requests, "keep-as-references-check").single),
        [bestPath, inboxPath]);
    expect(
        find.text(testL10n.duplicatesKeepQuestion(
            testL10n.keptAlbumName("Best of"), "Trip", 1)),
        findsOneWidget,
        reason: "One file goes into the trash.");
    expect(find.text(testL10n.duplicatesKeepRefused(1)), findsOneWidget);
    expect(find.text(testL10n.duplicatesKeepReason(inboxRefusal, "Inbox")),
        findsOneWidget);

    await tester.tap(find.byKey(const Key("duplicates-keep-confirm")));
    await tester.pumpAndSettle();

    expect(copiesOf(sent(requests, "keep-as-references").single), [bestPath],
        reason: "Only what the server would keep is asked for.");
    expect(find.text(testL10n.duplicatesKept(1)), findsOneWidget);
  });

  testWidgets('nothing to keep: the question only says why, and closes',
      (tester) async {
    var requests = <http.Request>[];
    await pump(tester, server(requests: requests));

    await tester.tap(find.byKey(const ValueKey("duplicates-keep-$inboxPath")));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key("duplicates-keep-question")), findsNothing);
    expect(find.byKey(const Key("duplicates-keep-confirm")), findsNothing);
    expect(find.text(testL10n.duplicatesKeepReason(inboxRefusal, "Inbox")),
        findsOneWidget);
    expect(find.text(testL10n.close), findsOneWidget);

    await tester.tap(find.byKey(const Key("duplicates-keep-cancel")));
    await tester.pumpAndSettle();
    expect(sent(requests, "keep-as-references"), isEmpty);
  });

  testWidgets('another copy chosen as the original is kept instead',
      (tester) async {
    var requests = <http.Request>[];
    await pump(tester, server(requests: requests));

    await tester
        .tap(find.byKey(const ValueKey("duplicates-original-$bestPath")));
    await tester.pumpAndSettle();

    expect(
        find.byKey(const ValueKey("duplicates-keep-$bestPath")), findsNothing);
    expect(find.byKey(const ValueKey("duplicates-keep-$tripPath")),
        findsOneWidget);

    await tester.tap(find.byKey(const ValueKey("duplicates-keep-all-h1")));
    await tester.pumpAndSettle();

    var check = sent(requests, "keep-as-references-check").single;
    expect(check["original"], bestPath);
    expect(copiesOf(check), [tripPath, inboxPath]);
    expect(
        find.text(testL10n.duplicatesKeepQuestion(
            testL10n.keptAlbumName("Trip"), "Best of", 1)),
        findsOneWidget);
  });
}
