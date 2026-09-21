import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jsontool/jsontool.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The tile of the group member with the given file name.
Finder memberTile(String name) => find.byKey(ValueKey("group-tile-$name"));

/// The representative mark on the group tile of [name].
Finder representativeMark(String name) => find.descendant(
      of: find.ancestor(of: memberTile(name), matching: find.byType(Stack)),
      matching: find.byKey(const Key("group-representative")),
    );

/// The URL of the single image the viewer shows.
String shownUrl(WidgetTester tester) {
  var image = tester.widget<Image>(find.byKey(const Key("image-picture")));
  return (image.image as NetworkImage).url;
}

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) =>
    settle(tester, () => tester.sendKeyEvent(key));

void main() {
  testWidgets(
      'the representative of a group is picked in the detail view of the edit mode',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (request) => http.Response(
        request.method == "PUT" ? "" : fixture("album.json"),
        200,
        headers: {"content-type": "application/json"},
      ),
      requests: requests,
    );

    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));

    // A long press on the group's tile enters the edit mode with the group
    // selected; its tools offer the way into the alternatives.
    await settle(tester, () => tester.longPress(find.byType(Image).at(2)));
    expect(find.byIcon(Icons.save), findsOneWidget, reason: "edit mode");
    await tap(tester, find.byTooltip("Gruppenbild wählen"));

    // The alternatives view marks the current representative.
    expect(find.byType(GroupView), findsOneWidget);
    expect(representativeMark("group-a.jpg"), findsOneWidget);
    expect(representativeMark("group-b.jpg"), findsNothing);

    // The other shot opens in the detail view, which navigates the group ...
    await tap(tester, memberTile("group-b.jpg"));
    expect(shownUrl(tester), endsWith("/group-b.jpg"));
    expect(find.byIcon(Icons.chevron_left), findsOneWidget);
    expect(find.byIcon(Icons.chevron_right), findsNothing);
    await tap(tester, find.byIcon(Icons.chevron_left));
    expect(shownUrl(tester), endsWith("/group-a.jpg"));
    expect(find.byTooltip(testL10n.groupPictureIsThis), findsOneWidget);
    await tap(tester, find.byIcon(Icons.chevron_right));
    expect(shownUrl(tester), endsWith("/group-b.jpg"));

    // ... and offers to make the shown image the representative.
    expect(find.byTooltip(testL10n.useAsGroupPicture), findsOneWidget);
    await tap(tester, find.byTooltip(testL10n.useAsGroupPicture));
    expect(find.byTooltip(testL10n.groupPictureIsThis), findsOneWidget);

    // Up: out of the group and straight back to the album, in one step — the
    // alternatives view is reached from the tile, not on the way out
    // (issue #79). The album is still being edited.
    await press(tester, LogicalKeyboardKey.arrowUp);
    expect(find.byType(AlbumContent), findsOneWidget);
    expect(find.byIcon(Icons.save), findsOneWidget,
        reason: "the edit mode survives the trip into the group");

    // And the alternatives view, entered again from the tile, shows the new
    // choice.
    await tap(tester, find.byTooltip("Gruppenbild wählen"));
    expect(representativeMark("group-b.jpg"), findsOneWidget);
    expect(representativeMark("group-a.jpg"), findsNothing);
    await tap(tester, find.byIcon(Icons.arrow_back));

    // Saving stores the choice.
    await tap(tester, find.byIcon(Icons.save));

    var puts = requests.where((r) => r.method == "PUT").toList();
    expect(puts, hasLength(1));
    var saved =
        Resource.read(JsonReader.fromString(puts.single.body)) as AlbumInfo;
    var group = saved.parts.whereType<ImageGroup>().single;
    expect(group.images[group.representative].name, "group-b.jpg");
  });

  testWidgets('outside the edit mode the detail view only shows the group',
      (tester) async {
    var client = clientReturning(fixture("album.json"));

    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
    await tap(tester, find.byType(Image).at(2));
    await tap(tester, find.byIcon(Icons.expand_more));
    await tap(tester, memberTile("group-b.jpg"));

    expect(shownUrl(tester), endsWith("/group-b.jpg"));
    expect(find.byIcon(Icons.chevron_left), findsOneWidget);
    expect(find.byTooltip(testL10n.useAsGroupPicture), findsNothing);
    expect(find.byTooltip(testL10n.groupPictureIsThis), findsNothing);
  });
}
