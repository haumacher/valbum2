/// A burst is a group the author never made (issue #314): a tap on its tile
/// opens the viewer on its best shot, previous and next walk the album's
/// tiles past its other shots, "down" shows them all, choosing the picture
/// there makes it a stored group, a long press enters the edit mode with the
/// bursts left folded, and there a tile selects and moves all of its shots.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'album_multi_reorder_test.dart' show dragOnto;
import 'album_stacks_test.dart'
    show albumState, badge, image, routeOf, selectedNames;
import 'move_test.dart' show tile, longPressTile, tapTile;
import 'util/fake_image_http.dart' show transparentPixelPng;
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart';

const String dataUrl = "http://server/valbum/data";

/// Every photograph of the album `Twin`, in its stored order.
const List<String> twinNames = [
  "s.jpg",
  "a1.jpg",
  "a2.jpg",
  "a3.jpg",
  "m.jpg",
  "b1.jpg",
  "b2.jpg",
  "b3.jpg",
  "z.jpg",
];

/// The first burst, its best shot first: the tile `a1.jpg`, between `s.jpg`
/// and `m.jpg`.
const List<String> burstA = ["a1.jpg", "a2.jpg", "a3.jpg"];

/// The second burst, its best shot first: the tile `b2.jpg`.
const List<String> burstB = ["b2.jpg", "b1.jpg", "b3.jpg"];

/// The tiles of the album with both bursts stacked.
const List<String> folded = ["s.jpg", "a1.jpg", "m.jpg", "b2.jpg", "z.jpg"];

String stackJson(List<String> names) =>
    '{"members": [${names.map((n) => '{"name": "$n"}').join(", ")}]}';

/// The album `Twin`: two bursts of three shots and three photos beside them.
String twinAlbum() => '["AlbumInfo", {"path": "Twin", "title": "Twin", '
    '"subTitle": "", "rights": [{"name": "view"}, {"name": "download"}, '
    '{"name": "contribute"}, {"name": "edit"}], '
    '"stacks": [${stackJson(burstA)}, ${stackJson(burstB)}], '
    '"parts": [${[
      for (var (index, name) in twinNames.indexed) image(name, index)
    ].join(", ")}]}]';

