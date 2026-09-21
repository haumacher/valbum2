/// Tests of the teaser on hover (issue #75): what a video tile does while the
/// pointer rests on it, and what it does *not* do when the teaser is not there
/// or there is no pointer at all.
///
/// A teaser is a nicety. Everything here is about it staying one: it plays
/// muted and looping while the pointer is on the tile, it is gone the moment
/// the pointer leaves, and a teaser the server has not made yet shows nothing
/// and says nothing — and is asked about at most once more.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_video_player.dart';
import 'util/l10n.dart';

/// The teaser of the video of this test.
const String teaserUrl = "http://server/valbum/data/album/clip.mp4?type=teaser";

/// A probe answering [states] in turn, remembering what it was asked.
class FakeProbe {
  final List<RenditionState> states;
  final List<String> asked = [];

  FakeProbe(this.states);

  Future<RenditionState> call(String url) async {
    asked.add(url);
    var index = asked.length - 1;
    return states[index >= states.length ? states.length - 1 : index];
  }
}

/// Pumps a tile with a teaser on it.
Future<void> pumpTile(
  WidgetTester tester, {
  required FakeProbe probe,
  bool enabled = true,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      localizationsDelegates: testLocalizationsDelegates,
      supportedLocales: testSupportedLocales,
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 200,
            height: 150,
            child: VideoTeaser(
              teaserUrl: teaserUrl,
              probeTeaser: probe.call,
              headers: const {"Authorization": "Bearer dev-token"},
              // The platform decides this in the app; a widget test says it
              // outright, because the test binding reports a phone.
              enabled: enabled,
              child: const ColoredBox(
                color: Colors.grey,
                child: SizedBox.expand(key: Key("the-poster")),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

/// The [MouseRegion] the teaser itself builds, if it builds one.
final Finder hoverRegion = find.descendant(
  of: find.byType(VideoTeaser),
  matching: find.byType(MouseRegion),
);

/// Moves a mouse pointer onto the tile and off it again.
Future<TestGesture> hover(WidgetTester tester) async {
  var gesture = await tester.createGesture(kind: PointerDeviceKind.mouse);
  addTearDown(gesture.removePointer);
  await gesture.addPointer(location: Offset.zero);
  await gesture.moveTo(tester.getCenter(find.byKey(const Key("the-poster"))));
  await tester.pump();
  await tester.pump();
  await tester.pump();
  return gesture;
}

void main() {
  late FakeVideoPlayerPlatform platform;

  setUp(() {
    platform = FakeVideoPlayerPlatform();
    VideoPlayerPlatform.instance = platform;
  });

  testWidgets('plays the teaser while the pointer rests on the tile',
      (tester) async {
    var probe = FakeProbe([const RenditionState(RenditionStatus.ready)]);
    await pumpTile(tester, probe: probe);

    expect(platform.dataSources, isEmpty, reason: "nothing before the hover");

    var gesture = await hover(tester);

    expect(probe.asked, [teaserUrl]);
    expect(platform.dataSources.single.uri, teaserUrl);
    // The bearer travels with it, like every other request of this app.
    expect(
      platform.dataSources.single.httpHeaders,
      containsPair("Authorization", "Bearer dev-token"),
    );
    // Muted and looping: a tile is not a place where sound starts by itself.
    expect(platform.calls, contains("setVolume"));
    expect(platform.calls, contains("setLooping"));
    expect(platform.calls, contains("play"));
    expect(find.byKey(const Key("video-teaser")), findsOneWidget);
    // The poster is still there, underneath.
    expect(find.byKey(const Key("the-poster")), findsOneWidget);

    // And it is gone with the pointer.
    await gesture.moveTo(const Offset(5, 5));
    await tester.pump();
    // The controller's own `dispose` runs off the event loop (it cancels the
    // event subscription first), which a pumped frame does not reach.
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();

    expect(platform.calls, contains("dispose"));
    expect(find.byKey(const Key("video-teaser")), findsNothing);
    expect(find.byKey(const Key("the-poster")), findsOneWidget);
  });

  testWidgets('skips a teaser the server has not made yet, silently',
      (tester) async {
    var probe = FakeProbe([
      const RenditionState(
        RenditionStatus.pending,
        retryAfter: Duration(seconds: 10),
      ),
    ]);
    await pumpTile(tester, probe: probe);

    await hover(tester);

    expect(probe.asked, [teaserUrl]);
    expect(platform.dataSources, isEmpty, reason: "nothing was played");
    expect(find.byKey(const Key("video-teaser")), findsNothing);
    // Nothing is said about it either: a row of error boxes over an album is
    // worse than no teaser.
    expect(find.byType(Text), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('asks about a pending teaser once more, then stops asking',
      (tester) async {
    var probe = FakeProbe([
      const RenditionState(RenditionStatus.pending),
      const RenditionState(RenditionStatus.pending),
      const RenditionState(RenditionStatus.ready),
    ]);
    await pumpTile(tester, probe: probe);

    for (var round = 0; round < 3; round++) {
      var gesture = await hover(tester);
      await gesture.moveTo(const Offset(5, 5));
      await tester.pump();
      await gesture.removePointer();
      await tester.pump();
    }

    expect(
      probe.asked,
      [teaserUrl, teaserUrl],
      reason: "one retry per view, then this tile stops asking",
    );
    expect(platform.dataSources, isEmpty);
  });

  testWidgets('stops asking once the server says there will be none',
      (tester) async {
    var probe = FakeProbe([
      const RenditionState(
        RenditionStatus.unavailable,
        message: "The video could not be converted.",
      ),
      const RenditionState(RenditionStatus.ready),
    ]);
    await pumpTile(tester, probe: probe);

    for (var round = 0; round < 2; round++) {
      var gesture = await hover(tester);
      await gesture.moveTo(const Offset(5, 5));
      await tester.pump();
      await gesture.removePointer();
      await tester.pump();
    }

    expect(probe.asked, [teaserUrl], reason: "a final answer is final");
    expect(platform.dataSources, isEmpty);
  });

  testWidgets('is not built at all where there is no pointer', (tester) async {
    var probe = FakeProbe([const RenditionState(RenditionStatus.ready)]);
    await pumpTile(tester, probe: probe, enabled: false);

    // The tile is the tile: no mouse region of its own, nothing to hover,
    // nothing asked. (The `MouseRegion`s a `Scaffold` builds are none of this
    // widget's doing, so the finder looks below it.)
    expect(find.byKey(const Key("the-poster")), findsOneWidget);
    expect(hoverRegion, findsNothing);

    await hover(tester);

    expect(probe.asked, isEmpty);
    expect(platform.dataSources, isEmpty);
  });

  testWidgets('shows only the tile where nobody can be asked', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoTeaser(
          teaserUrl: teaserUrl,
          child: SizedBox.expand(key: Key("the-poster")),
        ),
      ),
    );
    await tester.pump();

    expect(find.byKey(const Key("the-poster")), findsOneWidget);
    expect(hoverRegion, findsNothing);
  });

  test('the teaser is a pointer affordance, not a touch one', () {
    // The rule, stated where it can be read: everywhere but Android and iOS —
    // which is also right on the web, because a phone browser reports itself
    // as one of those two. See [teaserOnHoverSupported] for why there is no
    // substitute on a touch platform.
    for (var target in TargetPlatform.values) {
      debugDefaultTargetPlatformOverride = target;
      expect(
        teaserOnHoverSupported(),
        target != TargetPlatform.android && target != TargetPlatform.iOS,
        reason: "$target",
      );
    }
    debugDefaultTargetPlatformOverride = null;
  });
}
