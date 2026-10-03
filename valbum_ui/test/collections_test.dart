/// Tests of collections (issue #221): albums of references to photographs of
/// other albums — created from the listing and in the picker, shown with a
/// placeholder for a photograph that is gone, given photographs by "Add to
/// collection…" from an album's edit mode and from the viewer, emptied by
/// "Remove from collection", and written through to the albums the
/// photographs lie in.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/album_date.dart' show folderHasDate;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

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
/// folder `2021` and the collection `Best`.
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
            '"message":"Removed from the collection; the photo stays in '
            'its album."}]}');
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
      case "/valbum/data/New/":
        return json(fixture("collection-best.json"));
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

void main() {
  group("creating a collection", () {
    testWidgets("the listing creates one of kind COLLECTION", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const []);

      // A collection tile without a picture says what it is.
      expect(find.byKey(const Key("collection-icon")), findsOneWidget);

      await openMenu(tester);
      await tapKey(tester, "create-collection");
      expect(find.byKey(const Key("create-collection-dialog")), findsOneWidget);
      await tester.enterText(
          find.byKey(const Key("collection-title")), "Best of 2026");
      await tapKey(tester, "create-collection-confirm");

      var put = space.puts.single;
      expect(pathOf(put), "/valbum/data/Best of 2026/");
      var sent = (Resource.read(JsonReader.fromString(put.body)) as AlbumInfo);
      expect(sent.kind, AlbumKind.collection);
      expect(sent.title, "Best of 2026");
      expect(sent.date, 0);
      // The app follows where the server says it landed.
      expect(find.text("Highlights"), findsOneWidget);
    });

    test("a collection has a date only where its author gave it one", () {
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

  group("the collection page", () {
    testWidgets("shows its photographs and a placeholder for a gone one",
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
      // Every address stays the collection's; the gone one is never asked.
      expect(asked.where((url) => url.contains("/valbum/data/Best/a.jpg")),
          isNotEmpty);
      expect(asked.where((url) => url.contains("/valbum/data/Best/x.jpg")),
          isNotEmpty);
      expect(asked.where((url) => url.contains("gone.jpg")), isEmpty);
      // Nothing is uploaded into a collection.
      expect(find.byIcon(Icons.cloud_upload), findsNothing);

      // The placeholder opens no viewer.
      await withFakeImageHttp(() async {
        await tester.tap(find.byKey(const Key("collection-missing")));
        await tester.pumpAndSettle();
      });
      expect(find.byKey(const Key("collection-missing")), findsOneWidget);
    });

    testWidgets("offers no trash, persons, re-read or move of images",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      await openMenu(tester);

      expect(find.byKey(const Key("show-trash")), findsNothing);
      expect(find.byKey(const Key("persons")), findsNothing);
      expect(find.byKey(const Key("reanalyze")), findsNothing);
      expect(find.text("Move 1 image to…"), findsNothing);
      expect(find.byKey(const Key("remove-from-collection")), findsOneWidget);
      expect(find.byKey(const Key("add-to-collection")), findsOneWidget);
    });

    testWidgets("removes the selection without touching the photos",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Best"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "remove-from-collection");

      expect(
          find.text("The photos are removed from this collection. They "
              "stay in their albums."),
          findsOneWidget);
      await tapKey(tester, "remove-from-collection-confirm");

      var post = space.posts("delete").single;
      expect(pathOf(post), "/valbum/data/Best/");
      expect(post.body, '{"target":"","names":[{"name":"a.jpg"}]}');
      // The server's own words, and the collection fetched anew.
      expect(
          find.text("Removed from the collection; the photo stays in its "
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
      // A gone photo is offered to nobody's collection.
      expect(find.byKey(const Key("add-to-collection")), findsNothing);
      await tapKey(tester, "remove-from-collection");
      await tapKey(tester, "remove-from-collection-confirm");

      expect(space.posts("delete").single.body,
          '{"target":"","names":[{"name":"gone.jpg"}]}');
    });

    testWidgets(
        "an edit is written to the collection and the source album "
        "is fetched anew", (tester) async {
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

  group("adding to a collection", () {
    testWidgets("from an album's edit mode, chosen in the picker",
        (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "add-to-collection");

      // The picker opens above the album; a folder cannot be confirmed.
      expect(find.byKey(const Key("collection-picker")), findsOneWidget);
      expect(find.byKey(const Key("picker-needs-collection")), findsOneWidget);
      expect(
          tester
              .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
              .onPressed,
          isNull);
      // An album is no collection either.
      await tapKey(tester, "picker-folder-2020 Trip");
      expect(
          tester
              .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
              .onPressed,
          isNull);
      await tapKey(tester, "picker-up");
      await tapKey(tester, "picker-folder-Best");
      await tapKey(tester, "picker-confirm");

      var post = space.posts("collect").single;
      expect(pathOf(post), "/valbum/data/Inbox/");
      expect(post.body, '{"target":"Best","names":[{"name":"a.jpg"}]}');
      expect(find.text("Added 1 photo to 'Best'."), findsOneWidget);
      // Nothing was moved out of the album.
      expect(space.posts("move"), isEmpty);
    });

    testWidgets("into a collection created in the picker", (tester) async {
      var space = Space()..createdPath = "New";
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "add-to-collection");
      await tapKey(tester, "picker-create-collection");
      await tester.enterText(find.byKey(const Key("collection-title")), "New");
      await tapKey(tester, "create-collection-confirm");

      var put = space.puts.single;
      expect(pathOf(put), "/valbum/data/New/");
      expect((Resource.read(JsonReader.fromString(put.body)) as AlbumInfo).kind,
          AlbumKind.collection);
      var post = space.posts("collect").single;
      expect(space.requests.indexOf(post),
          greaterThan(space.requests.indexOf(put)));
      expect(post.body, '{"target":"New","names":[{"name":"a.jpg"}]}');
    });

    testWidgets("a move never goes into a collection", (tester) async {
      var space = Space();
      await pumpRoute(tester, space.client, const ["Inbox"]);
      await enterEditMode(tester);
      await openMenu(tester);
      await tapKey(tester, "move-to");
      await tapKey(tester, "picker-folder-Best");
      expect(
          find.byKey(const Key("picker-collection-refused")), findsOneWidget);
      expect(
          tester
              .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
              .onPressed,
          isNull);
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
        caller: const CallerInfo(userName: "anna", role: roleMember, space: "anna"),
        editPath: const ["album"],
      );
      await tester.tap(find.byKey(const Key("viewer-menu")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("viewer-add-to-collection")));
      await tester.pumpAndSettle();
      await tapKey(tester, "picker-folder-Best");
      await tapKey(tester, "picker-confirm");

      var post = space.posts("collect").single;
      expect(pathOf(post), "/valbum/data/album/");
      expect(post.body, '{"target":"Best","names":[{"name":"a.jpg"}]}');
    });

    testWidgets("the viewer names no faces in a collection", (tester) async {
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
      expect(find.byKey(const Key("viewer-add-to-collection")), findsOneWidget);
    });
  });

  testWidgets("the German words of a collection", (tester) async {
    var l10n = l10nOf(const Locale("de"));
    expect(l10n.createCollection, "Neue Sammlung");
    expect(l10n.removeFromCollection, "Aus der Sammlung entfernen");
    expect(l10n.addedToCollection(2, "'Best'"),
        "2 Fotos wurden zu 'Best' hinzugefügt.");
  });
}
