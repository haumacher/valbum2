/// Tests of issue #171: on an edge-to-edge screen (Android 15) the last
/// control of every scrolling page ends above the navigation bar, and on a
/// screen without insets (the web, a desktop) every page is laid out exactly
/// as before.
///
/// Each page is pumped on a phone whose view reports a navigation bar of
/// [bar] at the bottom — as `padding` and `viewPadding`, which is what the
/// `MediaQuery` of the app is built from — scrolled to its end, and its last
/// control is measured. A page that does not overflow would say nothing, so
/// every case asserts first that there is something to scroll.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/device_code_scanner_plugin.dart';
import 'package:valbum_ui/first_screen.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/page_insets.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/trash_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'invitation_test.dart' as invitation;
import 'persons_view_test.dart' as persons;
import 'settings_test.dart' as settings;
import 'sign_in_required_test.dart' as sign_in;
import 'first_screen_test.dart' as first;
import 'trash_view_test.dart' as trash;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'video_view_test.dart' as video;

/// The height of the navigation bar of the tests.
const double bar = 48;

/// A phone upright, and one on its side.
const Size portrait = Size(360, 640);
const Size landscape = Size(640, 300);

/// Makes the test's view the given screen, with the given insets.
void useScreen(
  WidgetTester tester,
  Size size, {
  double bottom = 0,
  double left = 0,
  double right = 0,
}) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  var insets = FakeViewPadding(left: left, right: right, bottom: bottom);
  tester.view.padding = insets;
  tester.view.viewPadding = insets;
  addTearDown(tester.view.reset);
}

/// Scrolls [scrollable] to its end, after checking that it has one.
Future<void> scrollToEnd(WidgetTester tester, Finder scrollable) async {
  var position = tester.state<ScrollableState>(scrollable).position;
  expect(
    position.maxScrollExtent,
    greaterThan(0),
    reason: "the page must overflow the screen for the test to say anything",
  );
  position.jumpTo(position.maxScrollExtent);
  await tester.pumpAndSettle();
}

/// One scrolling page body: how it is shown, what scrolls, what comes last,
/// and the padding its scroll view had before issue #171.
class PageCase {
  final String name;
  final Size size;
  final Future<void> Function(WidgetTester tester) pump;
  final Finder Function() scrollable;
  final Finder Function() last;
  final EdgeInsetsGeometry? Function(WidgetTester tester) padding;
  final EdgeInsets formerPadding;

  const PageCase({
    required this.name,
    required this.size,
    required this.pump,
    required this.scrollable,
    required this.last,
    required this.padding,
    required this.formerPadding,
  });
}

/// The padding of the first [ListView] below [of].
EdgeInsetsGeometry? listPadding(WidgetTester tester, Finder of) => tester
    .widget<ListView>(
      find.descendant(of: of, matching: find.byType(ListView)).first,
    )
    .padding;

/// The padding of the first [SingleChildScrollView] below [of].
EdgeInsetsGeometry? scrollPadding(WidgetTester tester, Finder of) => tester
    .widget<SingleChildScrollView>(
      find
          .descendant(of: of, matching: find.byType(SingleChildScrollView))
          .first,
    )
    .padding;

/// The first [Scrollable] below [of].
Finder scrollableIn(Finder of) =>
    find.descendant(of: of, matching: find.byType(Scrollable)).first;

// --- The pages ------------------------------------------------------------

Future<void> pumpSettings(WidgetTester tester) => settings.pumpScreen(
      tester,
      settings.settingsWith(InMemorySettingsStore()),
      MockClient((request) async => http.Response(fixture("listing.json"), 200)),
    );

/// An album holding twelve trashed photographs: more than a phone shows.
String manyTrashed() =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", "rights": ['
    '${trash.editRights.map((right) => '{"name": "$right"}').join(",")}], '
    '"parts": [${[for (var i = 0; i < 12; i++) trash.part("t$i.jpg", -2)].join(",")}]}]';

Future<void> pumpTrash(WidgetTester tester) => trash.pump(
      tester,
      trash.server(requests: [], album: manyTrashed),
      route: const TrashRoute(["Zoo"]),
    );

/// Sixteen photographs, one unknown face each in a cluster of its own.
List<String> manyFaces() => [
      for (var i = 0; i < 16; i++)
        persons.imageOf("p$i.jpg", [persons.faceOf(0, cluster: "c$i")]),
    ];

Future<void> pumpPersons(WidgetTester tester) => persons.pumpEditor(
      tester,
      persons.editorClient(
        [],
        auth: persons.authOf(),
        album: persons.albumOf(images: manyFaces()),
      ),
    );

Future<void> pumpFirst(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: first.plainServer(),
      ),
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => null,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

