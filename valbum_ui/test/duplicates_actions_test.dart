/// Tests of what the overview of the photos in several albums lets one do
/// with a copy, issue #228.
///
/// A viewer opened from the overview sits on it — its way back, `Escape` and
/// the system's back button return to the overview, which stayed mounted —
/// and an editor takes one copy out of its album from the list: the copy is
/// rated as trash (−2) in that album, after a question, and the overview is
/// asked again.
library;

import 'dart:convert';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/duplicates_view.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'duplicates_view_test.dart'
    show
        authOf,
        copy,
        dataUrl,
        editRights,
        groupJson,
        json,
        list,
        pump,
        rightsJson,
        root,
        routeOf;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

const String tripFolder = "2023-07-01 Trip";
const String bestFolder = "2023-08-01 Best of";

/// The album "Trip": two plain photos.
String tripAlbum() =>
    '["AlbumInfo", {"path": "", "title": "Trip", "subTitle": "", "rights": ['
    '${rightsJson(editRights)}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "IMG_1.jpg", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 1, '
    '"comment": "Sunset"}],'
    '["ImagePart", {"kind": "IMAGE", "name": "IMG_2.jpg", "date": 2, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY"}]]}]';

/// The album "Best of": a group whose second member is the copy.
String bestAlbum() =>
    '["AlbumInfo", {"path": "", "title": "Best of", "subTitle": "", "rights": ['
    '${rightsJson(editRights)}], "parts": ['
    '["ImageGroup", {"representative": 0, "images": ['
    '{"kind": "IMAGE", "name": "front.jpg", "date": 1, "width": 20, '
    '"height": 10, "orientation": "IDENTITY", "rating": 1},'
    '{"kind": "IMAGE", "name": "beach.jpg", "date": 2, "width": 20, '
    '"height": 10, "orientation": "IDENTITY"}]}]]}]';

/// Two photos: one in three albums, one in two.
String overview() => list([
      groupJson("h1", [
        copy(tripFolder, "Trip", "IMG_1.jpg", albumDate: 1688169600000),
        copy(bestFolder, "Best of", "beach.jpg"),
        copy("Inbox", "", "IMG_1.jpg"),
      ]),
      groupJson("h2", [
        copy(tripFolder, "Trip", "IMG_2.jpg"),
        copy("", "", "IMG_2.jpg"),
      ]),
    ]);

/// Many photos, so that the overview scrolls.
String longOverview() => list([
      for (var index = 0; index < 30; index++)
        groupJson("g$index", [
          copy(tripFolder, "Trip", "IMG_1.jpg"),
          copy(bestFolder, "Best of", "beach.jpg"),
        ]),
    ]);

VAlbumClient server({
  required List<http.Request> requests,
  String role = "edit",
  String Function()? duplicates,
  http.Response Function(http.Request request)? put,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "dev-9",
      userName: "carol",
      httpClient: MockClient((request) async {
        requests.add(request);
        var path = Uri.decodeComponent(request.url.path);
        if (isThumbnailRequest(request) || path.endsWith(".jpg")) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: const {"content-type": "image/png"},
          );
        }
        var query = request.url.queryParameters;
        if (query["type"] == "auth") {
          return json(authOf(role));
        }
        if (query["type"] == "duplicates") {
          return json(duplicates?.call() ?? overview());
        }
        if (request.method == "PUT") {
          return put?.call(request) ?? json('["CreateResult", {"path": ""}]');
        }
        if (path.contains(tripFolder)) {
          return json(tripAlbum());
        }
        if (path.contains(bestFolder)) {
          return json(bestAlbum());
        }
        return json(root(editRights));
      }),
    );

/// The album a request body carries.
AlbumInfo readAlbum(String body) =>
    Resource.read(JsonReader.fromString(body)) as AlbumInfo;

int duplicatesAsked(List<http.Request> requests) => requests
    .where((request) => request.url.queryParameters["type"] == "duplicates")
    .length;

List<http.Request> puts(List<http.Request> requests) =>
    requests.where((request) => request.method == "PUT").toList();

/// The scroll offset of the overview's list.
double overviewOffset(WidgetTester tester) => tester
    .state<ScrollableState>(find
        .descendant(
            of: find.byType(DuplicatesView), matching: find.byType(Scrollable))
        .first)
    .position
    .pixels;

const ImageRoute tripViewer =
    ImageRoute([tripFolder], "IMG_1.jpg", fromDuplicates: true);

