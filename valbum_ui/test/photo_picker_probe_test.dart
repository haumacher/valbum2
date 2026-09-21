/// Probe of issues #63 and #64: batching, the partial-failure report, the
/// wakelock and the diagnostics log composed in one upload, and the picker's
/// selection composed across months and albums.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/photo_library.dart';
import 'package:valbum_ui/photo_picker_view.dart';
import 'package:valbum_ui/wakelock.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

UploadFile sizedFile(String name, int size) => UploadFile(
      name: name,
      length: size,
      openRead: () => Stream.value(List<int>.filled(size, 3)),
    );

String storedAnswer(Iterable<String> names) =>
    '{"files":[${names.map((n) => '{"name":"$n","storedAs":"$n",'
        '"hash":"h-$n","status":"stored"}').join(",")}]}';

PhotoItem photo(String name, DateTime takenAt) =>
    fakePhoto(name, transparentPixelPng, takenAt: takenAt);

void main() {
  testWidgets(
      'a lost second batch: plain words, the wakelock released, the album '
      'reloaded, both PUTs in the log', (tester) async {
    var wakelock = RecordingWakelock();
    var log = DiagnosticsLog();
    var listings = 0;
    var puts = 0;
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      token: "t",
      log: log,
      httpClient: MockClient(servingThumbnails((request) async {
        if (request.method == "PUT") {
          puts++;
          if (puts == 1) {
            // 25 names, whatever they are: the server stored the first batch.
            var names = List.generate(25, (i) => "p$i.jpg");
            return http.Response(storedAnswer(names), 200);
          }
          throw http.ClientException("Connection reset by peer", request.url);
        }
        if (request.method == "POST") {
          return http.Response('{"present":[]}', 200);
        }
        listings++;
        return http.Response(fixture("album.json"), 200);
      })),
    );

    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: client, wakelock: wakelock, diagnostics: log),
      );
      await tester.pumpAndSettle();
      var before = listings;
      var state = tester.state<VAlbumState>(find.byType(VAlbumView));
      await state.uploadPicked(
        List.generate(30, (i) => sizedFile("p$i.jpg", 1024)),
      );
      await tester.pumpAndSettle();
      expect(listings, greaterThan(before), reason: "what arrived is shown");
    });

    expect(puts, 2);
    var bar = find.byType(SnackBar);
    expect(bar, findsOneWidget);
    var text = tester.widget<Text>(
      find.descendant(of: bar, matching: find.byType(Text)),
    );
    expect(text.data, contains("25 von 30"));
    expect(text.data, contains("5"));
    expect(text.data, isNot(contains("ClientException")));
    expect(wakelock.requests, [true, false]);
    var logged = log.entries.map((e) => e.message).join("\n");
    expect(logged, contains("PUT http://server/valbum/data/"));
    expect(logged.split("\n").where((l) => l.startsWith("PUT")).length, 2);
    expect(logged, contains("!! ClientException"));
    expect(logged, contains("Connection reset by peer"));
  });

  test('the splitter keeps order, isolates a giant, never drops an item', () {
    var lengths = [10, 10, 500, 10, 10, 10];
    var batches = const UploadBatching(maxFiles: 2, maxBytes: 100)
        .split(List.generate(lengths.length, (i) => i), (i) => lengths[i]);
    expect(batches.expand((b) => b).toList(), [0, 1, 2, 3, 4, 5]);
    expect(batches, [
      [0, 1],
      [2],
      [3, 4],
      [5],
    ]);
    expect(const UploadBatching().split(<int>[], (i) => 0), isEmpty);
  });

  test('nothing pending after the check: no PUT, complete summary', () async {
    var puts = 0;
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/data",
      httpClient: MockClient(servingThumbnails((request) async {
        if (request.method == "PUT") {
          puts++;
          return http.Response(storedAnswer(const ["x"]), 200);
        }
        var asked = RegExp(r'"hash":"([0-9a-f]+)"')
            .allMatches(request.body)
            .map((m) => '{"hash":"${m[1]}","name":"old"}')
            .join(",");
        return http.Response('{"present":[$asked]}', 200);
      })),
    );
    var summary = await client.uploadNew(
      const ["album"],
      List.generate(40, (i) => sizedFile("p$i.jpg", 100 + i)),
    );
    expect(puts, 0);
    expect(summary.present, 40);
    expect(summary.remaining, 0);
    expect(summary.complete, isTrue);
  });

  testWidgets('a selection spans months and survives an album switch',
      (tester) async {
    var library = FakePhotoLibrary();
    library.addAlbum("Reise", [
      photo("jan-1.jpg", DateTime(2025, 1, 5)),
      photo("jan-2.jpg", DateTime(2025, 1, 6)),
      photo("feb-1.jpg", DateTime(2025, 2, 1)),
    ]);
    library.addAlbum("Sonstiges", [
      photo("x-1.jpg", DateTime(2024, 6, 1)),
    ]);
    await tester.pumpWidget(
      MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: PhotoPickerScreen(library: library)),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(photoAlbumKey("Reise")));
    await tester.pumpAndSettle();
    // The whole album, then one photo of January taken out again.
    await tester.tap(find.byKey(photoPickerAllKey));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(photoItemKey("jan-2.jpg")));
    await tester.pumpAndSettle();
    expect(find.text(testL10n.photoPickerSelected(2)), findsOneWidget);

    // Into the other album and back: the two are still selected, and the
    // other album's photo adds a third.
    await tester.tap(find.byTooltip(testL10n.allAlbums));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(photoAlbumKey("Sonstiges")));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(photoItemKey("x-1.jpg")));
    await tester.pumpAndSettle();
    expect(find.text(testL10n.photoPickerSelected(3)), findsOneWidget);

    var button = tester.widget<FilledButton>(find.byKey(photoPickerUploadKey));
    expect(button.onPressed, isNotNull);
    expect(find.text(testL10n.photoPickerUpload(3)), findsOneWidget);
  });
}
