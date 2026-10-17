/// The viewer's actions follow the photo, not the way it was opened (issue
/// #294): the same photo, opened from its album, an unsaved search, a saved
/// search, a collection or an entry of the bell, offers the same crop, poster,
/// collection, faces and properties — decided from the album it lies in and
/// the rights there, as the server decides a write through a collection or a
/// search (#292). A crop from the search is written into that album at once,
/// with no edit session; "Properties" stands in the menu for everybody,
/// read-only without the edit right, and `e`, a long press and a secondary
/// click reach the same.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/photo_actions.dart' show PhotoSource, photoSourceOf;
import 'package:valbum_ui/resource.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'crop_viewer_test.dart' show drawAndApply;
import 'search_view_test.dart' as search;
import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';
import 'util/photo_space.dart';

/// A way to the photo `a.jpg` of `trip`: the view it is opened from and the
/// name of its tile there.
typedef Way = (String label, VAlbumRoute route, String tile);

const List<Way> ways = [
  ("its album", ListingOrAlbumRoute(["trip"]), "a.jpg"),
  ("an unsaved search", SearchRoute([]), "trip/a.jpg"),
  ("a saved search", ListingOrAlbumRoute(["Saved"]), "trip/a.jpg"),
  ("a collection", ListingOrAlbumRoute(["Best"]), "a.jpg"),
  ("an entry of the bell", NewsRoute(["trip"], newsKey), "a.jpg"),
];

/// The entries of the viewer's menu that follow the photo.
const List<String> followed = [
  "viewer-crop",
  "viewer-use-as-poster",
  "viewer-add-to-collection",
  "viewer-edit-persons",
  "viewer-properties",
];

/// Opens the viewer on the photo by [way] in [space].
Future<void> openBy(WidgetTester tester, PhotoSpace space, Way way) async {
  await openSpace(tester, space, way.$2);
  if (way.$2 is SearchRoute) {
    await chooseAnne(tester);
  }
  await tester.tap(tileOf(way.$3));
  await settle(tester);
  expect(find.byType(ImageView), findsOneWidget, reason: "by ${way.$1}");
}

/// Which of [followed] the viewer's ⋮ menu offers.
Future<Set<String>> offered(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key("viewer-menu")));
  await settle(tester);
  var result = {
    for (var key in followed)
      if (find.byKey(Key(key)).evaluate().isNotEmpty) key,
  };
  await tester.sendKeyEvent(LogicalKeyboardKey.escape);
  await settle(tester);
  return result;
}

