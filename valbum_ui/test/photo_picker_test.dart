/// Tests of the in-app photo picker (issue #64).
///
/// The system picker of Android 13+ hands out at most 100 items, which is not
/// enough for the album this app was written for. The picker tested here reads
/// the phone's own library through the [PhotoLibrary] abstraction — a fake one
/// in these tests — and hands the album whatever the user selected, however
/// many that is.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/photo_library.dart';
import 'package:valbum_ui/photo_picker_view.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// A photo of the fake library, its contents a 1x1 PNG so that the tile of the
/// picker really decodes a picture.
PhotoItem photo(String name, DateTime takenAt) =>
    fakePhoto(name, transparentPixelPng, takenAt: takenAt);

/// A library holding two albums, one of them with photos from two months.
FakePhotoLibrary twoAlbums() {
  var library = FakePhotoLibrary();
  library.addAlbum("Camera", [
    photo("march-1.jpg", DateTime(2024, 3, 4)),
    photo("march-2.jpg", DateTime(2024, 3, 20)),
    photo("april-1.jpg", DateTime(2024, 4, 2)),
  ]);
  library.addAlbum("Screenshots", [
    photo("shot-1.png", DateTime(2023, 12, 24)),
  ]);
  return library;
}

/// Pumps the picker on its own, as a screen of a plain app.
Future<void> pumpPicker(WidgetTester tester, PhotoLibrary library) async {
  await tester.pumpWidget(
    MaterialApp(home: PhotoPickerScreen(library: library)),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('the albums of the device', () {
    testWidgets('are listed with their counts', (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());

      expect(find.byKey(photoAlbumKey("Camera")), findsOneWidget);
      expect(find.byKey(photoAlbumKey("Screenshots")), findsOneWidget);
      expect(find.text("3 Fotos"), findsOneWidget);
      expect(find.text("1 Fotos"), findsOneWidget);
    });

    testWidgets('open on a tap and show their items by month',
        (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());

      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();

      // Newest first, so April stands above March.
      expect(find.byKey(photoMonthKey("2024-04")), findsOneWidget);
      expect(find.byKey(photoMonthKey("2024-03")), findsOneWidget);
      expect(find.text("April 2024 (1)"), findsOneWidget);
      expect(find.text("März 2024 (2)"), findsOneWidget);
      expect(find.byKey(photoItemKey("march-1.jpg")), findsOneWidget);
      expect(find.byKey(photoItemKey("april-1.jpg")), findsOneWidget);
      expect(
        find.byKey(photoItemKey("shot-1.png")),
        findsNothing,
        reason: "another album's photo",
      );
    });

    testWidgets('lead back to the list of albums', (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());

      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip("Alle Alben"));
      await tester.pumpAndSettle();

      expect(find.byKey(photoAlbumKey("Screenshots")), findsOneWidget);
    });
  });

  group('selecting', () {
    testWidgets('a tap toggles one tile', (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();

      expect(find.text("0 ausgewählt"), findsOneWidget);

      await tester.tap(find.byKey(photoItemKey("march-1.jpg")));
      await tester.pumpAndSettle();
      expect(find.text("1 ausgewählt"), findsOneWidget);

      await tester.tap(find.byKey(photoItemKey("march-1.jpg")));
      await tester.pumpAndSettle();
      expect(find.text("0 ausgewählt"), findsOneWidget);
    });

    testWidgets('a month selects exactly its own items',
        (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(photoMonthAllKey("2024-03")));
      await tester.pumpAndSettle();

      expect(find.text("2 ausgewählt"), findsOneWidget);
      expect(find.text("2 Fotos hochladen"), findsOneWidget);

      // And unselects them again, the button having become "Keine".
      await tester.tap(find.byKey(photoMonthAllKey("2024-03")));
      await tester.pumpAndSettle();
      expect(find.text("0 ausgewählt"), findsOneWidget);
    });

    testWidgets('the whole album is one tap', (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(photoPickerAllKey));
      await tester.pumpAndSettle();

      expect(find.text("3 ausgewählt"), findsOneWidget);
    });

    testWidgets('survives a look into another album',
        (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(photoMonthAllKey("2024-04")));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip("Alle Alben"));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(photoAlbumKey("Screenshots")));
      await tester.pumpAndSettle();

      expect(find.text("1 ausgewählt"), findsOneWidget);
    });
  });

  group('the way out', () {
    testWidgets('is disabled while nothing is selected',
        (WidgetTester tester) async {
      await pumpPicker(tester, twoAlbums());

      var button = tester.widget<FilledButton>(
        find.byKey(photoPickerUploadKey),
      );
      expect(button.onPressed, isNull);
      expect(find.text("0 Fotos hochladen"), findsOneWidget);
    });

    testWidgets('answers the selected items', (WidgetTester tester) async {
      var library = twoAlbums();
      List<PhotoItem>? picked;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () async =>
                    picked = await pickFromLibrary(context, library),
                child: const Text("pick"),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text("pick"));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(photoMonthAllKey("2024-03")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(photoPickerUploadKey));
      await tester.pumpAndSettle();

      expect(picked, isNotNull);
      expect(
        [for (var item in picked!) item.name],
        // In the order the grid shows them: newest first.
        ["march-2.jpg", "march-1.jpg"],
      );
      expect(find.byKey(photoPickerKey), findsNothing, reason: "screen left");
    });

    testWidgets('answers nothing when the screen is left',
        (WidgetTester tester) async {
      var library = twoAlbums();
      List<PhotoItem>? picked;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () async =>
                    picked = await pickFromLibrary(context, library),
                child: const Text("pick"),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text("pick"));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip("Zurück"));
      await tester.pumpAndSettle();

      expect(picked, isEmpty);
    });
  });

  group('a library that refuses', () {
    testWidgets('says why, in its own words', (WidgetTester tester) async {
      var library = FakePhotoLibrary(
        granted: false,
        accessProblem: "Access to the photo library was denied.",
      );

      await pumpPicker(tester, library);

      expect(
        find.text("Access to the photo library was denied."),
        findsOneWidget,
      );
      expect(find.byKey(photoPickerProblemKey), findsOneWidget);
    });

    testWidgets('says something even where it gave no reason',
        (WidgetTester tester) async {
      await pumpPicker(tester, FakePhotoLibrary(granted: false));

      expect(find.byKey(photoPickerProblemKey), findsOneWidget);
      expect(
        find.textContaining("Kein Zugriff auf die Fotomediathek"),
        findsOneWidget,
      );
    });

    testWidgets('says so when there is no album at all',
        (WidgetTester tester) async {
      await pumpPicker(tester, FakePhotoLibrary());

      expect(find.byKey(photoPickerProblemKey), findsOneWidget);
    });
  });

  group('the upload menu of an album', () {
    /// A client serving the fixture album.
    VAlbumClient albumClient() => clientHandling(
          (request) => http.Response(fixture("album.json"), 200),
        );

    testWidgets('offers the in-app picker where the device has a library',
        (WidgetTester tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: albumClient(), photoLibrary: twoAlbums()),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.byIcon(Icons.cloud_upload));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(uploadFromLibraryKey), findsOneWidget);
      expect(find.byKey(uploadFromFilesKey), findsOneWidget);
      expect(find.text(photoPickerEntry), findsOneWidget);
      expect(find.text(systemPickerEntry), findsOneWidget);
    });

    testWidgets('opens the picker on the library entry',
        (WidgetTester tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: albumClient(), photoLibrary: twoAlbums()),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.byIcon(Icons.cloud_upload));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(uploadFromLibraryKey));
        await tester.pumpAndSettle();
      });

      expect(find.byKey(photoPickerKey), findsOneWidget);
      expect(find.byKey(photoAlbumKey("Camera")), findsOneWidget);
    });

    testWidgets('offers nothing to choose where there is no library',
        (WidgetTester tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(
            client: albumClient(),
            photoLibrary: const UnavailablePhotoLibrary(),
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.byIcon(Icons.cloud_upload));
        await tester.pump();
      });

      // Straight to the system picker: a menu of one entry is worse than none.
      expect(find.byKey(uploadFromLibraryKey), findsNothing);
      expect(find.byKey(uploadFromFilesKey), findsNothing);
      expect(find.byKey(photoPickerKey), findsNothing);
    });
  });

  group('on a narrow phone', () {
    testWidgets('lays out at 400 px without overflowing',
        (WidgetTester tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      await pumpPicker(tester, twoAlbums());
      await tester.tap(find.byKey(photoAlbumKey("Camera")));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(photoPickerAllKey));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text("3 ausgewählt"), findsOneWidget);
      expect(find.text("3 Fotos hochladen"), findsOneWidget);
    });
  });
}
