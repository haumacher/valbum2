/// The screens of the upload target of issue #240: choosing the album with
/// the move picker (limited to albums the member may add photos to) and the
/// end date, the line on the start page with its one tap back to the inbox,
/// and the upload into the inbox that follows the target.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/notices.dart';
import 'package:valbum_ui/upload_target.dart';
import 'package:valbum_ui/upload_target_view.dart';

import 'inbox_view_test.dart' show inboxJson;
import 'upload_test.dart' show fileNamed;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String serverDataUrl = "http://server/valbum/data";

/// Wednesday, 7 October 2026, at noon.
final DateTime today = DateTime(2026, 10, 7, 12);

const CallerInfo member =
    CallerInfo(role: roleMember, space: "carol", inbox: "Inbox");

http.Response json(String body) => http.Response(body, 200,
    headers: {"content-type": "application/json; charset=utf-8"});

String rightsOf(List<String> rights) =>
    '[${rights.map((r) => '{"name": "$r"}').join(", ")}]';

/// The tree the picker browses: the root, a year folder, and two albums in
/// it — one the member may add to, one they may only look at.
http.Response tree(http.Request request) {
  switch (Uri.decodeFull(request.url.path)) {
    case "/valbum/data/":
      return json('["ListingInfo", {"path": "", "title": "Library", '
          '"rights": ${rightsOf(["contribute"])}, '
          '"folders": [{"name": "2026", "title": "2026"}]}]');
    case "/valbum/data/2026/":
      return json('["ListingInfo", {"path": "2026", "title": "2026", '
          '"rights": ${rightsOf(["contribute"])}, '
          '"folders": [{"name": "Mallorca 2026", "title": "Mallorca 2026"}, '
          '{"name": "Rome", "title": "Rome"}]}]');
    case "/valbum/data/2026/Mallorca 2026/":
      return json('["AlbumInfo", {"path": "2026/Mallorca 2026", '
          '"title": "Mallorca 2026", "rights": ${rightsOf(["contribute"])}, '
          '"parts": []}]');
    case "/valbum/data/2026/Rome/":
      return json('["AlbumInfo", {"path": "2026/Rome", "title": "Rome", '
          '"rights": ${rightsOf(["view"])}, "parts": []}]');
  }
  return http.Response("No such resource", 404);
}

/// Pumps the settings section on its own, as [member].
Future<UploadTargets> pumpSection(
  WidgetTester tester, {
  InMemorySettingsStore? store,
  Locale locale = defaultTestLocale,
  VAlbumClient? client,
}) async {
  var targets = UploadTargets(
    store: store ?? InMemorySettingsStore(),
    clock: () => today,
  );
  await targets.load();
  addTearDown(targets.dispose);
  await tester.pumpWidget(localizedApp(
    CallerScope(
      caller: member,
      child: UploadTargetScope(
        targets: targets,
        client: client ??
            VAlbumClient(
              dataUrl: serverDataUrl,
              httpClient: MockClient((request) async => tree(request)),
            ),
        child: const Scaffold(
          body: SingleChildScrollView(child: UploadTargetSection()),
        ),
      ),
    ),
    locale: locale,
  ));
  await tester.pumpAndSettle();
  return targets;
}

