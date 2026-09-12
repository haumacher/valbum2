/// Tests of "Share with…" and of the rights the app honours (issue #49): the
/// share dialog, the groups it needs, and what the album, the listing and the
/// image viewer offer a caller who is not the owner.
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

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

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

/// An album with one image, carrying the given rights.
String albumWith(String rights) => '["AlbumInfo", {"path": "Zoo", '
    '"title": "Zoo", "subTitle": "", $rights'
    '"parts": [["ImagePart", {"kind": "IMAGE", "name": "a.jpg", '
    '"date": 1015113600000, "width": 2048, "height": 1536, '
    '"orientation": "IDENTITY", "rating": 0}]]}]';

/// A listing with one folder, carrying the given rights.
String listingWith(String rights) => '["ListingInfo", {"path": "", '
    '"title": "Test-album", $rights'
    '"folders": [{"name": "Zoo", "title": "Zoo"}]}]';

/// The rights field as the server spells it, for the given names.
String rightsField(List<String> names) => names.isEmpty
    ? ""
    : '"rights": [${names.map((name) => '{"name": "$name"}').join(", ")}], ';

/// The grants the share dialog is answered with: one made on the folder
/// itself, one inherited from the whole space.
const String grantsAnswer = '{"grants": ['
    '{"owner": "alice", "path": "2024/Zoo", "subject": "user:bob", '
    '"rights": [{"name": "view"}, {"name": "download"}], '
    '"created": "2026-09-01T10:00:00Z"}, '
    '{"owner": "alice", "path": "", "subject": "group:family", '
    '"rights": [{"name": "view"}], "created": "2026-08-01T10:00:00Z"}'
    ']}';

/// The users of the test server: the caller and two others.
const String usersAnswer = '{"users": ['
    '{"name": "alice", "role": "admin"}, '
    '{"name": "bob", "role": "member"}, '
    '{"name": "carol", "role": "member"}]}';

/// The groups the caller sees: one of their own, one of somebody else's.
const String groupsAnswer = '{"groups": ['
    '{"name": "family", "owner": "alice", "members": [{"name": "bob"}], '
    '"created": ""}, '
    '{"name": "neighbours", "owner": "bob", "members": [], "created": ""}]}';

/// A client for the share dialog, signed in as `alice`.
VAlbumClient shareClient(
  http.Response Function(http.Request request) handler, {
  List<http.Request>? requests,
}) =>
    VAlbumClient(
      dataUrl: dataUrl,
      token: "alice-token",
      userName: "alice",
      httpClient: MockClient(servingThumbnails((request) async {
        requests?.add(request);
        return handler(request);
      })),
    );

/// The standard answers of the share dialog's server.
http.Response shareAnswers(http.Request request) {
  var type = request.url.queryParameters["type"];
  var action = request.url.queryParameters["action"];
  if (type == "grants") {
    return json(grantsAnswer);
  }
  if (type == "users") {
    return json(usersAnswer);
  }
  if (type == "groups") {
    return json(groupsAnswer);
  }
  if (action == "grant" || action == "revoke") {
    return json('{"grants": []}');
  }
  if (action == "group" || action == "ungroup") {
    return json('{"groups": []}');
  }
  return http.Response("No such resource: ${pathOf(request)}", 404);
}

/// Shows the share dialog on `2024/Zoo` of the caller's own space.
Future<void> pumpShareDialog(
  WidgetTester tester,
  VAlbumClient client,
) async {
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: ShareDialog(client: client, path: const ["2024", "Zoo"]),
    ),
  ));
  await tester.pumpAndSettle();
}

/// Taps the widget with the given key, scrolling it into view first.
///
/// The share dialog is a scrolling list: the rights sit below the subjects,
/// and on the test surface they start off the bottom of the box.
Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

/// The settings of a device signed in as [user], over the injected transport.
ServerSettings signedInAs(String user) => ServerSettings(
      store: InMemorySettingsStore(),
      platformDefault: () => dataUrl,
      token: "$user-token",
      userName: user,
      loaded: true,
    );

/// The body of the last request of the given action.
String bodyOf(List<http.Request> requests, String action) => requests
    .lastWhere((request) => request.url.queryParameters["action"] == action)
    .body;

