/// Tests of issue #307, package 2: a photo other albums still show is never
/// thrown away. Before a delete the app asks the server what it would keep
/// (`?action=delete-check`) and names those albums in the question; after the
/// delete it says where the photos went. Where nothing is kept, the question
/// is the one asked before.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/kept_photos.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'delete_test.dart' show longPressFolder, confirmDelete;
import 'move_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// One kept photo, as the server names it.
String keptPhoto(String photo, String album, String name) =>
    '{"photo":"$photo","album":"$album","title":"$album","name":"$name"}';

/// The answer to a delete of the album "2020 Trip" keeping [kept].
String tripDeleted(List<String> kept, {String newName = "2020 Trip"}) =>
    '{"outcomes":[{"name":"2020 Trip","newName":"$newName","message":'
    '"Moved to the trash of the space (\'.valbum/trash\') as \'$newName\'.",'
    '"kept":[${kept.join(",")}]}]}';

/// Opens "Delete…" of the listing tile "2020 Trip", the server answering the
/// question with [check] and the delete with [delete].
Future<List<http.Request>> openDelete(
  WidgetTester tester, {
  required String check,
  String? delete,
}) async {
  var requests = <http.Request>[];
  var client = recordingClient(
    (request) => request.method != "POST"
        ? treeAnswer(request)
        : request.url.query.contains("action=delete-check")
            ? json(check)
            : json(delete ?? check),
    requests,
  );
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await longPressFolder(tester, "2020 Trip");
  await tester.tap(find.text("Delete…"));
  await tester.pumpAndSettle();
  return requests;
}

void main() {
  var trip = keptPhoto("2020 Trip/b.jpg", "Best of 2025", "b.jpg");

  group('the question before a delete', () {
    testWidgets('names the one album that keeps photos', (tester) async {
      await withFakeImageHttp(() async {
        var requests = await openDelete(tester,
            check: tripDeleted([
              trip,
              keptPhoto("2020 Trip/c.jpg", "Best of 2025", "c.jpg"),
              keptPhoto("2020 Trip/d.jpg", "Best of 2025", "d.jpg"),
            ], newName: ""));

        var question = testL10n.keptQuestion("‘Best of 2025’", 3);
        expect(
            question,
            "3 photos are still shown in ‘Best of 2025’ – they stay there, "
            "all others go to the trash.");
        expect(find.text("${testL10n.deleteExplanation}\n\n$question"),
            findsOneWidget);
        var check = requests.singleWhere((r) => r.method == "POST");
        expect(check.url.query, "action=delete-check");
        expect(check.body, '{"target":"","names":[{"name":"2020 Trip"}]}');

        await tester.tap(find.text("Cancel"));
        await tester.pumpAndSettle();
        expect(
            requests.where(
                (r) => r.method == "POST" && r.url.query == "action=delete"),
            isEmpty);
      });
    });

    testWidgets('names several albums, each once', (tester) async {
      await withFakeImageHttp(() async {
        await openDelete(tester,
            check: tripDeleted([
              trip,
              keptPhoto("2020 Trip/c.jpg", "Summer", "c.jpg"),
              keptPhoto("2020 Trip/d.jpg", "Best of 2025", "d.jpg"),
            ], newName: ""));

        expect(
          find.textContaining(
              "3 photos are still shown in ‘Best of 2025’, ‘Summer’ – they "
              "stay there, all others go to the trash."),
          findsOneWidget,
        );
      });
    });

    testWidgets('is the one asked before where nothing is kept',
        (tester) async {
      await withFakeImageHttp(() async {
        await openDelete(tester, check: tripDeleted([], newName: ""));

        expect(find.byKey(const Key("delete-dialog")), findsOneWidget);
        expect(find.text("Delete '2020 Trip'?"), findsOneWidget);
        expect(find.text(testL10n.deleteExplanation), findsOneWidget);
        expect(find.textContaining("still shown"), findsNothing);
      });
    });

    testWidgets('is the one asked before where the server cannot be asked',
        (tester) async {
      await withFakeImageHttp(() async {
        var requests = <http.Request>[];
        var client = recordingClient(
          (request) => request.method != "POST"
              ? treeAnswer(request)
              : http.Response(
                  '["ErrorInfo",{"message":"Unknown action."}]', 400,
                  headers: {"content-type": "application/json"}),
          requests,
        );
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await longPressFolder(tester, "2020 Trip");
        await tester.tap(find.text("Delete…"));
        await tester.pumpAndSettle();

        expect(find.text(testL10n.deleteExplanation), findsOneWidget);
      });
    });
  });

  group('after the delete', () {
    testWidgets('says where the kept photos went', (tester) async {
      await withFakeImageHttp(() async {
        await openDelete(
          tester,
          check: tripDeleted([trip], newName: ""),
          delete: tripDeleted([trip]),
        );
        await confirmDelete(tester);

        expect(
          find.textContaining(
              "1 photo is still shown in ‘Best of 2025’ and was moved there "
              "instead of into the trash."),
          findsOneWidget,
        );
        // What happened to the album itself is said too, in the server's words.
        expect(find.textContaining("Moved to the trash of the space"),
            findsOneWidget);
      });
    });
  });

  group('the sentences', () {
    test('every photo kept is said so, and nothing for none', () {
      var kept = [
        KeptPhoto(photo: "b.jpg", album: "Trip", title: "Trip", name: "b.jpg"),
      ];
      expect(
          keptQuestion(testL10n, kept, 1),
          "1 photo is still shown in ‘Trip’ – it stays there instead of going "
          "to the trash.");
      expect(
          keptQuestion(testL10n, kept, 2),
          "1 photo is still shown in ‘Trip’ – it stays there, all others go "
          "to the trash.");
      expect(keptQuestion(testL10n, const [], 2), isNull);
      expect(keptReport(testL10n, const []), isNull);
      expect(
          keptPurgeReport(testL10n, kept),
          "1 photo is still shown in ‘Trip’ and was moved there instead of "
          "being deleted.");
    });

    test('a photo kept is told from an album some of whose photos were', () {
      var photo = KeptPhoto(
          photo: "b.jpg", album: "Trip", title: "Trip", name: "b.jpg");
      expect(isKeptPhoto(MoveOutcome(name: "b.jpg", kept: [photo])), isTrue);
      expect(
          isKeptPhoto(MoveOutcome(name: "Other", kept: [
            KeptPhoto(
                photo: "Other/b.jpg",
                album: "Trip",
                title: "Trip",
                name: "b.jpg")
          ])),
          isFalse);
    });
  });
}