void main() {
  group('the route of a viewer opened from the overview', () {
    test('is addressed below /.duplicates/ and read back', () {
      expect(Uri.decodeComponent(tripViewer.path),
          "/.duplicates/$tripFolder/IMG_1.jpg");
      expect(
          parseRoute(routeToUri(tripViewer, basePath: "/valbum/"),
              basePath: "/valbum/"),
          tripViewer);
      expect(parseRoute(Uri.parse("/.duplicates/IMG_2.jpg")),
          const ImageRoute([], "IMG_2.jpg", fromDuplicates: true));
      expect(parseRoute(Uri.parse("/.duplicates/A/B/c.jpg")),
          const ImageRoute(["A", "B"], "c.jpg", fromDuplicates: true));
    });

    test('is another route than the album\'s own viewer', () {
      expect(tripViewer, isNot(const ImageRoute([tripFolder], "IMG_1.jpg")));
      expect(parseRoute(Uri.parse("/$tripFolder/IMG_1.jpg")),
          const ImageRoute([tripFolder], "IMG_1.jpg"));
    });

    test('sits on the overview and leads back there', () {
      expect(VAlbumRouterDelegate.levelsOf(tripViewer),
          [ListingOrAlbumRoute.root, const DuplicatesRoute(), tripViewer]);
      expect(tripViewer.up, const DuplicatesRoute());
    });

    test('keeps its way back while paging', () {
      expect(tripViewer.withName("IMG_2.jpg"),
          const ImageRoute([tripFolder], "IMG_2.jpg", fromDuplicates: true));
      expect(tripViewer.withAlbumPath(["X"]),
          const ImageRoute(["X"], "IMG_1.jpg", fromDuplicates: true));
    });
  });

  group('back to the overview', () {
    Future<List<http.Request>> openFromThumbnail(WidgetTester tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));
      await tester.tap(find.byKey(const ValueKey("duplicates-thumbnail-h1")));
      await tester.pumpAndSettle();
      expect(routeOf(tester), tripViewer);
      return requests;
    }

    void expectOverviewAgain(WidgetTester tester, List<http.Request> requests) {
      expect(routeOf(tester), const DuplicatesRoute());
      expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
      expect(duplicatesAsked(requests), 1,
          reason: "The overview stayed mounted beneath the viewer.");
    }

    testWidgets('by the viewer\'s way up', (tester) async {
      var requests = await openFromThumbnail(tester);

      await tester.tap(find.byTooltip(testL10n.duplicatesBack));
      await tester.pumpAndSettle();

      expectOverviewAgain(tester, requests);
    });

    testWidgets('by Escape', (tester) async {
      var requests = await openFromThumbnail(tester);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();

      expectOverviewAgain(tester, requests);
    });

    testWidgets('by the system\'s back button', (tester) async {
      var requests = await openFromThumbnail(tester);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      expectOverviewAgain(tester, requests);
    });

    testWidgets('from the entry of another album', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(
          find.byKey(const ValueKey("duplicates-copy-$bestFolder/beach.jpg")));
      await tester.pumpAndSettle();
      expect(routeOf(tester),
          const ImageRoute([bestFolder], "beach.jpg", fromDuplicates: true));

      await tester.tap(find.byTooltip(testL10n.duplicatesBack));
      await tester.pumpAndSettle();

      expectOverviewAgain(tester, requests);
    });

    testWidgets('after paging through the album', (tester) async {
      var requests = await openFromThumbnail(tester);

      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pumpAndSettle();
      expect(routeOf(tester),
          const ImageRoute([tripFolder], "IMG_2.jpg", fromDuplicates: true));

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();

      expectOverviewAgain(tester, requests);
    });

    testWidgets('where it was scrolled to', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests, duplicates: longOverview));

      await tester.drag(find.byType(DuplicatesView), const Offset(0, -900));
      await tester.pumpAndSettle();
      var offset = overviewOffset(tester);
      expect(offset, greaterThan(0));

      var entry = find
          .byKey(const ValueKey("duplicates-copy-$bestFolder/beach.jpg"))
          .hitTestable()
          .first;
      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(routeOf(tester),
          const ImageRoute([bestFolder], "beach.jpg", fromDuplicates: true));

      await tester.tap(find.byTooltip(testL10n.duplicatesBack));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const DuplicatesRoute());
      expect(overviewOffset(tester), offset);
      expect(duplicatesAsked(requests), 1);
    });

    testWidgets('a deep link builds the same pages', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests), route: tripViewer);

      await tester.tap(find.byTooltip(testL10n.duplicatesBack));
      await tester.pumpAndSettle();

      expect(routeOf(tester), const DuplicatesRoute());
      expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
    });
  });

  group('deleting a copy', () {
    const tripKey = ValueKey("duplicates-delete-$tripFolder/IMG_1.jpg");
    const bestKey = ValueKey("duplicates-delete-$bestFolder/beach.jpg");

    testWidgets('is offered to an editor', (tester) async {
      await pump(tester, server(requests: []));

      expect(find.byKey(tripKey), findsOneWidget);
      expect(find.byKey(bestKey), findsOneWidget);
      expect(
          find.byTooltip(testL10n.duplicatesDeleteTooltip), findsNWidgets(5));
    });

    testWidgets('is not offered to a viewer', (tester) async {
      await pump(tester, server(requests: [], role: "view"));

      expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
      expect(find.byKey(tripKey), findsNothing);
      expect(find.byTooltip(testL10n.duplicatesDeleteTooltip), findsNothing);
    });

    testWidgets('asks first and sends nothing on Cancel', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));
      var before = requests.length;

      await tester.tap(find.byKey(tripKey));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("duplicates-delete-dialog")), findsOneWidget);
      expect(find.text(testL10n.duplicatesDeleteQuestion("Trip", "IMG_1.jpg")),
          findsOneWidget);

      await tester.tap(find.byKey(const Key("duplicates-delete-cancel")));
      await tester.pumpAndSettle();

      expect(requests.length, before);
      expect(routeOf(tester), const DuplicatesRoute());
    });

    testWidgets('rates that copy as trash in its album and asks again',
        (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(find.byKey(tripKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("duplicates-delete-confirm")));
      await tester.pumpAndSettle();

      var written = puts(requests);
      expect(written, hasLength(1));
      expect(Uri.decodeComponent(written.single.url.path),
          "/valbum/data/$tripFolder/");
      var album = readAlbum(written.single.body);
      var parts = album.parts.cast<ImagePart>();
      expect(parts.map((part) => part.name), ["IMG_1.jpg", "IMG_2.jpg"]);
      expect(parts[0].rating, -2);
      expect(parts[0].comment, "Sunset", reason: "Nothing else changes.");
      expect(parts[1].rating, 0);
      // The written album is the one read, but for the one rating.
      var stored = jsonDecode(tripAlbum()) as List;
      ((stored[1] as Map)["parts"] as List)[0][1]["rating"] = -2;
      var original = readAlbum(jsonEncode(stored));
      expect(album.toString(), original.toString());

      expect(requests.where((request) => request.method == "POST"), isEmpty,
          reason: "Never ?action=delete.");
      expect(duplicatesAsked(requests), 2);
      expect(find.byKey(const Key("duplicates-deleted")), findsOneWidget);
      expect(find.text(testL10n.duplicatesDeleted("Trip", "IMG_1.jpg")),
          findsOneWidget);
    });

    testWidgets('rates a group member by itself', (tester) async {
      var requests = <http.Request>[];
      await pump(tester, server(requests: requests));

      await tester.tap(find.byKey(bestKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("duplicates-delete-confirm")));
      await tester.pumpAndSettle();

      var written = puts(requests).single;
      expect(
          Uri.decodeComponent(written.url.path), "/valbum/data/$bestFolder/");
      var album = readAlbum(written.body);
      var group = album.parts.single as ImageGroup;
      expect(
          group.images.map((image) => image.name), ["front.jpg", "beach.jpg"]);
      expect(group.images[0].rating, 1);
      expect(group.images[1].rating, -2);
      expect(group.representative, 0);
    });

    testWidgets('says the server\'s refusal and changes nothing',
        (tester) async {
      var requests = <http.Request>[];
      await pump(
          tester,
          server(
              requests: requests,
              put: (_) => json(
                  '["ErrorInfo", {"message": "Not yours to change."}]', 403)));

      await tester.tap(find.byKey(tripKey));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("duplicates-delete-confirm")));
      await tester.pumpAndSettle();

      expect(find.text("Not yours to change."), findsOneWidget);
      expect(find.byKey(const Key("duplicates-deleted")), findsNothing);
      expect(duplicatesAsked(requests), 1);
      expect(find.byKey(const ValueKey("duplicates-group-h1")), findsOneWidget);
    });

    testWidgets('is refused offline without a request', (tester) async {
      var requests = <http.Request>[];
      var state = OfflineState();
      await pump(tester, server(requests: requests), offlineState: state);
      state.goneOffline(null);
      await tester.pumpAndSettle();
      var before = requests.length;

      await tester.tap(find.byKey(tripKey));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("duplicates-delete-dialog")), findsNothing);
      expect(find.text(testL10n.offlineRefusal), findsOneWidget);
      expect(requests.length, before);
    });
  });
}
