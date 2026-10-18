/// Tests of the space settings an administrator makes in the app (issue
/// #297): the page is offered to the administrator and nobody else, a save
/// posts what was edited to the data root of the space, a refusal is said in
/// the server's words, and nothing is sent while the app is offline.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/offline.dart';
import 'package:valbum_ui/settings.dart';
import 'package:valbum_ui/space_settings_view.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String serverUrl = "http://server/valbum/";

/// The data root of a space whose name needs percent-encoding.
const String spaceDataUrl = "http://server/valbum/Fam%C3%ADlia/data";

/// A JSON answer of the server.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a signed-in caller of the given role.
String authOfUser(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "carol"}';

/// The settings as the server answers them.
String settingsOf({
  String name = "The Family",
  String anonymous = "NONE",
  String anonymousFixed = "",
  bool faces = false,
  String facesUnavailable = "",
  String mapUrl = "https://www.google.com/maps?q={lat},{lon}",
  String timeZone = "",
  String inbox = "Inbox",
  String inboxFixed = "",
  String? content,
  String contentUnavailable = "",
  Map<String, double>? thresholds,
}) =>
    jsonEncode({
      "name": name,
      "anonymous": anonymous,
      "anonymousFixed": anonymousFixed,
      "faces": faces,
      "facesUnavailable": facesUnavailable,
      "mapUrl": mapUrl,
      "defaultMapUrl": "https://www.google.com/maps?q={lat},{lon}",
      "timeZone": timeZone,
      "serverTimeZone": "UTC",
      "inbox": inbox,
      "inboxFixed": inboxFixed,
      // A server of an earlier release answers no switch of the content search.
      if (content != null) "content": content,
      if (content != null) "contentUnavailable": contentUnavailable,
      // A server before issue #302 answers no cuts.
      if (thresholds != null)
        "thresholds": {
          "text": 0.0,
          "similar": 0.0,
          "defaultText": 0.05,
          "defaultSimilar": 0.3,
          ...thresholds,
        },
    });

/// Pumps the page alone, asking the given transport.
Future<void> pumpPage(WidgetTester tester, http.Client transport,
    {OfflineState? offline}) async {
  await tester.binding.setSurfaceSize(const Size(800, 2000));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  Widget page = SpaceSettingsScreen(
    client: VAlbumClient(dataUrl: spaceDataUrl, httpClient: transport),
  );
  if (offline != null) {
    page =
        OfflineScope(state: offline, cache: MemoryOfflineCache(), child: page);
  }
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: page,
    ),
  );
  await tester.pumpAndSettle();
}

