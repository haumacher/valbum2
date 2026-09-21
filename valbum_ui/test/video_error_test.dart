/// Tests of what a failed video says (issue #73): a plain sentence for the
/// person, the raw text in the diagnostics log.
///
/// What was reported from the phone was this, verbatim, on top of the poster:
/// `PlatformException(VideoError, Video player had error
/// com.google.android.exoplayer2.ExoPlaybackException: Source error …)`. That
/// says nothing to anybody holding a phone, and it is also the one thing a bug
/// report needs — so it moves from the screen into the log, and the screen
/// gets a sentence and, where the failure can be classified, one hint.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/l10n.dart';

/// The URL of the video the tests fail to play.
const String videoUrl = "http://192.168.1.9:8080/valbum/data/album/clip.mp4";

/// The failure `video_player` reports on Android when ExoPlayer cannot fetch
/// the video — the text of the report, shortened only where it repeats.
final PlatformException exoSourceError = PlatformException(
  code: "VideoError",
  message: "Video player had error "
      "com.google.android.exoplayer2.ExoPlaybackException: Source error",
);

/// A [VideoControllerFactory] that fails with [problem] before a controller
/// even exists.
VideoControllerFactory failingWith(Object problem) => (
      Uri url, {
      Map<String, String> headers = const {},
    }) =>
        throw problem;

/// Pumps a [VideoView] that cannot play, with a log it can write to.
Future<DiagnosticsLog> pumpFailing(
  WidgetTester tester,
  Object problem, {
  DiagnosticsLog? log,
}) async {
  var used = log ?? DiagnosticsLog();
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: videoUrl,
          posterUrl: "$videoUrl?type=tn",
          createController: failingWith(problem),
          log: used,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
  });
  return used;
}

/// Everything the failure box shows, joined.
String shownText(WidgetTester tester) => tester
    .widgetList<Text>(find.descendant(
      of: find.byKey(const Key("video-error")),
      matching: find.byType(Text),
    ))
    .map((text) => text.data ?? "")
    .join("\n");

