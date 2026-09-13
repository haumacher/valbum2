/// Review probe of the app half of issue #52: invitations and the guest role
/// composed with what was there before — a server at its context root, a deep
/// link under an invitation base, a guest contributing through a #50 link, the
/// sharing entries at a guest's root, and a double tap on "Join".
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fixtures.dart';

http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

const String authOfInvitation =
    '{"mode": "writes", "deviceName": "", "writeAllowed": false, '
    '"userName": "", "role": "", "space": "", '
    '"invitation": {"role": "guest", "invitedBy": "alice", '
    '"note": "", "expires": "2026-12-24T17:00:00Z"}}';

const String pairedAnswer = '{"token": "dev-9", "deviceName": "Phone", '
    '"userName": "carol", "role": "guest", "space": ""}';

String authOfUser(String role) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "carol", "role": "$role", "space": ""}';

/// A guest's root: one link tile, every right (the server grants a guest all
/// of them in their own root so that they may rearrange and decline links).
const String guestRoot = '["ListingInfo", {"path": "", "title": "My albums", '
    '"rights": [{"name": "edit"}], '
    '"folders": [{"name": "Zoo", "title": "Zoo", "link": "~alice/2024/Zoo"}]}]';

/// The album behind the link, with the contribute right the grant gives.
const String zooAlbum = '["AlbumInfo", {"path": "Zoo", "title": "Zoo", '
    '"subTitle": "", "rights": [{"name": "view"}, {"name": "contribute"}], '
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

Future<List<http.Request>> pumpAs(
  WidgetTester tester,
  String role, {
  VAlbumRoute? initialRoute,
}) async {
  var requests = <http.Request>[];
  var client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    token: "dev-9",
    userName: "carol",
    httpClient: MockClient(servingThumbnails((request) async {
      requests.add(request);
      var type = request.url.queryParameters["type"];
      var path = pathOf(request);
      if (type == "auth") {
        return json(authOfUser(role));
      }
      if (type == "json" && path == "/valbum/data/") {
        return json(guestRoot);
      }
      if (type == "json" && path == "/valbum/data/Zoo/") {
        return json(zooAlbum);
      }
      if (type == "grants") {
        // What the server answers at a guest's own root: nothing, not a
        // refusal — the grants there are simply none.
        return json('{"grants": []}');
      }
      return http.Response("No such resource: $path", 404);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    initialRoute: initialRoute,
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => "http://server/valbum/data",
      token: "dev-9",
      userName: "carol",
      loaded: true,
    ),
  ));
  await tester.pumpAndSettle();
  return requests;
}

