/// The viewer walks exactly the bursts the grid shows (issue #316): as this
/// device stacks them — known before any album is loaded, so that a viewer
/// opened by its address, without the grid ever shown, pages as the grid
/// would — and as the album is filtered at that moment: a shot the rating
/// floor or the label filter hides is no shot of the burst, the best shown
/// shot is its tile and the picture the viewer opens, and a burst filtered
/// down to one shot is none.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_layout.dart' show ToImage;
import 'package:valbum_ui/album_stacks.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'album_stacks_test.dart' show albumState, badge, routeOf;
import 'move_test.dart' show tile;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fixtures.dart';
import 'util/viewer_harness.dart';

/// Every photograph of the album `Walk`, in its stored order.
const List<String> walkNames = ["s.jpg", "a.jpg", "b.jpg", "c.jpg", "z.jpg"];

/// The burst of the album, its best shot first.
const List<String> walkBurst = ["a.jpg", "b.jpg", "c.jpg"];

String _photo(String name, int index, int rating, List<String> labels) =>
    '["ImagePart", {"kind": "IMAGE", "name": "$name", '
    '"labels": [${labels.map((l) => '{"name": "$l"}').join(", ")}], '
    '"date": ${1015113600000 + index * 60000}, "width": 2048, '
    '"height": 1536, "orientation": "IDENTITY", "rating": $rating}]';

/// The album `Walk`: a photo, the burst of three, a photo; the shots rated
/// by [ratings] (0 where not named) and labelled by [labels].
String walkAlbum({
  Map<String, int> ratings = const {},
  Map<String, List<String>> labels = const {},
}) =>
    '["AlbumInfo", {"path": "Walk", "title": "Walk", "subTitle": "", '
    '"minRating": 0, '
    '"rights": [{"name": "view"}, {"name": "download"}, {"name": "edit"}], '
    '"stacks": [{"members": [${walkBurst.map((n) => '{"name": "$n"}').join(", ")}]}], '
    '"parts": [${[
      for (var (index, name) in walkNames.indexed)
        _photo(name, index, ratings[name] ?? 0, labels[name] ?? const []),
    ].join(", ")}]}]';

