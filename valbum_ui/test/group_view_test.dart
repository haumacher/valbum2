import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The tile of the group member with the given file name.
Finder memberTile(String name) => find.byKey(ValueKey("group-tile-$name"));

/// The URL of the single image the viewer shows.
String shownUrl(WidgetTester tester) {
  var image = tester.widget<Image>(find.byType(Image).first);
  return (image.image as NetworkImage).url;
}

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) async {
  await withFakeImageHttp(() async {
    await tester.sendKeyEvent(key);
    await tester.pumpAndSettle();
  });
}

Future<void> tap(WidgetTester tester, Finder finder) async {
  await withFakeImageHttp(() async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  });
}

void main() {
  testWidgets('opens the alternatives of a group and navigates in it',
      (tester) async {
    var client = clientReturning(fixture("album.json"));

    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client));
      await tester.pumpAndSettle();

      // The album shows the group by its representative; tapping it opens the
      // viewer on the group itself.
      await tester.tap(find.byType(Image).at(2));
      await tester.pumpAndSettle();
    });
    expect(shownUrl(tester), endsWith("/group-a.jpg"));

    // The viewer offers the way down into the group.
    expect(find.byIcon(Icons.expand_more), findsOneWidget);
    await tap(tester, find.byIcon(Icons.expand_more));

    // The alternatives view lists all images of the group under the album
    // title.
    expect(find.byType(GroupView), findsOneWidget);
    expect(find.text("Schlosspark Karlsruhe"), findsOneWidget);
    expect(memberTile("group-a.jpg"), findsOneWidget);
    expect(memberTile("group-b.jpg"), findsOneWidget);

    // A member opens in the viewer, navigating within the group.
    await tap(tester, memberTile("group-a.jpg"));
    expect(shownUrl(tester), endsWith("/group-a.jpg"));
    // No way further down from the detail view.
    expect(find.byIcon(Icons.expand_more), findsNothing);

    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), endsWith("/group-b.jpg"));
    // The group is the whole world here: there is no next image.
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), endsWith("/group-b.jpg"));
    await press(tester, LogicalKeyboardKey.home);
    expect(shownUrl(tester), endsWith("/group-a.jpg"));

    // Up returns to the alternatives view, up again to the album.
    await press(tester, LogicalKeyboardKey.arrowUp);
    expect(find.byType(GroupView), findsOneWidget);
    await tap(tester, find.byIcon(Icons.arrow_upward));
    expect(find.byType(GroupView), findsNothing);
    expect(shownUrl(tester), endsWith("/group-a.jpg"));
  });

  testWidgets('shows the alternatives regardless of their rating',
      (tester) async {
    var trashed = ImagePart(
      name: "b.jpg",
      rating: -2,
      width: 2000,
      height: 1000,
    );
    var group = ImageGroup(
      representative: 0,
      images: [ImagePart(name: "a.jpg", width: 2000, height: 1000), trashed],
    );
    AlbumInitializer().init(AlbumInfo(title: "Album", parts: [group]));

    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        MaterialApp(
          home: GroupView(
            client: clientReturning("{}"),
            baseUrl: "http://server/valbum/data/album",
            group: group,
          ),
        ),
      );
      await tester.pumpAndSettle();
    });

    expect(find.text("Album"), findsOneWidget);
    expect(memberTile("a.jpg"), findsOneWidget);
    expect(memberTile("b.jpg"), findsOneWidget);

    // And the viewer reaches the trashed alternative.
    await tap(tester, memberTile("a.jpg"));
    await press(tester, LogicalKeyboardKey.arrowRight);
    expect(shownUrl(tester), endsWith("/b.jpg"));
  });
}
