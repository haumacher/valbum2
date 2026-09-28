/// Tests of the page a failed load shows (issue #176): it speaks in the
/// reader's terms — the album or folder the address names and the server as
/// the reader opened it — and keeps the technical address behind "Details".
/// A 404 is "not found", never "offline"; a server that cannot be reached
/// keeps the offline wording.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/load_failure.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to, and the app base a reader knows it by.
const String dataUrl = "http://server/valbum/data";
const String appBase = "http://server/valbum/";

/// The body of the server's refusal of an address that names nothing.
String notFoundBody(String segment) =>
    '["ErrorInfo",{"message":"There is no album, folder or file \'$segment\' here."}]';

/// A client whose server knows the root listing and nothing else.
///
/// Request paths are percent-encoded, so the album path is compared decoded.
VAlbumClient serverKnowingOnlyTheRoot({
  OfflineCache? cache,
  OfflineState? offlineState,
  int status = 404,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      cache: cache,
      offlineState: offlineState,
      httpClient: MockClient(servingThumbnails((request) async {
        var path = Uri.decodeComponent(request.url.path);
        if (path == "/valbum/data/") {
          return http.Response(fixture("listing.json"), 200,
              headers: {"content-type": "application/json; charset=utf-8"});
        }
        var segments = path.substring("/valbum/data/".length).split("/");
        return http.Response(notFoundBody(segments.first), status,
            headers: {"content-type": "application/json; charset=utf-8"});
      })),
    );

Future<void> pumpAt(
  WidgetTester tester,
  VAlbumRoute route,
  VAlbumClient client, {
  OfflineCache? cache,
  OfflineState? offlineState,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      initialRoute: route,
      cache: cache,
      offlineState: offlineState,
    ));
    await tester.pumpAndSettle();
  });
}

String headline(WidgetTester tester) =>
    tester.widget<Text>(find.byKey(headlineKey)).data!;

/// The start page button leads to the root listing, and the details line
/// reveals the address that was asked.
Future<void> expectWayOnAndDetails(
  WidgetTester tester, {
  required String technicalUrl,
}) async {
  // Hidden until asked for.
  expect(find.textContaining("/data/"), findsNothing);
  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(detailsKey));
    await tester.pumpAndSettle();
  });
  expect(find.text("HTTP 404 for $technicalUrl"), findsOneWidget);

  await withFakeImageHttp(() async {
    await tester.tap(find.byKey(goToStartKey));
    await tester.pumpAndSettle();
  });
  expect(find.byKey(headlineKey), findsNothing);
  expect(find.text("Test-album"), findsOneWidget);
  expect(find.text("Schlosspark Karlsruhe"), findsOneWidget);
}

void main() {
  testWidgets('a listing that is not there names itself and the server',
      (tester) async {
    await pumpAt(
      tester,
      const ListingOrAlbumRoute(["valbum"]),
      serverKnowingOnlyTheRoot(),
    );

    expect(
      headline(tester),
      "The album or folder 'valbum' was not found on the server $appBase.",
    );
    // The server's own reason, verbatim.
    expect(find.text("There is no album, folder or file 'valbum' here."),
        findsOneWidget);
    expect(find.textContaining("Loading failed"), findsNothing);
    await expectWayOnAndDetails(
      tester,
      technicalUrl: "$dataUrl/valbum/?type=json",
    );
  });

  testWidgets('an album that is not there names its folder name',
      (tester) async {
    await pumpAt(
      tester,
      const ListingOrAlbumRoute(["2020", "2020-05-01 Trip"]),
      serverKnowingOnlyTheRoot(),
    );

    expect(
      headline(tester),
      "The album or folder '2020-05-01 Trip' was not found on the server "
      "$appBase.",
    );
    await expectWayOnAndDetails(
      tester,
      technicalUrl: "$dataUrl/2020/2020-05-01 Trip/?type=json",
    );
  });

  testWidgets('the viewer of an album that is not there names the album',
      (tester) async {
    await pumpAt(
      tester,
      const ImageRoute(["Trip"], "IMG_1.jpg"),
      serverKnowingOnlyTheRoot(),
    );

    // The viewer is a level of the album, and it is the album that failed.
    expect(
      headline(tester),
      "The album 'Trip' was not found on the server $appBase.",
    );
    await expectWayOnAndDetails(
      tester,
      technicalUrl: "$dataUrl/Trip/?type=json",
    );
  });

  testWidgets('the persons, trash and group pages name the album too',
      (tester) async {
    for (var route in const <VAlbumRoute>[
      PersonsRoute(["Trip"]),
      TrashRoute(["Trip"]),
      AlternativesRoute(["Trip"], "IMG_1.jpg"),
    ]) {
      await tester.pumpWidget(const SizedBox());
      await pumpAt(tester, route, serverKnowingOnlyTheRoot());
      expect(
        find.text("The album 'Trip' was not found on the server $appBase."),
        findsWidgets,
        reason: "$route",
      );
      expect(find.byKey(goToStartKey), findsWidgets, reason: "$route");
    }
  });

  testWidgets('a refusal other than 404 says the album could not be opened',
      (tester) async {
    await pumpAt(
      tester,
      const ListingOrAlbumRoute(["Trip"]),
      serverKnowingOnlyTheRoot(status: 500),
    );

    expect(
      headline(tester),
      "The album or folder 'Trip' could not be opened on the server $appBase.",
    );
  });

  testWidgets('the start page itself offers no way to the start page',
      (tester) async {
    await pumpAt(
      tester,
      ListingOrAlbumRoute.root,
      clientReturning("", status: 404, dataUrl: dataUrl),
    );

    expect(headline(tester),
        "The start page was not found on the server $appBase.");
    expect(find.byKey(goToStartKey), findsNothing);
    // No ErrorInfo: nothing but the headline quotes the server, and the
    // status lives in the details.
    expect(find.textContaining("HTTP 404"), findsNothing);
    await tester.tap(find.byKey(detailsKey));
    await tester.pumpAndSettle();
    expect(find.text("HTTP 404 for $dataUrl/?type=json"), findsOneWidget);
    // The retry is still there.
    expect(find.byIcon(Icons.update), findsOneWidget);
  });

  testWidgets('a 404 neither serves the cached album nor goes offline',
      (tester) async {
    var cache = MemoryOfflineCache();
    await cache.putResource(dataUrl, const ["Trip"], fixture("album.json"));
    var state = OfflineState();

    await pumpAt(
      tester,
      const ListingOrAlbumRoute(["Trip"]),
      serverKnowingOnlyTheRoot(cache: cache, offlineState: state),
      cache: cache,
      offlineState: state,
    );

    expect(headline(tester), contains("was not found"));
    expect(find.text("Am Morgen"), findsNothing);
    expect(state.offline, isFalse);
  });

  testWidgets('a server that cannot be reached keeps the offline wording',
      (tester) async {
    var state = OfflineState();
    var client = VAlbumClient(
      dataUrl: dataUrl,
      cache: MemoryOfflineCache(),
      offlineState: state,
      httpClient: MockClient(
        (request) async =>
            throw http.ClientException("Connection refused", request.url),
      ),
    );

    await pumpAt(tester, const ListingOrAlbumRoute(["Trip"]), client,
        offlineState: state);

    expect(headline(tester), startsWith("Loading failed: "));
    expect(headline(tester), contains("cannot be reached"));
    expect(find.textContaining("was not found"), findsNothing);
    expect(find.byKey(detailsKey), findsNothing);
    expect(state.offline, isTrue);
  });
}
