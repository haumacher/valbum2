/// The bursts of an album, stacked into one tile each (issue #257, package
/// 6): the server answers them derived ([AlbumInfo.stacks]), the grid shows
/// each as its best shot with a count, a tap opens it in place, the viewer
/// pages through every photograph as ever, the stacking is a view of this
/// device switched in the album's menu and remembered, a tile of a burst
/// selects all of its shots, and the edit mode shows every photograph singly.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/album_stacks.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' show openAlbumMenu, tile, longPressTile;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

const String dataUrl = "http://server/valbum/data";

/// Every photograph of the album, in its order.
const List<String> allNames = [
  "p0.jpg",
  "p1.jpg",
  "p2.jpg",
  "p3.jpg",
  "p4.jpg",
  "x.jpg",
];

/// The burst of the album, its best shot first.
const List<String> burst = ["p2.jpg", "p0.jpg", "p1.jpg", "p3.jpg", "p4.jpg"];

String image(String name, int index) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", "labels": [], '
    '"date": ${1015113600000 + index * 60000}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": 0}]';

/// The album `Burst` carrying [rights]: five shots of a burst and one more.
String burstAlbum(List<String> rights, {bool stacked = true}) =>
    '["AlbumInfo", {"path": "Burst", "title": "Burst", "subTitle": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"stacks": [${stacked ? '{"members": [${burst.map((n) => '{"name": "$n"}').join(", ")}]}' : ""}], '
    '"parts": [${[
      for (var (index, name) in allNames.indexed) image(name, index)
    ].join(", ")}]}]';

/// Opens the album `Burst` for a caller holding [rights], remembering the
/// stacking in [store].
Future<void> pumpBurst(
  WidgetTester tester, {
  List<String> rights = const ["view", "download", "contribute", "edit"],
  InMemorySettingsStore? store,
  VAlbumRoute route = const ListingOrAlbumRoute(["Burst"]),
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "tok-9",
    httpClient: MockClient(servingThumbnails((request) async {
      var path = Uri.decodeFull(request.url.path);
      if (path == "/valbum/data/Burst/") {
        return http.Response(burstAlbum(rights), 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }
      if (path.startsWith("/valbum/data/Burst/") && request.url.query.isEmpty) {
        return http.Response.bytes(transparentPixelPng, 200,
            headers: {"content-type": "image/png"});
      }
      return http.Response(
          '["ListingInfo", {"path": "", "title": "Library", "folders": []}]',
          200,
          headers: {"content-type": "application/json; charset=utf-8"});
    })),
  );
  var settings = ServerSettings(
    store: store ??
        InMemorySettingsStore(
            "http://server/valbum/", "tok-9", "Phone", "anna"),
  );
  await settings.load();
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: settings,
    initialRoute: route,
  ));
  await tester.pumpAndSettle();
}

/// The names whose tiles the album shows.
List<String> tiles() => [
      for (var name in allNames)
        if (tile(name).evaluate().isNotEmpty) name,
    ];

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.widget<VAlbumNavigator>(find.byType(VAlbumNavigator)).route;

Finder badge(String name) => find.byKey(ValueKey("stack-badge-$name"));

