/// "Re-read photo details" in the album menu, the app half of issue #161.
///
/// A part a sidecar already lists is never analysed again, so an album
/// described before the camera and the position existed carries neither; the
/// entry has the server read the files again and fill in what is missing.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

const List<String> editor = ["view", "download", "contribute", "edit"];

/// An album of one image, answered with the given rights.
String albumWith(List<String> rights) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "rights": ['
    '${rights.map((right) => '{"name": "$right"}').join(",")}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

http.Response json(String body, [int status = 200]) => http.Response(
      body,
      status,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// A server answering the album with the given rights and the re-reading
/// with [answer].
VAlbumClient server({
  required List<String> rights,
  required List<http.Request> requests,
  String answer = '{"examined": 12, "filled": 5, "albums": 1}',
  int status = 200,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json('{"mode": "all", "deviceName": "Phone", '
              '"writeAllowed": true, "userName": "carol", "role": "edit", '
              '"space": "", "clearance": "all", "mayShare": true}');
        }
        if (query["action"] == "reanalyze") {
          return json(answer, status);
        }
        return json(albumWith(rights));
      }),
    );

/// The re-readings asked for so far.
List<http.Request> reanalysesIn(List<http.Request> requests) => [
      for (var request in requests)
        if (request.url.queryParameters["action"] == "reanalyze") request,
    ];

/// The album fetches made so far.
int albumFetches(List<http.Request> requests) => requests
    .where((r) => r.method == "GET" && r.url.queryParameters["type"] == "json")
    .length;

Future<void> pumpAlbum(
  WidgetTester tester,
  VAlbumClient client, {
  OfflineState? offlineState,
}) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    offlineState: offlineState,
    initialRoute: const ListingOrAlbumRoute(["Zoo"]),
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "dev-9",
      userName: "carol",
      loaded: true,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

/// Opens the menu, taps the entry and confirms the question.
Future<void> reanalyze(WidgetTester tester) async {
  await openMenu(tester);
  await tester.tap(find.byKey(const Key("reanalyze")));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("reanalyze-confirm")));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    PaintingBinding.instance.imageCache
      ..clear()
      ..clearLiveImages();
  });

  testWidgets('an editor is offered the entry, right after the duplicates',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, server(rights: editor, requests: []));
      await openMenu(tester);

      expect(find.byKey(const Key("reanalyze")), findsOneWidget);
      expect(find.text(testL10n.reanalyze), findsOneWidget);
      var duplicates =
          tester.getTopLeft(find.byKey(const Key("find-duplicates"))).dy;
      var reanalyze = tester.getTopLeft(find.byKey(const Key("reanalyze"))).dy;
      expect(reanalyze, greaterThan(duplicates));
    });
  });

  testWidgets('a contributor and a viewer are not', (tester) async {
    for (var rights in const [
      ["view", "download", "contribute"],
      ["view", "download"],
    ]) {
      await withFakeImageHttp(() async {
        await pumpAlbum(tester, server(rights: rights, requests: []));
        await openMenu(tester);

        expect(find.byKey(const Key("reanalyze")), findsNothing);
      });
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('the entry asks first, and Cancel posts nothing', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, server(rights: editor, requests: requests));
      await openMenu(tester);
      await tester.tap(find.byKey(const Key("reanalyze")));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("reanalyze-dialog")), findsOneWidget);
      expect(find.text(testL10n.reanalyzeExplanation), findsOneWidget);

      await tester.tap(find.text(testL10n.cancel));
      await tester.pumpAndSettle();
      expect(reanalysesIn(requests), isEmpty);
    });
  });

  testWidgets('it posts the action, says the counts and reloads the album',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, server(rights: editor, requests: requests));
      var before = albumFetches(requests);

      await reanalyze(tester);

      var posted = reanalysesIn(requests);
      expect(posted.length, 1);
      expect(posted.single.method, "POST");
      expect(posted.single.url.path, endsWith("/Zoo/"));
      expect(posted.single.headers["Authorization"], "Bearer dev-9");
      expect(find.text(testL10n.reanalyzeDone(12, 5)), findsOneWidget);
      expect(
          find.text("Checked 12 photos; 5 of them gained a camera or a "
              "position."),
          findsOneWidget);
      expect(albumFetches(requests), greaterThan(before),
          reason: "The album is fetched anew to show what was filled.");
    });
  });

  testWidgets('nothing written, nothing reloaded', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          rights: editor,
          requests: requests,
          answer: '{"examined": 3, "filled": 0, "albums": 0}',
        ),
      );
      var before = albumFetches(requests);

      await reanalyze(tester);

      expect(find.text(testL10n.reanalyzeDone(3, 0)), findsOneWidget);
      expect(albumFetches(requests), before);
    });
  });

  testWidgets('a run that goes on in the background says so', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          rights: editor,
          requests: [],
          status: 202,
          answer: '{"examined": 40, "filled": 2, "albums": 1, '
              '"running": true}',
        ),
      );

      await reanalyze(tester);

      expect(find.text(testL10n.reanalyzeRunning(40, 2)), findsOneWidget);
    });
  });

  testWidgets('a refusal is shown in the server own words', (tester) async {
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(
          rights: editor,
          requests: [],
          status: 403,
          answer: '["ErrorInfo", {"message": "You may not change this '
              'album, so its photo details cannot be re-read."}]',
        ),
      );

      await reanalyze(tester);

      expect(
        find.text("You may not change this album, so its photo details "
            "cannot be re-read."),
        findsOneWidget,
      );
    });
  });

  testWidgets('it is refused while the app is offline', (tester) async {
    var requests = <http.Request>[];
    var state = OfflineState();
    await withFakeImageHttp(() async {
      await pumpAlbum(
        tester,
        server(rights: editor, requests: requests),
        offlineState: state,
      );
      state.goneOffline(null);
      await tester.pumpAndSettle();

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("reanalyze")));
      await tester.pumpAndSettle();

      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(find.byKey(const Key("reanalyze-dialog")), findsNothing);
    });
    expect(reanalysesIn(requests), isEmpty);
  });
}