void main() {
  group("the album a photo lies in", () {
    ImagePart image(String name, {String? ref}) => ImagePart(
        name: name, ref: ref == null ? null : PhotoRef(hash: "", path: ref));
    AlbumInfo of(AlbumKind kind) => AlbumInfo(kind: kind);

    test("is the album shown, for a photo of an album", () {
      expect(
          photoSourceOf(image("a.jpg"),
              album: of(AlbumKind.album), folder: const ["trip"]),
          const PhotoSource(["trip"], "a.jpg"));
    });

    test("is the one a photo found elsewhere names", () {
      for (var kind in [AlbumKind.search, AlbumKind.collection]) {
        expect(
            photoSourceOf(image("2020 Trip/x.jpg", ref: "2020 Trip/x.jpg"),
                album: of(kind), folder: const []),
            const PhotoSource(["2020 Trip"], "x.jpg"),
            reason: "$kind");
      }
    });

    test("lies in the space the view stands in", () {
      expect(
          photoSourceOf(image("x.jpg", ref: "2020/Rome/x.jpg"),
              album: of(AlbumKind.collection),
              folder: const ["~alice", "Best"]),
          const PhotoSource(["~alice", "2020", "Rome"], "x.jpg"));
    });

    test("is unknown where a photo found elsewhere names none", () {
      for (var kind in [AlbumKind.search, AlbumKind.collection]) {
        expect(
            photoSourceOf(image("a.jpg"),
                album: of(kind), folder: const ["Best"]),
            isNull,
            reason: "$kind");
      }
      expect(photoSourceOf(image("a.jpg"), album: of(AlbumKind.album)), isNull,
          reason: "no folder");
    });
  });

  group("the same photo offers the same", () {
    for (var (kind, video) in [("photo", false), ("video", true)]) {
      for (var (role, rights, expected) in [
        (
          "edit",
          const ["view", "download", "contribute", "edit"],
          video
              ? {
                  "viewer-use-as-poster",
                  "viewer-add-to-collection",
                  "viewer-properties"
                }
              : {
                  "viewer-crop",
                  "viewer-add-to-collection",
                  "viewer-edit-persons",
                  "viewer-properties"
                },
        ),
        (
          "view",
          const ["view"],
          {"viewer-add-to-collection", "viewer-properties"},
        ),
      ]) {
        for (var way in ways) {
          testWidgets("$kind to a $role member, opened from ${way.$1}",
              (tester) async {
            if (video) {
              VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
            }
            var space = PhotoSpace(
              role: role,
              rights: rights,
              aKind: video ? "VIDEO" : "IMAGE",
              faces: true,
            );
            await openBy(tester, space, way);
            expect(await offered(tester), expected);
          });
        }
      }
    }
  });

  group("properties", () {
    for (var way in ways) {
      testWidgets("open read-only to a view-only member from ${way.$1}",
          (tester) async {
        await openBy(
            tester, PhotoSpace(role: "view", rights: const ["view"]), way);
        await tester.tap(find.byKey(const Key("viewer-menu")));
        await settle(tester);
        await tester.tap(find.byKey(const Key("viewer-properties")));
        await settle(tester);
        expect(find.byKey(const Key("image-properties-read-only")),
            findsOneWidget);
      });
    }

    testWidgets("open editable to an editor from an unsaved search",
        (tester) async {
      await openBy(tester, PhotoSpace(), ways[1]);
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await settle(tester);
      await tester.tap(find.byKey(const Key("viewer-properties")));
      await settle(tester);
      expect(find.byKey(const Key("image-properties-read-only")), findsNothing);
      expect(find.byType(TextInputDialog), findsOneWidget);
    });

    testWidgets("`e` opens them read-only, never a refusal", (tester) async {
      await openBy(
          tester, PhotoSpace(role: "view", rights: const ["view"]), ways[0]);
      await tester.sendKeyEvent(LogicalKeyboardKey.keyE);
      await settle(tester);
      expect(
          find.byKey(const Key("image-properties-read-only")), findsOneWidget);
      expect(find.byKey(const Key("image-not-editable")), findsNothing);
    });

    testWidgets("a long press opens them read-only, never a refusal",
        (tester) async {
      await openBy(
          tester, PhotoSpace(role: "view", rights: const ["view"]), ways[1]);
      await tester.longPress(find.byKey(const Key("viewer-surface")));
      await settle(tester);
      expect(
          find.byKey(const Key("image-properties-read-only")), findsOneWidget);
      expect(find.byKey(const Key("image-not-editable")), findsNothing);
    });
  });

  group("a secondary click", () {
    Future<void> rightClickViewer(WidgetTester tester) async {
      await tester.tapAt(
        tester.getCenter(find.byKey(const Key("viewer-surface"))),
        buttons: kSecondaryMouseButton,
        kind: PointerDeviceKind.mouse,
      );
      await settle(tester);
    }

    testWidgets("on a photo opens the viewer's whole menu", (tester) async {
      await openBy(tester, PhotoSpace(faces: true), ways[0]);
      await rightClickViewer(tester);
      for (var key in [
        "viewer-download",
        "viewer-crop",
        "viewer-add-to-collection",
        "viewer-edit-persons",
        "viewer-properties",
      ]) {
        expect(find.byKey(Key(key)), findsOneWidget, reason: key);
      }
      await tester.tap(find.byKey(const Key("viewer-properties")));
      await settle(tester);
      expect(find.byType(TextInputDialog), findsOneWidget);
    });

    testWidgets("on a video opens the viewer's whole menu", (tester) async {
      VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
      await openBy(tester, PhotoSpace(aKind: "VIDEO"), ways[0]);
      await rightClickViewer(tester);
      for (var key in [
        "viewer-download",
        "viewer-use-as-poster",
        "viewer-add-to-collection",
        "viewer-properties",
      ]) {
        expect(find.byKey(Key(key)), findsOneWidget, reason: key);
      }
    });
  });

  testWidgets(
      "a crop from an unsaved search is written into the album the photo "
      "lies in, at once", (tester) async {
    var space = search.Space()
      ..searchAnswer = search.found.replaceFirst(
          '"rights":[{"name":"view"},{"name":"download"}]',
          '"rights":[{"name":"view"},{"name":"download"},{"name":"edit"}]');
    await search.pumpRoute(tester, space, const SearchRoute([]));
    await search.chooseAnne(tester);
    await tester.tap(find.byKey(const ValueKey("2020 Trip/x.jpg")));
    await search.settle(tester);
    expect(find.byType(ImageView), findsOneWidget);

    await tester.tap(find.byKey(const Key("viewer-menu")));
    await search.settle(tester);
    await tester.tap(find.byKey(const Key("viewer-crop")));
    await search.settle(tester);
    expect(find.byKey(const Key("crop-editor")), findsOneWidget);
    await withFakeImageHttp(() async {
      await drawAndApply(
          tester, const Offset(0.5, 0.1), const Offset(0.9, 0.6));
    });
    await search.settle(tester);

    var post = space.posts("crop").single;
    // The album's own folder, percent-encoded, under the file's own name —
    // not the folder searched, nor the name the search gave it.
    expect(post.url.path, "/valbum/data/2020%20Trip/");
    var sent =
        AbstractImage.read(JsonReader.fromString(post.body)) as ImagePart;
    expect(sent.name, "x.jpg");
    expect(sent.crop, isNotNull);
  });

  testWidgets(
      "a description written from an unsaved search goes into the album the "
      "photo lies in", (tester) async {
    var space = PhotoSpace();
    await openBy(tester, space, ways[1]);

    await tester.sendKeyEvent(LogicalKeyboardKey.keyE);
    await settle(tester);
    await tester.enterText(find.byType(TextField), "Am Meer");
    await tester.tap(find.text(testL10n.apply));
    await settle(tester);

    var put = space.requests.singleWhere((r) => r.method == "PUT");
    expect(Uri.decodeFull(put.url.path), "/valbum/data/trip/");
    var album = Resource.fromString(put.body) as AlbumInfo;
    var images = {
      for (var part in album.parts)
        if (part is ImagePart) part.name: part.comment,
    };
    expect(images, {"a.jpg": "Am Meer", "b.jpg": "", "c.jpg": ""},
        reason: "The album as it is answered, the one photo described.");
  });
}