/// Opens the album `Twin`, recording every request in [requests]; a write
/// is refused with a reason of the server's where [refuseWrites] says so.
Future<void> pumpTwin(
  WidgetTester tester, {
  List<http.Request>? requests,
  bool refuseWrites = false,
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "tok-9",
    httpClient: MockClient(servingThumbnails((request) async {
      requests?.add(request);
      var path = Uri.decodeFull(request.url.path);
      if (request.method == "PUT") {
        return refuseWrites
            ? http.Response("Not yours to change", 403)
            : http.Response("", 200);
      }
      if (path == "/valbum/data/Twin/") {
        return http.Response(twinAlbum(), 200,
            headers: {"content-type": "application/json; charset=utf-8"});
      }
      if (path.startsWith("/valbum/data/Twin/") && request.url.query.isEmpty) {
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
    store: InMemorySettingsStore(
        "http://server/valbum/", "tok-9", "Phone", "anna"),
  );
  await settings.load();
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: settings,
    initialRoute: const ListingOrAlbumRoute(["Twin"]),
  ));
  await tester.pumpAndSettle();
}

/// The names whose tiles the album shows.
List<String> twinTiles() => [
      for (var name in twinNames)
        if (tile(name).evaluate().isNotEmpty) name,
    ];

/// The names of the parts of [album] in their stored order, a group as
/// `Group(representative: members)`.
List<String> storedNames(AlbumInfo album) => [
      for (var part in album.parts)
        if (part is ImageGroup)
          "Group(${part.images[part.representative].name}: "
              "${part.images.map((i) => i.name).join(",")})"
        else if (part is ImagePart)
          part.name,
    ];

/// Opens the viewer on the tile of the first burst and goes down into its
/// alternatives.
Future<void> openBurstA(WidgetTester tester) async {
  await tester.tap(tile("a1.jpg"));
  await tester.pumpAndSettle();
  await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
  await tester.pumpAndSettle();
}

void main() {
  setUp(withEmptyImageCache);

  group('in the view mode', () {
    testWidgets('a tap opens the best shot, next and previous pass the burst',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpTwin(tester);
        expect(twinTiles(), folded);

        await tester.tap(tile("a1.jpg"));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Twin"], "a1.jpg"));

        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Twin"], "m.jpg"),
            reason: "Next is the photo after the burst, not into it.");
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Twin"], "a1.jpg"));
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ImageRoute(["Twin"], "s.jpg"));

        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
        expect(twinTiles(), folded, reason: "Nothing was unfolded.");
      });
    });

    testWidgets('down shows the shots of the burst, up leads back',
        (tester) async {
      await withFakeImageHttp(() async {
        await pumpTwin(tester);
        await openBurstA(tester);
        expect(routeOf(tester), const AlternativesRoute(["Twin"], "a1.jpg"));
        expect(find.byType(GroupView), findsOneWidget);
        for (var name in burstA) {
          expect(find.byKey(ValueKey("group-tile-$name")), findsOneWidget);
        }
        expect(find.byKey(const ValueKey("group-tile-m.jpg")), findsNothing);

        await tester.tap(find.byKey(const ValueKey("group-tile-a1.jpg")));
        await tester.pumpAndSettle();
        expect(
            routeOf(tester), const MemberRoute(["Twin"], "a1.jpg", "a1.jpg"));
        var seen = <String>["a1.jpg"];
        for (var step = 0; step < 3; step++) {
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
          await tester.pumpAndSettle();
          seen.add((routeOf(tester) as MemberRoute).member);
        }
        expect(seen, [...burstA, "a3.jpg"],
            reason: "The viewer pages through the three shots, and stops.");

        // Up leaves the burst for the album in one step, as for a group
        // (issue #79).
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ListingOrAlbumRoute(["Twin"]));
        expect(twinTiles(), folded);
      });
    });

    testWidgets('choosing the picture of a burst stores it as a group',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpTwin(tester, requests: requests);
        await openBurstA(tester);
        expect(find.byKey(const Key("group-representative")), findsOneWidget);

        await tester.tap(find.byKey(const ValueKey("group-choose-a2.jpg")));
        await tester.pumpAndSettle();

        var put = requests.where((r) => r.method == "PUT").single;
        expect(Uri.decodeFull(put.url.path), "/valbum/data/Twin/");
        var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
        var stored = [
          "s.jpg",
          "Group(a2.jpg: a1.jpg,a2.jpg,a3.jpg)",
          "m.jpg",
          "b1.jpg",
          "b2.jpg",
          "b3.jpg",
          "z.jpg",
        ];
        expect(storedNames(saved), stored);
        var reread =
            Resource.read(JsonReader.fromString(saved.toString())) as AlbumInfo;
        expect(reread.toString(), saved.toString());

        // The alternatives now mark the chosen one.
        expect(
            find.descendant(
                of: find.ancestor(
                    of: find.byKey(const ValueKey("group-tile-a2.jpg")),
                    matching: find.byType(Stack)),
                matching: find.byKey(const Key("group-representative"))),
            findsOneWidget);

        await tester.tap(find.byIcon(Icons.arrow_back));
        await tester.pumpAndSettle();
        expect(routeOf(tester), const ListingOrAlbumRoute(["Twin"]));
        expect(tile("a2.jpg"), findsOneWidget,
            reason: "The group's tile shows the chosen shot.");
        expect(tile("a1.jpg"), findsNothing);
        expect(badge("a1.jpg"), findsNothing);
        expect(badge("a2.jpg"), findsNothing, reason: "A group, no burst.");
        expect(badge("b2.jpg"), findsOneWidget);
        expect(storedNames(albumState(tester).widget.album), stored);
      });
    });

    testWidgets('a refused choice leaves the burst as it was', (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpTwin(tester, requests: requests, refuseWrites: true);
        await openBurstA(tester);
        await tester.tap(find.byKey(const ValueKey("group-choose-a2.jpg")));
        await tester.pumpAndSettle();
        expect(find.textContaining("HTTP 403"), findsOneWidget,
            reason: "The server's reason is said.");
        expect(requests.where((r) => r.method == "PUT"), hasLength(1));

        await tester.tap(find.byIcon(Icons.arrow_back));
        await tester.pumpAndSettle();
        expect(storedNames(albumState(tester).widget.album), twinNames);
        expect(twinTiles(), folded);
        expect(badge("a1.jpg"), findsOneWidget);
      });
    });
  });

  group('the edit mode', () {
    testWidgets(
        'a long press on a burst keeps every burst folded and selects its '
        'shots', (tester) async {
      await withFakeImageHttp(() async {
        await pumpTwin(tester);
        await longPressTile(tester, "a1.jpg");
        expect(albumState(tester).editMode, isTrue);
        expect(twinTiles(), folded,
            reason: "Neither this burst nor the other one unfolded.");
        expect(badge("a1.jpg"), findsOneWidget);
        expect(badge("b2.jpg"), findsOneWidget);
        expect(selectedNames(tester), burstA.toSet());
      });
    });

    testWidgets('a tap toggles all shots of a burst', (tester) async {
      await withFakeImageHttp(() async {
        await pumpTwin(tester);
        await longPressTile(tester, "m.jpg");
        expect(selectedNames(tester), {"m.jpg"});

        await tapTile(tester, "a1.jpg");
        expect(selectedNames(tester), {"m.jpg", ...burstA});
        await tapTile(tester, "a1.jpg");
        expect(selectedNames(tester), {"m.jpg"});
      });
    });

    testWidgets('a burst is dragged as one block of its shots, and saved',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpTwin(tester, requests: requests);
        await longPressTile(tester, "m.jpg");

        // The burst behind the last photo.
        await dragOnto(tester, tile("a1.jpg"), tile("z.jpg"), left: false);
        var order = [
          "s.jpg",
          "m.jpg",
          "b1.jpg",
          "b2.jpg",
          "b3.jpg",
          "z.jpg",
          "a1.jpg",
          "a2.jpg",
          "a3.jpg",
        ];
        expect(storedNames(albumState(tester).widget.album), order);
        expect(selectedNames(tester), burstA.toSet());

        // Behind the tile of the other burst is behind all of its shots.
        await dragOnto(tester, tile("m.jpg"), tile("b2.jpg"), left: false);
        order = [
          "s.jpg",
          "b1.jpg",
          "b2.jpg",
          "b3.jpg",
          "m.jpg",
          "z.jpg",
          "a1.jpg",
          "a2.jpg",
          "a3.jpg",
        ];
        expect(storedNames(albumState(tester).widget.album), order);
        expect(twinTiles(), folded, reason: "Both bursts still folded.");

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();

        var put = requests.where((r) => r.method == "PUT").single;
        var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
        expect(storedNames(saved), order);
        var reread =
            Resource.read(JsonReader.fromString(saved.toString())) as AlbumInfo;
        expect(storedNames(reread), storedNames(saved));
      });
    });

    testWidgets('the picture of a burst is chosen into the edit buffer',
        (tester) async {
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpTwin(tester, requests: requests);
        await longPressTile(tester, "a1.jpg");
        await tester.tap(find.byTooltip(testL10n.chooseGroupPicture));
        await tester.pumpAndSettle();
        expect(find.byType(GroupView), findsOneWidget);
        await tester.tap(find.byKey(const ValueKey("group-choose-a3.jpg")));
        await tester.pumpAndSettle();
        expect(requests.where((r) => r.method == "PUT"), isEmpty,
            reason: "Inside an edit session, Save writes it.");

        await tester.tap(find.byIcon(Icons.arrow_back));
        await tester.pumpAndSettle();
        expect(albumState(tester).editMode, isTrue);
        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
        var put = requests.where((r) => r.method == "PUT").single;
        var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
        expect(storedNames(saved)[1], "Group(a3.jpg: a1.jpg,a2.jpg,a3.jpg)");
      });
    });
  });
}
