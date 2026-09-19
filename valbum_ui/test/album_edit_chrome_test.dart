/// The album's edit-mode chrome, where issue #100 put it: the way back at the
/// left, the three-dots menu last at the right — and "View as" inside it.
///
/// The edit bar carried six controls, one of them a popup of its own for a
/// switch that is used once in a while. A phone has no room for that; the
/// album's other standing choice, the rating filter, had lived in the menu all
/// along, and "View as" joins it there — a line saying which view is on the
/// screen, the three choices under it.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'util/edit_drag.dart';
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The album fixture, with every PUT accepted, and the preview of a clearance
/// answered with an album of one image.
VAlbumClient editableAlbum(List<http.Request> requests) => clientHandling(
      (request) {
        if (request.method == "PUT") {
          return http.Response("", 200);
        }
        return http.Response(
          request.url.queryParameters["viewAs"] == null
              ? fixture("album.json")
              : publicAlbum,
          200,
          headers: const {"content-type": "application/json; charset=utf-8"},
        );
      },
      requests: requests,
    );

/// What a caller without any clearance receives: one image of the fixture.
const String publicAlbum =
    '["AlbumInfo", {"path": "", "title": "Album", "subTitle": "", "parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "landscape.jpg", "date": 1, '
    '"width": 2000, "height": 1000, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

Future<void> pumpEditMode(WidgetTester tester, VAlbumClient client) async {
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: const ListingOrAlbumRoute(["Album"]),
  ));
  await tester.pumpAndSettle();
  await tester.longPress(find.byType(Image).first);
  await tester.pumpAndSettle();
}

Future<void> openMenu(WidgetTester tester) async {
  await tester.tap(find.byIcon(Icons.more_vert).last);
  await tester.pumpAndSettle();
}

/// The `viewAs` parameter of every JSON request made so far, in order.
List<String?> viewAsOf(List<http.Request> requests) => [
      for (var request in requests)
        if (request.method == "GET" &&
            request.url.queryParameters["type"] == "json")
          request.url.queryParameters["viewAs"],
    ];

void main() {
  testWidgets('the way back is the leading control, the menu the last one',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum([]));

      var bar = tester.widget<AppBar>(find.byType(AppBar).last);
      expect(bar.leading, isA<IconButton>());
      expect((bar.leading! as IconButton).tooltip, "Up");
      expect(bar.actions!.last, isA<PopupMenuButton>());

      // "View as" is no longer a control of its own.
      expect(find.byTooltip("View as"), findsNothing);
      expect(
        [
          for (var action in bar.actions!)
            if (action is IconButton) action.tooltip,
        ],
        ["Move to…", "Album properties", "Save", "Cancel"],
      );
    });
  });

  testWidgets('the menu names the view on the screen and offers the three',
      (tester) async {
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum([]));
      await openMenu(tester);

      expect(find.text("View as"), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key("view-as-state"))).data,
        "yourself",
      );
      for (var value in ViewAs.values) {
        expect(find.byKey(Key("view-as-${value.name}")), findsOneWidget);
      }
    });
  });

  testWidgets('choosing "public" previews what the public sees',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));
      expect(viewAsOf(requests), [null]);

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("view-as-public")));
      await tester.pumpAndSettle();

      expect(viewAsOf(requests), [null, "public"]);
      expect(albumStateOf(tester).viewAs, ViewAs.public);
      expect(
        find.text("Viewing as public - this is what the public sees"),
        findsOneWidget,
      );
      // The smaller album the server answered is what is shown.
      expect(tile("portrait.jpg"), findsNothing);

      // And the menu now says which view that is.
      await openMenu(tester);
      expect(
        tester.widget<Text>(find.byKey(const Key("view-as-state"))).data,
        "public",
      );

      // The way back out of the preview is in the same menu.
      await tester.tap(find.byKey(const Key("view-as-owner")));
      await tester.pumpAndSettle();
      expect(viewAsOf(requests), [null, "public", null]);
      expect(find.byKey(const Key("view-as-banner")), findsNothing);
    });
  });

  testWidgets('an album with unsaved changes refuses the switch and says why',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await pumpEditMode(tester, editableAlbum(requests));
      await dragBehind(tester, "landscape.jpg", "portrait.jpg");
      expect(albumStateOf(tester).dirty, isTrue);

      await openMenu(tester);
      await tester.tap(find.byKey(const Key("view-as-members")));
      await tester.pumpAndSettle();

      expect(find.text("Save or discard your changes first"), findsOneWidget);
      expect(viewAsOf(requests), [null], reason: "nothing was fetched");
      expect(albumStateOf(tester).viewAs, ViewAs.owner);
    });
  });
}
