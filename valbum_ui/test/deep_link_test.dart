/// The link that opens a place of an album in the app (issue #275): what it
/// accepts, what it refuses, and the web app's offer to follow it.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:url_launcher/link.dart';
import 'package:valbum_ui/contact_session.dart';
import 'package:valbum_ui/deep_link.dart';
import 'package:valbum_ui/l10n/app_localizations.dart';
import 'package:valbum_ui/routes.dart';

/// A browser on an Android phone.
const String androidAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";

/// A browser on a desktop.
const String desktopAgent = "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36";

/// A storage area that throws on every access, as a private window may.
class FailingStorageArea implements StorageArea {
  @override
  String? get(String key) => throw StateError("blocked");

  @override
  void set(String key, String value) => throw StateError("blocked");

  @override
  void remove(String key) => throw StateError("blocked");
}

AppLink? parse(String link) => parseAppLink(Uri.parse(link));

String link(String server, [String? path]) => Uri(
      scheme: "valbum",
      host: "open",
      queryParameters: {
        "server": server,
        if (path != null) "path": path,
      },
    ).toString();

void main() {
  group("parsing", () {
    test("the link the web app writes opens the same place", () {
      for (var route in <VAlbumRoute>[
        ListingOrAlbumRoute.root,
        const ListingOrAlbumRoute(["2024", "Urlaub am Meer"]),
        const ImageRoute(["Grüße aus Köln"], "Straße & Dom.jpg"),
        const MemberRoute(["Zoo"], "a.jpg", "b.jpg"),
        const SearchRoute(["Zoo"], query: '{"text":"Löwe"}'),
      ]) {
        var written = appLinkFor("https://foto.example.de/valbum/data", route);
        var read = parseAppLink(Uri.parse(written.toString()))!;
        expect(read.serverUrl, "https://foto.example.de/valbum/");
        expect(read.route, route, reason: "$written");
      }
    });

    test("garbage is no link", () {
      expect(parse("valbum://open"), isNull);
      expect(parse("valbum://open?path=/Zoo/"), isNull);
      expect(parse(link("")), isNull);
      expect(parse(link("not a server")), isNull);
      expect(parse(link("foto.example.de/valbum")), isNull);
      expect(parse(link("ftp://foto.example.de/valbum/")), isNull);
      expect(parse(link("javascript:alert(1)")), isNull);
      expect(parse("valbum://other?server=https://h/valbum/"), isNull);
      expect(parse("https://open?server=https://h/valbum/"), isNull);
      expect(parse("valbum://open?server=%E0%A4%A"), isNull);
      expect(isAppLink(Uri.parse("/Zoo/")), isFalse);
      expect(isAppLink(Uri.parse("valbum://anything")), isTrue);
    });

    test("the server is its app base, however it is spelled", () {
      for (var spelling in [
        "https://foto.example.de/valbum",
        "https://foto.example.de/valbum/",
        "https://foto.example.de/valbum/index.html",
        "https://foto.example.de/valbum/data",
        "https://foto.example.de/valbum/shared/",
        "  https://foto.example.de/valbum/  ",
      ]) {
        expect(parse(link(spelling, "/Zoo/"))!.serverUrl,
            "https://foto.example.de/valbum/",
            reason: spelling);
      }
      // A context path of several segments, a space, a port, the root.
      expect(parse(link("http://nas.local:8080/photos/deep/alice/"))!.serverUrl,
          "http://nas.local:8080/photos/deep/alice/");
      expect(parse(link("http://nas.local:8080/photos/deep/alice"))!.dataUrl,
          "http://nas.local:8080/photos/deep/alice/data");
      expect(parse(link("https://h/"))!.serverUrl, "https://h/");
      expect(parse(link("https://h/data"))!.serverUrl, "https://h/");
      expect(
          parse(link("http://nas.local:8080/valbum/"))!.host, "nas.local:8080");
      expect(parse(link("https://foto.example.de/valbum/"))!.host,
          "foto.example.de");
    });

    test("a path with spaces and umlauts, encoded or not", () {
      var raw = parse(link("https://h/valbum/", "/Urlaub 2024/Strand Ä.jpg"))!;
      expect(raw.route, const ImageRoute(["Urlaub 2024"], "Strand Ä.jpg"));
      var encoded = parse(
          link("https://h/valbum/", "/Urlaub%202024/Strand%20%C3%84.jpg"))!;
      expect(encoded.route, raw.route);
      expect(parse(link("https://h/valbum/", "/Grüße aus Köln/"))!.route,
          const ListingOrAlbumRoute(["Grüße aus Köln"]));
      expect(parse(link("https://h/valbum/", "/100% Urlaub/"))!.route,
          const ListingOrAlbumRoute(["100% Urlaub"]));
      // Without a leading slash, with several, and without any path.
      expect(parse(link("https://h/valbum/", "Zoo/a.jpg"))!.route,
          const ImageRoute(["Zoo"], "a.jpg"));
      expect(parse(link("https://h/valbum/", "//evil.example/Zoo/"))!.route,
          const ListingOrAlbumRoute(["evil.example", "Zoo"]));
      expect(parse(link("https://h/valbum/"))!.route, ListingOrAlbumRoute.root);
      expect(
          parse(link("https://h/valbum/", "/Zoo/a.jpg/alternatives/b.jpg"))!
              .route,
          const MemberRoute(["Zoo"], "a.jpg", "b.jpg"));
    });

    test("never a token", () {
      // A parameter of the link is ignored, whatever it is called.
      var withToken = parse("valbum://open?server=https://h/valbum/"
          "&path=/Zoo/&token=secret&invitation=secret")!;
      expect(
          withToken,
          const AppLink(
              serverUrl: "https://h/valbum/",
              route: ListingOrAlbumRoute(["Zoo"])));
      expect(withToken.toString(), isNot(contains("secret")));
      // A token in the server address is dropped, or the server refused.
      expect(parse(link("https://h/valbum/?token=secret"))!.serverUrl,
          "https://h/valbum/");
      expect(parse(link("https://h/valbum/#secret"))!.serverUrl,
          "https://h/valbum/");
      expect(parse(link("https://h/valbum/i/secret/")), isNull);
      expect(parse(link("https://h/valbum/s/secret/")), isNull);
      expect(parse(link("https://user:secret@h/valbum/")), isNull);
    });
  });

  test("the intent link opens the app or the releases", () {
    var appLink = appLinkFor(
        "https://h/valbum/data", const ImageRoute(["Zoo"], "a b.jpg"));
    var intent = intentLinkFor(appLink);
    expect(intent, startsWith("intent://open?server="));
    expect(
        intent,
        endsWith("#Intent;scheme=valbum;package=de.haumacher.valbum;"
            "S.browser_fallback_url=https%3A%2F%2Fgithub.com%2Fhaumacher%2F"
            "valbum2%2Freleases;end"));
    // What the app gets back is the very same link.
    var back = Uri.parse("valbum:${intent.substring("intent:".length)}")
        .removeFragment();
    expect(parseAppLink(back)!.route, const ImageRoute(["Zoo"], "a b.jpg"));
  });

  group("the offer in the browser", () {
    test("only in a browser on Android, outside a link, until dismissed", () {
      bool offers({
        bool isWeb = true,
        String agent = androidAgent,
        bool linkSession = false,
        bool dismissed = false,
      }) =>
          offersOpenInApp(
            isWeb: isWeb,
            userAgent: agent,
            linkSession: linkSession,
            dismissed: dismissed,
          );
      expect(offers(), isTrue);
      expect(offers(agent: desktopAgent), isFalse);
      expect(offers(agent: ""), isFalse);
      expect(offers(isWeb: false), isFalse);
      expect(offers(linkSession: true), isFalse);
      expect(offers(dismissed: true), isFalse);
    });

    test("a dismissal is remembered by the browser", () {
      var storage = MemoryStorageArea();
      var offer = OpenInAppOffer(
          isWeb: true, userAgent: androidAgent, storage: storage);
      expect(offer.shows(linkSession: false), isTrue);
      offer.dismiss();
      expect(offer.shows(linkSession: false), isFalse);
      // The next page load of the same browser.
      expect(
          OpenInAppOffer(isWeb: true, userAgent: androidAgent, storage: storage)
              .shows(linkSession: false),
          isFalse);
    });

    test("a storage that throws neither breaks nor forgets in this page", () {
      var offer = OpenInAppOffer(
          isWeb: true, userAgent: androidAgent, storage: FailingStorageArea());
      expect(offer.shows(linkSession: false), isTrue);
      offer.dismiss();
      expect(offer.shows(linkSession: false), isFalse);
    });

    testWidgets("the banner carries the place shown, and follows it",
        (tester) async {
      var route =
          ValueNotifier<VAlbumRoute>(const ListingOrAlbumRoute(["Zoo"]));
      var dismissed = 0;
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: OpenInAppBanner(
            routes: route,
            appLink: () => appLinkFor("https://h/valbum/data", route.value),
            onDismiss: () => dismissed++,
          ),
        ),
      ));
      Uri href() => tester.widget<Link>(find.byType(Link)).uri!;
      expect(find.text("Open in app"), findsOneWidget);
      expect(href().scheme, "intent");
      expect(
          parseAppLink(Uri.parse("valbum:${href().toString().substring(7)}")
                  .removeFragment())!
              .route,
          const ListingOrAlbumRoute(["Zoo"]));

      route.value = const ImageRoute(["Zoo"], "Löwe.jpg");
      await tester.pump();
      expect(href().toString(), contains("L%25C3%25B6we.jpg"));

      await tester.tap(find.byKey(openInAppDismissKey));
      expect(dismissed, 1);
    });
  });
}
