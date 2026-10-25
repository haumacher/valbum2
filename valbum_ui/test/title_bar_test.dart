/// The title bar of every page (issues #308, #309, #310): the way up is the
/// leading control at the left in every mode, the title starts beside it on
/// the line every page's title is on; the status indicator and the bell stand
/// on the start page alone, there is no home button, an album's search is an
/// entry of its menu; the bar of a page on black is black with light text,
/// the settings follow the system's light or dark theme.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/news.dart';
import 'package:valbum_ui/page_app_bar.dart';

import 'settings_test.dart' as settings;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// An album the server is still reading (issue #253): nothing to show yet.
const String preparingAlbum =
    '["AlbumInfo", {"path": "Trip", "title": "Trip", "subTitle": "", '
    '"preparing": true, "rights": [{"name": "view"}], "parts": []}]';

/// Pumps the app at [path], every request answered with [body].
Future<void> pumpAt(
  WidgetTester tester,
  String body, {
  List<String> path = const [],
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: clientReturning(body),
      initialRoute: ListingOrAlbumRoute(path),
    ));
    await tester.pumpAndSettle();
  });
}

/// The bar of the page shown.
AppBar barOf(WidgetTester tester) => tester.widget<AppBar>(find.byType(AppBar));

/// The colour the bar of the page shown is painted in.
Color? barColor(WidgetTester tester) => tester
    .widget<Material>(find
        .descendant(of: find.byType(AppBar), matching: find.byType(Material))
        .first)
    .color;

/// The colour the text [title] is painted in.
Color? textColor(WidgetTester tester, String title) => tester
    .widget<RichText>(
        find.descendant(of: find.text(title), matching: find.byType(RichText)))
    .text
    .style
    ?.color;

/// Whether the way up is somewhere among the actions of the bar.
bool upInActions(WidgetTester tester) => barOf(tester).actions!.any((action) =>
    action is WayBackButton ||
    find
        .descendant(
          of: find.byWidget(action),
          matching: find.byIcon(Icons.arrow_back),
        )
        .evaluate()
        .isNotEmpty);

/// The search buttons standing anywhere on the page.
Finder searchButtons() => find.ancestor(
    of: find.byIcon(Icons.search), matching: find.byType(IconButton));

