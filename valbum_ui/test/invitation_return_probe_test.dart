/// Probe for issue #88: the notice of a dead invitation address composed with
/// the rest of the location and with a session start.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import "util/fixtures.dart";

const String dataUrl = "http://server/valbum/data";

const String serverUrl = "http://server/valbum/";

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

const String rootListing = '["ListingInfo", {"path": "", '
    '"title": "My albums", "folders": [{"name": "Zoo", "title": "Zoo"}]}]';

const String authOfCarol =
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "edit", "space": ""}';

const String authOfShare =
    '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
    '"userName": "", "role": "", "space": "", '
    '"share": {"id": "sh-1", "label": "Zoo", "path": "Zoo", '
    '"rights": ["view"], "maxPrivacy": "public"}}';

MockClient answering(String auth) => MockClient(servingThumbnails((request) async {
      if (request.url.queryParameters["type"] == "auth") {
        return json(auth);
      }
      if (request.url.queryParameters["type"] == "json") {
        return json(rootListing);
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    }));

final Finder notice = find.byKey(const Key("invitation-notice"));

void main() {
  testWidgets('the fragment and an empty reason: nothing shown, location cleaned',
      (tester) async {
    var rewritten = <Uri>[];
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
          dataUrl: dataUrl, token: "dev-9", httpClient: answering(authOfCarol)),
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
      location: Uri.parse("$serverUrl?invitation=&tab=2#/Zoo/"),
      rewriteLocation: rewritten.add,
      openUrl: (_) {},
    ));
    await tester.pumpAndSettle();

    // An empty reason is no reason, but it is still taken out of the location;
    // what else the location carried stays exactly as it was.
    expect(notice, findsNothing);
    expect(rewritten, [Uri.parse("$serverUrl?tab=2#/Zoo/")]);
    expect(find.text("Zoo"), findsOneWidget);
  });

  testWidgets('a share session ignores the parameter and leaves the location alone',
      (tester) async {
    var rewritten = <Uri>[];
    const share = SessionUrl(
      kind: SessionKind.share,
      token: "sh-token",
      dataUrl: dataUrl,
      basePath: "/valbum/s/sh-token/",
    );
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(dataUrl: dataUrl, token: "sh-token", httpClient: answering(authOfShare)),
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
      ),
      session: share,
      location: Uri.parse("http://server/valbum/s/sh-token/?invitation=used"),
      rewriteLocation: rewritten.add,
      openUrl: (_) {},
    ));
    await tester.pumpAndSettle();

    expect(notice, findsNothing);
    expect(rewritten, isEmpty);
  });

  testWidgets('a reason nobody knows is dropped without a word', (tester) async {
    var rewritten = <Uri>[];
    await tester.pumpWidget(VAlbumApp(
      client: VAlbumClient(
          dataUrl: dataUrl, token: "dev-9", httpClient: answering(authOfCarol)),
      settings: ServerSettings(
        store: InMemorySettingsStore(),
        platformDefault: () => dataUrl,
        token: "dev-9",
        userName: "carol",
        loaded: true,
      ),
      location: Uri.parse("$serverUrl?invitation=<script>"),
      rewriteLocation: rewritten.add,
      openUrl: (_) {},
    ));
    await tester.pumpAndSettle();

    expect(notice, findsNothing);
    expect(find.textContaining("<script>"), findsNothing);
    expect(rewritten, [Uri.parse(serverUrl)]);
  });
}
