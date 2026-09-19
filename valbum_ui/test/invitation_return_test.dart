/// Tests of the app half of issue #88: what an ordinary start does with the
/// `?invitation=<reason>` a dead invitation address was redirected with, and
/// the way on the refusal page of an invitation that died while it was open.
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

/// The app base of that server, what a device stores and where a dead
/// invitation address sends the browser.
const String serverUrl = "http://server/valbum/";

/// The invitation session of the race test.
const SessionUrl invitation = SessionUrl(
  kind: SessionKind.invitation,
  token: "inv-1",
  dataUrl: dataUrl,
  basePath: "/valbum/i/inv-1/",
);

/// An answer with the JSON content type the app expects.
http.Response json(String body) => http.Response(
      body,
      200,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// A refusal of the server, with the reason it names.
http.Response refusal(int status, String message) => http.Response(
      '["ErrorInfo", {"message": "$message"}]',
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

/// The `?type=auth` answer of a signed-in caller.
String authOfUser(String name) =>
    '{"mode": "writes", "deviceName": "Phone", "writeAllowed": true, '
    '"userName": "$name", "role": "edit", "space": "$name"}';

/// A root listing of one album tile.
const String rootListing = '["ListingInfo", {"path": "", '
    '"title": "My albums", "folders": [{"name": "Zoo", "title": "Zoo"}]}]';

/// What the app was pumped with, and what it wrote back to the location.
typedef Pumped = ({List<Uri> rewritten, List<String> left});

/// Pumps an ordinary start at the given location.
///
/// [token] is what the device has stored: a token (and a server) means this
/// browser is signed in here, which is what the used notice reads differently
/// for.
Future<Pumped> pumpStart(
  WidgetTester tester,
  String location, {
  String? token,
  String? userName,
  bool serverKnown = true,
  bool authAnswers = true,
}) async {
  var rewritten = <Uri>[];
  var left = <String>[];
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: token,
    httpClient: MockClient(servingThumbnails((request) async {
      if (request.url.queryParameters["type"] == "auth") {
        return authAnswers
            ? json(authOfUser(userName ?? ""))
            : refusal(500, "The server did not say.");
      }
      if (request.url.queryParameters["type"] == "json") {
        return json(rootListing);
      }
      return http.Response("No such resource: ${request.url.path}", 404);
    })),
  );
  await tester.pumpWidget(VAlbumApp(
    client: client,
    settings: ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => serverKnown ? dataUrl : null,
      token: token,
      userName: userName,
      loaded: true,
    ),
    location: Uri.parse(location),
    rewriteLocation: rewritten.add,
    openUrl: left.add,
  ));
  await tester.pumpAndSettle();
  return (rewritten: rewritten, left: left);
}

/// The notice of a dead invitation address, and its text.
final Finder notice = find.byKey(const Key("invitation-notice"));

