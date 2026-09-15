/// Tests of what the app *offers* a caller of Phase 6 (issue #85, second
/// slice): the sentence in the settings, and the entries the album and the
/// listing show or leave out per role.
///
/// The per-folder rights of issue #49 stay the source of truth for what a
/// request may do. The role decides what is *offered* only where the server
/// answered no rights at all — and where neither was said, everything is
/// offered exactly as before, because the app never hides on a guess.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// A JSON answer.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// The answer of `?type=auth` for a caller of the Phase 6 model.
String authOf({
  required String role,
  String clearance = "",
  bool mayShare = false,
}) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": "carol", '
    '"clearance": "$clearance", "mayShare": $mayShare}';

/// The `rights` field of a folder, or nothing at all.
String rightsField(List<String>? rights) => rights == null
    ? ""
    : '"rights": [${rights.map((r) => '{"name": "$r"}').join(",")}], ';

/// A listing of one folder, with or without rights.
String listingOf(List<String>? rights) =>
    '["ListingInfo", {"path": "", "title": "My albums", '
    '${rightsField(rights)}'
    '"folders": [{"name": "Zoo", "title": "Zoo"}]}]';

/// An album of two images, with or without rights.
String albumOf(List<String>? rights) =>
    '["AlbumInfo", {"path": "", "title": "Zoo", "subTitle": "", '
    '${rightsField(rights)}'
    '"parts": ['
    '["ImagePart", {"kind": "IMAGE", "name": "a.jpg", "date": 1, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}],'
    '["ImagePart", {"kind": "IMAGE", "name": "b.jpg", "date": 2, "width": 300, "height": 200, "orientation": "IDENTITY", "rating": 0}]'
    ']}]';

/// Pumps the app on a listing or an album, signed in as the given caller.
Future<void> pumpAs(
  WidgetTester tester, {
  required String auth,
  required String resource,
}) async {
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "dev-9",
    userName: "carol",
    httpClient: MockClient(servingThumbnails((request) async {
      var query = request.url.queryParameters;
      if (query["type"] == "auth") {
        return json(auth);
      }
      if (query["type"] == "json") {
        return json(resource);
      }
      if (query["type"] == "grants") {
        return json('{"grants": []}');
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    })),
  );
  await withFakeImageHttp(() async {
    await tester.pumpWidget(VAlbumApp(
      client: client,
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
    ));
    await tester.pumpAndSettle();
  });
}

/// Opens the app-bar menu of the view shown.
Future<void> openMenu(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
  });
}

/// Long-presses the tile of the image with the given name.
Future<void> longPressImage(WidgetTester tester, String name) async {
  await withFakeImageHttp(() async {
    await tester.longPress(find.byKey(ValueKey(name)));
    await tester.pumpAndSettle();
  });
}

