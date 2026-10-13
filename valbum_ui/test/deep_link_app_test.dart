/// The app on a link that opens a place of an album (issue #275): at once on
/// the server it is connected to, after "Connect to …?" on another one, on
/// the first screen filled in where it is connected to none — as the link it
/// was started with (cold start) and as one it is handed while it runs (warm
/// start). And the web app's offer to follow such a link.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:url_launcher/link.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/deep_link.dart';
import 'package:valbum_ui/first_screen.dart';
import 'package:valbum_ui/main.dart';

import 'deep_link_test.dart' show androidAgent, desktopAgent;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the device is connected to in most tests.
const String home = "http://server/valbum/";

/// Another album server.
const String other = "https://foto.example.de/valbum/";

/// What a server answers a pairing it accepted.
const String paired = '{"token":"tok-2","deviceName":"Phone",'
    '"userName":"haui","role":"admin","space":""}';

/// A server answering every album with `album.json`, recording each request.
MockClient serverRecording(List<Uri> requests) =>
    MockClient(servingThumbnails((request) async {
      requests.add(request.url);
      var query = request.url.queryParameters;
      if (query["action"] == "pair") {
        return http.Response(paired, 200);
      }
      if (query["type"] == "auth") {
        return http.Response(
          '{"mode":"writes","deviceName":"Phone","writeAllowed":true,'
          '"userName":"haui","role":"admin"}',
          200,
        );
      }
      if (query["type"] == "devices") {
        return http.Response('{"devices":[]}', 200);
      }
      var path = request.url.path;
      var body = path.endsWith("/data") || path.endsWith("/data/")
          ? fixture("listing.json")
          : fixture("album.json");
      return http.Response(body, 200,
          headers: {"content-type": "application/json; charset=utf-8"});
    }));

/// The link opening [path] on [server].
String linkTo(String server, String path) =>
    appLinkFor(dataUrlOf(server), parseRoute(Uri.parse(path))).toString();

/// Pumps the app with [serverUrl] stored (or nothing), the way a phone starts.
Future<InMemorySettingsStore> pumpApp(
  WidgetTester tester,
  List<Uri> requests, {
  String? serverUrl = home,
  String? openedWith,
  OpenInAppOffer? openInApp,
  VAlbumRoute? initialRoute,
  String? token = "tok-1",
}) async {
  await tester.binding.setSurfaceSize(const Size(800, 1400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  if (openedWith != null) {
    tester.binding.platformDispatcher.defaultRouteNameTestValue = openedWith;
    addTearDown(
        tester.binding.platformDispatcher.clearDefaultRouteNameTestValue);
  }
  var store = InMemorySettingsStore(
      serverUrl, serverUrl == null ? null : token, "Phone", "haui");
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: serverRecording(requests),
      ),
      settings: ServerSettings(store: store, platformDefault: () => null),
      openInApp: openInApp,
      initialRoute: initialRoute,
    ));
    await tester.pumpAndSettle();
  });
  return store;
}

/// Hands the running app [link] the way the Android embedding does on a new
/// intent: `pushRouteInformation` on the navigation channel.
Future<void> pushLink(WidgetTester tester, String link) async {
  await withFakeImageHttp(() async {
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      SystemChannels.navigation.name,
      SystemChannels.navigation.codec.encodeMethodCall(
        MethodCall("pushRouteInformation", {"location": link, "state": null}),
      ),
      (_) {},
    );
    await tester.pumpAndSettle();
  });
}

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.state<VAlbumAppState>(find.byType(VAlbumApp)).router.route;

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

/// Whether the album at [path] of [server] was asked for.
bool asked(List<Uri> requests, String server, String path) =>
    requests.any((url) =>
        url.toString().startsWith(dataUrlOf(server)) &&
        Uri.decodeComponent(url.path).endsWith(path));

