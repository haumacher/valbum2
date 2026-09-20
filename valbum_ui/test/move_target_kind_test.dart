/// Tests of what the move dialog offers (issues #113 and #114): the kind of
/// the target decides whether it can be picked at all, every entry is one
/// `date title` line, and the album an image is to go into may be created
/// right there.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

const String dataUrl = "http://server/valbum/data";

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The album with the date and the title the entry line is read from.
const String datedAlbumName = "2002-03-03 Schlosspark Karlsruhe";

/// 2002-03-03, midday UTC, so that the day is the same in every time zone.
const int march3rd = 1015156800000;

/// 2003-05-01, midday UTC.
const int may1st = 1051790400000;

/// 2005-08-24, midday UTC.
const int august24th = 1124884800000;

/// An album whose folder name still carries the title it was created with:
/// the line is composed from what the entry carries, so the stale name never
/// reaches the screen.
const String renamedAlbumName = "2005-08-24 Alter Titel";

/// The root: an album with a date and a title, a folder of folders without a
/// date, and the source album, whose title is empty.
const String root = '["ListingInfo", {"path": "", "title": "My albums", '
    '"rights": [{"name": "edit"}], "folders": ['
    '{"name": "$datedAlbumName", "title": "Schlosspark Karlsruhe", '
    '"effectiveDate": $march3rd}, '
    '{"name": "$renamedAlbumName", "title": "Blumen und Fliegen", '
    '"effectiveDate": $august24th}, '
    '{"name": "2021", "title": "2021"}, '
    '{"name": "Inbox", "title": ""}'
    ']}]';

/// A folder of folders below the root.
const String folder2021 = '["ListingInfo", {"path": "2021", "title": "2021", '
    '"rights": [{"name": "edit"}], "folders": ['
    '{"name": "Summer", "title": "Summer"}'
    ']}]';

/// The album the images are taken out of: the later photo comes first, so
/// that the earliest date is really looked for and not simply the first one.
const String inbox = '["AlbumInfo", {"path": "Inbox", "title": "Inbox", '
    '"subTitle": "", "rights": [{"name": "edit"}], "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": $may1st, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}], '
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": $march3rd, '
    '"width": 2048, "height": 1536, "orientation": "IDENTITY"}]'
    ']}]';

const String datedAlbum = '["AlbumInfo", {"path": "$datedAlbumName", '
    '"title": "Schlosspark Karlsruhe", "subTitle": "", '
    '"rights": [{"name": "edit"}], "parts": []}]';

http.Response treeAnswer(http.Request request) {
  switch (pathOf(request)) {
    case "/valbum/data/":
      return json(root);
    case "/valbum/data/2021/":
      return json(folder2021);
    case "/valbum/data/Inbox/":
      return json(inbox);
    case "/valbum/data/$datedAlbumName/":
      return json(datedAlbum);
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

VAlbumClient recordingClient(
  http.Response Function(http.Request request) handler,
  List<http.Request> requests,
) =>
    VAlbumClient(
      dataUrl: dataUrl,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        return handler(request);
      })),
    );

/// Whether the confirm button of the open picker can be pressed.
bool confirmEnabled(WidgetTester tester) =>
    tester
        .widget<ElevatedButton>(find.byKey(const Key("picker-confirm")))
        .onPressed !=
    null;

/// Shows `Inbox` in the edit mode with both images selected.
Future<void> pumpSelection(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: const ListingOrAlbumRoute(["Inbox"]),
  ));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
  var box = tester.getRect(find.byKey(const ValueKey("b.jpg")));
  await tester.longPressAt(Offset(box.left + 8, box.center.dy));
  await tester.pumpAndSettle();
}

Future<void> openPicker(WidgetTester tester) async {
  // The move lives in the album's menu since issue #121.
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key("move-to")));
  await tester.pumpAndSettle();
}

Future<void> enterFolder(WidgetTester tester, String name) async {
  await tester.tap(find.byKey(Key("picker-folder-$name")));
  await tester.pumpAndSettle();
}

/// Opens the picker on the folder `2021` of the root listing.
Future<void> openEntryPicker(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(
    find
        .ancestor(of: find.text("2021"), matching: find.byType(GestureDetector))
        .first,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text("Move to…"));
  await tester.pumpAndSettle();
}

