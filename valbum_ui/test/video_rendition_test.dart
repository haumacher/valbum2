/// Tests of the playback rendition of issue #75: what the viewer plays, what
/// it says while the server is still making it, and what it falls back to.
///
/// The contract with the server (issue #74): `<image url>?type=video` answers
/// the rendition with a range (`200`/`206`), `202` with `RENDITION_PENDING`
/// and a `Retry-After` while it is being made, and `500` with
/// `RENDITION_FAILED` once it will never be there. The original stays what it
/// always was — the download, and the fallback.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';

/// The video of the tests, as the album addresses it.
const String imageUrl = "http://server/valbum/data/album/clip.mp4";
const String renditionUrl = "$imageUrl?type=video";

/// An `ErrorInfo` body as the server sends it.
String errorBody(String message) => '["ErrorInfo",{"message":"$message"}]';

/// A probe answering [states] in turn, remembering what it was asked.
class FakeProbe {
  /// The answers, in order; the last one is repeated.
  final List<RenditionState> states;

  /// Every URL that was probed, in order.
  final List<String> asked = [];

  FakeProbe(this.states);

  Future<RenditionState> call(String url) async {
    asked.add(url);
    return states[asked.length > states.length ? states.length - 1 : asked.length - 1];
  }
}

/// Pumps a [VideoView] that plays the rendition where it can.
///
/// The waits are collected instead of sat out, and answered at once: a
/// `Retry-After` of ten seconds is not something a test waits for.
Future<List<Duration>> pumpViewer(
  WidgetTester tester,
  FakeProbe probe, {
  bool autoPlay = true,
}) async {
  var waits = <Duration>[];
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: imageUrl,
          renditionUrl: renditionUrl,
          probeRendition: probe.call,
          posterUrl: "$imageUrl?type=tn",
          autoPlay: autoPlay,
          wait: (delay) async => waits.add(delay),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();
  });
  return waits;
}

