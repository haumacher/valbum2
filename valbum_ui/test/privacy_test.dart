/// Tests of the privacy levels (issue #46): the marker on a restricted tile,
/// the privacy control beside the rating, and the "view as" preview of the
/// edit mode.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// The tile of the image with the given file name.
Finder tile(String name) => find.byKey(ValueKey(name));

/// The privacy marker on the tile of [name].
Finder marker(String name) => find.descendant(
      of: tile(name),
      matching: find.byKey(const Key("privacy-marker")),
    );

/// The privacy control on the tile of [name].
Finder control(String name) => find.descendant(
      of: tile(name),
      matching: find.byKey(const Key("privacy-control")),
    );

/// The icon shown by the widget the given finder names.
IconData? iconOf(WidgetTester tester, Finder finder) => tester
    .widget<Icon>(
      find.descendant(of: finder, matching: find.byType(Icon), matchRoot: true),
    )
    .icon;

AlbumContentState albumState(WidgetTester tester) =>
    tester.state<AlbumContentState>(find.byType(AlbumContent));

AlbumInfo album(WidgetTester tester) => albumState(tester).widget.album;

/// Taps a tile beside its toolbars, so that the tap reaches the tile itself.
Future<void> tapTile(WidgetTester tester, String name) async {
  var box = tester.getRect(tile(name));
  await tester.tapAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

/// Enters the edit mode by a long press and selects the tile of [select].
Future<void> pumpEditMode(
  WidgetTester tester,
  VAlbumClient client, {
  String select = "landscape.jpg",
  OfflineCache? cache,
}) async {
  await tester.pumpWidget(VAlbumApp(client: client, cache: cache));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
  await tapTile(tester, select);
}

/// Chooses the given entry of the "view as" menu of the edit mode.
Future<void> chooseViewAs(WidgetTester tester, ViewAs view) async {
  await tester.tap(find.byTooltip("View as"));
  await tester.pumpAndSettle();
  await tester.tap(find.text(view.label).last);
  await tester.pumpAndSettle();
}

/// The album with parts of all three levels.
String get privacyAlbum => fixture("album-privacy.json");

/// The same album as a member of it receives it: the two private parts gone.
String get membersAlbum => fixture("album-privacy-members.json");

/// A client answering through [handler], recording every request and caching
/// what it answers, so a test can see what reached the cache.
VAlbumClient cachingClient(
  http.Response Function(http.Request request) handler, {
  required OfflineCache cache,
  required List<http.Request> requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      cache: cache,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

/// The `viewAs` parameter of every JSON request made so far, in order.
List<String?> viewAsOf(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          request.url.queryParameters["viewAs"],
    ];

void main() {
  group('the privacy model helpers', () {
    test('names the three levels', () {
      expect(privacyName(privacyPublic), "Public");
      expect(privacyName(privacyMembers), "Members");
      expect(privacyName(privacyPrivate), "Private");
    });

    test('cycles through the levels', () {
      expect(nextPrivacy(privacyPublic), privacyMembers);
      expect(nextPrivacy(privacyMembers), privacyPrivate);
      expect(nextPrivacy(privacyPrivate), privacyPublic);
    });

    test('reads the level of a group off its representative', () {
      var group = ImageGroup(
        representative: 1,
        images: [
          ImagePart(name: "a.jpg", privacy: privacyPublic),
          ImagePart(name: "b.jpg", privacy: privacyPrivate),
        ],
      );
      expect(privacyOf(group), privacyPrivate);
      expect(privacyOf(Heading(text: "H")), privacyPublic);
    });
  });

  group('the privacy marker', () {
    testWidgets('marks members and private tiles, in the view mode',
        (tester) async {
      var client = clientReturning(privacyAlbum, dataUrl: dataUrl);

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        expect(marker("landscape.jpg"), findsNothing);
        expect(marker("group-a.jpg"), findsNothing);
        expect(iconOf(tester, marker("portrait.jpg")), Icons.group);
        expect(iconOf(tester, marker("secret.jpg")), Icons.lock);
        expect(iconOf(tester, marker("hidden.jpg")), Icons.lock);

        // The level names the marker by its tooltip.
        expect(
          tester
              .widget<Tooltip>(find.ancestor(
                of: marker("secret.jpg"),
                matching: find.byType(Tooltip),
              ))
              .message,
          "Private",
        );
      });
    });

    testWidgets('marks the same tiles in the edit mode', (tester) async {
      var client = clientReturning(privacyAlbum, dataUrl: dataUrl);

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        expect(marker("landscape.jpg"), findsNothing);
        expect(iconOf(tester, marker("portrait.jpg")), Icons.group);
        expect(iconOf(tester, marker("secret.jpg")), Icons.lock);
      });
    });

    testWidgets('leaves an album without privacy fields unmarked',
        (tester) async {
      var client = clientReturning(fixture("album.json"), dataUrl: dataUrl);

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);

        expect(find.byKey(const Key("privacy-marker")), findsNothing);
        // The control of an old part says what its absent level means.
        expect(iconOf(tester, control("landscape.jpg")), Icons.public);
        expect((album(tester).parts[1] as ImagePart).privacy, privacyPublic);
      });
    });
  });

  group('the privacy control', () {
    testWidgets('shows the level and saves the change', (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("", 200)
            : http.Response(privacyAlbum, 200),
        dataUrl: dataUrl,
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client, select: "secret.jpg");

        // A private tile shows the lock in its control.
        expect(iconOf(tester, control("secret.jpg")), Icons.lock);
        expect(albumState(tester).dirty, isFalse);

        // One tap cycles private -> public.
        await tester.tap(control("secret.jpg"));
        await tester.pumpAndSettle();

        var secret = album(tester).parts[3] as ImagePart;
        expect(secret.name, "secret.jpg");
        expect(secret.privacy, privacyPublic);
        expect(albumState(tester).dirty, isTrue);
        expect(marker("secret.jpg"), findsNothing);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var put = requests.where((r) => r.method == "PUT").single;
      // The changed level is on the wire.
      expect(put.body, contains('"privacy":0'));
      expect(put.body, contains('"privacy":2'));

      var saved = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
      expect(saved.parts, hasLength(6));
      expect((saved.parts[3] as ImagePart).privacy, privacyPublic);
      // Nothing else was lost or changed.
      expect((saved.parts[2] as ImagePart).privacy, privacyMembers);
      expect((saved.parts[5] as ImagePart).privacy, privacyPrivate);
    });

    testWidgets('sets every member of a group', (tester) async {
      var client = clientReturning(privacyAlbum, dataUrl: dataUrl);

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client, select: "group-a.jpg");

        expect(iconOf(tester, control("group-a.jpg")), Icons.public);

        // Public -> members, on the group as a whole.
        await tester.tap(control("group-a.jpg"));
        await tester.pumpAndSettle();

        var group = album(tester).parts[4] as ImageGroup;
        expect(group.images.map((image) => image.privacy), [
          privacyMembers,
          privacyMembers,
        ]);
        expect(iconOf(tester, control("group-a.jpg")), Icons.group);
        expect(iconOf(tester, marker("group-a.jpg")), Icons.group);
      });
    });
  });

  group('the "view as" preview', () {
    testWidgets('asks the server with viewAs and shows the answer read-only',
        (tester) async {
      var cache = MemoryOfflineCache();
      var requests = <http.Request>[];
      var client = cachingClient(
        (request) => http.Response(
          request.url.queryParameters["viewAs"] == "members"
              ? membersAlbum
              : privacyAlbum,
          200,
        ),
        cache: cache,
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client, cache: cache);
        expect(viewAsOf(requests), [null]);

        await chooseViewAs(tester, ViewAs.members);

        // The request carried the clearance to preview.
        expect(viewAsOf(requests), [null, "members"]);

        // The smaller album the server answered with is what is shown.
        expect(tile("secret.jpg"), findsNothing);
        expect(tile("hidden.jpg"), findsNothing);
        expect(tile("portrait.jpg"), findsOneWidget);

        // Read-only: no tile editor, no reordering.
        expect(find.byType(ThumbnailEditor), findsNothing);
        expect(find.byType(ReorderablePart), findsNothing);
        expect(find.byIcon(Icons.save), findsNothing);
        expect(find.byIcon(Icons.cloud_upload), findsNothing);

        expect(
          find.text("Viewing as members - this is what members see"),
          findsOneWidget,
        );

        // The preview is never written to the offline cache.
        var entry = await cache.getResource(dataUrl, const []);
        expect(entry, isNotNull);
        expect(entry!.text, privacyAlbum);

        // And the way back asks for the album itself again.
        await chooseViewAs(tester, ViewAs.owner);
        expect(viewAsOf(requests), [null, "members", null]);
        expect(find.byKey(const Key("view-as-banner")), findsNothing);
        expect(tile("secret.jpg"), findsOneWidget);
      });
    });

    testWidgets('shows the server\'s refusal verbatim', (tester) async {
      var client = clientHandling(
        (request) => request.url.queryParameters["viewAs"] != null
            ? http.Response(
                '["ErrorInfo", {"message": "Unknown clearance: members."}]',
                400,
              )
            : http.Response(privacyAlbum, 200),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client);
        await chooseViewAs(tester, ViewAs.members);

        expect(find.text("Unknown clearance: members."), findsOneWidget);
        // The album stayed where it was.
        expect(find.byKey(const Key("view-as-banner")), findsNothing);
        expect(tile("secret.jpg"), findsOneWidget);
      });
    });

    testWidgets('refuses to leave unsaved changes behind', (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => http.Response(privacyAlbum, 200),
        dataUrl: dataUrl,
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await pumpEditMode(tester, client, select: "secret.jpg");

        await tester.tap(control("secret.jpg"));
        await tester.pumpAndSettle();
        expect(albumState(tester).dirty, isTrue);

        await chooseViewAs(tester, ViewAs.members);

        expect(find.text("Save or discard your changes first"), findsOneWidget);
        // Nothing was fetched, and the edit is still there.
        expect(viewAsOf(requests), [null]);
        expect((album(tester).parts[3] as ImagePart).privacy, privacyPublic);
        expect(albumState(tester).viewAs, ViewAs.owner);
      });
    });
  });
}
