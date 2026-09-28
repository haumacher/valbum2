// The web path of a video (issue #185): an HTML <video> element fetches its
// address itself and cannot send the bearer, so every address it is handed is
// signed first, and a refused or expired signature is asked for anew, once.

import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';

const String token = "device-secret-token";

const String album = "http://server/valbum/data/2026/Trip";

/// A player platform whose first [failures] players fail with [initError].
class FailingFirst extends FakeVideoPlayerPlatform {
  int failures;

  FailingFirst(this.failures);

  @override
  Future<int?> createWithOptions(VideoCreationOptions options) {
    failInit = failures > 0;
    if (failures > 0) {
      failures--;
    }
    return super.createWithOptions(options);
  }
}

/// The server of the test: answers `?type=media-url` with a new signature
/// each time, and records what it was asked.
class SigningServer {
  final List<http.Request> asked = [];

  int _issued = 0;

  late final VAlbumClient client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    token: token,
    httpClient: MockClient((request) async {
      asked.add(request);
      if (request.url.queryParameters["type"] != "media-url") {
        return http.Response("unexpected", 500);
      }
      _issued++;
      var media = "d.dev1.${1900000000 + _issued}.mac$_issued";
      // The plain object, as the server's serveJsonObject writes it.
      return http.Response(
        jsonEncode({
          "url": "${request.url.path}?media=$media",
          "media": media,
          "expires": DateTime.now()
              .add(const Duration(minutes: 10))
              .toUtc()
              .toIso8601String(),
        }),
        200,
        headers: {"content-type": "application/json"},
      );
    }),
  );
}

Future<VideoViewState> pumpSigned(
  WidgetTester tester,
  VAlbumClient client, {
  DiagnosticsLog? log,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      localizedApp(
        VideoView(
          videoUrl: "$album/clip.mp4",
          renditionUrl: client.playbackUrl("$album/clip.mp4"),
          probeRendition: (url) async =>
              const RenditionState(RenditionStatus.ready),
          posterUrl: "$album/clip.mp4?type=tn",
          headers: client.authHeaders,
          // The web decision, injected the way deriveDataUrl takes it.
          signUrl: client.mediaSigner(isWeb: true),
          log: log,
        ),
      ),
    );
    for (var i = 0; i < 10; i++) {
      await tester.pump();
    }
  });
  return tester.state<VideoViewState>(find.byType(VideoView));
}

