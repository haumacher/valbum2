/// Tests of what a video that does not play says, and what it writes down
/// (issue #184).
///
/// The author's verdict on the message before this: "the video player says
/// nothing at all", and on the log: it held "only GET calls". Each case here
/// is driven the way the app drives it — a [VAlbumClient] over a [MockClient]
/// answering like the server of issue #74, and the fake platform player —
/// and each asserts both halves: the sentence the person reads, and the one
/// entry with its facts.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';

const String dataUrl = "http://server/valbum/data";

/// The video of the tests; the name carries a blank, so the request path is
/// percent-encoded the way the app builds it.
const String videoName = "Sommer 2024.mov";
const String imageUrl = "$dataUrl/album/Sommer%202024.mov";

/// An `ErrorInfo` body as the server sends it.
String errorBody(String message) => '["ErrorInfo",{"message":"$message"}]';

/// What the server's `VideoRenditions` answers while it transcodes.
const String pendingMessage =
    "This video is being prepared; please try again shortly.";

/// What it answers once the transcode failed, reason included (issue #184).
const String failedMessage = "This video cannot be prepared for playback. "
    "IOException: FFmpeg failed with exit code 1: Invalid data found when "
    "processing input.";

/// The first byte of a file of [total] bytes, as a ranged answer.
http.Response firstByte(String type, int total, {int status = 206}) =>
    http.Response(
      "x",
      status,
      headers: {
        "content-type": type,
        "content-length": status == 200 ? "$total" : "1",
        if (status == 206) "content-range": "bytes 0-0/$total",
      },
    );

/// A request the view made, as the test sees it.
typedef Asked = List<String>;

/// Pumps a [VideoView] the way the viewer builds one, over a client that
/// answers with [answer]; returns the log.
Future<DiagnosticsLog> pumpServed(
  WidgetTester tester,
  Future<http.Response> Function(http.Request request) answer, {
  required Asked asked,
  Future<void> Function(WidgetTester tester)? then,
}) async {
  var log = DiagnosticsLog();
  var client = VAlbumClient(
    dataUrl: dataUrl,
    token: "device-token",
    log: log,
    httpClient: MockClient((request) async {
      // The raw query and the encoded path, as they go over the wire.
      asked.add("${request.method} ${request.url}");
      return answer(request);
    }),
  );
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: client.originalUrl(imageUrl),
          renditionUrl: client.playbackUrl(imageUrl),
          probeRendition: client.renditionState,
          posterUrl: client.thumbnailUrl(imageUrl),
          headers: client.authHeaders,
          log: client.log,
          part: ImagePart(
            name: videoName,
            kind: ImageKind.quicktime,
            width: 1920,
            height: 1080,
          ),
          album: albumLabel(dataUrl, "$dataUrl/album"),
        ),
      ),
    );
    await settle(tester);
    await then?.call(tester);
  });
  return log;
}

/// Lets the view's chain of futures run out, without moving the clock.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.pump();
  }
}

/// The text of the widget with [key].
String textOf(WidgetTester tester, String key) =>
    tester.widget<Text>(find.byKey(Key(key))).data!;

bool isRendition(http.Request request) =>
    request.url.queryParameters["type"] == "video";