void main() {
  group("connected to the server of the link", () {
    testWidgets("a cold start opens the album", (tester) async {
      var requests = <Uri>[];
      await pumpApp(tester, requests,
          openedWith: linkTo(home, "/2024/Schlosspark Köln/"));

      expect(routeOf(tester),
          const ListingOrAlbumRoute(["2024", "Schlosspark Köln"]));
      expect(find.text("Schlosspark Karlsruhe"), findsWidgets);
      expect(asked(requests, home, "/2024/Schlosspark Köln/"), isTrue);
    });

    testWidgets("a cold start opens the photo", (tester) async {
      var requests = <Uri>[];
      await pumpApp(tester, requests,
          openedWith: linkTo(home, "/Schlosspark/landscape.jpg"));

      expect(
          routeOf(tester), const ImageRoute(["Schlosspark"], "landscape.jpg"));
    });

    testWidgets("a warm start opens the album, the photo, the group member",
        (tester) async {
      var requests = <Uri>[];
      await pumpApp(tester, requests);
      expect(routeOf(tester), ListingOrAlbumRoute.root);

      await pushLink(tester, linkTo(home, "/Urlaub am Meer/"));
      expect(routeOf(tester), const ListingOrAlbumRoute(["Urlaub am Meer"]));
      expect(asked(requests, home, "/Urlaub am Meer/"), isTrue);

      await pushLink(tester, linkTo(home, "/Urlaub am Meer/portrait.jpg"));
      expect(routeOf(tester),
          const ImageRoute(["Urlaub am Meer"], "portrait.jpg"));

      await pushLink(tester,
          linkTo(home, "/Urlaub am Meer/group-a.jpg/alternatives/group-b.jpg"));
      expect(routeOf(tester),
          const MemberRoute(["Urlaub am Meer"], "group-a.jpg", "group-b.jpg"));
      // Nothing was asked, and nothing stored changed.
      expect(find.byKey(connectToServerKey), findsNothing);
    });

    testWidgets("the server, spelled as its page or its data URL",
        (tester) async {
      var requests = <Uri>[];
      await pumpApp(tester, requests);
      await pushLink(
          tester,
          "valbum://open?server=${Uri.encodeComponent("http://SERVER/valbum/data")}"
          "&path=${Uri.encodeComponent("/Zoo/")}");
      expect(routeOf(tester), const ListingOrAlbumRoute(["Zoo"]));
    });

    testWidgets("a link that names no server opens nothing", (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests);
      await pushLink(tester, "valbum://open?path=/Zoo/");
      await pushLink(tester, "valbum://open?server=garbage&path=/Zoo/");
      await pushLink(tester,
          "valbum://open?server=${Uri.encodeComponent("$home/i/tok/")}");
      expect(routeOf(tester), ListingOrAlbumRoute.root);
      expect(find.byKey(connectToServerKey), findsNothing);
      expect(store.value, home);
    });
  });

  group("connected to another server", () {
    testWidgets("asks, signs in there, then opens the place", (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests);

      await pushLink(tester, linkTo(other, "/Zoo/landscape.jpg"));
      expect(find.byKey(connectToServerKey), findsOneWidget);
      expect(find.text("Connect to foto.example.de?"), findsOneWidget);
      expect(find.textContaining(home), findsOneWidget);
      // Nothing changed yet.
      expect(store.value, home);
      expect(routeOf(tester), ListingOrAlbumRoute.root);

      await tapVisible(tester, find.byKey(connectToServerYesKey));
      expect(find.byKey(serverPairingPageKey), findsOneWidget);
      expect(find.text("Album server: $other"), findsOneWidget);
      expect(find.byKey(firstScreenChangeKey), findsNothing);

      await tester.enterText(find.byKey(deviceCodeFieldKey), "ABCD-EFGH");
      await tester.pumpAndSettle();
      await tapVisible(tester, find.byKey(signInButtonKey));

      expect(store.value, other);
      expect(store.token, "tok-2");
      expect(find.byKey(serverPairingPageKey), findsNothing);
      expect(routeOf(tester), const ImageRoute(["Zoo"], "landscape.jpg"));
      expect(asked(requests, other, "/Zoo/"), isTrue);
    });

    testWidgets("a cold start asks as well", (tester) async {
      var requests = <Uri>[];
      var store =
          await pumpApp(tester, requests, openedWith: linkTo(other, "/Zoo/"));
      expect(find.byKey(connectToServerKey), findsOneWidget);

      await tapVisible(tester, find.byKey(connectToServerYesKey));
      await tapVisible(tester, find.byKey(firstScreenSkipKey));

      expect(store.value, other);
      expect(routeOf(tester), const ListingOrAlbumRoute(["Zoo"]));
    });

    testWidgets("Cancel stays where the app is", (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests);
      await pushLink(tester, linkTo(other, "/Zoo/"));

      await tapVisible(tester, find.text("Cancel"));

      expect(find.byKey(connectToServerKey), findsNothing);
      expect(store.value, home);
      expect(store.token, "tok-1");
      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });

    testWidgets("leaving the sign-in lets the link go", (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests);
      await pushLink(tester, linkTo(other, "/Zoo/"));
      await tapVisible(tester, find.byKey(connectToServerYesKey));

      await tapVisible(tester, find.byType(BackButton));

      expect(find.byKey(serverPairingPageKey), findsNothing);
      expect(store.value, home);
      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });
  });

  group("connected to no server", () {
    testWidgets("the first screen names the server of the link",
        (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests,
          serverUrl: null, openedWith: linkTo(other, "/Zoo/portrait.jpg"));

      expect(find.byKey(firstScreenKey), findsOneWidget);
      expect(find.text("Album server: $other"), findsOneWidget);
      expect(find.byKey(connectToServerKey), findsNothing);

      // The field holds it too, for "another server".
      await tapVisible(tester, find.byKey(firstScreenChangeKey));
      expect(
          tester
              .widget<TextField>(find.byKey(firstScreenFieldKey))
              .controller!
              .text,
          other);
      await tapVisible(tester, find.byKey(firstScreenContinueKey));
      await tapVisible(tester, find.byKey(firstScreenSkipKey));

      expect(store.value, other);
      expect(routeOf(tester), const ImageRoute(["Zoo"], "portrait.jpg"));
    });

    testWidgets("a warm start fills in the first screen", (tester) async {
      var requests = <Uri>[];
      await pumpApp(tester, requests, serverUrl: null);
      expect(find.byKey(firstScreenServerKey), findsNothing);

      await pushLink(tester, linkTo(other, "/Zoo/"));
      expect(find.text("Album server: $other"), findsOneWidget);
    });

    testWidgets("another server chosen there lets the link go", (tester) async {
      var requests = <Uri>[];
      var store = await pumpApp(tester, requests,
          serverUrl: null, openedWith: linkTo(other, "/Zoo/"));
      await tapVisible(tester, find.byKey(firstScreenChangeKey));
      await tester.enterText(find.byKey(firstScreenFieldKey), home);
      await tapVisible(tester, find.byKey(firstScreenContinueKey));
      await tapVisible(tester, find.byKey(firstScreenSkipKey));

      expect(store.value, home);
      expect(find.byKey(connectToServerKey), findsNothing);
      expect(routeOf(tester), ListingOrAlbumRoute.root);
    });
  });

  group("the offer of the web app", () {
    OpenInAppOffer offer(String agent, MemoryStorageArea storage) =>
        OpenInAppOffer(isWeb: true, userAgent: agent, storage: storage);

    Uri appLinkOfBanner(WidgetTester tester) {
      var href = tester.widget<Link>(find.byType(Link)).uri.toString();
      expect(href, startsWith("intent://open?"));
      return Uri.parse("valbum:${href.substring("intent:".length)}")
          .removeFragment();
    }

    testWidgets("an Android browser is offered the place it shows",
        (tester) async {
      var requests = <Uri>[];
      var storage = MemoryStorageArea();
      await pumpApp(tester, requests, openInApp: offer(androidAgent, storage));

      expect(find.byKey(openInAppBannerKey), findsOneWidget);
      var link = parseAppLink(appLinkOfBanner(tester))!;
      expect(link.serverUrl, home);
      expect(link.route, ListingOrAlbumRoute.root);

      var state = tester.state<VAlbumAppState>(find.byType(VAlbumApp));
      await withFakeImageHttp(() async {
        state.router.go(const ImageRoute(["Grüße"], "landscape.jpg"));
        await tester.pumpAndSettle();
      });
      expect(parseAppLink(appLinkOfBanner(tester))!.route,
          const ImageRoute(["Grüße"], "landscape.jpg"));

      await tapVisible(tester, find.byKey(openInAppDismissKey));
      expect(find.byKey(openInAppBannerKey), findsNothing);
      expect(storage.values, contains(OpenInAppOffer.dismissedKey));
    });

    testWidgets("a page opened deep in the album offers that place",
        (tester) async {
      // The banner is built above the router, before the router took the
      // location the page was opened at. Anonymous, as most visitors of a
      // page are: nothing else rebuilds the app after the first frame.
      await pumpApp(tester, [],
          token: null,
          openInApp: offer(androidAgent, MemoryStorageArea()),
          initialRoute: const ListingOrAlbumRoute(["Blumen und Fliegen"]));
      expect(parseAppLink(appLinkOfBanner(tester))!.route,
          const ListingOrAlbumRoute(["Blumen und Fliegen"]));
    });

    testWidgets("a dismissed offer stays dismissed in this browser",
        (tester) async {
      var storage = MemoryStorageArea()
        ..values[OpenInAppOffer.dismissedKey] = "1";
      await pumpApp(tester, [], openInApp: offer(androidAgent, storage));
      expect(find.byKey(openInAppBannerKey), findsNothing);
    });

    testWidgets("a desktop browser is offered nothing", (tester) async {
      await pumpApp(tester, [],
          openInApp: offer(desktopAgent, MemoryStorageArea()));
      expect(find.byKey(openInAppBannerKey), findsNothing);
    });

    testWidgets("nobody on a share link is offered the app", (tester) async {
      await tester.pumpWidget(VAlbumApp(
        client: VAlbumClient(
          dataUrl: "http://server/valbum/data",
          httpClient: MockClient(servingThumbnails((request) async {
            if (request.url.queryParameters["type"] == "auth") {
              return http.Response(
                '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
                '"userName": "", "role": "", "space": "", '
                '"share": {"label": "Zoo", "expires": "", '
                '"rights": [{"name": "view"}], "path": "Zoo"}}',
                200,
                headers: {"content-type": "application/json; charset=utf-8"},
              );
            }
            return http.Response(fixture("album.json"), 200,
                headers: {"content-type": "application/json; charset=utf-8"});
          })),
        ),
        settings: ServerSettings(
          store: InMemorySettingsStore(home),
          platformDefault: () => "http://server/valbum/data",
        ),
        session: const SessionUrl(
          kind: SessionKind.share,
          token: "tok-42",
          dataUrl: "http://server/valbum/data",
          basePath: "/valbum/s/tok-42/",
        ),
        openInApp: offer(androidAgent, MemoryStorageArea()),
      ));
      await withFakeImageHttp(() => tester.pumpAndSettle());
      expect(find.text("Schlosspark Karlsruhe"), findsWidgets);
      expect(find.byKey(openInAppBannerKey), findsNothing);
    });
  });
}