void main() {
  setUp(withEmptyImageCache);

  group('a burst', () {
    testWidgets('shows as one tile, its best shot, with the count',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        expect(tiles(), ["p2.jpg", "x.jpg"],
            reason: "The best shot stands for the burst.");
        expect(badge("p2.jpg"), findsOneWidget);
        expect(
            tester
                .widget<Text>(find.byKey(const ValueKey("stack-count-p2.jpg")))
                .data,
            "5");
        expect(find.byTooltip(testL10n.stackCount(5)), findsOneWidget);
        expect(badge("x.jpg"), findsNothing);
      });
    });

    testWidgets('opens in place, and folds again', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        await tester.tap(tile("p2.jpg"));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ListingOrAlbumRoute(["Burst"]),
            reason: "Opening a burst opens no viewer.");
        expect(tiles(), allNames);
        expect(badge("p2.jpg"), findsNothing);

        // A shot of the opened burst opens the viewer like any tile.
        await tester.tap(tile("p0.jpg"));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "p0.jpg"));
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const ValueKey("stack-fold-p2.jpg")));
        await tester.pumpAndSettle();
        expect(tiles(), ["p2.jpg", "x.jpg"]);
      });
    });

    testWidgets('hides nothing from the viewer', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        await tester.tap(tile("x.jpg"));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "x.jpg"));
        var seen = <String>["x.jpg"];
        for (var step = 0; step < allNames.length - 1; step++) {
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
          await tester.pumpAndSettle();
          seen.add((routeOf(tester) as ImageRoute).name);
        }
        expect(seen, allNames.reversed.toList(),
            reason: "The viewer pages through every photograph of the album.");
      });
    });

    testWidgets('opens on Enter from the keyboard', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        // The keyboard walks the tiles shown: the burst's first.
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        await tester.sendKeyEvent(LogicalKeyboardKey.enter);
        await tester.pumpAndSettle();
        expect(tiles(), allNames);
        expect(routeOf(tester), const ListingOrAlbumRoute(["Burst"]));
      });
    });
  });

  group('the stacking', () {
    testWidgets('is switched off in the menu, and remembered', (tester) async {
      await withFakeImageHttp(() async {
        var store = InMemorySettingsStore(
            "http://server/valbum/", "tok-9", "Phone", "anna");
        await pumpBurst(tester, store: store);
        await openAlbumMenu(tester);
        expect(find.text(testL10n.unstackShotsAction), findsOneWidget);
        await tester.tap(find.byKey(const Key("toggle-stacks")));
        await tester.pumpAndSettle();
        expect(tiles(), allNames, reason: "Every shot singly.");
        expect(badge("p2.jpg"), findsNothing);
        expect(store.albumStacking, isFalse);

        // Opened again, as after a restart of the app.
        await tester.pumpWidget(const SizedBox());
        await pumpBurst(tester, store: store);
        expect(tiles(), allNames, reason: "Remembered on this device.");
        await openAlbumMenu(tester);
        expect(find.text(testL10n.stackShotsAction), findsOneWidget);
        await tester.tap(find.byKey(const Key("toggle-stacks")));
        await tester.pumpAndSettle();
        expect(tiles(), ["p2.jpg", "x.jpg"]);
        expect(store.albumStacking, isTrue);
      });
    });

    testWidgets('is on where nothing was chosen', (tester) async {
      await withFakeImageHttp(() async {
        var store = InMemorySettingsStore(
            "http://server/valbum/", "tok-9", "Phone", "anna");
        expect(await store.loadAlbumStacking(), isNull);
        await pumpBurst(tester, store: store);
        expect(tiles(), ["p2.jpg", "x.jpg"]);
      });
    });
  });

  group('selecting', () {
    testWidgets('a burst selects all of its shots', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester, rights: const ["view", "download"]);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("select-mode")));
        await tester.pumpAndSettle();

        await tester.tap(tile("p2.jpg"));
        await tester.pumpAndSettle();
        expect(
            tester
                .widget<Text>(find.byKey(const Key("select-mode-count")))
                .data,
            testL10n.selectedCount(5));
        expect(
            find.descendant(
                of: tile("p2.jpg"),
                matching: find.byKey(const Key("select-mark-on"))),
            findsOneWidget);

        await tester.tap(tile("p2.jpg"));
        await tester.pumpAndSettle();
        expect(
            tester
                .widget<Text>(find.byKey(const Key("select-mode-count")))
                .data,
            testL10n.selectedCount(0));

        // Ctrl+A takes the shots under the tile too.
        await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
        await tester.sendKeyEvent(LogicalKeyboardKey.keyA);
        await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
        await tester.pumpAndSettle();
        expect(
            tester
                .widget<Text>(find.byKey(const Key("select-mode-count")))
                .data,
            testL10n.selectedCount(allNames.length));
      });
    });

    testWidgets('arranging shows every shot singly', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        expect(tiles(), ["p2.jpg", "x.jpg"]);
        await longPressTile(tester, "x.jpg");
        expect(tiles(), allNames, reason: "The edit mode stacks nothing.");
        expect(badge("p2.jpg"), findsNothing);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(tiles(), ["p2.jpg", "x.jpg"]);
      });
    });
  });

  group('the view of the parts', () {
    ImagePart part(String name) => ImagePart(name: name);
    PhotoStack stack(List<String> names) =>
        PhotoStack(members: [for (var name in names) StackMember(name: name)]);

    test('folds what is shown, the first shown on top', () {
      var parts = <AlbumPart>[for (var name in allNames) part(name)];
      var view = stackView(parts, [stack(burst)], isOpened: (_) => false);
      expect([for (var p in view.parts) (p as ImagePart).name],
          ["p2.jpg", "x.jpg"]);
      expect(view.hiddenUnder["p0.jpg"], "p2.jpg");

      // A filter hiding the best shot puts the next one on top.
      var filtered = [
        for (var p in parts)
          if ((p as ImagePart).name != "p2.jpg") p
      ];
      var narrower =
          stackView(filtered, [stack(burst)], isOpened: (_) => false);
      expect([for (var p in narrower.parts) (p as ImagePart).name],
          ["p0.jpg", "x.jpg"]);
    });

    test('a burst of one shown is no burst, an opened one hides nothing', () {
      var parts = <AlbumPart>[part("p0.jpg"), part("x.jpg")];
      expect(stackView(parts, [stack(burst)], isOpened: (_) => false).parts,
          hasLength(2));
      var all = <AlbumPart>[for (var name in allNames) part(name)];
      var opened = stackView(all, [stack(burst)], isOpened: (tile) => true);
      expect(opened.parts, hasLength(allNames.length));
      expect(opened.opened, hasLength(1));
      var kept = stackView(all, [stack(burst)],
          isOpened: (_) => false, keepShown: {"p3.jpg"});
      expect(kept.parts, hasLength(allNames.length),
          reason: "The photo the album was opened for is never hidden.");
    });
  });
}