/// Opens [route] of the album `Walk` answered as [album], the device's
/// choices kept in [store].
Future<void> pumpWalk(
  WidgetTester tester, {
  required String album,
  InMemorySettingsStore? store,
  VAlbumRoute route = const ListingOrAlbumRoute(["Walk"]),
}) async {
  var client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    token: "tok-9",
    httpClient: MockClient(servingThumbnails((request) async {
      var path = Uri.decodeFull(request.url.path);
      if (path == "/valbum/data/Walk/") {
        return http.Response(album, 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }
      if (path.startsWith("/valbum/data/Walk/") && request.url.query.isEmpty) {
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
List<String> walkTiles() => [
      for (var name in walkNames)
        if (tile(name).evaluate().isNotEmpty) name,
    ];

/// The name of the photograph the viewer shows: a group's picture.
String shownImage(WidgetTester tester) =>
    ToImage.toImage(tester.widget<ImageView>(find.byType(ImageView)).image)
        .name;

/// The photographs the viewer shows from where it stands, stepping next
/// [steps] times.
Future<List<String>> walk(WidgetTester tester, int steps) async {
  var seen = [shownImage(tester)];
  for (var step = 0; step < steps; step++) {
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pumpAndSettle();
    seen.add(shownImage(tester));
  }
  return seen;
}

/// The alternatives the "down" of the viewer shows.
Future<List<String>> alternatives(WidgetTester tester) async {
  await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
  await tester.pumpAndSettle();
  expect(find.byType(GroupView), findsOneWidget);
  return [
    for (var name in walkNames)
      if (find.byKey(ValueKey("group-tile-$name")).evaluate().isNotEmpty) name,
  ];
}

Future<void> open(WidgetTester tester, String name) async {
  await tester.tap(tile(name));
  await tester.pumpAndSettle();
}

Future<void> back(WidgetTester tester) async {
  await tester.sendKeyEvent(LogicalKeyboardKey.escape);
  await tester.pumpAndSettle();
}

InMemorySettingsStore stackingStore({required bool stacking}) =>
    InMemorySettingsStore("http://server/valbum/", "tok-9", "Phone", "anna")
      ..albumStacking = stacking;

void main() {
  setUp(withEmptyImageCache);

  group('a viewer opened by its address', () {
    testWidgets('walks every shot where the device does not stack',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpWalk(
          tester,
          album: walkAlbum(),
          store: stackingStore(stacking: false),
          route: const ImageRoute(["Walk"], "s.jpg"),
        );
        expect(find.byType(AlbumContent), findsNothing,
            reason: "The grid was never shown.");
        expect(await walk(tester, 4), walkNames,
            reason: "One shot after the other, no group, nothing skipped.");
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
        await tester.pumpAndSettle();
        expect(find.byType(GroupView), findsNothing,
            reason: "A single shot has no alternatives.");
      });
    });

    testWidgets('walks the burst as one step where the device stacks',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpWalk(
          tester,
          album: walkAlbum(),
          store: stackingStore(stacking: true),
          route: const ImageRoute(["Walk"], "s.jpg"),
        );
        expect(await walk(tester, 3), ["s.jpg", "a.jpg", "z.jpg", "z.jpg"]);
      });
    });
  });

  group('a filter hiding the best shot', () {
    Future<void> expectTileB(WidgetTester tester) async {
      expect(walkTiles(), ["s.jpg", "b.jpg", "z.jpg"]);
      expect(badge("b.jpg"), findsOneWidget);
      expect(
          tester
              .widget<Text>(find.byKey(const ValueKey("stack-count-b.jpg")))
              .data,
          "2");

      await open(tester, "b.jpg");
      expect(routeOf(tester), const ImageRoute(["Walk"], "b.jpg"));
      expect(shownImage(tester), "b.jpg",
          reason: "The viewer opens on the tile, not on the hidden best shot.");
      expect(await alternatives(tester), ["b.jpg", "c.jpg"],
          reason: "The hidden shot is no alternative of the burst.");
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(routeOf(tester), const ListingOrAlbumRoute(["Walk"]));

      await open(tester, "s.jpg");
      expect(await walk(tester, 3), ["s.jpg", "b.jpg", "z.jpg", "z.jpg"],
          reason: "The burst is one step, landing on its tile.");
      await back(tester);
    }

    testWidgets('the rating floor: the viewer opens the tile shown',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpWalk(tester, album: walkAlbum(ratings: {"a.jpg": -1}));
        await expectTileB(tester);
      });
    });

    testWidgets('lowered again, the viewer follows the floor at once',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpWalk(tester, album: walkAlbum(ratings: {"a.jpg": -1}));
        await expectTileB(tester);

        albumState(tester).showMore();
        await tester.pumpAndSettle();
        expect(walkTiles(), ["s.jpg", "a.jpg", "z.jpg"]);
        expect(
            tester
                .widget<Text>(find.byKey(const ValueKey("stack-count-a.jpg")))
                .data,
            "3");

        await open(tester, "a.jpg");
        expect(shownImage(tester), "a.jpg");
        expect(await alternatives(tester), walkBurst);
        await tester.tap(find.byIcon(Icons.arrow_back));
        await tester.pumpAndSettle();

        await open(tester, "s.jpg");
        expect(await walk(tester, 2), ["s.jpg", "a.jpg", "z.jpg"]);
      });
    });

    testWidgets('the label filter: the viewer opens the tile shown',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpWalk(tester,
            album: walkAlbum(labels: {
              for (var name in walkNames)
                if (name != "a.jpg") name: ["kept"],
            }));
        expect(walkTiles(), ["s.jpg", "a.jpg", "z.jpg"]);
        await tester.tap(find.byKey(const Key("label-chip-kept")));
        await tester.pumpAndSettle();
        await expectTileB(tester);
      });
    });
  });

  testWidgets('a burst filtered down to one shot is none', (tester) async {
    await withFakeImageHttp(() async {
      await pumpWalk(tester,
          album: walkAlbum(ratings: {"a.jpg": -1, "b.jpg": -1}));
      expect(walkTiles(), ["s.jpg", "c.jpg", "z.jpg"]);
      expect(badge("c.jpg"), findsNothing);

      await open(tester, "c.jpg");
      expect(shownImage(tester), "c.jpg");
      expect(tester.widget<ImageView>(find.byType(ImageView)).image,
          isA<ImagePart>(),
          reason: "An ordinary photograph, no group.");
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
      await tester.pumpAndSettle();
      expect(find.byType(GroupView), findsNothing);
      await back(tester);

      await open(tester, "s.jpg");
      expect(await walk(tester, 3), ["s.jpg", "c.jpg", "z.jpg", "z.jpg"]);
    });
  });

  testWidgets('choosing the picture of a filtered burst groups its shown shots',
      (tester) async {
    var puts = <http.Request>[];
    await withFakeImageHttp(() async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "tok-9",
        httpClient: MockClient(servingThumbnails((request) async {
          var path = Uri.decodeFull(request.url.path);
          if (request.method == "PUT") {
            puts.add(request);
            return http.Response("", 200);
          }
          if (path == "/valbum/data/Walk/") {
            return http.Response(walkAlbum(ratings: {"a.jpg": -1}), 200,
                headers: {"content-type": "application/json; charset=utf-8"});
          }
          if (path.startsWith("/valbum/data/Walk/") &&
              request.url.query.isEmpty) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          return http.Response(
              '["ListingInfo", {"path": "", "title": "Library", '
              '"folders": []}]',
              200,
              headers: {"content-type": "application/json; charset=utf-8"});
        })),
      );
      var settings = ServerSettings(
        store: InMemorySettingsStore(
            "http://server/valbum/", "tok-9", "Phone", "anna"),
      );
      await settings.load();
      await tester.pumpWidget(VAlbumApp(
        client: client,
        settings: settings,
        initialRoute: const AlternativesRoute(["Walk"], "b.jpg"),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(GroupView), findsOneWidget,
          reason: "The alternatives opened by their address.");
      await tester.tap(find.byKey(const ValueKey("group-choose-c.jpg")));
      await tester.pumpAndSettle();

      expect(puts, hasLength(1));
      var album =
          Resource.read(JsonReader.fromString(puts.single.body)) as AlbumInfo;
      expect([
        for (var part in album.parts)
          part is ImageGroup
              ? "Group(${part.images[part.representative].name}: "
                  "${part.images.map((i) => i.name).join(",")})"
              : (part as ImagePart).name,
      ], [
        "s.jpg",
        "a.jpg",
        "Group(c.jpg: b.jpg,c.jpg)",
        "z.jpg"
      ],
          reason: "What the alternatives showed, the hidden shot stays a "
              "photograph of its own.");
    });
  });

  group('the album model', () {
    ImagePart part(String name, {int rating = 0}) =>
        ImagePart(name: name, rating: rating);

    AlbumInfo album() => AlbumInfo(
          path: "Walk",
          parts: [for (var name in walkNames) part(name)],
          stacks: [
            PhotoStack(members: [
              for (var name in walkBurst) StackMember(name: name),
            ]),
          ],
        );

    test('is linked anew where the stacking or a filter changed its bursts',
        () {
      var self = album();
      AlbumInitializer().init(self);
      expect(syncStacks(self, stacking: true), isFalse,
          reason: "Nothing changed.");
      var a = self.parts[1] as ImagePart;
      var b = self.parts[2] as ImagePart;
      expect(a.group, isNotNull);

      expect(syncStacks(self, stacking: false), isTrue);
      expect(a.group, isNull);
      expect(linkedStacks(self), isEmpty);
      expect(syncStacks(self, stacking: false), isFalse);

      expect(syncStacks(self, stacking: true), isTrue);
      a.rating = -1;
      expect(syncStacks(self), isTrue, reason: "The best shot was hidden.");
      expect(a.group, isNull, reason: "An ordinary, invisible link.");
      var group = b.group!;
      expect([for (var m in group.images) m.name], ["b.jpg", "c.jpg"]);
      expect(group.images[group.representative], same(b));
      expect(a.next, same(group));
      expect(group.next, same(self.parts[4]));

      expect(stackedParts(self), isNot(contains(a)));
      self.minRating = -1;
      expect(stackedParts(self), contains(a));
      expect(syncStacks(self), isTrue);
      expect([for (var m in a.group!.images) m.name], walkBurst);
    });

    test('the grid folds the very groups the viewer walks', () {
      var self = album();
      (self.parts[1] as ImagePart).rating = -1;
      AlbumInitializer().init(self);
      var shown = stackedParts(self);
      var view = albumStackView(shown, self);
      expect([for (var p in view.parts) (p as ImagePart).name],
          ["s.jpg", "b.jpg", "z.jpg"]);
      var group = linkedStacks(self).single;
      expect(view.foldedOf(group.images[group.representative]), isNotNull);
    });
  });
}
