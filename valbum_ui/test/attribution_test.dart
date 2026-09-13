/// Tests of the uploader attribution (issue #53): the "Added by …" line in
/// the viewer and in the tile properties, and taking one's own contribution
/// back out of somebody else's album.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'util/fake_image_http.dart';
import 'util/fixtures.dart';

/// The server the tests talk to.
const String dataUrl = "http://server/valbum/data";

/// An image of an album, with the attribution the server derives.
ImagePart imagePart(
  String name, {
  String comment = "",
  String contributor = "",
  String contributorLabel = "",
}) =>
    ImagePart(
      name: name,
      comment: comment,
      contributor: contributor,
      contributorLabel: contributorLabel,
      width: 2000,
      height: 1000,
    );

/// Links the given images into an album carrying the given rights, as loading
/// an album does.
AlbumInfo linkedAlbum(
  List<AbstractImage> images, {
  List<String> rights = const [],
}) {
  var album = AlbumInfo(
    parts: images,
    rights: [for (var name in rights) RightName(name: name)],
  );
  for (var i = 0; i < images.length; i++) {
    var image = images[i];
    image.owner = album;
    image.previous = i > 0 ? images[i - 1] : null;
    image.next = i < images.length - 1 ? images[i + 1] : null;
    image.home = images.first;
    image.end = images.last;
    if (image is ImageGroup) {
      for (var member in image.images) {
        member.owner = album;
      }
    }
  }
  return album;
}

/// A share session as the app runs in one, see `share_session.dart`.
ShareSession shareSession() => ShareSession(
      url: const SessionUrl(
        kind: SessionKind.share,
        token: "t0ken",
        dataUrl: dataUrl,
        basePath: "/valbum/s/t0ken/",
      ),
      info: ShareInfo(
        label: "Party",
        rights: [RightName(name: "view"), RightName(name: "contribute")],
        path: "~alice/Party",
      ),
      writeAllowed: true,
    );

/// The viewer, following the image it navigates to, as the app does.
class ViewerHarness extends StatefulWidget {
  final AbstractImage initial;
  final VAlbumClient client;
  final List<String>? albumPath;

  const ViewerHarness(
    this.initial, {
    super.key,
    required this.client,
    this.albumPath,
  });

  @override
  State<ViewerHarness> createState() => ViewerHarnessState();
}

class ViewerHarnessState extends State<ViewerHarness> {
  late AbstractImage image = widget.initial;

  @override
  Widget build(BuildContext context) => ImageView(
        client: widget.client,
        baseUrl: "$dataUrl/album",
        image: image,
        albumPath: widget.albumPath,
        onShowImage: (next) => setState(() => image = next),
        // The app leaves the viewer by a route change; here the harness is
        // the second route of a test app, so "up" pops it.
        onUp: () => Navigator.maybePop(context),
      );
}

