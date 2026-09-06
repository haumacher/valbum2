/// Probe for the privacy levels of issue #46, composed with what existed
/// before them: the offline copy (issue #31) must be the owner's album even
/// after a "view as" preview, and the tile control and marker must agree
/// through a whole cycle of levels.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'privacy_test.dart' hide main;
import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

Never unreachable(http.Request request) =>
    throw http.ClientException("Connection refused", request.url);

void main() {
  testWidgets('the offline copy is the owner\'s album, not the last preview',
      (tester) async {
    var cache = MemoryOfflineCache();
    var state = OfflineState();
    var reachable = true;
    var requests = <http.Request>[];
    var client = VAlbumClient(
      dataUrl: dataUrl,
      cache: cache,
      offlineState: state,
      httpClient: MockClient(servingThumbnails((request) async {
        requests.add(request);
        if (!reachable) {
          unreachable(request);
        }
        return http.Response(
          request.url.queryParameters["viewAs"] == null
              ? privacyAlbum
              : membersAlbum,
          200,
        );
      })),
    );

    await withFakeImageHttp(() async {
      await tester.pumpWidget(
        VAlbumApp(client: client, cache: cache, offlineState: state),
      );
      await tester.pumpAndSettle();
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await tapTile(tester, "landscape.jpg");

      await chooseViewAs(tester, ViewAs.public);
      expect(tile("secret.jpg"), findsNothing);
      expect(viewAsOf(requests), [null, "public"]);

      // The server goes away while the preview is on screen; the way back to
      // the owner's album is answered from the copy, which is the whole album.
      reachable = false;
      await chooseViewAs(tester, ViewAs.owner);
    });

    expect(state.offline, isTrue);
    expect(find.textContaining(offlineMessage(state.lastUpdated)), findsOneWidget);
    expect(tile("secret.jpg"), findsOneWidget);
    expect(tile("hidden.jpg"), findsOneWidget);
    expect(find.byKey(const Key("view-as-banner")), findsNothing);
    // The private tiles carry their markers in the copy as well.
    expect(marker("secret.jpg"), findsOneWidget);

    // A preview cannot be shown from a copy: there is none to show.
    await withFakeImageHttp(() async {
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      await chooseViewAs(tester, ViewAs.members);
    });
    expect(find.textContaining("nothing to preview"), findsOneWidget);
    expect(tile("secret.jpg"), findsOneWidget);
    expect(albumState(tester).viewAs, ViewAs.owner);
  });

  testWidgets('the control walks through the levels and the marker follows',
      (tester) async {
    var requests = <http.Request>[];
    var client = clientHandling(
      (request) => http.Response(privacyAlbum, 200),
      dataUrl: dataUrl,
      requests: requests,
    );

    await withFakeImageHttp(() async {
      await pumpEditMode(tester, client, select: "landscape.jpg");
    });

    // landscape.jpg is public in the fixture.
    expect(marker("landscape.jpg"), findsNothing);
    expect(iconOf(tester, control("landscape.jpg")), Icons.public);
    expect(albumState(tester).dirty, isFalse);

    await tester.tap(control("landscape.jpg"));
    await tester.pumpAndSettle();
    expect(iconOf(tester, control("landscape.jpg")), Icons.group);
    expect(iconOf(tester, marker("landscape.jpg")), Icons.group);
    expect(albumState(tester).dirty, isTrue);

    await tester.tap(control("landscape.jpg"));
    await tester.pumpAndSettle();
    expect(iconOf(tester, control("landscape.jpg")), Icons.lock);
    expect(iconOf(tester, marker("landscape.jpg")), Icons.lock);

    await tester.tap(control("landscape.jpg"));
    await tester.pumpAndSettle();
    expect(iconOf(tester, control("landscape.jpg")), Icons.public);
    expect(marker("landscape.jpg"), findsNothing);
    // Back at the stored level, yet the album was edited: nothing hides that.
    expect(albumState(tester).dirty, isTrue);

    // The tooltip speaks the same vocabulary as the marker.
    expect(find.byTooltip("Privacy: Public (tap for Members)"), findsOneWidget);
  });
}
