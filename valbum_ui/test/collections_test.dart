/// Tests of references (issues #221, #307): an album holds references to
/// photographs of other albums beside its own files, and a collection is an
/// album without files of its own — shown as one, a placeholder for a
/// photograph that is gone, given photographs by "Add to album (as
/// reference)…" from an album's edit mode, a tile's menu and the viewer,
/// emptied by "Remove from album", its description its own, and every other
/// edit written through to the albums the photographs lie in.
library;

import 'package:flutter/gestures.dart'
    show PointerDeviceKind, kSecondaryMouseButton;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_date.dart' show folderHasDate;
import 'package:valbum_ui/collections.dart' show referenceMarkKey;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'download_test.dart' show installSaver, postedNames, pumpSelection;
import 'move_test.dart' show openAlbumMenu, tile;

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';
import 'util/viewer_harness.dart'
    show pumpViewerHarness, viewerAlbum, viewerImagePart, withEmptyImageCache;

const String dataUrl = "http://server/valbum/data";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// The space the tests browse: an album `Inbox`, an album `2020 Trip`, a
/// folder `2021`, the collection `Best` and the album `Mixed`, which holds
/// the files `own.jpg` and `z.jpg` and a reference `a.jpg` to `Inbox/a.jpg`.
class Space {
  final List<http.Request> requests = [];

  /// The answer to a `PUT` creating a folder: where it landed.
  String createdPath = "Best";

  http.Response answer(http.Request request) {
    var path = pathOf(request);
    if (request.method == "PUT") {
      return json('{"path":"$createdPath","message":""}');
    }
    if (request.method == "POST") {
      var action = request.url.queryParameters["action"];
      if (action == "collect") {
        return json('{"outcomes":[{"name":"a.jpg","newName":"a.jpg",'
            '"message":""}]}');
      }
      if (action == "delete") {
        return json('{"outcomes":[{"name":"a.jpg","newName":"",'
            '"message":"Removed from the album; the photo stays in its own '
            'album."}]}');
      }
    }
    switch (path) {
      case "/valbum/data/":
        return json(fixture("listing-collections.json"));
      case "/valbum/data/2021/":
        return json(fixture("listing-move-2021.json"));
      case "/valbum/data/2020 Trip/":
        return json(fixture("album-target.json"));
      case "/valbum/data/Inbox/":
        return json(fixture("album-move.json"));
      case "/valbum/data/Best/":
        return json(fixture("collection-best.json"));
      case "/valbum/data/Mixed/":
      case "/valbum/data/New/":
        return json(fixture("album-references.json"));
    }
    return http.Response("No such resource: $path", 404);
  }

  VAlbumClient get client => VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          return answer(request);
        }),
      );

  List<http.Request> posts(String action) => [
        for (var request in requests)
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == action)
            request,
      ];

  List<http.Request> get puts => [
        for (var request in requests)
          if (request.method == "PUT") request
      ];

  /// How often [path] was fetched as JSON after the request at [index].
  int fetchesAfter(int index, String path) => requests
      .sublist(index + 1)
      .where((r) =>
          r.method == "GET" &&
          r.url.queryParameters["type"] == "json" &&
          pathOf(r) == path)
      .length;
}

Future<void> pumpRoute(
    WidgetTester tester, VAlbumClient client, List<String> path) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      initialRoute: ListingOrAlbumRoute(path),
    ));
    await tester.pumpAndSettle();
  });
}

/// Hands the app the given location, as the browser does on back/forward.
Future<void> goTo(WidgetTester tester, String location) async {
  var delegate = tester
      .widget<MaterialApp>(find.byType(MaterialApp))
      .routerDelegate! as VAlbumRouterDelegate;
  await withFakeImageHttp(() async {
    await delegate.setNewRoutePath(parseRoute(Uri.parse(location)));
    delegate.notifyListeners();
    await tester.pumpAndSettle();
  });
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

/// Enters the edit mode by a long press on the first photograph.
Future<void> enterEditMode(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.longPress(find.byType(Image).first);
    await tester.pumpAndSettle();
  });
}