void main() {
  group('the kind of the target decides (#113)', () {
    testWidgets('images are refused a folder of folders and take an album',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);

        // The top level is a folder of folders: nothing to confirm, and the
        // reason is on the screen.
        expect(find.text("Top level"), findsOneWidget);
        expect(find.byKey(const Key("picker-needs-album")), findsOneWidget);
        expect(find.text(imagesLiveInAlbums), findsOneWidget);
        expect(confirmEnabled(tester), isFalse);
        // The label stays honest either way.
        expect(find.text("Move 2 images to the top level"), findsOneWidget);

        // A folder of folders one level down is no better.
        await enterFolder(tester, "2021");
        expect(confirmEnabled(tester), isFalse);
        await tester.tap(find.byKey(const Key("picker-up")));
        await tester.pumpAndSettle();

        // An album takes images.
        await enterFolder(tester, datedAlbumName);
        expect(find.byKey(const Key("picker-needs-album")), findsNothing);
        expect(confirmEnabled(tester), isTrue);
      });

      // Nothing was sent while the picker was open.
      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });

    testWidgets('an album takes a folder of folders and no album',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await openEntryPicker(tester, client);

        // The top level is a folder of folders: a folder may go there.
        expect(confirmEnabled(tester), isTrue);
        expect(find.byKey(const Key("picker-needs-album")), findsNothing);

        await enterFolder(tester, datedAlbumName);
        expect(find.byKey(const Key("picker-leaf")), findsOneWidget);
        expect(find.text(albumHoldsNoFolders), findsOneWidget);
        expect(confirmEnabled(tester), isFalse);
      });

      expect(requests.where((r) => r.method != "GET"), isEmpty);
    });
  });

  group('one line per entry (#113)', () {
    testWidgets('the date and the title, and no second line', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);

        var dated = tester.widget<ListTile>(
            find.byKey(const Key("picker-folder-$datedAlbumName")));
        expect((dated.title as Text).data, "2002-03-03 Schlosspark Karlsruhe");
        expect(dated.subtitle, isNull,
            reason: "the folder name says the same thing twice");

        // The line is composed from the entry, never read off the name: a
        // title that was changed since shows, the stale name does not.
        var renamed = tester.widget<ListTile>(
            find.byKey(const Key("picker-folder-$renamedAlbumName")));
        expect((renamed.title as Text).data, "2005-08-24 Blumen und Fliegen");
        expect(renamed.subtitle, isNull);
        expect(find.text(renamedAlbumName), findsNothing);

        // Nothing says when this folder happened: the title alone.
        var undated = tester
            .widget<ListTile>(find.byKey(const Key("picker-folder-2021")));
        expect((undated.title as Text).data, "2021");
        expect(undated.subtitle, isNull);

        // No title: the name is what there is.
        var untitled = tester
            .widget<ListTile>(find.byKey(const Key("picker-folder-Inbox")));
        expect((untitled.title as Text).data, "Inbox");
      });
    });
  });

  group('creating the album to move into (#114)', () {
    /// Answers the tree, the album creation and the move; the created album
    /// is filed into its year folder, as a placement rule would file it.
    http.Response Function(http.Request) creating(
      http.Response Function(http.Request request)? onCreate,
    ) =>
        (request) {
          if (request.method == "PUT") {
            return onCreate != null
                ? onCreate(request)
                : json('{"path":"2002/2002-03-03 Ausflug",'
                    '"message":"Filed into \'2002\'."}');
          }
          if (request.url.queryParameters["action"] == "move") {
            return json('{"outcomes":['
                '{"name":"a.jpg","newName":"a.jpg","message":""},'
                '{"name":"b.jpg","newName":"b.jpg","message":""}]}');
          }
          return treeAnswer(request);
        };

    /// Opens "Create new album…" and fills the dialog in.
    Future<void> createAlbumNamed(WidgetTester tester, String title) async {
      await tester.tap(find.byKey(const Key("picker-create-album")));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).first, title);
      await tester.pumpAndSettle();
      await tester.tap(find.text("Anlegen"));
      await tester.pumpAndSettle();
    }

    testWidgets(
        'the dialog is dated by the earliest photo, and the move follows the '
        'album to where the server put it', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(creating(null), requests);

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);

        expect(find.byKey(const Key("picker-create-album")), findsOneWidget);
        await tester.tap(find.byKey(const Key("picker-create-album")));
        await tester.pumpAndSettle();

        // The earliest of the two photos, not the first of them.
        expect(find.text("2002-03-03"), findsOneWidget);

        await tester.enterText(find.byType(TextFormField).first, "Ausflug");
        await tester.pumpAndSettle();
        await tester.tap(find.text("Anlegen"));
        await tester.pumpAndSettle();
      });

      var put = requests.singleWhere((r) => r.method == "PUT");
      expect(
          Uri.decodeFull(put.url.toString()), "$dataUrl/2002-03-03 Ausflug/");

      var post = requests
          .singleWhere((r) => r.url.queryParameters["action"] == "move");
      expect(
          Uri.decodeFull(post.url.toString()), "$dataUrl/Inbox/?action=move");
      expect(
        post.body,
        '{"target":"2002/2002-03-03 Ausflug",'
        '"names":[{"name":"a.jpg"},{"name":"b.jpg"}]}',
        reason: "the images follow the album to where the server filed it",
      );
      // Where the album landed is said, not passed over in silence.
      expect(
        find.textContaining("Moved 2 images to '2002/2002-03-03 Ausflug'."),
        findsOneWidget,
      );
      expect(find.textContaining("Filed into '2002'."), findsOneWidget);
    });

    testWidgets('a refused creation moves nothing and says why',
        (tester) async {
      const refusal = "You may not add anything to this folder.";
      var requests = <http.Request>[];
      var client = recordingClient(
        creating(
            (_) => json('["ErrorInfo",{"message":"$refusal"}]', status: 403)),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);
        await createAlbumNamed(tester, "Ausflug");

        expect(find.text(refusal), findsOneWidget);
      });

      expect(
        requests.where((r) => r.url.queryParameters["action"] == "move"),
        isEmpty,
        reason: "no album, nothing to move into",
      );
    });

    testWidgets('an album that was created keeps its place when the move fails',
        (tester) async {
      const refusal = "You may only move photos you contributed yourself.";
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) {
          if (request.method == "PUT") {
            return json('{"path":"2002-03-03 Ausflug","message":""}');
          }
          if (request.url.queryParameters["action"] == "move") {
            return json('["ErrorInfo",{"message":"$refusal"}]', status: 403);
          }
          return treeAnswer(request);
        },
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);
        await createAlbumNamed(tester, "Ausflug");

        // Both halves of the truth: why nothing moved, and that the album is
        // there and empty.
        expect(find.textContaining(refusal), findsOneWidget);
        expect(
          find.textContaining(
              "The new album '2002-03-03 Ausflug' was created and is empty."),
          findsOneWidget,
        );
      });
    });

    testWidgets('it is not offered on an album', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);
        expect(find.byKey(const Key("picker-create-album")), findsOneWidget);

        // An album holds no albums.
        await enterFolder(tester, datedAlbumName);
        expect(find.byKey(const Key("picker-create-album")), findsNothing);
      });
    });

    testWidgets('it is not offered for a folder that moves', (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(treeAnswer, requests);

      await withFakeImageHttp(() async {
        // A folder does not move into an album that is created for it.
        await openEntryPicker(tester, client);
        expect(find.byKey(const Key("picker-create-album")), findsNothing);
      });
    });

    testWidgets('it is not offered where the caller may not add anything',
        (tester) async {
      var requests = <http.Request>[];
      var client = recordingClient(
        (request) => pathOf(request) == "/valbum/data/"
            ? json(root.replaceAll(
                '"rights": [{"name": "edit"}]', '"rights": [{"name": "view"}]'))
            : treeAnswer(request),
        requests,
      );

      await withFakeImageHttp(() async {
        await pumpSelection(tester, client);
        await openPicker(tester);
        expect(find.byKey(const Key("picker-create-album")), findsNothing);
      });
    });
  });
}