Future<void> pumpInvitation(WidgetTester tester) async {
  await invitation.pumpInvitation(tester, invitation.liveInvitation());
}

Future<void> pumpSignInRequired(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: sign_in.refusingUntilPaired(requests: []),
      ),
      settings: ServerSettings(
        store: InMemorySettingsStore("http://server/valbum/"),
        platformDefault: () => null,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

/// A group of twelve landscape alternatives, linked as loading an album links.
ImageGroup manyAlternatives() {
  var group = ImageGroup(
    representative: 0,
    images: [
      for (var i = 0; i < 12; i++)
        ImagePart(name: "g$i.jpg", width: 2000, height: 1000),
    ],
  );
  AlbumInitializer().init(AlbumInfo(title: "Album", parts: [group]));
  return group;
}

Future<void> pumpGroup(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: GroupView(
          client: clientReturning("{}"),
          baseUrl: "http://server/valbum/data/album",
          group: manyAlternatives(),
          onUp: () {},
          onShowDetail: (_) {},
        ),
      ),
    );
    await tester.pumpAndSettle();
  });
}

final List<PageCase> pages = [
  PageCase(
    name: "the server screen",
    size: portrait,
    pump: pumpSettings,
    scrollable: () => scrollableIn(find.byType(ServerSettingsScreen)),
    last: () => find.byKey(diagnosticsSectionKey),
    padding: (tester) => listPadding(tester, find.byType(ServerSettingsScreen)),
    formerPadding: const EdgeInsets.all(16),
  ),
  PageCase(
    name: "the trash page",
    size: portrait,
    pump: pumpTrash,
    scrollable: () => scrollableIn(find.byType(TrashContent)),
    last: () => find.byKey(const ValueKey("trash-tile-t11.jpg")),
    padding: (tester) => scrollPadding(tester, find.byType(TrashContent)),
    formerPadding: const EdgeInsets.all(8),
  ),
  PageCase(
    name: "the persons editor",
    size: portrait,
    pump: pumpPersons,
    scrollable: () => scrollableIn(find.byType(PersonsContent)),
    last: () => find.byKey(const Key("persons-forget-target")),
    padding: (tester) => listPadding(tester, find.byType(PersonsContent)),
    formerPadding: const EdgeInsets.only(bottom: 32),
  ),
  PageCase(
    name: "the first screen",
    size: landscape,
    pump: pumpFirst,
    scrollable: () => scrollableIn(find.byKey(firstScreenKey)),
    last: () => find.byKey(firstScreenContinueKey),
    padding: (tester) => scrollPadding(tester, find.byKey(firstScreenKey)),
    formerPadding: const EdgeInsets.all(24),
  ),
  PageCase(
    name: "the invitation screen",
    size: landscape,
    pump: pumpInvitation,
    scrollable: () => scrollableIn(find.byKey(const Key("invitation-welcome"))),
    last: () => find.byKey(const Key("invitation-join")),
    padding: (tester) =>
        scrollPadding(tester, find.byKey(const Key("invitation-welcome"))),
    formerPadding: const EdgeInsets.all(24),
  ),
  PageCase(
    name: "the sign-in-required page",
    size: landscape,
    pump: pumpSignInRequired,
    scrollable: () => scrollableIn(find.byType(Scaffold).first),
    last: () => find.widgetWithIcon(TextButton, Icons.settings),
    padding: (tester) => scrollPadding(tester, find.byType(Scaffold).first),
    formerPadding: const EdgeInsets.all(24),
  ),
  PageCase(
    name: "the alternatives view",
    size: portrait,
    pump: pumpGroup,
    scrollable: () => scrollableIn(find.byType(GroupView)),
    last: () => find.byKey(const ValueKey("group-tile-g11.jpg")),
    padding: (tester) => scrollPadding(tester, find.byType(GroupView)),
    formerPadding: EdgeInsets.zero,
  ),
];