void main() {
  group('the screen', () {
    testWidgets('says one plain sentence, never the platform exception',
        (tester) async {
      var log = await pumpFailing(tester, exoSourceError);

      expect(find.byKey(const Key("video-error")), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key("video-error-headline"))).data,
        "Cannot play this video.",
      );

      // Not one word of the platform's own text is on the screen.
      var shown = shownText(tester);
      expect(shown, isNot(contains("PlatformException")));
      expect(shown, isNot(contains("ExoPlaybackException")));
      expect(shown, isNot(contains("com.google.android")));
      expect(shown, isNot(contains("VideoError")));

      // And all of it is in the log, where a bug report can fetch it.
      var logged = [for (var entry in log.entries) entry.message].join("\n");
      expect(logged, contains("PlatformException"));
      expect(logged, contains("ExoPlaybackException"));
      expect(logged, contains("Source error"));
      expect(logged, contains(videoUrl));
    });

    testWidgets('keeps the URL that was refused', (tester) async {
      await pumpFailing(tester, exoSourceError);

      expect(
        tester.widget<Text>(find.byKey(const Key("video-error-url"))).data,
        videoUrl,
      );
    });

    testWidgets('says where the details are', (tester) async {
      await pumpFailing(tester, exoSourceError);

      expect(
        tester
            .widget<Text>(find.byKey(const Key("video-error-diagnostics")))
            .data,
        videoErrorDiagnosticsHint,
      );
    });

    testWidgets('hints at the server when the video never arrived',
        (tester) async {
      await pumpFailing(tester, exoSourceError);

      expect(
        tester.widget<Text>(find.byKey(const Key("video-error-hint"))).data,
        videoNetworkHint,
      );
    });

    testWidgets('hints at the format when the video cannot be decoded',
        (tester) async {
      await pumpFailing(
        tester,
        PlatformException(
          code: "VideoError",
          message: "Video player had error "
              "androidx.media3.exoplayer.ExoPlaybackException: "
              "Decoder init failed: OMX.google.h265.decoder",
        ),
      );

      expect(
        tester.widget<Text>(find.byKey(const Key("video-error-hint"))).data,
        videoFormatHint,
      );
    });

    testWidgets('offers no hint where it cannot tell, and still says the rest',
        (tester) async {
      await pumpFailing(tester, StateError("something went sideways"));

      expect(find.byKey(const Key("video-error-hint")), findsNothing);
      expect(find.byKey(const Key("video-error-headline")), findsOneWidget);
      expect(find.byKey(const Key("video-error-url")), findsOneWidget);
      expect(shownText(tester), isNot(contains("sideways")));
    });

    testWidgets('logs nothing and shows the sentence without a log',
        (tester) async {
      // A view pumped on its own has no log; that must not keep the refusal
      // from speaking.
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: videoUrl,
              posterUrl: "$videoUrl?type=tn",
              createController: failingWith(exoSourceError),
            ),
          ),
        );
        await tester.pump();
        await tester.pump();
      });

      expect(find.byKey(const Key("video-error-headline")), findsOneWidget);
    });
  });

  group('the box fits the slot it is given', () {
    // The rework of issue #73: four sentences, a long share-link URL and a
    // video tile that is a few dozen pixels tall do not fit each other, and a
    // striped overflow marker over the poster is not a refusal speaking. The
    // box scrolls inside the slot instead — and never truncates a sentence.
    const String longUrl =
        "http://nas.local:8080/valbum/s/SECRETTOKEN123/data/album/clip.mp4";

    /// Pumps the failing view on a surface of [width] x [height].
    Future<void> pumpAt(
      WidgetTester tester,
      double width,
      double height,
    ) async {
      await tester.binding.setSurfaceSize(Size(width, height));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await withFakeImageHttp(() async {
        await tester.pumpWidget(
          MaterialApp(
            localizationsDelegates: testLocalizationsDelegates,
            supportedLocales: testSupportedLocales,
            home: VideoView(
              videoUrl: longUrl,
              posterUrl: "$longUrl?type=tn",
              // A classified failure, so that all four lines are there: the
              // icon, the headline, the hint, the URL and the pointer.
              createController: failingWith(exoSourceError),
              log: DiagnosticsLog(),
            ),
          ),
        );
        await tester.pump();
        await tester.pump();
      });
    }

    testWidgets('on a slot the size of a video tile (320x240)',
        (tester) async {
      await pumpAt(tester, 320, 240);

      expect(
        tester.takeException(),
        isNull,
        reason: "nothing overflowed the slot",
      );
      // All four lines are there, and the headline is what the slot shows
      // first — it is the top of the scrollable content, so it is on screen
      // whatever the slot's height.
      expect(find.byKey(const Key("video-error-hint")), findsOneWidget);
      expect(find.byKey(const Key("video-error-url")), findsOneWidget);
      expect(find.byKey(const Key("video-error-diagnostics")), findsOneWidget);
      var headline = find.byKey(const Key("video-error-headline"));
      expect(headline, findsOneWidget);
      var box = tester.getRect(headline);
      expect(box.top, greaterThanOrEqualTo(0));
      expect(box.top, lessThan(240));

      // The panel stops at the slot's edge rather than growing past it.
      var panel = tester.getSize(find.byKey(const Key("video-error")));
      expect(panel.height, lessThanOrEqualTo(240));
      expect(panel.width, lessThanOrEqualTo(320));

      // What does not fit is reachable, not cut away.
      expect(find.byType(SingleChildScrollView), findsOneWidget);
    });

    testWidgets('on a phone-sized slot in portrait (360x640)', (tester) async {
      await pumpAt(tester, 360, 640);

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key("video-error-headline")), findsOneWidget);
      var panel = tester.getSize(find.byKey(const Key("video-error")));
      expect(panel.height, lessThanOrEqualTo(640));
    });

    testWidgets('on a wide slot (800x600)', (tester) async {
      await pumpAt(tester, 800, 600);

      expect(tester.takeException(), isNull);
      var headline = find.byKey(const Key("video-error-headline"));
      expect(headline, findsOneWidget);
      // Room enough here for the headline to stand whole on the screen.
      var box = tester.getRect(headline);
      expect(box.top, greaterThanOrEqualTo(0));
      expect(box.bottom, lessThanOrEqualTo(600));
      var panel = tester.getSize(find.byKey(const Key("video-error")));
      expect(panel.height, lessThanOrEqualTo(600));
    });

    testWidgets('truncates no sentence, whatever the slot', (tester) async {
      await pumpAt(tester, 320, 240);

      for (var key in const [
        Key("video-error-headline"),
        Key("video-error-hint"),
        Key("video-error-url"),
        Key("video-error-diagnostics"),
      ]) {
        var text = tester.widget<Text>(find.byKey(key));
        expect(
          text.overflow,
          isNot(TextOverflow.ellipsis),
          reason: "$key must be readable to its end",
        );
        expect(text.maxLines, isNull, reason: "$key is not cut off");
      }
    });
  });

  group('the classification', () {
    test('reads a source failure as the server not answering', () {
      for (var message in const [
        "ExoPlaybackException: Source error",
        "com.google.android.exoplayer2.upstream.HttpDataSource"
            "\$HttpDataSourceException",
        "CLEARTEXT communication to 192.168.1.9 not permitted by network "
            "security policy",
        "Unable to connect",
        "java.net.SocketTimeoutException: failed to connect",
        "Response code: 401",
      ]) {
        expect(
          videoErrorHint(PlatformException(code: "VideoError", message: message)),
          videoNetworkHint,
          reason: message,
        );
      }
    });

    test('reads a decoder failure as the format, even inside a source error',
        () {
      for (var message in const [
        "Decoder init failed",
        "Unable to instantiate decoder OMX.google.h265.decoder",
        "ExoPlaybackException: Renderer error",
        // The one that carries both: the bytes did arrive, and the container
        // is what nothing here can read — the format is the answer.
        "Source error: UnrecognizedInputFormatException: None of the "
            "available extractors could read the stream",
        "MediaCodecVideoRenderer: format unsupported",
      ]) {
        expect(
          videoErrorHint(PlatformException(code: "VideoError", message: message)),
          videoFormatHint,
          reason: message,
        );
      }
    });

    test('answers nothing where the message says nothing it knows', () {
      expect(videoErrorHint(StateError("no player for this URL")), isNull);
      expect(
        videoErrorHint(
          PlatformException(code: "VideoError", message: "Video player had error"),
        ),
        isNull,
      );
      expect(videoErrorHint("42"), isNull);
    });
  });
}