void main() {
  late FakeVideoPlayerPlatform platform;

  setUp(() {
    platform = FakeVideoPlayerPlatform();
    VideoPlayerPlatform.instance = platform;
  });

  testWidgets(
      'a rendition still being made: says so, asks again after Retry-After, '
      'gives up after six asks with one entry', (tester) async {
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => http.Response(
        errorBody(pendingMessage),
        202,
        headers: const {
          "content-type": "application/json",
          "retry-after": "7",
        },
      ),
      asked: asked,
      then: (tester) async {
        expect(
          textOf(tester, "video-preparing-line"),
          "The playable version of this video is still being made.",
        );
        expect(
          textOf(tester, "video-preparing-retry"),
          "Asking again in 7 seconds (2 of 6).",
        );
        expect(asked, hasLength(1));

        // Not a moment before the server's Retry-After.
        await tester.pump(const Duration(seconds: 6));
        await settle(tester);
        expect(asked, hasLength(1));
        await tester.pump(const Duration(seconds: 1));
        await settle(tester);
        expect(asked, hasLength(2));
        expect(
          textOf(tester, "video-preparing-retry"),
          "Asking again in 7 seconds (3 of 6).",
        );

        // The rest of the minute.
        for (var i = 0; i < 4; i++) {
          await tester.pump(const Duration(seconds: 7));
          await settle(tester);
        }
      },
    );

    // Six asks of the rendition and not one more: bounded, never a storm.
    expect(asked, hasLength(6));
    expect(asked.every((line) => line.endsWith("?type=video")), isTrue);
    expect(platform.dataSources, isEmpty, reason: "nothing was played");

    // The message says the main thing and offers the two ways on.
    expect(
      textOf(tester, "video-error-headline"),
      "The playable version is still being made — try again in a minute.",
    );
    expect(find.byKey(const Key("video-try-again")), findsOneWidget);
    expect(find.byKey(const Key("video-play-original")), findsOneWidget);
    expect(
      textOf(tester, "video-error-diagnostics"),
      testL10n.videoDiagnosticsHint,
    );
    expect(find.byKey(const Key("video-loading")), findsNothing);

    // One entry, with its facts.
    expect(log.entries, hasLength(1));
    var entry = log.entries.single;
    expect(entry.headline,
        "Video could not be played: $videoName in 'album'");
    var facts = entry.facts.join("\n");
    expect(facts, contains("Tried: the playable version (?type=video)"));
    expect(facts, contains("URL: $imageUrl?type=video"));
    expect(facts, contains("Server answered: 202, Content-Type application/json"));
    expect(facts, contains("Server said: $pendingMessage"));
    expect(facts, contains("Asked: 6 times"));
    expect(facts, contains("(7 s)"));
    expect(facts, contains("Original: $videoName (extension .mov, kind "
        "quicktime, 1920×1080"));
    expect(facts, contains("Platform error: none reported"));
    expect(facts, contains("Platform: "));
    expect(facts, contains("Diagnosis: the server was still making the "
        "playable version after 6 asks"));
    expect(entry.message, isNot(contains("device-token")));
  });

  testWidgets(
      'a failed conversion: the reason in the message and the entry, the '
      'original tried and refused too', (tester) async {
    platform
      ..failInit = true
      ..initError = PlatformException(
        code: "MEDIA_ERR_SRC_NOT_SUPPORTED",
        message: "The element has no supported sources.",
      );
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => isRendition(request)
          ? http.Response(
              errorBody(failedMessage),
              500,
              headers: const {"content-type": "application/json"},
            )
          : firstByte("video/quicktime", 5000000),
      asked: asked,
    );

    // The rendition, then the original the player refused, asked once.
    expect(asked, [
      "GET $imageUrl?type=video",
      "GET $imageUrl",
    ]);
    expect(platform.dataSources.single.uri, imageUrl);

    expect(
      textOf(tester, "video-error-headline"),
      "The server could not convert this video: $failedMessage",
    );
    expect(
      textOf(tester, "video-error-hint"),
      "This browser or device cannot play this format (video/quicktime).",
    );

    expect(log.entries, hasLength(1));
    var facts = log.entries.single.facts.join("\n");
    expect(facts, contains("Tried: the original, because the playable "
        "version was refused"));
    expect(facts, contains("URL: $imageUrl"));
    expect(facts, contains("Server answered: 206, Content-Type "
        "video/quicktime, Content-Length 1, of 5000000 bytes (4.8 MB)"));
    expect(facts, contains("Playable version before: Server answered: 500"));
    expect(facts, contains(failedMessage));
    expect(facts, contains("5000000 bytes"));
    expect(facts, contains("Platform error: code MEDIA_ERR_SRC_NOT_SUPPORTED, "
        "message The element has no supported sources."));
    expect(facts, contains("Diagnosis: the server delivered the file and the "
        "platform refused it"));
  });

  testWidgets(
      'a failed conversion whose original plays: said over the video, and '
      'written down once', (tester) async {
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => isRendition(request)
          ? http.Response(errorBody(failedMessage), 500)
          : firstByte("video/quicktime", 5000000),
      asked: asked,
    );

    expect(platform.dataSources.single.uri, imageUrl);
    expect(find.byKey(const Key("video-controls")), findsOneWidget);
    expect(find.byKey(const Key("video-error")), findsNothing);
    var notice = find.byKey(const Key("video-fallback-notice"));
    expect(notice, findsOneWidget);
    expect(
      find.descendant(
        of: notice,
        matching: find.text(
          "The server could not convert this video: $failedMessage "
          "The original is played instead.",
        ),
      ),
      findsOneWidget,
    );
    // A video that plays needs no second request.
    expect(asked, ["GET $imageUrl?type=video"]);

    expect(log.entries, hasLength(1));
    var entry = log.entries.single;
    expect(entry.headline, startsWith("Video: the playable version was "
        "refused, the original plays instead"));
    expect(entry.facts.join("\n"), contains(failedMessage));

    await tester.tap(find.byKey(const Key("video-fallback-dismiss")));
    await tester.pump();
    expect(notice, findsNothing);
  });

  testWidgets(
      'a playable answer the platform refuses: the format named, the entry '
      'with status, type, length and platform error', (tester) async {
    platform
      ..failInit = true
      ..initError = PlatformException(
        code: "MEDIA_ERR_SRC_NOT_SUPPORTED",
        message: "Format error",
      );
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => isRendition(request)
          ? firstByte("video/mp4", 1234567, status: 200)
          : firstByte("video/quicktime", 98765432),
      asked: asked,
    );

    expect(platform.dataSources.single.uri, "$imageUrl?type=video");
    expect(
      textOf(tester, "video-error-headline"),
      "This browser or device cannot play this format (video/mp4).",
    );
    expect(find.byKey(const Key("video-error-hint")), findsNothing);
    expect(
      textOf(tester, "video-error-diagnostics"),
      testL10n.videoDiagnosticsHint,
    );
    // Never the platform's own words on the screen.
    expect(find.textContaining("MEDIA_ERR"), findsNothing);

    // The rendition's answer is known from the probe that chose it; the one
    // request after the failure learns the original's size.
    expect(asked, ["GET $imageUrl?type=video", "GET $imageUrl"]);
    expect(log.entries, hasLength(1));
    var facts = log.entries.single.facts;
    expect(facts, contains("Tried: the playable version (?type=video), "
        "because the server has a playable version ready"));
    expect(facts, contains("Server answered: 200, Content-Type video/mp4, "
        "Content-Length 1234567, of 1234567 bytes (1.2 MB)"));
    expect(facts, contains("Original: $videoName (extension .mov, kind "
        "quicktime, 1920×1080, 98765432 bytes (94.2 MB))"));
    expect(facts, contains("Platform error: code MEDIA_ERR_SRC_NOT_SUPPORTED, "
        "message Format error"));
    expect(
      facts.firstWhere((fact) => fact.startsWith("Player: ")),
      startsWith("Player: not initialized"),
    );
  });

  testWidgets(
      'a player that never starts: after ten seconds the message and exactly '
      'one entry, and no spinner left', (tester) async {
    platform.neverInit = true;
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => isRendition(request)
          ? firstByte("video/mp4", 2000000)
          : firstByte("video/quicktime", 9000000),
      asked: asked,
      then: (tester) async {
        // Opened, and waiting: a spinner, bounded.
        expect(find.byKey(const Key("video-loading")), findsOneWidget);
        await tester.pump(const Duration(seconds: 9));
        await settle(tester);
        expect(find.byKey(const Key("video-error")), findsNothing);
        expect(entriesOf(tester), isEmpty);

        await tester.pump(const Duration(seconds: 1));
        await settle(tester);

        // A minute later nothing more has happened.
        await tester.pump(const Duration(seconds: 60));
        await settle(tester);
      },
    );

    expect(
      textOf(tester, "video-error-headline"),
      "The video did not start within 10 seconds.",
    );
    expect(
      textOf(tester, "video-error-hint"),
      "The server delivers it (video/mp4), but the player neither started "
      "nor reported an error.",
    );
    expect(find.byKey(const Key("video-loading")), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    // The controller disposes asynchronously, off the fake clock.
    await tester.runAsync(() async {});
    expect(platform.calls, contains("dispose"),
        reason: "the silent player is let go");

    expect(log.entries, hasLength(1));
    var facts = log.entries.single.facts.join("\n");
    expect(facts, contains("Server answered: 206, Content-Type video/mp4"));
    expect(facts, contains("Player: not initialized, not buffering, duration "
        "unknown"));
    expect(facts, contains("Waited: 10 s"));
    expect(facts, contains("Platform error: none reported"));
    expect(facts, contains("Diagnosis: the player neither started nor failed "
        "within 10 s"));
    expect(asked, ["GET $imageUrl?type=video", "GET $imageUrl"]);
  });

  testWidgets('a video that plays writes nothing', (tester) async {
    var asked = <String>[];
    var log = await pumpServed(
      tester,
      (request) async => firstByte("video/mp4", 2000000),
      asked: asked,
      then: (tester) async {
        await tester.pump(const Duration(seconds: 30));
        await settle(tester);
      },
    );

    expect(find.byKey(const Key("video-controls")), findsOneWidget);
    expect(find.byKey(const Key("video-error")), findsNothing);
    expect(log.isEmpty, isTrue);
    expect(asked, ["GET $imageUrl?type=video"]);
  });
}

/// The log's entries while the test is still inside [pumpServed].
List<DiagnosticsEntry> entriesOf(WidgetTester tester) => tester
    .state<VideoViewState>(find.byType(VideoView))
    .widget
    .log!
    .entries;