void main() {
  group('the web decision', () {
    test('only the web with a token signs', () {
      var client = SigningServer().client;
      expect(client.mediaSigner(isWeb: true), isNotNull);
      expect(client.mediaSigner(isWeb: false), isNull,
          reason: "Off the web the player sends the header itself.");
      expect(client.withToken(null).mediaSigner(isWeb: true), isNull,
          reason: "An anonymous caller has nothing to sign for.");
    });

    test('the signed address carries media= and never the token', () async {
      var server = SigningServer();
      var signed =
          await server.client.signMediaUrl("$album/clip.mp4?type=video");

      expect(signed.url,
          "$album/clip.mp4?type=video&media=d.dev1.1900000001.mac1");
      expect(signed.url, isNot(contains(token)));
      expect(signed.expires, isNotNull);
      var request = server.asked.single;
      expect(request.url.queryParameters,
          {"type": "media-url", "for": "video"});
      expect(request.url.path, "/valbum/data/2026/Trip/clip.mp4");
      expect(request.headers["Authorization"], "Bearer $token",
          reason: "The app's own request carries the bearer.");

      await server.client.signMediaUrl("$album/clip.mp4");
      expect(server.asked.last.url.queryParameters["for"], "original");
      await server.client.signMediaUrl("$album/clip.mp4?type=teaser");
      expect(server.asked.last.url.queryParameters["for"], "teaser");
    });

    test('a refusal is the server\'s own sentence', () async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: token,
        httpClient: MockClient((request) async => http.Response(
            '["ErrorInfo",{"message":"You may look at this album but not '
            'download its original files."}]',
            403)),
      );
      await expectLater(
        client.signMediaUrl("$album/clip.mp4"),
        throwsA(isA<VAlbumException>()
            .having((e) => e.status, "status", 403)
            .having((e) => e.reason, "reason", contains("not download"))),
      );
    });
  });

  group('the player', () {
    testWidgets('is handed the signed address, and no token', (tester) async {
      var platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
      var server = SigningServer();

      var view = await pumpSigned(tester, server.client);

      expect(view.isPlayable, isTrue);
      var uri = platform.dataSources.single.uri!;
      expect(uri, contains("media="));
      expect(uri, contains("?type=video&media="));
      expect(uri, isNot(contains(token)));
      expect(view.playedUrls, [uri]);
    });

    testWidgets('a 410 makes exactly one renewal, and then it plays',
        (tester) async {
      var platform = FailingFirst(1)
        ..initError = PlatformException(
            code: "MEDIA_ERR_SRC_NOT_SUPPORTED", message: "410: Gone");
      VideoPlayerPlatform.instance = platform;
      var server = SigningServer();

      var view = await pumpSigned(tester, server.client);

      expect(server.asked, hasLength(2), reason: "Signed, then signed anew.");
      expect(view.playedUrls, [
        "$album/clip.mp4?type=video&media=d.dev1.1900000001.mac1",
        "$album/clip.mp4?type=video&media=d.dev1.1900000002.mac2",
      ]);
      expect(view.isPlayable, isTrue);
      expect(view.failure, isNull);
    });

    testWidgets('a 410 that stays is renewed once and then said',
        (tester) async {
      var platform = FailingFirst(99)
        ..initError = PlatformException(
            code: "MEDIA_ERR_SRC_NOT_SUPPORTED", message: "410: Gone");
      VideoPlayerPlatform.instance = platform;
      var server = SigningServer();
      var log = DiagnosticsLog();

      var view = await pumpSigned(tester, server.client, log: log);

      expect(server.asked, hasLength(2),
          reason: "Exactly one renewal, never a loop.");
      expect(view.playedUrls, hasLength(2));
      expect(view.failure, isNotNull);
      var text = log.copyText();
      expect(text, contains("Media address: signed for the browser's player"));
      expect(text, contains("asked for anew once"));
      expect(text, isNot(contains("mac2")),
          reason: "A signature is a credential; the log never shows it.");
    });

    testWidgets('a refused signature is the server\'s answer',
        (tester) async {
      var platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: token,
        httpClient: MockClient((request) async => http.Response(
            '["ErrorInfo",{"message":"You may not see this image."}]', 403)),
      );

      var view = await pumpSigned(tester, client);

      expect(platform.dataSources, isEmpty,
          reason: "Nothing is handed to the player.");
      expect(view.failure?.kind, VideoFailureKind.refused);
      expect(view.failure?.answer?.status, 403);
    });
  });

  group('the teaser', () {
    testWidgets('is signed too', (tester) async {
      var platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
      var server = SigningServer();

      await tester.pumpWidget(localizedApp(
        VideoTeaser(
          teaserUrl: server.client.teaserUrl("$album/clip.mp4"),
          headers: server.client.authHeaders,
          signUrl: server.client.mediaSigner(isWeb: true),
          probeTeaser: (url) async =>
              const RenditionState(RenditionStatus.ready),
          enabled: true,
          child: const SizedBox(width: 100, height: 100),
        ),
      ));
      var gesture =
          await tester.createGesture(kind: PointerDeviceKind.mouse);
      await gesture.addPointer(location: Offset.zero);
      await gesture.moveTo(tester.getCenter(find.byType(VideoTeaser)));
      for (var i = 0; i < 10; i++) {
        await tester.pump();
      }

      var uri = platform.dataSources.single.uri!;
      expect(uri, "$album/clip.mp4?type=teaser&media=d.dev1.1900000001.mac1");
      expect(uri, isNot(contains(token)));
      await gesture.removePointer();
    });
  });

  group('the diagnosis (#185)', () {
    test('a 401 of the platform on a delivered file is the missing sign-in',
        () {
      var kind = VideoFailure.classify(
        answer: const SourceAnswer(status: 206, contentType: "video/mp4"),
        problem: PlatformException(
          code: "MEDIA_ERR_SRC_NOT_SUPPORTED",
          message: "401: Unauthorized",
          details: "The video has been found to be unsuitable (missing or in "
              "a format not supported by your browser).",
        ),
      );
      expect(kind, VideoFailureKind.signInMissing);
      var failure = VideoFailure(
          kind: kind,
          source: VideoSource.rendition,
          answer: const SourceAnswer(status: 206));
      expect(failure.diagnosis,
          "the browser fetched the video without the sign-in and was refused");
      expect(failure.sentence(testL10n),
          "The browser fetched the video without the sign-in and was refused.");
    });

    test('a 403 too, and a size is no status', () {
      expect(problemNamesStatus("403: Forbidden", signInStatuses), isTrue);
      expect(problemNamesStatus("3096592 bytes, 4010 ms", signInStatuses),
          isFalse);
      expect(
          VideoFailure.classify(
            answer: const SourceAnswer(status: 206),
            problem: "The video has been found to be unsuitable",
          ),
          VideoFailureKind.format);
    });

    test('the log masks a signature', () {
      expect(
        maskUrl("$album/clip.mp4?type=video&media=d.dev1.1900000001.abcdefgh"),
        "$album/clip.mp4?type=video&media=d.${"•" * 22}gh",
      );
    });
  });
}
