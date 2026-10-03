/// The labels of an album on the screen (issue #213): the members' chips that
/// filter the grid, "Label…" on a selection in the edit mode, the rename and
/// removal of a label on its chip, no chips in a share session, and the row
/// of the share dialog that makes a link showing one label.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/label_dialog.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'move_test.dart' show json, pathOf, tile;
import 'share_album_chrome_test.dart' as session;
import 'share_link_test.dart'
    show bodyOf, chooseFrom, ownerAnswers, ownerClient, tapKey;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

ImagePart _photo(String name, [List<String> labels = const []]) => ImagePart(
      kind: ImageKind.image,
      name: name,
      date: 1015113600000,
      width: 2048,
      height: 1536,
      labels: [for (var label in labels) LabelName(name: label)],
    );

/// Two days, a subsection in the first, `Day` on two photos and `Party` on
/// one — the subsection holding no photo of `Day`.
AlbumInfo _album() => AlbumInfo(
      title: "Holiday",
      rights: [
        for (var right in const ["view", "download", "contribute", "edit"])
          RightName(name: right),
      ],
      parts: [
        Heading(text: "Day 1", level: 1),
        _photo("a.jpg", ["Day"]),
        _photo("b.jpg"),
        Heading(text: "Morning", level: 2),
        _photo("c.jpg", ["Party"]),
        Heading(text: "Day 2", level: 1),
        _photo("d.jpg", ["Day"]),
      ],
    );

/// The server of the album `Holiday`, which remembers what was written.
class FakeLabelServer {
  AlbumInfo album = _album();

  final List<http.Request> requests = [];

  VAlbumClient client() => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async {
          requests.add(request);
          if (isThumbnailRequest(request)) {
            return http.Response.bytes(transparentPixelPng, 200,
                headers: {"content-type": "image/png"});
          }
          var path = pathOf(request);
          if (path != "/valbum/data/Holiday/") {
            return http.Response("No such resource: $path", 404);
          }
          if (request.method == "POST" &&
              request.url.queryParameters["action"] == "relabel") {
            var change =
                LabelChange.read(JsonReader.fromString(request.body));
            for (var part in album.parts) {
              if (part is ImagePart) {
                var labels = <LabelName>[];
                for (var name in part.labels) {
                  if (name.name != change.from) {
                    labels.add(name);
                  } else if (change.to.isNotEmpty) {
                    labels.add(LabelName(name: change.to));
                  }
                }
                part.labels = labels;
              }
            }
            return json(album.toString());
          }
          if (request.method == "PUT") {
            album =
                Resource.read(JsonReader.fromString(request.body)) as AlbumInfo;
            return json('{"path": "Holiday", "message": ""}');
          }
          return json(album.toString());
        }),
      );
}

Future<void> pumpAlbum(WidgetTester tester, FakeLabelServer server) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: server.client(),
      initialRoute: const ListingOrAlbumRoute(["Holiday"]),
    ));
    await tester.pumpAndSettle();
  });
}

Finder chip(String label) => find.byKey(Key("label-chip-$label"));