void main() {
  group('the pasted URL', () {
    test('an invitation link without its trailing slash is still one', () {
      var location = serverLocationOf("http://h/valbum/i/tok-1");
      expect(location.invitation, "tok-1");
      expect(location.serverUrl, "http://h/valbum/");
      expect(location.dataUrl, "http://h/valbum/data");
    });

    test('a server at its context root', () {
      var location = serverLocationOf("http://h:8080/i/tok/");
      expect(location.invitation, "tok");
      expect(location.serverUrl, "http://h:8080/");
      expect(location.dataUrl, "http://h:8080/data");
    });

    test('a plain URL with an album named "i" is not an invitation', () {
      // `.../i/2024/` as a *server* URL: the last folder would be the app
      // base, and there is no such thing as a token there.
      var location = serverLocationOf("http://h/valbum/i/2024/");
      expect(location.invitation, "2024",
          reason: "an app base ending in /i/<segment>/ can only be a link; "
              "the server field is not an album path");
    });
  });

  group('joining', () {
    testWidgets('at a server at its context root stores the root as server',
        (tester) async {
      var requests = <http.Request>[];
      var store = InMemorySettingsStore();
      var client = VAlbumClient(
        dataUrl: "http://server/data",
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfInvitation);
          }
          if (request.url.queryParameters["action"] == "pair") {
            return json(pairedAnswer);
          }
          return http.Response("unexpected ${pathOf(request)}", 500);
        })),
      );
      await tester.pumpWidget(VAlbumApp(
        client: client,
        settings: ServerSettings(store: store, platformDefault: () => null),
        session: const SessionUrl(
          kind: SessionKind.invitation,
          token: "inv-1",
          dataUrl: "http://server/data",
          basePath: "/i/inv-1/",
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key("invitation-welcome")), findsOneWidget);

      await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
      await tester.tap(find.byKey(const Key("invitation-join")));
      await tester.pumpAndSettle();

      expect(pathOf(requests.last), "/data/");
      expect(store.value, "http://server/");
      expect(store.token, "dev-9");
      expect(find.byKey(const Key("invitation-joined")), findsOneWidget);
    });

    testWidgets('a double tap on Join signs in once', (tester) async {
      var pairs = 0;
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient(servingThumbnails((request) async {
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfInvitation);
          }
          if (request.url.queryParameters["action"] == "pair") {
            pairs++;
            await Future<void>.delayed(const Duration(milliseconds: 200));
            return json(pairedAnswer);
          }
          return http.Response("unexpected", 500);
        })),
      );
      await tester.pumpWidget(VAlbumApp(
        client: client,
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => null,
        ),
        session: const SessionUrl(
          kind: SessionKind.invitation,
          token: "inv-1",
          dataUrl: "http://server/valbum/data",
          basePath: "/valbum/i/inv-1/",
        ),
      ));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(invitationUserFieldKey), "carol");
      await tester.tap(find.byKey(const Key("invitation-join")));
      await tester.pump(const Duration(milliseconds: 20));
      // While the server is asked there is nothing to tap a second time: the
      // button is gone (or disabled) until the answer is in.
      var join = find.byKey(const Key("invitation-join"));
      if (join.evaluate().isNotEmpty) {
        var button = tester.widget<ButtonStyleButton>(
          find.ancestor(of: join, matching: find.byType(ButtonStyleButton)).first,
        );
        expect(button.enabled, isFalse, reason: "a join in flight is not repeatable");
      }
      await tester.pumpAndSettle();

      expect(pairs, 1);
      expect(find.byKey(const Key("invitation-joined")), findsOneWidget);
    });

    testWidgets('a deep link under the invitation base still shows the welcome',
        (tester) async {
      var requests = <http.Request>[];
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient(servingThumbnails((request) async {
          requests.add(request);
          if (request.url.queryParameters["type"] == "auth") {
            return json(authOfInvitation);
          }
          return http.Response("unexpected ${pathOf(request)}", 500);
        })),
      );
      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ListingOrAlbumRoute(["2024", "Zoo"]),
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => null,
        ),
        session: const SessionUrl(
          kind: SessionKind.invitation,
          token: "inv-1",
          dataUrl: "http://server/valbum/data",
          basePath: "/valbum/i/inv-1/",
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("invitation-welcome")), findsOneWidget);
      expect(
        requests.where((r) => r.url.queryParameters["type"] == "json"),
        isEmpty,
        reason: "an invitation opens no album, whatever the path says",
      );
    });
  });

  group('a guest', () {
    testWidgets('contributes through a link although their root is photo-free',
        (tester) async {
      await pumpAs(tester, "guest");
      expect(find.text("Zoo"), findsOneWidget);

      await tester.tap(find.text("Zoo"));
      await tester.pumpAndSettle();

      expect(find.byTooltip("Upload"), findsOneWidget,
          reason: "the grant on the target decides, not the caller's role");
    });

    testWidgets('is offered no sharing at their own root', (tester) async {
      await pumpAs(tester, "guest");

      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();

      expect(find.text("Share with…"), findsNothing);
      expect(find.text("Share link…"), findsNothing);
      expect(find.text("Create album"), findsNothing);
    });

    testWidgets('as a member the same root offers creation (control)',
        (tester) async {
      await pumpAs(tester, "member");

      await tester.tap(find.byIcon(Icons.more_vert).first);
      await tester.pumpAndSettle();

      expect(find.textContaining("Create album"), findsOneWidget);
    });
  });
}