void main() {
  group('the album the server is still preparing (#308)', () {
    testWidgets('has the way up at the left and its title beside it',
        (tester) async {
      await pumpAt(tester, preparingAlbum, path: const ["Trip"]);
      expect(find.byKey(const Key("album-preparing")), findsOneWidget);

      var bar = barOf(tester);
      expect(bar.leading, isA<WayBackButton>());
      expect(upInActions(tester), isFalse,
          reason: "the way up is no action at the right");
      expect(bar.centerTitle, isFalse);

      var up = tester.getRect(find.byType(WayBackButton));
      var title = tester.getRect(find.text("Trip"));
      var width = tester.getSize(find.byType(AppBar)).width;
      expect(up.left, lessThan(title.left));
      expect(title.left - up.right, lessThan(32),
          reason: "the title starts beside the way up");
      expect(title.center.dx, lessThan(width / 2),
          reason: "the title is not centred");
      var albumTitleY = title.center.dy;

      // The listing's title, one line, on the same line.
      await tester.pumpWidget(const SizedBox());
      await pumpAt(tester, fixture("listing_sub.json"), path: const ["sub"]);
      var listingTitle = tester.getRect(find.text("Sub-listing"));
      expect(albumTitleY, moreOrLessEquals(listingTitle.center.dy, epsilon: 1),
          reason: "the title sits where every page's title sits");
      expect(listingTitle.left, moreOrLessEquals(title.left, epsilon: 1));
    });
  });

  group('a folder below the start page (#309, #310)', () {
    testWidgets('has the way up, no indicator, no bell, no home',
        (tester) async {
      await pumpAt(tester, fixture("listing_sub.json"), path: const ["sub"]);

      expect(barOf(tester).leading, isA<WayBackButton>());
      expect(upInActions(tester), isFalse);
      expect(find.byType(StatusIndicator), findsNothing);
      expect(find.byType(NewsBellButton), findsNothing);
      expect(find.byIcon(Icons.home), findsNothing);
    });

    testWidgets('has a black bar with light text in a light system theme',
        (tester) async {
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
      await pumpAt(tester, fixture("listing_sub.json"), path: const ["sub"]);

      expect(Theme.of(tester.element(find.byType(Scaffold))).brightness,
          Brightness.light,
          reason: "the page lives in the light theme");
      expect(barColor(tester), Colors.black);
      expect(textColor(tester, "Sub-listing")!.computeLuminance(),
          greaterThan(0.5));
    });
  });

  testWidgets('the start page has the indicator and the bell, and no home',
      (tester) async {
    await pumpAt(tester, fixture("listing.json"));

    expect(find.byType(StatusIndicator), findsOneWidget);
    expect(find.byType(NewsBellButton), findsOneWidget);
    expect(find.byIcon(Icons.home), findsNothing);
    expect(barOf(tester).leading, isNull, reason: "nothing to go up to");
    expect(barColor(tester), Colors.black);
  });

  group('an album (#309)', () {
    testWidgets('offers its search in the menu, not as a button',
        (tester) async {
      await pumpAt(tester, fixture("album.json"), path: const ["Album"]);
      expect(find.byType(AppBar), findsNothing,
          reason: "the floating controls of the view mode");

      expect(searchButtons(), findsNothing);
      expect(find.byType(StatusIndicator), findsNothing);
      expect(find.byType(NewsBellButton), findsNothing);
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("open-search")), findsOneWidget,
          reason: "an editor is offered the search");
    });

    testWidgets('keeps the edit mode free of the search, in a black bar',
        (tester) async {
      await pumpAt(tester, fixture("album.json"), path: const ["Album"]);
      await withFakeImageHttp(() async {
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
      });
      expect(barOf(tester).leading, isA<WayBackButton>());
      expect(upInActions(tester), isFalse);
      expect(barColor(tester), Colors.black);
      expect(searchButtons(), findsNothing);

      // Nor in its menu: a search would leave the edit with its changes
      // unsaved.
      await tester.tap(find.byIcon(Icons.more_vert).last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("open-search")), findsNothing);
    });
  });

  group('the settings follow the system theme (#310)', () {
    for (var brightness in Brightness.values) {
      testWidgets('in ${brightness.name} mode', (tester) async {
        tester.platformDispatcher.platformBrightnessTestValue = brightness;
        addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
        await tester.pumpWidget(MaterialApp(
          theme: appTheme(),
          darkTheme: appTheme(brightness: Brightness.dark),
          localizationsDelegates: testLocalizationsDelegates,
          supportedLocales: testSupportedLocales,
          home: ServerSettingsScreen(
            settings: settings.settingsWith(InMemorySettingsStore()),
            clientFor: (dataUrl) => VAlbumClient(
              dataUrl: dataUrl,
              httpClient: MockClient((request) async =>
                  http.Response(fixture("listing.json"), 200)),
            ),
          ),
        ));
        await tester.pumpAndSettle();

        var luminance = barColor(tester)!.computeLuminance();
        if (brightness == Brightness.light) {
          expect(luminance, greaterThan(0.5), reason: "a light bar");
        } else {
          expect(luminance, lessThan(0.2), reason: "a dark bar");
        }
        expect(barColor(tester), isNot(Colors.black),
            reason: "not the black bar of the photographs");
      });
    }

    testWidgets('the app carries a dark theme for the system to choose',
        (tester) async {
      await pumpAt(tester, fixture("listing.json"));
      var app = tester.widget<MaterialApp>(find.byType(MaterialApp));
      expect(app.darkTheme?.brightness, Brightness.dark);
      expect(app.themeMode, ThemeMode.system);
    });
  });

  group('every page that offered home keeps a way out (#309)', () {
    testWidgets('a path outside a link leads back to its album',
        (tester) async {
      var home = 0;
      await tester.pumpWidget(localizedApp(
          ShareConfinedScreen(message: "Not here.", onHome: () => home++)));
      expect(find.byIcon(Icons.home), findsNothing);
      await tester.tap(find.byKey(const Key("share-home")));
      expect(home, 1);
    });

    testWidgets('a dead invitation continues to the start page',
        (tester) async {
      var continued = 0;
      await tester.pumpWidget(localizedApp(
          ShareGoneScreen(message: "Gone.", onContinue: () => continued++)));
      expect(find.byIcon(Icons.home), findsNothing);
      await tester.tap(find.byKey(const Key("invitation-continue")));
      expect(continued, 1);
    });

    // The load failure's way up is `load_failure_test.dart`'s, the folder's
    // above.
  });
}
