import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// Parses an album from its JSON representation (including the type tag).
AlbumInfo readAlbum(String json) =>
    Resource.read(JsonReader.fromString(json)) as AlbumInfo;

/// Enters the album edit mode by long-pressing the first image tile.
Future<void> enterEditMode(WidgetTester tester) async {
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

/// A field-wise description of an album, used to compare a saved album with
/// the one that was loaded.
List<String> describe(AlbumInfo album) => [
      "title=${album.title}",
      "subTitle=${album.subTitle}",
      for (var part in album.parts) ...describePart(part),
    ];

List<String> describePart(AlbumPart part) {
  if (part is Heading) {
    return ["Heading(${part.text})"];
  }
  if (part is ImagePart) {
    return [describeImage(part)];
  }
  var group = part as ImageGroup;
  return [
    "Group(representative=${group.representative})",
    ...group.images.map(describeImage),
  ];
}

String describeImage(ImagePart image) => "Image(${image.name}, ${image.kind}, "
    "${image.width}x${image.height}, ${image.orientation}, "
    "rating=${image.rating}, comment='${image.comment}')";

void main() {
  group('saveAlbum', () {
    test('stores an album at the URL of its own folder', () async {
      var requests = <http.Request>[];
      var client = clientReturning("", requests: requests);
      var album = AlbumInfo(title: "T");

      await client.saveAlbum(const [], album);
      await client.saveAlbum(const ["2005-08-24 Blumen und Fliegen"], album);

      expect(requests.map((r) => r.method), ["PUT", "PUT"]);
      expect(requests[0].url.toString(), "http://server/valbum/data/");
      expect(
        requests[1].url.toString(),
        "http://server/valbum/data/2005-08-24%20Blumen%20und%20Fliegen/",
      );
      // The store URL is the JSON URL without its query.
      expect(
        client.folderUrl(const ["a", "b"]),
        client.jsonUrl(const ["a", "b"]).split("?").first,
      );
    });

    test('reports the status of a refused write', () {
      expect(
        () => clientReturning("nope", status: 500)
            .saveAlbum(const [], AlbumInfo()),
        throwsA(
          isA<VAlbumException>().having(
            (e) => e.message,
            'message',
            contains("HTTP 500"),
          ),
        ),
      );
    });
  });

  group('serialisation', () {
    test('does not write the transient fields', () {
      var album = readAlbum(fixture("album.json"));
      AlbumInitializer().init(album);

      var json = album.toString();

      expect(json, isNot(contains('"path"')));
      expect(json, isNot(contains('"imageByName"')));
      expect(json, isNot(contains('"minRating"')));
      expect(json, isNot(contains('"owner"')));
      expect(json, isNot(contains('"previous"')));
      expect(json, isNot(contains('"next"')));
      expect(json, isNot(contains('"group"')));

      // What is written parses back into an equal album.
      var reread = readAlbum(json);
      expect(describe(reread), describe(album));
    });
  });

  group('album edit mode', () {
    testWidgets('saves the edited album back to its own URL', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("album.json"), requests: requests);

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        await enterEditMode(tester);

        // The edit mode shows the save and the properties action.
        expect(find.byIcon(Icons.save), findsOneWidget);
        expect(find.byIcon(Icons.tune), findsOneWidget);

        // Change the title through the album properties editor.
        await tester.tap(find.byIcon(Icons.tune));
        await tester.pumpAndSettle();
        expect(find.text("Titel"), findsOneWidget);
        expect(find.text("Subtitel"), findsOneWidget);
        await tester.enterText(find.byType(TextField).first, "Neu");
        await tester.tap(find.text("Übernehmen"));
        await tester.pumpAndSettle();

        // The header shows the new title.
        expect(find.text("Neu"), findsOneWidget);
        expect(find.text("Schlosspark Karlsruhe"), findsNothing);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      var puts = requests.where((r) => r.method == "PUT").toList();
      expect(puts, hasLength(1));

      var put = puts.single;
      expect(put.url.toString(), "http://server/valbum/data/");
      expect(put.headers["content-type"], startsWith("application/json"));

      var saved = Resource.read(JsonReader.fromString(put.body));
      expect(saved, isA<AlbumInfo>());

      var expected = readAlbum(fixture("album.json"));
      expected.title = "Neu";
      expect(describe(saved as AlbumInfo), describe(expected));

      // The album was re-loaded after the save, so the transient links are
      // rebuilt from what the server has.
      expect(requests.last.method, "GET");
      expect(
        requests.last.url.toString(),
        "http://server/valbum/data/?type=json",
      );

      // The edit mode was left.
      expect(find.byIcon(Icons.save), findsNothing);
    });

    testWidgets('a refused save keeps the edit mode and shows the status',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientHandling(
        (request) => request.method == "PUT"
            ? http.Response("no", 500)
            : http.Response(fixture("album.json"), 200),
        requests: requests,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();

        await enterEditMode(tester);

        await tester.tap(find.byIcon(Icons.save));
        await tester.pumpAndSettle();
      });

      expect(find.textContaining("500"), findsOneWidget);

      // Still in edit mode, and no reload happened.
      expect(find.byIcon(Icons.save), findsOneWidget);
      expect(requests.where((r) => r.method == "GET"), hasLength(1));
    });
  });
}