/// Pumps the settings screen alone, talking to the given transport.
Future<void> pumpSettings(
  WidgetTester tester,
  ServerSettings settings,
  http.Client transport,
) async {
  await tester.binding.setSurfaceSize(const Size(800, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (dataUrl) =>
            VAlbumClient(dataUrl: dataUrl, httpClient: transport),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// The settings of a device signed in at the test server.
Future<ServerSettings> signedIn() async {
  var settings = ServerSettings(
    store: InMemorySettingsStore(
      "http://server/valbum/",
      "dev-9",
      "Phone",
      "carol",
    ),
  );
  await settings.load();
  return settings;
}

void main() {
  group('the settings say what the caller may do and see', () {
    testWidgets('for somebody who edits, sees everything and may share',
        (tester) async {
      await pumpSettings(
        tester,
        await signedIn(),
        MockClient((request) async => json(
              authOf(role: "edit", clearance: "all", mayShare: true),
            )),
      );

      expect(
        tester.widget<Text>(find.byKey(permissionLineKey)).data,
        "You may edit every album of this space; you see all images; "
        "you may share links.",
      );
    });

    testWidgets('for somebody who may only add photos', (tester) async {
      await pumpSettings(
        tester,
        await signedIn(),
        MockClient((request) async => json(
              authOf(role: "contribute", clearance: "nonPrivate"),
            )),
      );

      expect(
        tester.widget<Text>(find.byKey(permissionLineKey)).data,
        "You may add photos to this space; you see all but the private "
        "images; you may not share links.",
      );
    });

    testWidgets('for somebody who may only look at what is public',
        (tester) async {
      await pumpSettings(
        tester,
        await signedIn(),
        MockClient((request) async =>
            json(authOf(role: "view", clearance: "public"))),
      );

      expect(
        tester.widget<Text>(find.byKey(permissionLineKey)).data,
        "You may look at this space; you see the public images; you may not "
        "share links.",
      );
    });

    testWidgets('and nothing at all where the server named nobody',
        (tester) async {
      await pumpSettings(
        tester,
        await signedIn(),
        MockClient((request) async => json(authOf(role: ""))),
      );

      expect(find.byKey(permissionLineKey), findsNothing);
    });
  });

  group('the album offers what the role allows, where no rights were sent', () {
    testWidgets('a viewer is not offered the way into the edit mode',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: albumOf(null),
      );

      await longPressImage(tester, "a.jpg");

      // Nothing is greyed out: the way in is simply not there.
      expect(find.byIcon(Icons.save), findsNothing);
    });

    testWidgets('a contributor is not offered it either', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "contribute"),
        resource: albumOf(null),
      );

      await longPressImage(tester, "a.jpg");

      expect(find.byIcon(Icons.save), findsNothing);
    });

    testWidgets('an editor is', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit"),
        resource: albumOf(null),
      );

      await longPressImage(tester, "a.jpg");

      expect(find.byIcon(Icons.save), findsOneWidget);
    });

    testWidgets('and so is a caller nobody named, exactly as before',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: ""),
        resource: albumOf(null),
      );

      await longPressImage(tester, "a.jpg");

      expect(find.byIcon(Icons.save), findsOneWidget);
    });
  });

  group('the upload follows the role where no rights were sent', () {
    testWidgets('a viewer is offered no way to add photos', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: albumOf(null),
      );

      expect(find.byType(FloatingActionButton), findsNothing);
    });

    testWidgets('a contributor is', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "contribute"),
        resource: albumOf(null),
      );

      expect(find.byType(FloatingActionButton), findsOneWidget);
    });

    testWidgets('and so is an editor, and a caller nobody named',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit"),
        resource: albumOf(null),
      );
      expect(find.byType(FloatingActionButton), findsOneWidget);

      await pumpAs(
        tester,
        auth: authOf(role: ""),
        resource: albumOf(null),
      );
      expect(find.byType(FloatingActionButton), findsOneWidget);
    });

    testWidgets('an album that carries rights decides for itself',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: albumOf(const ["contribute"]),
      );

      expect(find.byType(FloatingActionButton), findsOneWidget);
    });
  });

  group('the rights of the folder win wherever the server sent them', () {
    testWidgets('a viewer whose album says `edit` is offered the edit mode',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: albumOf(const ["edit"]),
      );

      await longPressImage(tester, "a.jpg");

      expect(find.byIcon(Icons.save), findsOneWidget,
          reason: "the rights are the server's word about this folder");
    });

    testWidgets('an editor whose album says `view` is not', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit"),
        resource: albumOf(const ["view"]),
      );

      await longPressImage(tester, "a.jpg");

      expect(find.byIcon(Icons.save), findsNothing);
    });
  });

  group('the listing offers what the role allows', () {
    testWidgets('a viewer creates nothing', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: listingOf(null),
      );

      await openMenu(tester);

      expect(find.text("Create album"), findsNothing);
      expect(find.text("Create folder"), findsNothing);
      expect(find.text("Folder properties"), findsNothing);
    });

    testWidgets('an editor creates albums and folders', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit"),
        resource: listingOf(null),
      );

      await openMenu(tester);

      expect(find.text("Create album"), findsOneWidget);
      expect(find.text("Create folder"), findsOneWidget);
    });

    testWidgets('a folder that carries rights decides for itself',
        (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "view"),
        resource: listingOf(const ["edit"]),
      );

      await openMenu(tester);

      expect(find.text("Create album"), findsOneWidget);
    });
  });

  group('sharing is offered to whoever may share', () {
    testWidgets('an editor who may share sees both entries', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit", mayShare: true),
        resource: listingOf(const ["edit"]),
      );

      await openMenu(tester);

      expect(find.text("Share with…"), findsOneWidget);
      expect(find.text("Share link…"), findsOneWidget);
    });

    testWidgets('an editor who may not share sees neither', (tester) async {
      await pumpAs(
        tester,
        auth: authOf(role: "edit"),
        resource: listingOf(const ["edit"]),
      );

      await openMenu(tester);

      expect(find.text("Share with…"), findsNothing);
      expect(find.text("Share link…"), findsNothing);
      // What is left is what an editor may do with the folder itself.
      expect(find.text("Create album"), findsOneWidget);
    });

    testWidgets('a server that says nothing about sharing offers it as before',
        (tester) async {
      // The old vocabulary: such a server never sets `mayShare`, and its
      // silence is not a refusal — the rights decide, as they always did.
      await pumpAs(
        tester,
        auth: authOf(role: "member"),
        resource: listingOf(const ["edit"]),
      );

      await openMenu(tester);

      expect(find.text("Share with…"), findsOneWidget);
      expect(find.text("Share link…"), findsOneWidget);
    });
  });
}