/// Pumps the viewer showing [initial] as the second route of an app, signed in
/// as [caller] and, where given, inside a share session.
Future<void> pumpViewer(
  WidgetTester tester,
  AbstractImage initial, {
  CallerInfo? caller,
  ShareSession? share,
  VAlbumClient? client,
  List<String>? albumPath,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      CallerScope(
        caller: caller,
        child: ShareSessionScope(
          session: share,
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  child: const Text("open"),
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (context) => ViewerHarness(
                        initial,
                        client: client ?? clientReturning("{}"),
                        albumPath: albumPath,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text("open"));
    await tester.pumpAndSettle();
  });
}

/// Who a signed-in member is.
CallerInfo member(String name) =>
    CallerInfo(userName: name, role: roleMember, space: name);

Finder get attribution => find.byKey(const Key("image-contributor"));

Finder get caption => find.byKey(const Key("image-caption"));

Finder get takeBack => find.byKey(const Key("image-take-back"));

/// The path of a request, with the percent-encoding of the wire undone.
String pathOf(http.BaseRequest request) => Uri.decodeFull(request.url.path);

/// An answer with the JSON content type the app expects.
http.Response json(String body, {int status = 200}) => http.Response(
      body,
      status,
      headers: {"content-type": "application/json; charset=utf-8"},
    );

void main() {
  group('the attribution line', () {
    test('names the contributor, and nobody without a label', () {
      expect(attributionLine("bob"), "Added by bob");
      expect(attributionShown(imagePart("a.jpg")), isNull);
      expect(
        attributionShown(imagePart("a.jpg", contributorLabel: "bob")),
        "Added by bob",
      );
    });

    testWidgets('is shown to somebody else than the contributor',
        (tester) async {
      var images = [
        imagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob"),
      ];
      linkedAlbum(images);

      await pumpViewer(tester, images[0], caller: member("carol"));

      expect(attribution, findsOneWidget);
      expect(find.text("Added by bob"), findsOneWidget);
    });

    testWidgets('is not shown to the contributor themself', (tester) async {
      var images = [
        imagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob"),
      ];
      linkedAlbum(images);

      await pumpViewer(tester, images[0], caller: member("bob"));

      expect(attribution, findsNothing);
      expect(caption, findsNothing);
    });

    testWidgets('is shown in a share session, which is no user',
        (tester) async {
      var images = [
        imagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob"),
      ];
      linkedAlbum(images);

      await pumpViewer(tester, images[0], share: shareSession());

      expect(attribution, findsOneWidget);
    });

    testWidgets('an image of an old album carries no caption at all',
        (tester) async {
      var images = [imagePart("a.jpg")];
      linkedAlbum(images);

      await pumpViewer(tester, images[0], caller: member("carol"));

      expect(attribution, findsNothing);
      expect(find.byKey(const Key("image-comment")), findsNothing);
      expect(caption, findsNothing);
    });

    testWidgets('stands above the comment, both in one caption',
        (tester) async {
      var images = [
        imagePart(
          "a.jpg",
          comment: "Am Morgen",
          contributor: "user:bob",
          contributorLabel: "bob",
        ),
      ];
      linkedAlbum(images);

      await pumpViewer(tester, images[0], caller: member("carol"));

      expect(caption, findsOneWidget);
      expect(find.descendant(of: caption, matching: attribution),
          findsOneWidget);
      expect(find.text("Am Morgen"), findsOneWidget);
      expect(
        tester.getTopLeft(attribution).dy,
        lessThan(tester.getTopLeft(find.text("Am Morgen")).dy),
      );
    });

    testWidgets('a group is attributed by its representative', (tester) async {
      var group = ImageGroup(
        representative: 1,
        images: [
          imagePart("a.jpg", contributor: "user:bob", contributorLabel: "bob"),
          imagePart("b.jpg",
              contributor: "user:alice", contributorLabel: "alice"),
        ],
      );
      linkedAlbum([group]);

      await pumpViewer(tester, group, caller: member("carol"));

      expect(find.text("Added by alice"), findsOneWidget);
    });
  });

  group('the tile properties', () {
    /// The album fixture with `landscape.jpg` contributed by bob.
    String contributedAlbum() => fixture("album.json").replaceFirst(
          '"name": "landscape.jpg"',
          '"name": "landscape.jpg", "contributor": "user:bob", '
              '"contributorLabel": "bob"',
        );

    Future<void> openProperties(WidgetTester tester, String body) async {
      await tester.pumpWidget(VAlbumApp(client: clientReturning(body)));
      await tester.pumpAndSettle();
      // The long press enters the edit mode with the tile pressed selected;
      // the tap beside the toolbars reaches the tile itself.
      await tester.longPress(find.byType(Image).first);
      await tester.pumpAndSettle();
      var tile = find.byKey(const ValueKey("landscape.jpg"));
      var box = tester.getRect(tile);
      await tester.tapAt(Offset(box.left + 8, box.center.dy));
      await tester.pumpAndSettle();
      await tester.tap(
        find.descendant(of: tile, matching: find.byIcon(Icons.notes)),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('name who added the image', (tester) async {
      await withFakeImageHttp(() async {
        await openProperties(tester, contributedAlbum());

        expect(find.text("Bildeigenschaften"), findsOneWidget);
        expect(
          find.byKey(const Key("properties-contributor")),
          findsOneWidget,
        );
        expect(find.text("Added by bob"), findsOneWidget);
      });
    });

    testWidgets('say nothing about an image that was never uploaded',
        (tester) async {
      await withFakeImageHttp(() async {
        await openProperties(tester, fixture("album.json"));

        expect(find.text("Bildeigenschaften"), findsOneWidget);
        expect(find.byKey(const Key("properties-contributor")), findsNothing);
      });
    });
  });

  group('taking a contribution back', () {
    ImagePart contributed({String contributor = "user:bob"}) =>
        imagePart("a.jpg", contributor: contributor, contributorLabel: "bob");

    testWidgets('is offered to the contributor who may not edit',
        (tester) async {
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute"]);

      await pumpViewer(tester, image,
          caller: member("bob"), albumPath: const ["album"]);

      expect(takeBack, findsOneWidget);
    });

    testWidgets('is not offered to somebody who may edit the album',
        (tester) async {
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute", "edit"]);

      await pumpViewer(tester, image,
          caller: member("bob"), albumPath: const ["album"]);

      expect(takeBack, findsNothing);
    });

    testWidgets('is not offered for somebody else\'s photo', (tester) async {
      var image = contributed(contributor: "user:alice");
      linkedAlbum([image], rights: const ["view", "contribute"]);

      await pumpViewer(tester, image,
          caller: member("bob"), albumPath: const ["album"]);

      expect(takeBack, findsNothing);
    });

    testWidgets('is not offered in a share session, which has no space',
        (tester) async {
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute"]);

      await pumpViewer(tester, image,
          caller: member("bob"),
          share: shareSession(),
          albumPath: const ["album"]);

      expect(takeBack, findsNothing);
    });

    testWidgets('is not offered to a guest, whose root holds no photos',
        (tester) async {
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute"]);

      await pumpViewer(
        tester,
        image,
        caller: const CallerInfo(userName: "bob", role: roleGuest),
        albumPath: const ["album"],
      );

      expect(takeBack, findsNothing);
    });

    testWidgets('posts the move the picker was confirmed with',
        (tester) async {
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute"]);

      var requests = <http.Request>[];
      var client = clientHandling(
        (request) {
          if (request.url.queryParameters["action"] == "move") {
            return json('{"outcomes":[{"name":"a.jpg","newName":"a.jpg",'
                '"message":""}]}');
          }
          return json(fixture("listing-move.json"));
        },
        dataUrl: dataUrl,
        requests: requests,
      );

      await pumpViewer(tester, image,
          caller: member("bob"), client: client, albumPath: const ["album"]);

      await withFakeImageHttp(() async {
        await tester.tap(takeBack);
        await tester.pumpAndSettle();
        expect(find.byKey(const Key("folder-picker")), findsOneWidget);

        await tester.tap(find.byKey(const Key("picker-folder-2021")));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("picker-confirm")));
        await tester.pumpAndSettle();
      });

      var moves = [
        for (var request in requests)
          if (request.url.queryParameters["action"] == "move") request,
      ];
      expect(moves, hasLength(1));
      expect(pathOf(moves.single), "/valbum/data/album/");
      expect(moves.single.body, contains('"target":"2021"'));
      expect(moves.single.body, contains('"name":"a.jpg"'));

      // The picker is gone and the photo is no longer where it was: the
      // viewer left it, back to the album.
      expect(find.byKey(const Key("folder-picker")), findsNothing);
      expect(find.byType(ImageView), findsNothing);
    });

    testWidgets('shows the server\'s own reason for a refusal', (tester) async {
      const refusal = "You may only move photos out of this album that you "
          "contributed yourself.";
      var image = contributed();
      linkedAlbum([image], rights: const ["view", "contribute"]);

      var client = clientHandling(
        (request) {
          if (request.url.queryParameters["action"] == "move") {
            return json('["ErrorInfo",{"message":"$refusal"}]', status: 403);
          }
          return json(fixture("listing-move.json"));
        },
        dataUrl: dataUrl,
      );

      await pumpViewer(tester, image,
          caller: member("bob"), client: client, albumPath: const ["album"]);

      await withFakeImageHttp(() async {
        await tester.tap(takeBack);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key("picker-confirm")));
        await tester.pumpAndSettle();
      });

      expect(find.text(refusal), findsOneWidget);
      // Nothing moved: the photo is still on the screen.
      expect(find.byType(ImageView), findsOneWidget);
    });
  });
}
