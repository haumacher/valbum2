/// Probe for #107: the heading's date line goes with the title in the edit
/// mode, and the tile and the heading say the same date for one album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';

import 'listing_date_test.dart' show listingJson, albumJson, millis;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

VAlbumClient client() => clientHandling((request) {
      var path = Uri.decodeComponent(request.url.path);
      if (path.endsWith("/2002-03-03 Lake/")) {
        return http.Response(albumJson(millis(2002, 3, 3)), 200,
            headers: {"content-type": "application/json"});
      }
      return http.Response(listingJson(), 200,
          headers: {"content-type": "application/json"});
    });

String textOf(WidgetTester tester, Key key) =>
    tester.widget<Text>(find.byKey(key)).data!;

void main() {
  testWidgets('the tile and the heading agree, and the edit mode hides the '
      'date with the title', (tester) async {
    await withFakeImageHttp(() async {
      await tester.pumpWidget(VAlbumApp(client: client()));
      await tester.pumpAndSettle();
      var onTile = textOf(tester, const Key("folder-date"));
      await tester.tap(find.text("Lake"));
      await tester.pumpAndSettle();
      expect(find.byType(AlbumContent), findsOneWidget);
      expect(textOf(tester, const Key("album-heading-date")), onTile);
      expect(find.text("At the shore"), findsOneWidget);

      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.save), findsOneWidget, reason: "edit mode");
      expect(find.byKey(const Key("album-heading-date")), findsNothing,
          reason: "the heading is not shown while editing, nor its date");
    });
  });
}