void main() {
  group('the notice of a dead invitation address', () {
    testWidgets('a used invitation, on the device that accepted it',
        (tester) async {
      var app = await pumpStart(
        tester,
        "${serverUrl}?invitation=used",
        token: "dev-9",
        userName: "carol",
      );

      // The album is shown: that is the "it simply works" of the issue.
      expect(find.text("Zoo"), findsOneWidget);
      expect(notice, findsOneWidget);
      expect(
        find.text("This invitation was already used — you are signed in "
            "here as carol."),
        findsOneWidget,
      );
      // The reason is said once; the location does not carry it any more.
      expect(app.rewritten, [Uri.parse(serverUrl)]);
    });

    testWidgets('the notice can be dismissed and stays away', (tester) async {
      await pumpStart(
        tester,
        "${serverUrl}?invitation=used",
        token: "dev-9",
        userName: "carol",
      );
      expect(notice, findsOneWidget);

      await tester.tap(find.byKey(const Key("invitation-notice-dismiss")));
      await tester.pumpAndSettle();

      expect(notice, findsNothing);
      expect(find.text("Zoo"), findsOneWidget);
    });

    testWidgets('a used invitation on a device that is signed in nameless',
        (tester) async {
      await pumpStart(
        tester,
        "${serverUrl}?invitation=used",
        token: "dev-9",
        authAnswers: false,
      );

      expect(
        find.text("This invitation was already used — you are already "
            "signed in here."),
        findsOneWidget,
      );
    });

    testWidgets('a used invitation on a device that is not signed in',
        (tester) async {
      var app = await pumpStart(
        tester,
        "${serverUrl}?invitation=used",
        serverKnown: false,
      );

      expect(find.byType(ServerSettingsScreen), findsOneWidget);
      expect(notice, findsOneWidget);
      expect(
        find.text("This invitation was already used. If you accepted it on "
            "another device, sign in here with a device code from that "
            "device; otherwise ask for a new invitation."),
        findsOneWidget,
      );
      expect(app.rewritten, [Uri.parse(serverUrl)]);
    });

    testWidgets('an expired invitation', (tester) async {
      await pumpStart(tester, "${serverUrl}?invitation=expired",
          token: "dev-9", userName: "carol");

      expect(find.text("This invitation has expired. Ask for a new one."),
          findsOneWidget);
    });

    testWidgets('a withdrawn invitation', (tester) async {
      await pumpStart(tester, "${serverUrl}?invitation=withdrawn",
          token: "dev-9", userName: "carol");

      expect(find.text("This invitation was withdrawn."), findsOneWidget);
    });

    testWidgets('a token this server never issued', (tester) async {
      await pumpStart(tester, "${serverUrl}?invitation=unknown",
          token: "dev-9", userName: "carol");

      expect(find.text("This is not an invitation of this server."),
          findsOneWidget);
    });

    testWidgets('a reason this build does not know says nothing, and is still '
        'taken out of the location', (tester) async {
      var app = await pumpStart(tester, "${serverUrl}?invitation=garbage",
          token: "dev-9", userName: "carol");

      expect(notice, findsNothing);
      expect(find.text("Zoo"), findsOneWidget);
      expect(app.rewritten, [Uri.parse(serverUrl)]);
    });

    testWidgets('an ordinary start without the parameter leaves the location '
        'alone', (tester) async {
      var app =
          await pumpStart(tester, serverUrl, token: "dev-9", userName: "carol");

      expect(notice, findsNothing);
      expect(app.rewritten, isEmpty);
    });

    testWidgets('other query parameters are kept', (tester) async {
      var app = await pumpStart(
        tester,
        "${serverUrl}?viewAs=public&invitation=withdrawn",
        token: "dev-9",
        userName: "carol",
      );

      expect(find.text("This invitation was withdrawn."), findsOneWidget);
      expect(app.rewritten, [Uri.parse("${serverUrl}?viewAs=public")]);
    });
  });

  group('an invitation that died while its page was open', () {
    /// Pumps the app inside an invitation session the server answers `410` to.
    Future<List<String>> pumpDeadSession(WidgetTester tester) async {
      var left = <String>[];
      var client = VAlbumClient(
        dataUrl: dataUrl,
        httpClient: MockClient(servingThumbnails((request) async =>
            refusal(410, "This invitation was already used."))),
      );
      await tester.pumpWidget(VAlbumApp(
        client: client,
        settings: ServerSettings(
          store: InMemorySettingsStore(),
          platformDefault: () => dataUrl,
        ),
        session: invitation,
        openUrl: left.add,
      ));
      await tester.pumpAndSettle();
      return left;
    }

    testWidgets('keeps its reason and offers the start page', (tester) async {
      var left = await pumpDeadSession(tester);

      expect(find.byKey(const Key("share-gone")), findsOneWidget);
      expect(find.text("This invitation was already used."), findsOneWidget);

      await tester.tap(find.byKey(const Key("invitation-continue")));
      await tester.pumpAndSettle();

      // The way on is the ordinary app base of the very same server.
      expect(left, [serverUrl]);
    });
  });
}
