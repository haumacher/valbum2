/// An original the platform cannot decode is shown by its display rendition
/// (issue #313).
///
/// The server reads a picture by its bytes (#312): a HEIC named `.jpg` has its
/// metadata, its preview and a `?type=display` JPEG. The app picks between the
/// original and the display rendition by the name, so it handed such a file's
/// HEIF bytes to a codec that cannot read them and showed the broken picture
/// of a photo the server can show. Now a failed **decode** of an original asks
/// for the display rendition once and shows it; only if that fails too is the
/// failure said — once. A failed transfer (a `500`) and a picture not made yet
/// (`202`, #256/#258) are no decode failures and keep their own ways.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/not_ready.dart';
import 'package:valbum_ui/thumbnails.dart';

import 'util/fake_image_http.dart' show heifBytes;
import 'util/fixtures.dart' show isThumbnailRequest;
import 'util/l10n.dart';
import 'util/viewer_harness.dart';
import 'viewer_pin_test.dart' show sharpPictureShown;

const String original = "$viewerBaseUrl/wrong.jpg";
const String display = "$viewerBaseUrl/wrong.jpg?type=display";

late List<http.Request> requests;
late List<String> fetched;

/// How often [url] was fetched by the platform.
int fetchesOf(String url) => fetched.where((each) => each == url).length;

/// Whether [url] is the original of `wrong.jpg`, not one of its renditions.
bool isWrongOriginal(Uri url) =>
    url.path.endsWith("/wrong.jpg") && url.query.isEmpty;

/// Whether [url] is the display rendition of `wrong.jpg`.
bool isWrongDisplay(Uri url) =>
    url.path.endsWith("/wrong.jpg") && url.queryParameters["type"] == "display";

/// Says nothing more about the picture: lets go of the viewer, so that no
/// re-ask timer outlives the test.
Future<void> closeViewer(WidgetTester tester) =>
    tester.pumpWidget(const SizedBox());