Future<void> tapKey(WidgetTester tester, String key) async {
  await withFakeImageHttp(() async {
    // A long menu scrolls: the entry is brought into view first.
    await tester.ensureVisible(find.byKey(Key(key)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();
  });
}

/// Clicks the tile [name] with the secondary mouse button: its menu.
Future<void> rightClick(WidgetTester tester, String name) async {
  await withFakeImageHttp(() async {
    await tester.tap(tile(name),
        buttons: kSecondaryMouseButton, kind: PointerDeviceKind.mouse);
    await tester.pumpAndSettle();
  });
}

/// An album `Inbox` holding a file `a.jpg` and a reference `r.jpg`, for the
/// download of a selection.
String albumWithReference(List<String> rights) =>
    '["AlbumInfo", {"path": "Inbox", "title": "Inbox", "subTitle": "", '
    '"rights": [${rights.map((r) => '{"name": "$r"}').join(", ")}], '
    '"parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}], '
    '["ImagePart", {"kind": "IMAGE", "name": "r.jpg", '
    '"date": 1015113610000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0, '
    '"ref": {"hash": "rrrr", "path": "Trip/r.jpg"}}]'
    ']}]';

void main() {
  group("no collection of its own", () {
    testWidgets("the listing creates no collection and shows one as an album",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const []);

      // A collection tile without a picture looks like an album's.
      expect(find.byKey(const Key("collection-icon")), findsNothing);
      await openMenu(tester);
      expect(find.byKey(const Key("create-collection")), findsNothing);
      expect(find.text("New collection"), findsNothing);
    });

    test("an album of references has a date only where it has one", () {
      expect(
          folderHasDate(FolderInfo(
              name: "Best", kind: FolderKind.collection, effectiveDate: 0)),
          isFalse);
      expect(
          folderHasDate(FolderInfo(
              name: "Best",
              kind: FolderKind.collection,
              effectiveDate: 1015113600000)),
          isTrue);
    });
  });

  group("a former collection", () {
    testWidgets("opens as an album, with a placeholder for a gone photo",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      // The pictures the tiles draw, by the address of their image.
      var asked = [
        for (var image in tester.widgetList<Image>(find.byType(Image)))
          switch (image.image) {
            ThumbnailImage(:var imageUrl) => imageUrl,
            ResizeImage(imageProvider: ThumbnailImage(:var imageUrl)) =>
              imageUrl,
            _ => "${image.image}",
          },
      ];

      expect(find.text("Highlights"), findsOneWidget);
      expect(find.byKey(const Key("collection-missing")), findsOneWidget);
      expect(
          find.text("This photo is no longer in the library"), findsOneWidget);
      // Every address stays the album's; the gone one is never asked.
      expect(asked.where((url) => url.contains("/valbum/data/Best/a.jpg")),
          isNotEmpty);
      expect(asked.where((url) => url.contains("/valbum/data/Best/x.jpg")),
          isNotEmpty);
      expect(asked.where((url) => url.contains("gone.jpg")), isEmpty);
      // Every reference is marked as one.
      expect(find.byKey(referenceMarkKey), findsNWidgets(2));
      // It is an album: files are uploaded into it.
      expect(find.byIcon(Icons.cloud_upload), findsOneWidget);

      // The placeholder opens no viewer.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("collection-missing")));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("collection-missing")), findsOneWidget);
    });

    testWidgets("offers the moves of an album and the removal of a reference",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      await openMenu(tester);

      expect(find.byKey(const Key("show-trash")), findsNothing);
      expect(find.byKey(const Key("remove-from-collection")), findsOneWidget);
      expect(find.byKey(const Key("add-to-collection")), findsOneWidget);
      expect(find.byKey(const Key("move-to")), findsOneWidget);
    });

    testWidgets("removes the selected references without touching the photos",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "remove-from-collection");

      expect(find.text(testL10n.removeReferencesExplanation), findsOneWidget);
      await tapKey(tester, "remove-from-collection-confirm");

      var post = space.posts("delete").single;
      expect(pathOf(post), "/valbum/data/Best/");
      expect(post.body, '{"target":"","names":[{"name":"a.jpg"}]}');
      // The server's own words, and the album fetched anew.
      expect(
          find.text("Removed from the album; the photo stays in its own "
              "album."),
          findsOneWidget);
      expect(
          space.fetchesAfter(
              space.requests.indexOf(post), "/valbum/data/Best/"),
          greaterThan(0));
    });

    testWidgets("a gone photo is selected and removed", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      // The first tile is deselected, the placeholder selected alone.
      await withFakeImageHttp(() async {
        var box = tester.getRect(find.byKey(const Key("collection-missing")));
        await tester.tapAt(Offset(box.left + 8, box.center.dy));
        await tester.pumpAndSettle();
      });
      await openMenu(tester);
      // A gone photo is offered to no other album.
      expect(find.byKey(const Key("add-to-collection")), findsNothing);
      await tapKey(tester, "remove-from-collection");
      await tapKey(tester, "remove-from-collection-confirm");

      expect(space.posts("delete").single.body,
          '{"target":"","names":[{"name":"gone.jpg"}]}');
    });

    testWidgets(
        "an edit is written to the album and the source album is fetched "
        "anew", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await goTo(tester, "/Best/");
      await enterEditMode(tester);
      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.rotate_right).first);
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = space.puts.single;
      expect(pathOf(put), "/valbum/data/Best/");
      var sent = (Resource.read(JsonReader.fromString(put.body)) as AlbumInfo);
      var turned = sent.parts.whereType<ImagePart>().first;
      expect(turned.name, "a.jpg");
      expect(turned.orientation, isNot(Orientation.identity));

      // The album the photograph lies in shows the turn when it is opened
      // again: the router forgot what it held of it.
      var index = space.requests.indexOf(put);
      await goTo(tester, "/Inbox/");
      expect(space.fetchesAfter(index, "/valbum/data/Inbox/"), 1);
    });

    testWidgets("the properties name the album a photo lies in",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      await withFakeImageHttp(() async {
        await tester.tap(find.byTooltip("Image properties").first);
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("property-source")), findsOneWidget);
      expect(find.text("In album: Inbox"), findsOneWidget);
    });
  });

  group("an album holding references", () {
    testWidgets("shows them among its own photos, marked", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Mixed"]);

      expect(tile("own.jpg"), findsOneWidget);
      expect(tile("a.jpg"), findsOneWidget);
      expect(tile("z.jpg"), findsOneWidget);
      expect(
          find.descendant(
              of: tile("a.jpg"), matching: find.byKey(referenceMarkKey)),
          findsOneWidget);
      expect(find.byKey(referenceMarkKey), findsOneWidget,
          reason: "a file of the album carries no mark");
      // In the order the album answers: by date, the reference among them.
      expect(tester.getTopLeft(tile("own.jpg")).dx,
          lessThan(tester.getTopLeft(tile("a.jpg")).dx));
      expect(tester.getTopLeft(tile("a.jpg")).dx,
          lessThan(tester.getTopLeft(tile("z.jpg")).dx));
    });

    testWidgets("its description of a reference is written with the album",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Mixed"]);
      await enterEditMode(tester);
      await withFakeImageHttp(() async {
        // A tap beside the tile's toolbars selects it alone.
        var box = tester.getRect(tile("a.jpg"));
        await tester.tapAt(Offset(box.left + 8, box.center.dy));
        await tester.pumpAndSettle();
        await tester.tap(find.descendant(
            of: tile("a.jpg"), matching: find.byIcon(Icons.notes)));
        await tester.pumpAndSettle();
      });
      // The description shown is the original's, the album having none.
      expect(find.text("Beach"), findsWidgets);
      await tester.enterText(find.byType(TextField), "Where we swam");
      await withFakeImageHttp(() async {
        await tester.tap(find.text(testL10n.apply));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = space.puts.single;
      expect(pathOf(put), "/valbum/data/Mixed/",
          reason: "the album's own description, never the original's album");
      var sent = (Resource.read(JsonReader.fromString(put.body)) as AlbumInfo);
      var reference = sent.parts
          .whereType<ImagePart>()
          .singleWhere((p) => p.name == "a.jpg");
      expect(reference.comment, "Where we swam");
      expect(reference.ref?.hash, "aaaa");
    });

    testWidgets("a reference is removed, never put into the trash",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Mixed"]);
      await enterEditMode(tester);
      await rightClick(tester, "a.jpg");
      expect(find.byKey(const Key("tile-context-delete")), findsNothing);
      expect(find.byKey(const Key("tile-context-remove-from-collection")),
          findsOneWidget);
      await tapKey(tester, "tile-context-remove-from-collection");
      await tapKey(tester, "remove-from-collection-confirm");

      expect(space.posts("delete").single.body,
          '{"target":"","names":[{"name":"a.jpg"}]}');
      expect(space.puts, isEmpty, reason: "no rating is written");

      // A file of the album is put into the trash as before.
      await rightClick(tester, "own.jpg");
      expect(find.byKey(const Key("tile-context-delete")), findsOneWidget);
      expect(find.byKey(const Key("tile-context-remove-from-collection")),
          findsNothing);
    });

    testWidgets("a selection is downloaded with its references",
        (tester) async {
      var saver = installSaver();
      var requests = <http.Request>[];
      await withFakeImageHttp(() async {
        await pumpSelection(tester, requests,
            rights: const ["view", "download"],
            album: albumWithReference,
            select: false);
        await openAlbumMenu(tester);
        await tester.tap(find.byKey(const Key("select-mode")));
        await tester.pumpAndSettle();
        await tester.tap(tile("a.jpg"));
        await tester.pumpAndSettle();
      });
      // Both selected: the mark of each.
      await withFakeImageHttp(() async {
        await tester.tap(find.descendant(
            of: tile("r.jpg"), matching: find.byKey(const Key("select-mark"))));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("select-mode-download")));
        await tester.pumpAndSettle();
      });

      var post =
          requests.singleWhere((r) => r.url.queryParameters["action"] == "zip");
      expect(postedNames(post), ["a.jpg", "r.jpg"],
          reason: "a reference is downloaded as if it lay in the album");
      expect(saver.saved.single.name, "Inbox.zip");
    });
  });

  group("adding to an album as reference", () {
    testWidgets("from an album's edit mode into an ordinary album",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      expect(find.text(testL10n.addToAlbumAsReference), findsOneWidget);
      await tapKey(tester, "add-to-collection");

      // The picker opens above the album; a folder cannot be confirmed.
      expect(find.byKey(const Key("collection-picker")), findsOneWidget);
      // With the search field of every folder picker (issue #261).
      expect(find.byKey(const Key("picker-search")), findsOneWidget);
      expect(find.byKey(const Key("picker-needs-collection")), findsNothing);
      expect(find.byKey(const Key("picker-create-collection")), findsNothing);
      expect(
          tester
              .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
              .onPressed,
          isNull);
      // Every album is a target, an ordinary one as a former collection.
      expect(find.byKey(const Key("picker-refused-2020 Trip")), findsNothing);
      expect(find.byKey(const Key("picker-refused-Best")), findsNothing);
      await tapKey(tester, "picker-folder-2020 Trip");
      await tapKey(tester, "picker-confirm");

      var post = space.posts("collect").single;
      expect(pathOf(post), "/valbum/data/Inbox/");
      expect(post.body, '{"target":"2020 Trip","names":[{"name":"a.jpg"}]}');
      expect(find.text("Added 1 photo to '2020 Trip'."), findsOneWidget);
      // Nothing was moved out of the album.
      expect(space.posts("move"), isEmpty);
    });

    testWidgets("from a tile's menu", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await rightClick(tester, "b.jpg");
      await tapKey(tester, "tile-context-collect");
      await tapKey(tester, "picker-folder-Mixed");
      await tapKey(tester, "picker-confirm");

      expect(space.posts("collect").single.body,
          '{"target":"Mixed","names":[{"name":"b.jpg"}]}');
    });

    testWidgets("into an album created in the picker", (tester) async {
      var space = Space()..createdPath = "New";
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "add-to-collection");
      await tapKey(tester, "picker-create-album");
      await tester.enterText(
          find.widgetWithText(TextFormField, testL10n.titleLabel), "New");
      await withFakeImageHttp(() async {
        await tester.tap(find.text(testL10n.create));
        await tester.pumpAndSettle();
      });

      var put = space.puts.single;
      expect(pathOf(put).startsWith("/valbum/data/"), isTrue);
      expect((Resource.read(JsonReader.fromString(put.body)) as AlbumInfo).kind,
          AlbumKind.album);
      var post = space.posts("collect").single;
      expect(space.requests.indexOf(post),
          greaterThan(space.requests.indexOf(put)));
      // The album was created for what is added, see issue #277.
      expect(post.body,
          '{"target":"New","names":[{"name":"a.jpg"}],"created":{"cover":""}}');
    });

    testWidgets("a move goes into a former collection", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "move-to");
      await tapKey(tester, "picker-folder-Best");
      expect(find.byKey(const Key("picker-refused-Best")), findsNothing);
      expect(find.byKey(const Key("picker-selected")), findsOneWidget);
    });

    testWidgets("from the viewer's menu", (tester) async {
      var space = Space();
      withEmptyImageCache();
      var image = viewerImagePart("a.jpg");
      viewerAlbum([image],
          rights: const ["view", "download", "contribute", "edit"]);
      fakeImageRequests();
      await pumpViewerHarness(
        tester,
        image,
        client: space.client,
        caller:
            const CallerInfo(userName: "anna", role: roleMember, space: "anna"),
        editPath: const ["album"],
      );
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.text(testL10n.addToAlbumAsReference), findsOneWidget);
      await tester.tap(find.byKey(const Key("viewer-add-to-collection")));
      await tester.pumpAndSettle();
      await tapKey(tester, "picker-folder-Best");
      await tapKey(tester, "picker-confirm");

      var post = space.posts("collect").single;
      expect(pathOf(post), "/valbum/data/album/");
      expect(post.body, '{"target":"Best","names":[{"name":"a.jpg"}]}');
    });

    testWidgets(
        "the viewer names the faces of a reference in the album it lies in "
        "(issue #294)", (tester) async {
      var space = Space();
      withEmptyImageCache();
      var image = viewerImagePart("a.jpg")
        ..ref = PhotoRef(hash: "aaaa", path: "trip/a.jpg");
      viewerAlbum([image],
          rights: const ["view", "download", "contribute", "edit"]);
      fakeImageRequests();
      await pumpViewerHarness(
        tester,
        image,
        client: space.client,
        caller: const CallerInfo(
            userName: "anna", role: roleMember, space: "anna", faces: true),
        editPath: const ["Best"],
      );
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-edit-persons")), findsOneWidget);
      expect(find.byKey(const Key("viewer-add-to-collection")), findsOneWidget);
    });

    testWidgets(
        "a photo of a collection that names no album is written nowhere "
        "(issue #294)", (tester) async {
      var space = Space();
      withEmptyImageCache();
      var image = viewerImagePart("a.jpg");
      var album = viewerAlbum([image],
          rights: const ["view", "download", "contribute", "edit"]);
      album.kind = AlbumKind.collection;
      fakeImageRequests();
      await pumpViewerHarness(
        tester,
        image,
        client: space.client,
        caller: const CallerInfo(
            userName: "anna", role: roleMember, space: "anna", faces: true),
        editPath: const ["Best"],
      );
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("viewer-edit-persons")), findsNothing);
      expect(find.byKey(const Key("viewer-add-to-collection")), findsNothing);
      expect(find.byKey(const Key("viewer-crop")), findsNothing);
      expect(find.byKey(const Key("viewer-properties")), findsOneWidget);
    });
  });

  testWidgets("the German words of a reference", (tester) async {
    var l10n = l10nOf(const Locale("de"));
    expect(l10n.removeReference, "Aus dem Album entfernen");
    expect(l10n.addedToCollection(2, "'Best'"),
        "2 Fotos wurden zu 'Best' hinzugefügt.");
  });
}
