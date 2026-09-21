/// Review probes of the face editor (issue #126): a deep link straight into
/// the persons page, and a face carried away and back again.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'persons_view_test.dart' hide main;
import 'util/fake_image_http.dart';

void main() {
  test('the persons page is a route of its own that a link can name', () {
    var route = parseRoute(Uri.parse("/Zoo/2026/persons/"));
    expect(route, isA<PersonsRoute>());
    expect(route.albumPath, ["Zoo", "2026"]);
    expect(route.segments, ["Zoo", "2026", "persons", ""]);
  });

  testWidgets('a deep link opens the editor with its groups, the album beneath',
      (tester) async {
    var requests = <http.Request>[];
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(
        client: editorClient(requests, auth: authOf(), album: albumOf()),
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => dataUrl,
          token: "dev-9",
          userName: "carol",
          loaded: true,
        ),
        initialRoute: const PersonsRoute([]),
      ));
      await tester.pumpAndSettle();
    });
    expect(header("person:p-anna"), findsOneWidget);
    expect(header("cluster:c1"), findsOneWidget);
    expect(faceTile("a.jpg#0"), findsOneWidget);
    expect(albumFetches(requests), 1, reason: "One album, fetched once for both pages.");
  });

  testWidgets('a face carried away and back again changes nothing',
      (tester) async {
    var requests = <http.Request>[];
    await pumpEditor(
      tester,
      editorClient(
        requests,
        auth: authOf(),
        album: albumOf(),
        post: (request) => json(albumOf()),
      ),
    );

    await dragFaceTo(tester, "a.jpg#0", header("person:p-anna"));
    await dragFaceTo(tester, "a.jpg#0", header("cluster:c1"));

    // Nothing is dirty: the way back asks nothing, and nothing was posted.
    await withFakeImageHttp(() async {
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
    });
    expect(find.byKey(const Key("leave-dialog")), findsNothing);
    expect(posted(requests, "tag-faces"), isEmpty);
    expect(find.byKey(const Key("persons")), findsNothing,
        reason: "The album page is shown again (its menu is closed).");
  });
}