/// Pumps the whole settings screen of a device signed in as the given role.
Future<void> pumpSettings(
    WidgetTester tester, String role, List<http.Request> requests) async {
  await tester.binding.setSurfaceSize(const Size(800, 3600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  var settings = ServerSettings(
      store: InMemorySettingsStore(serverUrl, "dev-2", "Phone", "carol"));
  await settings.load();
  var transport = MockClient((request) async {
    requests.add(request);
    var query = request.url.queryParameters;
    if (query["type"] == "auth") {
      return json(authOfUser(role));
    }
    if (query["type"] == "space-settings") {
      return json(settingsOf());
    }
    if (query["type"] == "catch-up") {
      return json('{"albumsDone": 1, "albumsTotal": 1, "step": "IDLE"}');
    }
    if (query["type"] == "invitations") {
      return json('{"invitations": []}');
    }
    if (query["type"] == "devices") {
      return json('{"devices": []}');
    }
    if (query["type"] == "contacts") {
      return json('{"contacts": []}');
    }
    return json('{"users": []}');
  });
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('an administrator finds the page in the server settings',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "admin", requests);
    expect(find.byKey(spaceSettingsSectionKey), findsOneWidget);

    await tester.ensureVisible(find.byKey(spaceSettingsOpenKey));
    await tester.tap(find.byKey(spaceSettingsOpenKey));
    await tester.pumpAndSettle();
    expect(find.byType(SpaceSettingsScreen), findsOneWidget);
    expect(
        requests
            .where((r) => r.url.queryParameters["type"] == "space-settings"),
        isNotEmpty);
    expect(find.widgetWithText(TextField, "The Family"), findsOneWidget);
  });

  testWidgets('a member is not offered the page, and nothing is asked',
      (tester) async {
    var requests = <http.Request>[];
    await pumpSettings(tester, "edit", requests);
    expect(find.byKey(spaceSettingsSectionKey), findsNothing);
    expect(
        requests
            .where((r) => r.url.queryParameters["type"] == "space-settings"),
        isEmpty);
  });

  testWidgets('shows each switch with what it does and costs', (tester) async {
    await pumpPage(tester, MockClient((request) async => json(settingsOf())));
    expect(find.text(testL10n.spaceSettingsAnonymous), findsOneWidget);
    expect(find.text(testL10n.spaceSettingsAnonymousHelp), findsOneWidget);
    expect(find.text(testL10n.spaceSettingsFacesHelp), findsOneWidget);
    expect(
        find.text(testL10n.spaceSettingsTimeZoneHelp("UTC")), findsOneWidget);
    // The default map is an empty field, so that it keeps following the default.
    var map = tester.widget<TextField>(find.byKey(spaceSettingsMapKey));
    expect(map.controller!.text, "");
  });

  testWidgets('a save posts what was edited to the data root of the space',
      (tester) async {
    var posts = <http.Request>[];
    await pumpPage(
      tester,
      MockClient((request) async {
        if (request.method == "POST") {
          posts.add(request);
          var sent = jsonDecode(request.body) as Map<String, dynamic>;
          return json(settingsOf(
            name: sent["name"] as String,
            faces: sent["faces"] as bool,
            anonymous: sent["anonymous"] as String,
            timeZone: sent["timeZone"] as String,
            mapUrl: sent["mapUrl"] as String,
          ));
        }
        return json(settingsOf());
      }),
    );

    await tester.enterText(find.byKey(spaceSettingsNameKey), "Haumachers");
    await tester.tap(find.byKey(spaceSettingsFacesKey));
    await tester.tap(find.byKey(spaceSettingsAnonymousKey));
    await tester.enterText(
        find.byKey(spaceSettingsTimeZoneKey), "Europe/Berlin");
    await tester.enterText(find.byKey(spaceSettingsMapKey),
        "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}");
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();

    expect(posts, hasLength(1));
    var post = posts.single;
    expect(post.url.path, "/valbum/Fam%C3%ADlia/data/",
        reason: "The data root of the space, spelled as the server named it.");
    expect(post.url.queryParameters, {"action": "space-settings"});
    var body = jsonDecode(post.body) as Map<String, dynamic>;
    expect(body["name"], "Haumachers");
    expect(body["faces"], true);
    expect(body["anonymous"], "PUBLIC");
    expect(body["timeZone"], "Europe/Berlin");
    expect(
        body["mapUrl"], "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}");
    expect(body["inbox"], "Inbox");
    expect(find.text(testL10n.spaceSettingsSaved), findsOneWidget);
  });

  testWidgets('the content search is switched on with the right body',
      (tester) async {
    var posts = <Map<String, dynamic>>[];
    await pumpPage(
      tester,
      MockClient((request) async {
        if (request.method == "POST") {
          var sent = jsonDecode(request.body) as Map<String, dynamic>;
          posts.add(sent);
          return json(settingsOf(content: sent["content"] as String));
        }
        return json(settingsOf(content: "OFF"));
      }),
    );
    expect(find.text(testL10n.spaceSettingsContent), findsOneWidget);
    expect(find.text(testL10n.spaceSettingsContentHelp), findsOneWidget);
    expect(
        find.descendant(
            of: find.byKey(spaceSettingsContentStatusKey),
            matching: find.text(testL10n.spaceSettingsContentReady)),
        findsOneWidget);
    expect(
        tester
            .widget<SwitchListTile>(find.byKey(spaceSettingsContentKey))
            .value,
        isFalse);

    await tester.tap(find.byKey(spaceSettingsContentKey));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();
    expect(posts, hasLength(1));
    expect(posts.single["content"], "ON");
    expect(posts.single["faces"], false,
        reason: "The faces are another switch.");
    expect(
        tester
            .widget<SwitchListTile>(find.byKey(spaceSettingsContentKey))
            .value,
        isTrue);

    await tester.tap(find.byKey(spaceSettingsContentKey));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();
    expect(posts.last["content"], "OFF");
  });

  testWidgets(
      'says why the embedder cannot describe photos, in the server\'s '
      'words', (tester) async {
    const reason =
        "the embedder service at http://pi:8091/ is downloading the model";
    await pumpPage(
        tester,
        MockClient((request) async =>
            json(settingsOf(content: "ON", contentUnavailable: reason))));
    expect(
        tester
            .widget<SwitchListTile>(find.byKey(spaceSettingsContentKey))
            .value,
        isTrue);
    expect(
        find.descendant(
            of: find.byKey(spaceSettingsContentStatusKey),
            matching:
                find.text(testL10n.spaceSettingsContentUnavailable(reason))),
        findsOneWidget);
    expect(find.text(testL10n.spaceSettingsContentReady), findsNothing);
  });

  testWidgets('a server without the switch is shown none, and sent none',
      (tester) async {
    var posts = <Map<String, dynamic>>[];
    await pumpPage(
      tester,
      MockClient((request) async {
        if (request.method == "POST") {
          posts.add(jsonDecode(request.body) as Map<String, dynamic>);
        }
        return json(settingsOf());
      }),
    );
    expect(find.byKey(spaceSettingsContentKey), findsNothing);
    expect(find.byKey(spaceSettingsContentStatusKey), findsNothing);
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();
    expect(posts, hasLength(1));
    expect(posts.single["content"] ?? "KEEP", "KEEP",
        reason: "The space keeps what it says.");
  });

  group('the cuts of the content search (issue #302)', () {
    testWidgets('are offered only where the content search is on',
        (tester) async {
      await pumpPage(
          tester,
          MockClient((request) async =>
              json(settingsOf(content: "OFF", thresholds: const {}))));
      expect(find.byKey(spaceSettingsTextThresholdKey), findsNothing);
      expect(find.byKey(spaceSettingsSimilarThresholdKey), findsNothing);

      await tester.tap(find.byKey(spaceSettingsContentKey));
      await tester.pumpAndSettle();
      expect(find.byKey(spaceSettingsTextThresholdKey), findsOneWidget);
      expect(find.byKey(spaceSettingsSimilarThresholdKey), findsOneWidget);
      expect(find.text(testL10n.spaceSettingsThresholdHelp("0.05")),
          findsOneWidget,
          reason: "The built-in cut is what an empty field means.");
    });

    testWidgets('show the space\'s and save what was edited', (tester) async {
      var posts = <Map<String, dynamic>>[];
      await pumpPage(
        tester,
        MockClient((request) async {
          if (request.method == "POST") {
            var sent = jsonDecode(request.body) as Map<String, dynamic>;
            posts.add(sent);
            var cuts = sent["thresholds"] as Map<String, dynamic>;
            return json(settingsOf(content: "ON", thresholds: {
              "text": (cuts["text"] as num).toDouble(),
              "similar": (cuts["similar"] as num).toDouble(),
            }));
          }
          return json(settingsOf(content: "ON", thresholds: {"similar": 0.6}));
        }),
      );
      String field(Key key) => tester
          .widget<TextField>(find.byKey(key))
          .controller!
          .text;
      expect(field(spaceSettingsTextThresholdKey), "",
          reason: "None of its own: the built-in one.");
      expect(field(spaceSettingsSimilarThresholdKey), "0.60");

      await tester.enterText(find.byKey(spaceSettingsTextThresholdKey), "0,12");
      await tester.enterText(find.byKey(spaceSettingsSimilarThresholdKey), "");
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(spaceSettingsSaveKey));
      await tester.pumpAndSettle();
      expect(posts, hasLength(1));
      expect(posts.single["thresholds"]["text"], 0.12);
      expect(posts.single["thresholds"]["similar"], 0,
          reason: "Empty takes the space's cut away.");
      expect(field(spaceSettingsTextThresholdKey), "0.12");
      expect(field(spaceSettingsSimilarThresholdKey), "");
    });

    testWidgets('a value that is no cut is said and not sent', (tester) async {
      var posts = <http.Request>[];
      await pumpPage(
        tester,
        MockClient((request) async {
          if (request.method == "POST") {
            posts.add(request);
          }
          return json(settingsOf(content: "ON", thresholds: const {}));
        }),
      );
      await tester.enterText(find.byKey(spaceSettingsTextThresholdKey), "1.5");
      await tester.pumpAndSettle();
      expect(find.text(testL10n.spaceSettingsThresholdInvalid), findsOneWidget);
      await tester.tap(find.byKey(spaceSettingsSaveKey));
      await tester.pumpAndSettle();
      expect(posts, isEmpty);
    });

    testWidgets('a server without them is shown none, and sent none',
        (tester) async {
      var posts = <Map<String, dynamic>>[];
      await pumpPage(
        tester,
        MockClient((request) async {
          if (request.method == "POST") {
            posts.add(jsonDecode(request.body) as Map<String, dynamic>);
          }
          return json(settingsOf(content: "ON"));
        }),
      );
      expect(find.byKey(spaceSettingsTextThresholdKey), findsNothing);
      await tester.tap(find.byKey(spaceSettingsSaveKey));
      await tester.pumpAndSettle();
      expect(posts.single.containsKey("thresholds"), isFalse,
          reason: "The space keeps what it says.");
    });

    test('read with a comma or a point', () {
      expect(SpaceSettingsScreenState.parseThreshold("0,45"), 0.45);
      expect(SpaceSettingsScreenState.parseThreshold(" 0.3 "), 0.3);
      expect(SpaceSettingsScreenState.parseThreshold(""), 0);
      expect(SpaceSettingsScreenState.parseThreshold("-0.1"), isNull);
      expect(SpaceSettingsScreenState.parseThreshold("high"), isNull);
    });
  });

  testWidgets('a refusal is shown in the server\'s words', (tester) async {
    const reason = "'Mars/Olympus' is no time zone: give an IANA zone id "
        "such as 'Europe/Berlin' or 'UTC'.";
    await pumpPage(
      tester,
      MockClient((request) async {
        if (request.method == "POST") {
          return json('["ErrorInfo", {"message": ${jsonEncode(reason)}}]',
              status: 400);
        }
        return json(settingsOf());
      }),
    );
    await tester.enterText(
        find.byKey(spaceSettingsTimeZoneKey), "Mars/Olympus");
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();
    expect(find.byKey(spaceSettingsProblemKey), findsOneWidget);
    expect(find.text(testL10n.spaceSettingsRefused(reason)), findsOneWidget);
    var field = tester.widget<TextField>(find.byKey(spaceSettingsTimeZoneKey));
    expect(field.controller!.text, "Mars/Olympus",
        reason: "What was typed stays, so that it can be corrected.");
  });

  testWidgets('a reading refusal is said, with a way to ask again',
      (tester) async {
    await pumpPage(
        tester,
        MockClient((request) async => json(
            '["ErrorInfo", {"message": "The settings of this space are made '
            'by its administrator."}]',
            status: 403)));
    expect(
        find.text(testL10n.spaceSettingsUnavailable(
            "The settings of this space are made by its administrator.")),
        findsOneWidget);
    expect(find.text(testL10n.retry), findsOneWidget);
  });

  testWidgets('what the server decides is shown, and not offered',
      (tester) async {
    const fixed = "This server serves one library.";
    const holds = "The inbox holds photos.";
    await pumpPage(
        tester,
        MockClient((request) async => json(settingsOf(
            anonymousFixed: fixed,
            facesUnavailable: "No detector here.",
            inboxFixed: holds))));
    var anonymous =
        tester.widget<SwitchListTile>(find.byKey(spaceSettingsAnonymousKey));
    expect(anonymous.onChanged, isNull);
    expect(find.textContaining(fixed), findsOneWidget);
    expect(find.textContaining("No detector here."), findsOneWidget);
    var inbox = tester.widget<TextField>(find.byKey(spaceSettingsInboxKey));
    expect(inbox.enabled, isFalse);
    expect(find.text(holds), findsOneWidget);
  });

  testWidgets('nothing is sent while the app is offline', (tester) async {
    var posts = <http.Request>[];
    var offline = OfflineState();
    await pumpPage(
      tester,
      MockClient((request) async {
        if (request.method == "POST") {
          posts.add(request);
        }
        return json(settingsOf());
      }),
      offline: offline,
    );
    offline.goneOffline(null);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(spaceSettingsNameKey), "Offline");
    await tester.tap(find.byKey(spaceSettingsSaveKey));
    await tester.pumpAndSettle();
    expect(posts, isEmpty);
    expect(find.text(testL10n.offlineRefusal), findsOneWidget);
  });
}
