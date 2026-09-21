/// The single image viewer, pumped on its own over an album built in memory.
///
/// Shared by the tests of what a visitor sees in the viewer (issues #95, #96
/// and #101): they all need the same three things — an album whose images are
/// linked as the loader links them, a caller (a member, or a share link), and
/// the viewer following the image it navigates to.
library;

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/resource.dart';

import 'fake_image_http.dart';
import 'fixtures.dart';
import 'l10n.dart';

export 'fake_image_http.dart' show fakeImageRequests, withFakeImageHttp;

/// The server the viewer tests talk to.
const String viewerDataUrl = "http://server/valbum/data";

/// The URL of the album the viewer shows images of.
const String viewerBaseUrl = "$viewerDataUrl/album";

/// An image of an album.
ImagePart viewerImagePart(
  String name, {
  ImageKind kind = ImageKind.image,
  String contributor = "",
  String contributorLabel = "",
}) =>
    ImagePart(
      name: name,
      kind: kind,
      contributor: contributor,
      contributorLabel: contributorLabel,
      width: 2000,
      height: 1000,
    );

/// Links the given images into an album carrying the given rights, as loading
/// an album does (see `AlbumInitializer`).
AlbumInfo viewerAlbum(
  List<AbstractImage> images, {
  List<String> rights = const [],
}) {
  var album = AlbumInfo(
    title: "Album",
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
      for (var j = 0; j < image.images.length; j++) {
        var member = image.images[j];
        member.owner = album;
        member.group = image;
        member.previous = j > 0 ? image.images[j - 1] : null;
        member.next = j < image.images.length - 1 ? image.images[j + 1] : null;
        member.home = image.images.first;
        member.end = image.images.last;
      }
    }
  }
  return album;
}

/// A share session as the app runs in one, see `share_session.dart`.
ShareSession viewerShareSession({
  String label = "For grandma",
  List<String> rights = const ["view"],
}) =>
    ShareSession(
      url: const SessionUrl(
        kind: SessionKind.share,
        token: "t0ken",
        dataUrl: viewerDataUrl,
        basePath: "/valbum/s/t0ken/",
      ),
      info: ShareInfo(
        label: label,
        rights: [for (var name in rights) RightName(name: name)],
        path: "~alice/Party",
      ),
      writeAllowed: false,
    );

/// A client serving thumbnails and recording every request it was given.
///
/// The recorder of `clientHandling` sits behind [servingThumbnails] and never
/// sees a thumbnail; the tests here are about exactly those requests.
VAlbumClient recordingViewerClient(List<http.Request> requests) => VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(
            transparentPixelPng,
            200,
            headers: {"content-type": "image/png"},
          );
        }
        return http.Response("No such resource: ${request.url.path}", 404);
      }),
    );

/// Forgets every decoded picture, so that one test never answers the next.
///
/// Flutter's [ImageCache] is global: a picture a test loaded successfully is
/// handed to the next test that names the same URL, and a test about a
/// *failing* picture would then see the previous one's success.
void withEmptyImageCache() {
  var cache = PaintingBinding.instance.imageCache;
  cache.clear();
  cache.clearLiveImages();
  addTearDown(() {
    cache.clear();
    cache.clearLiveImages();
  });
}

/// The paths of the thumbnails among the given requests.
List<String> thumbnailPathsOf(List<http.Request> requests) => [
      for (var request in requests)
        if (isThumbnailRequest(request)) request.url.path,
    ];

/// Who a signed-in member is.
CallerInfo viewerMember(String name) =>
    CallerInfo(userName: name, role: roleMember, space: name);

/// The viewer, following the image it navigates to, as the app does.
class ViewerHarness extends StatefulWidget {
  final AbstractImage initial;
  final VAlbumClient client;

  const ViewerHarness(this.initial, {super.key, required this.client});

  @override
  State<ViewerHarness> createState() => ViewerHarnessState();
}

class ViewerHarnessState extends State<ViewerHarness> {
  late AbstractImage image = widget.initial;

  @override
  Widget build(BuildContext context) => ImageView(
        client: widget.client,
        baseUrl: viewerBaseUrl,
        image: image,
        // A group shows all of its alternatives, see `GroupDetailView`.
        minRating: image is ImagePart && (image as ImagePart).group != null
            ? noMinRating
            : null,
        onShowImage: (next) => setState(() => image = next),
        onUp: () => Navigator.maybePop(context),
      );
}

/// Pumps the viewer showing [initial] as the second route of an app, signed in
/// as [caller] and, where given, inside a share session.
///
/// What the originals answer is set up by [fakeImageRequests].
Future<void> pumpViewerHarness(
  WidgetTester tester,
  AbstractImage initial, {
  required VAlbumClient client,
  CallerInfo? caller,
  ShareSession? share,
}) async {
  await withFakeImageHttp(
    () async {
      await tester.pumpWidget(
        CallerScope(
          caller: caller,
          child: ShareSessionScope(
            session: share,
            child: MaterialApp(
              localizationsDelegates: testLocalizationsDelegates,
              supportedLocales: testSupportedLocales,
              home: Builder(
                builder: (context) => Scaffold(
                  body: TextButton(
                    child: const Text("open"),
                    onPressed: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (context) =>
                            ViewerHarness(initial, client: client),
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
    },
  );
  await letImagesDecode(tester);
}

/// Runs [act] on the pumped viewer and lets the pictures really decode.
///
/// The decoding needs the real event loop, which only runs inside
/// [WidgetTester.runAsync]; the pumping needs the test's own clock, which only
/// runs outside it — the trap of issue #93. The two therefore alternate until
/// nothing is pending any more.
Future<void> settleViewer(
  WidgetTester tester,
  Future<void> Function() act,
) async {
  await withFakeImageHttp(() async {
    await act();
    await tester.pumpAndSettle();
  });
  await letImagesDecode(tester);
}

/// Alternates the real event loop and the test clock, see [settleViewer].
Future<void> letImagesDecode(WidgetTester tester) async {
  for (var round = 0; round < 5; round++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 20)),
    );
    await withFakeImageHttp(() => tester.pumpAndSettle());
  }
}

/// The provider of the picture the viewer shows, see `viewerPicture`.
ImageProvider shownPicture(WidgetTester tester) =>
    tester.widget<Image>(find.byKey(const Key("image-picture"))).image;

/// The URL the viewer's picture is fetched from.
String shownPictureUrl(WidgetTester tester) => providerUrl(shownPicture(tester));

/// The URL a provider of the viewer fetches from.
String providerUrl(ImageProvider provider) => switch (provider) {
      NetworkImage(url: var url) => url,
      _ => thumbnailOf(provider)?.url ?? "$provider",
    };