String countOf(WidgetTester tester, String label) =>
    tester.widget<Text>(find.byKey(Key("label-count-$label"))).data!;

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).first);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets("the chips count their photos and filter the grid",
      (tester) async {
    var server = FakeLabelServer();
    await pumpAlbum(tester, server);

    expect(chip("Day"), findsOneWidget);
    expect(chip("Party"), findsOneWidget);
    expect(countOf(tester, "Day"), "2");
    expect(countOf(tester, "Party"), "1");
    for (var name in ["a.jpg", "b.jpg", "c.jpg", "d.jpg"]) {
      expect(tile(name), findsOneWidget);
    }
    expect(find.text("Morning"), findsOneWidget);

    await tester.tap(chip("Day"));
    await tester.pumpAndSettle();
    expect(tile("a.jpg"), findsOneWidget);
    expect(tile("d.jpg"), findsOneWidget);
    expect(tile("b.jpg"), findsNothing);
    expect(tile("c.jpg"), findsNothing);
    expect(find.text("Day 1"), findsOneWidget);
    expect(find.text("Day 2"), findsOneWidget);
    expect(find.text("Morning"), findsNothing,
        reason: "a subsection showing nothing of the label drops");

    await tester.tap(chip("Party"));
    await tester.pumpAndSettle();
    expect(tile("c.jpg"), findsOneWidget);
    expect(tile("a.jpg"), findsNothing);
    expect(find.text("Morning"), findsOneWidget);
    expect(find.text("Day 1"), findsOneWidget);
    expect(find.text("Day 2"), findsNothing);

    // A second tap shows everything again.
    await tester.tap(chip("Party"));
    await tester.pumpAndSettle();
    expect(tile("b.jpg"), findsOneWidget);
    expect(find.text("Day 2"), findsOneWidget);
    expect(server.requests.where((r) => r.method != "GET"), isEmpty,
        reason: "a filter is a view, nothing is written");
  });

  testWidgets("an unfiltered view keeps an empty heading, a filtered one not",
      (tester) async {
    var server = FakeLabelServer();
    server.album.parts = [
      ...server.album.parts,
      Heading(text: "Later", level: 1),
    ];
    await pumpAlbum(tester, server);
    expect(find.text("Later"), findsOneWidget);

    await tester.tap(chip("Day"));
    await tester.pumpAndSettle();
    expect(find.text("Later"), findsNothing);
  });

  testWidgets("an album without labels shows no chips", (tester) async {
    var server = FakeLabelServer();
    for (var part in server.album.parts) {
      if (part is ImagePart) {
        part.labels = const [];
      }
    }
    await pumpAlbum(tester, server);
    expect(find.byKey(const Key("label-chip-Day")), findsNothing);
    expect(find.byType(FilterChip), findsNothing);
  });

  testWidgets("a share session shows no chips", (tester) async {
    var labeled = _album().toString();
    await session.pumpSession(tester, (request) {
      if (request.url.queryParameters["type"] == "auth") {
        return session.json(session.authOfLink());
      }
      return session.json(labeled);
    });
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(tile("a.jpg"), findsOneWidget);
    expect(find.byType(FilterChip), findsNothing);
  });

  testWidgets("Label… gives the selection labels and takes them off",
      (tester) async {
    var server = FakeLabelServer();
    await pumpAlbum(tester, server);

    // Into the edit mode on b.jpg, then a.jpg too.
    await tester.longPress(tile("b.jpg"));
    await tester.pumpAndSettle();
    expect(chip("Day"), findsNothing, reason: "the edit mode is unfiltered");
    await tester.sendKeyDownEvent(LogicalKeyboardKey.control);
    await tester.tap(tile("a.jpg"));
    await tester.sendKeyUpEvent(LogicalKeyboardKey.control);
    await tester.pumpAndSettle();

    await openMenu(tester);
    await tester.tap(find.byKey(const Key("label-selection")));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("label-dialog")), findsOneWidget);

    // a.jpg carries Day, b.jpg not: half.
    var day = tester.widget<CheckboxListTile>(
        find.byKey(const Key("label-option-Day")));
    expect(day.value, isNull);
    expect(
        tester
            .widget<CheckboxListTile>(
                find.byKey(const Key("label-option-Party")))
            .value,
        isFalse);

    await tester.tap(find.byKey(const Key("label-option-Day")));
    await tester.enterText(find.byKey(const Key("label-new")), " Anna ");
    await tester.tap(find.byKey(const Key("label-new-add")));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<CheckboxListTile>(
                find.byKey(const Key("label-option-Anna")))
            .value,
        isTrue);
    await tester.tap(find.byKey(const Key("label-apply")));
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.save));
    await tester.pumpAndSettle();
    var put = server.requests.lastWhere((r) => r.method == "PUT");
    var stored = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    List<String> labelsOf(String name) => [
          for (var part in stored.parts)
            if (part is ImagePart && part.name == name)
              for (var label in part.labels) label.name,
        ];
    expect(labelsOf("a.jpg"), ["Day", "Anna"]);
    expect(labelsOf("b.jpg"), ["Day", "Anna"]);
    expect(labelsOf("c.jpg"), ["Party"], reason: "not selected");

    // Taking one off again.
    await tester.longPress(tile("a.jpg"));
    await tester.pumpAndSettle();
    await openMenu(tester);
    await tester.tap(find.byKey(const Key("label-selection")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("label-option-Anna")));
    await tester.tap(find.byKey(const Key("label-apply")));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.save));
    await tester.pumpAndSettle();
    put = server.requests.lastWhere((r) => r.method == "PUT");
    stored = Resource.read(JsonReader.fromString(put.body)) as AlbumInfo;
    expect(labelsOf("a.jpg"), ["Day"]);
    expect(labelsOf("b.jpg"), ["Day", "Anna"]);
  });

  testWidgets("a chip renames its label across the album, at once",
      (tester) async {
    var server = FakeLabelServer();
    await pumpAlbum(tester, server);
    await tester.tap(chip("Day"));
    await tester.pumpAndSettle();

    await tester.longPress(chip("Day"));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("label-rename")));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key("label-rename-field")), "Day with Anna");
    await tester.tap(find.byKey(const Key("label-rename-confirm")));
    await tester.pumpAndSettle();

    var post = server.requests.lastWhere((r) => r.method == "POST");
    expect(post.url.queryParameters["action"], "relabel");
    expect(post.body, contains('"from":"Day"'));
    expect(post.body, contains('"to":"Day with Anna"'));
    expect(chip("Day with Anna"), findsOneWidget);
    expect(chip("Day"), findsNothing);
    expect(countOf(tester, "Day with Anna"), "2");
    expect(tile("b.jpg"), findsNothing,
        reason: "the filter follows the rename");
  });

  testWidgets("a chip removes its label after asking", (tester) async {
    var server = FakeLabelServer();
    await pumpAlbum(tester, server);

    await tester.longPress(chip("Party"));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key("label-delete")));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key("label-delete-dialog")), findsOneWidget);
    await tester.tap(find.byKey(const Key("label-delete-confirm")));
    await tester.pumpAndSettle();

    var post = server.requests.lastWhere((r) => r.method == "POST");
    expect(post.body, contains('"from":"Party"'));
    expect(chip("Party"), findsNothing);
    expect(chip("Day"), findsOneWidget);
  });

  germanLabelDialog();

  group("the share dialog", () {
    Future<void> pumpDialog(
      WidgetTester tester,
      List<http.Request> requests,
      List<String> labels,
    ) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: Scaffold(
          body: ShareLinkDialog(
            client: ownerClient(ownerAnswers, requests: requests),
            path: const ["2024", "Zoo"],
            photoLabels: labels,
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets("offers a label of the album and sends it", (tester) async {
      var requests = <http.Request>[];
      await pumpDialog(tester, requests, const ["Day", "Party"]);
      await tapKey(tester, "new-link");
      expect(find.byKey(const Key("link-photo-label")), findsOneWidget);
      expect(find.text(testL10n.labelFilterWholeAlbum), findsOneWidget);

      await chooseFrom(
          tester, "link-photo-label", testL10n.labelFilterOnly("Day"));
      await tapKey(tester, "link-create");
      expect(bodyOf(requests, "share"), contains('"photoLabel":"Day"'));
    });

    testWidgets("sends the whole album by default", (tester) async {
      var requests = <http.Request>[];
      await pumpDialog(tester, requests, const ["Day"]);
      await tapKey(tester, "new-link");
      await tapKey(tester, "link-create");
      expect(bodyOf(requests, "share"), contains('"photoLabel":""'));
    });

    testWidgets("asks nothing where the album has no labels", (tester) async {
      await pumpDialog(tester, <http.Request>[], const []);
      await tapKey(tester, "new-link");
      expect(find.byKey(const Key("link-photo-label")), findsNothing);
    });
  });
}

/// The German smoke test of the label dialog (issue #108's rule for a new
/// screen): the term is "Label" throughout.
void germanLabelDialog() {
  testWidgets("the label dialog speaks German", (tester) async {
    await tester.pumpWidget(localizedApp(
      Scaffold(
        body: LabelDialog(
          labels: const ["Tag"],
          parts: [_photo("a.jpg", ["Tag"]), _photo("b.jpg")],
        ),
      ),
      locale: const Locale("de"),
    ));
    await tester.pumpAndSettle();
    var de = l10nOf(const Locale("de"));
    expect(find.text(de.labelDialogTitle(2)), findsOneWidget);
    expect(de.labelDialogTitle(2), "Labels von 2 Fotos");
    expect(find.text(de.labelApply), findsOneWidget);
  });
}
