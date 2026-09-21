/// Probe for the rendition playback of issue #75, composed with the answers a
/// withdrawn share link gives, odd Retry-After headers, and a rendition that
/// is pending first and then gone.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/video_view.dart';

import 'util/fake_image_http.dart';
import 'util/l10n.dart';

const String imageUrl = "http://server/valbum/data/album/clip.mp4";
const String renditionUrl = "$imageUrl?type=video";

class FakeProbe {
  final List<RenditionState> states;
  final List<String> asked = [];
  FakeProbe(this.states);
  Future<RenditionState> call(String url) async {
    asked.add(url);
    return states[asked.length > states.length ? states.length - 1 : asked.length - 1];
  }
}

void main() {
  test('a withdrawn share link is "no rendition", with the server\'s own sentence', () async {
    var client = VAlbumClient(
      dataUrl: "http://server/valbum/s/tok/data",
      token: "share-token",
      httpClient: MockClient((request) async => http.Response(
            '["ErrorInfo",{"message":"Dieser Link wurde zurückgezogen.","code":"LINK_REVOKED"}]',
            410,
          )),
    );
    var state = await client.renditionState(renditionUrl);
    expect(state.status, RenditionStatus.unavailable);
    expect(state.message, "Dieser Link wurde zurückgezogen.");
  });

  test('odd Retry-After headers never produce a storm or an endless wait', () {
    expect(VAlbumClient.retryAfterOf("0"), renditionRetryFloor);
    expect(VAlbumClient.retryAfterOf("-5"), renditionRetryFloor);
    expect(VAlbumClient.retryAfterOf(" 7 "), const Duration(seconds: 7));
    expect(VAlbumClient.retryAfterOf("99999"), renditionRetryCap);
    expect(VAlbumClient.retryAfterOf("Wed, 21 Oct 2015 07:28:00 GMT"), renditionRetryDefault);
    expect(VAlbumClient.retryAfterOf(null), renditionRetryDefault);
  });

  testWidgets('pending, then gone: the original is played after the wait', (tester) async {
    var opened = <String>[];
    var probe = FakeProbe([
      const RenditionState(RenditionStatus.pending, retryAfter: Duration(seconds: 4)),
      const RenditionState(RenditionStatus.unavailable, message: "failed"),
    ]);
    var waits = <Duration>[];
    await withFakeImageHttp(() async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: imageUrl,
          renditionUrl: renditionUrl,
          probeRendition: probe.call,
          posterUrl: "$imageUrl?type=tn",
          wait: (delay) async => waits.add(delay),
          createController: (Uri url, {Map<String, String> headers = const {}}) {
            opened.add(url.toString());
            throw StateError("no player in a test");
          },
        ),
      ));
      for (var i = 0; i < 4; i++) {
        await tester.pump();
      }
    });

    expect(probe.asked, [renditionUrl, renditionUrl]);
    expect(waits, [const Duration(seconds: 4)]);
    expect(opened, [imageUrl], reason: "the original is the fallback, never the rendition URL");
    expect(find.byKey(const Key("video-preparing")), findsNothing);
  });

  testWidgets('"Play the original" while pending opens the original at once and stops the retries',
      (tester) async {
    var opened = <String>[];
    var probe = FakeProbe([
      const RenditionState(RenditionStatus.pending, retryAfter: Duration(seconds: 30)),
    ]);
    await withFakeImageHttp(() async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: testLocalizationsDelegates,
        supportedLocales: testSupportedLocales,
        home: VideoView(
          videoUrl: imageUrl,
          renditionUrl: renditionUrl,
          probeRendition: probe.call,
          posterUrl: "$imageUrl?type=tn",
          // A wait that never ends within the test, without a timer.
          wait: (delay) => Completer<void>().future,
          createController: (Uri url, {Map<String, String> headers = const {}}) {
            opened.add(url.toString());
            throw StateError("no player in a test");
          },
        ),
      ));
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const Key("video-preparing")), findsOneWidget);
      await tester.tap(find.byKey(const Key("video-play-original")));
      await tester.pump();
      await tester.pump();
    });
    expect(opened, [imageUrl]);
    expect(probe.asked.length, 1, reason: "no further probe after the person chose the original");
    expect(find.byKey(const Key("video-preparing")), findsNothing);
  });
}
