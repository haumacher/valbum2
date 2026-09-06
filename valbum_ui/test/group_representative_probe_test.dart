import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/app.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/routes.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Finder memberTile(String name) => find.byKey(ValueKey("group-tile-$name"));

Finder representativeMark(String name) => find.descendant(
      of: find.ancestor(of: memberTile(name), matching: find.byType(Stack)),
      matching: find.byKey(const Key("group-representative")),
    );

Future<void> settle(WidgetTester tester, Future<void> Function() act) =>
    withFakeImageHttp(() async {
      await act();
      await tester.pumpAndSettle();
    });

Future<void> tap(WidgetTester tester, Finder finder) =>
    settle(tester, () => tester.tap(finder));

Future<void> press(WidgetTester tester, LogicalKeyboardKey key) =>
    settle(tester, () => tester.sendKeyEvent(key));

VAlbumRoute routeOf(WidgetTester tester) =>
    tester.state<VAlbumState>(find.byType(VAlbumView)).route;

void main() {
  testWidgets(
      'a group re-entered after the switch is addressed by its new representative',
      (tester) async {
    var client = clientReturning(fixture("album.json"));
    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));

    // Switch the representative from a to b, unsaved.
    await settle(tester, () => tester.longPress(find.byType(Image).at(2)));
    await tap(tester, find.byTooltip("Gruppenbild wählen"));
    expect(routeOf(tester), const AlternativesRoute([], "group-a.jpg"));
    await tap(tester, memberTile("group-b.jpg"));
    await tap(tester, find.byTooltip("Als Gruppenbild verwenden"));
    await press(tester, LogicalKeyboardKey.arrowUp);
    await tap(tester, find.byTooltip("Zurück zum Album"));
    await press(tester, LogicalKeyboardKey.arrowUp);
    expect(routeOf(tester), ListingOrAlbumRoute.root);

    // Back in the edit mode, the group's tile stands under its new name and
    // still leads into the alternatives, now addressed by the new name; the
    // unsaved choice is what the alternatives show.
    expect(find.byIcon(Icons.save), findsOneWidget);
    expect(find.byKey(const ValueKey("group-b.jpg")), findsOneWidget);
    expect(find.byKey(const ValueKey("group-a.jpg")), findsNothing);
    await settle(
      tester,
      () => tester.longPress(find.byKey(const ValueKey("group-b.jpg"))),
    );
    await tap(tester, find.byTooltip("Gruppenbild wählen"));
    expect(routeOf(tester), const AlternativesRoute([], "group-b.jpg"));
    expect(representativeMark("group-b.jpg"), findsOneWidget);
    expect(representativeMark("group-a.jpg"), findsNothing);

    // The old name still resolves to the same group, as a bookmark would.
    await tap(tester, memberTile("group-a.jpg"));
    expect(routeOf(tester),
        const MemberRoute([], "group-b.jpg", "group-a.jpg"));
    expect(find.byTooltip("Als Gruppenbild verwenden"), findsOneWidget);

    // Dissolving the group afterwards is still possible: the edit session
    // holds a selection that names the (possibly re-represented) group.
    await press(tester, LogicalKeyboardKey.arrowUp);
    await tap(tester, find.byTooltip("Zurück zum Album"));
    await press(tester, LogicalKeyboardKey.arrowUp);
    await settle(
      tester,
      () => tester.longPress(find.byKey(const ValueKey("group-b.jpg"))),
    );
    await tap(tester, find.byTooltip("Gruppierung aufheben"));
    expect(find.byKey(const ValueKey("group-a.jpg")), findsOneWidget);
    expect(find.byKey(const ValueKey("group-b.jpg")), findsOneWidget);
  });

  testWidgets('a new server forgets the edit session', (tester) async {
    var client = clientReturning(fixture("album.json"));
    await settle(tester, () => tester.pumpWidget(VAlbumApp(client: client)));
    await settle(tester, () => tester.longPress(find.byType(Image).at(2)));
    expect(find.byIcon(Icons.save), findsOneWidget);

    var delegate = VAlbumNavigator.of(
      tester.element(find.byType(VAlbumView)),
    ).delegate;
    delegate.client = clientReturning(
      fixture("album.json"),
      dataUrl: "http://other/valbum/data",
    );
    await settle(tester, () async {});

    expect(find.byIcon(Icons.save), findsNothing,
        reason: "another server's album is not the one being edited");
  });
}