Future<void> tapKey(WidgetTester tester, Key key) async {
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

/// Opens the picker from the section and walks to [album] in 2026.
Future<void> pickAlbum(WidgetTester tester, String album) async {
  await tapKey(tester, uploadTargetChooseKey);
  await tapKey(tester, const Key("picker-folder-2026"));
  await tapKey(tester, Key("picker-folder-$album"));
}

ElevatedButton confirm(WidgetTester tester) =>
    tester.widget<ElevatedButton>(find.byKey(const Key("picker-confirm")));

void main() {
  group('choosing the target', () {
    testWidgets('offers only an album the member may add photos to',
        (tester) async {
      await pumpSection(tester);
      expect(find.text(testL10n.uploadTargetToInbox), findsOneWidget);

      await pickAlbum(tester, "Rome");
      expect(find.byKey(const Key("picker-no-contribute")), findsOneWidget);
      expect(confirm(tester).onPressed, isNull,
          reason: "an album that refuses the upload is no target");

      await tapKey(tester, const Key("picker-up"));
      // A folder of folders is no target either.
      expect(confirm(tester).onPressed, isNull);
      await tapKey(tester, const Key("picker-folder-Mallorca 2026"));
      expect(find.byKey(const Key("picker-no-contribute")), findsNothing);
      expect(confirm(tester).onPressed, isNotNull);
    });

    testWidgets('keeps it on the device, per server, ending in a week',
        (tester) async {
      var store = InMemorySettingsStore();
      var targets = await pumpSection(tester, store: store);

      await pickAlbum(tester, "Mallorca 2026");
      await tapKey(tester, const Key("picker-confirm"));
      // The end date is offered one week ahead.
      var week = uploadTargetDay(DateTime(2026, 10, 14), testL10n);
      expect(find.text(testL10n.uploadTargetUntil(week)), findsOneWidget);
      await tapKey(tester, uploadTargetSaveKey);

      expect(
        targets.targetOf(serverDataUrl),
        UploadTarget(
          path: const ["2026", "Mallorca 2026"],
          title: "Mallorca 2026",
          until: DateTime(2026, 10, 14),
        ),
      );
      expect(jsonDecode(store.uploadTargets!).keys, [serverDataUrl]);
      expect(targets.targetOf("http://other/valbum/data"), isNull);
      expect(
        find.text(testL10n.uploadTargetLineUntil("Mallorca 2026", week)),
        findsOneWidget,
      );
    });

    testWidgets('may have no end date', (tester) async {
      var targets = await pumpSection(tester);

      await pickAlbum(tester, "Mallorca 2026");
      await tapKey(tester, const Key("picker-confirm"));
      await tapKey(tester, uploadTargetNoEndKey);
      expect(find.text(testL10n.uploadTargetNoEnd), findsWidgets);
      await tapKey(tester, uploadTargetSaveKey);

      expect(targets.targetOf(serverDataUrl)?.until, isNull);
      expect(find.text(testL10n.uploadTargetLine("Mallorca 2026")),
          findsOneWidget);
    });

    testWidgets('cancelled keeps the inbox', (tester) async {
      var targets = await pumpSection(tester);

      await pickAlbum(tester, "Mallorca 2026");
      await tapKey(tester, const Key("picker-confirm"));
      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();

      expect(targets.targetOf(serverDataUrl), isNull);
    });

    testWidgets('changes the end date and goes back to the inbox',
        (tester) async {
      var store = InMemorySettingsStore()
        ..uploadTargets = jsonEncode({
          serverDataUrl: UploadTarget(
            path: const ["2026", "Mallorca 2026"],
            title: "Mallorca 2026",
            until: DateTime(2026, 10, 14),
          ).toJson()
        });
      var targets = await pumpSection(tester, store: store);

      await tapKey(tester, uploadTargetChangeEndKey);
      await tapKey(tester, uploadTargetNoEndKey);
      await tapKey(tester, uploadTargetSaveKey);
      expect(targets.targetOf(serverDataUrl)?.until, isNull);

      await tapKey(tester, uploadTargetResetKey);
      expect(targets.targetOf(serverDataUrl), isNull);
      expect(find.text(testL10n.uploadTargetToInbox), findsOneWidget);
    });

    testWidgets('is not offered without a server of the device (a share link)',
        (tester) async {
      var targets = UploadTargets(store: InMemorySettingsStore());
      addTearDown(targets.dispose);
      await tester.pumpWidget(localizedApp(
        UploadTargetScope(
          targets: targets,
          client: null,
          child: const CallerScope(
            caller: member,
            child: Scaffold(
              body: Column(
                children: [UploadTargetSection(), UploadTargetBanner()],
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(uploadTargetSectionKey), findsNothing);
      expect(find.byKey(uploadTargetLineKey), findsNothing);
    });
  });

  group('in the app', () {
    /// The app signed in as a member, over a store holding [targets].
    Future<({InMemorySettingsStore store, List<http.Request> requests})>
        pumpApp(
      WidgetTester tester, {
      String? targets,
      VAlbumRoute? initialRoute,
      http.Response Function(http.Request request)? onPut,
    }) async {
      var requests = <http.Request>[];
      var store = InMemorySettingsStore(
          "http://server/valbum/", "dev-1", "Phone", "carol")
        ..uploadTargets = targets;
      var settings = ServerSettings(store: store);
      await settings.load();
      var client = VAlbumClient(
        dataUrl: serverDataUrl,
        token: "dev-1",
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          if (request.url.queryParameters["type"] == "auth") {
            return json('{"mode": "writes", "userName": "carol", '
                '"role": "member", "space": "carol", "inbox": "Inbox", '
                '"inboxCount": 0}');
          }
          if (request.method == "POST") {
            return json('{"present": []}');
          }
          if (request.method == "PUT") {
            return onPut?.call(request) ?? http.Response("", 200);
          }
          if (Uri.decodeFull(request.url.path) == "/valbum/data/Inbox/") {
            return json(inboxJson(rights: const ["contribute"]));
          }
          return http.Response(fixture("listing.json"), 200);
        })),
      );
      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          settings: settings,
          initialRoute: initialRoute,
        ));
        await tester.pumpAndSettle();
      });
      return (store: store, requests: requests);
    }

    String stored(UploadTarget target) =>
        jsonEncode({serverDataUrl: target.toJson()});

    testWidgets(
        'the start page names the target, and one tap goes back to '
        'the inbox', (tester) async {
      var until = DateTime.now().add(const Duration(days: 3));
      var day = DateTime(until.year, until.month, until.day);
      var (:store, requests: _) = await pumpApp(tester,
          targets: stored(UploadTarget(
              path: const ["2026", "Mallorca 2026"],
              title: "Mallorca 2026",
              until: day)));

      expect(
        find.text(testL10n.uploadTargetLineUntil(
            "Mallorca 2026", uploadTargetDay(day, testL10n))),
        findsOneWidget,
      );

      await tapKey(tester, uploadTargetResetKey);

      expect(find.byKey(uploadTargetLineKey), findsNothing);
      expect(store.uploadTargets, "{}");
    });

    testWidgets('the start page says once why photos go to the inbox again',
        (tester) async {
      var (:store, requests: _) = await pumpApp(tester,
          targets: jsonEncode({
            serverDataUrl: {
              "ended": {"why": "gone", "album": "Mallorca 2026"}
            }
          }));

      expect(
        find.text(
            noticeText(const UploadTargetGone("Mallorca 2026"), testL10n)),
        findsOneWidget,
      );
      await tapKey(tester, uploadTargetDismissKey);
      expect(find.byKey(uploadTargetEndedKey), findsNothing);
      expect(store.uploadTargets, "{}");
    });

    testWidgets('nothing is shown while photos go to the inbox',
        (tester) async {
      await pumpApp(tester);
      expect(find.byKey(uploadTargetLineKey), findsNothing);
      expect(find.byKey(uploadTargetEndedKey), findsNothing);
    });

    testWidgets('an upload into the inbox goes to the target', (tester) async {
      var (store: _, :requests) = await pumpApp(
        tester,
        targets: stored(const UploadTarget(
            path: ["2026", "Mallorca 2026"], title: "Mallorca 2026")),
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
      );

      await withFakeImageHttp(() async {
        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([fileNamed("a.jpg", "a".codeUnits)]);
        await tester.pumpAndSettle();
      });

      expect(
        [
          for (var r in requests)
            if (r.method == "PUT") Uri.decodeFull(r.url.path)
        ],
        ["/valbum/data/2026/Mallorca 2026/"],
      );
      expect(find.textContaining(testL10n.uploadTargetWentTo("Mallorca 2026")),
          findsOneWidget);
    });

    testWidgets(
        'an upload into the inbox whose target is gone lands in the '
        'inbox and says why', (tester) async {
      var (:store, :requests) = await pumpApp(
        tester,
        targets: stored(const UploadTarget(
            path: ["2026", "Mallorca 2026"], title: "Mallorca 2026")),
        initialRoute: const ListingOrAlbumRoute(["Inbox"]),
        onPut: (request) => request.url.path.contains("Mallorca")
            ? http.Response('["ErrorInfo",{"message":"Not found."}]', 404)
            : http.Response("", 200),
      );

      await withFakeImageHttp(() async {
        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([fileNamed("a.jpg", "a".codeUnits)]);
        await tester.pumpAndSettle();
      });

      expect(
        [
          for (var r in requests)
            if (r.method == "PUT") Uri.decodeFull(r.url.path)
        ],
        ["/valbum/data/2026/Mallorca 2026/", "/valbum/data/Inbox/"],
      );
      expect(
        find.textContaining(
            noticeText(const UploadTargetGone("Mallorca 2026"), testL10n)),
        findsOneWidget,
      );
      expect(jsonDecode(store.uploadTargets!)[serverDataUrl]["path"], isNull);
    });

    testWidgets('an upload into another album ignores the target',
        (tester) async {
      var (store: _, :requests) = await pumpApp(
        tester,
        targets: stored(const UploadTarget(path: ["2026", "Mallorca 2026"])),
        initialRoute: const ListingOrAlbumRoute(["2026", "Rome"]),
      );

      await withFakeImageHttp(() async {
        var state = tester.state<VAlbumState>(find.byType(VAlbumView));
        await state.uploadPicked([fileNamed("a.jpg", "a".codeUnits)]);
        await tester.pumpAndSettle();
      });

      expect(
        [
          for (var r in requests)
            if (r.method == "PUT") Uri.decodeFull(r.url.path)
        ],
        ["/valbum/data/2026/Rome/"],
      );
    });
  });

  testWidgets('the section and the start page speak German', (tester) async {
    var de = l10nOf(const Locale("de"));
    var en = l10nOf(const Locale("en"));
    var store = InMemorySettingsStore()
      ..uploadTargets = jsonEncode({
        serverDataUrl: UploadTarget(
          path: const ["2026", "Mallorca 2026"],
          title: "Mallorca 2026",
          until: DateTime(2026, 10, 14),
        ).toJson()
      });
    await pumpSection(tester, store: store, locale: const Locale("de"));

    var day = uploadTargetDay(DateTime(2026, 10, 14), de);
    expect(day, isNot(uploadTargetDay(DateTime(2026, 10, 14), en)),
        reason: "the date is written in the app's locale");
    expect(find.text(de.uploadTargetHeading), findsOneWidget);
    expect(find.text(de.uploadTargetLineUntil("Mallorca 2026", day)),
        findsOneWidget);
    expect(find.text(de.uploadTargetBackToInbox), findsOneWidget);
    expect(find.text(en.uploadTargetHeading), findsNothing);
    for (var (german, english) in [
      (de.uploadTargetExplanation, en.uploadTargetExplanation),
      (de.uploadTargetToInbox, en.uploadTargetToInbox),
      (de.uploadTargetPickerTitle, en.uploadTargetPickerTitle),
      (de.uploadTargetNoContribute, en.uploadTargetNoContribute),
      (de.uploadTargetEndExplanation, en.uploadTargetEndExplanation),
      (de.uploadTargetWentTo("M"), en.uploadTargetWentTo("M")),
      (
        noticeText(UploadTargetExpired("M", DateTime(2026, 10, 14)), de),
        noticeText(UploadTargetExpired("M", DateTime(2026, 10, 14)), en)
      ),
      (
        noticeText(const UploadTargetGone("M"), de),
        noticeText(const UploadTargetGone("M"), en)
      ),
      (
        noticeText(const UploadTargetRefused("M"), de),
        noticeText(const UploadTargetRefused("M"), en)
      ),
      (
        noticeText(const UploadTargetNotAlbum("M"), de),
        noticeText(const UploadTargetNotAlbum("M"), en)
      ),
      (
        noticeText(const UploadTargetFollowed("A", "B"), de),
        noticeText(const UploadTargetFollowed("A", "B"), en)
      ),
    ]) {
      expect(german, isNot(english));
    }
    // The placeholders land where the German sentence puts them.
    expect(
        noticeText(UploadTargetExpired("Mallorca", DateTime(2026, 10, 14)), de),
        allOf(contains("Mallorca"), contains(day)));
    expect(noticeText(const UploadTargetFollowed("Alt", "Neu"), de),
        matches(RegExp("Alt.*Neu")));
  });
}
