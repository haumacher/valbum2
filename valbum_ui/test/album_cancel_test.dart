/// Cancelling an album edit (issue #99).
///
/// The edit mode used to have exactly one way out: Save. An accidental long
/// press, a wrong drag or a heading nobody wanted were written to the server
/// or left hanging in the buffer. Cancel — the × of the app bar and the
/// Escape key — ends the edit; unsaved changes are never thrown away in
/// silence, the album asks and, when told to, fetches itself from the server
/// again, so what is on the screen is exactly what the server has.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The stored order of the album fixture, before anything was moved.
const List<String> storedOrder = [
  "Heading(Am Morgen)",
  "landscape.jpg",
  "portrait.jpg",
  "Group(group-a.jpg,group-b.jpg)",
];

/// The order after the landscape image was dropped behind the group.
const List<String> movedOrder = [
  "Heading(Am Morgen)",
  "portrait.jpg",
  "Group(group-a.jpg,group-b.jpg)",
  "landscape.jpg",
];

/// The album fixture, with every PUT accepted and recorded.
VAlbumClient editableAlbum(List<http.Request> requests) => clientHandling(
      (request) => request.method == "PUT"
          ? http.Response("", 200)
          : http.Response(fixture("album.json"), 200),
      requests: requests,
    );

List<http.Request> putsIn(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "PUT") request
    ];

/// Loads the album and enters the edit mode by a long press.
Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(client: client));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

Finder get cancelAction => find.byKey(const Key("edit-cancel"));
Finder get discardDialog => find.byKey(const Key("discard-dialog"));

Future<void> pressEscape(WidgetTester tester) async {
  await tester.sendKeyEvent(LogicalKeyboardKey.escape);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Cancel leaves an edit that changed nothing, without a word',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));
      expect(albumStateOf(tester).editMode, isTrue);
      expect(albumStateOf(tester).selection, isNotEmpty);

      await tester.tap(cancelAction);
      await tester.pumpAndSettle();

      expect(discardDialog, findsNothing, reason: "nothing to ask about");
      expect(albumStateOf(tester).editMode, isFalse);
      expect(albumStateOf(tester).selection, isEmpty);
      expect(albumStateOf(tester).lastClicked, isNull);
      expect(cancelAction, findsNothing);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('Cancel asks about unsaved changes and discarding restores them',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));

      await dragBehind(tester, "landscape.jpg", "group-a.jpg");
      expect(partNames(albumOf(tester)), movedOrder);
      expect(albumStateOf(tester).dirty, isTrue);

      await tester.tap(cancelAction);
      await tester.pumpAndSettle();

      expect(discardDialog, findsOneWidget);
      expect(find.text("Discard the changes to this album?"), findsOneWidget);

      await tester.tap(find.byKey(const Key("discard-changes")));
      await tester.pumpAndSettle();

      expect(discardDialog, findsNothing);
      expect(albumStateOf(tester).editMode, isFalse);
      expect(albumStateOf(tester).dirty, isFalse);
      // The album on the screen is the one the server answered again.
      expect(partNames(albumOf(tester)), storedOrder);
    });
    expect(putsIn(requests), isEmpty, reason: "the server never heard of it");
  });

  testWidgets('"Keep editing" changes nothing at all', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));

      await dragBehind(tester, "landscape.jpg", "group-a.jpg");

      await tester.tap(cancelAction);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("keep-editing")));
      await tester.pumpAndSettle();

      expect(discardDialog, findsNothing);
      expect(albumStateOf(tester).editMode, isTrue);
      expect(albumStateOf(tester).dirty, isTrue);
      expect(partNames(albumOf(tester)), movedOrder);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('Escape is Cancel', (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));

      // With unsaved changes it asks, exactly as the × does.
      await dragBehind(tester, "landscape.jpg", "group-a.jpg");
      await pressEscape(tester);
      expect(discardDialog, findsOneWidget);
      await tester.tap(find.byKey(const Key("keep-editing")));
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).editMode, isTrue);

      // And without them it simply leaves the edit mode.
      await tester.tap(cancelAction);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key("discard-changes")));
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).editMode, isFalse);

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(albumStateOf(tester).editMode, isTrue);
      await pressEscape(tester);
      expect(discardDialog, findsNothing);
      expect(albumStateOf(tester).editMode, isFalse);
    });
    expect(putsIn(requests), isEmpty);
  });

  testWidgets('the edit mode reads from the left: the way out, the menu last',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: editableAlbum(requests),
        initialRoute: const ListingOrAlbumRoute(["Album"]),
      ));
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();

      var bar = tester.widget<AppBar>(find.byType(AppBar));
      expect(bar.leading, isA<IconButton>());
      expect((bar.leading! as IconButton).tooltip, "Up");
      // The three-dots menu is the last thing in the row.
      expect(bar.actions!.last, isA<PopupMenuButton>());
      // And the edit's own actions sit between them, Cancel behind Save.
      // The move and the album properties are entries of the menu since
      // issue #121.
      var tooltips = [
        for (var action in bar.actions!)
          if (action is IconButton) action.tooltip,
      ];
      expect(tooltips, ["Save", "Cancel"]);
    });
  });
}
