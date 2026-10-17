/// Tests of the relay a phone's runs meet in (issue #300): the claim at the
/// isolate name server, a claim left behind by an engine that is gone, the
/// statuses the app on the screen watches, and the Stop that reaches the run.
///
/// One isolate stands in for several: the name server and the ports behave
/// the same between the isolates of one process.
library;

import 'dart:isolate';
import 'dart:ui' show IsolateNameServer;

import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/sync_relay.dart';
import 'package:valbum_ui/sync_relay_io.dart';

void main() {
  tearDown(() {
    IsolateNameServer.removePortNameMapping(claimPortName);
    IsolateNameServer.removePortNameMapping(watchPortName);
  });

  test('one claim at a time, the next one after the release', () async {
    var relay = IsolateSyncRelay();
    var first = await relay.claim(task: "a", patience: Duration.zero);
    expect(first, isNotNull);

    expect(await relay.claim(task: "b", patience: Duration.zero), isNull);

    await first!.release();
    var second = await relay.claim(task: "b", patience: Duration.zero);
    expect(second, isNotNull);
    await second!.release();
  });

  test('a waiting run gets the claim once the holder releases', () async {
    var relay = IsolateSyncRelay();
    var first = await relay.claim(task: "a", patience: Duration.zero);
    var waiting = relay.claim(task: "b", patience: const Duration(seconds: 10));

    await Future<void>.delayed(const Duration(milliseconds: 100));
    await first!.release();

    var second = await waiting;
    expect(second, isNotNull);
    await second!.release();
  });

  test('a claim left behind by an engine that is gone is taken over', () async {
    var gone = ReceivePort();
    IsolateNameServer.registerPortWithName(gone.sendPort, claimPortName);
    gone.close();

    var claim =
        await IsolateSyncRelay().claim(task: "a", patience: Duration.zero);

    expect(claim, isNotNull);
    await claim!.release();
  });

  test('the app watches what the run says, and its Stop reaches the run',
      () async {
    var relay = IsolateSyncRelay();
    var messages = <RelayMessage>[];
    var watch = relay.watch().listen(messages.add);
    await Future<void>.delayed(Duration.zero);

    var claim = await relay.claim(task: "upload", patience: Duration.zero);
    var stops = 0;
    claim!.stopRequests.listen((_) => stops++);

    claim.publish({"phase": "running", "done": 1, "total": 3});
    claim.publish({"phase": "idle"}, finished: true, retryable: true);
    expect(await relay.requestStop(), isTrue);
    await Future<void>.delayed(const Duration(milliseconds: 50));

    expect([for (var message in messages) message.task], ["upload", "upload"]);
    expect(messages.first.status["done"], 1);
    expect(messages.last.finished, isTrue);
    expect(messages.last.retryable, isTrue);
    expect(stops, 1);

    await claim.release();
    expect(await relay.requestStop(), isFalse);
    await watch.cancel();
    expect(IsolateNameServer.lookupPortByName(watchPortName), isNull);
  });
}
