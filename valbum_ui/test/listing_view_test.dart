/// Tests for [ListingView]: the index picture crop, the subtitle line and the
/// home/up navigation.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// Pumps the app showing the resource at [path].
Future<void> pumpListing(
  WidgetTester tester,
  VAlbumClient client, {
  List<String> path = const [],
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      VAlbumApp(client: client, initialRoute: ListingOrAlbumRoute(path)),
    );
    await tester.pumpAndSettle();
  });
}

/// The transform applied to the index picture of the tile showing [image].
Matrix4 transformOf(WidgetTester tester, String image) {
  var picture = find.byWidgetPredicate(
    (widget) =>
        widget is Image &&
        widget.image is ThumbnailImage &&
        (widget.image as ThumbnailImage).url.contains(image),
  );
  expect(picture, findsOneWidget);
  return tester
      .widget<Transform>(
        find.ancestor(of: picture, matching: find.byType(Transform)).first,
      )
      .transform;
}

void main() {
  group('thumbnailTransform', () {
    test('is the identity for an untransformed picture', () {
      var info = ThumbnailInfo(image: "x.jpg", scale: 1, tx: 0, ty: 0);
      expect(thumbnailTransform(info, 200), Matrix4.identity());
    });

    test('reads a missing scale as "no zoom"', () {
      // `scale` defaults to 0.0 in the generated model; a picture without one
      // must not collapse to a point.
      expect(thumbnailTransform(ThumbnailInfo(image: "x.jpg"), 200),
          Matrix4.identity());
    });

    test('aligns the top of a 3:4 portrait picture with the top of the tile',
        () {
      // What the server computes for a 3:4 picture: scale = h / w and
      // ty = (h - w) / h * 150.
      var info = ThumbnailInfo(image: "x.jpg", scale: 4 / 3, ty: 37.5);

      var matrix = thumbnailTransform(info, 200);

      // Fitted into the 200px box the picture is 150 x 200; scaled by 4/3 it
      // is 200 x 266.67, i.e. it overflows the box by 66.67px, half of it
      // above the box. Shifting it down by that half aligns its top edge with
      // the top of the tile.
      expect(matrix.getTranslation().y, closeTo(200 * (4 / 3 - 1) / 2, 1e-9));
      expect(matrix.getTranslation().y, closeTo(33.3333333, 1e-6));
      expect(matrix.getTranslation().x, 0);
      expect(matrix.storage[0], closeTo(4 / 3, 1e-9));
      expect(matrix.storage[5], closeTo(4 / 3, 1e-9));

      // The centre of the box maps to the shifted centre: the transform is a
      // pure scale about the centre plus that shift.
      var centre = matrix.transformed3(Matrix4.zero().getTranslation());
      expect(centre.x, 0);
      expect(centre.y, closeTo(matrix.getTranslation().y, 1e-9));
    });

    test('scales the translation with the tile size', () {
      var info = ThumbnailInfo(image: "x.jpg", scale: 4 / 3, tx: 12, ty: 37.5);

      var small = thumbnailTransform(info, 150);
      var large = thumbnailTransform(info, 600);

      expect(large.getTranslation().x,
          closeTo(4 * small.getTranslation().x, 1e-9));
      expect(large.getTranslation().y,
          closeTo(4 * small.getTranslation().y, 1e-9));
      // The 300px preview of the GWT client is the reference: at that size the
      // offsets are used verbatim (before the scale).
      expect(
        thumbnailTransform(info, cssPreviewSize).getTranslation().y,
        closeTo(4 / 3 * 37.5, 1e-9),
      );
    });

    test('applies the translation before the scale, as CSS does', () {
      var info = ThumbnailInfo(image: "x.jpg", scale: 2, tx: 30, ty: 0);

      // scale(2) translate(30px, 0) about the centre moves a point by 60px,
      // not by 30px.
      expect(
        thumbnailTransform(info, cssPreviewSize).getTranslation().x,
        closeTo(60, 1e-9),
      );
    });
  });

  group('ListingView', () {
    testWidgets('crops the index picture into a square tile', (tester) async {
      var client = clientReturning(fixture("listing_sub.json"));
      await pumpListing(tester, client, path: const ["sub"]);

      var clip = find.ancestor(
        of: find.byWidgetPredicate(
          (widget) =>
              widget is Image &&
              widget.image is ThumbnailImage &&
              (widget.image as ThumbnailImage).url.contains("P3031379.JPG"),
        ),
        matching: find.byType(ClipRect),
      );
      // The innermost ClipRect is the preview area (the scroll view brings one
      // of its own).
      expect(clip, findsWidgets);

      // The preview area is square.
      var size = tester.getSize(clip.first);
      expect(size.width, size.height);

      var info = ThumbnailInfo(image: "P3031379.JPG", scale: 4 / 3, ty: 37.5);
      expect(
        transformOf(tester, "P3031379.JPG"),
        thumbnailTransform(info, size.width),
      );
      // ... which is the top-aligned crop of the portrait picture.
      expect(
        transformOf(tester, "P3031379.JPG").getTranslation().y,
        closeTo(size.width * (4 / 3 - 1) / 2, 1e-9),
      );
    });

    testWidgets('leaves an unscaled picture untransformed', (tester) async {
      var client = clientReturning(fixture("listing_sub.json"));
      await pumpListing(tester, client, path: const ["sub"]);

      expect(transformOf(tester, "landscape.JPG"), Matrix4.identity());
    });

    testWidgets('shows a folder icon without an index picture', (tester) async {
      var client = clientReturning(fixture("listing_sub.json"));
      await pumpListing(tester, client, path: const ["sub"]);

      expect(find.text('No picture'), findsOneWidget);
      // Two tiles have an index picture, the third one has none.
      expect(find.byType(Image), findsNWidgets(2));
      expect(find.byIcon(Icons.folder), findsOneWidget);
    });

    testWidgets('shows the subtitle only where there is one', (tester) async {
      var client = clientReturning(fixture("listing_sub.json"));
      await pumpListing(tester, client, path: const ["sub"]);

      // Present.
      expect(find.text('March 3, 2002'), findsOneWidget);
      // Empty and absent: the title is there, but no (empty) subtitle line
      // below it.
      expect(find.text('Empty subtitle'), findsOneWidget);
      expect(find.text('No picture'), findsOneWidget);
      expect(find.text(''), findsNothing);

      // The subtitle is smaller than the title.
      var title = tester.widget<Text>(find.text('Portrait album'));
      var subTitle = tester.widget<Text>(find.text('March 3, 2002'));
      expect(subTitle.style!.fontSize! < (title.style?.fontSize ?? 14), isTrue);
      expect(title.style!.fontWeight, FontWeight.bold);
    });

    testWidgets('offers home but no up action at the root', (tester) async {
      var client = clientReturning(fixture("listing.json"));
      await pumpListing(tester, client);

      expect(find.byIcon(Icons.home), findsOneWidget);
      expect(find.byIcon(Icons.arrow_upward), findsNothing);
    });

    testWidgets('offers home and up below the root', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(
        fixture("listing_sub.json"),
        requests: requests,
      );
      await pumpListing(tester, client, path: const ["sub"]);

      expect(find.byIcon(Icons.home), findsOneWidget);
      expect(find.byIcon(Icons.arrow_upward), findsOneWidget);
      expect(requests.single.url.toString(),
          "http://server/valbum/data/sub/?type=json");
    });

    testWidgets('up loads the parent listing', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(
        fixture("listing_sub.json"),
        requests: requests,
      );
      await pumpListing(tester, client, path: const ["a", "b"]);

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.arrow_upward));
        await tester.pumpAndSettle();
      });

      expect(requests.last.url.toString(),
          "http://server/valbum/data/a/?type=json");
    });

    testWidgets('up returns to the listing it was reached from',
        (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(
        fixture("listing_sub.json"),
        requests: requests,
      );
      await pumpListing(tester, client);

      // Descend into a folder, then go up again: the parent is popped off the
      // navigation stack instead of being re-loaded.
      await withFakeImageHttp(() async {
        await tester.tap(find.text('Portrait album'));
        await tester.pumpAndSettle();
      });
      expect(requests.last.url.toString(),
          "http://server/valbum/data/portrait/?type=json");

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.arrow_upward));
        await tester.pumpAndSettle();
      });
      expect(requests, hasLength(2));
    });

    testWidgets('home loads the root listing', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(
        fixture("listing_sub.json"),
        requests: requests,
      );
      await pumpListing(tester, client, path: const ["a", "b"]);

      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.home));
        await tester.pumpAndSettle();
      });

      expect(
          requests.last.url.toString(), "http://server/valbum/data/?type=json");
    });
  });

  group('the create dialogs', () {
    /// Opens the given entry of the listing menu.
    Future<void> openMenu(WidgetTester tester, String entry) async {
      await withFakeImageHttp(() async {
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        await tester.tap(find.text(entry));
        await tester.pumpAndSettle();
      });
    }

    testWidgets('the new album is left by its cancel button', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("listing.json"), requests: requests);
      await pumpListing(tester, client);

      await openMenu(tester, "Create album");
      expect(find.text("Neues Album"), findsOneWidget);

      await withFakeImageHttp(() async {
        await tester.tap(find.text("Abbrechen"));
        await tester.pumpAndSettle();
      });

      expect(find.text("Neues Album"), findsNothing);
      expect(
        requests.where((request) => request.method != "GET"),
        isEmpty,
        reason: "a cancelled dialog creates nothing",
      );
    });

    testWidgets('the new folder is left by its cancel button', (tester) async {
      var requests = <http.Request>[];
      var client = clientReturning(fixture("listing.json"), requests: requests);
      await pumpListing(tester, client);

      await openMenu(tester, "Create folder");
      expect(find.text("Neuer Ordner"), findsOneWidget);

      await withFakeImageHttp(() async {
        await tester.tap(find.text("Abbrechen"));
        await tester.pumpAndSettle();
      });

      expect(find.text("Neuer Ordner"), findsNothing);
      expect(requests.where((request) => request.method != "GET"), isEmpty);
    });

    testWidgets('the escape key closes the new folder dialog', (tester) async {
      var client = clientReturning(fixture("listing.json"));
      await pumpListing(tester, client);

      await openMenu(tester, "Create folder");
      expect(find.text("Neuer Ordner"), findsOneWidget);

      await withFakeImageHttp(() async {
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pumpAndSettle();
      });

      expect(find.text("Neuer Ordner"), findsNothing);
    });
  });
}
