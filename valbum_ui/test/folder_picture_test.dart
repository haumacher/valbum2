/// The app half of issue #110: a folder of folders is shown by the picture of
/// the child its author chose.
///
/// The choice is stored on the *folder's own* listing ([ListingInfo.index]),
/// so it is made where a folder's entries are — in the long-press menu of a
/// tile — and written with the ordinary listing PUT. The server resolves it
/// and answers the parent's `indexPicture` with the path from the chosen child
/// down to the photograph, which the tile builds an ordinary image address
/// from.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'move_test.dart' hide main;
import 'share_link_test.dart' as share;
import 'util/fake_image_http.dart';

/// The listing of the folder `F`, which holds the album `A` and has no choice
/// of its own yet.
String folderF({String index = ""}) => '["ListingInfo", {"path": "F", '
    '"title": "F"${index.isEmpty ? "" : ', "index": "$index"'}, '
    '"placement": "NONE", "folders": ['
    '{"name": "A", "title": "A", "kind": "ALBUM", '
    '"effectiveDate": 1015113600000}, '
    '{"name": "B", "title": "B", "kind": "ALBUM"}'
    ']}]';

/// The listing above it, where `F` is shown by the picture of its child: the
/// server answers the path from the entry down to the photograph.
const String rootWithCoveredFolder = '["ListingInfo", {"path": "", '
    '"title": "Library", "folders": ['
    '{"name": "F", "title": "F", "kind": "FOLDER", '
    '"indexPicture": {"image": "A/a.jpg", "scale": 1, "tx": 0, "ty": 0}}'
    ']}]';

/// The listing above it while nothing is chosen: the folder icon.
const String rootWithBareFolder = '["ListingInfo", {"path": "", '
    '"title": "Library", "folders": ['
    '{"name": "F", "title": "F", "kind": "FOLDER"}'
    ']}]';

Future<void> pumpAt(
  WidgetTester tester,
  http.Response Function(http.Request) handler, {
  List<String> route = const [],
  List<http.Request>? requests,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: recordingClient(handler, requests ?? []),
      initialRoute: ListingOrAlbumRoute(route),
    ));
    await tester.pumpAndSettle();
  });
}

/// Long-presses the tile named [name] and opens its menu.
Future<void> longPressTileNamed(WidgetTester tester, String name) async {
  await tester.longPress(
    find
        .ancestor(of: find.text(name), matching: find.byType(GestureDetector))
        .first,
  );
  await tester.pumpAndSettle();
}

/// The URLs of the thumbnails the listing asked for.
List<String> thumbnailUrls(WidgetTester tester) => [
      for (var image in tester.widgetList<Image>(find.byType(Image)))
        if (image.image is ThumbnailImage) (image.image as ThumbnailImage).url,
    ];

void main() {
  group('the tile of a folder of folders', () {
    testWidgets('asks for the picture the server named, path and all',
        (tester) async {
      await pumpAt(tester, (_) => json(rootWithCoveredFolder));

      // `<data>/F/A/a.jpg?type=tn` — the entry's name and the path the server
      // answered, which is an ordinary image address.
      expect(thumbnailUrls(tester), [
        "$dataUrl/F/A/a.jpg?type=tn",
      ]);
      // And no folder icon where there is a picture.
      expect(find.byIcon(Icons.folder), findsNothing);
    });
  });

  group('the folder picture menu', () {
    testWidgets('writes the chosen entry into the listing and reloads',
        (tester) async {
      var requests = <http.Request>[];
      await pumpAt(
        tester,
        (request) {
          if (request.method == "PUT") {
            return json("");
          }
          return json(pathOf(request) == "/valbum/data/F/"
              ? folderF()
              : rootWithBareFolder);
        },
        route: const ["F"],
        requests: requests,
      );

      await longPressTileNamed(tester, "A");
      expect(find.byKey(const Key("use-as-folder-picture")), findsOneWidget);
      // Nothing is chosen yet, so there is nothing to take away.
      expect(find.byKey(const Key("clear-folder-picture")), findsNothing);
      await tester.tap(find.text(useAsFolderPicture));
      await tester.pumpAndSettle();

      var put = requests.singleWhere((r) => r.method == "PUT");
      expect(pathOf(put), "/valbum/data/F/");
      expect(put.body, contains('"index":"A"'));

      // Both this listing and the one above it are asked for again: the tile
      // that changed is the tile of *this* folder, up there (issue #134).
      var after = requests.sublist(requests.indexOf(put));
      expect(jsonGets(after), contains("/valbum/data/F/"));
      // The parent was forgotten, so ascending fetches it anew.
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(
        jsonGets(requests.sublist(requests.indexOf(put))),
        contains("/valbum/data/"),
      );
    });

    testWidgets('takes the choice away again', (tester) async {
      var requests = <http.Request>[];
      await pumpAt(
        tester,
        (request) => request.method == "PUT"
            ? json("")
            : json(pathOf(request) == "/valbum/data/F/"
                ? folderF(index: "A")
                : rootWithCoveredFolder),
        route: const ["F"],
        requests: requests,
      );

      await longPressTileNamed(tester, "B");
      expect(find.byKey(const Key("clear-folder-picture")), findsOneWidget);
      await tester.tap(find.text(useNoFolderPicture));
      await tester.pumpAndSettle();

      var put = requests.singleWhere((r) => r.method == "PUT");
      expect(put.body, contains('"index":""'));
    });

    testWidgets('says why when the server refuses the write', (tester) async {
      await pumpAt(
        tester,
        (request) => request.method == "PUT"
            ? http.Response(
                '["ErrorInfo", {"message": "Not your folder."}]',
                403,
                headers: {"content-type": "application/json"},
              )
            : json(pathOf(request) == "/valbum/data/F/"
                ? folderF()
                : rootWithBareFolder),
        route: const ["F"],
      );

      await longPressTileNamed(tester, "A");
      await tester.tap(find.text(useAsFolderPicture));
      await tester.pumpAndSettle();

      expect(find.text("Not your folder."), findsOneWidget);
    });

    testWidgets('is not offered to a caller who may only look', (tester) async {
      await pumpAt(
        tester,
        (_) => json('["ListingInfo", {"path": "F", "title": "F", '
            '"rights": [{"name": "view"}, {"name": "download"}], '
            '"folders": [{"name": "A", "title": "A", "kind": "ALBUM"}]}]'),
        route: const ["F"],
      );

      await longPressTileNamed(tester, "A");

      expect(find.byKey(const Key("use-as-folder-picture")), findsNothing);
      expect(find.byKey(const Key("clear-folder-picture")), findsNothing);
    });

    testWidgets('is not offered inside a share link', (tester) async {
      await share.pumpLinkSession(tester, (request) {
        if (request.url.queryParameters["type"] == "auth") {
          return json(share.authOfLink(
            rights: const ["view", "download", "contribute", "edit"],
          ));
        }
        return json('["ListingInfo", {"path": "", "title": "Shared", '
            '"rights": [{"name": "view"}, {"name": "download"}, '
            '{"name": "contribute"}, {"name": "edit"}], '
            '"folders": [{"name": "A", "title": "A", "kind": "ALBUM"}]}]');
      });

      await longPressTileNamed(tester, "A");

      // A link is a view, not a console: it changes nothing of the folder it
      // was handed out on, see issue #51.
      expect(find.byKey(const Key("use-as-folder-picture")), findsNothing);
      expect(find.byKey(const Key("clear-folder-picture")), findsNothing);
    });
  });
}
