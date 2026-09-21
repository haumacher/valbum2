/// Tests of what an invitation offers, and of what the space model retired
/// (issue #85, third slice).
///
/// An invitation now carries the whole permission the invited person will
/// have: what they may do, what they see, and whether they may hand out links.
/// The dialog asks for all three and starts at the least that still lets
/// somebody see the family's pictures — a permission given too generously is
/// not noticed until somebody changes what they should not have.
///
/// What went with the space model: the named user groups, the "Share with…"
/// grants dialog, the link tiles of issue #50 and the guest role. A listing
/// from a server that still sends a `link` field shows an ordinary folder.
library;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';
import 'util/l10n.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// A JSON answer.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: const {"content-type": "application/json; charset=utf-8"},
    );

/// The answer of `?type=auth` for an administrator.
const String adminAuth = '{"mode": "writes", "deviceName": "Desk", '
    '"writeAllowed": true, "userName": "haui", "role": "admin", '
    '"space": "", "clearance": "all", "mayShare": true}';

/// A listing whose only folder still carries a `link` field, as a server from
/// before issue #85 answers it.
const String listingWithLink =
    '["ListingInfo", {"path": "", "title": "My albums", '
    '"rights": [{"name": "edit"}], '
    '"folders": [{"name": "Zoo", "title": "Zoo", "link": "~alice/2024/Zoo"}, '
    '{"name": "Party", "title": "Party"}]}]';

/// Pumps the settings screen of a signed-in administrator.
Future<List<http.Request>> pumpSettings(WidgetTester tester) async {
  var requests = <http.Request>[];
  var settings = ServerSettings(
    store: InMemorySettingsStore(
      "http://server/valbum/",
      "admin-token",
      "Desk",
      "haui",
    ),
  );
  await settings.load();
  await tester.binding.setSurfaceSize(const Size(800, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: ServerSettingsScreen(
        settings: settings,
        clientFor: (url) => VAlbumClient(
          dataUrl: url,
          httpClient: MockClient((request) async {
            requests.add(request);
            var query = request.url.queryParameters;
            if (query["type"] == "auth") {
              return json(adminAuth);
            }
            if (query["action"] == "invite") {
              return json(
                '{"invitation": {"id": "i7", "role": "view", "note": "", '
                '"expires": "2026-12-24T17:00:00Z", "invitedBy": "haui", '
                '"created": "", "used": "", "usedBy": "", "revoked": ""}, '
                '"token": "tok", "url": "/valbum/i/tok/"}',
              );
            }
            if (query["type"] == "users") {
              return json('{"users": []}');
            }
            if (query["type"] == "invitations") {
              return json('{"invitations": []}');
            }
            return json('{"devices": []}');
          }),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return requests;
}

/// Scrolls the widget into view and taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  group('the invite dialog asks for the whole permission', () {
    testWidgets('and starts at the least that still shows the pictures',
        (tester) async {
      await pumpSettings(tester);
      await tapVisible(tester, find.byKey(inviteButtonKey));

      expect(find.byKey(const Key("invite-dialog")), findsOneWidget);
      // The three roles of Phase 6, in the words of the settings.
      expect(find.text("may edit the albums"), findsOneWidget);
      expect(find.text("may add photos"), findsOneWidget);
      expect(find.text("may look"), findsOneWidget);
      // The three clearances.
      expect(find.text("sees all images"), findsOneWidget);
      expect(find.text("sees all but the private images"), findsOneWidget);
      expect(find.text("sees the public images"), findsOneWidget);
      // And the flag.
      expect(find.byKey(const Key("invite-may-share")), findsOneWidget);

      // The defaults: may look, sees all but the private images, no links.
      expect(defaultInviteRole, roleView);
      expect(defaultInviteClearance, clearanceNonPrivate);
      expect(
        tester
            .widget<SwitchListTile>(find.byKey(const Key("invite-may-share")))
            .value,
        isFalse,
      );
    });

    testWidgets('and sends what was left standing', (tester) async {
      var requests = await pumpSettings(tester);
      await tapVisible(tester, find.byKey(inviteButtonKey));
      await tapVisible(tester, find.byKey(const Key("invite-create")));

      var invite = requests.last;
      expect(invite.url.queryParameters["action"], "invite");
      expect(invite.body, contains('"role":"view"'));
      expect(invite.body, contains('"clearance":"nonPrivate"'));
      expect(invite.body, contains('"mayShare":false'));
    });

    testWidgets('and sends what was chosen instead', (tester) async {
      var requests = await pumpSettings(tester);
      await tapVisible(tester, find.byKey(inviteButtonKey));
      await tapVisible(tester, find.byKey(const Key("invite-role-edit")));
      await tapVisible(tester, find.byKey(const Key("invite-clearance-all")));
      await tapVisible(tester, find.byKey(const Key("invite-may-share")));
      await tapVisible(tester, find.byKey(const Key("invite-create")));

      var invite = requests.last;
      expect(invite.body, contains('"role":"edit"'));
      expect(invite.body, contains('"clearance":"all"'));
      expect(invite.body, contains('"mayShare":true'));
    });

    testWidgets('and offers nothing of the retired roles', (tester) async {
      await pumpSettings(tester);
      await tapVisible(tester, find.byKey(inviteButtonKey));

      expect(find.byKey(const Key("invite-role-member")), findsNothing);
      expect(find.byKey(const Key("invite-role-guest")), findsNothing);
      expect(find.text("Member"), findsNothing);
      expect(find.text("Guest"), findsNothing);
    });
  });

  group('a listing from a server that still sends links', () {
    testWidgets('shows an ordinary folder tile, and never an error',
        (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: clientReturning(listingWithLink)),
        );
        await tester.pumpAndSettle();
      });

      // Both folders are there, and neither is marked as anybody else's.
      expect(find.text("Zoo"), findsOneWidget);
      expect(find.text("Party"), findsOneWidget);
      expect(find.byKey(const Key("link-badge-Zoo")), findsNothing);
      expect(find.byKey(const Key("link-owner-Zoo")), findsNothing);
      expect(find.textContaining("from alice"), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('offers nothing to unlink on its tile', (tester) async {
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: clientReturning(listingWithLink)),
        );
        await tester.pumpAndSettle();
        await tester.longPress(find.text("Zoo"));
        await tester.pumpAndSettle();
      });

      expect(find.text("Remove from my albums"), findsNothing);
      expect(find.text("Share with…"), findsNothing);
      // What a folder of one's own still offers.
      expect(find.text("Move to…"), findsOneWidget);
    });
  });

  group('what the space model retired', () {
    testWidgets('is not in the settings any more', (tester) async {
      await pumpSettings(tester);

      // The groups screen of issue #55 and the grants it made out.
      expect(find.text("Groups…"), findsNothing);
      // The users section says what people may do; it does not promote them
      // to a role that no longer exists.
      expect(find.textContaining("Make member"), findsNothing);
    });
  });
}
