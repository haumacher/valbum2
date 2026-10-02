/// Tests of the QuickTime, iTunes and 3GPP videos of issue #189: which file
/// the viewer may fall back to, and what it says while it may not.
///
/// A browser cannot be relied on to play a QuickTime movie (an iPhone's HEVC
/// above all) or a 3GPP file, so on the web those play through the
/// rendition the server converts them to, and the original is not offered;
/// an mp4 keeps the original as its fallback, and every platform player
/// (the app, the desktop) plays every original.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide Orientation;
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/l10n.dart';

const String movUrl = "http://server/valbum/data/album/IMG_0001.MOV";
const String renditionUrl = "$movUrl?type=video";

ImagePart video(String name, ImageKind kind) =>
    ImagePart(name: name, kind: kind, width: 1920, height: 1080);

/// A probe answering [states] in turn, the last one repeated.
class FakeProbe {
  final List<RenditionState> states;
  final List<String> asked = [];

  FakeProbe(this.states);

  Future<RenditionState> call(String url) async {
    asked.add(url);
    return states[asked.length > states.length
        ? states.length - 1
        : asked.length - 1];
  }
}

const pending = RenditionState(
  RenditionStatus.pending,
  retryAfter: Duration(seconds: 10),
);

Future<void> pumpView(
  WidgetTester tester,
  FakeProbe probe, {
  required bool originalPlayable,
  required Wait wait,
}) async {
  await withFakeImageHttp(() async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: movUrl,
          renditionUrl: renditionUrl,
          probeRendition: probe.call,
          posterUrl: "$movUrl?type=tn",
          originalPlayable: originalPlayable,
          wait: wait,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();
  });
}

void main() {
  group('whether the original plays here', () {
    test('a browser plays the original of an mp4 and an m4v', () {
      expect(
        originalPlaysHere(video("clip.mp4", ImageKind.video), isWeb: true),
        isTrue,
      );
      expect(
        originalPlaysHere(video("clip.M4V", ImageKind.video), isWeb: true),
        isTrue,
      );
    });

    test('a browser plays a QuickTime movie only through the rendition', () {
      expect(
        originalPlaysHere(
          video("IMG_0001.MOV", ImageKind.quicktime),
          isWeb: true,
        ),
        isFalse,
      );
      // The container the server read decides, not the name.
      expect(
        originalPlaysHere(
          video("renamed.mp4", ImageKind.quicktime),
          isWeb: true,
        ),
        isFalse,
      );
    });

    test('a browser plays a 3GPP file only through the rendition', () {
      expect(
        originalPlaysHere(video("clip.3gp", ImageKind.video), isWeb: true),
        isFalse,
      );
      expect(
        originalPlaysHere(video("clip.3G2", ImageKind.video), isWeb: true),
        isFalse,
      );
    });

    test('the platform player of the app plays every original', () {
      for (var part in [
        video("clip.mp4", ImageKind.video),
        video("IMG_0001.MOV", ImageKind.quicktime),
        video("clip.3gp", ImageKind.video),
      ]) {
        expect(
          originalPlaysHere(part, isWeb: false),
          isTrue,
          reason: part.name,
        );
      }
    });
  });

  group('a video whose original does not play here', () {
    late FakeVideoPlayerPlatform platform;

    setUp(() {
      platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
    });

    testWidgets('waits for the rendition without offering the original', (
      tester,
    ) async {
      await pumpView(
        tester,
        FakeProbe([pending]),
        originalPlayable: false,
        wait: (delay) => Completer<void>().future,
      );

      expect(find.byKey(const Key("video-preparing")), findsOneWidget);
      expect(find.byKey(const Key("video-play-original")), findsNothing);
      expect(
        tester
            .widget<Text>(find.byKey(const Key("video-needs-rendition")))
            .data,
        "This browser cannot play the original of this video; it plays as "
        "soon as the server has converted it.",
      );
      expect(
        tester
            .widget<Text>(find.byKey(const Key("video-preparing-retry")))
            .data,
        "Asking again in 10 seconds (2 of $renditionPendingAttemptsWithoutOriginal).",
      );
      expect(platform.dataSources, isEmpty);
    });

    testWidgets('plays the rendition once it is there', (tester) async {
      var probe = FakeProbe([
        pending,
        pending,
        const RenditionState(RenditionStatus.ready),
      ]);
      await pumpView(
        tester,
        probe,
        originalPlayable: false,
        wait: (delay) async {},
      );

      expect(probe.asked.length, 3);
      expect(platform.dataSources.single.uri, renditionUrl);
    });

    testWidgets('asks longer, and gives up offering only to ask again', (
      tester,
    ) async {
      var probe = FakeProbe([pending]);
      await pumpView(
        tester,
        probe,
        originalPlayable: false,
        wait: (delay) async {},
      );
      await tester.pump();

      expect(probe.asked.length, renditionPendingAttemptsWithoutOriginal);
      expect(find.byKey(const Key("video-error")), findsOneWidget);
      expect(find.byKey(const Key("video-try-again")), findsOneWidget);
      expect(find.byKey(const Key("video-play-original")), findsNothing);
      expect(platform.dataSources, isEmpty);
    });

    testWidgets('still falls back to the original where there will be none', (
      tester,
    ) async {
      await pumpView(
        tester,
        FakeProbe([
          const RenditionState(
            RenditionStatus.unavailable,
            message: "The video could not be converted.",
          ),
        ]),
        originalPlayable: false,
        wait: (delay) async {},
      );

      expect(platform.dataSources.single.uri, movUrl);
    });
  });

  group('a video whose original plays here', () {
    late FakeVideoPlayerPlatform platform;

    setUp(() {
      platform = FakeVideoPlayerPlatform();
      VideoPlayerPlatform.instance = platform;
    });

    testWidgets('keeps offering the original while the rendition is made', (
      tester,
    ) async {
      var probe = FakeProbe([pending]);
      await pumpView(
        tester,
        probe,
        originalPlayable: true,
        wait: (delay) async {},
      );
      await tester.pump();

      // Exactly as before issue #189: six asks, then the choice of both.
      expect(probe.asked.length, renditionPendingAttempts);
      expect(find.byKey(const Key("video-try-again")), findsOneWidget);
      expect(find.byKey(const Key("video-play-original")), findsOneWidget);
      expect(find.byKey(const Key("video-needs-rendition")), findsNothing);
    });
  });
}
