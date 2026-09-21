/// Probe for the speaking video error of issue #73, composed with the real
/// messages Android produces, a share-link URL that must not leak into the
/// log, and a view without a log.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/diagnostics.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/l10n.dart';

const String tokenUrl =
    "http://nas.local:8080/valbum/s/SECRETTOKEN123/data/album/clip.mp4";

VideoControllerFactory failingWith(Object problem) => (
      Uri url, {
      Map<String, String> headers = const {},
    }) =>
        throw problem;

Future<void> pumpFailing(WidgetTester tester, Object problem,
    {DiagnosticsLog? log, String url = tokenUrl}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: VideoView(
        videoUrl: url,
        posterUrl: "$url?type=tn",
        createController: failingWith(problem),
        log: log,
      ),
    ));
    await tester.pump();
    await tester.pump();
  });
}

String shown(WidgetTester tester) => tester
    .widgetList<Text>(find.descendant(
        of: find.byKey(const Key("video-error")), matching: find.byType(Text)))
    .map((t) => t.data ?? "")
    .join("\n");

void main() {
  test('classifies the messages Android really produces', () {
    expect(
      videoErrorHint(testL10n, PlatformException(
          code: "VideoError",
          message:
              "Video player had error com.google.android.exoplayer2.ExoPlaybackException: Source error")),
      "The server could not be reached, or it refused the video.",
    );
    expect(
      videoErrorHint(testL10n, "java.io.IOException: Cleartext HTTP traffic to 192.168.1.5 not permitted"),
      "The server could not be reached, or it refused the video.",
    );
    expect(
      videoErrorHint(testL10n, "ExoPlaybackException: Source error ... UnrecognizedInputFormatException: None of the available extractors could read the stream"),
      "This device cannot play the format of this video.",
      reason: "an unreadable container is a format problem even though ExoPlayer calls it a source error",
    );
    expect(
      videoErrorHint(testL10n, "MediaCodecVideoRenderer error, index=0, format=Format(...)"),
      "This device cannot play the format of this video.",
    );
    expect(videoErrorHint(testL10n, "boom"), isNull);
    expect(
      videoErrorHint(testL10n, StateError("something else entirely")),
      isNull,
    );
  });

  testWidgets('a share token in the video URL never reaches the log or the screen raw',
      (tester) async {
    var log = DiagnosticsLog();
    await pumpFailing(
      tester,
      PlatformException(code: "VideoError", message: "Video player had error: Source error"),
      log: log,
    );

    expect(log.entries, hasLength(1));
    expect(log.entries.single.message, isNot(contains("SECRETTOKEN123")));
    expect(log.entries.single.message, contains("Source error"));
    var text = shown(tester);
    expect(text, contains("Cannot play this video."));
    expect(text, contains("The server could not be reached, or it refused the video."));
    expect(text, isNot(contains("PlatformException")));
    expect(text, isNot(contains("VideoError")));
  });

  testWidgets('an unclassified failure shows no hint line and a view without a log still speaks',
      (tester) async {
    await pumpFailing(tester, StateError("boom"));
    expect(find.byKey(const Key("video-error-hint")), findsNothing);
    expect(shown(tester), contains("Cannot play this video."));
    expect(shown(tester), isNot(contains("boom")));
    expect(tester.takeException(), isNull);
  });
}