void main() {
  group("pagePadding", () {
    testWidgets("adds every side of the MediaQuery padding to the page's own",
        (tester) async {
      late EdgeInsets padding;
      await tester.pumpWidget(MediaQuery(
        data: const MediaQueryData(
          padding: EdgeInsets.fromLTRB(1, 2, 3, 4),
        ),
        child: Builder(builder: (context) {
          padding = pagePadding(context, const EdgeInsets.all(10));
          return const SizedBox();
        }),
      ));
      expect(padding, const EdgeInsets.fromLTRB(11, 12, 13, 14));
    });

    testWidgets("is the page's own padding where the screen has no insets",
        (tester) async {
      late EdgeInsets padding;
      late EdgeInsets none;
      await tester.pumpWidget(MediaQuery(
        data: const MediaQueryData(),
        child: Builder(builder: (context) {
          padding = pagePadding(context, const EdgeInsets.all(16));
          none = pagePadding(context);
          return const SizedBox();
        }),
      ));
      expect(padding, const EdgeInsets.all(16));
      expect(none, EdgeInsets.zero);
    });
  });

  for (var page in pages) {
    group(page.name, () {
      testWidgets("ends above the navigation bar", (tester) async {
        useScreen(tester, page.size);
        await page.pump(tester);
        await scrollToEnd(tester, page.scrollable());
        var without = tester.getRect(page.last()).bottom;

        // The bar appears: the page keeps the very gap it had above the
        // bottom of the screen, now above the bar.
        const insets = FakeViewPadding(bottom: bar);
        tester.view.padding = insets;
        tester.view.viewPadding = insets;
        await tester.pumpAndSettle();
        await scrollToEnd(tester, page.scrollable());

        expect(page.last(), findsOneWidget);
        var bottom = tester.getRect(page.last()).bottom;
        expect(bottom, lessThanOrEqualTo(page.size.height - bar));
        expect(bottom, moreOrLessEquals(without - bar));
      });

      testWidgets("is laid out as before on a screen without insets",
          (tester) async {
        useScreen(tester, page.size);
        await page.pump(tester);

        expect(
          page.padding(tester) ?? EdgeInsets.zero,
          page.formerPadding,
        );
        await scrollToEnd(tester, page.scrollable());
        expect(
          tester.getRect(page.last()).bottom,
          lessThanOrEqualTo(page.size.height),
        );
      });
    });
  }

  testWidgets("the alternatives keep clear of a navigation bar at the side",
      (tester) async {
    useScreen(tester, portrait, left: 40, right: 40);
    await pumpGroup(tester);

    var tiles = find.byWidgetPredicate((widget) =>
        widget.key is ValueKey<String> &&
        (widget.key as ValueKey<String>).value.startsWith("group-tile-"));
    expect(tiles, findsWidgets);
    for (var element in tiles.evaluate()) {
      var rect = tester.getRect(find.byWidget(element.widget));
      expect(rect.left, greaterThanOrEqualTo(40 - 0.01));
      expect(rect.right, lessThanOrEqualTo(portrait.width - 40 + 0.01));
    }
    expect(tester.takeException(), isNull);
  });

  group("the video controls", () {
    late FakeVideoPlayerPlatform platform;

    setUp(() {
      platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
    });

    testWidgets("run to the edge and keep their buttons above the bar",
        (tester) async {
      useScreen(tester, portrait, bottom: bar);
      await video.pumpVideo(tester);

      var controls = find.byKey(const Key("video-controls"));
      expect(tester.getRect(controls).bottom, portrait.height);
      expect(
        tester.getRect(find.byKey(const Key("video-play-pause"))).bottom,
        lessThanOrEqualTo(portrait.height - bar),
      );
    });

    testWidgets("do not grow by the status bar at the top", (tester) async {
      useScreen(tester, portrait, bottom: bar);
      tester.view.padding = const FakeViewPadding(top: 24, bottom: bar);
      await video.pumpVideo(tester);

      expect(
        tester
            .widget<Container>(find.byKey(const Key("video-controls")))
            .padding,
        const EdgeInsets.fromLTRB(8, 4, 8, 4 + bar),
      );
    });

    testWidgets("are padded as before on a screen without insets",
        (tester) async {
      useScreen(tester, portrait);
      await video.pumpVideo(tester);

      expect(
        tester
            .widget<Container>(find.byKey(const Key("video-controls")))
            .padding,
        const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      );
    });
  });

  group("the scanner page", () {
    Future<void> pumpScanner(WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: DeviceCodeScannerPage(),
      ));
      await tester.pump();
    }

    /// The advice standing at the foot of the camera picture.
    Finder advice() => find
        .ancestor(
          of: find.text(testL10n.scanCodeAdvice),
          matching: find.byType(Positioned),
        )
        .first;

    testWidgets("puts its advice above the navigation bar", (tester) async {
      useScreen(tester, portrait, bottom: bar, left: 10, right: 20);
      await pumpScanner(tester);

      var rect = tester.getRect(advice());
      expect(rect.bottom, portrait.height - bar - 24);
      expect(rect.left, 10 + 16);
      expect(rect.right, portrait.width - 20 - 16);
    });

    testWidgets("puts its advice where it was on a screen without insets",
        (tester) async {
      useScreen(tester, portrait);
      await pumpScanner(tester);

      var rect = tester.getRect(advice());
      expect(rect.bottom, portrait.height - 24);
      expect(rect.left, 16);
      expect(rect.right, portrait.width - 16);
    });
  });
}
