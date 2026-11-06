/// Nothing is asked of the server by a timer while the app is in the
/// background (issue #317): the bell and the status poller stop there, and a
/// resume asks once at once and starts the interval afresh.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/foreground_timer.dart';
import 'package:valbum_ui/main.dart';
import 'package:valbum_ui/news.dart';

import 'util/lifecycle.dart';

/// A member's client that counts the questions of [type].
class _Counting {
  int asked = 0;

  final String type;

  final String answer;

  _Counting(this.type, this.answer);

  late final VAlbumClient client = VAlbumClient(
    dataUrl: "http://server/valbum/data",
    token: "carol-token",
    httpClient: MockClient((request) async {
      if (request.url.queryParameters["type"] == type) {
        asked++;
      }
      return http.Response(answer, 200,
          headers: {"content-type": "application/json; charset=utf-8"});
    }),
  );
}

const Duration _interval = Duration(minutes: 3);

/// Lets [time] pass, and the answers it asked for arrive.
Future<void> _wait(WidgetTester tester, Duration time) async {
  await tester.pump(time);
  await tester.pump();
}

void main() {
  group('a foreground timer', () {
    testWidgets('ticks every interval while the app can be seen',
        (tester) async {
      resume(tester);
      var ticks = 0;
      var timer = ForegroundTimer(() => ticks++)..start(_interval);

      await _wait(tester, _interval * 3);
      expect(ticks, 3);
      timer.dispose();
    });

    testWidgets('stops in the background, and ticks at once on resume',
        (tester) async {
      resume(tester);
      var ticks = 0;
      var timer = ForegroundTimer(() => ticks++)..start(_interval);

      await _wait(tester, _interval);
      expect(ticks, 1);

      toBackground(tester);
      expect(timer.ticking, isFalse);
      expect(timer.started, isTrue);
      await _wait(tester, _interval * 10);
      expect(ticks, 1, reason: "nothing while in the background");

      toForeground(tester);
      expect(ticks, 2, reason: "once at once");
      await _wait(tester, _interval - const Duration(seconds: 1));
      expect(ticks, 2, reason: "the interval starts afresh");
      await _wait(tester, const Duration(seconds: 1));
      expect(ticks, 3);
      timer.dispose();
    });

    testWidgets('started in the background, first ticks on resume',
        (tester) async {
      resume(tester);
      toBackground(tester);
      var ticks = 0;
      var timer = ForegroundTimer(() => ticks++)..start(_interval);

      await _wait(tester, _interval * 5);
      expect(ticks, 0);

      toForeground(tester);
      expect(ticks, 1);
      await _wait(tester, _interval);
      expect(ticks, 2);
      timer.dispose();
    });

    testWidgets('stopped, ticks nothing on resume either', (tester) async {
      resume(tester);
      var ticks = 0;
      var timer = ForegroundTimer(() => ticks++)..start(_interval);
      timer.stop();

      toBackground(tester);
      toForeground(tester);
      await _wait(tester, _interval * 3);
      expect(ticks, 0);
      timer.dispose();
    });
  });

  group('the bell', () {
    testWidgets('asks nothing in the background, and at once on resume',
        (tester) async {
      resume(tester);
      var server = _Counting("news-count", '{"count": 2}');
      var bell = NewsBell(interval: _interval);

      bell.attach(server.client);
      await tester.pump();
      expect(server.asked, 1, reason: "asked at once");
      expect(bell.count, 2);

      await _wait(tester, _interval);
      expect(server.asked, 2);

      toBackground(tester);
      await _wait(tester, _interval * 10);
      expect(server.asked, 2, reason: "nothing while in the background");

      toForeground(tester);
      await tester.pump();
      expect(server.asked, 3, reason: "once at once on resume");

      await _wait(tester, _interval - const Duration(seconds: 1));
      expect(server.asked, 3, reason: "the interval starts afresh");
      await _wait(tester, const Duration(seconds: 1));
      expect(server.asked, 4);
      await _wait(tester, _interval);
      expect(server.asked, 5, reason: "and goes on");
      bell.dispose();
    });

    testWidgets('detached, asks nothing on resume', (tester) async {
      resume(tester);
      var server = _Counting("news-count", '{"count": 0}');
      var bell = NewsBell(interval: _interval);
      bell.attach(server.client);
      await tester.pump();
      bell.attach(null);

      toBackground(tester);
      toForeground(tester);
      await _wait(tester, _interval * 3);
      expect(server.asked, 1);
      bell.dispose();
    });
  });

  group('the status poller', () {
    testWidgets('asks nothing in the background, and at once on resume',
        (tester) async {
      resume(tester);
      var server = _Counting("status", '{"admin": false}');
      var monitor = StatusMonitor(interval: _interval);

      monitor.attach(server.client);
      await tester.pump();
      expect(server.asked, 1, reason: "asked at once");

      await _wait(tester, _interval);
      expect(server.asked, 2);

      toBackground(tester);
      await _wait(tester, _interval * 10);
      expect(server.asked, 2, reason: "nothing while in the background");

      toForeground(tester);
      await tester.pump();
      expect(server.asked, 3, reason: "once at once on resume");

      await _wait(tester, _interval - const Duration(seconds: 1));
      expect(server.asked, 3, reason: "the interval starts afresh");
      await _wait(tester, const Duration(seconds: 1));
      expect(server.asked, 4);
      await _wait(tester, _interval);
      expect(server.asked, 5, reason: "and goes on");
      monitor.dispose();
    });

    testWidgets('the page closed in the background starts no timer',
        (tester) async {
      resume(tester);
      var server = _Counting("status", '{"admin": false}');
      var monitor = StatusMonitor(
          interval: _interval, pageInterval: const Duration(seconds: 5));
      monitor.attach(server.client);
      var unwatch = monitor.watch();
      await tester.pump();

      toBackground(tester);
      var before = server.asked;
      // The page is left while the app is in the background.
      unwatch();
      await _wait(tester, _interval * 5);
      expect(server.asked, before);

      toForeground(tester);
      await tester.pump();
      expect(server.asked, before + 1);
      monitor.dispose();
    });
  });
}
