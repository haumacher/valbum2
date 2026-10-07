/// The About dialog, issue #187: "Add an 'about' dialog to the menus (also
/// and especially for the share link view) telling about the app and linking
/// to the github page."
///
/// The entry stands last in every main menu — the listing, the album, the
/// inbox, the viewer — and inside a share link, whose album floats the menu
/// a visitor has, and on the bare page a dead link ends on. The dialog names
/// the app, says what it is, shows the version the release build was given
/// (none where it was given none) and opens the GitHub page.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/about.dart';
import 'package:valbum_ui/main.dart';

import 'album_menu_actions_test.dart' show pumpAlbum;
import 'download_test.dart' show menuKeys, pumpViewer, viewerClient;
import 'inbox_view_test.dart' show inboxTree, openMenu, pumpInbox;
import 'move_test.dart' show openAlbumMenu, treeAnswer;
import 'share_album_chrome_test.dart' as chrome;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart' show withEmptyImageCache;

/// Gives the dialog a version for the running test, as a release build has.
void withVersion(String version) {
  var before = shownVersion;
  shownVersion = version;
  addTearDown(() => shownVersion = before);
}

/// Records what the dialog asks to open instead of opening it.
List<Uri> recordLaunches() {
  var launched = <Uri>[];
  var before = openExternalUrl;
  openExternalUrl = (url) async {
    launched.add(url);
    return true;
  };
  addTearDown(() => openExternalUrl = before);
  return launched;
}

/// Taps the About entry of the open menu and checks the dialog it opens.
Future<void> expectAboutFromOpenMenu(WidgetTester tester) async {
  var keys = menuKeys(tester);
  expect(keys, contains("about"));
  expect(keys.last, "about", reason: "the last entry of the menu");
  await tester.tap(find.byKey(const Key("about")));
  await tester.pumpAndSettle();
  expectAboutDialog();
}

void expectAboutDialog() {
  expect(find.byType(AboutDialog), findsOneWidget);
  expect(find.text("VAlbum"), findsOneWidget);
  expect(find.text(testL10n.aboutVersion("2.10.0")), findsOneWidget);
  expect(find.text(testL10n.aboutDescription), findsOneWidget);
  expect(find.byKey(const Key("about-github")), findsOneWidget);
  // The address is readable, not only clickable.
  expect(find.text(githubUrl), findsOneWidget);
  // The licence, which the repository's LICENSE states.
  expect(find.text(testL10n.aboutLicense), findsOneWidget);
  // The credit the place names' licence asks for (CC BY 4.0, issue #234).
  expect(find.text(testL10n.aboutGeoNames), findsOneWidget);
  expect(find.byKey(const Key("about-geonames")), findsOneWidget);
  expect(find.text(geoNamesUrl), findsOneWidget);
}