void main() {
  setUp(() {
    withEmptyImageCache();
    resetNotReady();
    requests = [];
    fetched = [];
  });

  group('isUndecodable', () {
    test('is a codec that could not read the bytes', () {
      expect(isUndecodable(Exception("Invalid image data")), isTrue);
      expect(isUndecodable(StateError("Codec failed to produce an image")),
          isTrue);
    });

    test('is no answer other than 200, no 202 and no transport failure', () {
      var uri = Uri.parse(original);
      expect(
          isUndecodable(NetworkImageLoadException(statusCode: 500, uri: uri)),
          isFalse);
      expect(
          isUndecodable(
              NetworkImageLoadException(statusCode: notReadyStatus, uri: uri)),
          isFalse);
      expect(isUndecodable(const ImageNotReady(original)), isFalse);
      expect(isUndecodable(TimeoutException("slow")), isFalse);
      expect(
          isUndecodable(
              Exception("Failed to load network image.\nImage URL: $original")),
          isFalse);
    });
  });

  testWidgets('an original no codec reads is shown by its display rendition',
      (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: fetched, undecodable: isWrongOriginal);
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));
    await letImagesDecode(tester);

    expect(shownPictureUrl(tester), display);
    expect(sharpPictureShown(tester), isTrue,
        reason: "the display rendition is decoded and painted");
    expect(find.byKey(const Key("image-failed")), findsNothing);
    expect(find.text(testL10n.pictureFailedMessage), findsNothing);
    expect(fetchesOf(original), 1, reason: "$fetched");
    expect(fetchesOf(display), 1, reason: "$fetched");
    expect(client.undecodableOriginals, {original});
  });

  testWidgets(
      'an original and a display rendition that both fail are said '
      'once', (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(
      requested: fetched,
      undecodable: (url) => isWrongOriginal(url) || isWrongDisplay(url),
    );

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    await letImagesDecode(tester);

    expect(shownPictureUrl(tester), display);
    expect(find.byKey(const Key("image-failed")), findsOneWidget);
    expect(find.text(testL10n.pictureFailedMessage), findsOneWidget);
    // The display rendition is asked once, and is not given up on for the
    // original again: no loop between the two.
    expect(fetchesOf(original), 1, reason: "$fetched");
    expect(fetchesOf(display), 1, reason: "$fetched");
    expect(find.byKey(const Key("image-thumbnail")), findsOneWidget,
        reason: "what the album showed stays beneath");
  });

  testWidgets(
      'an original the server failed to deliver asks for no display '
      'rendition', (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: fetched, failing: isWrongOriginal);
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));
    await letImagesDecode(tester);

    // A 500 is the transfer failing, not the codec: the display rendition
    // would be refused by the same server, and the failure speaks as before.
    expect(shownPictureUrl(tester), original);
    expect(fetchesOf(display), 0, reason: "$fetched");
    expect(fetchesOf(original), 1, reason: "$fetched");
    expect(find.byKey(const Key("image-failed")), findsOneWidget);
    expect(client.undecodableOriginals, isEmpty);
  });

  testWidgets('a display rendition not made yet keeps the way of #256/#258',
      (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(
      requested: fetched,
      undecodable: isWrongOriginal,
      notReady: isWrongDisplay,
    );

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    await letImagesDecode(tester);

    // The fallback asked for the display rendition, the server has not made
    // it yet: no failure, the thumbnail stands in, the re-ask is armed.
    expect(shownPictureUrl(tester), display);
    expect(find.byKey(const Key("image-failed")), findsNothing);
    expect(find.byType(SnackBar), findsNothing);
    expect(hasNotReadyImages, isTrue);
    expect(fetchesOf(original), 1, reason: "$fetched");
    await closeViewer(tester);
  });

  testWidgets('an original not made yet is no decode failure', (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: fetched, notReady: isWrongOriginal);
    var client = recordingViewerClient(requests);

    await pumpViewerHarness(tester, images[0],
        client: client, caller: viewerMember("bob"));
    await letImagesDecode(tester);

    expect(shownPictureUrl(tester), original);
    expect(fetchesOf(display), 0, reason: "$fetched");
    expect(find.byKey(const Key("image-failed")), findsNothing);
    expect(hasNotReadyImages, isTrue);
    expect(client.undecodableOriginals, isEmpty);
    await closeViewer(tester);
  });

  testWidgets('paging away and back asks the failed original no second time',
      (tester) async {
    var images = [viewerImagePart("wrong.jpg"), viewerImagePart("b.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: fetched, undecodable: isWrongOriginal);

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    await letImagesDecode(tester);
    expect(shownPictureUrl(tester), display);

    await pageViewer(tester, LogicalKeyboardKey.arrowRight);
    await letImagesDecode(tester);
    expect(shownPictureUrl(tester), "$viewerBaseUrl/b.jpg");

    await pageViewer(tester, LogicalKeyboardKey.arrowLeft);
    await letImagesDecode(tester);
    expect(shownPictureUrl(tester), display);
    expect(sharpPictureShown(tester), isTrue);
    expect(find.byKey(const Key("image-failed")), findsNothing);
    expect(fetchesOf(original), 1, reason: "$fetched");
  });

  testWidgets(
      'a neighbour whose original fails in the prefetch is paged to '
      'by its display rendition', (tester) async {
    var images = [viewerImagePart("b.jpg"), viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view", "download"]);
    fakeImageRequests(requested: fetched, undecodable: isWrongOriginal);

    await pumpViewerHarness(tester, images[0],
        client: recordingViewerClient(requests), caller: viewerMember("bob"));
    await letImagesDecode(tester);

    await pageViewer(tester, LogicalKeyboardKey.arrowRight);
    await letImagesDecode(tester);
    expect(shownPictureUrl(tester), display);
    expect(sharpPictureShown(tester), isTrue);
    expect(find.byKey(const Key("image-failed")), findsNothing,
        reason: "a failed prefetch is never said");
    expect(fetchesOf(original), 1, reason: "$fetched");
  });

  testWidgets('a share link without download is shown no display rendition',
      (tester) async {
    var images = [viewerImagePart("wrong.jpg")];
    viewerAlbum(images, rights: const ["view"]);
    fakeImageRequests(requested: fetched);
    // The preview the link may have arrives as bytes no codec reads.
    var client = VAlbumClient(
      dataUrl: viewerDataUrl,
      httpClient: MockClient((request) async {
        requests.add(request);
        if (isThumbnailRequest(request)) {
          return http.Response.bytes(heifBytes, 200,
              headers: {"content-type": "image/jpeg"});
        }
        return http.Response("No such resource", 404);
      }),
    );

    await pumpViewerHarness(tester, images[0],
        client: client, share: viewerShareSession());
    await letImagesDecode(tester);

    // As before #313: the preview is all this caller may have, and its
    // failure is said once; nothing the link does not permit is asked.
    expect(shownPicture(tester), isA<ThumbnailImage>());
    expect(fetched, isEmpty, reason: "neither original nor display");
    expect(
      requests.where((r) => r.url.queryParameters["type"] == "display"),
      isEmpty,
    );
    expect(find.byKey(const Key("image-failed")), findsOneWidget);
    expect(client.undecodableOriginals, isEmpty);
  });
}
