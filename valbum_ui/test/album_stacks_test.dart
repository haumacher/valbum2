/// The bursts of an album, stacked into one tile each (issue #257, package
/// 6, and issue #314): the server answers them derived ([AlbumInfo.stacks]),
/// the grid shows each as its best shot with a count, and a burst is a group
/// the author never made — a tap opens the viewer on the best shot, which
/// pages past the burst's other shots, and "down" shows them all. The
/// stacking is a view of this device switched in the album's menu and
/// remembered, a tile of a burst selects all of its shots, and a long press
/// switches the mode with the bursts left as they are.
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

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent).last);

/// The names of the selected photographs of the album shown.
Set<String> selectedNames(WidgetTester tester) => {
      for (var part in albumState(tester).selection)
        if (part is ImagePart) part.name,
    };

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

    testWidgets('opens the viewer on its best shot, which pages past it',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        await tester.tap(tile("p2.jpg"));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "p2.jpg"),
            reason: "The tile opens the viewer, as a group's does (#314).");
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "x.jpg"),
            reason: "Next is the tile after the burst, not a shot of it.");
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "p2.jpg"));
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(tiles(), ["p2.jpg", "x.jpg"], reason: "Nothing unfolded.");
      });
    });

    testWidgets('shows all of its shots below the viewer', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        await tester.tap(tile("p2.jpg"));
        await tester.pumpAndSettle();
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const AlternativesRoute(["Burst"], "p2.jpg"));
        for (var name in burst) {
          expect(find.byKey(ValueKey("group-tile-$name")), findsOneWidget);
        }
        await tester.tap(find.byKey(const ValueKey("group-tile-p2.jpg")));
        await tester.pumpAndSettle();
        var seen = <String>[(routeOf(tester) as MemberRoute).member];
        for (var step = 0; step < burst.length - 1; step++) {
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
          await tester.pumpAndSettle();
          seen.add((routeOf(tester) as MemberRoute).member);
        }
        expect(seen, burst, reason: "The shots in the burst's order.");
      });
    });

    testWidgets('opens the viewer on Enter', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        // The keyboard walks the tiles shown: the burst's first.
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        await tester.sendKeyEvent(LogicalKeyboardKey.enter);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Burst"], "p2.jpg"),
            reason: "Enter opens the viewer on the tile's shot (#314).");
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

    testWidgets('switched off, the viewer walks every shot', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("toggle-stacks")));
        await tester.pumpAndSettle();
        expect(tiles(), allNames);
        await tester.tap(tile("p0.jpg"));
        await tester.pumpAndSettle();
        var seen = <String>[(routeOf(tester) as ImageRoute).name];
        for (var step = 0; step < allNames.length - 1; step++) {
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
          await tester.pumpAndSettle();
          seen.add((routeOf(tester) as ImageRoute).name);
        }
        expect(seen, allNames);
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
        await tester.pumpAndSettle();
        expect(routeOf(tester), isA<ImageRoute>(),
            reason: "A single shot has no alternatives.");
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

    testWidgets('arranging keeps the bursts as they are shown', (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester);
        expect(tiles(), ["p2.jpg", "x.jpg"]);
        await longPressTile(tester, "x.jpg");
        expect(albumState(tester).editMode, isTrue);
        expect(tiles(), ["p2.jpg", "x.jpg"],
            reason: "The edit mode keeps the burst folded (#314).");
        expect(badge("p2.jpg"), findsOneWidget);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(tiles(), ["p2.jpg", "x.jpg"]);
      });
    });

    testWidgets('a long press into the select mode takes the whole burst',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpBurst(tester, rights: const ["view", "download"]);
        await longPressTile(tester, "p2.jpg");
        expect(albumState(tester).selectMode, isTrue);
        expect(tiles(), ["p2.jpg", "x.jpg"], reason: "Still folded.");
        expect(
            tester
                .widget<Text>(find.byKey(const Key("select-mode-count")))
                .data,
            testL10n.selectedCount(5));

      });
    });
  });

  group('the view of the parts', () {
    ImagePart part(String name) => ImagePart(name: name);
    PhotoStack stack(List<String> names) =>
        PhotoStack(members: [for (var name in names) StackMember(name: name)]);

    test('folds what is shown, the first shown on top', () {
      var parts = <AlbumPart>[for (var name in allNames) part(name)];
      var view = stackView(parts, [stack(burst)]);
      expect([for (var p in view.parts) (p as ImagePart).name],
          ["p2.jpg", "x.jpg"]);
      expect(view.hiddenUnder["p0.jpg"], "p2.jpg");

      // A filter hiding the best shot puts the next one on top.
      var filtered = [
        for (var p in parts)
          if ((p as ImagePart).name != "p2.jpg") p
      ];
      var narrower = stackView(filtered, [stack(burst)]);
      expect([for (var p in narrower.parts) (p as ImagePart).name],
          ["p0.jpg", "x.jpg"]);
    });

    test('a burst of one shown is no burst', () {
      var parts = <AlbumPart>[part("p0.jpg"), part("x.jpg")];
      expect(stackView(parts, [stack(burst)]).parts, hasLength(2));
    });

    AlbumInfo album() => AlbumInfo(
          path: "Burst",
          parts: [for (var name in allNames) part(name)],
          stacks: [stack(burst)],
        );

    test('a burst is a group to the viewer, the album stores none', () {
      var self = album();
      AlbumInitializer().init(self);
      expect(self.parts, hasLength(allNames.length),
          reason: "The stored parts are untouched.");
      var best = self.parts[2] as ImagePart;
      var group = best.group!;
      expect(isStackGroup(group), isTrue);
      expect([for (var m in group.images) m.name], burst);
      expect(group.images[group.representative].name, "p2.jpg");
      expect(group.next, same(self.parts[5]), reason: "Next is x.jpg.");
      expect((self.parts[5] as ImagePart).previous, same(group));
      expect(group.previous, isNull, reason: "p0 and p1 are in the burst.");
      expect(self.toString(), isNot(contains("ImageGroup")));

      setAlbumStacking(self, false);
      AlbumInitializer().init(self);
      expect(best.group, isNull);
      expect(best.next, same(self.parts[3]));
    });

    test('a chosen picture makes the burst a stored group', () {
      var self = album();
      AlbumInitializer().init(self);
      var stack = (self.parts[2] as ImagePart).group!;
      var chosen = stack.images[1];
      var group = storeStack(self, stack, chosen)!;
      AlbumInitializer().init(self);
      expect([
        for (var p in self.parts) p is ImagePart ? p.name : "group",
      ], ["group", "x.jpg"], reason: "Where the tile (p2) stood.");
      expect(isStackGroup(group), isFalse);
      expect([for (var m in group.images) m.name], burst);
      expect(group.images[group.representative].name, "p0.jpg");
      expect(stackView(self.parts, self.stacks).folded, isEmpty,
          reason: "Grouped shots are no burst any more.");
    });
  });
}