void main() {
  group('the share dialog', () {
    testWidgets('lists the grants and marks the inherited one', (tester) async {
      await pumpShareDialog(tester, shareClient(shareAnswers));

      expect(find.byKey(const Key("grant-user:bob")), findsOneWidget);
      expect(find.byKey(const Key("grant-group:family")), findsOneWidget);
      expect(find.textContaining("View, Download"), findsOneWidget);
      // The inherited one says where it was made, and offers no revoke here.
      expect(
        find.textContaining("inherited from the whole space, revoke it there"),
        findsOneWidget,
      );
      expect(find.byKey(const Key("revoke-user:bob")), findsOneWidget);
      expect(find.byKey(const Key("revoke-group:family")), findsNothing);
    });

    testWidgets('revokes a grant by its subject', (tester) async {
      var requests = <http.Request>[];
      await pumpShareDialog(
        tester,
        shareClient(shareAnswers, requests: requests),
      );

      await tapKey(tester, "revoke-user:bob");

      expect(bodyOf(requests, "revoke"), contains('"subject":"user:bob"'));
      expect(
        requests.any((request) =>
            request.url.queryParameters["action"] == "revoke" &&
            pathOf(request) == "/valbum/data/2024/Zoo/"),
        isTrue,
      );
    });

    testWidgets('offers the other users, the groups and everybody',
        (tester) async {
      await pumpShareDialog(tester, shareClient(shareAnswers));

      expect(find.byKey(const Key("subject-user:bob")), findsOneWidget);
      expect(find.byKey(const Key("subject-user:carol")), findsOneWidget);
      // Sharing with oneself is not a thing.
      expect(find.byKey(const Key("subject-user:alice")), findsNothing);
      expect(find.byKey(const Key("subject-group:family")), findsOneWidget);
      expect(find.byKey(const Key("subject-anonymous")), findsOneWidget);
      expect(find.byKey(const Key("anonymous-note")), findsOneWidget);
      // A group of one's own can be changed here, somebody else's cannot.
      expect(find.byKey(const Key("group-edit-family")), findsOneWidget);
      expect(find.byKey(const Key("group-edit-neighbours")), findsNothing);
    });

    testWidgets('grants what the check boxes show', (tester) async {
      var requests = <http.Request>[];
      await pumpShareDialog(
        tester,
        shareClient(shareAnswers, requests: requests),
      );

      await tapKey(tester, "subject-group:family");
      await tapKey(tester, "right-contribute");
      await tapKey(tester, "share-apply");

      var body = bodyOf(requests, "grant");
      expect(body, contains('"subject":"group:family"'));
      // The closure, which is what the boxes show: ticking "contribute" ticks
      // "view" in front of the user, so "view" travels along.
      expect(body, contains('"name":"contribute"'));
      expect(body, contains('"name":"view"'));
      expect(body, isNot(contains('"name":"edit"')));
    });

    testWidgets('ticking edit ticks the others and locks them', (tester) async {
      await pumpShareDialog(tester, shareClient(shareAnswers));

      await tapKey(tester, "right-edit");

      for (var right in const ["view", "download", "contribute"]) {
        var box = tester.widget<CheckboxListTile>(
          find.byKey(Key("right-$right")),
        );
        expect(box.value, isTrue, reason: right);
        expect(box.onChanged, isNull, reason: right);
      }
    });

    testWidgets('shows the server reason for a refused grant', (tester) async {
      var client = shareClient((request) {
        if (request.url.queryParameters["action"] == "grant") {
          return refusal(403, "This is not your library.");
        }
        return shareAnswers(request);
      });
      await pumpShareDialog(tester, client);

      await tapKey(tester, "subject-anonymous");
      await tapKey(tester, "share-apply");

      expect(find.text("This is not your library."), findsOneWidget);
    });
  });

  group('a group made to share', () {
    testWidgets('is created with its members and offered at once',
        (tester) async {
      var requests = <http.Request>[];
      var client = shareClient((request) {
        if (request.url.queryParameters["action"] == "group") {
          return json('{"groups": [{"name": "friends", "owner": "alice", '
              '"members": [{"name": "bob"}, {"name": "carol"}], '
              '"created": ""}]}');
        }
        return shareAnswers(request);
      }, requests: requests);
      await pumpShareDialog(tester, client);

      await tapKey(tester, "subject-new-group");
      await tester.enterText(find.byKey(const Key("group-name")), "friends");
      await tester.pumpAndSettle();
      await tapKey(tester, "member-bob");
      await tapKey(tester, "member-carol");
      await tapKey(tester, "group-save");

      var body = bodyOf(requests, "group");
      expect(body, contains('"name":"friends"'));
      expect(body, contains('"name":"bob"'));
      expect(body, contains('"name":"carol"'));
      // Selectable straight away, without the share dialog being reopened.
      expect(find.byKey(const Key("subject-group:friends")), findsOneWidget);
    });

    testWidgets('is removed where the caller owns it', (tester) async {
      var requests = <http.Request>[];
      await pumpShareDialog(
        tester,
        shareClient(shareAnswers, requests: requests),
      );

      await tapKey(tester, "group-remove-family");

      expect(bodyOf(requests, "ungroup"), contains('"name":"family"'));
      expect(find.byKey(const Key("subject-group:family")), findsNothing);
    });
  });

  group('an album shared with the caller', () {
    testWidgets('offers no way to change it and says whose it is',
        (tester) async {
      var client = clientHandling(
        (request) => json(albumWith(rightsField(const ["view", "download"]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute(["~alice", "2024", "Zoo"]),
        ));
        await tester.pumpAndSettle();

        // Nothing to add to, and the long press leads nowhere.
        expect(find.byTooltip("Upload"), findsNothing);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        expect(find.byTooltip("Save"), findsNothing);
        expect(find.byTooltip("Album properties"), findsNothing);
        expect(find.byTooltip("Move to…"), findsNothing);

        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("shared-line")), findsOneWidget);
        expect(
          find.text("Shared by alice — you may look and download"),
          findsOneWidget,
        );
        expect(find.text("Share with…"), findsNothing);
      });
    });

    testWidgets('offers the upload with contribute, and nothing else',
        (tester) async {
      var client = clientHandling(
        (request) => json(albumWith(rightsField(const ["contribute", "view"]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute(["~alice", "2024", "Zoo"]),
        ));
        await tester.pumpAndSettle();

        expect(find.byTooltip("Upload"), findsOneWidget);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        expect(find.byTooltip("Save"), findsNothing);

        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        expect(
          find.text("Shared by alice — you may add photos"),
          findsOneWidget,
        );
      });
    });

    testWidgets('is the album as before where every right is held',
        (tester) async {
      var client = clientHandling(
        (request) => json(albumWith(rightsField(const [
          "view",
          "download",
          "contribute",
          "edit",
        ]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          initialRoute: const ListingOrAlbumRoute(["Zoo"]),
        ));
        await tester.pumpAndSettle();

        expect(find.byTooltip("Upload"), findsOneWidget);
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();
        expect(find.byTooltip("Save"), findsOneWidget);
        expect(find.byTooltip("Album properties"), findsOneWidget);

        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();
        // Nothing is said about sharing: this is the caller's own album.
        expect(find.byKey(const Key("shared-line")), findsNothing);
      });
    });
  });

  group('a listing shared with the caller', () {
    testWidgets('offers only what may be done there', (tester) async {
      var client = clientHandling(
        (request) => json(listingWith(rightsField(const ["view"]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();

        expect(find.text("Create album"), findsNothing);
        expect(find.text("Create folder"), findsNothing);
        expect(find.text("Folder properties"), findsNothing);
        expect(find.text("Share with…"), findsNothing);
        expect(find.text("Reload"), findsOneWidget);
        expect(find.text("Server..."), findsOneWidget);
      });
    });

    testWidgets('offers the full menu with edit', (tester) async {
      var client = clientHandling(
        (request) => json(listingWith(rightsField(const ["edit"]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();

        expect(find.text("Create album"), findsOneWidget);
        expect(find.text("Create folder"), findsOneWidget);
        expect(find.text("Folder properties"), findsOneWidget);
        expect(find.text("Reload"), findsOneWidget);
      });
    });

    testWidgets('does not offer sharing where the server refuses the grants',
        (tester) async {
      // Signed in and holding every right, so the app does ask — and the
      // server says no. Nothing is offered, and nothing is said: a refused
      // probe is an answer to a question the user never asked.
      var client = VAlbumClient(
        dataUrl: dataUrl,
        token: "bob-token",
        userName: "bob",
        httpClient: MockClient(servingThumbnails((request) async {
          if (request.url.queryParameters["type"] == "grants") {
            return refusal(403, "These are not your grants.");
          }
          return json(listingWith(rightsField(const [
            "view",
            "download",
            "contribute",
            "edit",
          ])));
        })),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, settings: signedInAs("bob")),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();

        expect(find.text("Share with…"), findsNothing);
        expect(find.text("These are not your grants."), findsNothing);
      });
    });

    testWidgets('offers sharing where the server answers the grants',
        (tester) async {
      var client = VAlbumClient(
        dataUrl: dataUrl,
        token: "alice-token",
        userName: "alice",
        httpClient: MockClient(servingThumbnails((request) async {
          if (request.url.queryParameters["type"] == "grants") {
            return json('{"grants": []}');
          }
          return json(listingWith(rightsField(const [
            "view",
            "download",
            "contribute",
            "edit",
          ])));
        })),
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          VAlbumApp(client: client, settings: signedInAs("alice")),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byIcon(Icons.more_vert));
        await tester.pumpAndSettle();

        expect(find.text("Share with…"), findsOneWidget);
      });
    });
  });

  group('the way into the share dialog', () {
    /// A server answering the album (or the listing) and the share endpoints.
    VAlbumClient owningClient(String resource) => VAlbumClient(
          dataUrl: dataUrl,
          token: "alice-token",
          userName: "alice",
          httpClient: MockClient(servingThumbnails((request) async {
            var type = request.url.queryParameters["type"];
            if (type == "grants") {
              return json('{"grants": []}');
            }
            if (type == "users" || type == "groups") {
              return shareAnswers(request);
            }
            return json(resource);
          })),
        );

    testWidgets('is the album edit app bar', (tester) async {
      var client = owningClient(albumWith(rightsField(const ["edit"])));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          settings: signedInAs("alice"),
          initialRoute: const ListingOrAlbumRoute(["Zoo"]),
        ));
        await tester.pumpAndSettle();
        await tester.longPress(find.byType(Image).first);
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key("share-with")));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key("share-dialog")), findsOneWidget);
        expect(find.byKey(const Key("share-nobody")), findsOneWidget);
      });
    });

    testWidgets('is the long press on a listing tile', (tester) async {
      var client = owningClient(listingWith(rightsField(const ["edit"])));

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(
          client: client,
          settings: signedInAs("alice"),
        ));
        await tester.pumpAndSettle();
        await tester.longPress(find.text("Zoo"));
        await tester.pumpAndSettle();

        expect(find.text("Move to…"), findsOneWidget);
        expect(find.text("Share with…"), findsOneWidget);

        await tester.tap(find.text("Share with…"));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("share-dialog")), findsOneWidget);
      });
    });

    testWidgets('is not the long press where nothing may be done',
        (tester) async {
      // A caller who may only look has neither a move nor a share to make:
      // the tile shows no menu at all rather than an empty one.
      var client = clientHandling(
        (request) => json(listingWith(rightsField(const ["view"]))),
        dataUrl: dataUrl,
      );

      await withFakeImageHttp(() async {
        await tester.pumpWidget(VAlbumApp(client: client));
        await tester.pumpAndSettle();
        await tester.longPress(find.text("Zoo"));
        await tester.pumpAndSettle();

        expect(find.text("Move to…"), findsNothing);
        expect(find.text("Share with…"), findsNothing);
      });
    });
  });

  group('an original the server refuses', () {
    testWidgets('shows the reason in place of the picture', (tester) async {
      const message = "You may look at this album but not take copies.";
      // No fake image transport here: `Image.network` must fail, which is what
      // a refused original does in the field.
      var client = clientHandling(
        (request) {
          if (request.url.queryParameters["type"] == null) {
            return refusal(403, message);
          }
          return json(albumWith(rightsField(const ["view"])));
        },
        dataUrl: dataUrl,
      );

      await tester.pumpWidget(VAlbumApp(
        client: client,
        initialRoute: const ImageRoute(["~alice", "2024", "Zoo"], "a.jpg"),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key("image-refusal")), findsOneWidget);
      expect(find.text(message), findsOneWidget);
      expect(find.byType(ErrorWidget), findsNothing);
    });
  });
}