void main() {
  group('the client asks the server about a rendition', () {
    test('builds the rendition and teaser URLs of issue #74', () {
      var client = VAlbumClient(dataUrl: "http://server/valbum/data");

      expect(client.playbackUrl(imageUrl), "$imageUrl?type=video");
      expect(client.teaserUrl(imageUrl), "$imageUrl?type=teaser");
      // The original is untouched: it is the download.
      expect(client.originalUrl(imageUrl), imageUrl);
    });

    test('asks for one byte, with the bearer, and reads 206 as ready',
        () async {
      var requests = <http.Request>[];
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        token: "dev-token",
        httpClient: MockClient((request) async {
          requests.add(request);
          return http.Response("", 206);
        }),
      );

      var state = await client.renditionState(renditionUrl);

      expect(state.status, RenditionStatus.ready);
      expect(requests.single.url.toString(), renditionUrl);
      expect(requests.single.method, "GET");
      expect(requests.single.headers["Range"], "bytes=0-0");
      expect(requests.single.headers["Authorization"], "Bearer dev-token");
    });

    test('reads a 200 as ready as well', () async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async => http.Response("x", 200)),
      );

      expect(
        (await client.renditionState(renditionUrl)).status,
        RenditionStatus.ready,
      );
    });

    test('reads a 202 as pending, with the server\'s own Retry-After',
        () async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async => http.Response(
              errorBody("The video is being prepared."),
              202,
              headers: {
                "content-type": "application/json",
                "retry-after": "10",
              },
            )),
      );

      var state = await client.renditionState(renditionUrl);

      expect(state.status, RenditionStatus.pending);
      expect(state.retryAfter, const Duration(seconds: 10));
      expect(state.message, "The video is being prepared.");
    });

    test('clamps a missing, unreadable or absurd Retry-After', () {
      expect(VAlbumClient.retryAfterOf(null), renditionRetryDefault);
      expect(VAlbumClient.retryAfterOf(""), renditionRetryDefault);
      // An HTTP-date is legal and depends on two clocks agreeing; the app
      // waits its own default instead of guessing.
      expect(
        VAlbumClient.retryAfterOf("Wed, 21 Oct 2026 07:28:00 GMT"),
        renditionRetryDefault,
      );
      expect(VAlbumClient.retryAfterOf("0"), renditionRetryFloor);
      expect(VAlbumClient.retryAfterOf("-5"), renditionRetryFloor);
      expect(VAlbumClient.retryAfterOf("86400"), renditionRetryCap);
      expect(VAlbumClient.retryAfterOf(" 7 "), const Duration(seconds: 7));
    });

    test('reads a 500 as unavailable and keeps the reason', () async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async => http.Response(
              errorBody("The video could not be converted."),
              500,
              headers: const {"content-type": "application/json"},
            )),
      );

      var state = await client.renditionState(renditionUrl);

      expect(state.status, RenditionStatus.unavailable);
      expect(state.message, "The video could not be converted.");
    });

    test('reads a server that cannot be reached as unavailable', () async {
      var client = VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient((request) async =>
            throw http.ClientException("Connection refused", request.url)),
      );

      expect(
        (await client.renditionState(renditionUrl)).status,
        RenditionStatus.unavailable,
        reason: "a question that cannot be asked is no reason to refuse",
      );
    });
  });

  group('the viewer', () {
    late FakeVideoPlayerPlatform platform;

    setUp(() {
      platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
    });

    testWidgets('plays the rendition where the server has it', (tester) async {
      var probe = FakeProbe([const RenditionState(RenditionStatus.ready)]);

      await pumpViewer(tester, probe);

      expect(probe.asked, [renditionUrl]);
      expect(platform.dataSources.single.uri, renditionUrl);
      expect(find.byKey(const Key("video-preparing")), findsNothing);
    });

    testWidgets('waits out a pending rendition and plays it when it is there',
        (tester) async {
      var probe = FakeProbe([
        const RenditionState(
          RenditionStatus.pending,
          retryAfter: Duration(seconds: 10),
        ),
        const RenditionState(RenditionStatus.ready),
      ]);

      var waits = await pumpViewer(tester, probe);

      // Asked twice, waiting exactly as long as the server asked for.
      expect(probe.asked, [renditionUrl, renditionUrl]);
      expect(waits, [const Duration(seconds: 10)]);
      // And it is the rendition that is playing, not the original.
      expect(platform.dataSources.single.uri, renditionUrl);
      expect(find.byKey(const Key("video-preparing")), findsNothing);
    });

    testWidgets('says that the video is being prepared while it waits',
        (tester) async {
      // Pending for ever: nobody answers the wait, so the line stays up.
      var probe = FakeProbe([
        const RenditionState(
          RenditionStatus.pending,
          retryAfter: Duration(seconds: 10),
        ),
      ]);
      var waits = <Duration>[];
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: imageUrl,
              renditionUrl: renditionUrl,
              probeRendition: probe.call,
              posterUrl: "$imageUrl?type=tn",
              // Never answers: the view stays in the waiting state.
              wait: (delay) {
                waits.add(delay);
                return Completer<void>().future;
              },
            ),
          ),
        );
        await tester.pump();
        await tester.pump();
      });

      expect(find.byKey(const Key("video-preparing")), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key("video-preparing-line"))).data,
        "The video is being prepared…",
      );
      expect(find.byKey(const Key("video-play-original")), findsOneWidget);
      // Nothing is played while it is being made, and nothing is an error.
      expect(platform.dataSources, isEmpty);
      expect(find.byKey(const Key("video-error")), findsNothing);
      expect(waits, [const Duration(seconds: 10)]);
    });

    testWidgets('plays the original at once when asked to', (tester) async {
      var probe = FakeProbe([
        const RenditionState(
          RenditionStatus.pending,
          retryAfter: Duration(seconds: 10),
        ),
      ]);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: imageUrl,
              renditionUrl: renditionUrl,
              probeRendition: probe.call,
              posterUrl: "$imageUrl?type=tn",
              wait: (delay) => Completer<void>().future,
            ),
          ),
        );
        await tester.pump();
        await tester.pump();

        await tester.tap(find.byKey(const Key("video-play-original")));
        await tester.pump();
        await tester.pump();
      });

      expect(platform.dataSources.single.uri, imageUrl);
      expect(find.byKey(const Key("video-preparing")), findsNothing);
    });

    testWidgets('falls back to the original when there will be no rendition',
        (tester) async {
      var probe = FakeProbe([
        const RenditionState(
          RenditionStatus.unavailable,
          message: "The video could not be converted.",
        ),
      ]);

      await pumpViewer(tester, probe);

      expect(platform.dataSources.single.uri, imageUrl);
      expect(find.byKey(const Key("video-preparing")), findsNothing);
      expect(find.byKey(const Key("video-error")), findsNothing);
    });

    testWidgets('plays the video straight away where nothing can be asked',
        (tester) async {
      // No probe: exactly what the view did before issue #75.
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          const MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: imageUrl,
              posterUrl: "$imageUrl?type=tn",
            ),
          ),
        );
        await tester.pump();
        await tester.pump();
      });

      expect(platform.dataSources.single.uri, imageUrl);
    });

    testWidgets('sends the bearer with the rendition it plays', (tester) async {
      var probe = FakeProbe([const RenditionState(RenditionStatus.ready)]);
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: imageUrl,
              renditionUrl: renditionUrl,
              probeRendition: probe.call,
              posterUrl: "$imageUrl?type=tn",
              headers: const {"Authorization": "Bearer dev-token"},
            ),
          ),
        );
        await tester.pump();
        await tester.pump();
      });

      expect(
        platform.dataSources.single.httpHeaders,
        containsPair("Authorization", "Bearer dev-token"),
      );
    });
  });
}