void main() {
  test('a mail or a text opens in this window, a web page in a new one', () {
    expect(externalWindowName(Uri.parse("mailto:petra@gmx.de?subject=x")),
        "_top");
    expect(externalWindowName(Uri.parse("sms:+4917012345?body=x")), "_top");
    expect(externalWindowName(Uri.parse("https://wa.me/4917012345")), isNull);
    expect(externalWindowName(Uri.parse("https://github.com/x")), isNull);
  });

  setUp(withEmptyImageCache);

  testWidgets('the listing menu ends with About', (tester) async {
    withVersion("2.10.0");
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, treeAnswer, route: const []);
      await openAlbumMenu(tester);
      await expectAboutFromOpenMenu(tester);
    });
  });

  testWidgets('the album menu ends with About', (tester) async {
    withVersion("2.10.0");
    await withFakeImageHttp(() async {
      await pumpAlbum(tester, treeAnswer);
      expect(find.byType(AlbumContent), findsOneWidget);
      await openAlbumMenu(tester);
      await expectAboutFromOpenMenu(tester);
    });
  });

  testWidgets('the inbox menu ends with About', (tester) async {
    withVersion("2.10.0");
    await pumpInbox(tester, inboxTree);
    await withFakeImageHttp(() async {
      await openMenu(tester);
      await expectAboutFromOpenMenu(tester);
    });
  });

  testWidgets('the viewer has a menu with About even for a mere viewer',
      (tester) async {
    withVersion("2.10.0");
    // `view` alone: neither the download nor the faces would open the menu,
    // which before #187 was therefore not there at all.
    await pumpViewer(tester, viewerClient([]), rights: const ["view"]);
    await tester.tap(find.byKey(const Key("viewer-menu")));
    await tester.pumpAndSettle();
    expect(menuKeys(tester), ["about"]);
    await expectAboutFromOpenMenu(tester);
  });

  group('a share link', () {
    testWidgets(
        'offers About in the floating menu of its album, and nothing '
        'a member does', (tester) async {
      withVersion("2.10.0");
      await chrome.pumpSession(tester, chrome.albumLink());
      // #97's look is kept: no app bar, the album's own heading once.
      expect(find.byType(AppBar), findsNothing);
      expect(find.text("Zoo"), findsOneWidget);
      await openAlbumMenu(tester);
      var keys = menuKeys(tester);
      for (var memberOnly in const [
        "album-properties",
        "move-to",
        "delete-album",
        "download-selection",
        "add-heading",
        "persons",
        "show-trash",
        "reanalyze",
        "refresh-previews",
        "view-as-public",
      ]) {
        expect(keys, isNot(contains(memberOnly)), reason: memberOnly);
      }
      expect(find.text(testL10n.serverMenuEntry), findsNothing);
      await expectAboutFromOpenMenu(tester);
    });

    testWidgets('offers About on the page of a link that is gone',
        (tester) async {
      withVersion("2.10.0");
      await tester.pumpWidget(
          localizedApp(const ShareGoneScreen(message: "The link is gone.")));
      await tester.tap(find.byKey(const Key("about")));
      await tester.pumpAndSettle();
      expectAboutDialog();
    });
  });

  group('the dialog', () {
    Future<void> pumpDialog(WidgetTester tester, {Locale? locale}) async {
      await tester.pumpWidget(localizedApp(
        Builder(
          builder: (context) => TextButton(
            onPressed: () => showAbout(context),
            child: const Text("open"),
          ),
        ),
        locale: locale ?? defaultTestLocale,
      ));
      await tester.tap(find.text("open"));
      await tester.pumpAndSettle();
    }

    testWidgets('opens the GitHub page', (tester) async {
      var launched = recordLaunches();
      await pumpDialog(tester);
      await tester.tap(find.byKey(const Key("about-github")));
      await tester.pumpAndSettle();
      expect(launched, [Uri.parse("https://github.com/haumacher/valbum2")]);
    });

    testWidgets('opens GeoNames, where the place names come from',
        (tester) async {
      var launched = recordLaunches();
      await pumpDialog(tester);
      expect(find.text(testL10n.aboutGeoNames), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key("about-geonames")));
      await tester.tap(find.byKey(const Key("about-geonames")));
      await tester.pumpAndSettle();
      expect(launched, [Uri.parse("https://www.geonames.org/")]);
    });

    testWidgets('shows no version where the build was given none',
        (tester) async {
      withVersion("");
      await pumpDialog(tester);
      expect(find.text("VAlbum"), findsOneWidget);
      expect(find.textContaining("Version"), findsNothing);
      expect(find.textContaining("1.0.0"), findsNothing);
    });

    testWidgets('speaks German on a German device', (tester) async {
      withVersion("2.10.0");
      var de = l10nOf(const Locale("de"));
      await pumpDialog(tester, locale: const Locale("de"));
      expect(find.text(de.aboutDescription), findsOneWidget);
      expect(find.text(de.aboutSourceCode), findsOneWidget);
      expect(find.text(de.aboutVersion("2.10.0")), findsOneWidget);
      expect(find.text(de.aboutLicense), findsOneWidget);
      expect(find.text(de.aboutGeoNames), findsOneWidget);
      expect(de.aboutGeoNames, isNot(testL10n.aboutGeoNames));
      expect(de.aboutMenuEntry, isNot(testL10n.aboutMenuEntry));
    });
  });
}
